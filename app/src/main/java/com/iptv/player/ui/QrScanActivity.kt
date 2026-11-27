package com.iptv.player.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.AttributeSet
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.iptv.player.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 相机扫二维码。
 *
 * 为什么不用 zxing 自带的 CaptureActivity：
 * 它把解码区域限制在「取景框裁出来的那一小块图」上，框大一点识别慢，
 * 框小一点就必须把码精确怼进框里，用户感受就是「怎么都扫不出来」。
 * 这里换成 CameraX 取景 + MLKit 识别，MLKit 是**整帧**识别，
 * 取景框只负责给用户一个「往这儿放」的视觉引导，码稍微偏出框、斜着拿、
 * 屏幕反光都还能认出来，识别距离也更宽。
 *
 * 结果通过 [EXTRA_RESULT] 回传，调用方只认 http/https，见 SourceManagerDialog.handleScanned。
 */
class QrScanActivity : AppCompatActivity() {

    companion object {
        /** 扫到的二维码原文 */
        const val EXTRA_RESULT = "scan_result"

        /** 取景页的提示语，由调用方按场景传入 */
        const val EXTRA_PROMPT = "scan_prompt"
    }

    private lateinit var previewView: PreviewView
    private lateinit var scanner: BarcodeScanner
    private var analysisExecutor: ExecutorService? = null
    /** 绑定成功后的 Camera，点屏幕变焦要用它的 cameraControl */
    private var camera: Camera? = null
    /** 当前变焦档位下标（1x / 2x / 3x） */
    private var zoomIndex = 0
    /**
     * 同一时刻只放一帧进 MLKit。
     * 单线程 executor 只是保证回调不并发，analyze 里是异步的，
     * 不卡住就会不断取新帧、同时压好几帧给 MLKit，ImageProxy 池被占满后
     * CameraX 直接停止出帧（画面定住）。
     */
    private val analyzing = AtomicBoolean(false)

    /** 已经出结果并正在关闭：防止连续多帧重复回调和重复 finish（跨线程读写，加 Volatile） */
    @Volatile
    private var finished = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                // 拒绝授权时给一句话再退回源管理页，别停在一块黑屏上什么都不发生
                Toast.makeText(this, R.string.source_scan_no_camera, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 预览铺满整块屏幕：状态栏留着会压掉一条取景画面
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.activity_qr_scan)

        previewView = findViewById(R.id.previewView)
        intent.getStringExtra(EXTRA_PROMPT)?.takeIf { it.isNotBlank() }?.let {
            findViewById<TextView>(R.id.tvScanPrompt).text = it
        }
        findViewById<View>(R.id.btnScanCancel).setOnClickListener { finish() }
        // 点取景区域切换变焦：镜头够不到的时候不用来回挪手机
        findViewById<View>(R.id.scanFrame).setOnClickListener { cycleZoom() }

        scanner = BarcodeScanning.getClient(
            // 只认二维码：模型只跑一种码制，出结果更快
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val executor = Executors.newSingleThreadExecutor()
                analysisExecutor = executor

                // 先按高分辨率绑，个别机型不支持这个组合时退回默认分辨率，
                // 避免某些老设备摄像头直接打不开
                val bound = bind(provider, preview, executor, highResolution = true)
                    ?: bind(provider, preview, executor, highResolution = false)
                    ?: throw IllegalStateException("camera bind failed")
                camera = bound
            } catch (t: Throwable) {
                Toast.makeText(this, R.string.source_scan_no_camera, Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 绑定预览 + 分析。
     *
     * [highResolution] = true 时把分析帧提到 1280×720。
     * CameraX 的 ImageAnalysis 默认只给 640×480，稍远一点的二维码在帧里只有
     * 几十个像素宽，MLKit 根本认不出来 —— 这正是「离得近才能扫上」的原因。
     * 帧分辨率翻上去，同一个码的像素数变成四倍，正常距离就能识别。
     */
    private fun bind(
        provider: ProcessCameraProvider,
        preview: Preview,
        executor: ExecutorService,
        highResolution: Boolean
    ): Camera? = try {
        val builder = ImageAnalysis.Builder()
            // 识别跟不上时丢旧帧，保证画面和处理都不积压
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        if (highResolution) {
            builder.setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
        }
        val analysis = builder.build()
        analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }
        provider.unbindAll()
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    } catch (t: Throwable) {
        null
    }

    /**
     * 点屏幕切换变焦：1x → 2x → 3x → 1x。
     *
     * 高分辨率帧解决「稍远」，变焦解决「再远一点」。
     * 上限取设备支持的最大倍率，避免在只支持 2x 的机器上设 3x 被拒。
     */
    private fun cycleZoom() {
        val cam = camera ?: return
        // 用本地下标轮转，不读 zoomRatio：setZoomRatio 是异步的，
        // 连点两下时读回来的还是旧值，倍率会跳不上去
        val max = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        val steps = listOf(1f, 2f, 3f).filter { it <= max + 0.01f }.ifEmpty { listOf(1f) }
        zoomIndex = (zoomIndex + 1) % steps.size
        val next = steps[zoomIndex]
        cam.cameraControl.setZoomRatio(next)
        Toast.makeText(
            this,
            getString(R.string.scan_zoom_toast, next.toInt()),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 每一帧交给 MLKit 识别。
     *
     * 无论成功还是失败都必须关掉 ImageProxy，否则分析管线会堵死 ——
     * 表现为画面还活着但永远不再识别，是 CameraX 最经典的坑。
     */
    private fun analyze(proxy: ImageProxy) {
        if (finished || !analyzing.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        val media = proxy.image
        if (media == null) {
            analyzing.set(false)
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { codes ->
                if (finished) return@addOnSuccessListener
                val content = codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                if (!content.isNullOrBlank()) {
                    finished = true
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT, content))
                    finish()
                }
            }
            .addOnCompleteListener {
                proxy.close()
                analyzing.set(false)
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        finished = true
        analysisExecutor?.shutdown()
        if (::scanner.isInitialized) scanner.close()
    }
}

/**
 * 取景引导框：框外压暗 + 四角高亮。
 *
 * 纯装饰，不参与识别 —— 识别始终针对整帧画面，所以码放在框边、
 * 甚至稍稍超出框，也一样能扫上。
 */
class ScanFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val maskPaint = Paint().apply {
        color = 0x9E000000.toInt()
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // 取景框四角：原来是一支独立的青色 #2EE6D6，全项目再无第二处用到，
        // 截图里跟品牌蓝各说各话。统一到提亮版品牌蓝，暗画面上的醒目度也不降。
        color = Color.parseColor("#4DA3FF")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val side = minOf(width, height) * 0.76f
        if (side <= 0f) return
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        val right = left + side
        val bottom = top + side

        // 框外压暗：画四块矩形，避免 saveLayer 在低端机型上开销过大
        canvas.drawRect(0f, 0f, width.toFloat(), top, maskPaint)
        canvas.drawRect(0f, bottom, width.toFloat(), height.toFloat(), maskPaint)
        canvas.drawRect(0f, top, left, bottom, maskPaint)
        canvas.drawRect(right, top, width.toFloat(), bottom, maskPaint)

        canvas.drawRect(left, top, right, bottom, borderPaint)

        // 四个直角，比整圈描边更像「扫码框」
        val len = side * 0.16f
        canvas.drawLine(left, top, left + len, top, cornerPaint)
        canvas.drawLine(left, top, left, top + len, cornerPaint)
        canvas.drawLine(right, top, right - len, top, cornerPaint)
        canvas.drawLine(right, top, right, top + len, cornerPaint)
        canvas.drawLine(left, bottom, left + len, bottom, cornerPaint)
        canvas.drawLine(left, bottom, left, bottom - len, cornerPaint)
        canvas.drawLine(right, bottom, right - len, bottom, cornerPaint)
        canvas.drawLine(right, bottom, right, bottom - len, cornerPaint)
    }
}

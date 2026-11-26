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
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
                val analysis = ImageAnalysis.Builder()
                    // 识别跟不上时丢旧帧，保证画面和处理都不积压
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val executor = Executors.newSingleThreadExecutor()
                analysisExecutor = executor
                analysis.setAnalyzer(executor) { proxy -> analyze(proxy) }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (t: Throwable) {
                Toast.makeText(this, R.string.source_scan_no_camera, Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 每一帧交给 MLKit 识别。
     *
     * 无论成功还是失败都必须关掉 ImageProxy，否则分析管线会堵死 ——
     * 表现为画面还活着但永远不再识别，是 CameraX 最经典的坑。
     */
    private fun analyze(proxy: ImageProxy) {
        if (finished) {
            proxy.close()
            return
        }
        val media = proxy.image
        if (media == null) {
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
            .addOnCompleteListener { proxy.close() }
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
        color = Color.parseColor("#2EE6D6")
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

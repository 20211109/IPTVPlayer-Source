package com.iptv.player.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 设备自带解码器缺失时的兜底播放器，基于 libVLC。
 *
 * 为什么从 IJK 换成 VLC：
 * IJK 官方/第三方预编译的 FFmpeg 通常只编进 aac/flac/mp3 —— 本项目原来用的
 * `ijkplayer-ex_so:0.0.16` 实测连 AC-3、MP2 都没有（音轨被静默丢弃，于是
 * 「有画面没声音」且不报错）。VLC 的 FFmpeg 是完整构建，mp2 / mp2float /
 * AC-3 / E-AC-3 / DTS / TrueHD 全都在，且对 HLS、MPEG-TS 的各种非标写法
 * 容错更好，一个内核就能兜住所有音频兼容问题。
 *
 * 代价是体积：libvlc 的 so 是 monolith（arm64 约 52MB，压缩后约 23MB），
 * 所以它只在「Exo 明确解不了音频」时才加载，正常播放仍走 ExoPlayer。
 *
 * ── 关于 vout（视频输出）的线程与尺寸，两个已修过的坑 ──────────────
 * 1) 画面缩在左上角：libvlc 是拿 `setWindowSize()` 给的窗口尺寸去算缩放的，
 *    这个值必须在 surface 尺寸变化时（横竖屏切换、手势条显隐、分屏）同步更新。
 *    3.6.x 对「已在运行的 vout」调 setWindowSize 并不一定生效，所以这里改成
 *    **按新尺寸重建整条管线**（同一套代码路径，起播什么样重建后就什么样）。
 * 2) 横屏后界面假死、返回键点不动：旋转的那一瞬间 libvlc 正在重建 vout 并持有
 *    锁，此时主线程同步调用 setVideoScale / stop 会一直等下去 —— 画面还在动，
 *    但整个界面完全没响应，只能强杀。所以所有 vout 相关调用都丢到 [voutThread]
 *    串行执行，即使阻塞也只阻塞那条后台线程。
 */
class VlcFallbackPlayer(
    private val context: Context,
    private val host: ViewGroup,
    private val onPrepared: () -> Unit,
    private val onFailed: (String) -> Unit,
    /** 尺寸变化需要重建时回调（界面可以借机显示一下「正在重连」） */
    private val onRestarting: (() -> Unit)? = null
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 所有会碰 VLC 视频输出（vout）的调用都在这条后台线程上串行执行：
     * libvlc 对 vout 的操作是带锁的，主线程一旦阻塞就是「画面在动、按钮全死」。
     */
    private val voutThread: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vlc-vout").apply { isDaemon = true }
    }

    private var surfaceView: SurfaceView? = null
    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null

    private var zoomMode = "fit"
    private var zoomScalePct = 100

    private var currentUrl: String? = null

    /** 已释放或已失败：所有异步回调都要立刻失效，避免释放后仍回调界面 */
    private var released = false

    /** 每次起播/重建自增：上一代的 VLC 回调（在途的 Playing / Error）全部作废 */
    private var generation = 0

    /** 已交给 VLC 的窗口尺寸（= surface 的实际像素尺寸），用来判断是否需要重建 */
    private var voutWidth = 0
    private var voutHeight = 0
    private var lastRestartAt = 0L
    private var restartPending = false

    /** host 尺寸变化（横竖屏切换）时也要重算自定义缩放的视图尺寸 */
    private val hostLayoutListener = View.OnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
        if (r - l > 0 && b - t > 0) onHostResized()
    }

    private val restartRunnable = Runnable {
        restartPending = false
        if (released) return@Runnable
        val sv = surfaceView ?: return@Runnable
        if (sv.width <= 0 || sv.height <= 0) return@Runnable
        // 尺寸其实没变（例如只是键盘弹出引起的微小抖动）就不折腾
        if (abs(sv.width - voutWidth) < 8 && abs(sv.height - voutHeight) < 8) return@Runnable
        restartNow()
    }

    init {
        host.addOnLayoutChangeListener(hostLayoutListener)
    }

    /**
     * 起播。返回 false 表示内核不可用（例如 ABI 不匹配、so 缺失），
     * 调用方应直接回退到「无声播放」而不要再等超时。
     *
     * 真正的 LibVLC 初始化被推迟到 surface 量好尺寸之后 —— 用 0 尺寸 attach，
     * VLC 会按视频原始分辨率 1:1 画在左上角（就是「只剩左上角一点点」的成因）。
     */
    fun play(url: String): Boolean {
        released = false
        currentUrl = url
        lastRestartAt = 0L
        beginSession(url)
        return true
    }

    /** 暂停/继续；返回切换后的状态（true = 正在播放） */
    /**
     * 当前出画面用的 SurfaceView：界面侧做黑边采样时要用它（PixelCopy 只能拷贝 SurfaceView）。
     * 重建管线期间会短暂为 null，调用方按「这轮跳过」处理即可。
     */
    fun videoSurface(): SurfaceView? = surfaceView

    fun togglePlayPause(): Boolean {
        val mp = player ?: return false
        return try {
            if (mp.isPlaying) {
                mp.pause()
                false
            } else {
                mp.play()
                true
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** 画面比例：VLC 自己有缩放策略，比手工裁切更稳，这里只做模式映射 */
    fun applyLayout(mode: String, scalePct: Int) {
        val changed = zoomMode != mode || zoomScalePct != pctSafe(scalePct)
        zoomMode = mode
        zoomScalePct = pctSafe(scalePct)
        layoutSurface()
        // 比例/缩放变化会改变 surface 尺寸，必须按新尺寸重建 vout 才能生效
        if (changed && player != null) scheduleRestart()
    }

    /**
     * host 尺寸变化（旋转、进/出全屏）后由界面调用。
     * surface 自己的布局回调一般也会触发，这里是双保险：宿主宽度变了但 surface
     * 因为固定尺寸（自定义缩放）没有重排时，也能被测到。
     */
    fun onHostResized() {
        if (released) return
        if (zoomMode == "scale") layoutSurface()
        if (player != null) scheduleRestart()
    }

    private fun pctSafe(v: Int): Int = v.coerceIn(50, 200)

    /** 起一条全新的会话：先挂 surface，等它拿到真实尺寸后再初始化 VLC */
    private fun beginSession(url: String) {
        val g = ++generation
        val sv = SurfaceView(context).apply {
            // 兜底内核的 surface 只负责出画面：不能抢焦点、不能吃触摸，
            // 否则在部分机型上会把上层按钮的点击吞掉。
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            layoutParams = buildLayoutParams()
        }
        host.addView(sv)
        surfaceView = sv
        sv.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val curve = v as SurfaceView
            if (curve !== surfaceView) return@addOnLayoutChangeListener
            onSurfaceResized(r - l, b - t)
        }
        // post 而不是立即执行：等这一帧布局结束，surface 才有真实宽高
        sv.post { if (!released && g == generation) startEngine(url, g) }
    }

    private fun startEngine(url: String, g: Int) {
        if (released || g != generation) return
        val sv = surfaceView ?: return
        try {
            val w = if (sv.width > 0) sv.width else fallbackWidth()
            val h = if (sv.height > 0) sv.height else fallbackHeight()
            voutWidth = w
            voutHeight = h

            // 直播场景的取舍：宁可多缓存一点也别丢帧跳帧，
            // 「--no-drop-late-frames/--no-skip-frames」能避免弱源上出现马赛克跳帧。
            val vlc = LibVLC(context, ArrayList(VLC_ARGS))
            libVlc = vlc
            val mp = MediaPlayer(vlc)
            player = mp

            // VLC 的默认音频输出就是 AudioTrack，不需要额外指定；
            // 但如果系统音量被上一次播放器压到 0，这里显式拉回满音量。
            submitVout { runCatching { mp.setVolume(100) } }

            val vout = mp.getVLCVout()
            vout.setVideoView(sv)
            vout.setWindowSize(w, h)
            vout.attachViews()

            mp.setEventListener(object : MediaPlayer.EventListener {
                // 注意：3.6.x 这里声明的是 MediaPlayer.Event；3.7.x 改成了 AbstractVLCEvent，
                // 本项目锁 3.6.5（3.7 要求 compileSdk 36），所以必须用具体子类做参数。
                override fun onEvent(event: MediaPlayer.Event) {
                    if (released || g != generation) return
                    when (event.type) {
                        // Playing 才是「真的有输出了」：此时音频/视频轨都已解出来，
                        // 不像 IJK 的 prepared 那样只代表容器解析成功。
                        MediaPlayer.Event.Playing -> mainHandler.post {
                            if (!released && g == generation) {
                                applyScaleTypeAsync()
                                layoutSurface()
                                onPrepared()
                            }
                        }

                        MediaPlayer.Event.EncounteredError -> mainHandler.post {
                            if (!released && g == generation) fail("内核无法解码该流")
                        }
                    }
                }
            })

            val media = Media(vlc, Uri.parse(url))
            // 视频优先硬解，不允许时自动退回软解；音频始终由 FFmpeg 解码（MP2/AC-3 靠这个）
            media.setHWDecoderEnabled(true, true)
            media.addOption(":network-caching=$NETWORK_CACHING_MS")
            media.addOption(":live-caching=$NETWORK_CACHING_MS")
            mp.setMedia(media)
            media.release()
            mp.play()
        } catch (t: Throwable) {
            fail("兼容内核不可用：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun fallbackWidth(): Int =
        if (host.width > 0) host.width else context.resources.displayMetrics.widthPixels

    private fun fallbackHeight(): Int =
        if (host.height > 0) host.height else context.resources.displayMetrics.heightPixels

    /** surface 尺寸变了（旋转最典型）：VLC 按旧尺寸算缩放会把画面画在左上角，必须重建 */
    private fun onSurfaceResized(w: Int, h: Int) {
        if (released || player == null) return
        if (w <= 0 || h <= 0) return
        if (abs(w - voutWidth) < 8 && abs(h - voutHeight) < 8) return
        // 不在这里直接重建：surface 在旋转/键盘弹出时会连续变几次尺寸，
        // 交给 scheduleRestart 防抖 + 到点再确认一次尺寸是否真的变了。
        scheduleRestart()
    }

    private fun scheduleRestart() {
        if (released || restartPending) return
        restartPending = true
        // 与上一次重建至少间隔 RESTART_COOLDOWN_MS，避免尺寸抖动导致反复重建
        val wait = maxOf(
            RESTART_DELAY_MS,
            RESTART_COOLDOWN_MS - (System.currentTimeMillis() - lastRestartAt)
        )
        mainHandler.postDelayed(restartRunnable, wait)
    }

    /** 用新尺寸重建整条 VLC 管线（旧实例在后台线程安静销毁，不阻塞界面） */
    private fun restartNow() {
        val url = currentUrl ?: return
        if (released) return
        lastRestartAt = System.currentTimeMillis()
        onRestarting?.invoke()
        abortCurrent()
        beginSession(url)
    }

    private fun applyScaleTypeAsync() {
        val mp = player ?: return
        val scale = when (zoomMode) {
            "crop" -> MediaPlayer.ScaleType.SURFACE_FILL
            "stretch" -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        }
        // 关键：不在主线程同步调用。vout 正在重建时这一步会阻塞，
        // 一旦卡在主线程就是「画面还在动、返回键按不动」。
        submitVout { runCatching { mp.setVideoScale(scale) } }
    }

    private fun buildLayoutParams(): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.CENTER
        )
        if (zoomMode == "scale") {
            val hostW = if (host.width > 0) host.width else fallbackWidth()
            val hostH = if (host.height > 0) host.height else fallbackHeight()
            if (hostW > 0 && hostH > 0) {
                val factor = zoomScalePct / 100f
                lp.width = (hostW * factor).toInt().coerceAtLeast(1)
                lp.height = (hostH * factor).toInt().coerceAtLeast(1)
            }
        }
        return lp
    }

    /** 只有「自定义缩放」需要改视图尺寸；fit/crop/stretch 交给 VLC 自己算 */
    private fun layoutSurface() {
        if (released) return
        val sv = surfaceView ?: return
        val lp = sv.layoutParams as? FrameLayout.LayoutParams ?: return
        val want = buildLayoutParams()
        if (lp.width == want.width && lp.height == want.height) return
        sv.layoutParams = want
    }

    /** 失败收尾：释放自身并把原因交回界面，由界面决定回退到「无声播放」 */
    private fun fail(reason: String) {
        if (released) return
        release()
        onFailed(reason)
    }

    fun release() {
        if (released) return
        released = true
        restartPending = false
        mainHandler.removeCallbacks(restartRunnable)
        try {
            host.removeOnLayoutChangeListener(hostLayoutListener)
        } catch (t: Throwable) {
            // 忽略
        }
        abortCurrent()
        try {
            // 已提交的销毁任务仍会执行，只是不再接受新任务
            voutThread.shutdown()
        } catch (t: Throwable) {
            // 忽略
        }
    }

    /** 立刻弃用当前实例：界面层马上换回来，原生对象交给后台线程慢慢收尸 */
    private fun abortCurrent() {
        generation++
        val mp = player
        val vlc = libVlc
        val sv = surfaceView
        player = null
        libVlc = null
        surfaceView = null
        voutWidth = 0
        voutHeight = 0
        if (sv != null) {
            try {
                sv.visibility = View.GONE
            } catch (t: Throwable) {
                // 忽略
            }
            try {
                host.removeView(sv)
            } catch (t: Throwable) {
                // 忽略
            }
        }
        if (mp == null && vlc == null) return
        submitVout {
            // 顺序很关键：先摘监听，再 stop，最后 release。
            // 这三步在部分机型上会阻塞数秒，所以绝不能放在主线程。
            runCatching { mp?.setEventListener(null) }
            runCatching { mp?.stop() }
            runCatching { mp?.release() }
            runCatching { vlc?.release() }
        }
    }

    private fun submitVout(block: () -> Unit) {
        try {
            voutThread.execute(block)
        } catch (t: Throwable) {
            // 线程池已关闭（释放之后），直接丢弃
        }
    }

    private companion object {
        /** 直播缓存（毫秒）：太小会频繁缓冲，太大换台延迟明显，1200 在盒子上比较平衡 */
        const val NETWORK_CACHING_MS = 1200

        /** 尺寸变化后延迟多久重建：留一帧给布局稳定 */
        const val RESTART_DELAY_MS = 420L

        /** 两次重建的最小间隔，防止尺寸抖动导致反复重建 */
        const val RESTART_COOLDOWN_MS = 2500L

        val VLC_ARGS = listOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--audio-time-stretch",
            "--no-sub-autodetect-file"
        )
    }
}

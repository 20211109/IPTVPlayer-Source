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
    private var player: MediaPlayer? = null

    private var zoomMode = "fit"
    private var zoomScalePct = 100

    /**
     * 自动去黑边的结果（true = 让 VLC 自己裁切铺满）。
     *
     * 关键：这是通过 `setVideoScale(SURFACE_FILL)` 实现的，**不改 SurfaceView 尺寸**。
     * 改尺寸会让 libvlc 重算 vout 并重建整条管线（2–5 秒），而黑边检测恰好发生在
     * 起播 3.5 秒后 —— 那就是「切到软解后播 5 秒左右卡住」的真正原因。
     */
    private var autoFill = false

    /**
     * 「拉伸铺满」时的目标显示比例（宽 × 高，0 = 不干预）。
     *
     * 为什么还要这条路：SURFACE_FIT_SCREEN（非等比铺满）由 setVideoScale 下发，
     * 在部分机型上会被 vout 悄悄忽略，结果是「Exo 铺满、切到软解就变回两边黑边」。
     * 而把显示比例直接定成窗口比例（相当于告诉 VLC「这是变形宽银幕片源」），
     * 默认的 best-fit 就必然铺满整屏 —— 值在开播前定好，不需要改窗口尺寸，
     * 因此也不会触发管线重建（不会再有「播 5 秒卡一下」）。
     */
    private var fillAspectW = 0
    private var fillAspectH = 0

    fun setFillAspect(w: Int, h: Int) {
        fillAspectW = if (w > 0 && h > 0) w else 0
        fillAspectH = if (w > 0 && h > 0) h else 0
    }

    private var currentUrl: String? = null

    /** 硬解被判定为「播几秒就定格」之后置位：这一条流改成视频软解重开 */
    private var forceSoftVideo = false
    /** 卡死后先做「不重建」的轻量恢复（重新 play），失败才动重建 */
    private var softRecoverTried = false

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
        // 新频道 = 新的卡死计数，别把上一条流的账算到这条头上
        hardRestarts = 0
        forceSoftVideo = false
        softRecoverTried = false
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
        zoomMode = mode
        zoomScalePct = pctSafe(scalePct)
        // 注意：这里**不重建管线**。旧代码只要参数值变化就 scheduleRestart()，
        // 而自动去黑边在起播 3.5 秒后会带着新的百分比调用一次 —— 于是每次都被迫
        // 重建整条 VLC 管线（2–5 秒），重建后又从 HLS 窗口开头重新播，表现为
        // 「播 5 秒卡住 → 又重复刚才的画面」。只有 surface 尺寸真的变了才该重建，
        // 那个判断在 onSurfaceResized 里（比较的是实际像素尺寸，不是参数值）。
        layoutSurface()
    }

    /**
     * 自动去黑边：检测到对称黑边时让 VLC 裁切铺满，否则保持完整画面。
     *
     * 只改 ScaleType，不碰 SurfaceView 尺寸 —— 这是即时生效的，不会触发管线重建，
     * 也就不会出现「播几秒突然卡住」。
     */
    fun setAutoFill(on: Boolean) {
        // 不做「值没变就 return」：换了内核实例后 autoFill 又被置回默认值，
        // 只比较字段会漏掉「新实例还没被设置过」这一步。这里每次都下发，幂等且便宜。
        autoFill = on
        if (player != null) applyScaleTypeAsync()
    }

    /**
     * host 尺寸变化（旋转、进/出全屏）后由界面调用。
     * surface 自己的布局回调一般也会触发，这里是双保险：宿主宽度变了但 surface
     * 因为固定尺寸（自定义缩放）没有重排时，也能被测到。
     */
    fun onHostResized() {
        if (released) return
        if (zoomMode == "scale") layoutSurface()
        // 只有 surface 实际尺寸（像素）跟已交给 VLC 的窗口尺寸不同才需要重建。
        // 无条件重建是上一版的坑：宿主每重排一次就重启一次管线，画面反复回到窗口开头。
        maybeRestartIfSizeChanged()
    }

    private fun maybeRestartIfSizeChanged() {
        if (released || player == null) return
        val sv = surfaceView ?: return
        if (sv.width <= 0 || sv.height <= 0) return
        if (abs(sv.width - voutWidth) < 8 && abs(sv.height - voutHeight) < 8) return
        scheduleRestart()
    }

    private fun pctSafe(v: Int): Int = v.coerceIn(50, 200)

    /** 约分用：aspect-ratio 里 2340:1080 这种大数 VLC 也认，但小值更稳 */
    private fun gcdOf(a: Int, b: Int): Int {
        var x = abs(a)
        var y = abs(b)
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return if (x == 0) 1 else x
    }

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

            // LibVLC 是全局单例（见 VlcCore）：这里只拿现成的实例，不再每次 new。
            // 旧写法在这里 new LibVLC() 要 1–3 秒，且发生在主线程，直接表现为界面卡死。
            val vlc = VlcCore.obtain(context) ?: run {
                fail("兼容内核不可用（libvlc 初始化失败）")
                return
            }
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
                                // 出画面之后再开始盯「卡死」：起播阶段的缓冲不算
                                startStallWatch()
                                onPrepared()
                                // vout 刚 attach 时 setVideoScale 有可能被拒（异常还被吞掉），
                                // 补两次：裁切/拉伸在不同机型上生效时机不一样，重试比「猜」便宜。
                                mainHandler.postDelayed({
                                    if (!released && g == generation) applyScaleTypeAsync()
                                }, 400L)
                                mainHandler.postDelayed({
                                    if (!released && g == generation) applyScaleTypeAsync()
                                }, 1200L)
                            }
                        }

                        MediaPlayer.Event.EncounteredError -> mainHandler.post {
                            if (!released && g == generation) fail("内核无法解码该流")
                        }
                    }
                }
            })

            val media = Media(vlc, Uri.parse(url))
            // 视频优先硬解，解不了自动退回软解；音频始终由 FFmpeg 解码（MP2/AC-3 靠这个）。
            // 第二个参数是「强制」，旧代码写 true —— 强制硬解在不支持该规格的盒子上会
            // 直接卡住解码器；改成 false 允许 VLC 自己回退到软解。
            // 卡死看过一次仍救不回来时（forceSoftVideo），视频也强制软解：
            // Amlogic 这类盒子的 MPEG-2/H.264 硬解在 libvlc 下有「播几秒定格」的毛病。
            media.setHWDecoderEnabled(!forceSoftVideo, false)
            media.addOption(":network-caching=$NETWORK_CACHING_MS")
            media.addOption(":live-caching=$NETWORK_CACHING_MS")
            // 直播流（TS/UDP/组播）的时钟抖动是「播几秒卡一下」的常见来源：
            // 默认的时钟同步会在时间戳跳变时反复重排，关掉抖动补偿反而更稳。
            media.addOption(":clock-jitter=0")
            media.addOption(":clock-synchro=0")
            // 断流时让 VLC 自己重连，别把连接直接丢掉（它会表现为永久定格）
            media.addOption(":http-reconnect=true")
            if (forceSoftVideo) media.addOption(":avcodec-hw=none")
            // 拉伸铺满：把显示比例定成窗口比例，best-fit 也必然铺满（见 fillAspectW 注释）。
            // 必须在 setMedia 之前 addOption，且只影响「显示比例」，不动解码参数。
            if (fillAspectW > 0 && fillAspectH > 0) {
                val d = gcdOf(fillAspectW, fillAspectH)
                media.addOption(":aspect-ratio=${fillAspectW / d}:${fillAspectH / d}")
            }
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
        val scale = when {
            // 自动去黑边优先于 fit：用 VLC 自己的裁切，不改视图尺寸
            autoFill && zoomMode == "fit" -> MediaPlayer.ScaleType.SURFACE_FILL
            zoomMode == "crop" -> MediaPlayer.ScaleType.SURFACE_FILL
            zoomMode == "stretch" -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        }
        // 关键：不在主线程同步调用。vout 正在重建时这一步会阻塞，
        // 一旦卡在主线程就是「画面还在动、返回键按不动」。
        submitVout { runCatching { mp.setVideoScale(scale) } }
    }

    // ── 卡死看门狗 ────────────────────────────────────────────────
    // VLC 有个很讨厌的行为：网络断了或者解码队列堵了，它既不报 Error 也不触发
    // Buffering，只是让画面永远定在最后一帧。用户看到的就是「一直卡在那里一动不动」。
    // 这里定期读一下播放时间戳，连续几次不前进就判定卡死，重建一次管线；
    // 连续两次重建都没救，就交回界面降级成「无声播放」，别让用户一直盯着死画面。

    private var stallWatchRunning = false
    private var lastPosMs = -1L
    private var frozenHits = 0
    private var hardRestarts = 0

    private val stallRunnable = object : Runnable {
        override fun run() {
            if (released || !stallWatchRunning) return
            val mp = player ?: return
            submitVout {
                val pos = runCatching { mp.time }.getOrDefault(-1L)
                val playing = runCatching { mp.isPlaying }.getOrDefault(false)
                mainHandler.post {
                    if (released || !stallWatchRunning || mp !== player) return@post
                    when {
                        // 暂停、还没起播、时间戳无效：不算卡死
                        !playing || pos <= 0 -> {
                            frozenHits = 0
                            lastPosMs = if (pos > 0) pos else -1L
                        }
                        pos == lastPosMs -> {
                            frozenHits++
                            if (frozenHits >= STALL_HITS) {
                                frozenHits = 0
                                recoverFromStall()
                                return@post
                            }
                        }
                        else -> {
                            frozenHits = 0
                            lastPosMs = pos
                        }
                    }
                    mainHandler.postDelayed(this, STALL_CHECK_MS)
                }
            }
        }
    }

    private fun startStallWatch() {
        if (stallWatchRunning) return
        stallWatchRunning = true
        lastPosMs = -1L
        frozenHits = 0
        mainHandler.removeCallbacks(stallRunnable)
        mainHandler.postDelayed(stallRunnable, STALL_CHECK_MS)
    }

    private fun stopStallWatch() {
        stallWatchRunning = false
        mainHandler.removeCallbacks(stallRunnable)
    }

    private fun recoverFromStall() {
        if (released) return
        // 第一步先做「不拆管线」的轻量恢复：很多定格只是 VLC 自己停了播放循环，
        // 重新 play() 就能接上。拆管线重建会让画面从头播一遍（用户看到「重复之前内容」），
        // 所以能不重建就不重建。
        if (!softRecoverTried) {
            softRecoverTried = true
            submitVout { runCatching { player?.play() } }
            return
        }
        if (hardRestarts >= MAX_HARD_RESTARTS) {
            fail("兼容内核画面卡住无法恢复")
            return
        }
        hardRestarts++
        // 第二次重建改用视频软解：硬解「播几秒而定格」在 Amlogic 盒子上很常见，
        // 与其反复重建，不如直接换一条解码路径。
        if (hardRestarts >= 2) forceSoftVideo = true
        onRestarting?.invoke()
        restartNow()
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
        stopStallWatch()
        val mp = player
        val sv = surfaceView
        player = null
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
        if (mp == null) return
        submitVout {
            // 顺序很关键：先摘监听，再 stop，最后 release。
            // 这几步在部分机型上会阻塞数秒，所以绝不能放在主线程。
            // 注意：LibVLC 本身是全局单例，这里**不释放**它，只销毁 MediaPlayer。
            runCatching { mp.setEventListener(null) }
            runCatching { mp.stop() }
            runCatching { mp.release() }
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
        /**
         * 直播缓存（毫秒）。
         *
         * 这个值必须大于一个分片的时长，否则每个分片边界都要停下来等下一段下载完 ——
         * HLS 分片普遍 4–6 秒，旧值 1000/1200 根本撑不过一个分片，表现就是
         * 「播 5 秒左右卡一下」。取 4000 让 VLC 手里常备一个分片，代价是起播慢 1–2 秒。
         */
        const val NETWORK_CACHING_MS = 4000

        /** 尺寸变化后延迟多久重建：留一帧给布局稳定 */
        const val RESTART_DELAY_MS = 420L

        /** 两次重建的最小间隔，防止尺寸抖动导致反复重建 */
        const val RESTART_COOLDOWN_MS = 3000L

        /** 卡死探测间隔：2.5 秒看一次播放时间戳是否前进 */
        const val STALL_CHECK_MS = 2500L

        /** 连续多少次时间戳不动判定为卡死（2.5s × 3 ≈ 7.5 秒） */
        const val STALL_HITS = 3

        /** 最多重建几次去救卡死：两次还不行就降级为无声播放，别让画面永远定住 */
        const val MAX_HARD_RESTARTS = 2
    }
}

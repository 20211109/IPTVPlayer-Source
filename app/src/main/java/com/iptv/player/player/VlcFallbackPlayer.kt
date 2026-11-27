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
    private val onRestarting: (() -> Unit)? = null,
    /**
     * VLC 侧直播缓存（毫秒）：与 Exo 侧的「直播延迟」保持同一个量级，
     * 否则同一个频道换内核后缓冲厚度会变，用户会觉得「有时候稳有时候卡」。
     */
    private val networkCachingMs: Int = NETWORK_CACHING_MS
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
     * 「拉伸铺满」用视频显示比例驱动的渲染缓冲（0 = 不启用）。
     *
     * 为什么不用 setVideoScale：1.61 实测在真机上**完全无效** —— 无论是
     * SURFACE_FILL 还是 SURFACE_FIT_SCREEN，下发后画面依旧是 best-fit 居中留边
     * （异常还被库内部吞掉，连日志都没有）。也就是这台机器的 VLC 视频输出压根不认
     * 缩放指令，所以「让 VLC 自己裁/拉」这条路走不通。
     *
     * 改成我们不求它：SurfaceView 的渲染缓冲尺寸可以独立于视图尺寸（holder.setFixedSize），
     * 而合成器会把这块缓冲**拉伸铺满整个视图层**。于是：
     *   缓冲 = 视频自身比例（如 4:3 → 1440×1080），VLC 在里面 best-fit 正好铺满、无黑边；
     *   视图 = 整屏（2340×1080）→ 合成器把这 1440 宽横向拉到 2340 → 就是「拉伸铺满」。
     * 全过程只用到窗口尺寸（libvlc 一直按它算缩放），不依赖任何缩放/裁切 API。
     */
    private var bufferAspect = 0f
    private var bufferW = 0
    private var bufferH = 0

    /**
     * 「视频层直接按视频比例定尺寸」（0 = 不启用，视图铺满宿主）。
     *
     * 只有竖屏看横屏内容会用到它：此时窗口宽高比（约 0.46）和视频比例（1.78）差得最远。
     * libvlc 的 best-fit 是「先按窗口建 vout，再在窗口里缩放」，出画前画面按窗口铺满、
     * 出画后才缩成视频比例 —— 这就是竖屏下「先往左右拉一下、再上下缩一下」的来源，
     * 也是 1.82~1.84 反复加长遮挡始终盖不住的原因：那两下是 vout 内部的重排，不是黑帧。
     *
     * 与其事后遮挡，不如一开始就让 SurfaceView 本身等于视频矩形：
     * 交给 libvlc 的窗口尺寸 `setWindowSize()` 也是这个矩形，等比例窗口上 best-fit
     * 就是满铺 —— 第一帧即最终形态，全程没有缩放动作，也不会出现「窗口尺寸对不上要重建」。
     *
     * 为什么只在竖屏启用：横屏时自动去黑边走 `SURFACE_FILL`，它要求视图大于窗口才裁得动；
     * 视图一缩成视频矩形就没有可裁的余量了。竖屏本来不做裁切（黑边在上下），两者互不干扰。
     */
    private var fitAspect = 0f

    /**
     * 让视频层按 [aspect] 定尺寸（0 = 回到铺满宿主）。
     *
     * 与 [setStretchAspect] 互斥：那个改的是渲染缓冲（视图仍满屏），这个改的是视图本身。
     * 起播前调用只记字段，不触发任何重建；转屏后调用才需要重建（窗口尺寸真的变了）。
     */
    fun setFitAspect(aspect: Float) {
        val a = if (aspect > 0.2f && aspect < 6f) aspect else 0f
        if (abs(a - fitAspect) < 0.005f) return
        fitAspect = a
        bufferW = 0
        bufferH = 0
        if (!released && player != null) {
            layoutSurface()
            scheduleRestart()
        }
    }

    /**
     * 视频矩形在宿主里的实际像素尺寸（按视频比例内缩、居中）。
     * 宿主还没量到尺寸时返回 0，由调用方退回旧逻辑。
     */
    private fun fitRectSize(): Pair<Int, Int> {
        if (fitAspect <= 0f) return 0 to 0
        // 刻意不退回屏幕尺寸：宿主还没量到时必须返回 0，交给 startEngine 等待。
        // 否则会先按整屏算一个偏大的矩形、起播后宿主变了再重建 —— 那就白改了。
        val hw = host.width
        val hh = host.height
        if (hw <= 0 || hh <= 0) return 0 to 0
        val hostAspect = hw.toFloat() / hh
        return if (fitAspect >= hostAspect) {
            // 视频比宿主更宽：宽度铺满，上下内缩（竖屏看 16:9 就是这一支）
            hw to (hw / fitAspect).toInt().coerceIn(1, hh)
        } else {
            // 视频比宿主更窄：高度铺满，左右内缩
            (hh * fitAspect).toInt().coerceIn(1, hw) to hh
        }
    }

    /**
     * 告诉 VLC 内核「要按这个比例铺满」。传 0 表示不启用（按原比例居中）。
     * 已经开播时比例变了必须重建管线（缓冲尺寸变了），走 scheduleRestart。
     */
    fun setStretchAspect(aspect: Float) {
        val a = if (aspect > 0.2f && aspect < 6f) aspect else 0f
        if (abs(a - bufferAspect) < 0.005f) return
        bufferAspect = a
        bufferW = 0
        bufferH = 0
        if (!released && player != null) scheduleRestart()
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
    /** 起播前等 surface 尺寸的已重试次数（每次会话归零） */
    private var engineWaitTries = 0
    private var lastRestartAt = 0L
    private var restartPending = false

    /** host 尺寸变化（横竖屏切换）时也要重算自定义缩放的视图尺寸 */
    private val hostLayoutListener = View.OnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
        if (r - l > 0 && b - t > 0) onHostResized()
    }

    private val restartRunnable = Runnable {
        restartPending = false
        if (released) return@Runnable
        if (surfaceView == null) return@Runnable
        val (w, h) = targetSurfaceSize()
        if (w <= 0 || h <= 0) return@Runnable
        // 尺寸其实没变（例如只是键盘弹出引起的微小抖动）就不折腾
        if (abs(w - voutWidth) < 8 && abs(h - voutHeight) < 8) return@Runnable
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
        // 按比例定尺寸时视图尺寸跟着宿主走，宿主变了必须重排
        if (fitAspect > 0f) layoutSurface()
        if (bufferAspect > 0f) {
            // 拉伸缓冲的高度是跟着宿主走的，宿主变了必须重算
            val oldW = bufferW
            val oldH = bufferH
            computeBuffer()
            if (oldW != bufferW || oldH != bufferH) applyBufferSize()
        }
        // 只有实际像素尺寸（像素）跟已交给 VLC 的窗口尺寸不同才需要重建。
        // 无条件重建是上一版的坑：宿主每重排一次就重启一次管线，画面反复回到窗口开头。
        maybeRestartIfSizeChanged()
    }

    /** 当前该交给 VLC 的窗口尺寸：拉伸模式是渲染缓冲，按比例定尺寸模式是视频矩形，否则是视图尺寸 */
    private fun targetSurfaceSize(): Pair<Int, Int> {
        if (bufferW > 0 && bufferH > 0) return bufferW to bufferH
        if (fitAspect > 0f) {
            // 直接按宿主与比例算，不去读 sv.width：起播瞬间视图还没布局完，
            // 读到的可能是 0 或上一次的旧尺寸，那正是 vout 建错尺寸又要重建的由来。
            // 宿主也还没量到时返回 0，由 startEngine 等待，绝不用兜底值先起播。
            return fitRectSize()
        }
        val sv = surfaceView ?: return 0 to 0
        return sv.width to sv.height
    }

    private fun maybeRestartIfSizeChanged() {
        if (released || player == null) return
        val (w, h) = targetSurfaceSize()
        if (w <= 0 || h <= 0) return
        if (abs(w - voutWidth) < 8 && abs(h - voutHeight) < 8) return
        scheduleRestart()
    }

    /**
     * 按视频比例算渲染缓冲：高度跟宿主一致，宽度按视频比例缩。
     * 视图仍然是满屏，系统会把这块缓冲拉伸铺满整层。
     */
    private fun computeBuffer() {
        bufferW = 0
        bufferH = 0
        // 按视频比例定尺寸时不需要再套一层渲染缓冲：视图本身已经是视频矩形，
        // 两套机制叠加会把画面拉两次。
        if (fitAspect > 0f) return
        if (bufferAspect <= 0f) return
        val hw = fallbackWidth()
        val hh = fallbackHeight()
        if (hw <= 0 || hh <= 0) return
        // 只有「画面比窗口窄」时才建缓冲（缓冲比窗口窄 → 被拉开 → 铺满）。
        // 反过来（竖屏看横屏画面）按视频比例算会得到 4906×2760 这种远超窗口的怪尺寸，
        // 一裁一拉就是「画面只剩中间一小条」—— 竖屏必须是「适应窗口、上下留边」。
        val hostAspect = hw.toFloat() / hh
        if (bufferAspect >= hostAspect - 0.02f) return
        val w = (hh * bufferAspect).toInt()
        if (w < 32) return
        bufferW = w
        bufferH = hh
    }

    /** 把缓冲尺寸交给 SurfaceView：视图尺寸不变，缓冲独立 —— 拉伸就发生在这里 */
    private fun applyBufferSize() {
        val sv = surfaceView ?: return
        runCatching {
            if (bufferW > 0 && bufferH > 0) sv.holder.setFixedSize(bufferW, bufferH)
            else sv.holder.setSizeFromLayout()
        }
    }

    private fun pctSafe(v: Int): Int = v.coerceIn(50, 200)

    /** 起一条全新的会话：先挂 surface，等它拿到真实尺寸后再初始化 VLC */
    private fun beginSession(url: String) {
        val g = ++generation
        engineWaitTries = 0
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
        // 拉伸模式：先把渲染缓冲定成视频比例（视图仍是满屏，由系统把缓冲拉开）。
        // 必须在挂上去之前设，否则第一帧会按满屏尺寸建缓冲，之后又得重建一次。
        computeBuffer()
        applyBufferSize()
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

        // 必须等到 surface 的真实尺寸再起播。
        // 用兜底值（整屏尺寸）先起播，交给 VLC 的窗口尺寸就和真实 surface 不一致，
        // 之后 onSurfaceResized 必然会判定「尺寸变了」→ 重建 vout（2–5 秒）：
        // 竖屏下这一次重建正是「画面先往右一拉、再上下缩一下」的来源。
        // 布局一般几十毫秒内就完成，这里最多等 500ms，超时再退回旧兜底值，绝不卡死起播。
        val (readyW, readyH) = targetSurfaceSize()
        if (readyW <= 0 || readyH <= 0) {
            if (engineWaitTries < ENGINE_WAIT_MAX) {
                engineWaitTries++
                sv.postDelayed({ if (!released && g == generation) startEngine(url, g) }, ENGINE_WAIT_STEP_MS)
                return
            }
        }

        try {
            val (sw, sh) = targetSurfaceSize()
            val w = if (sw > 0) sw else fallbackWidth()
            val h = if (sh > 0) sh else fallbackHeight()
            voutWidth = w
            voutHeight = h

            // 按视频比例定尺寸时，视图必须在交出窗口尺寸之前就摆成同一个矩形。
            // beginSession 建视图那一刻宿主可能还没量到尺寸（fitRectSize 返回 0），
            // 那次 layoutParams 只能是 MATCH_PARENT；这里补一次，否则窗口是视频矩形、
            // 视图仍是整屏，画面会被合成器横向拉满 —— 比不改还糟。
            if (fitAspect > 0f) {
                layoutSurface()
                // 还要等视图真的按目标矩形完成一次布局，再把窗口尺寸交出去。
                //
                // 渲染缓冲这一档走的是 setSizeFromLayout()，跟随的是视图**实际**布局尺寸；
                // 视图还停在旧的满屏尺寸时起播，第一帧的缓冲比例和窗口比例就对不上，
                // 合成器会把那块缓冲拉伸铺满当前视图 —— 用户看到的就是
                // 「整块画面往右放大一下、然后才恢复」。等一下（最多 500ms）比事后补救便宜。
                val (rw, rh) = fitRectSize()
                if (rw > 0 && rh > 0 && (sv.width != rw || sv.height != rh) &&
                    engineWaitTries < ENGINE_WAIT_MAX
                ) {
                    engineWaitTries++
                    sv.postDelayed({ if (!released && g == generation) startEngine(url, g) }, ENGINE_WAIT_STEP_MS)
                    return
                }
            }

            // LibVLC 是全局单例（见 VlcCore）：这里只拿现成的实例，不再每次 new。
            // 旧写法在这里 new LibVLC() 要 1–3 秒，且发生在主线程，直接表现为界面卡死。
            val vlc = VlcCore.obtain(context) ?: run {
                fail(ERR_INIT)
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
            // attachViews 有可能把 holder 恢复成「跟随布局」，这里再定一次缓冲尺寸
            applyBufferSize()
            // 首帧之前就把画幅指令发下去。
            // setVideoScale 是异步生效的：等到 Playing 事件再发，头几百毫秒会先按默认比例
            // 出画、再突然缩放成裁切/铺满 —— 用户看到的就是「画面上下拉一下」。
            // 这里提前发一次，绝大多数机型第一帧就已经是最终画幅；
            // Playing 之后那两次重试仍然保留，用来兜「attach 时 vout 还没就绪」的机器。
            applyScaleTypeAsync()

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
                            if (!released && g == generation) fail(ERR_DECODE)
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
            media.addOption(":network-caching=$networkCachingMs")
            media.addOption(":live-caching=$networkCachingMs")
            // 直播流（TS/UDP/组播）的时钟抖动是「播几秒卡一下」的常见来源：
            // 默认的时钟同步会在时间戳跳变时反复重排，关掉抖动补偿反而更稳。
            media.addOption(":clock-jitter=0")
            media.addOption(":clock-synchro=0")
            // 断流时让 VLC 自己重连，别把连接直接丢掉（它会表现为永久定格）
            media.addOption(":http-reconnect=true")
            if (forceSoftVideo) media.addOption(":avcodec-hw=none")
            mp.setMedia(media)
            media.release()
            mp.play()
        } catch (t: Throwable) {
            fail("$ERR_INIT_PREFIX：${t.message ?: t.javaClass.simpleName}")
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
        // 拉伸模式下真正交给 VLC 的是「渲染缓冲」，它跟视图尺寸不是一回事
        // （视图 2340×1080、缓冲可能只有 1440×1080）—— 这里必须比缓冲，
        // 否则视图尺寸永远对不上，会陷入「一布局就重建」的死循环。
        val (tw, th) = targetSurfaceSize()
        if (tw <= 0 || th <= 0) return
        if (abs(tw - voutWidth) < 8 && abs(th - voutHeight) < 8) return
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
        // 竖屏「视频层 = 视频矩形」模式（fitAspect）：窗口比例与视频一致，best-fit
        // 既是唯一正确解、也正好是 libvlc 的默认值 —— 这一档不需要任何缩放指令。
        //
        // 反过来，1.86 的实测是：下发之后 libvlc 会按新的 scale 重新算一遍视口，
        // 而这次重排既不重建 surface、也不回调 onRestarting，只是在 vout 内部把画面
        // 放大一下再缩回来 —— 用户看到的就是「整块画面往右放大一瞬间，然后又恢复」。
        // 既然默认值已经对，这里就彻底不发，把那一下抖动从源头去掉。
        if (fitAspect > 0f) return
        val scale = currentScaleType()
        // 关键：不在主线程同步调用。vout 正在重建时这一步会阻塞，
        // 一旦卡在主线程就是「画面还在动、返回键按不动」。
        submitVout { runCatching { mp.setVideoScale(scale) } }
    }

    /** 当前决策对应的 VLC 画面缩放方式（纯计算，不下发） */
    private fun currentScaleType(): MediaPlayer.ScaleType = when {
        // 竖屏「视频层 = 视频矩形」：窗口比例已与视频一致，best-fit 即满铺
        fitAspect > 0f -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        // 拉伸模式：缓冲本身就是视频比例，best-fit 就已经铺满整块缓冲了。
        // 这里绝不能再叠 FILL/FIT_SCREEN —— 那会把刚刚对齐的画幅又裁/拉一次。
        bufferW > 0 && bufferH > 0 -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
        // 自动去黑边优先于 fit：用 VLC 自己的裁切，不改视图尺寸
        autoFill && zoomMode == "fit" -> MediaPlayer.ScaleType.SURFACE_FILL
        zoomMode == "crop" -> MediaPlayer.ScaleType.SURFACE_FILL
        zoomMode == "stretch" -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
        else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
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
            fail(ERR_STALL)
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
        } else if (fitAspect > 0f) {
            // 视图本身就是视频矩形：交给 VLC 的窗口尺寸与之相同，best-fit 等于满铺，
            // 出画前后画面几何完全一致，不会「拉」。
            val (w, h) = fitRectSize()
            if (w > 0 && h > 0) {
                lp.width = w
                lp.height = h
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

    /**
     * 对外只需要两样东西：失败原因常量与「这次失败是否与线路有关」的判断，
     * 供界面决定「先换线路」还是「直接降级无声」。其余常量请保持不暴露给外部使用。
     */
    companion object {
        /**
         * 直播缓存（毫秒）默认值。
         *
         * 这个值必须大于一个分片的时长，否则每个分片边界都要停下来等下一段下载完 ——
         * HLS 分片普遍 4–6 秒，旧值 1000/1200 根本撑不过一个分片，表现就是
         * 「播 5 秒左右卡一下」。4000 只够一个分片，抖一下就断粮；
         * 提到 8000 让 VLC 手里常备一个半到两个分片，代价是起播多等 1 秒左右。
         */
        const val NETWORK_CACHING_MS = 8000

        /** 尺寸变化后延迟多久重建：留一帧给布局稳定 */
        const val RESTART_DELAY_MS = 420L

        /** 起播前等待 surface 量好尺寸的次数与间隔（10 × 50ms = 最多 500ms） */
        const val ENGINE_WAIT_MAX = 10
        const val ENGINE_WAIT_STEP_MS = 50L

        /** 两次重建的最小间隔，防止尺寸抖动导致反复重建 */
        const val RESTART_COOLDOWN_MS = 3000L

        /** 卡死探测间隔：2.5 秒看一次播放时间戳是否前进 */
        const val STALL_CHECK_MS = 2500L

        /** 连续多少次时间戳不动判定为卡死（2.5s × 3 ≈ 7.5 秒） */
        const val STALL_HITS = 3

        /** 最多重建几次去救卡死：两次还不行就交给界面换线路，全部线路都试过才降级 */
        const val MAX_HARD_RESTARTS = 2

        /** 失败原因：libvlc 本身起不来 —— 与线路无关，换哪条都一样，没必要换线浪费时间 */
        const val ERR_INIT = "兼容内核不可用（libvlc 初始化失败）"
        const val ERR_INIT_PREFIX = "兼容内核不可用"

        /** 失败原因：拿到了流但解不出来（分片损坏、封装异常等，通常换条线路就好了） */
        const val ERR_DECODE = "内核无法解码该流"

        /** 失败原因：重建到上限画面仍然定格 —— 这条线路在软解路径上已经废了 */
        const val ERR_STALL = "兼容内核画面卡住无法恢复"

        /**
         * 这次失败是否「与线路有关」——决定界面该不该先换线路重试，再考虑降级。
         *
         * 软解内核接管期间 PlayerManager 处于挂起状态，卡顿换线入口全部不可用；
         * 若直接降级成无声播放，就等于在一棵树上吊死。而 libvlc 初始化失败是全局
         * 能力问题，换成哪条线路都一样，跳过换线能省下十几秒。
         */
        fun isLineFailure(reason: String): Boolean = !reason.startsWith(ERR_INIT_PREFIX)
    }
}

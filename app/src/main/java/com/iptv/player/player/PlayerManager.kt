package com.iptv.player.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.iptv.player.config.HttpCompat
import com.iptv.player.config.HlsPlaylistCleanInterceptor
import com.iptv.player.data.Channel
import com.iptv.player.data.Source
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 播放管理器（v1.9 起采用「锁台优先」策略）：
 *
 * - 起播阶段（当前线路从未成功出过画面）连不上，才按顺序切到下一条线路。
 * - 一旦某条线路成功播放过，就锁定这条线路：之后无论卡顿还是网络抖动，
 *   都只在【原线路】上重连，绝不自动跳到下一条。看直播最怕的就是画面正看着
 *   突然被切走，一条 HLS 分片请求失败并不代表整条线路不可用。
 * - 自动切源的冷却判断必须显式处理「从未切换过」的初始状态，否则第一次错误
 *   会绕过冷却立刻切源。
 */
class PlayerManager(
    context: Context,
    private val bufferMinMs: Int = 5000,
    private val bufferMaxMs: Int = 15000,
    private val bufferForPlaybackMs: Int = 1000,
    private val bufferForPlaybackAfterRebufferMs: Int = 2000,
    private val switchCooldownMs: Long = 8000L,
    /** 解码器优先策略：auto 自动 / hardware 硬解优先 / software 软解优先 */
    private val decoderPriority: String = "auto"
) {

    companion object {
        /** 起播失败时，两次换线路之间的最小间隔 */
        private const val SWITCH_ERROR_COOLDOWN_MS = 4000L
        /** 距上次重连超过该时长，视为新一轮观看，重置重连次数 */
        private const val RECONNECT_RESET_WINDOW_MS = 60_000L
        /** 分片 404 的计数窗口：窗口内连续出现才逐级升级恢复方式 */
        private const val SILENT404_WINDOW_MS = 30_000L
        /** 同一次 404 会被重复回调多次，短间隔内只恢复一次，避免恢复风暴 */
        private const val SILENT404_MIN_INTERVAL_MS = 700L
        /**
         * 卡死判定：进度静止超过该时长才视为卡死。
         * 直播正常播放时进度一定在走，所以这是唯一可靠的判据；
         * 15 秒是为了吸收弱源「一个分片要拉十几秒」的正常抖动，避免误判后反复恢复。
         */
        private const val FREEZE_TIMEOUT_MS = 15_000L
        /** 卡死巡检间隔 */
        private const val FREEZE_CHECK_INTERVAL_MS = 3_000L
        /** 时间窗内反复卡死达到该次数，说明这条线路已经不可用，直接换线路 */
        private const val FREEZE_SWITCH_THRESHOLD = 4
        private const val FREEZE_SWITCH_WINDOW_MS = 300_000L
        /** 刚换过线路后的观察期：新线路起播/建缓冲期间不因卡死再换，避免连续跳线 */
        private const val FREEZE_SWITCH_GRACE_MS = 30_000L
        /** 连续播放这么久算「这条线路撑住了」，重置卡死换线计数 */
        private const val STABLE_PLAY_RESET_MS = 60_000L
        /** 所有线路都卡死后，暂停自动换线的时长，避免两条线路无限来回切 */
        private const val FREEZE_SWITCH_COOLDOWN_MS = 120_000L
        /**
         * 起播看门狗：每次切换线路后这么久还没出画面，就判定这条线路无响应，自动换下一条。
         * 旧实现只在界面 openChannel 时布防一次，播放器内部换线后没人重新布防，
         * 新线路一旦始终不出画面就会永久黑屏——这是「自动换源不生效」的主因。
         */
        private const val START_TIMEOUT_MS = 20_000L
        /** 所有线路都试过仍失败时，最多重新尝试几轮：既不轻易放弃，也不会死循环 */
        private const val MAX_STARTUP_ROUNDS = 2
        /**
         * 无声检测延迟：出画后等这么久再检查音频轨。
         * Media3 遇到设备没有解码器的音频编码（典型 MP2）时，会把该音轨标记为不支持并
         * 静默跳过——既不报错也不出声，只靠 onPlayerError 永远等不到，必须主动巡检。
         */
        private const val AUDIO_CHECK_DELAY_MS = 3_000L
    }

    val player: ExoPlayer
    private lateinit var bandwidthMeter: DefaultBandwidthMeter
    private var currentChannel: Channel? = null
    private var currentSourceIndex = -1
    private val triedIndices = mutableSetOf<Int>()

    /** 当前线路是否已经成功出过画面。true = 锁台，禁止一切自动换线路 */
    private var hasEverPlayed = false

    /** 当前线路的重连次数与上次重连时间 */
    private var reconnectAttempts = 0
    private var lastReconnectMs = 0L

    /** 当前 MediaItem，原线路重连时复用 */
    private var currentMediaItem: MediaItem? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectRunnable = Runnable { performReconnect() }

    // ---------------- 卡死看门狗 ----------------
    /** 上一次巡检时的播放进度与时间：进度长时间不变就是卡死 */
    private var lastProgressPositionMs = 0L
    private var lastProgressAtMs = 0L
    /** 连续恢复次数（进度恢复前进后清零） */
    private var freezeRecoveries = 0
    /** 近期卡死时间点，用于判断「这条线路是不是已经废了」 */
    private val freezeTimestamps = ArrayDeque<Long>()

    /** 连续「卡死换线」次数：只有稳定播放够久才清零，防止两条线路无限来回切 */
    private var freezeSwitchChain = 0
    /** 本次稳定播放起点，用于判断线路是否已经撑住 */
    private var stableSinceMs = 0L
    /** 所有线路都卡死后的冷却截止时间 */
    private var freezeSwitchCooldownUntilMs = 0L
    /** 刚换过线路后的观察期截止时间：期间不再因卡死换源，避免连续跳线 */
    private var freezeSwitchGraceUntilMs = 0L

    /** 起播尝试轮次：所有线路按顺序试完一遍算一轮，用于限制「全线路重试」次数 */
    private var startupRounds = 0
    /** 当前频道是否已经因音频编码不支持（如 MP2）而回退为无声播放 */
    private var audioCompatApplied = false
    /** 用户在设置里强制「仅视频、关闭音频」 */
    private var videoOnlyRequested = false
    /** 当前线路对象：音频兼容回退时界面需要 URL 去启动兜底内核 */
    private var currentSource: Source? = null
    /** 兜底内核对 Exo 的挂起标记：挂起期间暂停巡检、重连与错误处理 */
    private var suspended = false
    /** 无声检测是否已排期：同一条线路只排一次，避免反复出缓冲时不断推迟 */
    private var audioCheckScheduled = false
    /** 当前线路是否已完成无声检测：只查一次，避免播放中途因瞬时轨道变化误切内核 */
    private var audioChecked = false

    /** 所有线路都卡住、自动换线已暂停时回调 */
    var onAllSourcesStuck: (() -> Unit)? = null

    /** 起播超时自动换到下一线路后回调（界面用它提示用户） */
    var onStartTimeoutSwitched: (() -> Unit)? = null

    /**
     * 音频编码不支持（MP2/AC3 等老设备解码器缺失）而需要外部内核兜底时回调。
     * 参数是当前线路，界面据此启动 IJK 内核（视频硬解 + 音频 FFmpeg 软解）。
     * 若界面没有接管（未注册回调），playerManager 会自动退化为无声播放。
     */
    var onAudioFallback: ((Source) -> Unit)? = null

    /** 网络恢复监听：WiFi 断开重连、切网后立刻接上直播，不用等看门狗超时 */
    private var connectivityManager: android.net.ConnectivityManager? = null
    private lateinit var networkCallback: android.net.ConnectivityManager.NetworkCallback

    /** 卡死且恢复无效、自动换线路时回调：(新线路序号, 线路总数) */
    var onFreezeSwitched: ((Int, Int) -> Unit)? = null

    private val freezeCheckRunnable = object : Runnable {
        override fun run() {
            try {
                checkFreeze()
            } catch (e: Exception) {
                // 巡检本身绝不能影响播放
            }
            mainHandler.postDelayed(this, FREEZE_CHECK_INTERVAL_MS)
        }
    }

    /** 起播看门狗：由 switchTo 每次重新布防，只负责「这条新线路一直不出画面」的场景 */
    private val startWatchdogRunnable = Runnable { checkStartTimeout() }

    /** 无声检测：出画后检查音频轨是否真的可用（MP2 等编码可能被静默跳过） */
    private val audioCheckRunnable = Runnable { checkAudioAvailability() }

    // 切换冷却：切换后 switchCooldownMs 内不再响应卡顿切换
    var lastSwitchTimeMs = 0L
        private set

    // 错误节流：source error 会连续触发，冷却期内只上报一次，避免 toast 疯狂闪烁
    private var lastErrorReportMs = 0L
    private var lastErrorSwitchMs = 0L
    /** 播放中的偶发分片 404：先静默回直播点恢复，连续多次则强制刷新播放列表 */
    private var silent404Count = 0
    private var lastSilent404Ms = 0L
    private var lastSilent404RecoverMs = 0L
    private var consecutiveFailures = 0
    private var givenUp = false
    var maxRetry: Int = 6

    /** 连续失败达到上限时回调，通知 UI 停止重试并提示 */
    var onGiveUp: ((String) -> Unit)? = null

    var onSourceChanged: ((Source, Int, Int) -> Unit)? = null
    /** 参数：第几次恢复。不设上限，只要用户还在看这条频道就持续恢复 */
    var onReconnecting: ((Int) -> Unit)? = null
    /** 第几个参数：错误文案 / Media3 错误码（给用户截图定位用） */
    var onError: ((String, Int) -> Unit)? = null

    private val okHttp = HttpCompat.clientBuilder()
        // 央视频源含大量 404 占位分片，先在响应层清洗掉，从根上避免误判为线路故障
        .addInterceptor(HlsPlaylistCleanInterceptor())
        // 时间放宽一点点：坏源快速失败，好源也不会被误杀。
        // 读超时不能太紧：CDN 抖动时 5 秒分片偶尔要十几秒才回完，
        // 10 秒超时会直接被判失败并重连，表现为「播一会儿卡一下」。
        .connectTimeout(8, TimeUnit.SECONDS)
        // 部分源使用「阻塞式播放列表刷新」，服务器会挂住请求直到有新分片，
        // 15 秒正好卡在临界点会被误判超时、触发重连，表现为周期性卡顿。
        .readTimeout(25, TimeUnit.SECONDS)
        // 弱网优化：允许重试、缓存连接，避免频繁重建 TCP/TLS 连接
        .retryOnConnectionFailure(true)
        // 直播是高频小分片请求：复用空闲连接，减少反复 TCP/TLS 握手的开销，
        // 弱网下能明显减少分片拉取延迟抖动（TVBox 等壳子同样依赖连接复用）。
        .connectionPool(okhttp3.ConnectionPool(8, 15, TimeUnit.SECONDS))
        .build()

    init {
        val okHttpFactory = OkHttpDataSource.Factory(okHttp)
            // 很多直播源只放行常见播放器/浏览器 UA，自定义 UA 会被直接拒绝(403)
            .setUserAgent("VLC/3.0.18 LibVLC/3.0.18")
        val dataSourceFactory = DefaultDataSource.Factory(context, okHttpFactory)

        // DefaultMediaSourceFactory 会依据依赖自动识别 HLS(m3u8) 与渐进式流
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // 带宽估计器：ABR 依据它选码率。给一个偏保守的初始估计（1.5Mbps），
        // 避免弱网上手就冲高码率导致反复缓冲；之后由实际下载数据自动校正。
        bandwidthMeter = DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(1_500_000L)
            .build()

        // 硬解失败时允许回退软解，避免个别 TV 芯片在特定分辨率上直接卡死。
        // 解码器优先策略（设置页可调）：有些老盒子硬解 MP2/H.264 High 会花屏或卡死，
        // 改成软解优先往往能正常播放；反之低端 CPU 软解 4K 会掉帧，就用硬解优先。
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(buildCodecSelector(decoderPriority))

        // 轨道选择优化（针对「看一会儿开始卡顿」）：
        // HLS 自适应码率切到不同分辨率时，硬解需要重建解码器，画面会明显顿一下，
        // 在电视盒子上表现为周期性的卡顿。这里禁止「非无缝」切换，
        // 只在解码器可平滑切换的轨道间自适应，宁可少切也不顿。
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setAllowVideoMixedMimeTypeAdaptiveness(false)
                    .setAllowVideoNonSeamlessAdaptiveness(false)
            )
        }

        player = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setBandwidthMeter(bandwidthMeter)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        bufferMinMs,
                        bufferMaxMs,
                        bufferForPlaybackMs,
                        bufferForPlaybackAfterRebufferMs
                    )
                    // 时间优先：按"可播放秒数"而非字节数管理缓冲，直播弱网更稳
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
            )
            .build()

        // 电视盒子长时间播放时 WiFi 会进入省电模式，表现为「播一会儿卡一下」，
        // 播放期间请求网络唤醒锁，避免无线链路休眠丢包。
        player.setWakeMode(C.WAKE_MODE_NETWORK)

        // 卡死看门狗：只靠错误回调不够——有些不健康的直播源既不报错也不出画面，
        // 播放器停在缓冲态「动也不动」，必须靠进度巡检兜底。
        mainHandler.postDelayed(freezeCheckRunnable, FREEZE_CHECK_INTERVAL_MS)

        // 网络变化监听：断网恢复后立即在原线路上重连，缩短黑屏时间
        networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                mainHandler.post {
                    if (suspended) return@post
                    if (currentMediaItem == null) return@post
                    val state = player.playbackState
                    val needRecover = state == Player.STATE_IDLE || state == Player.STATE_BUFFERING
                    val stalled = lastProgressAtMs > 0L &&
                        System.currentTimeMillis() - lastProgressAtMs > 5_000L
                    if (player.playWhenReady && (needRecover || stalled)) {
                        // 网络刚回来，重置退避，立刻在原线路上重连
                        lastReconnectMs = 0L
                        scheduleReconnect(System.currentTimeMillis())
                    }
                }
            }
        }
        try {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            // 固件兼容：registerDefaultNetworkCallback 是 API 24（Android 7.0）才有的方法。
            // 小米盒子4 等国产盒子的低版本固件里没有这个实现，直接调用会抛
            // NoSuchMethodError —— 它属于 Error 而不是 Exception，catch(Exception) 根本接不住，
            // 会一路冒到外层，表现为「播放器初始化失败」或直接闪退、完全无法播放。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(networkCallback)
            } else {
                // 老固件退回 API 21 就有的「按 NetworkRequest 注册」，效果等价
                val request = android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, networkCallback)
            }
            connectivityManager = cm
        } catch (t: Throwable) {
            // 网络监听只是让断网恢复更快（卡死看门狗已能兜底），
            // 任何异常/错误都不能影响播放器初始化，失败就当作没有这个能力。
            connectivityManager = null
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // 出画面即撤销起播看门狗：之后交给卡死巡检与原线路重连
                if (state == Player.STATE_READY) {
                    mainHandler.removeCallbacks(startWatchdogRunnable)
                }
                // 真正出画面：确认这条线路可用，进入锁台状态
                if (state == Player.STATE_READY && player.playWhenReady) {
                    hasEverPlayed = true
                    consecutiveFailures = 0
                    silent404Count = 0
                    // 画面已出来：延迟检查音轨是否可用，覆盖「不报错但没声音」的场景
                    scheduleAudioCheck()
                    val now = System.currentTimeMillis()
                    // 距离上次重连已经很久：视为新一轮观看，恢复重连机会
                    if (lastReconnectMs > 0L && now - lastReconnectMs >= RECONNECT_RESET_WINDOW_MS) {
                        reconnectAttempts = 0
                    }
                    if (givenUp) givenUp = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 兜底内核接管期间 Exo 已停止，不再处理任何错误
                if (suspended) return
                val now = System.currentTimeMillis()
                val code = error.errorCode

                // 分片缺失一律不上报界面：
                //  - 播放中：静默分级恢复，绝不弹「分片不存在」的红字；
                //  - 起播阶段：下面按普通起播失败处理，静默切下一条线路。
                //
                // 关键修正（v1.36）：免费源/央视频的分片 404，Media3 抛的是
                // ERROR_CODE_IO_BAD_HTTP_STATUS(2004)，不是 ERROR_CODE_IO_FILE_NOT_FOUND(2005)。
                // 旧代码只认 2005，导致播放中每隔几秒的占位分片 404 被误当成故障弹红字
                // （「线路读取失败…错误码2004」），而画面其实一直在正常播放。
                // 这里把「2004 且确属 404」也纳入静默：用 cause 链里的 HTTP 状态码 + 文案双重判断，
                // 保证 403/5xx 等真实故障仍然照常上报，不会被误伤。
                val missingSegment = code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    (code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS && isHttp404(error))
                if (missingSegment && hasEverPlayed) {
                    recoverFromMissingSegment(now)
                    return
                }

                // 音频编码设备不支持（典型：只有 MP3 解码器、没有 MP2 解码器）：
                // 直接换源/换台也播不了，反而会黑屏。优先交给 IJK 兜底内核（音频 FFmpeg 软解），
                // 界面未接管时才退化为关闭音频轨继续出画面。
                if (!audioCompatApplied && isAudioDecodeError(error)) {
                    audioCompatApplied = true
                    applyAudioEnabled(false)
                    val source = currentSource
                    val fallback = onAudioFallback
                    if (source != null && fallback != null) {
                        fallback.invoke(source)
                    } else {
                        scheduleReconnect(now)
                    }
                    return
                }

                // 错误文案节流上报，并附带错误码：用户截图即可定位，不再靠猜
                if (!missingSegment && now - lastErrorReportMs >= 2500L) {
                    lastErrorReportMs = now
                    onError?.invoke(error.message ?: "播放错误", code)
                }

                // 落后直播窗口：回到直播点重试即可，不必换源也不必重连
                if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }

                if (givenUp) return

                // ★ 核心：已经成功播放过 => 锁台，绝不在播放途中跳到下一条线路。
                // 一个分片请求失败通常只是瞬时抖动，重连原线路比换源体验好得多。
                if (hasEverPlayed) {
                    scheduleReconnect(now)
                    return
                }

                // 起播阶段（从没出过画面）才算线路不可用，才允许按顺序换线路。
                // 解码失败、音轨初始化失败属于设备能力问题，换源同样播不了。
                if (code !in 2000..3999) return

                consecutiveFailures++
                if (consecutiveFailures >= maxRetry) {
                    givenUp = true
                    onGiveUp?.invoke("所有线路均无法播放，请检查网络或直播源")
                    return
                }
                // 真实冷却：lastErrorSwitchMs 初始为 0 时必须跳过冷却判断，
                // 否则第一次错误反而会因为「now - 0 >= 4000」而立即切源。
                if (lastErrorSwitchMs > 0L && now - lastErrorSwitchMs < SWITCH_ERROR_COOLDOWN_MS) {
                    return
                }
                lastErrorSwitchMs = now
                if (!switchToNextSource(force = true)) {
                    givenUp = true
                    onGiveUp?.invoke("所有线路均无法播放，请检查网络或直播源")
                }
            }
        })
    }

    /**
     * 判断一个 2004(BAD_HTTP_STATUS) 错误是否由 HTTP 404 分片缺失引起。
     * Media3 把 404 分片统一归为 2004；沿 cause 链找 InvalidResponseCodeException.responseCode==404，
     * 再辅以文案特征兜底（不同 Media3 版本包装层级可能不同）。
     * 只有确属 404 才静默；403/5xx 等真实线路故障仍照常上报，不被误伤。
     */
    private fun isHttp404(error: PlaybackException): Boolean {
        var cause: Throwable? = error.cause
        var depth = 0
        while (cause != null && depth++ < 12) {
            // HttpDataSource.InvalidResponseCodeException 携带 responseCode
            val respCode = try {
                cause.javaClass.getMethod("getResponseCode").invoke(cause) as? Int
            } catch (e: Exception) { null }
            if (respCode == 404) return true
            cause = cause.cause
        }
        val text = (error.message ?: "").lowercase()
        return text.contains("404")
    }

    /**
     * 判断错误是否由「音频解码器不支持」引起。
     * Media3 里音频/视频解码失败共用 4001(初始化失败)/4003(超出能力)/4004(格式不支持)，
     * 靠错误文案里的音频关键字区分；不能只凭错误码就把视频也关掉。
     */
    private fun isAudioDecodeError(error: PlaybackException): Boolean {
        val code = error.errorCode
        // 音频输出通道自身的初始化/写入失败：一定与音频相关，无需再看文案
        if (code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
        ) {
            return true
        }
        if (code != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED &&
            code != PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES &&
            code != PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        ) {
            return false
        }
        var text = (error.message ?: "").lowercase()
        var cause: Throwable? = error.cause
        var depth = 0
        while (cause != null && depth++ < 8) {
            text += " " + (cause.message ?: "").lowercase()
            cause = cause.cause
        }
        return text.contains("audio") || text.contains("mp2") ||
            text.contains("mpeg audio") || text.contains("ac3") ||
            text.contains("eac3") || text.contains("dts")
    }

    /**
     * 开关音频轨。
     * 关闭音频轨后视频仍正常播放（无声），比整条线路被判失败/黑屏体验好得多；
     * 对不支持的音频编码（MP2/AC3/DTS）这是唯一能在 Exo 侧兜底的手段。
     */
    private fun applyAudioEnabled(enabled: Boolean) {
        try {
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !enabled)
                .build()
        } catch (t: Throwable) {
            // 老固件/老 Media3 实现异常时忽略，绝不能影响播放
        }
    }

    /** 设置页「仅视频」开关 */
    fun setAudioOutputEnabled(enabled: Boolean) {
        videoOnlyRequested = !enabled
        applyAudioEnabled(enabled)
    }

    /**
     * 兜底内核接管 / 交还时调用。
     * 挂起时会停掉 Exo 并撤销所有巡检与重连——部分盒子的硬解码器实例有限，
     * 不彻底停止 Exo 会占着 MediaCodec，导致 IJK 起不来。
     */
    fun setSuspended(value: Boolean) {
        if (suspended == value) return
        suspended = value
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(startWatchdogRunnable)
        mainHandler.removeCallbacks(audioCheckRunnable)
        audioCheckScheduled = false
        audioChecked = false
        if (value) {
            try {
                player.pause()
                player.stop()
            } catch (t: Throwable) {
                // 停止失败不影响兜底内核启动
            }
        }
    }

    /**
     * 兜底内核也失败时调用：放弃声音，回到 Exo 以「仅视频」方式播放当前线路。
     * 同时标记 audioCompatApplied，避免再次触发回退形成死循环。
     */
    fun continueWithoutAudio() {
        suspended = false
        audioCompatApplied = true
        applyAudioEnabled(false)
        lastProgressPositionMs = 0L
        lastProgressAtMs = 0L
        freezeRecoveries = 0
        val item = currentMediaItem ?: return
        try {
            player.setMediaItem(item, C.TIME_UNSET)
            player.prepare()
            player.playWhenReady = true
        } catch (t: Throwable) {
            scheduleReconnect(System.currentTimeMillis())
            return
        }
        mainHandler.removeCallbacks(startWatchdogRunnable)
        mainHandler.postDelayed(startWatchdogRunnable, START_TIMEOUT_MS)
    }

    /** 当前线路是否已经成功出过画面（给 UI 判断看门狗是否需要兜底） */
    fun hasPlayed(): Boolean = hasEverPlayed

    /**
     * 线路顺序 = 播放列表里的原始顺序（不再做测速排序）。
     * 起播走第一条，只有起播一直失败时才按顺序切到下一未尝试线路。
     */
    private fun rankedIndices(channel: Channel, excludeTried: Boolean): List<Int> =
        channel.sources.indices
            .filter { !excludeTried || it !in triedIndices }

    /** 起播一个频道：从第一条线路、当前直播点开始；没有任何源时给出提示 */
    fun playChannel(channel: Channel) {
        currentChannel = channel
        triedIndices.clear()
        startupRounds = 0
        consecutiveFailures = 0
        givenUp = false
        // 新频道重新给音频一次机会：上一条频道因 MP2/AC3 不支持而降级的「无声播放」不延续
        audioCompatApplied = false
        // 每次起播都重置冷却基准，避免上一条频道的冷却时间影响新频道
        lastSwitchTimeMs = 0L
        lastErrorSwitchMs = 0L
        lastErrorReportMs = 0L
        // 换频道等于重新开始：清空卡死换线计数与冷却
        freezeSwitchChain = 0
        stableSinceMs = 0L
        freezeSwitchCooldownUntilMs = 0L
        val ranked = rankedIndices(channel, excludeTried = false)
        if (ranked.isEmpty()) {
            onError?.invoke("该频道暂无线路", -1)
            return
        }
        // 直播流用 C.TIME_UNSET：让它从"当前直播点"起播，而不是从时移窗口最开头回放
        switchTo(ranked.first(), positionMs = C.TIME_UNSET)
    }

    /**
     * 切到下一条未尝试线路。
     * 注意：只要当前线路已经成功播放过，这里一律返回 false（锁台），
     * 卡顿检测、起播看门狗、错误处理三个入口都受这一条保护。
     */
    fun switchToNextSource(force: Boolean = false): Boolean {
        val channel = currentChannel ?: return false
        if (hasEverPlayed || givenUp) return false
        val now = System.currentTimeMillis()
        if (!force && now - lastSwitchTimeMs < switchCooldownMs) return false

        var ranked = rankedIndices(channel, excludeTried = true)
        if (ranked.isEmpty()) {
            // 所有线路按顺序都试过仍未成功：清空尝试记录再来一轮。
            // 免费源常常「第一条彻底挂了、第二条偶尔能起」，只试一遍就放弃太早；
            // 但必须限制轮数，否则会在两条死线路之间无限循环。
            // 新一轮从「当前线路的下一条」开始，避免又先重试刚失败的那一条。
            if (startupRounds >= MAX_STARTUP_ROUNDS) return false
            startupRounds++
            triedIndices.clear()
            ranked = channel.sources.indices
                .filter { it != currentSourceIndex }
                .ifEmpty { channel.sources.indices.toList() }
        }
        switchTo(ranked.first(), C.TIME_UNSET)
        return true
    }

    /**
     * 手动换线：用户明确要求换一条线路时调用，无视「锁台」限制。
     * 用于自动换源仍找不到可用线路、或用户想主动避开当前卡顿线路的场景。
     */
    fun switchToNextSourceManually(): Boolean {
        val channel = currentChannel ?: return false
        if (channel.sources.size <= 1) return false
        val candidates = channel.sources.indices.filter { it != currentSourceIndex }
        if (candidates.isEmpty()) return false
        val next = candidates.firstOrNull { it !in triedIndices } ?: candidates.first()

        // 手动换线 = 用户主动重置：清空锁台、失败熔断与卡死统计
        hasEverPlayed = false
        givenUp = false
        startupRounds = 0
        triedIndices.clear()
        freezeRecoveries = 0
        freezeTimestamps.clear()
        freezeSwitchChain = 0
        stableSinceMs = 0L
        freezeSwitchGraceUntilMs = System.currentTimeMillis() + FREEZE_SWITCH_GRACE_MS
        switchTo(next, C.TIME_UNSET)
        return true
    }

    /** 真正执行切源：记录尝试、设置媒体源、从直播点起播 */
    private fun switchTo(index: Int, positionMs: Long) {
        val channel = currentChannel ?: return
        val source = channel.sources.getOrNull(index) ?: return
        triedIndices.add(index)
        currentSourceIndex = index
        currentSource = source
        // 切源即恢复 Exo 正常工作（若此前被兜底内核挂起），并给音频兼容一次重新判断的机会
        suspended = false
        audioCompatApplied = false
        lastSwitchTimeMs = System.currentTimeMillis()

        val builder = MediaItem.Builder().setUri(source.url)
        // 地址里带 m3u8 就直接按 HLS 解析，避免被当成普通文件去下载
        if (source.url.contains(".m3u8", ignoreCase = true)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        val mediaItem = builder.build()
        currentMediaItem = mediaItem

        // 换线路 = 全新开始：解除锁台、清空重连计数、取消排队中的重连与无声检测
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(audioCheckRunnable)
        audioCheckScheduled = false
        audioChecked = false
        hasEverPlayed = false
        reconnectAttempts = 0
        lastReconnectMs = 0L
        silent404Count = 0
        lastSilent404Ms = 0L
        lastSilent404RecoverMs = 0L
        // 换线路后重新开始计时，避免把上一条线路的静止时长算到新线路上
        lastProgressPositionMs = 0L
        lastProgressAtMs = 0L
        freezeRecoveries = 0

        player.setMediaItem(mediaItem, positionMs)
        player.prepare()
        player.playWhenReady = true

        // 每次切源都重新布防起播看门狗（关键修复）：
        // 旧实现只在 MainActivity.openChannel 布防一次，播放器内部换线后没有兜底，
        // 新线路若始终不出画面就会一直黑屏，自动换源表现为「只换一次就停住」。
        mainHandler.removeCallbacks(startWatchdogRunnable)
        mainHandler.postDelayed(startWatchdogRunnable, START_TIMEOUT_MS)
        // 切源后按当前策略设置音频轨（「仅视频」或已触发音频兼容回退时关闭）
        applyAudioEnabled(!videoOnlyRequested && !audioCompatApplied)

        channel.selectedIndex = index
        onSourceChanged?.invoke(source, index, channel.sources.size)
    }

    /**
     * 排期一次无声检测：每条线路只在首次出画后排一次，避免反复缓冲时不断推迟。
     */
    private fun scheduleAudioCheck() {
        if (audioCheckScheduled || audioChecked ||
            audioCompatApplied || videoOnlyRequested || suspended
        ) {
            return
        }
        audioCheckScheduled = true
        mainHandler.removeCallbacks(audioCheckRunnable)
        mainHandler.postDelayed(audioCheckRunnable, AUDIO_CHECK_DELAY_MS)
    }

    /**
     * 无声检测：出画后确认确实存在「受支持」的音频轨。
     *
     * Media3 遇到设备没有解码器的音频编码（典型 MP2）时，会把该音轨标记为不支持并
     * 静默跳过——既不抛错也不出声，只靠 onPlayerError 永远等不到。
     * 这里主动发现后交给兜底内核（IJK，音频 FFmpeg 软解），避免用户一直看着无声画面。
     */
    private fun checkAudioAvailability() {
        audioCheckScheduled = false
        if (audioChecked) return
        if (suspended || audioCompatApplied || videoOnlyRequested) return
        if (!hasEverPlayed) return
        val source = currentSource ?: return
        audioChecked = true

        val tracks = try {
            player.currentTracks
        } catch (t: Throwable) {
            return
        }

        var audioUsable = false
        var mp2Audio = false
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            if (!group.isSupported) continue
            audioUsable = true
            // 设备即使「声称支持」MP2，也可能解出静音（部分电视芯片的通病）。
            // 只要音轨是 MP2，就统一交给带 FFmpeg 的兜底内核，确保一定能出声。
            for (i in 0 until group.length) {
                val mime = try {
                    group.getTrackFormat(i).sampleMimeType
                } catch (t: Throwable) {
                    null
                }
                if (mime == MimeTypes.AUDIO_MPEG_L2) {
                    mp2Audio = true
                    break
                }
            }
            if (mp2Audio) break
        }
        if (audioUsable && !mp2Audio) return

        // 没有可用音轨（缺解码器），或是 MP2 音轨：交给兜底内核（FFmpeg 软解）处理
        audioCompatApplied = true
        applyAudioEnabled(false)
        val fallback = onAudioFallback
        if (fallback != null) {
            fallback.invoke(source)
        } else {
            // 没有兜底内核可用：保持无声播放，至少画面在线
            scheduleReconnect(System.currentTimeMillis())
        }
    }

    /**
     * 起播看门狗：切源后 [START_TIMEOUT_MS] 仍未出画面，判定该线路无响应，自动换下一条。
     * 成功出画面时会在 onPlaybackStateChanged(READY) 撤销，因此不会打断正常播放。
     */
    private fun checkStartTimeout() {
        if (suspended) return
        if (currentMediaItem == null) return
        if (hasEverPlayed) return
        if (player.playbackState == Player.STATE_READY) return
        val channel = currentChannel ?: return
        if (channel.sources.size <= 1) {
            // 只有一条线路时没有可换的目标，保持原线路继续重试
            return
        }
        if (switchToNextSource(force = true)) {
            onStartTimeoutSwitched?.invoke()
        } else if (!givenUp) {
            givenUp = true
            onGiveUp?.invoke("所有线路均无响应，请检查网络或直播源")
        }
    }

    /**
     * 播放进度巡检：直播是连续流，只要在正常播放，currentPosition 一定在前进。
     * 进度静止超过 [FREEZE_TIMEOUT_MS] 就视为卡死，按「回直播点 → 重建播放项 → 换线路」逐级恢复。
     * 用户主动暂停（playWhenReady=false）时不计入。
     */
    private fun checkFreeze() {
        if (suspended) return
        if (currentMediaItem == null) return
        // 起播阶段交给内置的起播看门狗（随每次切源重新布防），这里只负责「已经播出来之后再卡死」
        if (!hasEverPlayed) {
            lastProgressPositionMs = player.currentPosition
            lastProgressAtMs = System.currentTimeMillis()
            return
        }
        val now = System.currentTimeMillis()
        if (!player.playWhenReady || player.playbackState == Player.STATE_IDLE) {
            lastProgressPositionMs = player.currentPosition
            lastProgressAtMs = now
            return
        }
        if (lastProgressAtMs == 0L) {
            lastProgressPositionMs = player.currentPosition
            lastProgressAtMs = now
            return
        }

        val pos = player.currentPosition
        val progressAdvanced = pos != lastProgressPositionMs
        if (progressAdvanced) {
            lastProgressPositionMs = pos
            lastProgressAtMs = now
            // 进度恢复前进：连续恢复计数清零（窗口内的历史卡死次数保留，用于判断要不要换线路）
            if (player.playbackState == Player.STATE_READY) {
                freezeRecoveries = 0
                if (stableSinceMs == 0L) stableSinceMs = now
                // 稳定播放够久，说明这条线路是好的，清空「连续换线」计数
                if (now - stableSinceMs >= STABLE_PLAY_RESET_MS) freezeSwitchChain = 0
            }
        }

        // 唯一可靠的判据：直播是连续流，正常播放时 currentPosition 一定在前进，
        // 长时间静止不动才是真的卡死。
        // （旧版还叠加了「多久没出视频帧」的判据，但它监听的是丢帧偏移回调——
        //   只在掉帧时才触发，「一段时间没触发」恰恰说明播放很顺畅，
        //   结果每次顺畅播放十几秒就被误判成卡死，进而反复恢复、换源。）
        val progressStuck = !progressAdvanced && now - lastProgressAtMs >= FREEZE_TIMEOUT_MS
        if (!progressStuck) return

        // 刚换过线路：新线路起播、建缓冲期间不干预，避免连续跳线
        if (now < freezeSwitchGraceUntilMs) {
            lastProgressAtMs = now
            return
        }

        // ===== 判定卡死 =====
        lastProgressAtMs = now
        freezeRecoveries++
        freezeTimestamps.addLast(now)
        while (freezeTimestamps.isNotEmpty() &&
            now - freezeTimestamps.first() > FREEZE_SWITCH_WINDOW_MS
        ) {
            freezeTimestamps.removeFirst()
        }

        // 恢复过一次仍然卡死，或短时间内反复卡死：这条线路已经不可用，直接换线路
        val shouldSwitch = freezeRecoveries >= 2 || freezeTimestamps.size >= FREEZE_SWITCH_THRESHOLD
        if (shouldSwitch && forceSwitchToNextSource()) return

        onReconnecting?.invoke(freezeRecoveries)
        try {
            if (freezeRecoveries <= 1) {
                // 一级恢复：回到直播点并重新准备，尽量保留最后一帧
                lastProgressAtMs = now
                player.seekToDefaultPosition()
                player.prepare()
                player.playWhenReady = true
            } else {
                // 二级恢复：整条重建，重新拉取播放列表拿新分片地址
                val item = currentMediaItem ?: return
                player.setMediaItem(item, C.TIME_UNSET)
                player.prepare()
                player.playWhenReady = true
            }
        } catch (e: Exception) {
            try {
                val item = currentMediaItem ?: return
                player.setMediaItem(item, C.TIME_UNSET)
                player.prepare()
                player.playWhenReady = true
            } catch (ignored: Exception) {
                // 交给下一轮巡检继续尝试
            }
        }
    }

    /**
     * 卡死兜底换线路：绕过「锁台」限制。
     * 锁台是为了避免正常观看时被换走，但画面已经彻底定格的场景下，
     * 继续守着一条死线路毫无意义，必须主动换到下一条。
     */
    private fun forceSwitchToNextSource(): Boolean {
        val channel = currentChannel ?: return false
        if (channel.sources.size <= 1) return false

        val now = System.currentTimeMillis()
        // 所有线路刚被判定卡死，冷却期内不再来回切，避免频繁黑屏跳线
        if (now < freezeSwitchCooldownUntilMs) return false
        // 每条线路都试过一遍且都没撑住：停止自动换线，冷却一段时间后再允许
        if (freezeSwitchChain >= channel.sources.size) {
            freezeSwitchChain = 0
            freezeRecoveries = 0
            freezeTimestamps.clear()
            freezeSwitchCooldownUntilMs = now + FREEZE_SWITCH_COOLDOWN_MS
            onAllSourcesStuck?.invoke()
            return false
        }

        val candidates = channel.sources.indices.filter { it != currentSourceIndex }
        if (candidates.isEmpty()) return false
        val next = candidates.firstOrNull { it !in triedIndices } ?: candidates.first()
        // 换线路前清空卡死统计，避免新线路立刻被判死
        freezeRecoveries = 0
        freezeTimestamps.clear()
        freezeSwitchChain++
        stableSinceMs = 0L
        freezeSwitchGraceUntilMs = now + FREEZE_SWITCH_GRACE_MS
        givenUp = false
        switchTo(next, C.TIME_UNSET)
        onFreezeSwitched?.invoke(next, channel.sources.size)
        return true
    }

    /**
     *
     * 免费源/央视频的分片列表与 CDN 边缘常有一拍延迟：分片已写进 m3u8，
     * 但边缘节点上还没生成，请求就返回 404。这类错误基本都会自愈，
     * 因此只做分级恢复，绝不把它当播放失败上报给界面：
     *   第 1 次：原地重新准备，跳过缺失分片、保留已缓冲内容，观感几乎无感；
     *   第 2 次：回到直播点重新准备；
     *   第 3 次：重建当前 MediaItem，强制重新拉取播放列表（分片地址已刷新）；
     *   第 4 次起：走原线路重连退避，仍然不换台、不提示。
     */
    private fun recoverFromMissingSegment(now: Long) {
        // 一次 404 会按轨道/重试重复回调，短间隔内只处理一次
        if (now - lastSilent404RecoverMs < SILENT404_MIN_INTERVAL_MS) return
        lastSilent404RecoverMs = now

        if (now - lastSilent404Ms > SILENT404_WINDOW_MS) silent404Count = 0
        lastSilent404Ms = now
        silent404Count++

        mainHandler.removeCallbacks(reconnectRunnable)
        when {
            silent404Count == 1 -> try {
                // 先原地重新准备：只重拉播放列表跳过缺失分片，已缓冲内容全部保留，观感无感
                player.prepare()
            } catch (e: Exception) {
                scheduleReconnect(now)
            }

            silent404Count == 2 -> try {
                // 原地重拉不够，才回到直播边缘
                player.seekToDefaultPosition()
                player.prepare()
            } catch (e: Exception) {
                scheduleReconnect(now)
            }

            silent404Count == 3 -> try {
                // 连续 404：整条重建，播放列表会重新请求，拿到最新分片地址
                val item = currentMediaItem
                if (item != null) {
                    player.setMediaItem(item, C.TIME_UNSET)
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    scheduleReconnect(now)
                }
            } catch (e: Exception) {
                scheduleReconnect(now)
            }

            else -> scheduleReconnect(now)
        }
    }

    /** 播放中出错：安排在原线路上恢复（带退避），不换线路、不设次数上限 */
    private fun scheduleReconnect(now: Long) {
        if (suspended) return
        mainHandler.removeCallbacks(reconnectRunnable)
        if (lastReconnectMs > 0L && now - lastReconnectMs >= RECONNECT_RESET_WINDOW_MS) {
            reconnectAttempts = 0
        }
        reconnectAttempts++
        lastReconnectMs = now
        // 轻度退避：从 0.5 秒起、最多 4 秒。用户还在看就一直恢复，
        // 不再因为「重试 5 次用尽」把人丢在黑屏里。
        mainHandler.postDelayed(reconnectRunnable, minOf(500L * reconnectAttempts, 4000L))
    }

    /**
     * 执行原线路恢复：复用当前 MediaItem，回到直播边缘。
     * 不再 setMediaItem（那会清空画面、造成明显黑屏），只 seek + prepare，
     * 尽量保留最后一帧，让恢复过程几乎无感。
     */
    private fun performReconnect() {
        if (suspended) return
        if (currentMediaItem == null) return
        onReconnecting?.invoke(reconnectAttempts)
        try {
            // Media3 官方推荐的直播流恢复方式：先回直播点，再重新准备
            player.seekToDefaultPosition()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            // 极端状态（例如播放器正在释放）下退化为重建 MediaItem
            val item = currentMediaItem ?: return
            player.setMediaItem(item, C.TIME_UNSET)
            player.prepare()
            player.playWhenReady = true
        }
    }

    fun attachView(view: androidx.media3.ui.PlayerView) {
        view.player = player
    }

    /**
     * 按用户选择调整解码器优先顺序。
     * MediaCodecSelector 返回的列表顺序就是 Exo 的尝试顺序：
     * 把 softwareOnly 排到前面即为「软解优先」，排到后面即为「硬解优先」。
     * auto 直接用系统默认顺序，行为与旧版本完全一致。
     */
    private fun buildCodecSelector(priority: String): MediaCodecSelector {
        if (priority != "hardware" && priority != "software") return MediaCodecSelector.DEFAULT
        val preferSoftware = priority == "software"
        return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val infos: List<MediaCodecInfo> = MediaCodecUtil.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder
            )
            infos.sortedBy { if (it.softwareOnly == preferSoftware) 0 else 1 }
        }
    }

    fun release() {
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(freezeCheckRunnable)
        mainHandler.removeCallbacks(startWatchdogRunnable)
        mainHandler.removeCallbacks(audioCheckRunnable)
        audioCheckScheduled = false
        audioChecked = false
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (t: Throwable) {
            // 忽略：可能未注册成功，或老固件不支持
        }
        player.release()
    }
}

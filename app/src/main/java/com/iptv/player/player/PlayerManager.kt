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
    private val switchCooldownMs: Long = 8000L
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
        /** 卡死判定：进度静止超过该时长即视为卡死（直播正常播放时进度一定在走） */
        private const val FREEZE_TIMEOUT_MS = 12_000L
        /** 卡死巡检间隔 */
        private const val FREEZE_CHECK_INTERVAL_MS = 3_000L
        /** 时间窗内反复卡死达到该次数，说明这条线路已经不可用，直接换线路 */
        private const val FREEZE_SWITCH_THRESHOLD = 3
        private const val FREEZE_SWITCH_WINDOW_MS = 180_000L
        /** 连续播放这么久算「这条线路撑住了」，重置卡死换线计数 */
        private const val STABLE_PLAY_RESET_MS = 60_000L
        /** 所有线路都卡死后，暂停自动换线的时长，避免两条线路无限来回切 */
        private const val FREEZE_SWITCH_COOLDOWN_MS = 120_000L
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

    /** 最近一次真正渲染出视频帧的时间：解码器卡死时进度还在走，只能靠它识别 */
    private var lastVideoFrameAtMs = 0L
    private var hasVideoFrames = false

    /** 连续「卡死换线」次数：只有稳定播放够久才清零，防止两条线路无限来回切 */
    private var freezeSwitchChain = 0
    /** 本次稳定播放起点，用于判断线路是否已经撑住 */
    private var stableSinceMs = 0L
    /** 所有线路都卡死后的冷却截止时间 */
    private var freezeSwitchCooldownUntilMs = 0L

    /** 所有线路都卡住、自动换线已暂停时回调 */
    var onAllSourcesStuck: (() -> Unit)? = null

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

        // 硬解失败时允许回退软解，避免个别 TV 芯片在特定分辨率上直接卡死
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

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
                    // 保留 1 秒后向缓冲：直播中网络抖动导致分片晚到时，
                    // 播放器可回读一小段已缓冲内容顶住，而不是立刻进入 rebuffer。
                    // TVBox 类播放器抗抖动的关键之一，代价只是约 1 秒的时移。
                    .setBackBufferDurationMs(1_000)
                    .build()
            )
            .build()

        // 电视盒子长时间播放时 WiFi 会进入省电模式，表现为「播一会儿卡一下」，
        // 播放期间请求网络唤醒锁，避免无线链路休眠丢包。
        player.setWakeMode(C.WAKE_MODE_NETWORK)

        // 记录视频帧输出时间：解码器/渲染器卡死时播放进度照常在走，
        // 只有「多久没出帧」能识别这种情况。
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoFrameProcessingOffset(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                totalProcessingOffsetUs: Long,
                frameCount: Int
            ) {
                lastVideoFrameAtMs = System.currentTimeMillis()
                hasVideoFrames = true
            }
        })

        // 卡死看门狗：只靠错误回调不够——有些不健康的直播源既不报错也不出画面，
        // 播放器停在缓冲态「动也不动」，必须靠进度巡检兜底。
        mainHandler.postDelayed(freezeCheckRunnable, FREEZE_CHECK_INTERVAL_MS)

        // 网络变化监听：断网恢复后立即在原线路上重连，缩短黑屏时间
        networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                mainHandler.post {
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
            cm.registerDefaultNetworkCallback(networkCallback)
            connectivityManager = cm
        } catch (e: Exception) {
            connectivityManager = null
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // 真正出画面：确认这条线路可用，进入锁台状态
                if (state == Player.STATE_READY && player.playWhenReady) {
                    hasEverPlayed = true
                    consecutiveFailures = 0
                    silent404Count = 0
                    val now = System.currentTimeMillis()
                    // 距离上次重连已经很久：视为新一轮观看，恢复重连机会
                    if (lastReconnectMs > 0L && now - lastReconnectMs >= RECONNECT_RESET_WINDOW_MS) {
                        reconnectAttempts = 0
                    }
                    if (givenUp) givenUp = false
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val now = System.currentTimeMillis()
                val code = error.errorCode

                // 分片 404（错误码 2004）一律不上报界面：
                //  - 播放中：静默分级恢复，绝不弹「分片不存在」的红字；
                //  - 起播阶段：下面按普通起播失败处理，静默切下一条线路。
                val missingSegment = code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                if (missingSegment && hasEverPlayed) {
                    recoverFromMissingSegment(now)
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
        consecutiveFailures = 0
        givenUp = false
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

        val ranked = rankedIndices(channel, excludeTried = true)
        if (ranked.isEmpty()) return false
        switchTo(ranked.first(), C.TIME_UNSET)
        return true
    }

    /** 真正执行切源：记录尝试、设置媒体源、从直播点起播 */
    private fun switchTo(index: Int, positionMs: Long) {
        val channel = currentChannel ?: return
        val source = channel.sources.getOrNull(index) ?: return
        triedIndices.add(index)
        currentSourceIndex = index
        lastSwitchTimeMs = System.currentTimeMillis()

        val builder = MediaItem.Builder().setUri(source.url)
        // 地址里带 m3u8 就直接按 HLS 解析，避免被当成普通文件去下载
        if (source.url.contains(".m3u8", ignoreCase = true)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        val mediaItem = builder.build()
        currentMediaItem = mediaItem

        // 换线路 = 全新开始：解除锁台、清空重连计数、取消排队中的重连
        mainHandler.removeCallbacks(reconnectRunnable)
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
        lastVideoFrameAtMs = 0L
        hasVideoFrames = false

        player.setMediaItem(mediaItem, positionMs)
        player.prepare()
        player.playWhenReady = true
        channel.selectedIndex = index
        onSourceChanged?.invoke(source, index, channel.sources.size)
    }

    /**
     * 播放进度巡检：直播是连续流，只要在正常播放，currentPosition 一定在前进。
     * 进度静止超过 [FREEZE_TIMEOUT_MS] 就视为卡死，按「回直播点 → 重建播放项 → 换线路」逐级恢复。
     * 用户主动暂停（playWhenReady=false）时不计入。
     */
    private fun checkFreeze() {
        if (currentMediaItem == null) return
        // 起播阶段交给主界面的起播看门狗处理，这里只负责「已经播出来之后再卡死」
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

        // 两条判据：
        //  1) 播放进度不再前进 —— 网络/播放器卡在缓冲；
        //  2) 进度还在走但不出画面 —— 解码器或渲染器卡死，画面上只剩最后一帧。
        val progressStuck = !progressAdvanced && now - lastProgressAtMs >= FREEZE_TIMEOUT_MS
        val hasVideoTrack = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
        val framesStuck = hasVideoTrack && hasVideoFrames && lastVideoFrameAtMs > 0 &&
            now - lastVideoFrameAtMs >= FREEZE_TIMEOUT_MS
        if (!progressStuck && !framesStuck) return

        // ===== 判定卡死 =====
        lastProgressAtMs = now
        // 重置帧计时，避免恢复动作还没被验证就立刻再次判定卡死
        lastVideoFrameAtMs = now
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

    fun release() {
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.removeCallbacks(freezeCheckRunnable)
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // 忽略：可能未注册成功
        }
        player.release()
    }
}

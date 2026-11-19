package com.iptv.player.player

import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlin.math.max

/**
 * 卡顿检测器：
 *
 * 只把「播放中进入缓冲且持续超过 [minStallMs]」算作一次卡顿，
 * 短暂的缓冲抖动（直播每几秒重新拉一次分片时很常见）不算，
 * 丢帧只做统计展示、不再作为切源依据（盒子解码能力差异很大，丢几帧很正常）。
 *
 * 阈值全部由构造参数注入（来自用户的 PlaybackSettings）。
 */
class StallDetector(
    private val onStall: () -> Unit,
    private val onStats: ((bufferCount: Int, droppedFrames: Int, bwKbps: Long) -> Unit)? = null,
    private val windowMs: Long = 12_000L,
    private val stallCountThreshold: Int = 3,
    private val cooldownMs: Long = 10_000L,
    /** 单次缓冲至少持续多久才算一次卡顿 */
    private val minStallMs: Long = 1_500L,
    /** 起播后多久内不统计卡顿（首屏缓冲不算） */
    private val graceMs: Long = 8_000L
) : AnalyticsListener {

    private val stallTimestamps = ArrayDeque<Long>()
    private var wasPlaying = false
    private var bufferStartedAt = 0L
    private var lastTriggerMs = 0L
    private var startedAtMs = System.currentTimeMillis()
    private var droppedFramesAccum = 0
    private var bandwidthKbps = 0L

    override fun onPlaybackStateChanged(
        eventTime: AnalyticsListener.EventTime,
        state: Int
    ) {
        val now = System.currentTimeMillis()
        when (state) {
            Player.STATE_READY -> {
                // 离开缓冲态：只有持续足够久才算一次真卡顿
                if (bufferStartedAt > 0L && now - bufferStartedAt >= minStallMs) {
                    recordStall(now)
                }
                bufferStartedAt = 0L
                wasPlaying = true
            }
            Player.STATE_BUFFERING -> {
                // 只有"曾播放过再进入缓冲"才算，起播首次缓冲不计
                if (wasPlaying && now - startedAtMs >= graceMs) bufferStartedAt = now
            }
            Player.STATE_ENDED, Player.STATE_IDLE -> {
                wasPlaying = false
                bufferStartedAt = 0L
            }
        }
        prune()
        onStats?.invoke(stallTimestamps.size, droppedFramesAccum, bandwidthKbps)
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long
    ) {
        // 仅统计展示：盒子解码能力差异大，丢帧不代表网络卡，不能据此换源
        droppedFramesAccum += droppedFrames
        prune()
        onStats?.invoke(stallTimestamps.size, droppedFramesAccum, bandwidthKbps)
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long
    ) {
        // bitrateEstimate 单位为 bit/s，换算为 Kbps
        bandwidthKbps = bitrateEstimate / 1000
    }

    /** 切换线路后由外部调用：清空计数、重置起播宽限期并进入冷却 */
    fun onSourceSwitched() {
        stallTimestamps.clear()
        droppedFramesAccum = 0
        wasPlaying = false
        bufferStartedAt = 0L
        startedAtMs = System.currentTimeMillis()
        lastTriggerMs = System.currentTimeMillis()
    }

    private fun recordStall(now: Long) {
        stallTimestamps.addLast(now)
        if (stallTimestamps.size >= stallCountThreshold && now - lastTriggerMs >= cooldownMs) {
            lastTriggerMs = now
            stallTimestamps.clear()
            onStall()
        }
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        while (stallTimestamps.isNotEmpty() && now - stallTimestamps.first() > windowMs) {
            stallTimestamps.removeFirst()
        }
        // 丢帧计数缓慢衰减，避免长期累积
        droppedFramesAccum = max(0, droppedFramesAccum - 1)
    }
}

package com.iptv.player.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlin.math.max

/**
 * 卡顿检测器：
 *
 * 两类卡顿都要能发现，否则会出现「明明一直转圈，却从不换线路」：
 *  1. 间歇卡顿：播放中进入缓冲、持续超过 [minStallMs] 后恢复，窗口内累计到阈值算一次；
 *  2. 硬卡死：进入缓冲后长时间回不到 READY（分片一直拉不到）。
 *     旧实现只统计「离开缓冲」的时刻，永久卡在缓冲态的频道因此永远不计数——
 *     这是「卡顿不自动换线」的另一个根因，这里用定时巡检补上。
 *
 * 丢帧只做统计展示、不作为切源依据（盒子解码能力差异很大，丢几帧很正常）。
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
    private val graceMs: Long = 8_000L,
    /** 持续缓冲超过该时长直接判定为硬卡死，一次即触发换线 */
    private val hardStallMs: Long = 8_000L,
    /** 用户是否主动暂停：暂停时不应把「停在缓冲态」算成卡顿，更不能因此换线 */
    private val isPaused: () -> Boolean = { false }
) : AnalyticsListener {

    private val handler = Handler(Looper.getMainLooper())
    private val stallTimestamps = ArrayDeque<Long>()
    private var wasPlaying = false
    private var bufferStartedAt = 0L
    private var lastTriggerMs = 0L
    private var startedAtMs = System.currentTimeMillis()
    private var droppedFramesAccum = 0
    private var bandwidthKbps = 0L
    /** 当前是否停在缓冲态：硬卡死定时器据此决定要不要继续观察 */
    private var buffering = false

    private val hardStallCheck = object : Runnable {
        override fun run() {
            if (!buffering || bufferStartedAt <= 0L) return
            if (isPaused()) {
                // 用户暂停期间不判卡顿
                buffering = false
                bufferStartedAt = 0L
                return
            }
            val now = System.currentTimeMillis()
            if (now - bufferStartedAt < hardStallMs) {
                handler.postDelayed(this, hardStallMs)
                return
            }
            recordStall(now, hard = true)
            // 换线失败（单线路 / 冷却中）时继续观察，别让这条线路永久卡在黑屏
            if (buffering) {
                bufferStartedAt = System.currentTimeMillis()
                handler.postDelayed(this, hardStallMs)
            }
        }
    }

    override fun onPlaybackStateChanged(
        eventTime: AnalyticsListener.EventTime,
        state: Int
    ) {
        val now = System.currentTimeMillis()
        when (state) {
            Player.STATE_READY -> {
                // 离开缓冲态：先结算本次缓冲时长，再清理状态。
                // 顺序很重要：recordStall 可能同步触发换线并回调 onSourceSwitched，
                // 若在它之后再写状态，会把新线路的计数状态覆盖掉。
                val bufferedMs = if (bufferStartedAt > 0L) now - bufferStartedAt else 0L
                bufferStartedAt = 0L
                buffering = false
                handler.removeCallbacks(hardStallCheck)
                wasPlaying = true
                if (bufferedMs >= minStallMs) recordStall(now)
            }
            Player.STATE_BUFFERING -> {
                if (isPaused()) {
                    // 用户暂停时不累计卡顿时长
                    buffering = false
                    bufferStartedAt = 0L
                    handler.removeCallbacks(hardStallCheck)
                } else if (wasPlaying && now - startedAtMs >= graceMs) {
                    // 只有"曾播放过再进入缓冲"才算，起播首次缓冲不计
                    buffering = true
                    if (bufferStartedAt <= 0L) {
                        bufferStartedAt = now
                        handler.removeCallbacks(hardStallCheck)
                        handler.postDelayed(hardStallCheck, hardStallMs)
                    }
                }
            }
            Player.STATE_ENDED, Player.STATE_IDLE -> {
                wasPlaying = false
                buffering = false
                bufferStartedAt = 0L
                handler.removeCallbacks(hardStallCheck)
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
        handler.removeCallbacks(hardStallCheck)
        stallTimestamps.clear()
        droppedFramesAccum = 0
        wasPlaying = false
        buffering = false
        bufferStartedAt = 0L
        startedAtMs = System.currentTimeMillis()
        lastTriggerMs = System.currentTimeMillis()
    }

    /** 播放器释放时调用，移除定时任务，避免界面销毁后仍持有引用 */
    fun release() {
        handler.removeCallbacks(hardStallCheck)
        buffering = false
        bufferStartedAt = 0L
    }

    /**
     * @param hard true = 持续缓冲硬卡死，单次即触发；
     *             false = 间歇卡顿恢复，累计到阈值才触发。
     */
    private fun recordStall(now: Long, hard: Boolean = false) {
        stallTimestamps.addLast(now)
        val reached = hard || stallTimestamps.size >= stallCountThreshold
        if (!reached) return
        if (now - lastTriggerMs < cooldownMs) return

        lastTriggerMs = now
        stallTimestamps.clear()
        // 先清掉缓冲起点：onStall 里可能同步切源并回调 onSourceSwitched，
        // 之后的硬卡死定时器不应该把新线路的起播缓冲当成旧线路的卡顿。
        bufferStartedAt = 0L
        handler.removeCallbacks(hardStallCheck)
        onStall()
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

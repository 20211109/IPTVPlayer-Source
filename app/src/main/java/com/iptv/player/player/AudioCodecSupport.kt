package com.iptv.player.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * 音频编码识别：判断当前频道的音轨是否需要交给带 FFmpeg 软解的兜底内核。
 *
 * 背景（与 iptv-finder-docker 对接时最常见的问题）：
 * 自动抓取到的直播源里会混有 AC-3 / E-AC-3 / MP2 音轨的频道。安卓盒子普遍只带
 * AAC/MP3 解码器，遇到这类音轨时 ExoPlayer 的表现是：
 *  - 找不到解码器：把音轨标记为「不支持」后静默跳过 —— 有画面、没声音、也不报错；
 *  - 解码器存在但只会直通（passthrough）：同样听不到声音；
 *  - 少数盒子直接抛初始化失败。
 * 只靠 onPlayerError 永远等不到第一种情况，所以必须在出画后主动识别编码并切换内核。
 */
object AudioCodecSupport {

    /**
     * 需要软件解码（FFmpeg）的音频编码。
     *
     * 这里把 AC-3/E-AC-3 也算进来：盒子上「有解码器但只支持直通」的情况极常见，
     * 此时 Exo 不会报错，只是没有声音，用户完全无从判断。统一交给软解内核最稳。
     */
    private val SOFTWARE_DECODE_MIMES = setOf(
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_MPEG_L1,
        MimeTypes.AUDIO_MPEG_L2,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD,
        MimeTypes.AUDIO_DTS_EXPRESS,
        MimeTypes.AUDIO_TRUEHD
    )

    /** 音轨识别结果 */
    data class Info(
        /** 第一路音轨的 mime，null 表示还没解析出音轨 */
        val mime: String?,
        /** 给用户看的编码名：AC-3 / E-AC-3 / MP2 / AAC … */
        val label: String,
        /** 是否存在 Exo 认为可解码的音轨 */
        val hasUsableTrack: Boolean,
        /** 是否解析到了音轨（区分「没有音轨」和「音轨信息还没到」） */
        val hasAnyAudioTrack: Boolean,
        /** 该编码在盒子上容易出现无声/解码失败，建议走软解内核 */
        val needsSoftwareDecode: Boolean
    )

    /** 从 Media3 的轨道信息里挑出音频编码 */
    fun inspect(tracks: Tracks): Info {
        var mime: String? = null
        var usable = false
        var any = false

        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            any = true
            if (group.isSupported) usable = true
            if (mime == null) mime = firstMime(group)
        }

        return Info(
            mime = mime,
            label = labelOf(mime),
            hasUsableTrack = usable,
            hasAnyAudioTrack = any,
            needsSoftwareDecode = mime != null && mime in SOFTWARE_DECODE_MIMES
        )
    }

    /** 编码的展示名，用于状态栏/提示，便于用户反馈问题时截图定位 */
    fun labelOf(mime: String?): String = when (mime) {
        MimeTypes.AUDIO_AC3 -> "AC-3"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3"
        MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2 -> "MP2"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        else -> "未知"
    }

    /**
     * 设备自带的解码器数量（0 = 没有该编码的硬解）。
     * 仅用于诊断提示，不参与切换决策：Media3 的 group.isSupported 已经包含了这层判断，
     * 而「有解码器但只会直通」导致无声的场景，正是我们要绕开的。
     */
    fun deviceDecoderCount(mime: String?): Int {
        if (mime.isNullOrEmpty()) return 0
        return try {
            MediaCodecUtil.getDecoderInfos(mime, false, false).size
        } catch (t: Throwable) {
            0
        }
    }

    private fun firstMime(group: Tracks.Group): String? {
        for (i in 0 until group.length) {
            val format = try {
                group.getTrackFormat(i)
            } catch (t: Throwable) {
                null
            } ?: continue
            format.sampleMimeType?.let { return it }
        }
        return null
    }
}

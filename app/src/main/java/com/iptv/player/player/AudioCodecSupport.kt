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
     * 「设备可能解不了」的音频编码 —— 只是**候选名单**，不是判决书。
     *
     * 真正的切换条件见 [inspect] 里的 needsSoftwareDecode：必须同时满足
     * 「编码在这份名单里」且「Exo 自己报这条轨不被支持」。
     *
     * 千万不能只看编码就切内核：小米盒子 4 这类 Amlogic 机型本身带 MP2/AC-3
     * 硬解，Exo 放得好好的，一检测到 MP2 就切 VLC，反而把有声的频道弄成
     * 画面缩角、遥控器失灵、还卡顿。
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
            // 关键：只有「编码在候选名单里」**并且**「Exo 明确解不了」才切软解。
            // 设备自己能解（小米盒子 4 的 MP2/AC-3 硬解就是这种情况）时一律留在
            // ExoPlayer 主内核，否则会把本来有声音的频道切到 VLC 上受二茬罪。
            needsSoftwareDecode = mime != null && mime in SOFTWARE_DECODE_MIMES && !usable
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

package com.iptv.player.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.AbstractVLCEvent

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
 */
class VlcFallbackPlayer(
    private val context: Context,
    private val host: ViewGroup,
    private val onPrepared: () -> Unit,
    private val onFailed: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var surfaceView: SurfaceView? = null
    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var zoomMode = "fit"
    private var zoomScalePct = 100
    /** 已释放或已失败：所有异步回调都要立刻失效，避免释放后仍回调界面 */
    private var released = false

    /**
     * 起播。返回 false 表示内核不可用（例如 ABI 不匹配、so 缺失），
     * 调用方应直接回退到「无声播放」而不要再等超时。
     */
    fun play(url: String): Boolean {
        release()
        released = false
        return try {
            val sv = SurfaceView(context)
            sv.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            host.addView(sv)
            surfaceView = sv

            // 直播场景的取舍：宁可多缓存一点也别丢帧跳帧，
            // 「--no-drop-late-frames/--no-skip-frames」能避免弱源上出现马赛克跳帧。
            val vlc = LibVLC(context, ArrayList(VLC_ARGS))
            libVlc = vlc
            val mp = MediaPlayer(vlc)
            player = mp

            // VLC 的默认音频输出就是 AudioTrack，不需要额外指定；
            // 但如果系统音量被上一次播放器压到 0，这里显式拉回满音量。
            try {
                mp.setVolume(100)
            } catch (t: Throwable) {
                // 个别机型 setVolume 需要 native 就绪，失败不影响起播
            }

            val vout = mp.getVLCVout()
            vout.setVideoView(sv)
            vout.setWindowSize(
                if (host.width > 0) host.width else 1920,
                if (host.height > 0) host.height else 1080
            )
            vout.attachViews()

            mp.setEventListener(object : MediaPlayer.EventListener {
                override fun onEvent(event: AbstractVLCEvent) {
                    if (released) return
                    when (event.type) {
                        // Playing 才是「真的有输出了」：此时音频/视频轨都已解出来，
                        // 不像 IJK 的 prepared 那样只代表容器解析成功。
                        MediaPlayer.Event.Playing -> mainHandler.post {
                            if (!released) {
                                applyScaleType()
                                layoutSurface()
                                onPrepared()
                            }
                        }

                        MediaPlayer.Event.EncounteredError -> mainHandler.post {
                            if (!released) fail("内核无法解码该流")
                        }
                    }
                }
            })

            val media = Media(vlc, Uri.parse(url))
            // 视频优先硬解，不允许时自动退回软解；音频始终由 FFmpeg 解码（MP2/AC-3 靠这个）
            media.setHWDecoderEnabled(true, true)
            media.addOption(":network-caching=${NETWORK_CACHING_MS}")
            media.addOption(":live-caching=${NETWORK_CACHING_MS}")
            mp.setMedia(media)
            media.release()
            mp.play()
            true
        } catch (t: Throwable) {
            fail("兼容内核不可用：${t.message ?: t.javaClass.simpleName}")
            false
        }
    }

    /** 暂停/继续；返回切换后的状态（true = 正在播放） */
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
        zoomScalePct = scalePct
        applyScaleType()
        layoutSurface()
        host.post { layoutSurface() }
    }

    private fun applyScaleType() {
        val mp = player ?: return
        try {
            mp.setVideoScale(
                when (zoomMode) {
                    "crop" -> MediaPlayer.ScaleType.SURFACE_FILL
                    "stretch" -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
                    else -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
                }
            )
        } catch (t: Throwable) {
            // 画面比例失败不影响播放
        }
    }

    /** 只有「自定义缩放」需要改视图尺寸；fit/crop/stretch 交给 VLC 自己算 */
    private fun layoutSurface() {
        if (released) return
        val sv = surfaceView ?: return
        val lp = sv.layoutParams as? FrameLayout.LayoutParams ?: return
        if (zoomMode == "scale" && host.width > 0 && host.height > 0) {
            val factor = zoomScalePct.coerceIn(50, 200) / 100f
            lp.width = (host.width * factor).toInt().coerceAtLeast(1)
            lp.height = (host.height * factor).toInt().coerceAtLeast(1)
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        lp.gravity = Gravity.CENTER
        sv.layoutParams = lp
    }

    /** 失败收尾：释放自身并把原因交回界面，由界面决定回退到「无声播放」 */
    private fun fail(reason: String) {
        if (released) return
        released = true
        detach()
        onFailed(reason)
    }

    fun release() {
        if (released) return
        released = true
        detach()
    }

    private fun detach() {
        val mp = player
        val vlc = libVlc
        player = null
        libVlc = null
        try {
            // 顺序很关键：先解绑视图，再 stop，最后 release；
            // 反过来在部分机型上会留一个黑 Surface 不退，回到 Exo 后画面全黑。
            mp?.getVLCVout()?.detachViews()
        } catch (t: Throwable) {
            // 忽略
        }
        try {
            mp?.setEventListener(null)
        } catch (t: Throwable) {
            // 个别版本不接受 null，忽略即可
        }
        try {
            mp?.stop()
            mp?.release()
        } catch (t: Throwable) {
            // 释放阶段的异常一律忽略，绝不能影响界面回退流程
        }
        try {
            vlc?.release()
        } catch (t: Throwable) {
            // 忽略
        }
        surfaceView?.let { sv ->
            try {
                host.removeView(sv)
            } catch (t: Throwable) {
                // 忽略
            }
        }
        surfaceView = null
    }

    private companion object {
        /** 直播缓存（毫秒）：太小会频繁缓冲，太大换台延迟明显，1200 在盒子上比较平衡 */
        const val NETWORK_CACHING_MS = 1200

        val VLC_ARGS = listOf(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--audio-time-stretch",
            "--no-sub-autodetect-file"
        )
    }
}

package com.iptv.player.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * MP2/AC3 等「设备自带解码器缺失」场景的兜底播放器。
 *
 * 设计目标：
 * - 视频仍走 MediaCodec 硬解（低端盒子也不掉帧）；
 * - 音频由 IJK 自带的 FFmpeg 软解，保证 MP2 一定能出声；
 * - 只在 ExoPlayer 明确报出音频解码失败后临时接管，正常播放全程仍然用 Exo，
 *   因此不会影响既有播放体验，也不会额外增加运行时开销。
 *
 * 资源占用：IJK 的 MediaCodec 实例与 Exo 互斥，接管前调用方必须先 stop 掉 Exo，
 * 否则部分盒子上会出现「硬解码器被占用、兜底内核起不来」。
 */
class IjkFallbackPlayer(
    private val context: Context,
    private val host: ViewGroup,
    private val onPrepared: () -> Unit,
    private val onFailed: (String) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var surfaceView: SurfaceView? = null
    private var player: IjkMediaPlayer? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var zoomMode = "fit"
    private var zoomScalePct = 100
    /** 已释放或已失败：所有异步回调都要立刻失效，避免释放后仍回调界面 */
    private var released = false

    /**
     * 起播。返回 false 表示内核不可用（例如 x86 设备没有对应 .so），
     * 调用方应直接回退到「无声播放」而不要再等待超时。
     *
     * @param softwareVideo true = 视频也走 FFmpeg 软解（mediacodec=0）。
     *        少数盒子上 IJK 与 MediaCodec 组合会直接起不来，此时优先保住声音：
     *        软解视频虽然更吃 CPU，但 AC-3/MP2 频道至少能正常出声。
     *        音频始终由 FFmpeg 解码，不受该参数影响。
     */
    fun play(url: String, softwareVideo: Boolean = false): Boolean {
        release()
        released = false
        return try {
            val sv = SurfaceView(context)
            sv.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            // Surface 创建/销毁是异步的：必须回调里重新 setDisplay，
            // 否则在 Surface 还没就绪时 prepare 会导致「有声音没画面」。
            sv.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    if (!released) {
                        try {
                            player?.setDisplay(holder)
                        } catch (t: Throwable) {
                            // 忽略
                        }
                    }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    // 尺寸变化由 layoutSurface 处理，这里无需额外操作
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    try {
                        player?.setDisplay(null)
                    } catch (t: Throwable) {
                        // 忽略
                    }
                }
            })
            host.addView(sv)
            surfaceView = sv

            // 只加载一次 .so；x86 模拟器等没有对应 ABI 时会抛 UnsatisfiedLinkError，
            // 由外层 catch 统一转为「兜底失败」，绝不影响主流程。
            IjkMediaPlayer.loadLibrariesOnce(null)

            val mp = IjkMediaPlayer()
            player = mp
            // SurfaceView 在部分机型上会在 addView 时同步触发 surfaceCreated，
            // 那时 player 还没创建，回调会被跳过；这里立即补一次绑定。
            attachDisplayIfReady()

            // 视频解码：默认 MediaCodec 硬解；硬解起不来时退回 FFmpeg 软解。
            // 音频不设 mediacodec，始终走 FFmpeg 软解 —— AC-3/E-AC-3/MP2 才能真正出声。
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", if (softwareVideo) 0L else 1L)
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L)
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1L)
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1L)
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "reconnect", 1L)
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1L)
            // 网络超时（微秒）：坏源不会永久卡在启动阶段（界面另有 12 秒超时兜底）
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 15_000_000L)
            // 与 Exo 保持一致 UA：很多直播源只放行常见播放器
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", "VLC/3.0.18 LibVLC/3.0.18")
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1_000_000L)

            mp.setOnPreparedListener {
                if (!released) {
                    mainHandler.post {
                        if (!released) {
                            attachDisplayIfReady()
                            layoutSurface()
                            onPrepared()
                        }
                    }
                }
            }
            mp.setOnVideoSizeChangedListener { _, width, height, _, _ ->
                if (!released) {
                    videoWidth = width
                    videoHeight = height
                    mainHandler.post { if (!released) layoutSurface() }
                }
            }
            mp.setOnErrorListener { _, what, extra ->
                if (!released) {
                    mainHandler.post { fail("播放失败（$what/$extra）") }
                }
                true
            }

            mp.setScreenOnWhilePlaying(true)
            // 不在这里直接 setDisplay：Surface 还没创建时传入无效 Surface 容易出问题，
            // 统一由 surfaceCreated 回调（或 prepared 时的有效性检查）完成绑定。
            mp.setDataSource(url)
            mp.prepareAsync()
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
                mp.start()
                true
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** Surface 已就绪时绑定显示（正常由 surfaceCreated 回调完成，这里用于兜底与恢复） */
    private fun attachDisplayIfReady() {
        val mp = player ?: return
        val holder = surfaceView?.holder ?: return
        try {
            val surface = holder.surface
            if (surface != null && surface.isValid) {
                mp.setDisplay(holder)
            }
        } catch (t: Throwable) {
            // 忽略
        }
    }

    /** 画面比例/缩放：与 Exo 侧保持同一套语义（fit/crop/stretch/scale） */
    fun applyLayout(mode: String, scalePct: Int) {
        zoomMode = mode
        zoomScalePct = scalePct
        layoutSurface()
        host.post { layoutSurface() }
    }

    private fun layoutSurface() {
        if (released) return
        val sv = surfaceView ?: return
        val hostW = host.width
        val hostH = host.height
        if (hostW <= 0 || hostH <= 0) return
        val lp = sv.layoutParams as? FrameLayout.LayoutParams ?: return

        if (videoWidth <= 0 || videoHeight <= 0) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            val videoRatio = videoWidth.toFloat() / videoHeight
            val hostRatio = hostW.toFloat() / hostH
            when (zoomMode) {
                "stretch" -> {
                    lp.width = hostW
                    lp.height = hostH
                }

                "crop" -> {
                    // 等比铺满并裁掉多余部分
                    if (videoRatio > hostRatio) {
                        lp.height = hostH
                        lp.width = (hostH * videoRatio).toInt()
                    } else {
                        lp.width = hostW
                        lp.height = (hostW / videoRatio).toInt()
                    }
                }

                else -> {
                    // fit 与 scale 都保持比例：scale 在此之上再按百分比放大/缩小
                    val factor = if (zoomMode == "scale") {
                        zoomScalePct.coerceIn(50, 200) / 100f
                    } else {
                        1f
                    }
                    if (videoRatio > hostRatio) {
                        lp.width = (hostW * factor).toInt()
                        lp.height = (hostW / videoRatio * factor).toInt()
                    } else {
                        lp.height = (hostH * factor).toInt()
                        lp.width = (hostH * videoRatio * factor).toInt()
                    }
                }
            }
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
        player = null
        try {
            mp?.setOnPreparedListener(null)
            mp?.setOnVideoSizeChangedListener(null)
            mp?.setOnErrorListener(null)
            mp?.setDisplay(null)
            mp?.release()
        } catch (t: Throwable) {
            // 释放阶段的异常一律忽略，绝不能影响界面回退流程
        }
        surfaceView?.let { sv ->
            try {
                host.removeView(sv)
            } catch (t: Throwable) {
                // 忽略
            }
        }
        surfaceView = null
        videoWidth = 0
        videoHeight = 0
    }
}

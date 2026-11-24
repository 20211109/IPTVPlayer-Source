package com.iptv.player.player

import android.content.Context
import org.videolan.libvlc.LibVLC
import java.util.concurrent.Executors

/**
 * 全局共享的 LibVLC 实例。
 *
 * 为什么必须是单例（v1.57 的改动）：
 *
 * 1. `LibVLC(context)` 要加载约 50MB 的 so、初始化几百个模块，盒子上实测 1–3 秒，
 *    而旧代码是在**主线程**的 `startEngine()` 里 new 的 —— 这就是「切到软解后整机
 *    卡好几秒、遥控器按不动」的主要来源。
 * 2. 旧代码每次重建画面（横竖屏、比例变化）都会 `release()` 旧的再 new 一个新的。
 *    反复创建/销毁是 libvlc 最容易出内部状态问题的用法，表现就是「起播正常、
 *    播几秒后画面定住不动」。
 *
 * 现在只创建一次：所有 MediaPlayer 都挂在这一个 LibVLC 上，切换/重建时只销毁
 * MediaPlayer，LibVLC 一直活着。创建动作放在后台线程，界面永不阻塞。
 */
object VlcCore {

    /**
     * 启动参数。这里的每一项都直接影响「能不能稳定播下去」：
     *
     * - `--drop-late-frames` / `--skip-frames`：解码跟不上时**允许丢帧**。
     *   旧代码写的是 `--no-drop-late-frames` + `--no-skip-frames`，本意是「别跳帧、
     *   宁可卡一点也别出马赛克」，但直播流是无限的：一旦解码速度低于码率，帧队列
     *   只会越积越多、延迟越来越大，最后彻底不动 —— 这正是「播 5 秒就卡死」的成因。
     * - `--no-video-title-show` / `--no-osd` / `--no-spu`：起播时不闪文件名、
     *   不加载字幕，少一点多余开销。
     * - `--audio-time-stretch`：音频跟随时间戳伸缩，弱网抖动时不容易音画分离。
     */
    private val ARGS = arrayListOf(
        "--drop-late-frames",
        "--skip-frames",
        "--audio-time-stretch",
        "--no-sub-autodetect-file",
        "--no-video-title-show",
        "--no-osd",
        "--no-spu",
        // 直播场景不需要播放列表/媒体库扫描
        "--no-media-library"
    )

    @Volatile
    private var instance: LibVLC? = null

    /** 已经尝试过创建并且失败（so 缺失、ABI 不匹配）：不要再反复卡一次 */
    @Volatile
    private var broken = false

    private val lock = Any()

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vlc-core").apply { isDaemon = true }
    }

    /**
     * 预热：在后台线程把 LibVLC 建好，真正需要切软解时直接复用，几乎零等待。
     * 失败也不抛异常 —— 只是下次真正要用时再试一次。
     */
    fun warmUp(context: Context) {
        if (instance != null || broken) return
        val app = context.applicationContext
        try {
            ioExecutor.execute { obtain(app) }
        } catch (t: Throwable) {
            // 线程池不可用：忽略，走同步创建
        }
    }

    /** 取（必要时创建）共享实例；创建失败返回 null，调用方应回退到无声播放 */
    fun obtain(context: Context): LibVLC? {
        instance?.let { return it }
        if (broken) return null
        synchronized(lock) {
            instance?.let { return it }
            if (broken) return null
            return try {
                LibVLC(context.applicationContext, ARGS).also { instance = it }
            } catch (t: Throwable) {
                broken = true
                null
            }
        }
    }

    /** 实例是否已经就绪（用于界面判断要不要显示「准备中」） */
    fun isReady(): Boolean = instance != null
}

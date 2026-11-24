package com.iptv.player.config

import android.content.Context

/**
 * 可调播放参数：全部落在 SharedPreferences，用户可在「设置」弹窗里修改，实时生效于下次起播/检测。
 */
class PlaybackSettings(context: Context) {

    private val sp = context.getSharedPreferences("playback_settings", Context.MODE_PRIVATE)

    companion object {
        const val KEY_STALL_WINDOW = "stall_window_s"          // 卡顿统计窗口（秒）
        const val KEY_STALL_COUNT = "stall_count"              // 窗口内卡顿次数阈值
        const val KEY_STALL_COOLDOWN = "stall_cooldown_s"      // 切源冷却（秒）
        // 换 key：v1.26 起默认缓冲加大（10/25 → 15/40，卡顿后 3s → 4s），
        // 老用户 SharedPreferences 里如果存过旧值，换 key 才能让新默认值生效。
        const val KEY_BUFFER_MIN = "buffer_min_s_v2"           // 最小缓冲（秒）
        const val KEY_BUFFER_MAX = "buffer_max_s_v2"           // 最大缓冲（秒）
        const val KEY_PLAY_START = "play_start_ms_v2"          // 起播缓冲（毫秒）
        const val KEY_REBUFFER = "rebuffer_ms_v2"              // 卡顿后起播缓冲（毫秒）
        const val KEY_SHOW_STATS = "show_stats"                // 是否显示调试状态条
        const val KEY_MAX_RETRY = "max_retry"                  // 连续失败熔断阈值
        // 换 key：改为「默认开启」。老版本里该键已写入 false，换 key 才能让新默认对老用户生效。
        const val KEY_INSECURE_TLS = "insecure_tls_v2"         // 是否忽略证书校验（自签名源）
        // 换 key：老版本用户 SharedPreferences 里已写入 true，改默认值对他们不生效。
        // 换 key 后所有人回落到 v1.9 的新默认（关闭），避免观看中被自动切台。
        const val KEY_AUTO_SWITCH = "auto_switch_on_stall_v2"  // 卡顿后是否自动换线路

        // 画面缩放 / 比例
        const val KEY_ZOOM_MODE = "zoom_mode"                  // fit / stretch / crop / scale
        const val KEY_ZOOM_SCALE = "zoom_scale_pct"            // 自定义缩放百分比 50–200
        // 解码与渲染
        const val KEY_DECODER_PRIORITY = "decoder_priority"    // auto / hardware / software
        const val KEY_RENDER_MODE = "render_mode"              // surface / texture
        const val KEY_AUDIO_ENABLED = "audio_enabled"          // 关掉即「仅视频」

        // 默认值
        const val DEF_STALL_WINDOW = 12
        const val DEF_STALL_COUNT = 3
        const val DEF_STALL_COOLDOWN = 10
        // 直播源分片一般 5 秒一个：最小缓冲 15 秒 = 常备三个分片，
        // 最大 40 秒可吸收弱网抖动（超出直播窗口的部分会被自动忽略）。
        const val DEF_BUFFER_MIN = 15
        const val DEF_BUFFER_MAX = 40
        const val DEF_PLAY_START = 1500
        // 卡顿后至少攒够 4 秒再恢复，否则刚恢复就再次耗尽形成连环卡顿
        const val DEF_REBUFFER = 4000
        const val DEF_MAX_RETRY = 6
        const val DEF_ZOOM_MODE = "fit"
        const val DEF_ZOOM_SCALE = 100
        const val DEF_DECODER_PRIORITY = "auto"
        const val DEF_RENDER_MODE = "surface"
    }

    var stallWindowS: Int
        get() = sp.getInt(KEY_STALL_WINDOW, DEF_STALL_WINDOW)
        set(v) = sp.edit().putInt(KEY_STALL_WINDOW, v).apply()

    var stallCount: Int
        get() = sp.getInt(KEY_STALL_COUNT, DEF_STALL_COUNT)
        set(v) = sp.edit().putInt(KEY_STALL_COUNT, v).apply()

    var stallCooldownS: Int
        get() = sp.getInt(KEY_STALL_COOLDOWN, DEF_STALL_COOLDOWN)
        set(v) = sp.edit().putInt(KEY_STALL_COOLDOWN, v).apply()

    var bufferMinS: Int
        get() = sp.getInt(KEY_BUFFER_MIN, DEF_BUFFER_MIN)
        set(v) = sp.edit().putInt(KEY_BUFFER_MIN, v).apply()

    var bufferMaxS: Int
        get() = sp.getInt(KEY_BUFFER_MAX, DEF_BUFFER_MAX)
        set(v) = sp.edit().putInt(KEY_BUFFER_MAX, v).apply()

    var playStartMs: Int
        get() = sp.getInt(KEY_PLAY_START, DEF_PLAY_START)
        set(v) = sp.edit().putInt(KEY_PLAY_START, v).apply()

    var rebufferMs: Int
        get() = sp.getInt(KEY_REBUFFER, DEF_REBUFFER)
        set(v) = sp.edit().putInt(KEY_REBUFFER, v).apply()

    var showStats: Boolean
        get() = sp.getBoolean(KEY_SHOW_STATS, false)
        set(v) = sp.edit().putBoolean(KEY_SHOW_STATS, v).apply()

    var maxRetry: Int
        get() = sp.getInt(KEY_MAX_RETRY, DEF_MAX_RETRY)
        set(v) = sp.edit().putInt(KEY_MAX_RETRY, v).apply()

    /** 卡顿过多时是否自动换线路（v1.9 起默认关闭：播放中锁台，原线路重连不跳台） */
    var autoSwitchOnStall: Boolean
        get() = sp.getBoolean(KEY_AUTO_SWITCH, false)
        set(v) = sp.edit().putBoolean(KEY_AUTO_SWITCH, v).apply()

    /**
     * 忽略 HTTPS 证书校验。
     * 默认开启：IPTV 源大量使用自签名/过期证书，关着会让整条线路直接拉流失败（黑屏），
     * 对普通用户来说「能看」优先于严格校验；需要严格校验的用户可以自己关掉。
     */
    var insecureTls: Boolean
        get() = sp.getBoolean(KEY_INSECURE_TLS, true)
        set(v) = sp.edit().putBoolean(KEY_INSECURE_TLS, v).apply()

    /** 画面比例：fit 适应 / stretch 拉伸铺满 / crop 裁切铺满 / scale 自定义缩放 */
    var zoomMode: String
        get() = sp.getString(KEY_ZOOM_MODE, DEF_ZOOM_MODE) ?: DEF_ZOOM_MODE
        set(v) = sp.edit().putString(KEY_ZOOM_MODE, v).apply()

    /** 自定义缩放百分比（50–200），仅在 zoomMode == "scale" 时生效 */
    var zoomScalePct: Int
        get() = sp.getInt(KEY_ZOOM_SCALE, DEF_ZOOM_SCALE)
        set(v) = sp.edit().putInt(KEY_ZOOM_SCALE, v).apply()

    /** 解码器优先策略：auto 自动 / hardware 硬解优先 / software 软解优先 */
    var decoderPriority: String
        get() = sp.getString(KEY_DECODER_PRIORITY, DEF_DECODER_PRIORITY) ?: DEF_DECODER_PRIORITY
        set(v) = sp.edit().putString(KEY_DECODER_PRIORITY, v).apply()

    /** 渲染方式：surface（省电、老盒子兼容好）/ texture（支持动画缩放、截图） */
    var renderMode: String
        get() = sp.getString(KEY_RENDER_MODE, DEF_RENDER_MODE) ?: DEF_RENDER_MODE
        set(v) = sp.edit().putString(KEY_RENDER_MODE, v).apply()

    /** 是否输出声音；关闭即「仅视频」（音频解码器缺失时最稳） */
    var audioEnabled: Boolean
        get() = sp.getBoolean(KEY_AUDIO_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_AUDIO_ENABLED, v).apply()

    fun reset() {
        sp.edit()
            .putInt(KEY_STALL_WINDOW, DEF_STALL_WINDOW)
            .putInt(KEY_STALL_COUNT, DEF_STALL_COUNT)
            .putInt(KEY_STALL_COOLDOWN, DEF_STALL_COOLDOWN)
            .putInt(KEY_BUFFER_MIN, DEF_BUFFER_MIN)
            .putInt(KEY_BUFFER_MAX, DEF_BUFFER_MAX)
            .putInt(KEY_PLAY_START, DEF_PLAY_START)
            .putInt(KEY_REBUFFER, DEF_REBUFFER)
            .putInt(KEY_MAX_RETRY, DEF_MAX_RETRY)
            .putBoolean(KEY_SHOW_STATS, false)
            .putBoolean(KEY_INSECURE_TLS, true)
            .putBoolean(KEY_AUTO_SWITCH, false)
            .putString(KEY_ZOOM_MODE, DEF_ZOOM_MODE)
            .putInt(KEY_ZOOM_SCALE, DEF_ZOOM_SCALE)
            .putString(KEY_DECODER_PRIORITY, DEF_DECODER_PRIORITY)
            .putString(KEY_RENDER_MODE, DEF_RENDER_MODE)
            .putBoolean(KEY_AUDIO_ENABLED, true)
            .apply()
    }
}

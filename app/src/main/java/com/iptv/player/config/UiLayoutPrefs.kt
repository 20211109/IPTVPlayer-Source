package com.iptv.player.config

import android.content.Context

/**
 * 界面偏好：手机 / 电视布局手动切换。
 *
 * 默认 AUTO —— 由设备 uiMode 决定（电视盒子用电视布局，手机用手机布局）。
 * 用户在「我的」里手动选过之后写这里，重启仍保持。
 *
 * 注意：这里只决定「用哪套导航与网格」，不改变任何播放行为。
 */
class UiLayoutPrefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("iptv_ui_layout", Context.MODE_PRIVATE)

    companion object {
        const val MODE_AUTO = "auto"
        const val MODE_PHONE = "phone"
        const val MODE_TV = "tv"

        private const val KEY_MODE = "mode"
    }

    var mode: String
        get() = sp.getString(KEY_MODE, MODE_AUTO) ?: MODE_AUTO
        set(value) = sp.edit().putString(KEY_MODE, value).apply()

    /** 当前实际生效的是不是电视布局 */
    fun isTvLayout(deviceIsTv: Boolean): Boolean = when (mode) {
        MODE_PHONE -> false
        MODE_TV -> true
        else -> deviceIsTv
    }

    val modeLabel: String
        get() = when (mode) {
            MODE_PHONE -> "手机布局"
            MODE_TV -> "电视布局"
            else -> "自动（跟随设备）"
        }
}

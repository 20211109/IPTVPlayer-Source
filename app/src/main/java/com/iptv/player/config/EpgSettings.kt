package com.iptv.player.config

import android.content.Context

/**
 * 节目单接口设置。用户可在「直播源」页自定义 EPG 地址，
 * 支持 {key} 占位符；留空即恢复国内默认接口。
 */
class EpgSettings(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("epg_settings", Context.MODE_PRIVATE)

    /** 接口模板，永远返回非空值 */
    var apiTemplate: String
        get() {
            val v = sp.getString(KEY_API, DEFAULT_API)?.trim()
            return if (v.isNullOrBlank()) DEFAULT_API else v
        }
        set(value) {
            val v = value.trim()
            if (v.isBlank() || v == DEFAULT_API) {
                sp.edit().remove(KEY_API).apply()
            } else {
                sp.edit().putString(KEY_API, v).apply()
            }
        }

    fun isCustom(): Boolean = sp.getString(KEY_API, null)?.isNotBlank() == true

    companion object {
        const val KEY_API = "api_template"
        const val DEFAULT_API = "https://epg.112114.xyz/?ch={key}"
    }
}

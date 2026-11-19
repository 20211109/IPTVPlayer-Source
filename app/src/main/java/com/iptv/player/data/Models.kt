package com.iptv.player.data

/**
 * 一条直播源线路。
 * 同一个 Channel 下可有多条 Source，互为备用，按播放列表顺序依次尝试。
 */
data class Source(
    val url: String,
    var latencyMs: Long = -1,      // 测得的延时（毫秒），-1 表示未测/失败
    var speedKbps: Long = -1,      // 下载速率，用于评分补充
    var score: Int = Int.MAX_VALUE, // 历史遗留字段（保留用于读取旧配置，不再参与排序）
    var available: Boolean = true
) {
    fun scoreText(): String = if (latencyMs >= 0) "${latencyMs}ms" else "未测"
}

/**
 * 用户添加的一份直播源（一个 M3U 地址或一段 M3U 文本）。
 * 多份直播源可同时启用，同名频道会自动合并为同一个频道的多条线路。
 */
data class PlaylistSource(
    val id: String,
    var name: String,
    val url: String = "",
    val raw: String = "",
    var enabled: Boolean = true
) {
    val isUrl: Boolean get() = url.startsWith("http://", true) || url.startsWith("https://", true)
}

/**
 * 频道。同名（归一化后）的多条 M3U 条目会合并为一个频道的多条线路。
 */
data class Channel(
    val name: String,
    val group: String = "默认",
    var logo: String = "",
    var tvgId: String = "",
    val sources: MutableList<Source> = mutableListOf()
) {
    /** 当前选中的线路下标 */
    var selectedIndex: Int = 0

    /** 用户手动设置的台标链接（优先级高于 tvg-logo 与自动匹配） */
    var logoOverride: String = ""

    /** 首选线路：永远是列表里的第一条 */
    val bestSource: Source?
        get() = sources.firstOrNull()

    fun normalizedName(): String = name.trim().lowercase()
}

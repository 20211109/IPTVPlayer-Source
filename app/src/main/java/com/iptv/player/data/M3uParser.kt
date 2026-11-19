package com.iptv.player.data

import java.net.URI
import java.net.URL

/**
 * M3U / M3U8 播放列表解析器。
 * 支持标准 EXTINF 属性：tvg-id、tvg-name、tvg-logo、group-title，
 * 以及 #EXTGRP 分组。同名频道的多条线路合并为一个 [Channel] 的多条 [Source]。
 */
object M3uParser {

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")
    private val extInfRegex = Regex("""^#EXTINF:(-?\d+)\s*(.*)$""", RegexOption.IGNORE_CASE)

    /**
     * @param content 播放列表文本
     * @param baseUrl 列表来源地址，用于解析相对路径
     */
    fun parse(content: String, baseUrl: String = ""): List<Channel> {
        val channels = LinkedHashMap<String, Channel>()
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#EXTM3U") }
            .toList()

        var pendingName: String? = null
        var pendingGroup: String = "默认"
        var pendingLogo: String = ""
        var pendingId: String = ""
        var lastGroup: String = "默认"

        for (line in lines) {
            when {
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val m = extInfRegex.find(line)
                    val attrs = m?.groupValues?.get(2) ?: ""
                    val attributeMap = attrRegex.findAll(attrs)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }

                    // 频道名取逗号后显示名；无则回退 tvg-name
                    val displayName = line.substringAfterLast(",", "").trim()
                    val tvgName = attributeMap["tvg-name"] ?: ""
                    pendingName = displayName.ifBlank { tvgName }.ifBlank { null }
                    pendingId = attributeMap["tvg-id"] ?: ""
                    pendingLogo = attributeMap["tvg-logo"] ?: ""
                    pendingGroup = attributeMap["group-title"]?.ifBlank { lastGroup } ?: lastGroup
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    lastGroup = line.substringAfter(":").trim().ifBlank { "默认" }
                    pendingGroup = lastGroup
                }

                line.startsWith("#") -> {
                    // 其它标签（#KODIPROP、#EXTVLCOPT 等）忽略
                    continue
                }

                else -> {
                    val name = pendingName ?: line.substringAfterLast("/", "未命名")
                    val fullUrl = resolveUrl(line, baseUrl)
                    val key = name.trim().lowercase()
                    val channel = channels.getOrPut(key) {
                        Channel(
                            name = name.trim(),
                            group = pendingGroup.ifBlank { "默认" },
                            logo = pendingLogo,
                            tvgId = pendingId
                        )
                    }
                    // 去重：同名频道下相同 URL 不重复加入
                    if (channel.sources.none { it.url == fullUrl }) {
                        channel.sources.add(Source(url = fullUrl))
                    }
                    pendingName = null
                    pendingGroup = lastGroup
                    pendingLogo = ""
                    pendingId = ""
                }
            }
        }

        return channels.values.toList()
    }

    /**
     * 合并多份直播源的解析结果：同名频道只保留一个，线路按源的顺序追加。
     * 这样用户添加 3 份源后，同一频道会显示为一个卡片、3 条可切换线路。
     */
    fun merge(lists: Collection<List<Channel>>): List<Channel> {
        val merged = LinkedHashMap<String, Channel>()
        lists.flatten().forEach { ch ->
            val key = ch.normalizedName()
            val existing = merged[key]
            if (existing == null) {
                merged[key] = ch
            } else {
                ch.sources.forEach { s ->
                    if (existing.sources.none { it.url == s.url }) existing.sources.add(s)
                }
                if (existing.logo.isBlank()) existing.logo = ch.logo
                if (existing.tvgId.isBlank()) existing.tvgId = ch.tvgId
                if (existing.logoOverride.isBlank()) existing.logoOverride = ch.logoOverride
            }
        }
        return merged.values.toList()
    }

    /** 解析相对地址：相对路径基于 baseUrl 所在目录 */
    private fun resolveUrl(url: String, baseUrl: String): String {
        if (baseUrl.isBlank()) return url
        return try {
            val base = URL(baseUrl)
            val uri = URI(url)
            if (uri.isAbsolute) url else URL(base, url).toString()
        } catch (e: Exception) {
            url
        }
    }
}

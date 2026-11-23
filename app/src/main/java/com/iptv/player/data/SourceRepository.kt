package com.iptv.player.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地持久化：播放列表文件文本、频道与线路评分、上次播放频道。
 * 纯 SharedPreferences + org.json，无数据库依赖，编译轻量。
 */
class SourceRepository(context: Context) {

    private val sp = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PLAYLIST = "playlist_raw"
        private const val KEY_PLAYLIST_URL = "playlist_url"
        private const val KEY_CHANNELS_JSON = "channels_json"
        private const val KEY_LAST_CHANNEL = "last_channel"
        private const val KEY_PLAYLIST_SOURCES = "playlist_sources"
        private const val KEY_SOURCES_MIGRATED = "playlist_sources_migrated"
    }

    /** 列出全部直播源；首次使用时把旧版单条直播源平滑迁移过来 */
    fun getPlaylistSources(): List<PlaylistSource> {
        val raw = sp.getString(KEY_PLAYLIST_SOURCES, "") ?: ""
        if (raw.isNotBlank()) {
            val parsed = try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    PlaylistSource(
                        id = o.optString("id", i.toString()),
                        name = o.optString("name", "直播源 ${i + 1}"),
                        url = o.optString("url"),
                        raw = o.optString("raw"),
                        enabled = o.optBoolean("enabled", true)
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
            if (parsed.isNotEmpty()) return parsed
        }

        // 旧版本只有一份 playlist_raw，迁移成一条同名直播源，不丢失用户已有配置
        // 已经迁移过（含用户主动删空）就不再复活旧数据
        if (sp.getBoolean(KEY_SOURCES_MIGRATED, false)) return emptyList()
        val legacyRaw = getPlaylistRaw()
        if (legacyRaw.isBlank()) return emptyList()
        val legacy = PlaylistSource(
            id = "legacy",
            name = "我的直播源",
            url = getPlaylistUrl(),
            raw = legacyRaw,
            enabled = true
        )
        savePlaylistSources(listOf(legacy))
        return listOf(legacy)
    }

    fun savePlaylistSources(sources: List<PlaylistSource>) {
        val arr = JSONArray()
        sources.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("url", s.url)
                put("raw", s.raw)
                put("enabled", s.enabled)
            })
        }
        sp.edit()
            .putString(KEY_PLAYLIST_SOURCES, arr.toString())
            .putBoolean(KEY_SOURCES_MIGRATED, true)
            .apply()
    }

    /** 新增一份直播源。名称为空时自动生成，避免列表里出现空白项 */
    fun addPlaylistSource(name: String, url: String, raw: String): PlaylistSource {
        val sources = getPlaylistSources().toMutableList()
        val autoName = name.trim().ifBlank {
            val fromUrl = try {
                java.net.URL(url.ifBlank { "http://local" }).host
            } catch (e: Exception) {
                ""
            }
            fromUrl.ifBlank { "直播源 ${sources.size + 1}" }
        }
        val item = PlaylistSource(
            id = System.currentTimeMillis().toString() + "-" + (Math.random() * 1000).toInt(),
            name = autoName,
            url = url.trim(),
            raw = raw,
            enabled = true
        )
        sources.add(item)
        savePlaylistSources(sources)
        // 同步兼容旧字段：任何读取旧键的逻辑也能看到最新一份源
        savePlaylist(raw, url.trim())
        return item
    }

    /**
     * 更新一份直播源。所有字段均为可选，未传的保持原值。
     * url/raw 用于「刷新 / 编辑订阅源」：源站内容变化后重新下载并落库，
     * 下次 rebuildChannelsFromSources() 就能读到最新频道列表。
     */
    fun updatePlaylistSource(
        id: String,
        name: String? = null,
        enabled: Boolean? = null,
        url: String? = null,
        raw: String? = null
    ) {
        val sources = getPlaylistSources().map { s ->
            if (s.id != id) s else s.copy(
                name = name?.trim()?.ifBlank { s.name } ?: s.name,
                enabled = enabled ?: s.enabled,
                url = url ?: s.url,
                raw = raw ?: s.raw
            )
        }
        savePlaylistSources(sources)
        // 兼容旧字段：让旧键始终指向最新一份源，避免旧版读取路径拿到过期内容
        sources.lastOrNull()?.let { savePlaylist(it.raw, it.url) }
    }

    fun deletePlaylistSource(id: String) {
        savePlaylistSources(getPlaylistSources().filter { it.id != id })
    }


    fun savePlaylist(raw: String, sourceUrl: String) {
        sp.edit()
            .putString(KEY_PLAYLIST, raw)
            .putString(KEY_PLAYLIST_URL, sourceUrl)
            .apply()
    }

    fun getPlaylistRaw(): String = sp.getString(KEY_PLAYLIST, "") ?: ""
    fun getPlaylistUrl(): String = sp.getString(KEY_PLAYLIST_URL, "") ?: ""

    fun getLastChannelName(): String = sp.getString(KEY_LAST_CHANNEL, "") ?: ""
    fun setLastChannelName(name: String) {
        sp.edit().putString(KEY_LAST_CHANNEL, name).apply()
    }

    /** 保存频道及其线路评分 */
    fun saveChannels(channels: List<Channel>) {
        val arr = JSONArray()
        for (ch in channels) {
            val obj = JSONObject().apply {
                put("name", ch.name)
                put("group", ch.group)
                put("logo", ch.logo)
                put("logoOverride", ch.logoOverride)
                put("tvgId", ch.tvgId)
                val srcArr = JSONArray()
                for (s in ch.sources) {
                    srcArr.put(JSONObject().apply {
                        put("url", s.url)
                        put("latencyMs", s.latencyMs)
                        put("speedKbps", s.speedKbps)
                        put("score", s.score)
                        put("available", s.available)
                    })
                }
                put("sources", srcArr)
            }
            arr.put(obj)
        }
        sp.edit().putString(KEY_CHANNELS_JSON, arr.toString()).apply()
    }

    fun loadChannels(): List<Channel> {
        val raw = sp.getString(KEY_CHANNELS_JSON, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val ch = Channel(
                    name = obj.optString("name"),
                    group = obj.optString("group", "默认"),
                    logo = obj.optString("logo"),
                    tvgId = obj.optString("tvgId")
                )
                ch.logoOverride = obj.optString("logoOverride")
                val srcArr = obj.optJSONArray("sources") ?: JSONArray()
                (0 until srcArr.length()).forEach { j ->
                    val s = srcArr.getJSONObject(j)
                    ch.sources.add(Source(
                        url = s.optString("url"),
                        latencyMs = s.optLong("latencyMs", -1),
                        speedKbps = s.optLong("speedKbps", -1),
                        score = s.optInt("score", Int.MAX_VALUE),
                        available = s.optBoolean("available", true)
                    ))
                }
                ch
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearAll() {
        sp.edit().clear().apply()
    }
}

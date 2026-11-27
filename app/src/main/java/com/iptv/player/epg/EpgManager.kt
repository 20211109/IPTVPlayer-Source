package com.iptv.player.epg

import android.content.Context
import android.util.Xml
import com.iptv.player.config.EpgSettings
import com.iptv.player.config.HttpCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 轻量节目单（EPG）。
 *
 * 默认数据源：epg.112114.xyz（国内可直连、单频道返回几 KB JSON），
 * 也支持在「直播源」页填入自定义接口，用 {key} 表示频道名占位符。
 * 相比动辄几十 MB 的 XMLTV 全量文件，这种方式对老电视盒子更友好：
 *  - 只请求当前频道，流量小、解析快、内存占用低；
 *  - 失败/超时一律静默返回 null，播放页自动隐藏节目单，绝不影响观看。
 */
class EpgManager(context: Context) {

    private val settings = EpgSettings(context)
    private val prefs = context.applicationContext
        .getSharedPreferences("epg_cache_v2", Context.MODE_PRIVATE)

    private val client = HttpCompat.clientBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Program(val title: String, val start: Long, val end: Long)
    data class NowNext(val now: Program?, val next: Program?)

    companion object {
        private const val CACHE_MS = 30 * 60 * 1000L      // 节目单缓存 30 分钟
        private const val NEGATIVE_CACHE_MS = 5 * 60 * 1000L // 失败缓存 5 分钟，避免频繁重试
    }

    /** 内存缓存：key -> (写入时间, 结果) */
    private val mem = HashMap<String, Pair<Long, NowNext?>>()

    /** XMLTV 全量节目表缓存：一次下载，多频道复用 */
    private var xmltvPrograms: Map<String, List<Program>> = emptyMap()
    private var xmltvFetchedAt = 0L

    /** 当前使用的接口模板（设置保存后立即生效，无需重启应用） */
    fun currentApi(): String = settings.apiTemplate

    /**
     * 只从已有缓存读取「正在播放」标题，绝不发起网络请求。
     * 供频道列表的「当前节目」副标题与搜索使用 —— 列表一次可能有上百个频道，
     * 任何逐频道的网络请求都会拖垮滑动，所以这里只认缓存，没缓存就返回 null。
     */
    fun peekNowTitle(channelName: String, tvgId: String): String? {
        val nowMs = System.currentTimeMillis()
        for (key in candidateKeys(channelName, tvgId)) {
            val hit = mem[key] ?: readCache(key)
            if (hit == null) continue
            val (ts, value) = hit
            if (nowMs - ts !in 0..CACHE_MS) continue
            val title = value?.now?.title
            if (!title.isNullOrBlank()) return title
        }
        // XMLTV 是整表缓存，没有按频道写 mem，这里直接查整表
        if (isXmltvApi() && xmltvPrograms.isNotEmpty() && nowMs - xmltvFetchedAt in 0..CACHE_MS) {
            val candidates = candidateKeys(channelName, tvgId).map { it.lowercase() }
            val matched = candidates.firstNotNullOfOrNull { c ->
                xmltvPrograms.keys.firstOrNull { it.equals(c, true) }
            } ?: candidates.firstNotNullOfOrNull { c ->
                xmltvPrograms.keys.firstOrNull { id ->
                    id.startsWith(c) && (id.length == c.length || !id[c.length].isDigit())
                }
            } ?: return null
            return xmltvPrograms[matched]?.lastOrNull { it.start <= nowMs && it.end > nowMs }?.title
        }
        return null
    }

    /**
     * 只从缓存读取「正在播放」整条节目（标题 + 开始时间），不发起网络请求。
     * 频道卡片要显示「新闻30分 · 12:00」这种格式，只用标题缺少设计图里的时间。
     */
    fun peekNowProgram(channelName: String, tvgId: String): Program? {
        val nowMs = System.currentTimeMillis()
        for (key in candidateKeys(channelName, tvgId)) {
            val hit = mem[key] ?: readCache(key) ?: continue
            val (ts, value) = hit
            if (nowMs - ts !in 0..CACHE_MS) continue
            val now = value?.now
            if (now != null && now.title.isNotBlank()) return now
        }
        if (isXmltvApi() && xmltvPrograms.isNotEmpty() && nowMs - xmltvFetchedAt in 0..CACHE_MS) {
            val candidates = candidateKeys(channelName, tvgId).map { it.lowercase() }
            val matched = candidates.firstNotNullOfOrNull { c ->
                xmltvPrograms.keys.firstOrNull { it.equals(c, true) }
            } ?: candidates.firstNotNullOfOrNull { c ->
                xmltvPrograms.keys.firstOrNull { id ->
                    id.startsWith(c) && (id.length == c.length || !id[c.length].isDigit())
                }
            } ?: return null
            return xmltvPrograms[matched]?.lastOrNull { it.start <= nowMs && it.end > nowMs }
        }
        return null
    }

    /** 接口变更或用户手动刷新时清空缓存，避免看到旧接口的数据 */
    fun clearCache() {
        mem.clear()
        xmltvPrograms = emptyMap()
        xmltvFetchedAt = 0L
        try {
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            // 缓存清理失败不影响功能
        }
    }

    /** 获取指定频道此刻的节目；失败返回 null */
    suspend fun getNowNext(channelName: String, tvgId: String): NowNext? = withContext(Dispatchers.IO) {
        try {
            // XMLTV 全量节目表（如央视频 playback.xml）：一次下载，多频道复用
            if (isXmltvApi()) return@withContext getFromXmltv(channelName, tvgId)

            val keys = candidateKeys(channelName, tvgId)
            for (key in keys) {
                val cached = readCache(key)
                if (cached != null) {
                    val (ts, value) = cached
                    val ttl = if (value == null) NEGATIVE_CACHE_MS else CACHE_MS
                    if (System.currentTimeMillis() - ts in 0..ttl) {
                        if (value != null) return@withContext value
                        continue
                    }
                }
                val payload = fetch(key)
                if (payload == null) {
                    writeCache(key, null)
                    continue
                }
                val nn = parse(payload)
                if (nn != null && (nn.now != null || nn.next != null)) {
                    writeCache(key, nn)
                    return@withContext nn
                }
                writeCache(key, null)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** 频道候选名：tvg-id 优先，其次是从频道名归一化出的常见写法 */
    private fun candidateKeys(name: String, tvgId: String): List<String> {
        val list = mutableListOf<String>()
        val id = tvgId.trim()
        if (id.isNotBlank()) list.add(id.replace(" ", ""))

        val raw = name.trim()
        val cctv = Regex("""(?i)^cctv[-\s]?(\d{1,2}\+?)""").find(raw)
        if (cctv != null) {
            list.add("CCTV" + cctv.groupValues[1])
        } else {
            var cleaned = raw.replace(Regex("""[（(].*?[)）]"""), "")
                .replace(Regex("""(?i)\s*(高清|超清|4K|HD|标清|SD|频道|直播)\s*"""), "")
                .replace(" ", "")
                .trim()
            if (cleaned.isNotBlank()) list.add(cleaned)
            // 湖南卫视 → 湖南卫视（保持不变）；「湖南卫视高清」已在上面清掉后缀
            if (cleaned.endsWith("卫视")) list.add(cleaned)
        }
        if (raw.isNotBlank()) list.add(raw.replace(" ", ""))
        return list.distinct().take(4)
    }

    // ---------------- XMLTV 全量节目表 ----------------

    /** 接口地址是 .xml（或用户填了完整 XMLTV 地址）时按 XMLTV 解析 */
    private fun isXmltvApi(): Boolean = currentApi().contains(".xml", ignoreCase = true)

    /** 从已缓存的 XMLTV 数据里取当前频道的正在播放/接下来 */
    private fun getFromXmltv(name: String, tvgId: String): NowNext? {
        val age = System.currentTimeMillis() - xmltvFetchedAt
        if (xmltvPrograms.isEmpty() || age > CACHE_MS) {
            // 失败后 5 分钟内不再重复下载 2~3MB 的大文件
            if (xmltvFetchedAt > 0L && age < NEGATIVE_CACHE_MS) return null
            val raw = fetchRaw(currentApi()) ?: run {
                xmltvFetchedAt = System.currentTimeMillis()
                return null
            }
            xmltvPrograms = parseXmltv(raw)
            xmltvFetchedAt = System.currentTimeMillis()
        }
        if (xmltvPrograms.isEmpty()) return null

        val ids = xmltvPrograms.keys
        val candidates = candidateKeys(name, tvgId).map { it.lowercase() }
        val matched = candidates.firstNotNullOfOrNull { c -> ids.firstOrNull { it.equals(c, true) } }
            ?: candidates.firstNotNullOfOrNull { c ->
                ids.firstOrNull { id ->
                    id.startsWith(c) && (id.length == c.length || !id[c.length].isDigit())
                }
            } ?: return null

        val list = xmltvPrograms[matched] ?: return null
        val nowMs = System.currentTimeMillis()
        val now = list.lastOrNull { it.start <= nowMs && it.end > nowMs }
        val next = list.firstOrNull { it.start > nowMs }
        return if (now == null && next == null) null else NowNext(now, next)
    }

    /** 直接取接口原文（XMLTV 不需要按频道拼参数） */
    private fun fetchRaw(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 IPTVPlayer")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = if (resp.isSuccessful) resp.body?.string() else null
            if (!text.isNullOrBlank() && (text.contains("<programme", true) || text.contains("epg_data"))) {
                text
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }

    /** 解析 XMLTV：只保留当前时间之后的节目，避免 2~3MB 全量驻留内存 */
    private fun parseXmltv(raw: String): Map<String, List<Program>> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(raw))
        val nowMs = System.currentTimeMillis()
        val result = HashMap<String, MutableList<Program>>()
        var event = parser.eventType
        var channel: String? = null
        var startText: String? = null
        var stopText: String? = null
        var title: String? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "channel" -> channel = parser.getAttributeValue(null, "id")?.trim()
                    "programme" -> {
                        channel = parser.getAttributeValue(null, "channel")?.trim()
                        startText = parser.getAttributeValue(null, "start")
                        stopText = parser.getAttributeValue(null, "stop")
                        title = null
                    }
                    "title" -> if (channel != null && title == null) title = parser.nextText()
                }
            } else if (event == XmlPullParser.END_TAG && parser.name.equals("programme", true)) {
                val ch = channel
                val s = parseXmlTime(startText)
                val e = parseXmlTime(stopText)
                val t = title?.trim()
                if (ch != null && s != null && e != null && e > nowMs && !t.isNullOrBlank() && t.length < 200) {
                    result.getOrPut(ch) { ArrayList() }.add(Program(t, s, e))
                }
                channel = null
                startText = null
                stopText = null
                title = null
            }
            event = parser.next()
        }
        result.forEach { it.value.sortBy { p -> p.start } }
        return result
    }

    /** XMLTV 时间：20261118000500 +0800 */
    private fun parseXmlTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
            fmt.parse(value.trim())?.time
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 根据模板拼出实际请求地址。
     * - 支持 {key}/{ch}/{name}/{id} 占位符；
     * - 没有占位符时，地址以 =/&/? 结尾就直接补频道名，否则自动加 ?ch=；
     * - 多个接口可用换行或分号分隔，逐个尝试；
     * - 默认接口额外回退一次 http，兼容 TLS 握手异常的老盒子。
     */
    private fun buildUrls(template: String, encoded: String): List<String> {
        // XMLTV 是全量节目表，不需要也不能按频道拼参数
        if (template.contains(".xml", ignoreCase = true)) return listOf(template.trim())
        val parts = template.split('\n', ';').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()
        val result = ArrayList<String>()
        for (part in parts) {
            val url = when {
                part.contains("{key}") -> part.replace("{key}", encoded)
                part.contains("{ch}") -> part.replace("{ch}", encoded)
                part.contains("{name}") -> part.replace("{name}", encoded)
                part.contains("{id}") -> part.replace("{id}", encoded)
                part.endsWith("=") || part.endsWith("&") || part.endsWith("?") -> part + encoded
                part.contains("?") -> part + "&ch=" + encoded
                else -> part + "?ch=" + encoded
            }
            result.add(url)
            if (part == EpgSettings.DEFAULT_API) {
                result.add(url.replaceFirst("https://", "http://"))
            }
        }
        return result.distinct()
    }

    /** 请求单频道节目单；失败返回 null，由调用方静默隐藏节目单 */
    private fun fetch(key: String): String? {
        val encoded = try {
            URLEncoder.encode(key, "UTF-8")
        } catch (e: Exception) {
            key
        }
        for (url in buildUrls(currentApi(), encoded)) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 IPTVPlayer")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val text = resp.body?.string()
                        if (!text.isNullOrBlank() && text.contains("epg_data")) return text
                    }
                }
            } catch (e: Exception) {
                // 换下一个接口
            }
        }
        return null
    }

    /** 解析 112114 的 JSON，并算出「正在播放 / 接下来」 */
    private fun parse(json: String): NowNext? {
        val obj = JSONObject(json)
        val date = obj.optString("date", "")
        val arr = obj.optJSONArray("epg_data") ?: return null
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        val programs = ArrayList<Program>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val title = item.optString("title").trim()
            val start = parseClock(fmt, date, item.optString("start")) ?: continue
            var end = parseClock(fmt, date, item.optString("end")) ?: continue
            if (end <= start) end += 24 * 60 * 60 * 1000L // 跨零点
            if (title.isBlank()) continue
            programs.add(Program(title, start, end))
        }
        if (programs.isEmpty()) return null
        programs.sortBy { it.start }
        val nowMs = System.currentTimeMillis()
        val now = programs.lastOrNull { it.start <= nowMs && it.end > nowMs }
        val next = programs.firstOrNull { it.start > nowMs }
        return NowNext(now, next)
    }

    private fun parseClock(fmt: SimpleDateFormat, date: String, clock: String): Long? {
        if (clock.isBlank() || clock.length < 4) return null
        return try {
            fmt.parse("$date ${clock.trim()}")?.time
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- 缓存 ----------------

    private fun readCache(key: String): Pair<Long, NowNext?>? {
        mem[key]?.let { return it }
        val ts = prefs.getLong("t_$key", 0L)
        if (ts <= 0L) return null
        val raw = prefs.getString("d_$key", null) ?: return Pair(ts, null)
        val nn = try {
            val o = JSONObject(raw)
            fun readProgram(name: String): Program? {
                val p = o.optJSONObject(name) ?: return null
                return Program(p.optString("t"), p.optLong("s"), p.optLong("e"))
            }
            NowNext(readProgram("now"), readProgram("next"))
        } catch (e: Exception) {
            null
        }
        return Pair(ts, nn).also { mem[key] = it }
    }

    private fun writeCache(key: String, value: NowNext?) {
        mem[key] = System.currentTimeMillis() to value
        try {
            // 控制缓存规模，避免长期使用后 SharedPreferences 膨胀
            if (prefs.all.size > 80) prefs.edit().clear().apply()
            val edit = prefs.edit().putLong("t_$key", System.currentTimeMillis())
            if (value == null) {
                edit.remove("d_$key")
            } else {
                val o = JSONObject()
                value.now?.let {
                    o.put("now", JSONObject().put("t", it.title).put("s", it.start).put("e", it.end))
                }
                value.next?.let {
                    o.put("next", JSONObject().put("t", it.title).put("s", it.start).put("e", it.end))
                }
                edit.putString("d_$key", o.toString())
            }
            edit.apply()
        } catch (e: Exception) {
            // 缓存失败不影响功能
        }
    }
}

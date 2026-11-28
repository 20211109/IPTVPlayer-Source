package com.iptv.player.epg

import android.content.Context
import android.util.Xml
import com.iptv.player.config.EpgSettings
import com.iptv.player.config.HttpCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 轻量节目单（EPG）—— 多格式兼容版。
 *
 * 设计原则：
 *  1. 按「返回内容」自动嗅探格式，不看接口 URL 后缀。换接口、带参数都能用。
 *  2. 支持两类数据源：
 *     - 全量型（XMLTV playback.xml、按频道名分组的 JSON、CSV 全量表）：一次下载，
 *       解析成「频道名 -> 节目列表」缓存，多频道复用，不重复下载大文件；
 *     - 单频道型（112114 的 ?ch={key} 等带占位符接口）：按频道名拼 URL 逐个请求，
 *       结果按频道缓存。
 *  3. 所有格式统一解析成 Program(title, start, end)，上层只认这一种结构。
 *  4. 失败/超时一律静默返回 null，绝不影响观看。
 *
 * 支持的格式：
 *  - XMLTV：<tv><channel id><programme channel start stop><title>（含 display-name 变体）
 *  - 112114 JSON：{"date":"...","epg_data":[{title,start,end}]}
 *  - 分组 JSON：{"CCTV1":[{...}],"CCTV2":{epg_data:[...]}}
 *  - 列表 JSON：[{channel:"CCTV1",programs:[{name,startTime,endTime}]}]
 *  - CSV：频道,开始,结束,标题（逗号或制表符分隔，字段顺序自适应）
 */
class EpgManager(context: Context) {

    private val settings = EpgSettings(context)
    private val prefs = context.applicationContext
        .getSharedPreferences("epg_cache_v2", Context.MODE_PRIVATE)

    private val client = HttpCompat.clientBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Program(val title: String, val start: Long, val end: Long)
    data class NowNext(val now: Program?, val next: Program?)

    companion object {
        private const val CACHE_MS = 30 * 60 * 1000L
        private const val NEGATIVE_CACHE_MS = 5 * 60 * 1000L
        private const val DEFAULT_PROGRAM_MS = 30 * 60 * 1000L
        private val TZ_SH = TimeZone.getTimeZone("Asia/Shanghai")
    }

    /** 单频道结果缓存：key -> (写入时间, 结果) */
    private val mem = HashMap<String, Pair<Long, NowNext?>>()

    /** 全量节目表缓存：频道名 -> 节目列表 */
    private var allPrograms: Map<String, List<Program>> = emptyMap()
    private var allFetchedAt = 0L
    private val allLoadLock = Any()

    /** 当前使用的接口模板（设置保存后立即生效） */
    fun currentApi(): String = settings.apiTemplate

    /** 单频道接口：URL 里带占位符，或以 =/&/? 结尾等着拼频道名 */
    private fun isPerChannelApi(): Boolean {
        val t = currentApi().trim()
        return t.contains("{key}") || t.contains("{ch}") || t.contains("{name}") ||
            t.contains("{id}") || t.endsWith("=") || t.endsWith("&") || t.endsWith("?")
    }

    // ---------------- 只读缓存（列表 / 搜索用，绝不发网络请求） ----------------

    fun peekNowTitle(channelName: String, tvgId: String): String? =
        peekNowProgram(channelName, tvgId)?.title

    fun peekNowProgram(channelName: String, tvgId: String): Program? {
        val nowMs = System.currentTimeMillis()
        // 1) 单频道缓存
        for (key in candidateKeys(channelName, tvgId)) {
            val hit = mem[key] ?: readCache(key) ?: continue
            val (ts, value) = hit
            if (nowMs - ts !in 0..CACHE_MS) continue
            val now = value?.now
            if (now != null && now.title.isNotBlank()) return now
        }
        // 2) 全量缓存
        if (!isPerChannelApi() && allPrograms.isNotEmpty() && nowMs - allFetchedAt in 0..CACHE_MS) {
            val id = matchChannelId(allPrograms.keys, channelName, tvgId) ?: return null
            return currentProgram(allPrograms[id])
        }
        return null
    }

    /** 接口变更或用户手动刷新时清空缓存 */
    fun clearCache() {
        mem.clear()
        allPrograms = emptyMap()
        allFetchedAt = 0L
        try {
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            // 缓存清理失败不影响功能
        }
    }

    // ---------------- 主入口 ----------------

    suspend fun getNowNext(channelName: String, tvgId: String): NowNext? = withContext(Dispatchers.IO) {
        try {
            if (isPerChannelApi()) {
                getFromPerChannel(channelName, tvgId)
            } else {
                getFromFullSchedule(channelName, tvgId)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 单频道接口：按候选频道名逐个请求，命中即返回 */
    private fun getFromPerChannel(name: String, tvgId: String): NowNext? {
        for (key in candidateKeys(name, tvgId)) {
            val cached = readCache(key)
            if (cached != null) {
                val (ts, value) = cached
                val ttl = if (value == null) NEGATIVE_CACHE_MS else CACHE_MS
                if (System.currentTimeMillis() - ts in 0..ttl) {
                    if (value != null) return value
                    continue
                }
            }
            val payload = fetch(key)
            if (payload == null) {
                writeCache(key, null)
                continue
            }
            val map = parseAny(payload, key)
            val list = pickPrograms(map, key)
            val nn = toNowNext(list)
            if (nn != null) {
                writeCache(key, nn)
                return nn
            }
            writeCache(key, null)
        }
        return null
    }

    /** 全量接口：一次下载解析，多频道复用；加锁避免并发重复下载 */
    private fun getFromFullSchedule(name: String, tvgId: String): NowNext? {
        synchronized(allLoadLock) {
            val age = System.currentTimeMillis() - allFetchedAt
            if (allPrograms.isEmpty() || age > CACHE_MS) {
                if (allFetchedAt > 0L && age < NEGATIVE_CACHE_MS) return null
                val raw = fetchRaw(currentApi()) ?: run {
                    allFetchedAt = System.currentTimeMillis()
                    return null
                }
                allPrograms = parseAny(raw, "")
                allFetchedAt = System.currentTimeMillis()
            }
        }
        if (allPrograms.isEmpty()) return null
        val id = matchChannelId(allPrograms.keys, name, tvgId) ?: return null
        return toNowNext(allPrograms[id])
    }

    // ---------------- 格式嗅探 + 统一解析 ----------------

    private enum class Format { XMLTV, JSON, CSV, UNKNOWN }

    private fun detectFormat(raw: String): Format {
        val t = raw.trimStart().trimStart('\uFEFF')
        return when {
            t.startsWith("<") || t.contains("<programme", true) -> Format.XMLTV
            t.startsWith("{") || t.startsWith("[") -> Format.JSON
            looksLikeCsv(t) -> Format.CSV
            else -> Format.UNKNOWN
        }
    }

    /** 所有格式统一解析成「频道名 -> 节目列表」 */
    private fun parseAny(raw: String, fallbackKey: String): Map<String, List<Program>> = try {
        when (detectFormat(raw)) {
            Format.XMLTV -> parseXmltv(raw)
            Format.JSON -> parseJson(raw, fallbackKey)
            Format.CSV -> parseCsv(raw, fallbackKey)
            Format.UNKNOWN -> emptyMap()
        }
    } catch (e: Exception) {
        emptyMap()
    }

    // ---- JSON ----

    private fun parseJson(raw: String, fallbackKey: String): Map<String, List<Program>> {
        val out = HashMap<String, MutableList<Program>>()
        val t = raw.trimStart().trimStart('\uFEFF')
        if (t.startsWith("[")) {
            val arr = try { JSONArray(t) } catch (e: Exception) { return out }
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val chName = firstString(item, "channel", "channelName", "name", "id", "tvg_name")
                val progArr = firstArray(item, "programs", "epg", "list", "programme", "data", "items")
                if (chName != null && progArr != null) {
                    val list = out.getOrPut(chName) { ArrayList() }
                    parseProgramArray(progArr, firstString(item, "date") ?: "", list)
                } else {
                    val p = programFromJson(item, "")
                    if (p != null) out.getOrPut(fallbackKey) { ArrayList() }.add(p)
                }
            }
            return out
        }

        val obj = try { JSONObject(t) } catch (e: Exception) { return out }
        val dateHint = firstString(obj, "date") ?: ""

        // 单频道 epg_data（112114 等）：优先识别，避免被当成分组
        val epgArr = obj.optJSONArray("epg_data") ?: obj.optJSONArray("programs")
        if (epgArr != null) {
            val key = fallbackKey.ifBlank { firstString(obj, "channel", "name") ?: "" }
            val list = out.getOrPut(key) { ArrayList() }
            parseProgramArray(epgArr, dateHint, list)
            return out
        }

        // 常见包装层：data / result
        val wrapper = obj.optJSONObject("data") ?: obj.optJSONObject("result")
        if (wrapper != null) return parseJson(wrapper.toString(), fallbackKey)

        // 分组 JSON：每个 key 是一个频道名
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k) ?: continue
            val list = out.getOrPut(k) { ArrayList() }
            when (v) {
                is JSONArray -> parseProgramArray(v, dateHint, list)
                is JSONObject -> {
                    val arr = v.optJSONArray("epg_data") ?: v.optJSONArray("programs")
                        ?: v.optJSONArray("list") ?: v.optJSONArray("epg")
                    if (arr != null) {
                        parseProgramArray(arr, firstString(v, "date") ?: dateHint, list)
                    }
                }
            }
        }
        out.values.forEach { it.sortBy { p -> p.start } }
        return out
    }

    private fun parseProgramArray(arr: JSONArray, dateHint: String, out: MutableList<Program>) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = programFromJson(o, dateHint) ?: continue
            out.add(p)
        }
        out.sortBy { it.start }
    }

    /** 从任意节目对象里提取标题、开始、结束，字段名兼容多种写法 */
    private fun programFromJson(o: JSONObject, dateHint: String): Program? {
        val title = firstString(o, "title", "name", "n", "program", "programName", "text", "show")
            ?: return null
        val startS = firstString(o, "start", "startTime", "begin", "time", "t", "s", "from")
            ?: return null
        val endS = firstString(o, "end", "endTime", "stop", "timeEnd", "e", "et", "to")
        val s = parseTimeAny(startS, dateHint) ?: return null
        var e = if (endS != null) parseTimeAny(endS, dateHint) else null
        if (e == null) e = s + DEFAULT_PROGRAM_MS
        if (e <= s) e += 24 * 60 * 60 * 1000L
        return Program(title.trim(), s, e)
    }

    private fun firstString(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.opt(k) ?: continue
            if (v is String && v.isNotBlank()) return v.trim()
            if (v is Number) return v.toString()
        }
        return null
    }

    private fun firstArray(o: JSONObject, vararg keys: String): JSONArray? {
        for (k in keys) {
            val v = o.opt(k) ?: continue
            if (v is JSONArray) return v
        }
        return null
    }

    // ---- XMLTV ----

    private fun parseXmltv(raw: String): Map<String, List<Program>> {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(raw))
        val nowMs = System.currentTimeMillis()
        val result = HashMap<String, MutableList<Program>>()
        val idToName = HashMap<String, String>()
        var channel: String? = null
        var startText: String? = null
        var stopText: String? = null
        var title: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "channel" -> {
                        channel = parser.getAttributeValue(null, "id")?.trim()
                        if (channel != null) idToName[channel!!] = channel!!
                    }
                    "display-name" -> {
                        if (channel != null) {
                            val dn = parser.nextText()?.trim()
                            if (!dn.isNullOrBlank()) idToName[channel!!] = dn
                        }
                    }
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
                val s = parseTimeAny(startText ?: "")
                val e = parseTimeAny(stopText ?: "")
                val t = title?.trim()
                if (ch != null && s != null && e != null && e > nowMs && !t.isNullOrBlank() && t.length < 200) {
                    val p = Program(t, s, e)
                    val names = LinkedHashSet<String>()
                    names.add(ch)
                    idToName[ch]?.let { names.add(it) }
                    for (n in names) result.getOrPut(n) { ArrayList() }.add(p)
                }
                channel = null
                startText = null
                stopText = null
                title = null
            }
            event = parser.next()
        }
        result.values.forEach { it.sortBy { p -> p.start } }
        return result
    }

    // ---- CSV ----

    private fun looksLikeCsv(t: String): Boolean {
        if (t.isBlank()) return false
        val first = t.lineSequence().firstOrNull() ?: return false
        return (first.contains(',') || first.contains('\t')) && t.lineSequence().take(3).count() >= 2
    }

    private fun parseCsv(raw: String, fallbackKey: String): Map<String, List<Program>> {
        val out = HashMap<String, MutableList<Program>>()
        val lines = raw.lineSequence()
            .map { it.trim().trimStart('\uFEFF') }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .toList()
        for (line in lines) {
            val parts = if (line.contains('\t')) line.split('\t') else line.split(',')
            if (parts.size < 3) continue
            val ch = parts[0].trim().ifBlank { fallbackKey }
            if (ch.isBlank()) continue
            var s: String? = null
            var e: String? = null
            var title: String? = null
            for (i in 1 until parts.size) {
                val p = parts[i].trim()
                if (p.isBlank()) continue
                if (looksLikeTime(p)) {
                    if (s == null) s = p else if (e == null) e = p
                } else if (title == null) {
                    title = p
                }
            }
            if (title == null || s == null) continue
            val st = parseTimeAny(s) ?: continue
            var et = if (e != null) parseTimeAny(e) else null
            if (et == null) et = st + DEFAULT_PROGRAM_MS
            if (et <= st) et += 24 * 60 * 60 * 1000L
            out.getOrPut(ch) { ArrayList() }.add(Program(title, st, et))
        }
        out.values.forEach { it.sortBy { p -> p.start } }
        return out
    }

    private fun looksLikeTime(v: String): Boolean {
        if (v.length >= 10 && v.any { it == '-' || it == '/' }) return true
        if (v.matches(Regex("^\\d{1,2}:\\d{2}(?::\\d{2})?$"))) return true
        if (v.matches(Regex("^\\d{12,14}$"))) return true
        return false
    }

    /** 通用时间解析：XMLTV / yyyy-MM-dd HH:mm / yyyyMMddHHmmss / HH:mm / 时间戳 */
    private fun parseTimeAny(value: String, dateHint: String = ""): Long? {
        val v = value.trim()
        if (v.isBlank()) return null

        // 秒 / 毫秒时间戳
        if (v.matches(Regex("^\\d{10,13}$"))) {
            val n = v.toLong()
            return if (n < 100_000_000_000L) n * 1000L else n
        }

        // XMLTV：20261122000000 +0800
        val xmltv = Regex("^(\\d{14})\\s*([+-]\\d{4})?$").find(v)
        if (xmltv != null) {
            val zone = xmltv.groupValues[2].ifBlank { "+0800" }
            return try {
                SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).parse(xmltv.groupValues[1] + " " + zone)?.time
            } catch (e: Exception) { null }
        }

        // 纯时钟：04:22 / 04:22:30，配日期提示
        val clock = Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$").find(v)
        if (clock != null) {
            val date = normalizeDateHint(dateHint)
            val hh = clock.groupValues[1].padStart(2, '0')
            val mm = clock.groupValues[2]
            val ss = clock.groupValues[3].ifBlank { "00" }
            return parseDateTime("$date $hh:$mm:$ss")
        }

        return parseDateTime(v)
    }

    private fun normalizeDateHint(hint: String): String {
        val h = hint.trim()
        if (h.matches(Regex("^\\d{8}$"))) {
            return h.substring(0, 4) + "-" + h.substring(4, 6) + "-" + h.substring(6, 8)
        }
        return h.ifBlank {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TZ_SH }
                .format(java.util.Date())
        }
    }

    private fun parseDateTime(v: String): Long? {
        val formats = arrayOf(
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm",
            "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm",
            "yyyyMMddHHmmss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ssZ"
        )
        for (f in formats) {
            try {
                val fmt = SimpleDateFormat(f, Locale.US)
                fmt.timeZone = TZ_SH
                fmt.isLenient = false
                fmt.parse(v)?.let { return it.time }
            } catch (e: Exception) {
                // 试下一个格式
            }
        }
        return null
    }

    // ---------------- 频道名匹配 ----------------

    /** 在任意频道集合里找到与直播源频道对应的 id */
    private fun matchChannelId(ids: Collection<String>, name: String, tvgId: String): String? {
        if (ids.isEmpty()) return null
        val candidates = candidateKeys(name, tvgId)
        // 1) 完全相等
        candidates.firstNotNullOfOrNull { c -> ids.firstOrNull { it.equals(c, true) } }
            ?.let { return it }
        // 2) 前缀 + 后续非数字（CCTV1 -> CCTV1综合，但不误配 CCTV10）
        candidates.firstNotNullOfOrNull { c ->
            ids.firstOrNull { id ->
                id.length >= c.length && id.startsWith(c, true) &&
                    (id.length == c.length || !id[c.length].isDigit())
            }
        }?.let { return it }
        // 3) 子串（浙江卫视 -> 浙江卫视4K）
        candidates.firstNotNullOfOrNull { c -> ids.firstOrNull { it.contains(c, true) } }
            ?.let { return it }
        return null
    }

    /** 从解析结果里挑出当前频道的节目；单频道接口用 fallbackKey，兜底取唯一一组 */
    private fun pickPrograms(map: Map<String, List<Program>>, fallbackKey: String): List<Program>? {
        if (map.isEmpty()) return null
        val id = matchChannelId(map.keys, fallbackKey, "")
        if (id != null) return map[id]
        return if (map.size == 1) map.values.first() else null
    }

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
            if (cleaned.endsWith("卫视")) list.add(cleaned)
        }
        if (raw.isNotBlank()) list.add(raw.replace(" ", ""))
        return list.distinct().take(4)
    }

    private fun currentProgram(list: List<Program>?): Program? {
        if (list.isNullOrEmpty()) return null
        val nowMs = System.currentTimeMillis()
        return list.lastOrNull { it.start <= nowMs && it.end > nowMs }
    }

    private fun toNowNext(list: List<Program>?): NowNext? {
        if (list.isNullOrEmpty()) return null
        val nowMs = System.currentTimeMillis()
        val now = list.lastOrNull { it.start <= nowMs && it.end > nowMs }
        val next = list.firstOrNull { it.start > nowMs }
        return if (now == null && next == null) null else NowNext(now, next)
    }

    // ---------------- 网络 ----------------

    /** 按频道拿单频道接口原文 */
    private fun fetch(key: String): String? {
        val encoded = try { URLEncoder.encode(key, "UTF-8") } catch (e: Exception) { key }
        for (url in buildUrls(currentApi(), encoded)) {
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 IPTVPlayer").build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val text = resp.body?.string()
                        if (!text.isNullOrBlank() && detectFormat(text) != Format.UNKNOWN) return text
                    }
                }
            } catch (e: Exception) {
                // 换下一个接口
            }
        }
        return null
    }

    /** 全量接口原文：不拼频道参数 */
    private fun fetchRaw(url: String): String? = try {
        val req = Request.Builder().url(url.trim())
            .header("User-Agent", "Mozilla/5.0 IPTVPlayer").build()
        client.newCall(req).execute().use { resp ->
            val text = if (resp.isSuccessful) resp.body?.string() else null
            if (!text.isNullOrBlank() && detectFormat(text) != Format.UNKNOWN) text else null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 根据模板拼实际请求地址。
     * - 支持 {key}/{ch}/{name}/{id} 占位符；
     * - 没有占位符时，地址以 =/&/? 结尾就直接补频道名，否则自动加 ?ch=；
     * - 多个接口可用换行或分号分隔，逐个尝试。
     */
    private fun buildUrls(template: String, encoded: String): List<String> {
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

    // ---------------- 单频道缓存 ----------------

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

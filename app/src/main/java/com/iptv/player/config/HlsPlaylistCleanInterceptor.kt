package com.iptv.player.config

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * 央视频源播放列表清洗器。
 *
 * 实测央视频(ysp)的 m3u8 每隔 5 秒在真分片之间插入 0.01 秒的占位分片：
 *   #EXTINF:0.010,
 *   http://tv2026.quicking.com.cn/index2026/ysp-pad.ts
 * 这个分片在服务器上不存在（HTTP 404），ExoPlayer 播到它就抛 2004。
 *
 * 清洗策略（v1.33 修正）：
 *  1. 凡是 URI 含 ysp-pad 的分片一律视为占位符；
 *  2. 所有时长 <= 0.05 秒的超短分片也视为占位符；
 *  3. 关键：占位分片不再「删除」，而是加 #EXT-X-GAP 标记后保留。
 *     删除会让后面所有分片的序号整体前移，每次播放列表刷新（约 5 秒）序号都会变化，
 *     播放器会把已经播过的分片当成新分片重新下载、时间轴也跟着跳，
 *     表现就是「播一会儿卡一下」。加 GAP 后序号与时间轴完全不变，
 *     播放器直接跳过该分片、不发起请求，也就不会再有 404。
 */
class HlsPlaylistCleanInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        // 除了看 URL 后缀，也认 HLS 的 MIME 类型：
        // 有些源的分片列表地址不带 .m3u8（如 /live/index?type=1），
        // 只按后缀判断会漏掉占位分片，导致播放中每 5 秒触发一次恢复、表现为周期性卡顿。
        val url = chain.request().url.toString()
        val mime = response.header("Content-Type").orEmpty().lowercase()
        val isHlsPlaylist = url.contains(".m3u8", ignoreCase = true) || mime.contains("mpegurl")
        if (!isHlsPlaylist) {
            return response
        }
        val body = response.body ?: return response
        val contentType = body.contentType()

        val text = try {
            body.string()
        } catch (e: Exception) {
            return response
        }
        if (!text.contains("#EXTINF", ignoreCase = true)) {
            return response.newBuilder().body(text.toResponseBody(contentType)).build()
        }

        val lines = text.lineSequence().toList()
        val kept = ArrayList<String>(lines.size)
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val isInf = line.startsWith("#EXTINF", ignoreCase = true)
            if (isInf && i + 1 < lines.size) {
                val next = lines[i + 1]
                val isUri = next.isNotBlank() && !next.startsWith("#")
                if (isUri && isPlaceholder(next, line)) {
                    // 保留 EXTINF 与 URI（序号、时间轴不变），只加 GAP 让播放器跳过下载
                    kept.add(line)
                    kept.add("#EXT-X-GAP")
                    kept.add(next)
                    i += 2
                    continue
                }
            }
            kept.add(line)
            i++
        }
        val cleaned = kept.joinToString("\n")

        // 长度已变化，移除旧的长度/编码头，交给 OkHttp 重新计算
        return response.newBuilder()
            .body(cleaned.toResponseBody(contentType))
            .removeHeader("Content-Length")
            .removeHeader("Content-Encoding")
            .build()
    }

    /** 判断一个分片是否为无效占位符 */
    private fun isPlaceholder(uri: String, extInf: String?): Boolean {
        if (uri.contains(PAD_MARKER, ignoreCase = true)) return true
        val info = extInf ?: return false
        val seconds = info.substringAfter(":", "")
            .substringBefore(",")
            .trim()
            .toFloatOrNull() ?: return false
        // 真实直播分片一般 2~10 秒；0.05 秒以内的只可能是占位/填充
        return seconds in 0f..0.05f
    }

    private companion object {
        const val PAD_MARKER = "ysp-pad"
    }
}

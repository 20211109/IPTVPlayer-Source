package com.iptv.player.config

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 根据订阅地址下载播放列表文本；若输入本身是文本内容则原样返回 */
object PlaylistLoader {
    // 跟随设置里的证书策略：自签名源在「忽略证书校验」开启时也能下载列表
    private val client get() = HttpCompat.clientBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun load(input: String, isUrl: Boolean): Result<String> = withContext(Dispatchers.IO) {
        if (!isUrl) {
            return@withContext if (input.contains("#EXTM3U", ignoreCase = true) ||
                input.contains("#EXTINF", ignoreCase = true)) {
                Result.success(input)
            } else {
                Result.failure(IllegalArgumentException("文本中未找到 M3U 标记"))
            }
        }
        try {
            val req = Request.Builder()
                .url(input)
                .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Result.failure(IllegalStateException("HTTP ${resp.code}"))
                } else {
                    val text = resp.body?.string().orEmpty()
                    if (text.isBlank()) Result.failure(IllegalStateException("内容为空"))
                    else Result.success(text)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

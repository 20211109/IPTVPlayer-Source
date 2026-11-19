package com.iptv.player.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import com.iptv.player.data.Channel

/**
 * 台标解析（离线优先）：
 *
 *  1) 用户在应用内手动填写的台标链接（Channel.logoOverride，优先级最高）
 *  2) 播放列表自带的 tvg-logo（通常是国内可访问的图片地址）
 *  3) **本地绘制**一个品牌色台标（不联网、不会加载失败、低配盒子也瞬间显示）
 *
 * 之所以把「本地绘制」作为兜底而不是某个公开台标库：
 * 电视盒子/手机很可能无法访问境外站点，任何依赖 GitHub / 境外 CDN 的台标
 * 都会大面积空白。本地绘制保证 100% 有图，网络图只是锦上添花。
 */
object LogoHelper {

    /** 台标 bitmap 缓存（按频道名 + 尺寸），避免列表滚动时反复绘制 */
    private val cache = LruCache<String, Bitmap>(64)

    /** 频道可联网加载的台标地址；没有可用地址时返回 null（直接用本地绘制图） */
    fun resolve(channel: Channel): String? {
        channel.logoOverride.takeIf { it.isNotBlank() }?.let { return normalizeUrl(it.trim()) }
        channel.logo.takeIf { it.isNotBlank() }?.let { return normalizeUrl(it.trim()) }
        return null
    }

    /** 本地绘制的品牌台标（永远可用） */
    fun localLogo(context: Context, channel: Channel, sizePx: Int = 192): Bitmap {
        val key = channel.name.trim().lowercase() + "@" + sizePx
        cache.get(key)?.let { return it }
        val bmp = drawBrandLogo(channel.name, sizePx)
        cache.put(key, bmp)
        return bmp
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    /** 六套偏「高级感」的渐变配色，按频道名稳定挑选 */
    private val PALETTES = arrayOf(
        intArrayOf(0xFF1E3A8A.toInt(), 0xFF06B6D4.toInt()), // 深蓝 → 青
        intArrayOf(0xFF4C1D95.toInt(), 0xFFDB2777.toInt()), // 紫 → 玫红
        intArrayOf(0xFF065F46.toInt(), 0xFF14B8A6.toInt()), // 墨绿 → 青绿
        intArrayOf(0xFF7C2D12.toInt(), 0xFFF59E0B.toInt()), // 棕 → 金
        intArrayOf(0xFF1E293B.toInt(), 0xFF3B82F6.toInt()), // 石墨 → 蓝
        intArrayOf(0xFF831843.toInt(), 0xFFF43F5E.toInt())  // 酒红 → 红
    )

    private fun drawBrandLogo(rawName: String, size: Int): Bitmap {
        val name = rawName.trim().ifBlank { "直播" }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        val radius = size * 0.24f

        // 背景渐变
        val colors = PALETTES[(name.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }) % PALETTES.size]
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), colors[0], colors[1], Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        // 左上柔光，做出玻璃质感
        val gloss = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, size * 0.62f,
                Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size * 0.62f), radius, radius, gloss)

        // 细描边
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * 0.012f
            color = Color.argb(60, 255, 255, 255)
        }
        val innerRect = RectF(rect)
        innerRect.inset(size * 0.008f, size * 0.008f)
        canvas.drawRoundRect(innerRect, radius, radius, border)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            setShadowLayer(size * 0.02f, 0f, size * 0.012f, Color.argb(90, 0, 0, 0))
        }
        val subPaint = Paint(textPaint).apply {
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            color = Color.argb(230, 255, 255, 255)
        }

        val cctv = Regex("""(?i)^cctv[-\s]?(\d{1,2}\+?)""").find(name)
        if (cctv != null) {
            // CCTV 1 / CCTV 5+：上行小字 CCTV，下行大号数字
            textPaint.textSize = size * 0.20f
            canvas.drawText("CCTV", size / 2f, size * 0.38f, textPaint)
            textPaint.textSize = size * 0.46f
            canvas.drawText(cctv.groupValues[1], size / 2f, size * 0.80f, textPaint)
            return bmp
        }

        // 「湖南卫视 / 浙江卫视」→ 大字「湖南」，小字「卫视」
        if (name.endsWith("卫视") && name.length > 2) {
            val main = name.removeSuffix("卫视").take(4)
            textPaint.textSize = if (main.length >= 3) size * 0.28f else size * 0.36f
            canvas.drawText(main, size / 2f, size * 0.56f, textPaint)
            subPaint.textSize = size * 0.16f
            canvas.drawText("卫 视", size / 2f, size * 0.78f, subPaint)
            return bmp
        }

        // 其它频道：最多两行、每行两个字
        val chars = name.replace(Regex("""[（(].*?[)）]"""), "")
            .replace(Regex("""(?i)\s*(高清|超清|4K|HD|标清|SD|频道|直播)\s*"""), "")
            .trim()
            .ifBlank { name }
        val line1: String
        val line2: String
        if (chars.length <= 2) {
            line1 = chars; line2 = ""
        } else if (chars.length <= 4) {
            line1 = chars.substring(0, 2); line2 = chars.substring(2)
        } else {
            line1 = chars.substring(0, 2); line2 = chars.substring(2, 4)
        }
        textPaint.textSize = if (chars.length <= 2) size * 0.38f else size * 0.30f
        if (line2.isBlank()) {
            canvas.drawText(line1, size / 2f, size * 0.62f, textPaint)
        } else {
            canvas.drawText(line1, size / 2f, size * 0.48f, textPaint)
            canvas.drawText(line2, size / 2f, size * 0.80f, textPaint)
        }
        return bmp
    }

    private fun normalizeUrl(url: String): String {
        val u = url.trim()
        return if (u.startsWith("http://") || u.startsWith("https://")) u.replace(" ", "%20") else u
    }

}

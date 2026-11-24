package com.iptv.player.player

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * 黑边（上下 letterbox / 左右 pillarbox）探测。
 *
 * 直播源里「两边黑边太多」通常有两种来源：
 *  1. 内容本身是 4:3（老频道、地方台），16:9 屏幕上按比例铺不满，只能左右留黑；
 *  2. 源端把 4:3 或电影比例的画面直接 pad 进 16:9 帧里，黑边已经是画面的一部分。
 * 两种情况都只能靠「看像素」才能发现——轨道宽高比在第二种情况下是 16:9，看不出来。
 *
 * 所以这里做的是低分辨率采样：把当前显示画面缩到 96×54，统计四周「整列/整行全黑」的宽度。
 * 只认「左右对称」或「上下对称」的黑边，并要求有效画面至少占 45%，
 * 避免把暗场景、全屏黑（缓冲中）、单侧黑底字幕区误判成黑边。
 *
 * 采样失败（个别盒子 PixelCopy 不支持、DRM、surface 无效）一律静默返回 null，
 * 由调用方放弃本轮，绝不影响播放本身。
 */
object BlackBarDetector {

    private const val SAMPLE_W = 96
    private const val SAMPLE_H = 54

    /** 亮度低于该值算「黑」。直播源的黑边基本是纯黑，留余量吸收编码噪声 */
    private const val DARK_LUMA = 34

    /** 有效画面宽/高至少占这个比例，否则视为整屏黑或暗场，不做处理 */
    private const val MIN_CONTENT_RATIO = 0.45f

    /** 黑边是否对称的容忍度（占屏幕比例） */
    private const val SYMMETRY_TOLERANCE_H = 0.05f
    private const val SYMMETRY_TOLERANCE_V = 0.06f

    /** 小于该比例的黑边不值得处理（几像素的边框、编码留边） */
    private const val MIN_BAR_RATIO = 0.035f

    /** 放大倍数上限：再大就不是去黑边而是把画面裁没了 */
    private const val MAX_SCALE_PCT = 175

    /** 采样一旦在设备上失败过就不再反复尝试，避免每轮都触发一次异常 */
    @Volatile
    private var captureBroken = false

    /** 黑边占屏幕的比例（0–1） */
    data class Insets(val left: Float, val right: Float, val top: Float, val bottom: Float) {
        val horizontal: Float get() = left + right
        val vertical: Float get() = top + bottom
    }

    /**
     * 抓一帧画面并缩到 96×54。
     * 必须在主线程调用（TextureView.getBitmap 与 PixelCopy 都要求主线程发起）。
     */
    fun capture(host: View?, isTexture: Boolean, onResult: (Bitmap?) -> Unit) {
        val target = host ?: return onResult(null)
        if (captureBroken) return onResult(null)
        try {
            if (isTexture) {
                val tv = target as? TextureView ?: return onResult(null)
                if (!tv.isAvailable) return onResult(null)
                val bmp = tv.getBitmap(SAMPLE_W, SAMPLE_H)
                return onResult(bmp)
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return onResult(null)
            val sv = target as? SurfaceView ?: return onResult(null)
            if (!sv.holder.surface.isValid) return onResult(null)
            val w = sv.width
            val h = sv.height
            if (w <= 0 || h <= 0) return onResult(null)
            val dst = Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888)
            PixelCopy.request(
                sv,
                Rect(0, 0, w, h),
                dst,
                { result ->
                    if (result == PixelCopy.SUCCESS) onResult(dst) else onResult(null)
                },
                Handler(Looper.getMainLooper())
            )
        } catch (t: Throwable) {
            // 部分盒子/DRM 内容不支持拷贝：标记后不再尝试
            captureBroken = true
            onResult(null)
        }
    }

    /**
     * 分析黑边。返回 null 表示「没有可信的黑边」（包括采样画面全黑、黑白不对称的情况）。
     */
    fun analyze(bmp: Bitmap): Insets? {
        val w = bmp.width
        val h = bmp.height
        if (w < 16 || h < 16) return null
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // 逐列判断「整列都黑」：黑边是贯穿整幅画面的竖条，这个判据比单点亮度可靠得多
        val colDark = BooleanArray(w)
        for (x in 0 until w) {
            var all = true
            for (y in 0 until h) {
                if (luma(px[y * w + x]) >= DARK_LUMA) {
                    all = false
                    break
                }
            }
            colDark[x] = all
        }
        val rowDark = BooleanArray(h)
        for (y in 0 until h) {
            var all = true
            for (x in 0 until w) {
                if (luma(px[y * w + x]) >= DARK_LUMA) {
                    all = false
                    break
                }
            }
            rowDark[y] = all
        }

        var left = 0
        while (left < w && colDark[left]) left++
        var right = 0
        while (right < w - left && colDark[w - 1 - right]) right++
        var top = 0
        while (top < h && rowDark[top]) top++
        var bottom = 0
        while (bottom < h - top && rowDark[h - 1 - bottom]) bottom++

        val insets = Insets(
            left.toFloat() / w,
            right.toFloat() / w,
            top.toFloat() / h,
            bottom.toFloat() / h
        )
        // 整屏黑（缓冲中/切源瞬间）会算出 100% 黑边，必须排除
        if (1f - insets.horizontal < MIN_CONTENT_RATIO) return null
        if (1f - insets.vertical < MIN_CONTENT_RATIO) return null
        // 只有对称的黑边才可信：单侧一大片黑更可能是画面内容（暗场、字幕条、信箱转场）
        val symmetricH = abs(insets.left - insets.right) <= SYMMETRY_TOLERANCE_H
        val symmetricV = abs(insets.top - insets.bottom) <= SYMMETRY_TOLERANCE_V
        if (!symmetricH && !symmetricV) return null
        // 黑边太窄不值得动画面
        if (insets.horizontal < MIN_BAR_RATIO && insets.vertical < MIN_BAR_RATIO) return null
        return insets
    }

    /**
     * 算出「放大到刚好盖住黑边」需要的百分比。
     * 100 = 不需要处理；横向黑边和纵向黑边同时存在时取较大者。
     */
    fun requiredScalePct(insets: Insets): Int {
        val byWidth = if (insets.horizontal >= MIN_BAR_RATIO) {
            100f / (1f - insets.horizontal)
        } else 100f
        val byHeight = if (insets.vertical >= MIN_BAR_RATIO) {
            100f / (1f - insets.vertical)
        } else 100f
        // +1 是给「正好贴边」留一点余量，避免舍入后还残留 1–2 像素黑线
        return max(byWidth, byHeight).toInt().coerceIn(100, MAX_SCALE_PCT)
    }

    private fun luma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}

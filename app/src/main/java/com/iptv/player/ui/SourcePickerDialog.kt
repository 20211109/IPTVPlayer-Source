package com.iptv.player.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.iptv.player.R
import com.iptv.player.data.Channel

/**
 * 手动换源：把当前频道的所有线路列出来，让用户自己选一条。
 *
 * 为什么需要它：
 * - 播放中的自动换线有冷却与熔断（防止两条弱线路来回跳），用户想立刻避开某条
 *   特定线路时，那些保护反而碍事；
 * - 「下一条」这种顺序切换在「前几条都能播但都不理想」时根本绕不过去。
 *
 * 选中后由调用方执行真正的切源，本类只管选，不碰播放器状态。
 */
class SourcePickerDialog(
    private val activity: Activity,
    private val channel: Channel,
    private val currentIndex: Int,
    private val onPick: (Int) -> Unit
) {
    private var dialog: AlertDialog? = null

    fun show() {
        if (dialog?.isShowing == true) return
        if (channel.sources.isEmpty()) return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_source_picker, null)
        val container = view.findViewById<LinearLayout>(R.id.llSourceList)
        val scroll = view.findViewById<ScrollView>(R.id.svSourceList)
        view.findViewById<TextView>(R.id.tvSourcePickerTitle).text =
            "选择线路（共 ${channel.sources.size} 条）"

        val rows = ArrayList<View>(channel.sources.size)
        channel.sources.forEachIndexed { index, source ->
            val row = LayoutInflater.from(activity)
                .inflate(R.layout.item_source_line, container, false)
            row.findViewById<TextView>(R.id.tvLineIndex).text = "${index + 1}"
            row.findViewById<TextView>(R.id.tvLineTitle).text = "线路 ${index + 1}"
            row.findViewById<TextView>(R.id.tvLineUrl).text = describe(source.url)
            row.findViewById<TextView>(R.id.tvLineTag).visibility =
                if (index == currentIndex) View.VISIBLE else View.GONE
            row.setOnClickListener {
                dismiss()
                // 点当前线路等于「什么都不做」，避免白白重连一次
                if (index != currentIndex) onPick(index)
            }
            container.addView(row)
            rows.add(row)
        }

        val d = AlertDialog.Builder(activity)
            .setView(view)
            .create()
        d.setCanceledOnTouchOutside(true)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.setOnDismissListener { dialog = null }
        dialog = d
        d.show()

        // 宽度按屏幕比例给，高度自适应；线路多时限制最大高度，超出的部分滚动
        val metrics = activity.resources.displayMetrics
        val width = (metrics.widthPixels * 0.62f).toInt()
        val maxHeight = (metrics.heightPixels * 0.66f).toInt()
        d.window?.apply {
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.42f)
        }
        scroll.post {
            if (scroll.height > maxHeight) {
                scroll.layoutParams = scroll.layoutParams.apply { height = maxHeight }
            }
        }

        // 打开就把焦点放在「当前线路」上：遥控器一进来就知道自己在哪条
        val focusIndex = if (currentIndex in rows.indices) currentIndex else 0
        rows.getOrNull(focusIndex)?.post { rows.getOrNull(focusIndex)?.requestFocus() }
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    /** 把长 URL 压成「host + 尾部路径」，让用户能区分不同线路又不至于被截断成一样 */
    private fun describe(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return "（地址为空）"
        return try {
            val uri = java.net.URI(text)
            val host = uri.host
            val path = uri.path ?: ""
            if (!host.isNullOrEmpty()) {
                val tail = if (path.length > 22) "…" + path.takeLast(20) else path
                host + tail
            } else {
                clip(text)
            }
        } catch (t: Throwable) {
            clip(text)
        }
    }

    private fun clip(text: String): String =
        if (text.length > 56) text.take(54) + "…" else text
}

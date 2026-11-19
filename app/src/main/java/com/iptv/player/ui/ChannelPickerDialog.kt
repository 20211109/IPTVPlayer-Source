package com.iptv.player.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.iptv.player.R
import com.iptv.player.data.Channel

/**
 * 直播页呼出的「频道选择表」。
 *
 * 触发方式：播放页长按遥控器确认键（再长按一次收起）。
 * 复用主页那套 [ChannelAdapter] 卡片，所以外观、台标、焦点态完全一致；
 * 切换频道时不退出播放页，画面直接接上，更像电视的「换台」而不是「重开一路」。
 */
class ChannelPickerDialog(
    private val activity: Activity,
    private val items: List<Channel>,
    private val current: Channel?,
    private val groupLabel: String,
    private val onPick: (Channel) -> Unit
) {
    private var dialog: AlertDialog? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show() {
        if (isShowing || items.isEmpty()) return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_channel_picker, null)
        val rv = view.findViewById<RecyclerView>(R.id.rvPicker)
        rv.layoutManager = GridLayoutManager(activity, activity.resources.getInteger(R.integer.grid_columns))
        val adapter = ChannelAdapter(onChannelClick = { ch ->
            dismiss()
            // 选中的就是当前正在播的频道时不重连，避免白闪一下
            if (ch !== current) onPick(ch)
        })
        rv.adapter = adapter
        adapter.submitList(items)
        view.findViewById<TextView>(R.id.tvPickerGroup)?.text = groupLabel

        // 走 Activity 统一的深色弹窗主题，避免框架 Dialog 与 Material 主题混用的兼容坑
        val d = AlertDialog.Builder(activity)
            .setView(view)
            .create()
        d.setCanceledOnTouchOutside(true)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.setOnDismissListener { dialog = null }
        dialog = d
        d.show()

        // 窗体尺寸按屏幕比例给：不用 wrap_content，否则频道一多弹窗会顶到全屏
        val metrics = activity.resources.displayMetrics
        val tv = activity.resources.configuration.uiMode and
            Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
        d.window?.setLayout(
            (metrics.widthPixels * if (tv) 0.86f else 0.94f).toInt(),
            (metrics.heightPixels * if (tv) 0.78f else 0.62f).toInt()
        )

        // 打开时直接停在当前正在播放的频道上：遥控器一进来就知道"现在在看哪个"
        val index = items.indexOfFirst { it === current || (current != null && it.name == current.name) }
        if (index >= 0) {
            (rv.layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(index, 0)
            focusAt(rv, index)
        } else {
            rv.post { rv.requestFocus() }
        }
    }

    /** 等一帧让 ViewHolder 绑定完成后把焦点交给目标卡片；仍未拿到焦点则退回整表焦点 */
    private fun focusAt(rv: RecyclerView, index: Int) {
        rv.post {
            rv.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
        }
        rv.postDelayed({
            val d = dialog ?: return@postDelayed
            if (!d.isShowing || rv.findFocus() != null) return@postDelayed
            rv.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus()
            if (rv.findFocus() == null) rv.requestFocus()
        }, 150L)
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }
}

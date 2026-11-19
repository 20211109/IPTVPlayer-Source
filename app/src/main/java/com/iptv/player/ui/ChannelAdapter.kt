package com.iptv.player.ui

import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player.R
import com.iptv.player.data.Channel
import com.iptv.player.util.LogoHelper

class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onChannelFocus: (Channel) -> Unit = {},
    private val onChannelLongClick: (Channel) -> Unit = {}
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private val items = mutableListOf<Channel>()
    private var numberOffset = 0
    /** 加载失败的台标地址，避免滚动时反复请求同一个坏链接 */
    private val failedLogos = mutableSetOf<String>()

    fun submitList(list: List<Channel>, numberOffset: Int = 0) {
        this.numberOffset = numberOffset
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 台标被用户修改后，清除失败标记以便重新加载 */
    fun invalidateLogo(name: String) {
        failedLogos.removeAll { it.startsWith(name + "|") }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val logoSymbol: TextView = view.findViewById(R.id.tvLogo)
        val logoImage: ImageView = view.findViewById(R.id.ivLogo)
        val number: TextView = view.findViewById(R.id.tvNumber)
        val name: TextView = view.findViewById(R.id.tvName)
        val group: TextView = view.findViewById(R.id.tvGroup)
        val score: TextView = view.findViewById(R.id.tvScore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.name
        holder.number.text = (numberOffset + position + 1).toString()
        // 分组与线路数合并成一行，避免第三行文字被卡片高度裁掉
        holder.group.text = ch.group + " · " + ch.sources.size + " " +
            holder.itemView.context.getString(R.string.source_count)

        bindLogo(holder, ch)

        // 已取消测速：有线路就显示「直播」徽章，无线路则隐藏徽章
        if (ch.sources.isEmpty()) {
            holder.score.visibility = View.GONE
        } else {
            holder.score.visibility = View.VISIBLE
            holder.score.text = holder.itemView.context.getString(R.string.live_badge)
        }

        holder.itemView.setOnClickListener { onChannelClick(ch) }
        holder.itemView.setOnLongClickListener {
            onChannelLongClick(ch)
            true
        }
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onChannelFocus(ch)
        }
    }

    /**
     * 台标：先显示本地绘制的品牌图（立刻可见、绝不空白），
     * 若该频道有可访问的网络台标，再异步替换。网络失败时保持本地图。
     */
    private fun bindLogo(holder: VH, ch: Channel) {
        val ctx = holder.itemView.context
        val local: Bitmap = LogoHelper.localLogo(ctx, ch)
        holder.logoSymbol.visibility = View.GONE

        val url = LogoHelper.resolve(ch)
        val key = ch.normalizedName() + "|" + (url ?: "")
        if (url == null || failedLogos.contains(key)) {
            holder.logoImage.setImageBitmap(local)
            holder.logoImage.visibility = View.VISIBLE
            return
        }

        holder.logoImage.setImageBitmap(local)
        holder.logoImage.visibility = View.VISIBLE
        holder.logoImage.load(url) {
            crossfade(true)
            listener(
                onSuccess = { _, _ ->
                    holder.logoImage.visibility = View.VISIBLE
                },
                onError = { _, _ ->
                    failedLogos.add(key)
                    holder.logoImage.setImageBitmap(local)
                }
            )
        }
    }

    override fun getItemCount() = items.size
}

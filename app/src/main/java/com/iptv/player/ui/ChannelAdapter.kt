package com.iptv.player.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player.R
import com.iptv.player.data.Channel
import com.iptv.player.data.FavoriteManager
import com.iptv.player.util.LogoHelper

/**
 * 频道列表适配器：手机用列表行（item_channel_phone），电视用网格卡（item_channel）。
 * 两种布局的 ID 完全一致，除「当前节目」那一行外共用同一套绑定逻辑。
 */
class ChannelAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onChannelFocus: (Channel) -> Unit = {},
    private val onChannelLongClick: (Channel) -> Unit = {},
    private val favorites: FavoriteManager? = null,
    private val onFavoriteClick: (Channel) -> Unit = {},
    /** 当前节目文本，由 Activity 从 EPG 缓存里取；没有则回退到分组名 */
    private val programOf: (Channel) -> String = { it.group }
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    private val items = mutableListOf<Channel>()
    private var numberOffset = 0
    private var phoneLayout = false
    /** 加载失败的台标地址，避免滚动时反复请求同一个坏链接 */
    private val failedLogos = mutableSetOf<String>()

    fun submitList(list: List<Channel>, numberOffset: Int = 0, phoneLayout: Boolean = false) {
        this.numberOffset = numberOffset
        this.phoneLayout = phoneLayout
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** 收藏状态变化后只刷新受影响的那一项，避免整表重排丢失焦点 */
    fun notifyFavoriteChanged(channel: Channel) {
        val idx = items.indexOfFirst { it.normalizedName() == channel.normalizedName() }
        if (idx >= 0) notifyItemChanged(idx)
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
        val star: ImageView = view.findViewById(R.id.ivStar)
        /** 分组 + 直播标识所在整行 */
        val metaRow: View = view.findViewById<View>(R.id.tvGroup).parent as View
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = if (phoneLayout) R.layout.item_channel_phone else R.layout.item_channel
        val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ch = items[position]
        holder.name.text = ch.name
        holder.number.text = (numberOffset + position + 1).toString()

        val program = programOf(ch)
        holder.group.text = if (program.isNotBlank()) program else ch.group

        bindLogo(holder, ch)

        // 有线路就显示「直播中」，无线路隐藏
        if (ch.sources.isEmpty()) {
            holder.score.visibility = View.GONE
        } else {
            holder.score.visibility = View.VISIBLE
            holder.score.text = holder.itemView.context.getString(R.string.live_badge)
        }

        val fav = favorites?.isFavorite(ch) == true
        holder.star.isSelected = fav
        holder.star.contentDescription = holder.itemView.context.getString(
            if (fav) R.string.favorite_remove else R.string.favorite_add
        )
        // 星标自己处理点击，并截断事件，避免同时触发整行的「打开频道」
        holder.star.setOnClickListener {
            onFavoriteClick(ch)
            holder.star.isSelected = favorites?.isFavorite(ch) == true
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

    /** 台标：本地绘制图先占位，网络台标异步覆盖；失败保持本地图 */
    private fun bindLogo(holder: VH, ch: Channel) {
        val ctx = holder.itemView.context
        val local: android.graphics.Bitmap = LogoHelper.localLogo(ctx, ch)
        holder.logoSymbol.visibility = View.GONE
        holder.logoImage.setImageBitmap(local)
        holder.logoImage.visibility = View.VISIBLE

        val url = LogoHelper.resolve(ch) ?: return
        val key = ch.normalizedName() + "|" + url
        if (failedLogos.contains(key)) return
        holder.logoImage.load(url) {
            crossfade(true)
            listener(
                onSuccess = { _, _ -> holder.logoImage.visibility = View.VISIBLE },
                onError = { _, _ ->
                    failedLogos.add(key)
                    holder.logoImage.setImageBitmap(local)
                }
            )
        }
    }

    override fun getItemCount() = items.size
}

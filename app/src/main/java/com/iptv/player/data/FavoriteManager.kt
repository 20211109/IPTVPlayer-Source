package com.iptv.player.data

import android.content.Context

/**
 * 频道收藏：按归一化频道名保存，本地持久化。
 *
 * 用归一化名而不是对象引用或下标，是因为订阅源静默刷新后频道对象会整批重建、
 * 顺序也会变；只有名字是跨刷新稳定的标识。
 *
 * 这一层完全独立，不改动 SourceRepository 的任何既有读写，
 * 因此原有功能（直播源、线路评分、上次播放频道）不受影响。
 */
class FavoriteManager(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("iptv_favorites", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_NAMES = "names"
    }

    /** 进程内缓存，避免每次绑定卡片都读一次 SharedPreferences */
    private var cache: MutableSet<String>? = null

    fun all(): Set<String> {
        cache?.let { return it }
        val loaded = try {
            sp.getStringSet(KEY_NAMES, emptySet())?.toMutableSet() ?: mutableSetOf()
        } catch (e: Exception) {
            mutableSetOf()
        }
        cache = loaded
        return loaded
    }

    fun isFavorite(channel: Channel): Boolean = all().contains(channel.normalizedName())

    /** 切换收藏状态，返回切换后是否为「已收藏」 */
    fun toggle(channel: Channel): Boolean {
        val set = all().toMutableSet()
        val key = channel.normalizedName()
        val nowFavorite = if (set.contains(key)) {
            set.remove(key)
            false
        } else {
            set.add(key)
            true
        }
        sp.edit().putStringSet(KEY_NAMES, set).apply()
        cache = set
        return nowFavorite
    }

    fun count(): Int = all().size
}

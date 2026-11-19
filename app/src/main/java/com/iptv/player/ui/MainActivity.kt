package com.iptv.player.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player.R
import com.iptv.player.config.PlaylistLoader
import com.iptv.player.config.HttpCompat
import com.iptv.player.config.PlaybackSettings
import com.iptv.player.data.Channel
import com.iptv.player.data.M3uParser
import com.iptv.player.data.SourceRepository
import com.iptv.player.epg.EpgManager
import com.iptv.player.player.PlayerManager
import com.iptv.player.player.StallDetector
import com.iptv.player.update.UpdateInfo
import com.iptv.player.update.UpdateManager
import com.iptv.player.util.LogoHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var repo: SourceRepository
    private lateinit var adapter: ChannelAdapter
    private lateinit var playSettings: PlaybackSettings

    private var playerManager: PlayerManager? = null
    private var stallDetector: StallDetector? = null
    private var channels: List<Channel> = emptyList()
    private var filteredChannels: List<Channel> = emptyList()
    private var currentChannel: Channel? = null
    private var focusedChannel: Channel? = null
    private var currentGroup = "ALL"
    /** 分组顺序，供左右滑动切换 */
    private var groupKeys = listOf("ALL")
    /** 直播源管理弹窗引用：异步添加完成后需要刷新它的列表 */
    private var sourceDialog: SourceManagerDialog? = null
    private lateinit var epgManager: EpgManager
    private var isLandscapePlayback = false
    /** 当前线路标签，例如「线路 1/2」，状态栏各处复用 */
    private var currentLineLabel = "直播"
    private val uiHandler = Handler(Looper.getMainLooper())
    /** 焦点卡 EPG：防抖 + 序号校验，避免快速滑动时旧频道结果覆盖新频道 */
    private var heroEpgRunnable: Runnable? = null
    private var heroEpgSeq = 0
    private var lastHeroScrollMs = 0L
    private var heroTouchX = 0f
    private var heroTouchY = 0f
    /** 直播页上/下滑或遥控上/下键换台的防抖时间戳 */
    private var lastLiveSwitchMs = 0L
    /** 更新下载任务：进度框点「取消」时中断 */
    private var updateJob: kotlinx.coroutines.Job? = null
    /** 直播页上下划动换台手势（在 dispatchTouchEvent 中统一分发，确保收到完整序列） */
    private lateinit var liveSwipe: GestureDetector
    private val hideStatsRunnable = Runnable {
        if (!playSettings.showStats) statsBar.visibility = View.GONE
    }
    /** 节目单浮层只短暂显示，避免长时间盖住画面 */
    private val hideEpgRunnable = Runnable { epgPanel.visibility = View.GONE }

    /**
     * 起播看门狗：20 秒还没出画面，才认为这条线路卡死、自动换下一条。
     * 免费直播源经常「能连上但永远不吐数据」，没有这个兜底会一直黑屏。
     * 关键：一旦成功播放过就立即失效（pm.hasPlayed() 为 true），
     * 绝不会在观看途中因为一次抖动把用户切到别的线路。
     */
    private val startWatchdog = Runnable {
        val pm = playerManager ?: return@Runnable
        if (pm.hasPlayed() || pm.player.playbackState == Player.STATE_READY) return@Runnable
        val switched = pm.switchToNextSource(force = true)
        if (switched) {
            toast("这条线路无响应，已自动换线路")
        } else {
            setStatus("所有线路都连不上，请换个频道", R.color.red)
            toast("所有线路都连不上，请换个频道")
        }
    }

    // 视图
    private lateinit var pageList: View
    private lateinit var pagePlayer: View
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStats: TextView
    private lateinit var tvPlayerTitle: TextView
    private lateinit var tvChannelCount: TextView
    private lateinit var groupContainer: LinearLayout
    private lateinit var groupScroller: android.widget.HorizontalScrollView
    private lateinit var heroCard: View
    private lateinit var tvHeroTitle: TextView
    private lateinit var tvHeroSub: TextView
    private lateinit var tvHeroEpg: TextView
    private lateinit var tvHeroEpgNext: TextView
    private lateinit var ivHeroLogo: ImageView
    private lateinit var dotStatus: View
    private lateinit var statsBar: View
    private lateinit var pbLoading: ProgressBar
    // 节目单
    private lateinit var epgPanel: View
    private lateinit var tvEpgTime: TextView
    private lateinit var tvEpgNow: TextView
    private lateinit var tvEpgNext: TextView
    private lateinit var btnFullscreen: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repo = SourceRepository(this)
        epgManager = EpgManager(this)
        playSettings = PlaybackSettings(this)
        // 证书策略必须在创建任何网络客户端之前生效
        HttpCompat.allowInsecureTls = playSettings.insecureTls

        pageList = findViewById(R.id.pageList)
        pagePlayer = findViewById(R.id.pagePlayer)
        recyclerView = findViewById(R.id.recyclerView)
        tvEmpty = findViewById(R.id.tvEmpty)
        progressBar = findViewById(R.id.progressBar)
        tvStats = findViewById(R.id.tvStats)
        tvPlayerTitle = findViewById(R.id.tvPlayerTitle)
        tvChannelCount = findViewById(R.id.tvChannelCount)
        groupContainer = findViewById(R.id.groupContainer)
        groupScroller = findViewById(R.id.groupScroller)
        heroCard = findViewById(R.id.heroCard)
        tvHeroTitle = findViewById(R.id.tvHeroTitle)
        tvHeroSub = findViewById(R.id.tvHeroSub)
        tvHeroEpg = findViewById(R.id.tvHeroEpg)
        tvHeroEpgNext = findViewById(R.id.tvHeroEpgNext)
        ivHeroLogo = findViewById(R.id.ivHeroLogo)
        dotStatus = findViewById(R.id.dotStatus)
        statsBar = findViewById(R.id.statsBar)
        pbLoading = findViewById(R.id.pbLoading)
        epgPanel = findViewById(R.id.epgPanel)
        tvEpgTime = findViewById(R.id.tvEpgTime)
        tvEpgNow = findViewById(R.id.tvEpgNow)
        tvEpgNext = findViewById(R.id.tvEpgNext)
        btnFullscreen = findViewById(R.id.btnFullscreen)

        // 列数随屏幕自适应（手机 2 列 / 平板 4 列 / TV 5 列）
        val columns = resources.getInteger(R.integer.grid_columns)
        adapter = ChannelAdapter(
            onChannelClick = { openChannel(it) },
            onChannelFocus = { updateHero(it) },
            onChannelLongClick = { showLogoEditor(it) }
        )
        recyclerView.layoutManager = GridLayoutManager(this, columns)
        recyclerView.adapter = adapter

        // 触摸设备：焦点卡跟随列表滚动；TV：只认遥控器焦点，避免滚动抢焦点
        if (!isTv()) {
            recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    if (dy == 0) return
                    val now = System.currentTimeMillis()
                    if (now - lastHeroScrollMs < 250L) return
                    lastHeroScrollMs = now
                    updateHeroFromFirstVisible()
                }

                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) updateHeroFromFirstVisible()
                }
            })

            // 左右滑动频道列表：切换上一个/下一个分组；上下滑动仍由 RecyclerView 自己处理
            val groupSwipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
                ): Boolean {
                    if (e1 == null) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (kotlin.math.abs(dx) < 90f || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.5f) {
                        return false
                    }
                    switchGroupBy(if (dx < 0) 1 else -1)
                    return true
                }
            })
            recyclerView.setOnTouchListener { _, event ->
                groupSwipe.onTouchEvent(event)
                false // 不拦截 RecyclerView 的上下滚动
            }
        }

        findViewById<Button>(R.id.btnConfig).setOnClickListener { showSourceManager() }
        findViewById<Button>(R.id.btnBack).setOnClickListener { closePlayer() }
        findViewById<Button>(R.id.btnAbout).setOnClickListener { showAboutDialog() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        btnFullscreen.setOnClickListener { toggleFullscreen() }
        if (!isPhone()) btnFullscreen.visibility = View.GONE
        // 触摸设备支持左右滑动切换焦点频道；TV 用方向键移动焦点，不拦截触摸
        if (!isTv()) {
            heroCard.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        heroTouchX = event.x
                        heroTouchY = event.y
                        false
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = event.x - heroTouchX
                        val dy = event.y - heroTouchY
                        if (kotlin.math.abs(dx) > 60f && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.4f) {
                            stepHero(if (dx < 0) 1 else -1)
                            true // 消费这次 UP，避免误触发点击播放
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
        }
        heroCard.setOnClickListener { focusedChannel?.let { openChannel(it) } }

        // 直播页上下划动换台（触屏设备：手机/带触摸的盒子）。
        // 不在此拦截事件，PlayerView 控制条、单击显示控制等仍照常工作。
        // 只在明显的垂直滑动时才换台，避免误触。
        if (!isTv()) {
            liveSwipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
                ): Boolean {
                    if (e1 == null) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (kotlin.math.abs(dy) < 90f || kotlin.math.abs(dy) < kotlin.math.abs(dx) * 1.5f) {
                        return false
                    }
                    // 上滑 → 下一个频道，下滑 → 上一个频道
                    switchLiveChannel(if (dy < 0) 1 else -1)
                    return true
                }
            })
        }

        rebuildChannelsFromSources()

        // 启动时静默检查更新（无新版本或网络不通时无任何打扰）
        checkForUpdate()
    }

    /**
     * 遥控上下键换台：只在直播播放页生效，列表页完全交给系统焦点导航。
     * 同时支持标准方向键(DPAD_UP/DOWN)与部分盒子的频道键(CHANNEL_UP/DOWN)。
     * 有弹窗打开时不拦截，避免抢走对话框的按键焦点。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (pagePlayer.visibility != View.VISIBLE || sourceDialog?.isShowing == true) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                switchLiveChannel(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                switchLiveChannel(1)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 直播页上下划动换台：在事件分发入口把触摸喂给手势识别器。
     * 只在播放页可见、且是触屏设备时处理；始终不消费事件（返回 super），
     * 因此 PlayerView 的单击显示控制条、控制条按钮点击都不受影响。
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::liveSwipe.isInitialized && pagePlayer.visibility == View.VISIBLE) {
            liveSwipe.onTouchEvent(event)
        }
        return super.dispatchTouchEvent(event)
    }

    /** 检查新版本并弹窗提示 */
    private fun checkForUpdate() {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                UpdateManager.checkUpdate(this@MainActivity)
            }
            if (info != null && !UpdateManager.isIgnored(this@MainActivity, info.version)) {
                showUpdateDialog(info)
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val current = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        val head = getString(R.string.update_version_switch, current, info.version)
        val msg = if (info.notes.isNotBlank()) {
            "$head\n\n${info.notes.take(300)}"
        } else {
            "$head\n\n" + getString(R.string.update_found_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title))
            .setMessage(msg)
            .setPositiveButton(R.string.update_btn_install) { _, _ -> downloadAndInstall(info) }
            .setNegativeButton(R.string.update_btn_later) { _, _ ->
                UpdateManager.ignoreVersion(this, info.version)
            }
            .setCancelable(true)
            .show()
    }

    /**
     * 下载并安装。
     *
     * 电视盒子上踩过的坑：点「立即更新」后界面毫无反应（10MB 的包经加速器要几十秒），
     * 用户以为卡死；失败时也只有一句 toast，无从自救。所以这里补上：
     * 1) 带百分比的进度框，可取消；
     * 2) 下载/安装失败时弹出手动方案（加速器直链 + 已保存的安装包路径）。
     */
    private fun downloadAndInstall(info: UpdateInfo) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d + 0.5f).toInt()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(2))
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val label = TextView(this).apply {
            text = "0%"
            textSize = 15f
        }
        box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)))
        box.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_downloading_titled, info.version))
            .setView(box)
            .setNegativeButton(R.string.update_btn_cancel) { _, _ -> updateJob?.cancel() }
            .setCancelable(false)
            .create()
        dialog.show()

        updateJob = lifecycleScope.launch {
            val apk = withContext(Dispatchers.IO) {
                UpdateManager.download(this@MainActivity, info) { p ->
                    runOnUiThread {
                        bar.progress = p
                        label.text = "$p%"
                    }
                }
            }
            if (isFinishing || isDestroyed) return@launch
            dialog.dismiss()
            if (apk == null) {
                toast(getString(R.string.update_download_fail))
                showManualUpdateDialog(info, null)
                return@launch
            }
            when (UpdateManager.install(this@MainActivity, apk)) {
                UpdateManager.InstallResult.DISPATCHED -> {
                    toast(getString(R.string.update_opening_installer))
                    watchInstallLaunch(apk)
                }
                // 权限页已打开，同时给出手动方案（安装包已经下载好，不必重下）
                else -> showManualUpdateDialog(info, apk.absolutePath)
            }
        }
    }

    /**
     * 发起安装后盯一眼：10 秒后如果还停留在本界面（安装界面一直没起来），
     * 说明该 ROM 静默拦截了安装，给用户一个明确的出口，而不是对着"没反应"发呆。
     */
    private fun watchInstallLaunch(apk: java.io.File) {
        uiHandler.postDelayed({
            if (!isFinishing && !isDestroyed && hasWindowFocus()) {
                toast(getString(R.string.update_installer_blocked, apk.absolutePath))
            }
        }, 10_000L)
    }

    /** 自动安装失败时的兜底提示：可复制的加速器直链 + 已保存的安装包路径 */
    private fun showManualUpdateDialog(info: UpdateInfo, apkPath: String?) {
        val link = UpdateManager.manualDownloadUrl(info.apkUrl)
        val msg = buildString {
            append(getString(R.string.update_manual_intro))
            append("\n\n1. ")
            append(getString(R.string.update_manual_link_hint))
            append("\n")
            append(link)
            if (!apkPath.isNullOrBlank()) {
                append("\n\n2. ")
                append(getString(R.string.update_manual_path_hint))
                append("\n")
                append(apkPath)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_manual_title))
            .setMessage(msg)
            .setPositiveButton(R.string.update_btn_copy_link) { _, _ -> copyLink(link) }
            .setNegativeButton(R.string.update_btn_close, null)
            .show()
    }

    private fun copyLink(link: String) {
        try {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("IPTV update url", link))
            toast(getString(R.string.update_link_copied))
        } catch (t: Throwable) {
            toast(link)
        }
    }

    /**
     * 依据所有「已启用」的直播源重建频道列表。
     * 同名频道会合并线路；用户手动改过的台标在重建时保留。
     */
    private fun rebuildChannelsFromSources() {
        // 先记下手动台标，重建后回填，避免开关直播源导致用户设置丢失
        val oldOverrides = repo.loadChannels()
            .associateBy { it.normalizedName() }
            .mapValues { it.value.logoOverride }

        val sources = repo.getPlaylistSources().filter { it.enabled }
        channels = M3uParser.merge(sources.map { M3uParser.parse(it.raw, it.url) })
        channels.forEach { ch ->
            oldOverrides[ch.normalizedName()]?.let { ov ->
                if (ov.isNotBlank()) ch.logoOverride = ov
            }
        }
        repo.saveChannels(channels)

        buildGroups()
        // 当前分组可能因为删除直播源而消失，回落到「全部」
        if (currentGroup != "ALL" && channels.none { it.group == currentGroup }) currentGroup = "ALL"
        applyFilter(currentGroup)
    }

    /** 根据频道分组生成标签栏 */
    private fun buildGroups() {
        groupContainer.removeAllViews()
        val groups = mutableListOf("ALL")
        channels.forEach { ch ->
            val g = ch.group.ifBlank { "默认" }
            if (!groups.contains(g)) groups.add(g)
        }
        groups.forEach { g ->
            val label = if (g == "ALL") getString(R.string.group_all) else g
            val btn = Button(this).apply {
                text = label
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    resources.getDimensionPixelSize(R.dimen.chip_height)
                ).apply { setMargins(0, 0, 12, 0) }
                setOnClickListener { applyFilter(g) }
            }
            btn.setBackgroundResource(R.drawable.bg_chip)
            btn.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            if (g == currentGroup) updateChipSelected(btn, true)
            btn.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) btn.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                else if (g != currentGroup) btn.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
            groupContainer.addView(btn)
        }
        groupKeys = groups
    }

    /** 应用分组筛选 */
    private fun applyFilter(group: String) {
        currentGroup = group
        filteredChannels = if (group == "ALL") channels else channels.filter { it.group == group }
        // 同步标签选中态
        for (i in 0 until groupContainer.childCount) {
            val child = groupContainer.getChildAt(i) as Button
            val g = if (i == 0) "ALL" else child.text.toString()
            val isSelected = g == group
            updateChipSelected(child, isSelected)
            child.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (isSelected) R.color.text_primary else R.color.text_secondary
                )
            )
        }
        adapter.submitList(filteredChannels)
        tvChannelCount.text = "${filteredChannels.size} ${getString(R.string.channel_count)}"
        tvEmpty.visibility = if (filteredChannels.isEmpty()) View.VISIBLE else View.GONE
        // 左右滑动切分组后，把选中的分组标签滚到可见区域
        groupScroller.post { ensureGroupChipVisible() }

        // Hero 默认展示第一个频道
        focusedChannel = filteredChannels.firstOrNull()
        focusedChannel?.let { updateHero(it) }
    }

    private fun updateChipSelected(btn: Button, selected: Boolean) {
        btn.isSelected = selected
    }

    /** 左右滑动频道列表时切换分组 */
    private fun switchGroupBy(delta: Int) {
        if (groupKeys.size < 2) return
        val current = groupKeys.indexOf(currentGroup).let { if (it < 0) 0 else it }
        val target = (current + delta).coerceIn(0, groupKeys.size - 1)
        if (target == current) return
        applyFilter(groupKeys[target])
        recyclerView.scrollToPosition(0)
        toast(if (groupKeys[target] == "ALL") getString(R.string.group_all) else groupKeys[target])
    }

    /** 让当前分组标签在横向标签栏中保持可见 */
    private fun ensureGroupChipVisible() {
        val idx = groupKeys.indexOf(currentGroup)
        if (idx < 0 || idx >= groupContainer.childCount) return
        val chip = groupContainer.getChildAt(idx)
        val target = chip.left - groupScroller.width / 2 + chip.width / 2
        groupScroller.smoothScrollTo(target.coerceAtLeast(0), 0)
    }

    /** 焦点频道变化时更新顶部推荐卡（TV 由方向键驱动，触屏由滚动/滑动驱动） */
    private fun updateHero(channel: Channel) {
        focusedChannel = channel
        tvHeroTitle.text = channel.name
        tvHeroSub.text = "${channel.group}  ·  ${channel.sources.size} ${getString(R.string.source_count)}"
        loadLogoInto(ivHeroLogo, channel)

        // 先复位，防抖后异步加载节目单
        tvHeroEpg.text = getString(R.string.hero_playing)
        tvHeroEpgNext.visibility = View.GONE
        heroEpgRunnable?.let { uiHandler.removeCallbacks(it) }
        val runnable = Runnable { loadHeroEpg(channel) }
        heroEpgRunnable = runnable
        uiHandler.postDelayed(runnable, 250L)
    }

    /** 焦点卡 EPG：序号校验，快速滑动时只保留最后一次的结果 */
    private fun loadHeroEpg(channel: Channel) {
        val seq = ++heroEpgSeq
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                epgManager.getNowNext(channel.name, channel.tvgId)
            }
            if (seq != heroEpgSeq || focusedChannel !== channel) return@launch

            val fmt = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            }
            val now = result?.now
            val next = result?.next
            if (now == null && next == null) {
                tvHeroEpg.text = getString(R.string.hero_playing)
                tvHeroEpgNext.text = getString(R.string.hero_epg_empty)
                tvHeroEpgNext.visibility = View.VISIBLE
                return@launch
            }

            if (now != null) {
                tvHeroEpg.text = "正在播放 · ${now.title} · ${fmt.format(java.util.Date(now.start))}–${fmt.format(java.util.Date(now.end))}"
                if (next != null) {
                    tvHeroEpgNext.text = "接下来 ${fmt.format(java.util.Date(next.start))} · ${next.title}"
                    tvHeroEpgNext.visibility = View.VISIBLE
                } else {
                    tvHeroEpgNext.visibility = View.GONE
                }
            } else if (next != null) {
                tvHeroEpg.text = "即将播放 · ${next.title} · ${fmt.format(java.util.Date(next.start))}"
                tvHeroEpgNext.visibility = View.GONE
            }
        }
    }

    /** 触屏：列表滚动后，焦点卡跟随第一个可见频道 */
    private fun updateHeroFromFirstVisible() {
        val lm = recyclerView.layoutManager as? GridLayoutManager ?: return
        val pos = lm.findFirstVisibleItemPosition()
        if (pos == RecyclerView.NO_POSITION || pos >= filteredChannels.size) return
        val channel = filteredChannels[pos]
        if (channel !== focusedChannel) updateHero(channel)
    }

    /** 触屏：左右滑动焦点卡，切换到上/下一个频道并同步滚动列表 */
    private fun stepHero(delta: Int) {
        if (filteredChannels.isEmpty()) return
        val current = filteredChannels.indexOfFirst { it === focusedChannel }.let { if (it < 0) 0 else it }
        val target = (current + delta).coerceIn(0, filteredChannels.size - 1)
        if (target == current) return
        recyclerView.smoothScrollToPosition(target)
        updateHero(filteredChannels[target])
    }

    /** 直播源管理入口：可同时添加多份源，勾选启用、改名或删除 */
    private fun showSourceManager() {
        val dialog = SourceManagerDialog(
            this,
            repo,
            onAdd = { name, content, isUrl -> addPlaylistSource(name, content, isUrl) },
            onChanged = { rebuildChannelsFromSources() },
            onEpgChanged = { epgManager.clearCache() }
        )
        dialog.setOnDismissListener { sourceDialog = null }
        sourceDialog = dialog
        dialog.show()
    }

    /** 关于 / 版本信息 / 检查更新 / 开发者二维码 */
    private fun showAboutDialog() {
        AboutDialog(this, lifecycleScope) { info -> showUpdateDialog(info) }.show()
    }

    /**
     * 新增一份直播源：URL 先下载正文再落库（离线也能重建列表），
     * 文本源直接保存。添加完成后刷新管理弹窗与频道网格。
     */
    private fun addPlaylistSource(name: String, text: String, isUrl: Boolean) {
        toast(getString(R.string.loading))
        lifecycleScope.launch {
            val raw = if (isUrl) {
                val result = withContext(Dispatchers.IO) { PlaylistLoader.load(text, true) }
                result.getOrElse { e ->
                    toast("添加失败：${e.message}")
                    return@launch
                }
            } else {
                text
            }
            val parsed = M3uParser.parse(raw, if (isUrl) text else "")
            if (parsed.isEmpty()) {
                toast("未解析到频道，请检查直播源格式")
                return@launch
            }
            val source = repo.addPlaylistSource(name, if (isUrl) text else "", raw)
            currentGroup = "ALL"
            rebuildChannelsFromSources()
            sourceDialog?.refresh()
            toast("已添加「${source.name}」，当前共 ${channels.size} 个频道")
        }
    }

    /**
     * 直播页换台：相对「当前正在播放的频道」切到上/下一个。
     * 只做交互层的事（更新焦点卡、滚动列表），最终仍复用 openChannel，
     * 播放引擎（播放器实例、缓冲/锁台/看门狗）完全不变，因此不影响播放流畅度。
     * [delta] = -1 上一个，+1 下一个；到头/到尾不循环，给一次提示。
     */
    private fun switchLiveChannel(delta: Int) {
        if (filteredChannels.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastLiveSwitchMs < 350L) return  // 防抖：长按/连续滑动不至于瞬间跳过多个台
        val base = currentChannel ?: focusedChannel
        val cur = filteredChannels.indexOfFirst { it === base }
            .let { if (it < 0) 0 else it }
        val target = (cur + delta).coerceIn(0, filteredChannels.size - 1)
        if (target == cur) {
            toast(if (delta > 0) "已是最后一个频道" else "已是第一个频道")
            lastLiveSwitchMs = now
            return
        }
        lastLiveSwitchMs = now
        val ch = filteredChannels[target]
        updateHero(ch)                       // 同步焦点卡，退出播放后列表停在对应频道
        recyclerView.scrollToPosition(target)
        openChannel(ch)                      // 复用既有起播流程，不碰播放引擎
    }

    /** 打开频道开始播放 */
    private fun openChannel(channel: Channel) {
        currentChannel = channel
        repo.setLastChannelName(channel.name)
        tvPlayerTitle.text = channel.name
        setStatus("正在连接…", R.color.yellow)
        showPlayerPage()

        if (playerManager == null) initPlayer()

        // 播放启动本身也加保护：老盒子 ExoPlayer 在 setMediaItem/prepare 阶段
        // 偶发抛异常（解码器能力、MediaItem 状态等），不能让它冒到主线程导致闪退
        try {
            stallDetector?.onSourceSwitched()
            playerManager?.playChannel(channel)
        } catch (t: Throwable) {
            setStatus("播放启动失败：${t.message ?: t.javaClass.simpleName}", R.color.red)
            toast("播放启动失败，请重试或换一个频道")
            return
        }

        // 20 秒没画面才自动换线路（成功出画面后看门狗立即失效）
        uiHandler.removeCallbacks(startWatchdog)
        uiHandler.postDelayed(startWatchdog, 20_000L)

        // 加载节目单（后台，失败静默）
        loadEpg(channel)
    }

    /** 仅手机进入横屏；平板/TV 保持原方向 */
    private fun isPhone(): Boolean = !resources.getBoolean(R.bool.is_tablet)

    /** TV/盒子：由系统 uiMode 判定，焦点只跟随遥控器方向键 */
    private fun isTv(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION

    private fun loadLogoInto(image: ImageView, channel: Channel) {
        // 先放本地绘制台标：离线也立刻有图；有网络台标再覆盖
        val local = LogoHelper.localLogo(this, channel, 256)
        image.setImageBitmap(local)
        val url = LogoHelper.resolve(channel) ?: return
        image.load(url) {
            crossfade(true)
            listener(onError = { _, _ -> image.setImageBitmap(local) })
        }
    }

    /** 加载当前频道的节目单 */
    private fun loadEpg(channel: Channel) {
        epgPanel.visibility = View.GONE
        uiHandler.removeCallbacks(hideEpgRunnable)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                epgManager.getNowNext(channel.name, channel.tvgId)
            }
            if (currentChannel != channel || result == null) return@launch
            val now = result.now
            val next = result.next
            if (now == null && next == null) return@launch

            val fmt = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            }
            if (now != null) {
                val pct = ((System.currentTimeMillis() - now.start).toFloat() / (now.end - now.start) * 100)
                    .coerceIn(0f, 100f).toInt()
                tvEpgTime.text = "正在播放 · ${fmt.format(java.util.Date(now.start))}–${fmt.format(java.util.Date(now.end))} · 已过 $pct%"
                tvEpgNow.text = now.title
            } else {
                tvEpgTime.text = "节目单"
                tvEpgNow.text = "当前无节目信息"
            }
            tvEpgNext.text = next?.let {
                "接下来 ${fmt.format(java.util.Date(it.start))} · ${it.title}"
            } ?: ""
            epgPanel.visibility = View.VISIBLE
            uiHandler.postDelayed(hideEpgRunnable, 8000L)
        }
    }

    private fun initPlayer() {
        // 应用用户可调的缓冲参数。
        // 关键修正：Media3 的 DefaultLoadControl 要求 bufferForPlaybackMs 与
        // bufferForPlaybackAfterRebufferMs 都不能超过 minBufferMs，否则构造时直接抛
        // IllegalArgumentException —— 表现为「选中频道按确定瞬间闪退」。
        // 旧代码把上限误写为 maxS*1000，用户一旦调小「最小缓冲」或调大
        // 「起播缓冲/卡顿后缓冲」就会触发，这是本次电视端闪退的主因。
        val minS = playSettings.bufferMinS.coerceIn(2, 60)
        val maxS = playSettings.bufferMaxS.coerceIn(minS + 1, 120)
        val minBufferMs = minS * 1000
        val playStart = playSettings.playStartMs.coerceIn(200, minBufferMs)
        val rebuffer = playSettings.rebufferMs.coerceIn(200, minBufferMs)
        val cooldownMs = playSettings.stallCooldownS.coerceIn(2, 60) * 1000L
        val pm = try {
            PlayerManager(
                this,
                bufferMinMs = minBufferMs,
                bufferMaxMs = maxS * 1000,
                bufferForPlaybackMs = playStart,
                bufferForPlaybackAfterRebufferMs = rebuffer,
                switchCooldownMs = cooldownMs
            )
        } catch (t: Throwable) {
            // 兜底：即便老盒子因奇怪参数/解码能力抛错，也绝不能闪退
            try {
                PlayerManager(
                    this,
                    bufferMinMs = 15_000,
                    bufferMaxMs = 40_000,
                    bufferForPlaybackMs = 1_500,
                    bufferForPlaybackAfterRebufferMs = 1_500,
                    switchCooldownMs = cooldownMs
                )
            } catch (t2: Throwable) {
                toast("播放器初始化失败：${t2.message ?: t2.javaClass.simpleName}")
                return
            }
        }
        pm.maxRetry = playSettings.maxRetry
        lateinit var sd: StallDetector
        sd = StallDetector(
            onStall = stall@{
                // 用户可关闭「卡顿自动换线路」，关掉后只在状态条提示，不打断观看
                if (!playSettings.autoSwitchOnStall) return@stall
                val switched = pm.switchToNextSource()
                if (switched) {
                    sd.onSourceSwitched()
                    runOnUiThread { toast("持续卡顿，已切换到下一条线路") }
                }
            },
            onStats = { buffers, dropped, bw ->
                // 丢帧回调非常频繁，未开启状态条时不要往主线程丢任务，避免播放时掉帧
                if (playSettings.showStats) {
                    runOnUiThread {
                        tvStats.text = "缓冲：$buffers · 丢帧：$dropped · $bw Kbps"
                    }
                }
            },
            windowMs = playSettings.stallWindowS.coerceIn(5, 60) * 1000L,
            stallCountThreshold = playSettings.stallCount.coerceIn(2, 10),
            cooldownMs = playSettings.stallCooldownS.coerceIn(3, 60) * 1000L
        )
        pm.player.addAnalyticsListener(sd)
        pm.onSourceChanged = { _, idx, total ->
            runOnUiThread {
                currentLineLabel = if (total > 1) "线路 ${idx + 1}/$total" else "直播"
                setStatus("$currentLineLabel · 连接中…", R.color.yellow)
            }
        }
        pm.onReconnecting = { attempt ->
            runOnUiThread {
                setStatus("$currentLineLabel · 信号恢复中（第 $attempt 次，不换台）", R.color.yellow)
            }
        }
        // 画面彻底卡死且恢复无效时，看门狗会强制换到其他线路并在这里提示
        pm.onFreezeSwitched = { idx, total ->
            runOnUiThread {
                currentLineLabel = if (total > 1) "线路 ${idx + 1}/$total" else "直播"
                toast("画面卡住，已自动切换线路")
            }
        }
        // 所有线路都卡死：停止来回换线，冷却 2 分钟后再允许
        pm.onAllSourcesStuck = {
            runOnUiThread {
                setStatus("所有线路都卡住了，已暂停自动换线", R.color.red)
                toast("所有线路都卡住了，2 分钟后再自动尝试其他线路")
            }
        }
        pm.onError = { msg, code ->
            runOnUiThread { setStatus(friendlyError(msg, code), R.color.red) }
        }
        pm.onGiveUp = { msg ->
            runOnUiThread {
                toast(msg)
                setStatus(msg, R.color.red)
            }
        }

        // 缓冲/播放状态：转圈提示 + 状态栏如实反映当前是否真的在播
        // （旧版只在切源时改文字，播放成功后一直是「连接中…」，非常容易误判）
        pm.player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                pbLoading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                when (state) {
                    Player.STATE_READY -> {
                        uiHandler.removeCallbacks(startWatchdog)
                        setStatus("$currentLineLabel · 播放中", R.color.green)
                    }
                    Player.STATE_BUFFERING -> {
                        setStatus("$currentLineLabel · 缓冲中…", R.color.yellow)
                    }
                }
            }
        })

        pm.attachView(findViewById(R.id.playerView))
        playerManager = pm
        stallDetector = sd
        statsBar.visibility = View.VISIBLE
    }

    /** TV/盒子播放时隐藏状态栏与导航栏，获得真正的全屏画面 */
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    /** 回到列表时恢复系统栏；TV/盒子仍保持横屏 */
    private fun showSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    /** 横屏最大化切换（仅手机；TV/盒子进入播放即横屏全屏） */
    private fun toggleFullscreen() {
        if (!isPhone()) return
        isLandscapePlayback = !isLandscapePlayback
        requestedOrientation = if (isLandscapePlayback) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        btnFullscreen.text = getString(if (isLandscapePlayback) R.string.exit_fullscreen else R.string.fullscreen)
    }

    /** 把播放器的英文报错翻译成用户看得懂的原因，并带上错误码便于截图定位 */
    private fun friendlyError(raw: String, code: Int): String {
        val m = raw.lowercase()
        val reason = when {
            // 2004 分片缺失已在 PlayerManager 内静默处理，不会传到界面
            m.contains("file not found") -> "线路分片缺失(404)，正在尝试其它线路…"
            m.contains("403") || m.contains("forbidden") -> "线路拒绝访问(403)，可能已失效"
            m.contains("404") || m.contains("not found") -> "线路地址不存在(404)"
            m.contains("ssl") || m.contains("certificate") || m.contains("cert") ->
                "HTTPS 证书校验失败，可在设置里开启「忽略证书校验」"
            m.contains("timeout") || m.contains("timed out") -> "连接超时，正在原线路重连…"
            m.contains("unable to connect") || m.contains("connect") -> "连不上服务器，正在原线路重连…"
            m.contains("unrecognized input") || m.contains("parser") -> "线路格式不支持（可能不是标准直播流）"
            m.contains("source error") -> "线路读取失败，正在原线路重连…"
            else -> "播放中断，正在原线路重连…"
        }
        return if (code > 0) "$reason · 错误码 $code" else reason
    }

    private fun setStatus(text: String, colorRes: Int) {
        tvStats.text = text
        dotStatus.background = ContextCompat.getDrawable(this, R.drawable.bg_dot_green)?.mutate()?.apply {
            setTint(ContextCompat.getColor(this@MainActivity, colorRes))
        }
        // 状态提示只在切换/出错时短暂出现，不长期占用画面
        statsBar.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideStatsRunnable)
        uiHandler.postDelayed(hideStatsRunnable, 5000)
    }

    private fun showPlayerPage() {
        pageList.visibility = View.GONE
        pagePlayer.visibility = View.VISIBLE
        // 播放期间屏幕常亮：手机看直播时不做任何操作也会被系统锁屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!isPhone()) {
            // TV / 直播盒子：播放始终是横屏，不跟随系统的竖屏方向
            isLandscapePlayback = true
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        hideSystemUi()
    }

    private fun closePlayer() {
        uiHandler.removeCallbacks(startWatchdog)
        pbLoading.visibility = View.GONE
        playerManager?.player?.stop()
        // 退出播放后恢复正常息屏策略，避免列表页也一直亮着
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pagePlayer.visibility = View.GONE
        pageList.visibility = View.VISIBLE
        epgPanel.visibility = View.GONE
        uiHandler.removeCallbacks(hideEpgRunnable)
        // 手机退出播放恢复竖屏；TV/盒子保持横屏并恢复系统栏
        if (isPhone()) {
            isLandscapePlayback = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            btnFullscreen.text = getString(R.string.fullscreen)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        showSystemUi()
        applyFilter(currentGroup)
    }

    /** 长按频道：手动修改台标链接 */
    private fun showLogoEditor(channel: Channel) {
        val input = EditText(this).apply {
            setText(channel.logoOverride)
            hint = "输入台标图片 URL（留空则恢复自动匹配）"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("设置「${channel.name}」台标")
            .setMessage("可粘贴任意台标图片链接。留空并确定则恢复默认自动匹配。")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                channel.logoOverride = input.text.toString().trim()
                repo.saveChannels(channels)
                adapter.invalidateLogo(channel.normalizedName())
                applyFilter(currentGroup)
                toast("台标已更新")
            }
            .setNegativeButton("恢复默认") { _, _ ->
                channel.logoOverride = ""
                repo.saveChannels(channels)
                adapter.invalidateLogo(channel.normalizedName())
                applyFilter(currentGroup)
                toast("已恢复自动匹配")
            }
            .show()
    }

    /** 播放参数设置弹窗 */
    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etWindow = view.findViewById<EditText>(R.id.etStallWindow)
        val etCount = view.findViewById<EditText>(R.id.etStallCount)
        val etCooldown = view.findViewById<EditText>(R.id.etCooldown)
        val etBufMin = view.findViewById<EditText>(R.id.etBufferMin)
        val etBufMax = view.findViewById<EditText>(R.id.etBufferMax)
        val etPlayStart = view.findViewById<EditText>(R.id.etPlayStart)
        val etRebuffer = view.findViewById<EditText>(R.id.etRebuffer)
        val etRetry = view.findViewById<EditText>(R.id.etMaxRetry)
        val cbStats = view.findViewById<android.widget.CheckBox>(R.id.cbShowStats)
        val cbInsecure = view.findViewById<android.widget.CheckBox>(R.id.cbInsecureTls)
        val cbAutoSwitch = view.findViewById<android.widget.CheckBox>(R.id.cbAutoSwitch)

        etWindow.setText(playSettings.stallWindowS.toString())
        etCount.setText(playSettings.stallCount.toString())
        etCooldown.setText(playSettings.stallCooldownS.toString())
        etBufMin.setText(playSettings.bufferMinS.toString())
        etBufMax.setText(playSettings.bufferMaxS.toString())
        etPlayStart.setText(playSettings.playStartMs.toString())
        etRebuffer.setText(playSettings.rebufferMs.toString())
        etRetry.setText(playSettings.maxRetry.toString())
        cbStats.isChecked = playSettings.showStats
        cbInsecure.isChecked = playSettings.insecureTls
        cbAutoSwitch.isChecked = playSettings.autoSwitchOnStall

        val dialog = AlertDialog.Builder(this)
            .setTitle("播放参数设置")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                playSettings.stallWindowS = etWindow.intOr(PlaybackSettings.DEF_STALL_WINDOW)
                playSettings.stallCount = etCount.intOr(PlaybackSettings.DEF_STALL_COUNT)
                playSettings.stallCooldownS = etCooldown.intOr(PlaybackSettings.DEF_STALL_COOLDOWN)
                playSettings.bufferMinS = etBufMin.intOr(PlaybackSettings.DEF_BUFFER_MIN)
                playSettings.bufferMaxS = etBufMax.intOr(PlaybackSettings.DEF_BUFFER_MAX)
                playSettings.playStartMs = etPlayStart.intOr(PlaybackSettings.DEF_PLAY_START)
                playSettings.rebufferMs = etRebuffer.intOr(PlaybackSettings.DEF_REBUFFER)
                playSettings.maxRetry = etRetry.intOr(PlaybackSettings.DEF_MAX_RETRY)
                playSettings.showStats = cbStats.isChecked
                playSettings.autoSwitchOnStall = cbAutoSwitch.isChecked
                val tlsChanged = cbInsecure.isChecked != playSettings.insecureTls
                playSettings.insecureTls = cbInsecure.isChecked
                if (tlsChanged) {
                    // 播放器/网络客户端是按旧策略创建的，释放后下次进入频道用新策略重建
                    HttpCompat.allowInsecureTls = cbInsecure.isChecked
                    playerManager?.release()
                    playerManager = null
                    stallDetector = null
                    toast(if (cbInsecure.isChecked) "已开启忽略证书校验，重新进入频道生效" else "已恢复证书校验，重新进入频道生效")
                }
                statsBar.visibility = if (playSettings.showStats) View.VISIBLE else View.GONE
                // 下次起播生效；若正在播放则提示重新进入
                if (playerManager != null) toast("参数已保存，重新进入频道后生效") else toast("参数已保存")
            }
            .setNeutralButton("恢复默认") { _, _ ->
                playSettings.reset()
                toast("已恢复默认参数")
            }
            .setNegativeButton("取消", null)
            .create()

        // 强制深色弹窗背景 + 浅色标题：系统浅色模式下也不会出现「白底白字」
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_dark)
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
                ?.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }
        dialog.show()
    }

    private fun EditText.intOr(default: Int): Int = text.toString().trim().toIntOrNull() ?: default

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** TV 遥控器返回键：播放中先回列表，不直接退出应用 */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pagePlayer.visibility == View.VISIBLE) {
            closePlayer()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacks(hideStatsRunnable)
        uiHandler.removeCallbacks(startWatchdog)
        heroEpgRunnable?.let { uiHandler.removeCallbacks(it) }
        playerManager?.release()
        playerManager = null
    }
}

package com.iptv.player.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.iptv.player.R
import com.iptv.player.config.PlaylistLoader
import com.iptv.player.config.HttpCompat
import com.iptv.player.config.PlaybackSettings
import com.iptv.player.data.Channel
import com.iptv.player.data.M3uParser
import com.iptv.player.data.Source
import com.iptv.player.data.SourceRepository
import com.iptv.player.epg.EpgManager
import com.iptv.player.player.BlackBarDetector
import com.iptv.player.player.VlcCore
import com.iptv.player.player.VlcFallbackPlayer
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

    // ===== 订阅源定时刷新 =====
    /** 循环定时任务本身；间隔档位变化时先撤销再重新排期 */
    private var sourceRefreshRunnable: Runnable? = null
    /** 本次开启应用以来成功完成的刷新轮数（仅内存，进程退出即归零，不落盘） */
    private var sourceRefreshCount = 0
    /** 刷新任务序号：档位变化/退出时递增，让进行中的旧任务结果作废 */
    private var sourceRefreshSeq = 0
    /** 上一轮刷新的结果描述，显示在设置页，便于判断是否真的更新成功 */
    private var sourceRefreshLastResult = "尚未刷新"
    /** 直播页上/下滑或遥控上/下键换台的防抖时间戳 */
    private var lastLiveSwitchMs = 0L
    /** 直播页长按确认键呼出的频道选择浮层 */
    private var pickerDialog: ChannelPickerDialog? = null
    /** 长按确认键的定时器：到点即弹频道表 */
    private val pickerLongPressRunnable = Runnable {
        pickerLongPressFired = true
        showChannelPicker()
    }
    /** 本次长按是否已经弹出过频道表（用于吃掉随后的抬起事件，避免重复触发点击） */
    private var pickerLongPressFired = false
    /** 更新下载任务：进度框点「取消」时中断 */
    private var updateJob: kotlinx.coroutines.Job? = null

    /** 扫码添加直播源：等待相机返回的临时回调（同一时刻只允许一次） */
    private var pendingScanCallback: ((String) -> Unit)? = null
    /**
     * 相机扫码。源管理对话框是 Dialog，拿不到 registerForActivityResult 所需的
     * 生命周期宿主链接，所以统一由 Activity 发起，结果再回传给发起方。
     */
    private val scanQrLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = pendingScanCallback
            pendingScanCallback = null
            // 用户按返回/点取消退出扫码时不提示：那是主动取消，不是错误
            if (result.resultCode == Activity.RESULT_OK) {
                val content = result.data?.getStringExtra(QrScanActivity.EXTRA_RESULT)
                if (!content.isNullOrBlank()) cb?.invoke(content)
            }
        }
    /** 直播页上下划动换台手势（在 dispatchTouchEvent 中统一分发，确保收到完整序列） */
    private lateinit var liveSwipe: GestureDetector
    /**
     * 顶部信息条（返回 / 换源 / 竖屏 + 状态字）自动淡出的定时器。
     * 直播时这些控件一直压在画面上，既挡内容又影响观感；几秒没操作就收起来，
     * 按键或触摸一下屏幕再唤出（见 showOverlay / hideOverlay）。
     */
    private val overlayHideRunnable = Runnable { hideOverlay() }
    /** 顶部信息条当前是否处于「显示」状态 */
    private var overlayVisible = true
    /** 节目单浮层只短暂显示，避免长时间盖住画面 */
    private val hideEpgRunnable = Runnable { epgPanel.visibility = View.GONE }

    // 视图
    private lateinit var pageList: View
    // 必须是 FrameLayout：换内核时要往里插一张「旧画面」覆盖层（addView 只有 ViewGroup 有）
    private lateinit var pagePlayer: FrameLayout
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
    /** 顶部信息条整体（含半透明渐变底）与其中的按钮行 */
    private lateinit var topPanel: View
    private lateinit var btnRow: View
    private lateinit var pbLoading: ProgressBar
    // 节目单
    private lateinit var epgPanel: View
    private lateinit var tvEpgTime: TextView
    private lateinit var tvEpgNow: TextView
    private lateinit var tvEpgNext: TextView
    private lateinit var btnFullscreen: Button
    /** 手动换源按钮：弹出当前频道全部线路由用户指定 */
    private lateinit var btnSwitchLine: Button
    /** 播放视图容器：SurfaceView / TextureView 二选一 */
    private lateinit var videoHost: FrameLayout
    /** 当前 PlayerView（切换渲染方式时整体重建） */
    private var playerViewRef: PlayerView? = null
    /** MP2 等设备缺失音频解码器时临时接管的 VLC 兜底播放器（视频硬解 + 音频软件解码） */
    private var fallbackPlayer: VlcFallbackPlayer? = null
    /** 兜底内核启动超时计时器 */
    private var fallbackTimeoutRunnable: Runnable? = null

    /**
     * 换内核期间盖在画面上的「旧画面静止图」。
     *
     * 为什么需要它：Exo 一停，它的 surface 立刻变黑；VLC 又是新建一个 SurfaceView，
     * 在出首帧之前这块窗口同样是黑的。两段黑叠起来就是用户说的「切软解时黑屏一下」。
     * 这里在停 Exo **之前**把当前这一帧拷贝下来贴在画面上，等 VLC 真的出画再淡出，
     * 于是黑屏窗口被一张静止画面盖住，观感变成「画面定格一下 → 平滑过渡」。
     */
    private var kernelSwitchFrame: ImageView? = null
    /** 上面那张静止图对应的位图，移除时要回收（1080p 一整屏约 8MB） */
    private var kernelSwitchBitmap: android.graphics.Bitmap? = null
    /** 抓帧令牌：抓帧是异步的，期间换台/再切一次内核都要让这次结果作废 */
    private var kernelSwitchToken = 0
    /** 是否已经排了「延迟淡出」：避免重建管线时重复挂一次动画 */
    private var kernelSwitchHidePending = false
    /**
     * 那条延迟淡出任务的引用。必须留着才能在切台/重切内核时撤销它 ——
     * 否则它到点会执行，而此时屏幕上可能已经换成了新一轮的静止图，会被它顺手淡掉。
     */
    private var kernelSwitchHideRunnable: Runnable? = null

    /**
     * 自动去黑边当前生效的方式（运行时状态，不落盘；换台/换源都重新判断）。
     *
     * 1.60 起不再用「放大到 xx%」这种中间量：一是百分比在 SurfaceView 上不可靠，
     * 二是手机和电视的屏幕形状差太多，同一个百分比在两边观感完全相反。
     * 现在只保留三种结果：不动 / 裁切铺满 / 拉伸铺满，选哪个由 decideCrop() 按设备算。
     */
    private var cropMode = CropMode.NONE
    /** 本轮采样次数（限制重试，避免暗场频道反复采样耗电） */
    private var autoCropTries = 0
    /** 上一次采样算出的比例差，用于「连续两次一致才动手」的确认 */
    private var autoCropCandidatePct = 0
    private var autoCropStableHits = 0
    /** 已把采样任务排进消息队列（Handler.hasCallbacks 要 API 29，这里自己记） */
    private var autoCropScheduled = false
    /** 本轮已经判定完成（有黑边已放大 / 确认无黑边），避免播放器反复 READY 时重复采样 */
    private var autoCropDone = false
    private val autoCropRunnable = Runnable { detectBlackBars() }

    /**
     * 当前视频的显示尺寸（已按像素宽高比折算），来自轨道信息。
     *
     * 为什么需要它：4:3 老频道在 16:9 屏上左右留黑，这是**比例**问题，不是像素问题。
     * Exo 的做法是把 SurfaceView 本身按 4:3 缩小居中，所以对 SurfaceView 做像素采样
     * 只能拿到纯视频内容、根本看不到黑边 —— 这就是「去黑边好像没作用」的根因。
     * 拿到 (宽, 高) 就能直接算出要放大多少，既准又即时。
     */
    private var lastVideoW = 0
    private var lastVideoH = 0

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
        topPanel = findViewById(R.id.topPanel)
        btnRow = findViewById(R.id.btnRow)
        pbLoading = findViewById(R.id.pbLoading)
        epgPanel = findViewById(R.id.epgPanel)
        tvEpgTime = findViewById(R.id.tvEpgTime)
        tvEpgNow = findViewById(R.id.tvEpgNow)
        tvEpgNext = findViewById(R.id.tvEpgNext)
        btnFullscreen = findViewById(R.id.btnFullscreen)
        btnSwitchLine = findViewById(R.id.btnSwitchLine)
        videoHost = findViewById(R.id.videoHost)

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
        btnSwitchLine.setOnClickListener { switchSourceManually() }

        // 软解内核预热：libvlc 首次加载要 1–3 秒，放在主线程就是切台时整机卡死。
        // 这里等首页稳定后在后台线程先把它建好，真正需要兜底时直接复用，几乎零等待。
        if (playSettings.audioCompat) {
            uiHandler.postDelayed({ VlcCore.warmUp(this) }, 2_500L)
        }
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

        // 保证「频道网格至少露出三行」：大屏尺寸已在 dimens 里压缩过，
        // 这里再按真实可视高度兜一次底（不同电视窗口高度、系统字体缩放都会吃掉空间）
        ensureThreeRowsVisible()

        // 启动时静默检查更新（无新版本或网络不通时无任何打扰）
        checkForUpdate()

        // 订阅源定时刷新：按设置档位排期，默认关闭时什么都不做
        scheduleSourceRefresh()
    }

    /** 焦点卡原始高度：被压缩过之后要能还原（例如旋转屏幕、字体缩放变化后再量一次） */
    private var heroBaseHeight = 0

    /**
     * 三行兜底：量一次频道网格的可视高度，装不下三行就把焦点卡按差额压矮
     * （最多压到一张频道卡的高度）。只压不涨，所以不会把布局越改越奇怪。
     */
    private fun ensureThreeRowsVisible() {
        val lp = heroCard.layoutParams ?: return
        if (heroBaseHeight == 0) heroBaseHeight = lp.height
        if (lp.height != heroBaseHeight) {
            lp.height = heroBaseHeight
            heroCard.layoutParams = lp
        }
        recyclerView.post {
            if (pageList.visibility != View.VISIBLE) return@post
            val cardH = resources.getDimensionPixelSize(R.dimen.card_height)
            val margin = resources.getDimensionPixelSize(R.dimen.card_margin)
            val rowH = cardH + margin * 2
            val need = rowH * 3 - recyclerView.height
            if (need <= 0) return@post
            val cur = heroCard.layoutParams.height
            val newH = (cur - need).coerceAtLeast(cardH)
            if (newH < cur) {
                heroCard.layoutParams = heroCard.layoutParams.apply { height = newH }
            }
        }
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
        // 顶部信息条收起后，按任意键先把它唤出来（并重新开始计时），再照常处理这次按键。
        // 这样「按一下 → 看见返回/换源」的直觉成立，也不用专门加一个「显示菜单」键。
        if (event.action == KeyEvent.ACTION_DOWN) showOverlay()
        // 长按确认键呼出频道表：按下开始计时，抬起时若已弹出则把这次抬起吃掉，
        // 避免同一次长按又「点」到顶部栏按钮或播放器控制条。
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        ) {
            // 软解内核（VLC）接管时没有 PlayerView 控制条，所以短按确认键被借去当
            // 「暂停/继续」。但顶部栏的返回/换源/全屏也是按钮，焦点停在按钮上时
            // 这一下必须是「点这个按钮」——否则会出现「点换源反而变成已暂停」。
            // 只要焦点在按钮上，就整段交给系统焦点导航，不做任何拦截。
            if (fallbackPlayer != null && isFocusOnTopBarButton()) {
                return super.dispatchKeyEvent(event)
            }
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                    pickerLongPressFired = false
                    uiHandler.postDelayed(pickerLongPressRunnable, PICKER_LONG_PRESS_MS)
                }
                KeyEvent.ACTION_UP -> {
                    uiHandler.removeCallbacks(pickerLongPressRunnable)
                    if (pickerLongPressFired) {
                        pickerLongPressFired = false
                        return true
                    }
                    // 兜底内核没有 PlayerView 控制条：短按确认键直接暂停/继续
                    if (fallbackPlayer != null) {
                        toggleFallbackPlayback()
                        return true
                    }
                }
            }
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

    /** 直播页呼出频道选择表（再长按一次收起）：切换频道不退出播放页，画面直接接上 */
    private fun showChannelPicker() {
        pickerDialog?.let { if (it.isShowing) { it.dismiss(); return } }
        val list = filteredChannels.ifEmpty { channels }
        if (list.isEmpty()) return
        val groupLabel = if (currentGroup == "ALL") {
            getString(R.string.group_all) + " · " + list.size + " " + getString(R.string.channel_count)
        } else {
            currentGroup + " · " + list.size + " " + getString(R.string.channel_count)
        }
        pickerDialog = ChannelPickerDialog(
            this, list, currentChannel, groupLabel
        ) { picked ->
            if (picked !== currentChannel) openChannel(picked)
        }.also { it.show() }
    }

    /**
     * 直播页上下划动换台：在事件分发入口把触摸喂给手势识别器。
     * 只在播放页可见、且是触屏设备时处理；始终不消费事件（返回 super），
     * 因此 PlayerView 的单击显示控制条、控制条按钮点击都不受影响。
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::liveSwipe.isInitialized && pagePlayer.visibility == View.VISIBLE) {
            liveSwipe.onTouchEvent(event)
            // 触摸一下就把顶部信息条唤回来（音量/状态/返回按钮都在里面）
            if (event.actionMasked == MotionEvent.ACTION_DOWN) showOverlay()
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
                    watchInstallLaunch(info, apk)
                }
                // 权限页已打开，同时给出手动方案（安装包已经下载好，不必重下）
                else -> showManualUpdateDialog(info, apk.absolutePath)
            }
        }
    }

    /**
     * 发起安装后盯一眼：8 秒后如果还停留在本界面，说明安装界面根本没起来。
     *
     * 手机（Android 10+）上，会话安装的确认界面是靠广播里启动的，会被后台启动限制静默拦掉；
     * 所以第一轮改由前台 Activity 直接发 Intent 拉起系统安装器（这条路不受该限制），
     * 再不行才落到手动安装对话框，保证任何机型都不会"什么也不发生"。
     */
    private fun watchInstallLaunch(info: UpdateInfo, apk: java.io.File, attempt: Int = 0) {
        uiHandler.postDelayed({
            if (isFinishing || isDestroyed || !hasWindowFocus()) return@postDelayed
            if (attempt == 0) {
                when (UpdateManager.retryInstallWithIntent(this, apk)) {
                    UpdateManager.InstallResult.DISPATCHED -> {
                        toast(getString(R.string.update_opening_installer))
                        watchInstallLaunch(info, apk, 1)
                    }
                    else -> showManualUpdateDialog(info, apk.absolutePath)
                }
            } else {
                showManualUpdateDialog(info, apk.absolutePath)
            }
        }, 8_000L)
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

    /**
     * 静默应用刷新结果：把最新解析出的线路就地写回内存里的 Channel 对象。
     *
     * 关键：保持 Channel 对象引用不变，只替换它的 sources 内容。列表适配器、
     * filteredChannels 与 PlayerManager 持有的都是同一批对象，于是用户下一次
     * 换台时 openChannel() 取到的就是最新地址——等于"换台一瞬间直接换掉"。
     *
     * 全程不重建列表、不切分组、不刷适配器、不弹提示、不碰播放器，
     * 正在观看的这一路完全不被打扰，真正做到不知不觉。
     */
    private fun applyRefreshedChannelsSilently(fresh: List<Channel>) {
        if (fresh.isEmpty()) return

        // 用户手动设置过的台标要保留，不能被订阅源刷新冲掉
        val overrides = channels.associateBy { it.normalizedName() }.mapValues { it.value.logoOverride }
        fresh.forEach { ch ->
            overrides[ch.normalizedName()]?.let { ov -> if (ov.isNotBlank()) ch.logoOverride = ov }
        }

        val freshByName = fresh.associateBy { it.normalizedName() }
        val merged = channels.toMutableList()
        // 同名频道：就地替换线路（保持对象引用），解析为空的跳过以免误伤正在播放的频道
        merged.forEach { old ->
            val new = freshByName[old.normalizedName()] ?: return@forEach
            if (new.sources.isEmpty()) return@forEach
            old.sources.clear()
            old.sources.addAll(new.sources)
            old.logo = new.logo
            old.tvgId = new.tvgId
        }
        // 订阅源里新增的频道加进内存，下一次 applyFilter 时会自然出现在列表里
        val oldNames = merged.map { it.normalizedName() }.toSet()
        fresh.forEach { ch -> if (ch.normalizedName() !in oldNames) merged.add(ch) }

        channels = merged
        repo.saveChannels(channels)
    }

    /**
     * 排期订阅源定时刷新。档位 0=关闭时不排任何任务。
     * 每次调用都先撤销旧任务并递增序号，避免改档位后旧循环还在跑。
     */
    private fun scheduleSourceRefresh() {
        sourceRefreshRunnable?.let { uiHandler.removeCallbacks(it) }
        sourceRefreshRunnable = null
        sourceRefreshSeq++ // 让进行中的旧刷新协程结果作废
        val minutes = playSettings.sourceRefreshMin
        if (minutes <= 0) return
        val periodMs = minutes * 60_000L
        val runnable = object : Runnable {
            override fun run() {
                val seq = sourceRefreshSeq
                if (seq != sourceRefreshSeq) return
                performSourceRefresh(seq)
                // 无论本轮成功失败，按当前档位继续排下一轮
                val nextMin = playSettings.sourceRefreshMin
                if (nextMin > 0 && seq == sourceRefreshSeq) {
                    uiHandler.postDelayed(this, nextMin * 60_000L)
                }
            }
        }
        sourceRefreshRunnable = runnable
        uiHandler.postDelayed(runnable, periodMs)
    }

    /**
     * 执行一轮订阅源刷新：只下载 URL 类型且已启用的源。
     * 下载失败或解析不到频道时保留本地缓存（什么都不写），绝不把源清空。
     * 成功后静默就地更新内存里的频道线路，不重建列表、不弹提示、不打断观看；
     * 新地址在下一次换台那一刻生效。
     */
    private fun performSourceRefresh(seq: Int) {
        val targets = repo.getPlaylistSources().filter { it.enabled && it.url.startsWith("http", true) }
        if (targets.isEmpty()) {
            sourceRefreshLastResult = "没有可刷新的在线源"
            return
        }
        lifecycleScope.launch {
            var updated = 0
            var failReason = ""
            // 解析 1800+ 行的聚合源在主线程会明显卡顿，整段下载+解析都放在 IO
            var fresh: List<Channel> = emptyList()
            withContext(Dispatchers.IO) {
                for (s in targets) {
                    if (seq != sourceRefreshSeq) return@withContext
                    val result = PlaylistLoader.load(s.url, true)
                    if (result.isFailure) {
                        if (failReason.isBlank()) {
                            failReason = "下载失败：${result.exceptionOrNull()?.message ?: "网络异常"}"
                        }
                        continue
                    }
                    val raw = result.getOrNull() ?: continue
                    // 解析不到频道视为失败，保留旧 raw，不用空内容覆盖缓存
                    if (M3uParser.parse(raw, s.url).isEmpty()) {
                        if (failReason.isBlank()) failReason = "内容解析不到频道"
                        continue
                    }
                    repo.updatePlaylistSource(s.id, raw = raw)
                    updated++
                }
                if (updated > 0) {
                    val enabled = repo.getPlaylistSources().filter { it.enabled }
                    fresh = M3uParser.merge(enabled.map { M3uParser.parse(it.raw, it.url) })
                }
            }
            if (seq != sourceRefreshSeq) return@launch
            if (updated > 0 && fresh.isNotEmpty()) {
                // 只有真正拉到新内容才计数，避免「显示刷新了但什么都没变」的假成功
                sourceRefreshCount++
                sourceRefreshLastResult = "成功更新 $updated 个源"
                // 静默就地更新：只把最新线路写回内存里的频道对象，不重建列表、不弹提示。
                applyRefreshedChannelsSilently(fresh)
                // 正在观看：若当前这条线路已被新数据淘汰，就地换到新线路（不退出播放页）。
                // 线路还在则只修正索引，用户完全无感。
                if (pagePlayer.visibility == View.VISIBLE) {
                    currentChannel?.let { ch -> resyncPlayingSilently(ch) }
                }
            } else {
                sourceRefreshLastResult = if (failReason.isBlank()) "内容无变化" else failReason
            }
        }
    }

    /**
     * 观看中静默同步线路：数据已在 applyRefreshedChannelsSilently 里就地换好，
     * 这里只负责让播放器跟上，并保证状态栏不会残留旧错误提示。
     */
    private fun resyncPlayingSilently(channel: Channel) {
        val pm = playerManager ?: return
        // VLC 兜底内核接管期间（MP2/AC3 等软解频道）不动 Exo，否则会和兜底内核抢画面。
        // 这类频道本来就走独立内核，等下次正常换台再同步即可。
        if (fallbackPlayer != null) return
        val switched = pm.resyncPlayingChannel(channel)
        if (switched) {
            // 真的换了线路：更新线路标签，状态回到「连接中」，让用户知道画面会重连一次
            val total = channel.sources.size
            val idx = pm.playingSourceIndex
            currentLineLabel = if (total > 1 && idx >= 0) "线路 ${idx + 1}/$total" else "直播"
            setStatus("$currentLineLabel · 订阅源已更新，正在连接…", R.color.yellow)
        }
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
            // 用 TextView 而不是 Button：主题是 MaterialComponents，Button(this) 实际是
            // MaterialButton，自带约 24dp 的 insetLeft/insetRight，minWidth=0 和 setPadding
            // 都管不到它，标签会被撑到 ~80dp，手机上第四个「其它」被挤出屏幕。
            // TextView 没有这套 inset，padding 就是真实宽度，标签按「文字 + padding」测量，
            // 四个 2 字标签约 46dp×4 + 间距，360dp 手机上可完整显示，仍可横向滑动。
            val chip = TextView(this).apply {
                setBackgroundResource(R.drawable.bg_chip)
                text = label
                gravity = android.view.Gravity.CENTER
                isClickable = true
                isFocusable = true
                // TextView.setTextSize 默认按 sp 解释；getDimension 返回 px，除以 scaledDensity 换成 sp
                textSize = resources.getDimension(R.dimen.chip_text) /
                        resources.displayMetrics.scaledDensity
                val density = resources.displayMetrics.density
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    resources.getDimensionPixelSize(R.dimen.chip_height)
                ).apply { setMargins(0, 0, (6 * density + 0.5f).toInt(), 0) }
                setPadding((10 * density + 0.5f).toInt(), 0, (10 * density + 0.5f).toInt(), 0)
                setOnClickListener { applyFilter(g) }
            }
            chip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            if (g == currentGroup) updateChipSelected(chip, true)
            chip.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) chip.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                else if (g != currentGroup) chip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
            groupContainer.addView(chip)
        }
        groupKeys = groups
    }

    /** 应用分组筛选 */
    private fun applyFilter(group: String) {
        currentGroup = group
        filteredChannels = if (group == "ALL") channels else channels.filter { it.group == group }
        // 同步标签选中态
        for (i in 0 until groupContainer.childCount) {
            val child = groupContainer.getChildAt(i) as TextView
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

    private fun updateChipSelected(btn: TextView, selected: Boolean) {
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
            lifecycleScope,
            repo,
            onAdd = { name, content, isUrl -> addPlaylistSource(name, content, isUrl) },
            onChanged = { rebuildChannelsFromSources() },
            onEpgChanged = { epgManager.clearCache() },
            // 手机/平板上用相机直接扫二维码；电视没有相机，对话框会自动隐藏该按钮
            onRequestScan = { onResult -> requestQrScan(onResult) }
        )
        dialog.setOnDismissListener { sourceDialog = null }
        sourceDialog = dialog
        dialog.show()
    }

    /**
     * 发起相机扫码添加直播源。
     *
     * 相机被系统禁用、设备无相机、或预览页启动失败时，给出明确提示而不是静默失败 ——
     * 用户点了按钮却什么都没发生，是最难排查的一种「没反应」。
     */
    private fun requestQrScan(onResult: (String) -> Unit) {
        pendingScanCallback = onResult
        try {
            val intent = Intent(this, QrScanActivity::class.java)
                .putExtra(QrScanActivity.EXTRA_PROMPT, getString(R.string.source_scan_hint))
            scanQrLauncher.launch(intent)
        } catch (t: Throwable) {
            pendingScanCallback = null
            toast(getString(R.string.source_scan_no_camera))
        }
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
        // 换台/重新起播前，先释放可能存在的兜底内核并把 Exo 视图恢复显示
        releaseVlcFallback()
        playerViewRef?.visibility = View.VISIBLE
        currentChannel = channel
        repo.setLastChannelName(channel.name)
        tvPlayerTitle.text = channel.name
        // 只有多条线路时才显示「换线」按钮，避免单线路频道给用户无效操作
        btnSwitchLine.visibility = if (channel.sources.size > 1) View.VISIBLE else View.GONE
        setStatus("正在连接…", R.color.yellow)
        showPlayerPage()
        // 换台先清掉上一台的黑边判断结果，等出画后按新画面重新判断
        // 视频尺寸也要清：上一台是 4:3、这一台是 16:9 时，留着旧值会先错放大一次
        lastVideoW = 0
        lastVideoH = 0
        resetAutoCrop()
        applyVideoLayout()

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

        // 起播看门狗已下沉到 PlayerManager：每次内部切源都会重新布防，
        // 避免「换到一条死线路后没人兜底、永久黑屏」的问题。

        // 加载节目单（后台，失败静默）
        loadEpg(channel)
    }

    /**
     * 手动换源：弹出当前频道的全部线路，由用户指定要切哪一条。
     *
     * 早先是「顺序切下一条」，但实际用起来最需要的是「我就想跳过第 1 条去试第 3 条」，
     * 顺序切换绕不过去，而且自动换线有冷却/熔断，用户主动操作时不该被这些保护挡住。
     */
    private fun switchSourceManually() {
        val pm = playerManager ?: return
        val channel = currentChannel ?: return
        if (channel.sources.size <= 1) {
            toast("当前频道只有一条线路，没有可切换的备用源")
            return
        }
        val total = channel.sources.size
        SourcePickerDialog(this, channel, pm.playingSourceIndex) { index ->
            // 兜底内核接管期间换源：先交还控制权，让 Exo 重新起播新线路。
            // 注意必须等用户真的选了某条线路再释放 VLC —— 放在弹窗之前会把
            // 「打开换源弹窗后又取消」变成黑屏：VLC 已停、Exo 也还停着。
            if (fallbackPlayer != null) {
                releaseVlcFallback()
                playerViewRef?.visibility = View.VISIBLE
                pm.setSuspended(false)
            }
            if (pm.switchToSource(index)) {
                currentLineLabel = "线路 ${index + 1}/$total"
                setStatus("$currentLineLabel · 连接中…", R.color.yellow)
            }
        }.show()
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
        // 直播延迟：0 = 交给 Media3 自动判断（约 3 个分片），>0 = 按用户设定的秒数往后站
        val liveDelayMs = playSettings.liveDelayS.coerceIn(0, PlaybackSettings.MAX_LIVE_DELAY) * 1000
        val pm = try {
            PlayerManager(
                this,
                bufferMinMs = minBufferMs,
                bufferMaxMs = maxS * 1000,
                bufferForPlaybackMs = playStart,
                bufferForPlaybackAfterRebufferMs = rebuffer,
                liveDelayMs = liveDelayMs,
                switchCooldownMs = cooldownMs,
                decoderPriority = playSettings.decoderPriority
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
                    liveDelayMs = 0,
                    switchCooldownMs = cooldownMs,
                    decoderPriority = playSettings.decoderPriority
                )
            } catch (t2: Throwable) {
                toast("播放器初始化失败：${t2.message ?: t2.javaClass.simpleName}")
                return
            }
        }
        pm.maxRetry = playSettings.maxRetry
        pm.setAudioOutputEnabled(playSettings.audioEnabled)
        // AC-3 / MP2 音轨自动交给软解内核（iptv-finder 源里很常见）
        pm.setAudioCompatEnabled(playSettings.audioCompat)
        lateinit var sd: StallDetector
        sd = StallDetector(
            onStall = stall@{
                // 用户可关闭「卡顿自动换线路」，关掉后只在状态条提示，不打断观看
                if (!playSettings.autoSwitchOnStall) return@stall
                // 注意：这里必须走「卡顿换线」专用入口。
                // 旧代码调用 switchToNextSource()，而该方法在已出过画面时会直接返回 false，
                // 所以即使打开开关也永远不会换线 —— 这就是「卡顿不切线路」的根因。
                val switched = pm.switchToNextSourceOnStall()
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
            cooldownMs = playSettings.stallCooldownS.coerceIn(3, 60) * 1000L,
            // 用户主动暂停时不算卡顿，避免暂停久了被自动换线
            isPaused = { !pm.player.playWhenReady }
        )
        pm.player.addAnalyticsListener(sd)
        pm.onSourceChanged = { _, idx, total ->
            // 播放器内部自动换线 / 手动换线都会走到这里：同步重置卡顿检测，
            // 否则 StallDetector 会把新线路起播缓冲算成上一条线路的卡顿。
            sd.onSourceSwitched()
            runOnUiThread {
                currentLineLabel = if (total > 1) "线路 ${idx + 1}/$total" else "直播"
                setStatus("$currentLineLabel · 连接中…", R.color.yellow)
                // 换了线路就可能是完全不同的画面比例：丢掉上一条线路的放大倍数与视频尺寸
                lastVideoW = 0
                lastVideoH = 0
                resetAutoCrop()
                scheduleAutoCrop(3_000L)
            }
        }
        // 视频显示尺寸（已折算像素宽高比）：判断黑边的第一手依据。
        // 4:3 老频道在 16:9 屏上必然左右留黑，这个信息从轨道里就能拿到，
        // 比采样像素可靠得多 —— Exo 是把 SurfaceView 按比例缩小，采不到黑边。
        pm.onVideoSize = { vw, vh ->
            runOnUiThread {
                lastVideoW = vw
                lastVideoH = vh
                applyAspectCrop()
            }
        }
        // 起播看门狗（在 PlayerManager 内，随每次切源重新布防）自动换线后的提示
        pm.onStartTimeoutSwitched = {
            runOnUiThread { toast("这条线路无响应，已自动换线路") }
        }
        // 设备缺少 AC-3 / MP2 等音频解码器时：临时切换到 VLC 兜底内核（视频硬解 + 音频软解），
        // 保证这类频道一定出声；兜底内核也起不来时再退化为无声播放。
        pm.onAudioFallback = { source, codec ->
            runOnUiThread { startVlcFallback(source, codec) }
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
                // 只在状态栏提示，不弹 toast：偶发一次换线很正常，弹窗反而打断观看
                setStatus("$currentLineLabel · 画面卡顿，已切换线路", R.color.yellow)
            }
        }
        // 软解内核彻底卡死后自动换线：用户需要知道画面为什么从头来了一遍
        pm.onFallbackSwitched = { idx, total ->
            runOnUiThread {
                currentLineLabel = if (total > 1) "线路 ${idx + 1}/$total" else "直播"
                setStatus("$currentLineLabel · 软解卡顿，换线路重试…", R.color.yellow)
            }
        }
        // 换线额度用完（这个频道的线路都不稳）：停止一切自动换线，留在当前线路继续重连
        pm.onAllSourcesStuck = {
            runOnUiThread {
                setStatus("$currentLineLabel · 线路都不稳定，已停止自动换线", R.color.red)
                toast("这个频道的线路都不太稳，已停止自动换线；可手动换源，或稍后再试")
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
                        setStatus("$currentLineLabel · 播放中", R.color.green)
                        // 真正出画后再采样黑边：起播那一帧往往是全黑的，早了会误判
                        scheduleAutoCrop(2_000L)
                    }
                    Player.STATE_BUFFERING -> {
                        setStatus("$currentLineLabel · 缓冲中…", R.color.yellow)
                    }
                }
            }
        })

        pm.attachView(ensurePlayerView())
        playerManager = pm
        stallDetector = sd
        applyVideoLayout()
        statsBar.visibility = View.VISIBLE
    }

    /**
     * 创建（或按渲染方式重建）PlayerView 并挂到当前播放器。
     * surface 兼容性最好、更省电；texture 支持动画缩放，个别老盒子上用它更稳。
     */
    private fun ensurePlayerView(): PlayerView {
        val wantTexture = playSettings.renderMode == "texture"
        val existing = playerViewRef
        if (existing != null && existing.tag == playSettings.renderMode) {
            playerManager?.let { existing.player = it.player }
            return existing
        }
        existing?.player = null
        videoHost.removeAllViews()
        val layout = if (wantTexture) R.layout.view_player_texture else R.layout.view_player_surface
        val pv = layoutInflater.inflate(layout, videoHost, false) as PlayerView
        pv.tag = playSettings.renderMode
        videoHost.addView(pv)
        playerViewRef = pv
        playerManager?.let { pv.player = it.player }
        return pv
    }

    /** 自动去黑边的三种结果：不动 / 裁切铺满 / 拉伸铺满 */
    private enum class CropMode { NONE, CROP, STRETCH }

    /**
     * 按「设备屏幕形状 + 画面比例」决定怎么摆画面。**这是唯一的一处决策点**，
     * 主内核（Exo）和兜底内核（VLC）都走它，手机上再也不会出现「Exo 一套、软解另一套」。
     *
     * 核心判据是比例差 d = 屏幕比例 / 画面比例（d > 1 = 画面比屏幕窄，左右留黑）：
     *  - d ≤ 1.40：裁掉的那点边缘最多只有每边 14%，用「裁切铺满」换整屏最划算。
     *    典型：手机横屏看 16:9（d≈1.22，每边裁 9%，几乎看不出来）、电视看 4:3（d≈1.33）。
     *  - d > 1.40：裁起来就肉疼了（手机看 4:3 要裁掉 39% 高度），宁可居中留黑边，
     *    画质完整、不变形 —— 这就是「要么黑边、要么显示不全」的解：不裁，也不放大。
     *  - d < 0.98：画面比屏幕宽（宽银幕），黑边在上下，裁切会切掉左右内容，不动。
     */
    private fun decideCrop(d: Float): CropMode {
        if (!playSettings.autoCrop) return CropMode.NONE
        if (playSettings.zoomMode != "fit") return CropMode.NONE
        if (d <= 0.1f) return CropMode.NONE
        // 用户手动覆盖：1 一律裁切 / 2 一律原比例 / 3 一律拉伸
        when (playSettings.autoCropStrength) {
            1 -> return if (kotlin.math.abs(d - 1f) > 0.02f) CropMode.CROP else CropMode.NONE
            2 -> return CropMode.NONE
            3 -> return if (d > 1.02f) CropMode.STRETCH else CropMode.NONE
        }
        return when {
            d in 0.98f..1.02f -> CropMode.NONE     // 已经严丝合缝
            d > 1.02f && d <= 1.40f -> CropMode.CROP
            else -> CropMode.NONE
        }
    }

    /**
     * 应用画面比例与自定义缩放。
     * 自定义缩放不依赖 TextureView 的 scale 属性，而是直接放大/缩小视图实际尺寸并居中，
     * SurfaceView（老盒子默认）也能正确生效。
     */
    private fun applyVideoLayout() {
        // 自动去黑边只在「适应屏幕」时接管：用户手动选了裁切/拉伸/自定义缩放，就以用户为准，
        // 否则会出现「我明明选了裁切，它又给我改回去」的怪异行为。
        //
        // 还必须加一道几何闸：只有「画面比窗口窄」时铺满才有意义。
        // 竖屏看 16:9 画面时情况正相反（画面比窗口宽），铺满=把左右各切掉七成，
        // 而转屏瞬间 videoHost 可能还是旧方向、cropMode 还留着横屏的 STRETCH，
        // 于是竖屏第一帧就被套上横屏的结论 —— 这就是「转竖屏后画面变成一小条」的成因。
        val autoActive = cropMode != CropMode.NONE &&
            playSettings.zoomMode == "fit" && videoNarrowerThanHost()
        // 去黑边的实现方式是「铺满」：画面放大到刚好盖住整屏，多出来的部分裁掉。
        //
        // 1.58 的实现是「把播放视图撑到比屏幕还大」，在 SurfaceView（盒子默认渲染方式）
        // 上并不可靠：撑出屏幕的图层会被系统压回可见范围，画面没有跟着变大，外面的黑底
        // 却变大了 —— 用户看到的正是「放大了，左右黑边反而更多」。
        // 改用 Media3 自己的裁切模式，由 AspectRatioFrameLayout 算裁切、不动视图尺寸，
        // 结果确定、不闪不抖，SurfaceView / TextureView 都一样。
        val mode = when {
            !autoActive -> playSettings.zoomMode
            cropMode == CropMode.STRETCH -> "stretch"
            else -> "crop"
        }
        // 兜底内核（VLC）有自己的一套画面比例计算，同步应用同一份决策。
        //
        // 关键：VLC 的「自动去黑边」必须走 ScaleType，**绝不能改 SurfaceView 尺寸**。
        // 尺寸一变，libvlc 就认为窗口变了，要重建整条 vout 管线（2–5 秒），而黑边判断
        // 恰好发生在起播后几秒 —— 这就是「切到软解后播 5 秒左右突然卡住」的成因。
        fallbackPlayer?.let { fb ->
            val vlcCrop = autoActive && cropMode == CropMode.CROP
            val vlcStretch = autoActive && cropMode == CropMode.STRETCH
            fb.setAutoFill(vlcCrop)
            // 拉伸铺满：把「视频比例」交给兜底内核，让它用这个比例去建渲染缓冲，
            // 再由 SurfaceView 把缓冲拉到整屏 —— 完全绕开 setVideoScale
            // （1.61 实测那套在这台机器上一点效果都没有，VLC 视频输出压根不认缩放指令）。
            val videoAspect = if (lastVideoH > 0 && lastVideoW > 0) lastVideoW.toFloat() / lastVideoH else 0f
            fb.setStretchAspect(if (vlcStretch) videoAspect else 0f)
            // 竖屏 + 适应屏幕：直接把视频层做成视频矩形，从第一帧起就是最终几何。
            // 竖屏下窗口比例（约 0.46）和视频比例（1.78）差得最远，libvlc 的
            // 「先按窗口建 vout、再在里面缩放」会表现为「先往左右拉、再上下缩」；
            // 视图本身就等于视频矩形后，窗口比例与视频一致，那两下都不再发生。
            val portrait = if (videoHost.width > 0 && videoHost.height > 0) {
                videoHost.height > videoHost.width
            } else {
                // 宿主还没布局完时按系统方向判断，否则竖屏首个频道会漏掉这一档
                resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            }
            val vlcFit = portrait && playSettings.zoomMode == "fit" && !vlcCrop && !vlcStretch
            fb.setFitAspect(if (vlcFit) videoAspect else 0f)
            val vlcMode = when {
                vlcCrop -> "fit"          // 由 SURFACE_FILL 负责裁切
                vlcStretch -> "fit"       // 由缓冲比例负责铺满（best-fit 进缓冲即满）
                else -> playSettings.zoomMode
            }
            fb.applyLayout(vlcMode, if (autoActive) 100 else playSettings.zoomScalePct)
        }
        val pv = playerViewRef ?: return
        pv.resizeMode = when (mode) {
            "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            "crop" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        pv.post {
            val lp = pv.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            // 只有用户自己选的「自定义缩放」还靠撑大视图实现：Media3 没有按百分比的放大，
            // 而这条路径是用户明确选的，出了问题也知道是自己调的那一档。
            // 自动去黑边一律走 RESIZE_MODE_ZOOM，不再动视图尺寸。
            if (mode == "scale") {
                val hostW = if (videoHost.width > 0) videoHost.width
                else resources.displayMetrics.widthPixels
                val hostH = if (videoHost.height > 0) videoHost.height
                else resources.displayMetrics.heightPixels
                val scale = playSettings.zoomScalePct.coerceIn(50, 200) / 100f
                lp.width = (hostW * scale).toInt().coerceAtLeast(1)
                lp.height = (hostH * scale).toInt().coerceAtLeast(1)
            } else {
                lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            }
            lp.gravity = Gravity.CENTER
            pv.layoutParams = lp
        }
    }

    /**
     * 画面是否比宿主窗口更窄：只有这种情况才谈得上「左右留黑、要不要铺满」。
     * 竖屏看 16:9 时是反过来的（画面比窗口宽），自动去黑边必须完全退出，
     * 否则铺满会把左右各切掉七成，看起来就是「画面变成中间一小条」。
     */
    private fun videoNarrowerThanHost(): Boolean {
        if (lastVideoW <= 0 || lastVideoH <= 0) return false
        val hw = videoHost.width
        val hh = videoHost.height
        if (hw <= 0 || hh <= 0) return false
        val hostAspect = hw.toFloat() / hh
        val videoAspect = lastVideoW.toFloat() / lastVideoH
        return videoAspect < hostAspect - 0.02f
    }

    /**
     * 按轨道比例算一次该用哪种画幅，并立刻应用。
     *
     * 主内核和兜底内核都走这里：软解频道也拿得到比例（切 VLC 之前 Exo 已经起播过、
     * 轨道尺寸记在 lastVideoW/H 里，startVlcFallback 不会清它），
     * 所以手机上软解播放不再「一套一套规则」，不会再出现「Exo 铺满、软解留大黑边」。
     */
    private fun applyAspectCrop(silent: Boolean = false, force: Boolean = false) {
        if (!playSettings.autoCrop) return
        if (playSettings.zoomMode != "fit") return
        val hostW = videoHost.width
        val hostH = videoHost.height
        if (hostW <= 0 || hostH <= 0) return
        // 竖屏时黑边在上下、横屏在左右，两回事：只按横屏形态算，转屏后会重算
        if (hostW <= hostH) return
        if (lastVideoW <= 0 || lastVideoH <= 0) return

        val videoAspect = lastVideoW.toFloat() / lastVideoH
        val hostAspect = hostW.toFloat() / hostH
        val want = decideCrop(hostAspect / videoAspect)
        // force = 「换了内核，把决策重新灌一遍」：cropMode 是界面侧的状态，
        // 而新起的 VLC 实例 zoomMode 默认是 fit、autoFill 默认是 false。
        // 只比较「决策有没有变」就会漏掉这个交接，表现为 Exo 铺满、1.5 秒后切软解突然留大黑边。
        if (want == cropMode && !force) return
        cropMode = want
        if (want != CropMode.NONE) autoCropDone = true
        applyVideoLayout()
        if (want != CropMode.NONE && !silent) {
            toast(
                when (want) {
                    CropMode.STRETCH -> "画面比例 ${fmtAspect(videoAspect)}，已拉伸铺满"
                    else -> "画面比例 ${fmtAspect(videoAspect)}，已裁切铺满"
                }
            )
        }
    }

    private fun fmtAspect(a: Float): String =
        if (a < 1.4f) "4:3" else if (a < 1.6f) "3:2" else if (a < 1.9f) "16:9" else "宽银幕"

    /**
     * 自动去黑边：起播稳定后采样画面，检测到对称黑边就把画面整体放大到刚好盖住。
     *
     * 为什么不用「按频道记住比例」：一个频道通常有多条线路，比例却按频道存。
     * 换到一条本来就铺满的线路时，记忆值会把正常画面裁掉。
     * 这里每次起播/换源都重新判断一次，没有记忆，也就没有「换源后被误裁」的问题。
     *
     * 三条安全线：只在「适应屏幕」下接管、连续两次采样一致才动手、采样失败静默放弃。
     */
    private fun resetAutoCrop() {
        uiHandler.removeCallbacks(autoCropRunnable)
        autoCropScheduled = false
        autoCropDone = false
        autoCropTries = 0
        autoCropCandidatePct = 0
        autoCropStableHits = 0
        if (cropMode != CropMode.NONE) {
            cropMode = CropMode.NONE
            applyVideoLayout()
        }
    }

    /**
     * 换内核专用：清掉「重测排期」，但**保留已经得出的画幅结论**。
     *
     * 以前这里调的是 [resetAutoCrop]，它会把 cropMode 清成 NONE，于是软解内核的
     * 第一帧只能按「完整画面」出画（4:3 频道上下留黑），等出画后再重新判一次、
     * 把画面放大铺满 —— 用户看到的就是「上下拉一下」。
     *
     * 黑边是内容属性，同一路流换渲染内核不会变，没有理由重算。这里保留结论：
     * - 已有结论（CROP / STRETCH）：直接沿用，首帧就是最终画幅，全程零跳动；
     * - 还没有结论（起播不到一秒就切了内核）：保持 autoCropDone=false，
     *   出画后仍会补测一次，与旧行为一致。
     */
    private fun resetAutoCropKeepResult() {
        uiHandler.removeCallbacks(autoCropRunnable)
        autoCropScheduled = false
        autoCropTries = 0
        autoCropCandidatePct = 0
        autoCropStableHits = 0
        // 有结论就当作已完成（不再重测，避免二次变化）；没结论才允许出画后补测
        autoCropDone = cropMode != CropMode.NONE
        // 刻意不动 cropMode，也不调 applyVideoLayout：画面此刻仍是旧结论，不闪
    }

    /** @param delayMs 起播后多久开始采样：VLC 兜底重建管线较慢，要给足时间 */
    private fun scheduleAutoCrop(delayMs: Long) {
        if (!playSettings.autoCrop) return
        // 用户手动选过裁切/拉伸/自定义缩放，就完全以用户为准
        if (playSettings.zoomMode != "fit") return
        if (autoCropScheduled || autoCropDone) return
        autoCropTries = 0
        autoCropCandidatePct = 0
        autoCropStableHits = 0
        autoCropScheduled = true
        uiHandler.postDelayed(autoCropRunnable, delayMs)
    }

    private fun detectBlackBars() {
        autoCropScheduled = false
        if (!playSettings.autoCrop || playSettings.zoomMode != "fit") return
        if (isFinishing || isDestroyed) return
        if (pagePlayer.visibility != View.VISIBLE) return
        if (videoHost.width <= 0 || videoHost.height <= 0) {
            retryAutoCrop()
            return
        }
        // 竖屏时黑边在上下、横屏在左右，两者完全不是一回事：等转成横屏形态再判断
        if (videoHost.width <= videoHost.height) {
            retryAutoCrop()
            return
        }
        // 只要拿得到轨道比例，判断就是确定性的，不需要采样 —— 而且采样是在「已经处理好
        // 的画幅」上做的（Exo 会把画面缩在 SurfaceView 里、VLC 已经裁过），画面上本来就没
        // 黑边，采出来必然判「无黑边」，反而会把正确的裁切撤掉。软解频道尤其容易中这个招。
        if (lastVideoW > 0 && lastVideoH > 0) {
            autoCropDone = true
            return
        }
        val surface: View? = fallbackPlayer?.videoSurface() ?: playerViewRef?.videoSurfaceView
        if (surface == null) {
            retryAutoCrop()
            return
        }
        // TextureView 可以直接 getBitmap；SurfaceView 走 PixelCopy（API 24+）
        val isTexture = fallbackPlayer == null && playSettings.renderMode == "texture"
        BlackBarDetector.capture(surface, isTexture) { bmp ->
            uiHandler.post { onBlackBarSample(bmp) }
        }
    }

    private fun retryAutoCrop() {
        if (autoCropTries >= MAX_AUTO_CROP_TRIES) {
            // 采样一直不成功（整屏黑、设备不支持拷贝）：本轮收工，等下次换台再试
            autoCropDone = true
            return
        }
        autoCropTries++
        if (!playSettings.autoCrop || playSettings.zoomMode != "fit") return
        if (autoCropScheduled) return
        autoCropScheduled = true
        uiHandler.postDelayed(autoCropRunnable, AUTO_CROP_RETRY_MS)
    }

    private fun onBlackBarSample(bmp: android.graphics.Bitmap?) {
        if (isFinishing || isDestroyed) return
        val insets = bmp?.let { BlackBarDetector.analyze(it) }
        if (insets == null) {
            // 画面全黑（还在缓冲）、暗场、或这台设备不支持采样：这一轮放弃，稍后再看
            retryAutoCrop()
            return
        }
        val needed = BlackBarDetector.requiredScalePct(insets)
        if (needed <= 102) {
            // 这条源本来就没黑边（换源后很常见）：把上一轮可能存在的处理撤掉
            autoCropDone = true
            if (cropMode != CropMode.NONE) {
                cropMode = CropMode.NONE
                applyVideoLayout()
            }
            return
        }
        // 连续两次算出同一个值才应用：切源瞬间和暗场可能摸到一帧假黑边
        if (needed == autoCropCandidatePct) {
            autoCropStableHits++
        } else {
            autoCropCandidatePct = needed
            autoCropStableHits = 1
        }
        if (autoCropStableHits < 2) {
            retryAutoCrop()
            return
        }
        autoCropDone = true
        val want = decideCrop(needed / 100f)
        if (want == cropMode) return
        cropMode = want
        applyVideoLayout()
        if (want != CropMode.NONE) {
            toast(if (want == CropMode.STRETCH) "检测到黑边，已拉伸铺满" else "检测到黑边，已裁切铺满")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Activity 声明了 configChanges，旋转不会重建：此时必须重新计算缩放尺寸。
        // 兜底内核（VLC）要额外告诉它「surface 尺寸变了」——它得按新尺寸重建 vout，
        // 否则画面会按竖屏的窗口尺寸画在左上角（旧版本的横屏 bug）。
        if (pagePlayer.visibility == View.VISIBLE) {
            // 横竖屏切换后黑边的位置和比例完全不同，上一次的放大倍数作废，按新尺寸重新计算
            resetAutoCrop()
            applyVideoLayout()
            // 视频尺寸没变，比例结论仍然有效：这里直接用已知的 (lastVideoW, lastVideoH) 重算
            applyAspectCrop()
            fallbackPlayer?.onHostResized()
            scheduleAutoCrop(2_200L)
            // configChanges 回调早于重新布局：此刻 videoHost 还是旧尺寸，上面这轮用的是旧方向。
            // 等布局落定再判一次，免得竖屏还挂着横屏的结论（反之亦然）。
            uiHandler.postDelayed({
                if (!isFinishing && !isDestroyed && pagePlayer.visibility == View.VISIBLE) {
                    applyAspectCrop(silent = true, force = true)
                }
            }, 600L)
        }
    }

    /**
     * VLC 侧的直播缓存时长，跟随用户的「直播延迟」设置。
     *
     * 下限 4 秒：低于一个 HLS 分片就必然在每个分片边界停一下；
     * 上限 15 秒：VLC 的 demux 缓存是整段 TS 驻留内存，再大对小盒子不划算。
     * 用户选「自动」(0) 时用 8 秒——VLC 没有 Exo 那种按窗口自动计算的机制，
     * 必须给个固定值，8 秒约等于一个半分片，是最稳的折中。
     */
    private fun vlcCachingMs(): Int {
        val s = playSettings.liveDelayS
        if (s <= 0) return 8_000
        return (s * 1000).coerceIn(4_000, 15_000)
    }

    /**
     * 启动 VLC 兜底内核：视频优先硬解、驱动不允许时自动退回软解，音频由 FFmpeg 解码，
     * 专门解决 AC-3 / E-AC-3 / MP2 音轨「有画面没声音」的问题。
     *
     * @param codecLabel 识别到的音频编码（AC-3、MP2…），仅用于状态提示
     */
    private fun startVlcFallback(source: Source, codecLabel: String = "音频") {
        // 先抓一帧旧画面，再往下走：Exo 一 stop 它的 surface 就黑，而 VLC 从建 SurfaceView
        // 到出首帧还要 1~2 秒，这段黑洞就是「切软解黑屏一下」。抓帧是异步的（PixelCopy），
        // 但只有一帧的工夫（约 16~50ms），Exo 在这期间照常播放，用户看不出来。
        val token = ++kernelSwitchToken
        val urlAtStart = playerManager?.playingUrl
        captureSwitchFrame { bmp ->
            // 抓帧期间换了台 / 又切了一次内核：这次结果作废，交给新的那次去处理。
            // 不拦的话会拿着上一条流去起 VLC，把新频道的播放顶掉。
            if (token != kernelSwitchToken || playerManager?.playingUrl != urlAtStart) {
                bmp?.recycle()
                return@captureSwitchFrame
            }
            startVlcFallbackNow(source, codecLabel, bmp)
        }
    }

    /**
     * 真正的切换动作。原样保留原来的顺序，只多了一步「把旧画面贴上去」。
     *
     * @param frame 切换前抓到的最后一帧；null 表示这台设备抓不到（API<24、不支持 PixelCopy、
     *              自定义缩放模式），此时行为与旧版完全一致，只是少了那层遮挡。
     */
    private fun startVlcFallbackNow(source: Source, codecLabel: String, frame: Bitmap?) {
        val pm = playerManager
        if (pm == null) {
            frame?.recycle()
            return
        }
        // 同一时间只保留一个兜底内核
        releaseVlcFallback()
        // 换内核不等于换画面：沿用主内核已经算出的黑边结论，不要清成「完整画面」，
        // 否则首帧会先按未裁切的比例出来、稍后再放大铺满（「上下拉一下」）
        resetAutoCropKeepResult()
        // 必须在停 Exo 之前贴上去，否则中间会露出一帧黑
        if (frame != null) showSwitchFrame(frame)
        // 必须先停掉 Exo：硬解码器实例有限，Exo 不释放 VLC 就起不来
        pm.setSuspended(true)
        playerViewRef?.visibility = View.GONE
        pbLoading.visibility = View.VISIBLE
        // 编码名缺失时（例如解码失败路径拿不到轨道信息）避免出现「音频 音频」的重复文案
        val audioDesc = if (codecLabel.isBlank() || codecLabel == "音频") "音频" else "$codecLabel 音频"
        setStatus("$currentLineLabel · $audioDesc，兼容内核启动中…", R.color.yellow)

        var handled = false

        fun giveUp(reason: String) {
            if (handled) return
            handled = true
            // 先放掉 VLC 再决定下一步：盒子上的 MediaCodec 实例有限，
            // 不释放它，接下来无论是换线还是回到 Exo 都起不来
            releaseVlcFallback()
            playerViewRef?.visibility = View.VISIBLE

            // ① 优先换线路。软解管线已经救不回来（重建到上限仍然定格）时，
            //    重启同一条线路只是原地打转 —— 那正是「MP2 那几个频道老是卡」的由来。
            //    libvlc 本身起不来属于全局问题，换成哪条都一样，跳过换线省十几秒。
            if (VlcFallbackPlayer.isLineFailure(reason) &&
                playerManager?.switchToNextSourceAfterFallbackFailure() == true
            ) {
                pbLoading.visibility = View.VISIBLE
                toast("这条线路软解卡住了，换线路重试")
                return
            }

            // ② 所有线路的软解都失败过，才降级：保留画面、放弃声音
            pbLoading.visibility = View.GONE
            playerManager?.continueWithoutAudio()
            toast("${audioDesc}软解失败（$reason），已转为无声播放")
            setStatus("$currentLineLabel · 音频编码不支持，已无声播放", R.color.yellow)
        }

        val fallback = VlcFallbackPlayer(
            this,
            videoHost,
            // 这个回调要幂等：横竖屏切换后内核会按新尺寸重建一次，重建完成同样会回调，
            // 此时只需把转圈收掉、状态恢复成「播放中」，不能当成二次起播处理。
            onPrepared = preparedCb@{
                if (handled) return@preparedCb
                fallbackTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
                fallbackTimeoutRunnable = null
                pbLoading.visibility = View.GONE
                // 出画后再按轨道比例定一次画幅：软解频道以前完全不走比例判断，
                // 手机上就一直留着左右黑边（这里补上，且与主内核同一套规则）。
                // force：VLC 重建管线（转屏、卡死恢复）后是新实例，画幅要重灌一遍。
                applyAspectCrop(silent = true, force = true)
                // VLC 的 Playing 事件代表音视频轨都已解出来，这时候是真的有声音了
                setStatus("$currentLineLabel · 播放中（${audioDesc}软解）", R.color.green)
                // 首帧已经出来了，但先别急着揭掉那张静止图：setVideoScale 是异步下发，
                // Playing 后 400ms / 1200ms 还有重试，画幅可能在这期间才真正生效。
                // 多盖这一会儿，就把「画面上下拉一下」藏在了静止画面后面。
                hideSwitchFrame(switchFrameHoldMs())
                // 软解路径出画更慢，多等一会儿再采样黑边
                scheduleAutoCrop(3_500L)
            },
            onFailed = { reason -> giveUp(reason) },
            // 横竖屏切换 / 画面比例变化时 VLC 要按新尺寸重建视频输出：
            // 给个转圈提示，避免用户以为卡住了（重建约 0.5–1.5 秒）
            onRestarting = {
                if (!handled) {
                    pbLoading.visibility = View.VISIBLE
                    setStatus("$currentLineLabel · 画面重排中…", R.color.yellow)
                    // 重建期间 VLC 的画面会短暂消失/错位。静止图还在的话就继续盖着并重新计时，
                    // 否则用户会先看到「往右一拉」、再看到「上下缩一下」。
                    extendSwitchFrameHold(switchFrameHoldMs())
                }
            },
            // 与 Exo 侧的「直播延迟」同量级：用户把这个值调大后，两条内核都要跟着变厚，
            // 否则同一频道换内核就换了一套缓冲表现，用户会觉得「一会儿稳一会儿卡」。
            networkCachingMs = vlcCachingMs()
        )
        fallbackPlayer = fallback
        // 新实例的 zoomMode 是默认的 fit，必须先把「当前决策」原样灌进去，
        // 再按轨道比例重新判一次（force：换了内核要重灌，不能因为「决策没变」就跳过）。
        applyVideoLayout()
        // 切软解前 Exo 已经把轨道尺寸记下来了：这里立刻按同一套规则决定画幅，
        // 不用等出画后再采样，软解频道从第一帧起就和主内核一致。
        applyAspectCrop(silent = true, force = true)
        if (!fallback.play(source.url)) {
            // play() 内部已回调 onFailed，这里兜住「没有任何回调」的极端情况
            giveUp("启动失败")
            return
        }
        // VLC 自带硬解失败自动降级，不需要像 IJK 那样换一套参数重试，超时即判失败
        val timeout = Runnable { giveUp("启动超时") }
        fallbackTimeoutRunnable = timeout
        uiHandler.postDelayed(timeout, FALLBACK_START_TIMEOUT_MS)
    }

    /** 释放兜底内核（如果有）。调用方负责恢复 PlayerView 的显示。 */
    private fun releaseVlcFallback() {
        fallbackTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
        fallbackTimeoutRunnable = null
        fallbackPlayer?.release()
        fallbackPlayer = null
        // 内核都放掉了，那张静止图留着就是冻住的画面，必须一起收掉
        clearSwitchFrame()
    }

    // ── 换内核时的「旧画面静止图」─────────────────────────────────
    // 见 kernelSwitchFrame 的注释：用来抹掉 Exo 停播到 VLC 出画之间的黑色空洞，
    // 顺带把 VLC 画幅下发的那一下跳动藏在静止画面后面。

    /**
     * 抓当前正在显示的这一帧。必须在停 Exo 之前调用。
     *
     * 抓不到一律回调 null（由调用方走原来的路径），**任何情况下都不影响播放**：
     * API<24、设备不支持 PixelCopy（个别盒子/DRM）、surface 无效、内存不足都算在内。
     */
    private fun captureSwitchFrame(onDone: (Bitmap?) -> Unit) {
        val pv = playerViewRef
        if (pv == null || pv.visibility != View.VISIBLE) return onDone(null)
        if (pagePlayer.visibility != View.VISIBLE) return onDone(null)
        // 自定义缩放会把播放视图撑得比屏幕大（靠视图尺寸实现），整屏贴图对不上位置，
        // 贴上去反而会露出错位的画面 —— 这一档干脆不贴。
        if (playSettings.zoomMode == "scale") return onDone(null)
        val hostW = videoHost.width
        val hostH = videoHost.height
        if (hostW <= 0 || hostH <= 0) return onDone(null)
        val target = pv.videoSurfaceView

        if (playSettings.renderMode == "texture") {
            // 按 TextureView 自己的尺寸取图（不做缩放）：贴回去时要按它的真实位置 1:1 摆，
            // 若这里就拉成整屏，FIT 模式下的 4:3 频道会被横向拉长。
            val tv = target as? TextureView
            if (tv == null || !tv.isAvailable || tv.width <= 0 || tv.height <= 0) return onDone(null)
            return onDone(runCatching { tv.getBitmap(tv.width, tv.height) }.getOrNull())
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return onDone(null)
        val sv = target as? SurfaceView ?: return onDone(null)
        if (!sv.holder.surface.isValid) return onDone(null)
        val w = sv.width
        val h = sv.height
        if (w <= 0 || h <= 0) return onDone(null)
        // 一整屏位图，4K 盒子上约 33MB。分配失败就放弃遮挡，让切换走老路。
        val dst = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            return onDone(null)
        }

        // 只交付一次，且必须交付一次。
        // 「必须」：PixelCopy 的回调在 surface 被销毁等情况下可能永远不来，
        //         那样 VLC 就永远不会被启动，频道会卡在「有画面没声音」——
        //         所以加一道超时，到点就按「抓不到」继续切换。
        // 「只交付一次」：超时先到之后，迟到的回调只负责回收自己的位图。
        var finished = false
        val timeout = Runnable {
            if (finished) return@Runnable
            finished = true
            onDone(null)
        }
        uiHandler.postDelayed(timeout, SWITCH_FRAME_CAPTURE_TIMEOUT_MS)

        try {
            PixelCopy.request(sv, Rect(0, 0, w, h), dst, { result ->
                if (finished) {
                    // 已经按超时走掉了，这张图没人用，回收掉
                    if (!dst.isRecycled) dst.recycle()
                    return@request
                }
                finished = true
                uiHandler.removeCallbacks(timeout)
                if (result == PixelCopy.SUCCESS) {
                    onDone(dst)
                } else {
                    dst.recycle()
                    onDone(null)
                }
            }, uiHandler)
        } catch (t: Throwable) {
            // 部分盒子/DRM 内容不支持拷贝：这一轮不遮挡
            finished = true
            uiHandler.removeCallbacks(timeout)
            if (!dst.isRecycled) dst.recycle()
            onDone(null)
        }
    }

    /** 把抓到的旧画面贴到播放区最上层（转圈和顶部栏仍在它之上） */
    private fun showSwitchFrame(bmp: Bitmap) {
        clearSwitchFrame()
        if (pagePlayer.visibility != View.VISIBLE) {
            bmp.recycle()
            return
        }
        val iv = ImageView(this).apply {
            layoutParams = frameLayoutParamsFor()
            // 抓的就是那一块区域的原始像素，必须 1:1 铺回去，留一点缩放差都会看到错位
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(bmp)
            // 只负责挡黑，不参与交互（否则会把遥控焦点和点击从播放页截走）
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // videoHost 是第 0 个子view，插在 1 就是「盖住画面、但在转圈与顶部栏之下」
        pagePlayer.addView(iv, 1)
        kernelSwitchFrame = iv
        kernelSwitchBitmap = bmp
    }

    /**
     * 覆盖层在播放区里的摆放位置。
     *
     * 视频层（SurfaceView / TextureView）不一定等于播放区大小：
     * - FIT（默认）：按比例内缩，两侧或上下留黑边，比播放区小；
     * - ZOOM（裁切铺满）：被摆得比播放区大，多出的部分由父布局裁掉；
     * - FILL（拉伸）：正好等于播放区。
     * 所以不能简单地 MATCH_PARENT + FIT_XY，否则前两种都会画面错位/变形。
     * 拿不到几何信息时退回整屏，与旧行为一致。
     */
    private fun frameLayoutParamsFor(): FrameLayout.LayoutParams {
        val full = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        val target = playerViewRef?.videoSurfaceView ?: return full
        val w = target.width
        val h = target.height
        if (w <= 0 || h <= 0) return full
        val targetLoc = IntArray(2)
        val hostLoc = IntArray(2)
        try {
            target.getLocationInWindow(targetLoc)
            pagePlayer.getLocationInWindow(hostLoc)
        } catch (t: Throwable) {
            return full
        }
        // ZOOM 时这两个值可能是负数，负 margin 正好还原「超出播放区被裁掉」的效果
        return FrameLayout.LayoutParams(w, h).apply {
            leftMargin = targetLoc[0] - hostLoc[0]
            topMargin = targetLoc[1] - hostLoc[1]
        }
    }

    /** 淡出并回收静止图；delayMs 给「画幅还没下发完」留出遮挡时间 */
    private fun hideSwitchFrame(delayMs: Long = 0L) {
        // 已经安排过就不要再排一次：VLC 重建管线（转屏、卡死恢复）后会再回调一次 onPrepared
        if (kernelSwitchFrame == null || kernelSwitchHidePending) return
        kernelSwitchHidePending = true
        val start = Runnable {
            kernelSwitchHidePending = false
            kernelSwitchHideRunnable = null
            // 这里刻意不清 kernelSwitchFrame / kernelSwitchBitmap：
            // 因为接下来要淡出 200ms，这期间用户完全可能换台，
            // clearSwitchFrame() 还得靠这两个引用把旧画面立刻摘掉。
            // 真正清空放在动画结束（fadeOutFrame 内），并核对引用没被换掉。
            val iv = kernelSwitchFrame ?: return@Runnable
            val bmp = kernelSwitchBitmap
            fadeOutFrame(iv, bmp)
        }
        kernelSwitchHideRunnable = start
        if (delayMs > 0L) uiHandler.postDelayed(start, delayMs) else start.run()
    }

    /**
     * 静止图该盖多久。
     *
     * 横屏：等 setVideoScale 的重试（400/1200ms）落定即可。
     * 竖屏：VLC 的 vout 要按「宽高互换」的窗口重建一次，重建前后画面会先偏一下、
     * 再缩一下 —— 那正是竖屏下「先往右拉、再上下拉」的来源，需要盖得更久。
     */
    private fun switchFrameHoldMs(): Long {
        // 优先按画面区实际比例判断；起播瞬间还没量到尺寸时退回系统方向
        val w = videoHost.width
        val h = videoHost.height
        val portrait = if (w > 0 && h > 0) {
            h > w
        } else {
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        if (!portrait) return SWITCH_FRAME_HOLD_MS
        // 竖屏：视频层已经按视频比例定尺寸（VlcFallbackPlayer.fitAspect），
        // 出画即最终几何，不再需要用长遮挡去藏「拉一下」—— 盖住首帧前的黑就够。
        // 拿不到轨道比例时 fitAspect 不会启用，退回旧的长遮挡。
        return if (lastVideoW > 0 && lastVideoH > 0) SWITCH_FRAME_HOLD_VERTICAL_FIT_MS
        else SWITCH_FRAME_HOLD_VERTICAL_MS
    }

    /** 重新开始遮挡计时（VLC 重建 vout 时用）：已经在淡出/已揭开就不管了 */
    private fun extendSwitchFrameHold(delayMs: Long) {
        if (kernelSwitchFrame == null) return
        kernelSwitchHideRunnable?.let { uiHandler.removeCallbacks(it) }
        kernelSwitchHideRunnable = null
        kernelSwitchHidePending = false
        hideSwitchFrame(delayMs)
    }

    private fun fadeOutFrame(iv: ImageView, bmp: Bitmap?) {
        fun finish() {
            // 只有引用仍指向这一次的对象才清空：淡出期间可能已经贴上了新一轮的图，
            // 那两张属于下一轮，不能被这里顺手清掉（否则那张图就再也没人回收了）。
            if (kernelSwitchFrame === iv) kernelSwitchFrame = null
            if (kernelSwitchBitmap === bmp) kernelSwitchBitmap = null
            detachFrameView(iv)
            recycleFrameBitmap(bmp)
        }
        try {
            iv.animate().cancel()
            iv.animate().alpha(0f).setDuration(SWITCH_FRAME_FADE_MS).withEndAction { finish() }.start()
        } catch (t: Throwable) {
            // 动画起不来就直接摘掉，别让冻住的画面留在屏幕上
            finish()
        }
    }

    /**
     * 立刻收掉静止图（切台、失败降级、退出播放页时用，不等淡出）。
     *
     * 关键：淡出要等 480ms 才真正开始，这期间那层图还挂在屏幕上。
     * 所以这里必须在「还在等」的阶段就把它摘掉，否则切台后会有半秒
     * 盖着上一台的静止画面 —— 比黑屏难受得多。
     */
    private fun clearSwitchFrame() {
        // 排期中的淡出必须一起撤掉：不撤的话它到点会执行，把下一轮刚贴上去的新图淡掉
        kernelSwitchHideRunnable?.let { uiHandler.removeCallbacks(it) }
        kernelSwitchHideRunnable = null
        kernelSwitchHidePending = false
        val iv = kernelSwitchFrame ?: return
        kernelSwitchFrame = null
        val bmp = kernelSwitchBitmap
        kernelSwitchBitmap = null
        detachFrameView(iv)
        recycleFrameBitmap(bmp)
    }

    private fun detachFrameView(iv: ImageView) {
        try {
            (iv.parent as? android.view.ViewGroup)?.removeView(iv)
        } catch (t: Throwable) {
            // 已经不在树上了
        }
    }

    private fun recycleFrameBitmap(bmp: Bitmap?) {
        if (bmp == null) return
        try {
            if (!bmp.isRecycled) bmp.recycle()
        } catch (t: Throwable) {
            // 忽略：回收失败最多是多占一点内存，绝不能让播放流程崩
        }
    }

    /** 兜底内核没有 PlayerView 控制条：遥控确认键直接暂停/继续 */
    private fun toggleFallbackPlayback() {
        val fb = fallbackPlayer ?: return
        // 焦点停在顶部栏按钮上时，这一下属于按钮（换源/返回/全屏），
        // 触发暂停会把「点换源」变成「已暂停」，必须挡掉。
        if (isFocusOnTopBarButton()) return
        val playing = fb.togglePlayPause()
        toast(if (playing) "继续播放" else "已暂停")
    }

    /**
     * 焦点是不是落在播放页顶部栏的按钮上。
     *
     * 软解内核接管期间，确认键短按被借去当暂停；而 TV 上焦点默认落在「返回」按钮，
     * 用户按右键移到「换源」再按确认，就会被暂停逻辑吃掉，表现为「点一下只弹已暂停」。
     */
    private fun isFocusOnTopBarButton(): Boolean = currentFocus is Button

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
        // 转屏后条子会被重建的布局带出来，重新计时，避免刚转完就消失
        showOverlay()
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
        // 状态提示跟着顶部信息条一起走：出现时把按钮也一并唤出，几秒后整体淡出
        statsBar.visibility = View.VISIBLE
        showOverlay()
    }

    /**
     * 显示播放页顶部信息条，并在若干秒后自动淡出。
     *
     * 起播、切台、换源、出错提示、按键、触摸都会调用它，所以「最后一次操作之后
     * 几秒」条子就消失，不会一直压在画面上。@param autoHideMs 0 = 本次不自动隐藏。
     */
    private fun showOverlay(autoHideMs: Long = OVERLAY_AUTO_HIDE_MS) {
        if (pagePlayer.visibility != View.VISIBLE) return
        uiHandler.removeCallbacks(overlayHideRunnable)
        overlayVisible = true
        btnRow.visibility = View.VISIBLE
        topPanel.animate().cancel()
        if (topPanel.visibility != View.VISIBLE) {
            topPanel.visibility = View.VISIBLE
            topPanel.alpha = 0f
        }
        if (topPanel.alpha < 1f) topPanel.animate().alpha(1f).setDuration(160L).start()
        if (autoHideMs > 0L) uiHandler.postDelayed(overlayHideRunnable, autoHideMs)
    }

    /** 顶部信息条淡出。只动界面，不碰播放、不碰画幅，所以不会有任何画面抖动。 */
    private fun hideOverlay() {
        if (pagePlayer.visibility != View.VISIBLE) return
        // 弹窗开着时不要收：用户可能正对着换源列表/设置/频道表操作
        if (sourceDialog?.isShowing == true || pickerDialog?.isShowing == true) {
            uiHandler.postDelayed(overlayHideRunnable, 1_500L)
            return
        }
        overlayVisible = false
        // 焦点若停在按钮上，收起来后必须交还，否则遥控器按「确定」会落空
        (currentFocus as? Button)?.clearFocus()
        // 状态条一起收：它是「播放中／缓冲中」这类运行信息，同样不该长期压在画面上。
        // （设置里的「显示调试信息」只决定状态条里写不写缓冲/丢帧，不影响自动隐藏；
        //   否则开了调试就等于这条永远收不掉，用户看到的就是「只有它没隐藏」）
        statsBar.visibility = View.GONE
        topPanel.animate().cancel()
        topPanel.animate().alpha(0f).setDuration(200L).withEndAction {
            if (!overlayVisible) topPanel.visibility = View.INVISIBLE
        }.start()
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
        // 每次进播放页都从「看得见」开始，几秒后再淡出
        showOverlay()
    }

    private fun closePlayer() {
        uiHandler.removeCallbacks(pickerLongPressRunnable)
        uiHandler.removeCallbacks(overlayHideRunnable)
        // 退出时把顶部信息条复位成可见，下次进来不会看到一片空白
        topPanel.animate().cancel()
        topPanel.alpha = 1f
        topPanel.visibility = View.VISIBLE
        btnRow.visibility = View.VISIBLE
        overlayVisible = true
        // 退出播放就不再需要黑边采样，顺便把放大撤掉
        resetAutoCrop()
        pickerDialog?.dismiss()
        pickerDialog = null
        pbLoading.visibility = View.GONE
        // 退出路径必须绝对可靠：即便兜底内核/Exo 在释放时抛异常，
        // 也一定要把界面切回列表，绝不能出现「按钮点了没反应、只能强杀」。
        try {
            releaseVlcFallback()
        } catch (t: Throwable) {
            fallbackPlayer = null
        }
        playerViewRef?.visibility = View.VISIBLE
        try {
            playerManager?.player?.stop()
        } catch (t: Throwable) {
            // 忽略
        }
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
        // 刷新在后台静默就地更新了频道数据，回到列表正常刷新显示即可，无需重建
        applyFilter(currentGroup)
        // 手机从横屏播放回到竖屏列表，可视高度变了，重新兜一次三行
        ensureThreeRowsVisible()
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

    /**
     * 播放参数设置弹窗。
     *
     * 两条硬规则：
     * 1) 数值项一律不用 EditText。电视遥控器上没有数字键，EditText 上的改法是
     *    「方向键挪光标 + 一位一位按」，改一个 1500 要按十几次还容易按错；
     *    现在改成选中数值按「确定」→ 弹数字键盘 → 输完再按「确定」才写入。
     * 2) 保存后立刻重建播放引擎并重新加载当前频道。旧版本这些参数只在
     *    initPlayer() 里读一次，改完不重启应用根本不生效 —— 也就是「这些参数
     *    到底有没有作用」这个疑问的来源。
     */
    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val vWindow = view.findViewById<TextView>(R.id.vStallWindow)
        val vCount = view.findViewById<TextView>(R.id.vStallCount)
        val vCooldown = view.findViewById<TextView>(R.id.vCooldown)
        val vBufMin = view.findViewById<TextView>(R.id.vBufferMin)
        val vBufMax = view.findViewById<TextView>(R.id.vBufferMax)
        val vPlayStart = view.findViewById<TextView>(R.id.vPlayStart)
        val vRebuffer = view.findViewById<TextView>(R.id.vRebuffer)
        val vLiveDelay = view.findViewById<TextView>(R.id.vLiveDelay)
        val vRetry = view.findViewById<TextView>(R.id.vMaxRetry)
        val cbStats = view.findViewById<android.widget.CheckBox>(R.id.cbShowStats)
        val cbInsecure = view.findViewById<android.widget.CheckBox>(R.id.cbInsecureTls)
        val cbAutoSwitch = view.findViewById<android.widget.CheckBox>(R.id.cbAutoSwitch)
        val spZoom = view.findViewById<android.widget.Spinner>(R.id.spZoomMode)
        val vZoomScale = view.findViewById<TextView>(R.id.vZoomScale)
        val spDecoder = view.findViewById<android.widget.Spinner>(R.id.spDecoder)
        val spRender = view.findViewById<android.widget.Spinner>(R.id.spRender)
        val cbAudio = view.findViewById<android.widget.CheckBox>(R.id.cbAudio)
        val cbAudioCompat = view.findViewById<android.widget.CheckBox>(R.id.cbAudioCompat)
        val cbAutoCrop = view.findViewById<android.widget.CheckBox>(R.id.cbAutoCrop)
        val spCropStrength = view.findViewById<android.widget.Spinner>(R.id.spCropStrength)
        val spSourceRefresh = view.findViewById<android.widget.Spinner>(R.id.spSourceRefresh)
        val vRefreshCount = view.findViewById<TextView>(R.id.vRefreshCount)
        val vRefreshResult = view.findViewById<TextView>(R.id.vRefreshResult)
        val zoomValues = resources.getStringArray(R.array.zoom_mode_values)
        val decoderValues = resources.getStringArray(R.array.decoder_values)
        val renderValues = resources.getStringArray(R.array.render_values)

        // 档位位置 0..4 → 0/10/20/30/60 分钟
        val refreshMinutesOptions = listOf(0, 10, 20, 30, 60)
        spSourceRefresh.setSelection(
            refreshMinutesOptions.indexOf(playSettings.sourceRefreshMin).coerceAtLeast(0)
        )
        vRefreshCount.text = "${sourceRefreshCount} 次"
        vRefreshResult.text = sourceRefreshLastResult

        // 数值项：选中后按确定弹数字键盘，确认才写入 tag/text
        bindNumberField(vWindow, "卡顿统计窗口", playSettings.stallWindowS, 5, 60, " 秒")
        bindNumberField(vCount, "窗口内卡顿几次后切源", playSettings.stallCount, 2, 10, " 次")
        bindNumberField(vCooldown, "切源冷却", playSettings.stallCooldownS, 3, 60, " 秒")
        bindNumberField(vRetry, "连续失败重试上限", playSettings.maxRetry, 1, 20, " 次")
        bindNumberField(vBufMin, "最小缓冲", playSettings.bufferMinS, 5, 60, " 秒")
        bindNumberField(vBufMax, "最大缓冲", playSettings.bufferMaxS, 10, 120, " 秒")
        bindNumberField(vPlayStart, "起播缓冲", playSettings.playStartMs, 200, 20000, " 毫秒")
        bindNumberField(vRebuffer, "卡顿后缓冲", playSettings.rebufferMs, 200, 20000, " 毫秒")
        bindNumberField(
            vLiveDelay, "直播延迟", playSettings.liveDelayS, 0,
            PlaybackSettings.MAX_LIVE_DELAY, " 秒"
        )
        bindNumberField(vZoomScale, "自定义缩放", playSettings.zoomScalePct, 50, 200, " %")

        cbStats.isChecked = playSettings.showStats
        cbInsecure.isChecked = playSettings.insecureTls
        cbAutoSwitch.isChecked = playSettings.autoSwitchOnStall
        spZoom.setSelection(zoomValues.indexOf(playSettings.zoomMode).coerceAtLeast(0))
        spDecoder.setSelection(decoderValues.indexOf(playSettings.decoderPriority).coerceAtLeast(0))
        spRender.setSelection(renderValues.indexOf(playSettings.renderMode).coerceAtLeast(0))
        cbAudio.isChecked = playSettings.audioEnabled
        cbAudioCompat.isChecked = playSettings.audioCompat
        cbAutoCrop.isChecked = playSettings.autoCrop
        spCropStrength.setSelection(playSettings.autoCropStrength.coerceIn(0, 3))

        val dialog = AlertDialog.Builder(this)
            .setTitle("播放参数设置")
            .setView(view)
            .setPositiveButton("保存") { _, _ ->
                // 先记录旧值：只有真正变化时才需要重建播放器/视图
                val oldTls = playSettings.insecureTls
                val oldZoomMode = playSettings.zoomMode
                val oldZoomScale = playSettings.zoomScalePct
                val oldDecoder = playSettings.decoderPriority
                val oldRender = playSettings.renderMode
                val oldAudio = playSettings.audioEnabled
                val oldAutoCrop = playSettings.autoCrop
                val oldCropStrength = playSettings.autoCropStrength
                val oldRefreshMin = playSettings.sourceRefreshMin
                // 这一组只影响「播放引擎怎么建」与「卡顿检测策略」，
                // 全部在 PlayerManager / StallDetector 构造时读取，改完必须重建才生效
                val oldEngine = listOf(
                    playSettings.stallWindowS, playSettings.stallCount, playSettings.stallCooldownS,
                    playSettings.bufferMinS, playSettings.bufferMaxS, playSettings.playStartMs,
                    playSettings.rebufferMs, playSettings.liveDelayS, playSettings.maxRetry
                )

                playSettings.stallWindowS = vWindow.valueOr(PlaybackSettings.DEF_STALL_WINDOW)
                playSettings.stallCount = vCount.valueOr(PlaybackSettings.DEF_STALL_COUNT)
                playSettings.stallCooldownS = vCooldown.valueOr(PlaybackSettings.DEF_STALL_COOLDOWN)
                playSettings.bufferMinS = vBufMin.valueOr(PlaybackSettings.DEF_BUFFER_MIN)
                playSettings.bufferMaxS = vBufMax.valueOr(PlaybackSettings.DEF_BUFFER_MAX)
                playSettings.playStartMs = vPlayStart.valueOr(PlaybackSettings.DEF_PLAY_START)
                playSettings.rebufferMs = vRebuffer.valueOr(PlaybackSettings.DEF_REBUFFER)
                playSettings.liveDelayS = vLiveDelay.valueOr(PlaybackSettings.DEF_LIVE_DELAY)
                playSettings.maxRetry = vRetry.valueOr(PlaybackSettings.DEF_MAX_RETRY)
                playSettings.showStats = cbStats.isChecked
                playSettings.autoSwitchOnStall = cbAutoSwitch.isChecked
                playSettings.zoomMode = zoomValues[spZoom.selectedItemPosition.coerceIn(0, zoomValues.size - 1)]
                playSettings.zoomScalePct = vZoomScale.valueOr(PlaybackSettings.DEF_ZOOM_SCALE).coerceIn(50, 200)
                playSettings.decoderPriority =
                    decoderValues[spDecoder.selectedItemPosition.coerceIn(0, decoderValues.size - 1)]
                playSettings.renderMode =
                    renderValues[spRender.selectedItemPosition.coerceIn(0, renderValues.size - 1)]
                playSettings.audioEnabled = cbAudio.isChecked
                playSettings.audioCompat = cbAudioCompat.isChecked
                playSettings.autoCrop = cbAutoCrop.isChecked
                playSettings.autoCropStrength = spCropStrength.selectedItemPosition.coerceIn(0, 3)
                // 刷新档位变化（含开启/关闭）后立即重排定时任务
                val newRefreshMin = refreshMinutesOptions[
                    spSourceRefresh.selectedItemPosition.coerceIn(0, refreshMinutesOptions.size - 1)
                ]
                if (newRefreshMin != oldRefreshMin) {
                    playSettings.sourceRefreshMin = newRefreshMin
                    scheduleSourceRefresh()
                    // 首次开启时立刻跑一轮，不必干等一个完整周期才知道有没有生效
                    if (oldRefreshMin == 0 && newRefreshMin > 0) {
                        performSourceRefresh(sourceRefreshSeq)
                    }
                }

                HttpCompat.allowInsecureTls = playSettings.insecureTls
                statsBar.visibility = if (playSettings.showStats) View.VISIBLE else View.GONE

                val engineNow = listOf(
                    playSettings.stallWindowS, playSettings.stallCount, playSettings.stallCooldownS,
                    playSettings.bufferMinS, playSettings.bufferMaxS, playSettings.playStartMs,
                    playSettings.rebufferMs, playSettings.liveDelayS, playSettings.maxRetry
                )
                // 证书 / 解码器 / 渲染方式都在播放器或视图创建时固定，必须重建后生效；
                // 缓冲与卡顿参数同理 —— 以前漏了这一组，用户改完「毫无反应」。
                val needRebuild = oldTls != playSettings.insecureTls ||
                    oldDecoder != playSettings.decoderPriority ||
                    oldRender != playSettings.renderMode ||
                    oldEngine != engineNow

                if (needRebuild) {
                    val wasPlaying = pagePlayer.visibility == View.VISIBLE
                    val channel = currentChannel
                    rebuildPlayer()
                    if (wasPlaying && channel != null) {
                        // 正在看直播：直接按新参数重新起播，用户不用退出频道再进来
                        openChannel(channel)
                        toast("已按新参数重新加载当前频道")
                    } else {
                        toast("参数已保存，进入频道时生效")
                    }
                } else {
                    // 画面比例/缩放可以立即生效，不打断播放
                    val zoomChanged = oldZoomMode != playSettings.zoomMode ||
                        oldZoomScale != playSettings.zoomScalePct
                    if (zoomChanged || oldAutoCrop != playSettings.autoCrop ||
                        oldCropStrength != playSettings.autoCropStrength
                    ) {
                        // 比例或开关变了：先撤掉上一轮自动放大，再按新设置重排；
                        // 开关重新打开时立刻按视频比例判一次，像素采样作为补充
                        resetAutoCrop()
                        applyVideoLayout()
                        if (playSettings.autoCrop && playSettings.zoomMode == "fit") {
                            applyAspectCrop()
                            scheduleAutoCrop(800L)
                        }
                    }
                    // 音频兼容开关对下一条频道/下一次识别生效，不需要重建播放器
                    playerManager?.setAudioCompatEnabled(playSettings.audioCompat)
                    // 音频开关立即生效
                    if (oldAudio != playSettings.audioEnabled) {
                        playerManager?.setAudioOutputEnabled(playSettings.audioEnabled)
                        toast(if (playSettings.audioEnabled) "已开启声音" else "已关闭声音（仅视频）")
                    } else {
                        toast("参数已保存")
                    }
                }
            }
            .setNeutralButton("恢复默认") { _, _ ->
                playSettings.reset()
                // 档位被清零：立即撤销已排期的定时刷新，不等当前这一轮到点才停
                scheduleSourceRefresh()
                toast("已恢复默认参数")
                // 重开一次让界面上的数值同步成默认值（旧实现只弹个 toast，界面还是旧数字）
                uiHandler.post { showSettingsDialog() }
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

    /** 把某个数值格绑成「点确定 → 弹数字键盘 → 确认写入」，不再用 EditText */
    private fun bindNumberField(
        target: TextView,
        label: String,
        initial: Int,
        min: Int,
        max: Int,
        suffix: String
    ) {
        target.tag = initial
        target.text = displayValue(initial, suffix)
        target.setOnClickListener {
            val current = target.valueOr(initial)
            promptNumber(label, current, min, max, suffix) { picked ->
                target.tag = picked
                target.text = displayValue(picked, suffix)
            }
        }
    }

    private fun displayValue(v: Int, suffix: String): String =
        if (suffix.isBlank()) v.toString() else "$v$suffix"

    private fun TextView.valueOr(default: Int): Int = (tag as? Int) ?: default

    /**
     * 数字输入弹窗：遥控器方向键选键 + 确定输入，输入完再按一次「确定」才写入。
     * 打开时预填当前值，所以「不改直接确定」也是安全的。
     */
    private fun promptNumber(
        label: String,
        current: Int,
        min: Int,
        max: Int,
        suffix: String,
        onConfirm: (Int) -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_number_input, null)
        val tvValue = view.findViewById<TextView>(R.id.tvNumValue)
        val tvHint = view.findViewById<TextView>(R.id.tvNumHint)

        val buf = StringBuilder(current.toString())

        fun render() {
            tvValue.text = if (buf.isEmpty()) "—" else buf.toString()
        }

        fun type(digit: Char) {
            if (buf.length >= 6) return
            // 只有范围不含 0 时才禁止「0 开头」。直播延迟这类允许 0=自动 的项，
            // 如果也拦掉 0，用户就永远输不进「自动」这个合法值。
            if (buf.isEmpty() && digit == '0' && min > 0) return
            buf.append(digit)
            render()
        }

        fun backspace() {
            if (buf.isNotEmpty()) buf.deleteCharAt(buf.length - 1)
            render()
        }

        mapOf(
            R.id.key0 to '0', R.id.key1 to '1', R.id.key2 to '2', R.id.key3 to '3',
            R.id.key4 to '4', R.id.key5 to '5', R.id.key6 to '6', R.id.key7 to '7',
            R.id.key8 to '8', R.id.key9 to '9'
        ).forEach { (id, digit) ->
            view.findViewById<TextView>(id).setOnClickListener { type(digit) }
        }
        view.findViewById<TextView>(R.id.keyBack).setOnClickListener { backspace() }
        view.findViewById<TextView>(R.id.keyClear).setOnClickListener {
            buf.setLength(0)
            render()
        }

        tvHint.text = "范围 $min–$max$suffix · 输完按「确定」"
        render()

        val dialog = AlertDialog.Builder(this)
            .setTitle(label)
            .setView(view)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_dark)
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
                ?.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val typed = buf.toString().toIntOrNull()
                if (typed == null) {
                    toast("请先输入数字")
                    return@setOnClickListener
                }
                val fixed = typed.coerceIn(min, max)
                dialog.dismiss()
                if (fixed != typed) toast("已调整为 $fixed$suffix（范围 $min–$max）")
                onConfirm(fixed)
            }
        }
        dialog.show()
        // 焦点默认落在「确定」：值不改时按一下就关；要改就往键盘上走
        try {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).requestFocus()
        } catch (t: Throwable) {
            // 个别机型按钮还没 attach，忽略即可
        }
    }

    /** 释放并重建播放引擎：解码器 / 渲染方式 / 缓冲与卡顿参数改动后必须走这条路 */
    private fun rebuildPlayer() {
        try {
            releaseVlcFallback()
        } catch (t: Throwable) {
            fallbackPlayer = null
        }
        stallDetector?.release()
        stallDetector = null
        playerManager?.release()
        playerManager = null
        playerViewRef?.player = null
        playerViewRef = null
        videoHost.removeAllViews()
    }

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

    override fun onResume() {
        super.onResume()
        // 借前台 Activity 给安装结果接收器，绕开 Android 10+ 的后台启动界面限制
        com.iptv.player.update.InstallResultReceiver.attach(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        com.iptv.player.update.InstallResultReceiver.detach(this)
        uiHandler.removeCallbacks(overlayHideRunnable)
        uiHandler.removeCallbacks(pickerLongPressRunnable)
        heroEpgRunnable?.let { uiHandler.removeCallbacks(it) }
        releaseVlcFallback()
        stallDetector?.release()
        stallDetector = null
        playerManager?.release()
        playerManager = null
    }
}

/** 直播页长按确认键呼出频道表的判定时长；短于这个时长仍按普通点按处理 */
private const val PICKER_LONG_PRESS_MS = 600L
/** VLC 兜底内核启动超时：超时视为不可用，回退到 Exo 无声播放 */
private const val FALLBACK_START_TIMEOUT_MS = 12_000L
/** 自动去黑边：两轮采样之间的间隔（连续两次结果一致才应用） */
private const val AUTO_CROP_RETRY_MS = 1_300L
/** 自动去黑边：单轮最多采样几次（缓冲时间长、暗场时不要无限追着看） */
private const val MAX_AUTO_CROP_TRIES = 6
/** 播放页顶部信息条：最后一次操作后多久自动淡出（毫秒） */
private const val OVERLAY_AUTO_HIDE_MS = 4_500L

/**
 * 换内核时那层「旧画面静止图」，在 VLC 出首帧之后再压多久才淡出。
 *
 * 要盖住两件事：① VLC 首帧之前那块黑洞；② setVideoScale 是异步下发，
 * Playing 之后 400ms / 1200ms 各有一次重试，画幅可能到那时才真正生效
 * （表现为画面「上下拉一下」）。
 *
 * 1.83 实测：只盖 480ms 不够 —— 裁切是在 1200ms 那次重试才真正落到屏幕上的。
 * 这里取 1300ms，把最后一次重试连同它的渲染延迟一起盖住。
 * 代价是揭开前多定格约半秒，但覆盖层显示的是切换前的同一路画面，
 * 观感是「画面稍稍停了一下」，比看得见的比例跳变好得多。
 */
private const val SWITCH_FRAME_HOLD_MS = 1_300L

/**
 * 竖屏下的遮挡时长：VLC 要按宽高互换的窗口重建一次 vout（约 1–2 秒），
 * 重建前后的位移与缩放都在这一段里，必须整段盖住。
 */
private const val SWITCH_FRAME_HOLD_VERTICAL_MS = 2_200L

/**
 * 竖屏 + 已按视频比例定视频层尺寸时的遮挡时长。
 *
 * 这一档画面几何在起播前就定死了，出画即最终形态，没有需要藏的缩放动作，
 * 静止图只需盖住「VLC 首帧之前那块黑」。再按 2.2 秒盖就是白白定格，反而像卡住。
 */
private const val SWITCH_FRAME_HOLD_VERTICAL_FIT_MS = 1_000L

/** 静止图淡出时长：太长会像卡了一下，太短又能看到硬切 */
private const val SWITCH_FRAME_FADE_MS = 200L

/**
 * 抓「切换前最后一帧」的超时。
 *
 * PixelCopy 的回调在 surface 被销毁、DRM 内容、个别盒子上可能永远不返回；
 * 而抓帧没回来，后面的切换动作就不会发生 —— 表现是「有画面没声音」一直不好。
 * 到点按「抓不到」继续走，最多损失一层遮挡，绝不阻塞播放。
 */
private const val SWITCH_FRAME_CAPTURE_TIMEOUT_MS = 400L

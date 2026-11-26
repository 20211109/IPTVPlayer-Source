package com.iptv.player.ui

import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.iptv.player.R
import com.iptv.player.config.EpgSettings
import com.iptv.player.config.PlaylistLoader
import com.iptv.player.data.M3uParser
import com.iptv.player.data.PlaylistSource
import com.iptv.player.data.SourceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 直播源管理：多份 M3U 可同时启用，同名频道自动合并线路。
 * 勾选启用 / 编辑 / 刷新 / 删除即时生效；新增交由外部异步下载后回调 refresh。
 *
 * 页面分三块，互不干扰：直播源列表 → 添加直播源 → 节目单接口（EPG）。
 * 「添加直播源」的三种方式按设备能力区分：本机相机扫 / 另一台设备扫码填 / 手动输入。
 */
class SourceManagerDialog(
    context: Context,
    private val scope: CoroutineScope,
    private val repo: SourceRepository,
    private val onAdd: (name: String, content: String, isUrl: Boolean) -> Unit,
    private val onChanged: () -> Unit,
    private val onEpgChanged: () -> Unit = {},
    /**
     * 请求用本机相机扫二维码，扫到内容后回调。
     *
     * Dialog 自己拿不到 registerForActivityResult 的生命周期，必须由宿主 Activity 发起，
     * 所以这里只负责「发出请求」和「处理结果」，相机与权限全部交给宿主。
     */
    private val onRequestScan: ((String) -> Unit) -> Unit = {}
) : Dialog(context) {

    private lateinit var container: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var etEpgApi: EditText
    private val epgSettings = EpgSettings(context)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_source_manager)
        window?.setDimAmount(0.65f)
        // 弹窗尽量占满宽度，避免长 EPG 地址和底部三个按钮挤在一起
        window?.let { w ->
            val lp = w.attributes
            lp.width = (context.resources.displayMetrics.widthPixels * 0.94f).toInt()
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT
            w.attributes = lp
        }

        container = findViewById(R.id.sourceContainer)
        emptyView = findViewById(R.id.tvSourceEmpty)

        etEpgApi = findViewById(R.id.etEpgApi)
        etEpgApi.setText(epgSettings.apiTemplate)
        findViewById<Button>(R.id.btnEpgSave).setOnClickListener {
            saveEpgApi(etEpgApi.text.toString())
        }
        // EPG 的「手机扫码填」：显示二维码，手机打开网页填 EPG 地址。
        // 与上方「添加直播源」完全无关，两者只是都借用了同一个局域网页面。
        findViewById<Button>(R.id.btnEpgScan).setOnClickListener {
            PairingDialog(
                context,
                onSubmit = { text, _ -> saveEpgApi(text) },
                pageTitle = context.getString(R.string.epg_api_scan_title),
                pageHint = context.getString(R.string.epg_api_scan_hint),
                inputHint = context.getString(R.string.epg_api_hint_url)
            ).show()
        }
        findViewById<Button>(R.id.btnEpgReset).setOnClickListener {
            epgSettings.apiTemplate = ""
            etEpgApi.setText(epgSettings.apiTemplate)
            onEpgChanged()
            Toast.makeText(context, R.string.epg_api_reset_done, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnDone).setOnClickListener { dismiss() }
        // 「扫二维码」：用本机相机直接扫。电视没有相机，按钮隐藏，
        // 避免用户点下去只得到一句「没有可用相机」。
        val btnScanQr = findViewById<Button>(R.id.btnScanQr)
        if (hasCamera()) {
            btnScanQr.setOnClickListener { onRequestScan { content -> handleScanned(content) } }
        } else {
            btnScanQr.visibility = View.GONE
        }
        // 「用手机填」：本机显示二维码，另一台设备扫码后在网页里输入
        findViewById<Button>(R.id.btnScanAdd).setOnClickListener {
            PairingDialog(
                context,
                onSubmit = { text, isUrl -> onAdd("", text, isUrl) }
            ).show()
        }
        findViewById<Button>(R.id.btnManualAdd).setOnClickListener { showAddDialog() }

        refresh()
    }

    /** 新增或下载完成后由外部调用，刷新列表 */
    fun refresh() {
        if (!::container.isInitialized) return
        container.removeAllViews()
        val sources = repo.getPlaylistSources()
        emptyView.visibility = if (sources.isEmpty()) View.VISIBLE else View.GONE

        sources.forEach { source ->
            val row = LayoutInflater.from(context)
                .inflate(R.layout.item_playlist_source, container, false)
            val check = row.findViewById<CheckBox>(R.id.cbEnabled)
            val nameView = row.findViewById<TextView>(R.id.tvSourceName)
            val detailView = row.findViewById<TextView>(R.id.tvSourceDetail)

            nameView.text = source.name
            val channelCount = if (source.raw.isNotBlank()) {
                M3uParser.parse(source.raw, source.url).size
            } else {
                -1
            }
            detailView.text = buildString {
                if (source.isUrl) append(source.url) else append("本地文本 · ${source.raw.length} 字符")
                if (channelCount >= 0) append(" · $channelCount 个频道")
                if (!source.enabled) append("（未启用）")
            }

            check.isChecked = source.enabled
            check.setOnCheckedChangeListener { _, checked ->
                repo.updatePlaylistSource(source.id, enabled = checked)
                onChanged()
                refresh()
            }
            // 本地文本源没有可重新拉取的订阅地址，隐藏刷新按钮避免误解
            row.findViewById<Button>(R.id.btnRefresh).visibility =
                if (source.isUrl) View.VISIBLE else View.GONE
            row.findViewById<Button>(R.id.btnRefresh).setOnClickListener { refreshSource(source) }
            row.findViewById<Button>(R.id.btnRename).setOnClickListener { showEditDialog(source) }
            row.findViewById<Button>(R.id.btnDelete).setOnClickListener { confirmDelete(source.id, source.name) }
            container.addView(row)
        }
    }

    /**
     * 重新下载订阅地址并覆盖本地缓存。
     * 关键：源码里 raw 只在添加时下载一次，之前没有刷新入口，源站内容变化后永远看不到。
     * 下载失败或解析不到频道时保留旧数据，不让一次网络故障清空用户的频道列表。
     */
    private fun refreshSource(source: PlaylistSource) {
        if (!source.isUrl) {
            Toast.makeText(context, R.string.source_refresh_text_only, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, R.string.source_refreshing, Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = withContext(Dispatchers.IO) { PlaylistLoader.load(source.url, true) }
            val raw = result.getOrElse { e ->
                Toast.makeText(
                    context,
                    context.getString(R.string.source_refresh_fail, e.message ?: "网络错误"),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            val count = M3uParser.parse(raw, source.url).size
            if (count == 0) {
                Toast.makeText(
                    context,
                    context.getString(R.string.source_refresh_fail, "未解析到有效频道，已保留原内容"),
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            repo.updatePlaylistSource(source.id, raw = raw)
            onChanged()
            refresh()
            Toast.makeText(
                context,
                context.getString(R.string.source_refresh_done, source.name, count),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 保存 EPG 接口：手动填写与扫码提交共用，保存后立即清空节目单缓存 */
    private fun saveEpgApi(raw: String) {
        val api = normalizeApi(raw)
        if (api == null) {
            Toast.makeText(context, R.string.epg_api_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        epgSettings.apiTemplate = api
        etEpgApi.setText(api)
        onEpgChanged()
        Toast.makeText(context, R.string.epg_api_saved, Toast.LENGTH_SHORT).show()
    }

    /** 校验 EPG 地址；未带协议时自动补 https，多个地址支持换行/分号 */
    private fun normalizeApi(raw: String): String? {
        val parts = raw.split('\n', ';').map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        return parts.joinToString("\n") { part ->
            if (part.startsWith("http://", true) || part.startsWith("https://", true)) {
                part
            } else {
                "https://$part"
            }
        }
    }

    /**
     * 编辑直播源：名称、订阅地址、M3U 文本都可改。
     * 地址变更时重新下载最新内容；地址未变但改了文本则直接用新文本，
     * 两者都留空时提示错误，避免把频道列表清空。
     */
    private fun showEditDialog(source: PlaylistSource) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_add_source, null)
        val etName = view.findViewById<EditText>(R.id.etSourceName)
        val etUrl = view.findViewById<EditText>(R.id.etSourceUrl)
        val etText = view.findViewById<EditText>(R.id.etSourceText)
        view.findViewById<TextView>(R.id.tvAddTitle).text = context.getString(R.string.source_edit_title)
        view.findViewById<Button>(R.id.btnAddConfirm).text = context.getString(R.string.source_edit_confirm)
        etName.setText(source.name)
        if (source.isUrl) etUrl.setText(source.url) else etText.setText(source.raw)

        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(view)
            window?.setDimAmount(0.65f)
        }
        view.findViewById<Button>(R.id.btnAddCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btnAddConfirm).setOnClickListener {
            val name = etName.text.toString().trim()
            val url = etUrl.text.toString().trim()
            val text = etText.text.toString().trim()
            val isUrl = url.startsWith("http://", true) || url.startsWith("https://", true)
            if (!isUrl && text.isBlank()) {
                Toast.makeText(context, "请填写 M3U 地址或粘贴列表文本", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            scope.launch {
                val newRaw: String
                val newUrl: String
                if (isUrl) {
                    val result = withContext(Dispatchers.IO) { PlaylistLoader.load(url, true) }
                    newRaw = result.getOrElse { e ->
                        Toast.makeText(context, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    newUrl = url
                } else {
                    newRaw = text
                    newUrl = ""
                }
                val count = M3uParser.parse(newRaw, newUrl).size
                if (count == 0) {
                    Toast.makeText(context, "未解析到频道，请检查地址或文本", Toast.LENGTH_LONG).show()
                    return@launch
                }
                repo.updatePlaylistSource(
                    source.id,
                    name = name.ifBlank { source.name },
                    url = newUrl,
                    raw = newRaw
                )
                onChanged()
                refresh()
                Toast.makeText(
                    context,
                    context.getString(R.string.source_edit_saved, name.ifBlank { source.name }),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(id: String, name: String) {
        val dialog = AlertDialog.Builder(context)
            .setTitle("删除直播源")
            .setMessage("确定删除「$name」吗？该源的频道与线路会一并移除，其它直播源不受影响。")
            .setPositiveButton("删除") { _, _ ->
                repo.deletePlaylistSource(id)
                onChanged()
                refresh()
                Toast.makeText(context, "已删除「$name」", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_dark)
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)
                ?.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        }
        dialog.show()
    }

    /** 本机是否有可用相机：决定「扫二维码」是否显示（电视上隐藏） */
    private fun hasCamera(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /**
     * 扫码结果处理：只认 http/https 地址，且**不直接入库** ——
     * 先填进「添加直播源」对话框让用户确认一次。扫错码最多是点一下取消，
     * 不会把奇怪内容写进源列表。
     */
    private fun handleScanned(content: String) {
        if (!isShowing) return
        val text = content.trim()
        if (text.isBlank()) {
            Toast.makeText(context, R.string.source_scan_fail, Toast.LENGTH_SHORT).show()
            return
        }
        if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) {
            Toast.makeText(context, R.string.source_scan_invalid, Toast.LENGTH_LONG).show()
            return
        }
        showAddDialog(prefillUrl = text)
    }

    /**
     * 手动添加：名称 + 地址（优先）/ M3U 文本。
     *
     * @param prefillUrl 扫码得到的地址，预填到地址框并把焦点放在名称上，用户只需确认
     */
    private fun showAddDialog(prefillUrl: String? = null) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_add_source, null)
        val etName = view.findViewById<EditText>(R.id.etSourceName)
        val etUrl = view.findViewById<EditText>(R.id.etSourceUrl)
        val etText = view.findViewById<EditText>(R.id.etSourceText)
        if (!prefillUrl.isNullOrBlank()) {
            etUrl.setText(prefillUrl)
            etUrl.setSelection(prefillUrl.length)
            etName.requestFocus()
        }
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(view)
            window?.setDimAmount(0.65f)
        }
        view.findViewById<Button>(R.id.btnAddCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btnAddConfirm).setOnClickListener {
            val url = etUrl.text.toString().trim()
            val body = etText.text.toString().trim()
            val isUrl = url.startsWith("http://", true) || url.startsWith("https://", true)
            val content = if (isUrl) url else body.ifBlank { url }
            if (content.isBlank()) {
                Toast.makeText(context, "请填写 M3U 地址或粘贴列表文本", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onAdd(etName.text.toString().trim(), content, isUrl)
            dialog.dismiss()
        }
        dialog.show()
    }
}

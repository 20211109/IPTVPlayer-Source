package com.iptv.player.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.iptv.player.R
import com.iptv.player.config.NetworkUtils
import com.iptv.player.config.PairingServer
import com.iptv.player.config.QrCodeGenerator

/**
 * 配置对话框：TV 屏幕显示二维码与局域网地址，手机扫码后网页提交；
 * 同时提供电视端手动输入 M3U 的入口。
 */
class PairingDialog(
    context: Context,
    private val onSubmit: (text: String, isUrl: Boolean) -> Unit,
    /** 以下三项用于复用同一套二维码页面（直播源 / 节目单接口）：为空时保持默认文案 */
    private val pageTitle: String? = null,
    private val pageHint: String? = null,
    private val inputHint: String? = null
) : Dialog(context) {

    private var server: PairingServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_pairing)

        pageTitle?.let { findViewById<TextView>(R.id.tvPairTitle).text = it }
        pageHint?.let { findViewById<TextView>(R.id.tvPairHint).text = it }
        inputHint?.let { findViewById<EditText>(R.id.etM3u).hint = it }

        val ip = NetworkUtils.getLocalIp(context)
        val url = "http://$ip:8899"
        findViewById<TextView>(R.id.tvPairUrl).text = url
        findViewById<ImageView>(R.id.ivQr).setImageBitmap(QrCodeGenerator.generate(url))

        // 启动局域网配对服务
        server = PairingServer(
            onSubmit = { text, isUrl ->
                android.os.Handler(context.mainLooper).post {
                    onSubmit(text, isUrl)
                    dismiss()
                }
            },
            pageTitle = pageTitle ?: context.getString(R.string.pairing_title),
            pageHint = pageHint ?: context.getString(R.string.pairing_hint),
            urlPlaceholder = inputHint ?: context.getString(R.string.hint_m3u)
        )
        server?.startSafely()

        findViewById<Button>(R.id.btnManualConfirm).setOnClickListener {
            val text = findViewById<EditText>(R.id.etM3u).text.toString().trim()
            if (text.isBlank()) {
                Toast.makeText(context, "请输入内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val isUrl = text.startsWith("http://", true) || text.startsWith("https://", true)
            onSubmit(text, isUrl)
            dismiss()
        }
        findViewById<Button>(R.id.btnClose).setOnClickListener { dismiss() }
    }

    override fun dismiss() {
        server?.stopSafely()
        super.dismiss()
    }
}

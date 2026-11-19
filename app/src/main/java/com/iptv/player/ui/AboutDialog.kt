package com.iptv.player.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Window
import android.widget.Button
import android.widget.TextView
import com.iptv.player.R
import com.iptv.player.update.UpdateInfo
import com.iptv.player.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 关于弹窗：展示版本信息、手动检查更新、应用简介与免责声明、开发者二维码。
 */
class AboutDialog(
    context: Context,
    private val scope: CoroutineScope,
    private val onUpdateFound: (UpdateInfo) -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.dialog_about)

        // 版本号
        val version = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            pi.versionName ?: "-"
        } catch (e: Exception) {
            "-"
        }
        findViewById<TextView>(R.id.tvVersion).text =
            context.getString(R.string.about_version, version)

        val status = findViewById<TextView>(R.id.tvUpdateStatus)
        findViewById<Button>(R.id.btnCheckUpdate).setOnClickListener {
            status.text = context.getString(R.string.about_checking)
            scope.launch {
                val info = withContext(Dispatchers.IO) { UpdateManager.checkUpdate(context) }
                if (info != null) {
                    status.text = ""
                    dismiss()
                    onUpdateFound(info)
                } else {
                    status.text = context.getString(R.string.about_up_to_date)
                }
            }
        }

        findViewById<Button>(R.id.btnAboutClose).setOnClickListener { dismiss() }
    }
}

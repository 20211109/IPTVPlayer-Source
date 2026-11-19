package com.iptv.player.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.io.File

/**
 * 接收 PackageInstaller 会话安装的结果。
 *
 * 【踩过的大坑】普通应用没有 INSTALL_PACKAGES 权限，会话提交后系统不会自己弹安装界面，
 * 而是先发一条 STATUS_PENDING_USER_ACTION 广播，把「确认安装」的 Intent 放在 EXTRA_INTENT 里，
 * 要求应用自己把它启动起来。旧实现只打了一行日志、从未启动这个 Intent，
 * 表现就是用户点了「立即更新」→ 下载跑完 → 之后什么也不发生（静默失败）。
 *
 * 现在：收到 PENDING_USER_ACTION 立即启动确认界面；若被 ROM 拦截（部分电视禁止后台弹界面），
 * 退化成 toast 提示安装包路径，用户可用文件管理器手动安装。
 */
class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        /**
         * 前台 Activity 的弱引用。
         * Android 10+ 禁止应用从后台启动界面，广播里直接 startActivity 会被静默丢弃；
         * 但「当前有可见 Activity 的应用」可以启动界面，所以优先借它来拉起安装确认页。
         */
        private var host: java.lang.ref.WeakReference<android.app.Activity>? = null

        fun attach(activity: android.app.Activity) {
            host = java.lang.ref.WeakReference(activity)
        }

        fun detach(activity: android.app.Activity) {
            if (host?.get() === activity) host = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        android.util.Log.i("IPTVInstall", "session install status=$status message=$message")

        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) return

        val confirm = readIntentExtra(intent) ?: return
        // 从广播里启动 Activity 必须带 NEW_TASK
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = try {
            // 先借前台 Activity 启动（不受后台启动限制），没有则退回接收器 context
            val activity = host?.get()
            if (activity != null && !activity.isFinishing) activity.startActivity(confirm)
            else context.startActivity(confirm)
            true
        } catch (t: Throwable) {
            android.util.Log.w("IPTVInstall", "启动安装确认界面失败: ${t.message}")
            false
        }
        if (!ok) {
            val apk = File(context.getExternalFilesDir("updates"), "app-update.apk")
            Toast.makeText(
                context,
                "系统拦截了安装界面，请用文件管理器打开：${apk.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** EXTRA_INTENT 在 API 33 起要用类型化读取，低版本走旧的 getParcelableExtra */
    private fun readIntentExtra(intent: Intent): Intent? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
    } catch (t: Throwable) {
        null
    }
}

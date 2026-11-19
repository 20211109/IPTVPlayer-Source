package com.iptv.player.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * 接收 PackageInstaller 会话安装的结果。
 *
 * 系统在安装会话结束（用户确认安装 / 取消 / 失败）时通过 PendingIntent 发这个广播。
 * 这里只做日志记录，不改变主流程：安装成功系统会自动结束本应用进程并覆盖安装，
 * 失败时用户仍可在界面里看到原因（例如「安装包解析失败」）。
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        // 用 Log 而不是 Toast：安装成功时本进程马上会被系统杀掉，Toast 也没意义
        android.util.Log.i("IPTVInstall", "session install status=$status message=$message")
    }
}

package com.iptv.player.config

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** 获取设备在局域网内的 IPv4 地址（用于生成配对二维码） */
    fun getLocalIp(context: Context): String {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.connectionInfo?.ipAddress?.let { ip ->
                if (ip != 0) {
                    return String.format(
                        "%d.%d.%d.%d",
                        ip and 0xff, (ip shr 8) and 0xff, (ip shr 16) and 0xff, (ip shr 24) and 0xff
                    )
                }
            }
        } catch (_: Exception) {
        }
        // 回退：遍历网卡
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val ni = ifaces.nextElement()
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress ?: ""
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }
}

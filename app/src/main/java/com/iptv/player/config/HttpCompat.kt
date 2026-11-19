package com.iptv.player.config

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient

/**
 * 网络兼容层：
 *  - 老系统 HTTPS：Android 5.x/6.x 部分机型默认未启用 TLS 1.2，显式声明协议可避免握手失败。
 *  - 明文放行：CLEARTEXT 必须保留，否则 http:// 直播源无法播放。
 *  - 自签名证书：自建电视/酒店/校园直播系统常用私有 CA，系统证书库必然校验失败
 *    （表现就是拉流直接失败、黑屏）。用户可在设置里手动开启「忽略证书校验」，
 *    开启后才使用「信任全部」的 TrustManager 并跳过域名校验。
 *
 * allowInsecureTls 默认 false，是用户显式选择的高风险选项。
 */
object HttpCompat {

    /** 是否允许不受信任的证书（默认关闭，由设置页控制） */
    @Volatile
    var allowInsecureTls: Boolean = false

    /** 正常模式：现代 TLS + 明文 */
    private val SECURE_SPECS: List<ConnectionSpec> = listOf(
        ConnectionSpec.MODERN_TLS,
        ConnectionSpec.CLEARTEXT
    )

    /** 兼容模式：额外允许 TLS 1.0/1.1 与老密码套件，适配老旧直播服务器 */
    private val COMPAT_SPECS: List<ConnectionSpec> = listOf(
        ConnectionSpec.COMPATIBLE_TLS,
        ConnectionSpec.MODERN_TLS,
        ConnectionSpec.CLEARTEXT
    )

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val trustAllFactory: SSLSocketFactory by lazy {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        ctx.socketFactory
    }

    fun clientBuilder(): OkHttpClient.Builder {
        val insecure = allowInsecureTls
        val builder = OkHttpClient.Builder()
            .connectionSpecs(if (insecure) COMPAT_SPECS else SECURE_SPECS)
        if (insecure) {
            builder.sslSocketFactory(trustAllFactory, trustAllManager)
            // 自签名证书常伴随域名不匹配，一并跳过
            builder.hostnameVerifier { _, _ -> true }
        }
        return builder
    }
}

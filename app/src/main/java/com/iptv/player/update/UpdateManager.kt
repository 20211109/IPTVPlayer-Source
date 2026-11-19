package com.iptv.player.update

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.iptv.player.config.HttpCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 远端发布版本信息 */
data class UpdateInfo(
    val version: String,      // 例如 1.1
    val notes: String,        // Release 说明
    val apkUrl: String,       // GitHub 上的 APK 直链
    val sha256: String = ""   // APK 的 SHA-256（旧版本清单没有该字段时为空）
)

/**
 * 自动更新：从 GitHub Releases 检查新版本，通过加速器下载并安装 APK。
 */
object UpdateManager {

    /** APK 体积下限：低于该值必定不是完整安装包 */
    private const val MIN_APK_SIZE = 200 * 1024L

    private val client = HttpCompat.clientBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 检查更新专用：只拉几百字节，超时短，避免某个节点卡住拖慢整个检查 */
    private val checkClient = HttpCompat.clientBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        // 整次调用兜底：个别节点会一直慢吞吞地吐字节，单靠 readTimeout 拖不住
        .callTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 生成候选地址。
     * 用户电视/手机无科学上网：加速器必须是【主路径】，排在最前。
     * version.json 的 apk 是 raw.githubusercontent.com 直链，
     * ghfast.top / gh-proxy.com 等加速器支持「前缀 + 完整URL」代理它。
     * jsDelivr 作为无需代理的国内直连兜底（最后尝试）。
     */
    private fun candidateUrls(direct: String): List<String> {
        val list = mutableListOf<String>()
        // ① 加速器优先（主路径）
        UpdateConfig.ACCELERATORS.forEach { prefix ->
            list.add(prefix.trimEnd('/') + "/" + direct)
        }
        // ② 原始直链兜底
        list.add(direct)
        // ③ jsDelivr CDN 兜底：raw 链接转成 cdn.jsdelivr.net/gh 格式，国内可直连
        if (direct.contains("raw.githubusercontent.com")) {
            val rest = direct.substringAfter("raw.githubusercontent.com/")
            val parts = rest.split("/", limit = 4)
            if (parts.size == 4) {
                val (owner, repo, branch, path) = parts
                list.add("https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path")
                list.add("https://fastly.jsdelivr.net/gh/$owner/$repo@$branch/$path")
            }
        }
        return list.distinct()
    }

    /**
     * 检查是否有新版本。无网络/无 Release/已是最新 均返回 null（静默，不打扰用户）。
     */
    suspend fun checkUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        // 1) 首选：加速器代理 raw version.json（实时，不受 CDN 缓存影响）+ jsDelivr 兜底
        fromVersionJson(context)?.let { return@withContext it }
        // 2) 兜底：GitHub API（可科学上网的环境）
        fromGithubApi(context)
    }

    /**
     * 读取 CI 发布的版本清单：{"version":"1.4","notes":"...","apk":"https://..."}
     *
     * 【为什么并行】串行探测时，只要排在前面的加速器拿到的是 CDN 旧缓存（发版后 5 分钟内常见），
     * 就得把它整个等到超时再换下一个，6 个节点叠起来动辄十几秒，用户体感就是「检查更新好慢」。
     * 改为一次并行探测全部镜像、取版本号最高的那份：总耗时≈最慢的那个节点（通常 1~2 秒），
     * 同时天然免疫单个镜像的缓存滞后（旧缓存版本号更低，直接被淘汰）。
     */
    private suspend fun fromVersionJson(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val local = localVersionName(context)
        val results = UpdateConfig.VERSION_JSON_URLS.map { url ->
            async { fetchVersionInfo(url, local) }
        }
        var best: UpdateInfo? = null
        for (info in results.mapNotNull { it.await() }) {
            val cur = best
            if (cur == null || compareVersion(info.version, cur.version) > 0) best = info
        }
        best
    }

    /** 拉取单个镜像的版本清单；任何异常都当作「该节点不可用」返回 null */
    private fun fetchVersionInfo(url: String, local: String): UpdateInfo? {
        val body = try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "IPTVPlayer")
                .header("Cache-Control", "no-cache")
                .build()
            checkClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (t: Throwable) {
            null
        }
        if (body.isNullOrBlank()) return null
        return try {
            val json = JSONObject(body)
            val remote = json.optString("version", "")
                .removePrefix("v").removePrefix("V").trim()
            val apk = json.optString("apk", "").trim()
            if (remote.isBlank() || apk.isBlank()) return null
            if (!isNewer(remote, local)) return null
            UpdateInfo(
                remote,
                json.optString("notes", "").trim(),
                apk,
                json.optString("sha256", "").trim()
            )
        } catch (t: Throwable) {
            null
        }
    }

    /** 比较两个版本号，返回 a - b 的符号（用于在多镜像结果里取最高版本） */
    private fun compareVersion(a: String, b: String): Int {
        val sa = segments(a)
        val sb = segments(b)
        for (i in 0 until maxOf(sa.size, sb.size)) {
            val x = sa.getOrElse(i) { 0 }
            val y = sb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** GitHub API 兜底（能直连 GitHub 的环境才会走到这里） */
    private fun fromGithubApi(context: Context): UpdateInfo? {
        val body = directGet(UpdateConfig.GITHUB_API) ?: return null
        return try {
            val json = JSONObject(body)
            if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) return null
            val tag = json.optString("tag_name", "")
            val remote = tag.removePrefix("v").removePrefix("V")
            if (remote.isBlank()) return null
            if (!isNewer(remote, localVersionName(context))) return null

            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name", "")
                    if (name.endsWith(".apk", true) && name.contains("release", true)) {
                        apkUrl = a.optString("browser_download_url", "")
                        break
                    }
                }
                if (apkUrl.isBlank()) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        if (a.optString("name", "").endsWith(".apk", true)) {
                            apkUrl = a.optString("browser_download_url", "")
                            break
                        }
                    }
                }
            }
            if (apkUrl.isBlank()) return null
            UpdateInfo(remote, json.optString("body", "").trim(), apkUrl)
        } catch (e: Exception) {
            null
        }
    }

    /** 直连请求（GitHub API 用；加速器不支持 api.github.com，故不参与重试） */
    private fun directGet(target: String): String? = try {
        val req = Request.Builder().url(target)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "IPTVPlayer")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) {
        null
    }

    /** 下载 APK：依次尝试直链与各加速器，返回第一个完整下载的文件 */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // 清掉历史残留的安装包：盒子存储普遍很小，反复更新会攒下几十 MB
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.absolutePath != File(dir, "app-update.apk").absolutePath) f.delete()
        }
        val out = File(dir, "app-update.apk")
        for (url in candidateUrls(info.apkUrl)) {
            try {
                out.delete()
                val req = Request.Builder().url(url)
                    .header("User-Agent", "IPTVPlayer")
                    // 要求不压缩：这样 contentLength 就是真实字节数，可用来识别下载被截断
                    .header("Accept-Encoding", "identity")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body ?: return@use
                    val total = body.contentLength()
                    var downloaded = 0L
                    FileOutputStream(out).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        body.byteStream().use { input ->
                            var n: Int
                            while (input.read(buf).also { n = it } != -1) {
                                fos.write(buf, 0, n)
                                downloaded += n
                                if (total > 0) onProgress((downloaded * 100 / total).toInt())
                            }
                        }
                    }
                    // 完整性校验：长度必须与服务器一致、ZIP 结构可读、（有哈希时）SHA-256 匹配。
                    // 只判断 "PK" 会让被截断的包通过校验，最终由系统安装器报「安装包损坏」。
                    if (isValidApk(out, total, info.sha256)) {
                        onProgress(100)
                        return@withContext out
                    }
                    out.delete()
                }
            } catch (e: Exception) {
                // 尝试下一个加速器
            }
        }
        null
    }

    /**
     * 校验下载结果是否可交给系统安装：
     * 1) 体积下限；2) 与服务器 Content-Length 一致（识别中途断网导致的截断）；
     * 3) ZIP 目录可正常打开且含 AndroidManifest.xml；4) 清单提供 sha256 时逐字节比对。
     */
    private fun isValidApk(file: File, expectedBytes: Long, expectedSha256: String): Boolean {
        if (!file.exists() || file.length() < MIN_APK_SIZE) return false
        if (expectedBytes > 0 && file.length() != expectedBytes) return false
        val headOk = try {
            file.inputStream().use { input ->
                val head = ByteArray(2)
                input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            }
        } catch (e: Exception) {
            false
        }
        if (!headOk) return false
        val zipOk = try {
            java.util.zip.ZipFile(file).use { it.getEntry("AndroidManifest.xml") != null }
        } catch (e: Exception) {
            false
        }
        if (!zipOk) return false
        if (expectedSha256.isBlank()) return true
        val actual = try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            return false
        }
        return actual.equals(expectedSha256.trim(), ignoreCase = true)
    }

    private fun localVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /** 版本号比较：remote 是否高于 local，按 . 分段数值比较 */
    fun isNewer(remote: String, local: String): Boolean {
        val r = segments(remote)
        val l = segments(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    private fun segments(v: String): List<Int> =
        v.split('.', '-', '+').mapNotNull { it.toIntOrNull() }

    fun ignoreVersion(context: Context, version: String) {
        context.getSharedPreferences(UpdateConfig.PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(UpdateConfig.KEY_IGNORE_VERSION, version).apply()
    }

    fun isIgnored(context: Context, version: String): Boolean {
        val saved = context.getSharedPreferences(UpdateConfig.PREF_NAME, Context.MODE_PRIVATE)
            .getString(UpdateConfig.KEY_IGNORE_VERSION, "") ?: ""
        return saved == version
    }

    /** 安装结果，供界面决定后续提示 */
    enum class InstallResult {
        /** 已交给系统安装器 / 安装会话，用户会看到安装界面 */
        DISPATCHED,

        /** 缺少「安装未知应用」权限，已跳转设置页 */
        NEED_PERMISSION,

        /** 所有方式都失败，需要用户手动安装 */
        FAILED
    }

    /** 给用户手动下载用的加速器链接（与 App 内下载走同一条主路径） */
    fun manualDownloadUrl(direct: String): String =
        UpdateConfig.ACCELERATORS.firstOrNull()?.let { it.trimEnd('/') + "/" + direct } ?: direct

    /**
     * 安装 APK。
     *
     * 电视端的坑：不少国产电视（创维/酷开等）自带安装器读不到应用内部 cache 目录，
     * 即便给了 content:// 读权限，也会直接弹「安装失败：检测安装包异常，无法安装」。
     * 因此先把安装包复制到外部私有目录（/sdcard/Android/data/<包名>/files/updates），
     * 再用电视安装器普遍支持的 ACTION_INSTALL_PACKAGE 发起安装，失败再退回 ACTION_VIEW。
     */
    fun install(activity: Activity, apk: File): InstallResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "请先允许安装未知应用，再点击更新", Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.packageName)
                    )
                )
            } catch (e: Exception) {
            }
            return InstallResult.NEED_PERMISSION
        }

        val authority = activity.packageName + ".fileprovider"
        val target = copyToExternal(activity, apk)

        // ===== 方式一（首选）：PackageInstaller 会话安装 =====
        // 完全不依赖安装器的 Intent 过滤器。很多国产盒子/电视（小米盒子4、创维等）的
        // 安装器只声明了 file:// 过滤器，content:// 的 ACTION_VIEW / ACTION_INSTALL_PACKAGE
        // 会直接抛 ActivityNotFound，表现就是「无法自动打开安装器」。
        // 会话安装由系统 PackageInstallerService 直接处理，不经过 Intent 匹配，兼容性最好。
        if (installViaSession(activity, target)) return InstallResult.DISPATCHED

        // ===== 方式二：content:// Intent（AOSP 及多数 ROM 的正常路径） =====
        val uri = try {
            FileProvider.getUriForFile(activity, authority, target)
        } catch (e: Exception) {
            try {
                FileProvider.getUriForFile(activity, authority, apk)
            } catch (e2: Exception) {
                null
            }
        }
        if (uri != null) {
            // ClipData 携带 Uri：MIUI 安装器只认这个；再用 queryIntentActivities
            // 动态找出本机所有能处理 APK 的安装器逐一授权（硬编码包名会漏）。
            val clip = ClipData.newRawUri("apk", uri)
            grantUriToAllInstallers(activity, uri)
            fun baseIntent(action: String): Intent = Intent(action).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = clip
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // 不能用 resolveActivity 预判：targetSdk 30+ 包可见性限制下它会返回 null。
            for (intent in listOf(baseIntent(Intent.ACTION_VIEW), baseIntent(Intent.ACTION_INSTALL_PACKAGE))) {
                try {
                    activity.startActivity(intent)
                    return InstallResult.DISPATCHED
                } catch (e: Exception) {
                    // 换下一种方式
                }
            }
        }

        // ===== 方式三：file:// Intent（老固件兜底） =====
        // targetSdk>=24 默认禁止 file:// URI（FileUriExposedException），
        // 但老盒子/电视的安装器普遍只认 file://，临时放开一次检测即可。
        if (tryFileSchemeInstall(activity, target)) return InstallResult.DISPATCHED

        // ===== 方式四：导出到公共「下载」目录再拉起 =====
        // 少数安装器读不到 Android/data，但能读公共目录。
        val exported = exportToDownloads(activity, apk)
        val exportedUri = exported?.first
        if (exportedUri != null) {
            val publicIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(exportedUri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("apk", exportedUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                activity.startActivity(publicIntent)
                return InstallResult.DISPATCHED
            } catch (e: Exception) {
                // 继续走提示
            }
        }
        val hintPath = exported?.second ?: target.absolutePath
        Toast.makeText(
            activity,
            "自动安装未成功，请用电视文件管理器打开安装包：$hintPath",
            Toast.LENGTH_LONG
        ).show()
        return InstallResult.FAILED
    }

    /**
     * 通过 PackageInstaller 会话安装。
     *
     * 这是绕开「国产安装器不认 content://」最有效的一条路：把 APK 写入系统安装会话，
     * 由 PackageInstallerService 直接弹系统安装界面，完全不经过 Intent 过滤器匹配。
     * API 21 起可用；失败（权限/固件阉割）则返回 false 交给后面的兜底方式。
     */
    private fun installViaSession(activity: Activity, apk: File): Boolean {
        if (!apk.exists() || apk.length() <= 0L) return false
        return try {
            val installer = activity.packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setSize(apk.length())
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("iptv-update.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { input -> input.copyTo(out) }
                    out.flush()
                    session.fsync(out)
                }
                val resultIntent = Intent(activity, InstallResultReceiver::class.java)
                // API 31+ 必须显式声明可变性，否则 getBroadcast 直接抛异常
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pending = PendingIntent.getBroadcast(activity, sessionId, resultIntent, flags)
                session.commit(pending.intentSender)
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 用 file:// URI 拉起安装器（老固件兜底）。
     *
     * targetSdk>=24 时系统默认拦截 file:// 外传（FileUriExposedException），
     * 但小米盒子4 等老固件的安装器只声明了 file:// 过滤器，content:// 收不到。
     * 这里临时把 StrictMode 的 VM 策略放宽成 LAX 绕过检测，调用结束立即恢复原策略。
     */
    private fun tryFileSchemeInstall(activity: Activity, apk: File): Boolean {
        if (!apk.exists()) return false
        val oldPolicy = android.os.StrictMode.getVmPolicy()
        return try {
            android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.LAX)
            val fileUri = Uri.fromFile(apk)
            val clip = ClipData.newRawUri("apk", fileUri)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, "application/vnd.android.package-archive")
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = fileUri
                clipData = clip
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            for (intent in listOf(viewIntent, installIntent)) {
                try {
                    activity.startActivity(intent)
                    return true
                } catch (e: Exception) {
                    // 换下一种方式
                }
            }
            false
        } catch (t: Throwable) {
            false
        } finally {
            android.os.StrictMode.setVmPolicy(oldPolicy)
        }
    }

    /**
     * 给本机所有能处理 APK 的安装器授予 Uri 读取权限。
     * 小米盒子4 的安装器包名与 AOSP 不同，硬编码列表会漏掉导致「无法调起」。
     * 这里用 queryIntentActivities 动态发现（Manifest 已声明 <queries>），
     * 再对发现的每个包授权；常见的几个包名也一并授权作为双保险。
     */
    private fun grantUriToAllInstallers(activity: Activity, uri: Uri) {
        val probe = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
        try {
            val candidates = activity.packageManager.queryIntentActivities(probe, 0)
            for (ri in candidates) {
                try {
                    activity.grantUriPermission(
                        ri.activityInfo.packageName,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // 单个包授权失败不影响其它包
                }
            }
        } catch (e: Exception) {
            // 查询失败时退回硬编码列表
        }
        for (pkg in listOf(
            "com.miui.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller.tv"
        )) {
            try {
                activity.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // 该安装器不存在则忽略
            }
        }
    }

    /** 把 APK 导出到「下载/IPTVPlayer」公共目录，返回 (content uri, 便于阅读的路径) */
    private fun exportToDownloads(context: Context, apk: File): Pair<Uri?, String>? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, apk.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/IPTVPlayer"
                )
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                null
            } else {
                resolver.openOutputStream(uri)?.use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                }
                uri to (Environment.DIRECTORY_DOWNLOADS + "/IPTVPlayer/" + apk.name)
            }
        } else {
            // Android 9 及以下（小米盒子 4 等）没有 MediaStore.Downloads：
            // 有存储权限时直接写公共下载目录，至少能让文件管理器找到安装包。
            // 注意：checkSelfPermission 是 API 23 才有的方法，在老固件上会抛
            // NoSuchMethodError（属于 Error），所以整段外层用 catch(Throwable) 兜。
            val granted = context.checkSelfPermission(
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                null
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "IPTVPlayer"
                ).apply { mkdirs() }
                val dst = File(dir, apk.name)
                apk.inputStream().use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                null to dst.absolutePath
            }
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * 把安装包复制到外部私有目录。外部存储不可用时退回原文件，
     * 由 FileProvider 的 cache-path 规则继续提供读取。
     */
    private fun copyToExternal(context: Context, apk: File): File = try {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val dst = File(dir, apk.name)
        if (dst.absolutePath != apk.absolutePath) {
            apk.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (dst.exists() && dst.length() == apk.length()) dst else apk
    } catch (t: Throwable) {
        apk
    }
}

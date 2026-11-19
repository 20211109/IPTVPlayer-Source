package com.iptv.player.update

/**
 * 更新功能配置。
 *
 * 电视盒子/手机大多无法直连 GitHub，因此版本检查走加速器：
 * 优先用加速器代理 raw version.json（实时、小文件秒回），jsDelivr 仅兜底
 * （实测 jsDelivr 对 GitHub 源缓存不可控，发版后仍长时间返回旧版本，不可用做主路径）。
 * 拿不到时再回退到 GitHub API（适合能科学上网的环境；当前发布机制不用 Release，
 * 该兜底通常返回空，仅作兼容保留）。
 *
 * APK 由 CI 通过专用 Deploy Key 覆盖式推送到公开仓库 main 分支，
 * version.json 中的 apk 字段即 raw 直链；下载时再按 ACCELERATORS 顺序尝试加速器。
 * 实测（2026-11）：ghfast.top 最稳定，其余作为备用。
 */
object UpdateConfig {

    /** 公开分发仓库的版本清单（由 CI 在发布时自动更新） */
    private const val VERSION_JSON_PATH = "20211109/IPTVPlayer-Releases-New@main/version.json"

    /** APK 下载加速器（实测 ghfast.top 稳定，约 0.6MB/s；其余备用） */
    val ACCELERATORS = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/"
    )

    /** jsDelivr 三个可用节点，仅作最后兜底 */
    val VERSION_MIRRORS = listOf(
        "https://cdn.jsdelivr.net/gh/$VERSION_JSON_PATH",
        "https://fastly.jsdelivr.net/gh/$VERSION_JSON_PATH",
        "https://gcore.jsdelivr.net/gh/$VERSION_JSON_PATH"
    )

    /** GitHub API：仅在直连可用时兜底（当前发布机制不用 Release，通常返回空） */
    const val GITHUB_API = "https://api.github.com/repos/20211109/IPTVPlayer-Releases-New/releases/latest"

    /** version.json 的 GitHub raw 直链（实时源，无 CDN 缓存延迟） */
    private const val VERSION_JSON_RAW =
        "https://raw.githubusercontent.com/20211109/IPTVPlayer-Releases-New/main/version.json"

    /**
     * 检查更新的候选地址，按优先级排列。
     * 【关键教训】不能把 jsDelivr 放在首位：jsDelivr 对 GitHub 源缓存不可控，
     * purge 经常失效，实测发版后仍长时间返回旧版本（1.36 已发布却返回 1.34），
     * 导致「检查更新显示已是最新」且因串行重试三个镜像而很慢。
     * 改用加速器代理 raw 直链（实时、无需科学上网、小文件秒回）优先，
     * jsDelivr 仅作为加速器全挂时的最后兜底。
     */
    val VERSION_JSON_URLS: List<String> = buildList {
        ACCELERATORS.forEach { add(it.trimEnd('/') + "/" + VERSION_JSON_RAW) }
        addAll(VERSION_MIRRORS)
    }

    const val PREF_NAME = "iptv_update"
    const val KEY_IGNORE_VERSION = "ignore_version"
}

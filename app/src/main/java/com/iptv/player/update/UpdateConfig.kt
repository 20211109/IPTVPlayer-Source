package com.iptv.player.update

/**
 * 更新功能配置。
 *
 * 电视盒子/手机大多无法直连 GitHub，因此版本检查不再依赖 api.github.com：
 * 优先读取 jsDelivr 上的 version.json（国内可直连、响应 1~2 秒），
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

    /** jsDelivr 三个可用节点，依次尝试 */
    val VERSION_MIRRORS = listOf(
        "https://cdn.jsdelivr.net/gh/$VERSION_JSON_PATH",
        "https://fastly.jsdelivr.net/gh/$VERSION_JSON_PATH",
        "https://gcore.jsdelivr.net/gh/$VERSION_JSON_PATH"
    )

    /** GitHub API：仅在直连可用时作为最后兜底 */
    const val GITHUB_API = "https://api.github.com/repos/20211109/IPTVPlayer-Releases-New/releases/latest"

    /** APK 下载加速器（实测 ghfast.top 稳定，约 0.6MB/s；其余备用） */
    val ACCELERATORS = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/"
    )

    const val PREF_NAME = "iptv_update"
    const val KEY_IGNORE_VERSION = "ignore_version"
}

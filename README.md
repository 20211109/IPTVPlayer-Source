# IPTV 直播壳子（Android TV）

一款 IPTV 直播客户端，支持自定义直播源、手机扫码/手动输入、自动测速选优、卡顿自动切源。用 Kotlin + AndroidX Media3(ExoPlayer) 原生开发，**一套代码同时适配 Android TV / 平板 / 安卓手机**。

## 界面与多端适配

- **视觉风格**：深空暗色底 + 青紫渐变品牌色，顶部 Hero 推荐卡、分组标签栏、频道网格，TV 焦点态带描边与放大动画。
- **自适应布局**：通过 `values`（手机 2 列）/ `values-sw600dp`（平板 4 列）/ `values-television`（TV 5 列）资源限定符自动切换列数、字号、Banner 高度；未强制横屏，手机可竖屏使用。
- **播放器**：沉浸式播放页，顶部频道名与线路状态条（正常绿 / 连接黄 / 异常红），底部渐变控制栏含播放/暂停/快进退/进度/全屏。

## 功能特性

| 功能 | 说明 |
|---|---|
| 自定义直播源 | 支持 M3U/M3U8 订阅地址，或直接粘贴播放列表文本；同名频道的多条线路自动聚合为备用源 |
| 手机扫码输入 | TV 端显示二维码，手机扫码打开网页即可粘贴 M3U 地址提交（反向扫码，TV 无需摄像头） |
| 手动输入 | 电视端对话框内直接粘贴订阅地址或文本 |
| 最新播放器 | AndroidX Media3 ExoPlayer，支持 HLS / LL-HLS 低延时直播、自适应码率 |
| 自动测速 | 并发测量每条线路 TTFB 与下载带宽；m3u8 会自动取首个分片测速，更贴近真实播放 |
| 自动选优 | 加权评分（延时 70% + 带宽 30%），按最优线路起播 |
| 卡顿检测 | AnalyticsListener 监听缓冲与丢帧，滑动时间窗内超阈值判定为卡顿 |
| 自动切源 | 卡顿或播放错误时，按评分顺序切到下一条线路并定位续播，带冷却期防止来回抖动 |

## 编译运行

环境：Android Studio Ladybug 或更高（AGP 8.5+、JDK 17、compileSdk 35）。

1. 用 Android Studio 打开本目录（`IPTVPlayer/`）。
2. 首次打开会提示下载 Gradle 8.7 与依赖，等待同步完成。
3. 连接 Android TV / 电视盒子（开启开发者模式与 USB 调试），或直接用模拟器。
4. 点击 Run 安装到设备。

> 也可以用命令行：`./gradlew assembleDebug`，产物在 `app/build/outputs/apk/debug/`。

## GitHub 云端编译（无需本地环境）

源码仓库 `IPTVPlayer-Source`（**私密**）内置 GitHub Actions 工作流 `.github/workflows/build.yml`，push 后自动在云端编译；编译出的 APK 与 `version.json` 发布到公开仓库 `IPTVPlayer-Releases-New`，供 App 在线更新读取。源码不公开，仅安装包公开。

**发布流程（维护者）：**

1. 把本工程全部文件（含隐藏的 `.github` 目录）推送到私密仓库 `20211109/IPTVPlayer-Source` 的 `main` 分支。
2. push 会触发编译，但**只有推送 `v*` 标签才会发布到公开仓库**：
   ```bash
   git tag v1.34 && git push origin v1.34
   ```
   打 tag 后 CI 自动：① 编译 debug/release 两个包 → ② 把 release 包发布到公开仓库的 Release → ③ 生成 `version.json`（含版本号、下载直链、SHA-256）→ ④ 清理公开仓库的旧 Release，只保留最新版以节省存储配额。
3. 用户在 App 内「关于 → 检查更新」即可从公开仓库拉取新版本。

> **存储配额说明**：公开仓库长期只保留一个 Release + 一份 release APK；Artifacts 保留期 1 天。这样不会随版本迭代无限占用 GitHub 存储。
> **签名**：CI 通过 Secrets 中的 `RELEASE_TOKEN`（有 repo 权限的 PAT）跨仓库发布；签名密钥走仓库内 `app/ci/`（私密仓库不外泄）。

> 本地也有 `gradlew`（已附带 wrapper），有 JDK 17 时可直接 `./gradlew assembleRelease`。

## 使用流程

1. 首次启动列表为空，点击右上角 **配置直播源**。
2. 电视屏幕出现二维码与局域网地址 `http://<电视IP>:8899`：
   - **手机扫码**：确保手机与电视在同一 WiFi，扫码后在网页粘贴 M3U 地址或文本，点「发送到电视」；
   - **手动输入**：在电视对话框里粘贴内容，点「确定」。
3. 加载后自动并发测速，列表显示每个频道的最优延时。
4. 用遥控器选中频道回车播放；播放卡顿会自动切换到评分次优线路并续播。
5. 随时可点 **一键测速** 重新评估所有线路。

## 项目结构

```
app/src/main/java/com/iptv/player/
├── data/       Models(频道/线路)、M3uParser(播放列表解析)、SourceRepository(本地持久化)
├── speed/      SpeedTester(并发测速)、SourceScorer(加权评分)
├── player/     PlayerManager(ExoPlayer 播放/切源/续播)、StallDetector(卡顿检测)
├── config/     PairingServer(局域网配对服务)、QrCodeGenerator、PlaylistLoader、NetworkUtils
└── ui/         MainActivity(主逻辑)、ChannelAdapter、PairingDialog
```

## 可调参数

| 参数 | 位置 | 默认值 | 含义 |
|---|---|---|---|
| 卡顿判定窗口 | `StallDetector.WINDOW_MS` | 12000ms | 统计卡顿的时间窗 |
| 卡顿触发次数 | `StallDetector.STALL_COUNT_THRESHOLD` | 3 | 窗口内卡顿几次后切源 |
| 切换冷却期 | `PlayerManager.SWITCH_COOLDOWN_MS` | 8000ms | 切源后多久内不再切 |
| 缓冲区大小 | `PlayerManager` 的 `DefaultLoadControl` | 5–15s | 缓冲区间 |
| 测速评分权重 | `SourceScorer.score()` | 延时70%/带宽30% | 评分公式 |

## 注意事项

- 直播源需遵守当地法律法规，请仅使用合法授权的订阅源。
- 测速期间会并发请求所有线路，线路很多时可能占用一定带宽。
- 部分源有防盗链/UA 校验，可在 `OkHttpDataSource` / `OkHttpClient` 中补充对应 Header。
- 若某频道只有一条线路，卡顿切换将重置后重试该线路。

# KunBox 项目全面审查报告

日期：2026-06-08

## 审查范围

- Android 主工程：`app/src/main/java/com/kunk/singbox/`
- 核心链路：UI、ViewModel、Repository、IPC、VPN Service、libbox 封装、后台 Worker
- 协议与配置：sing-box JSON、Clash YAML、VMess、VLESS、Trojan、Shadowsocks、HTTPUpgrade、selector、urltest
- 数据层：Room、设置存储、配置文件写入、订阅导入更新、规则集更新、数据导入导出
- 构建与发布：Gradle、R8、ProGuard、Manifest、资源、内核同步脚本、`libbox.aar`
- 测试覆盖：协议解析、配置修复、订阅任务、per-app 清理、路由组选择、IPC 状态、测速路径

## 官方资料核对

- sing-box 最新稳定版：`v1.13.13`，GitHub Release 发布时间 `2026-06-04T13:32:10Z`。
  - 来源：<https://api.github.com/repos/SagerNet/sing-box/releases/latest>
  - Release：<https://github.com/SagerNet/sing-box/releases/tag/v1.13.13>
- sing-box selector / urltest 出站字段边界已核对。
  - selector：<https://sing-box.sagernet.org/configuration/outbound/selector/>
  - urltest：<https://sing-box.sagernet.org/configuration/outbound/urltest/>
- sing-box V2Ray Transport HTTPUpgrade 字段已核对，`Host` 使用 header 表达，路径使用 `path`。
  - 来源：<https://sing-box.sagernet.org/configuration/shared/v2ray-transport/>
- Android `BroadcastReceiver.goAsync()` 异步处理后需要结束 `PendingResult`。
  - 来源：<https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()>
- Android WorkManager 周期任务最小间隔为 15 分钟。
  - 来源：<https://developer.android.com/reference/androidx/work/PeriodicWorkRequest#MIN_PERIODIC_INTERVAL_MILLIS>
- Android `ConnectivityManager.bindProcessToNetwork()` 为进程级网络绑定。
  - 来源：<https://developer.android.com/reference/android/net/ConnectivityManager#bindProcessToNetwork(android.net.Network)>

## 内核处理

- 已运行 `.kernel-sync-local/sync-kernel.ps1`。
- `app/libs/libbox.aar` 已更新，当前文件大小 `77350015` 字节。
- 上游 sing-box 工作目录：`.kernel-sync-local/upstream-sing-box`
- 当前上游标签：`v1.13.13-dirty`
- 当前上游提交：`78b2e12f`
- 同步脚本已修正版本排序 fallback，避免 `v1.13.8` 排在 `v1.13.11` 之后造成误判。
- 同步脚本 NDK 默认优先级已对齐项目文档中的 `29.0.14206865`。

## 已修复问题

### 构建与发布

- `app/build.gradle.kts` 增加无 Git 环境 fallback，避免构建环境没有 Git 时版本号生成失败。
- `app/build.gradle.kts` 统一 Compose BOM 口径，main 与 androidTest 复用同一版本。
- `app/proguard-rules.pro` 修正 `SingBoxIpcService` 包名 keep 规则。
- `app/proguard-rules.pro` 增加 Worker keep 规则，降低 release / R8 下后台任务构造器被裁剪风险。
- `gradle.properties` 修复 UTF-8 中文注释乱码。
- `app/src/main/res/values-en/strings.xml` 补齐英文资源缺失项。

### 内核与测速

- `app/src/main/java/com/kunk/singbox/core/SingBoxCore.kt` 按 sing-box 1.13 口径重建测速 DNS 配置。
- 测速配置改用用户配置的本地 DNS，避免机场节点域名解析绕过用户 DNS。
- 节点域名出站增加本地 `domain_resolver`，IP 节点保留原样。
- 本地 HTTP proxy 测速中的 `bindProcessToNetwork()` 增加 `processNetworkBindMutex` 串行化，避免并发测速互相覆盖全进程网络绑定。
- 批量测速复用同一测速 DNS 配置，避免单节点与批量节点测速解析路径不一致。
- 批量测速在过滤运行态不支持的 outbound 后，按过滤后的 outbound 分配端口并映射 tag，避免延迟结果写到错误节点。
- `app/src/test/java/com/kunk/singbox/core/SingBoxCoreLatencyPathTest.kt` 增加测速 DNS、域名 resolver、入口路径、进程网络绑定串行化回归测试。

### 协议解析与配置生成

- `NodeLinkParser` 增加 VMess / VLESS / Trojan 的 `httpupgrade` 支持。
- `NodeLinkParser` 修正 VLESS 在 `httpupgrade` 下的 TLS 推断。
- `NodeLinkParser` 按 sing-box HTTPUpgrade 字段写入 `headers.Host` 和 `path`。
- `CommonParsers.SingBoxParser` 导入完整 sing-box JSON 时保留完整配置，只规范化 outbounds，避免 DNS、route、experimental 被裁掉。
- `OutboundFixer` 对 Shadowsocks 运行态保留 `tls` 和 `transport`，修复 v2ray-plugin 等传输字段丢失。
- `NodeExtractor` 识别 `url-test` 别名。
- `NodeLinkExporter` 导出 Shadowsocks 时支持 SIP002 `plugin=` 参数，并通过解析器回读测试。

### 订阅、规则集与 Worker

- `SubscriptionAutoUpdateWorker` 将自动更新间隔 `1..14` 分钟归一到 15 分钟，`<=0` 禁用。
- `SubscriptionAutoUpdateWorker` 顶层异常 retry 增加尝试次数上限。
- `ConfigRepository` 导入订阅和更新 profile metadata 时保存归一化后的自动更新间隔。
- `RuleSetUpdateWorker` 后台更新允许网络下载缺失远程规则集。
- `RuleSetAutoUpdateWorker` 顶层异常 retry 增加尝试次数上限。
- `RuleSetAutoUpdateWorker` 增加 15 分钟周期归一化，`SettingsRepository` 保存规则集自动更新间隔时同步归一化。
- 新增 `SubscriptionAutoUpdateWorkerTest` 覆盖 WorkManager 15 分钟规则和异常 retry。
- 新增 `RuleSetAutoUpdateWorkerTest` 覆盖规则集自动更新的 WorkManager 15 分钟规则。

### VPN 生命周期与 IPC

- `SingBoxService.onDestroy()` 在非手动停止且 VPN 仍运行时保留运行态，便于 keepalive 恢复。
- `ShutdownManager` 只在最终停止服务时取消 `VpnKeepaliveWorker`。
- `VpnKeepaliveWorker` 恢复路径改用实际运行配置 `running_config.json`。
- `StartupManager` 将 `VpnService.prepare()` 权限检查前移，避免权限缺失分支泄漏锁和广播接收器。
- `SingBoxRemote` 增加 `bindingInProgress`，避免重复 bind 和重连风暴。
- `SingBoxRemote` 重连条件提取为 `canAttemptReconnect()`，detekt 通过。
- `RouteGroupSelector` 去除 `!!`，修正 `#AUTO` 组内层 auto group RPC tag 选择。

### 数据与文件原子性

- `ConfigRepository.writeConfigFileOrThrow()` 改为 tmp + bak 写入，失败时恢复旧配置。
- `ConfigRepository.writeConfigFileOrThrow()` 只在本次创建备份后恢复 `.bak`，避免旧残留备份污染当前配置。
- 订阅更新失败后恢复旧配置文本、cache 和节点数据。
- 删除 profile 时清理该 profile 的 DNS 预解析记录。
- `DataExportRepository` 导入覆盖失败时恢复旧配置文件。
- `DataExportRepository` 回滚快照缺配置文件时中止导入。
- `DataExportRepository` 导入失败回滚按 `importSettings` / `importProfiles` 范围执行，避免设置失败回滚 profile 或 profile 失败覆盖设置。
- `DataExportRepository` 修正 `importRules=false`，跳过 customRules、ruleSets、appRules、appGroups 和规则集自动更新设置。

### per-app、UI 与工具类

- `PackageRemovedReceiver` 使用 `goAsync()`，finally 调用 `finish()`。
- 卸载包时刷新安装应用列表，并清理 allowlist、blocklist、appRules、appGroups 中的包名。
- `PerAppPackageSync` 增加 per-app 配置清理函数和测试。
- `AddNodeDialog`、`SelectProfileDialog` 在 profiles 异步加载后自动选择首个有效 profile。
- `NodesViewModel` 批量测速临时排序恢复移入 `finally`。
- `TrafficStatsViewModel` 磁盘读写切到 `Dispatchers.IO`。
- `ProfilesScreen` 去除 `draggingItemIndex!!`。
- `DnsResolver` DoH 响应解析异常转为失败结果，避免协程悬挂。
- `DnsResolveStore.kt`、`Settings.kt`、`SettingsEntity.kt` 修复中文乱码。
- `AppLogger` 统一日志可写判定。
- `VpnTileService` 使用兼容的 receiver 注册和 Android 14 `startActivityAndCollapse(PendingIntent)` 路径。
- `AndroidManifest.xml` 将 camera feature 标记为 `required=false`。

## 未直接改动项

- `AppDatabase.allowMainThreadQueries()` 仍保留。当前 `SettingsStore` 初始化和若干同步 DAO 路径依赖同步读取，直接移除会导致启动态设置读取失败或主线程崩溃。该项已记录为架构风险。
- `android:usesCleartextTraffic="true"` 仍保留。订阅源、局域网地址和用户自填 HTTP 地址存在兼容需求，直接关闭会改变现有导入与更新行为。该项已记录为安全风险。
- `QUERY_ALL_PACKAGES`、前台服务 `specialUse` / `dataSync` 属于上架政策项。代码已能构建，发布合规性需要按发布渠道申报口径核对。
- Clash YAML 导入当前重点转换 `proxies` / `proxy-groups`。如果验收口径是完整 Clash config 导入，DNS 与 rules 忽略属于功能边界风险。
- Room migration 已保留迁移代码，尚未新增专门迁移测试矩阵。

## 验证记录

- `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.core.SingBoxCoreLatencyPathTest"`：通过。
- `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.ipc.SingBoxRemoteStateTest" --tests "com.kunk.singbox.ipc.SingBoxIpcHubStateTest" --tests "com.kunk.singbox.ipc.VpnStateStoreTest" --tests "com.kunk.singbox.core.SingBoxCoreLatencyPathTest"`：通过。
- `.\gradlew testDebugUnitTest`：通过。
- `.\gradlew detekt`：首次发现 `SingBoxRemote.kt` 两处复杂条件，已拆分后复跑通过。
- `.\gradlew assembleDebug`：通过。
- `.\gradlew assembleRelease`：通过。
- 二次审查追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.core.SingBoxCoreLatencyPathTest" --tests "com.kunk.singbox.service.SubscriptionAutoUpdateWorkerTest" --tests "com.kunk.singbox.service.RuleSetAutoUpdateWorkerTest" --tests "com.kunk.singbox.repository.store.SettingsStoreTest" --tests "com.kunk.singbox.service.tun.VpnTunAddressPlanTest"`：通过。
- 二次审查追加 `.\gradlew testDebugUnitTest`：通过。
- 二次审查追加 `.\gradlew detekt`：首次发现 `SettingsStore.kt:110` 行过长，拆分后复跑通过。
- 二次审查追加 `.\gradlew assembleDebug`：通过。
- 二次审查追加 `.\gradlew assembleRelease`：通过。
- 二次审查追加 `git diff --check`：通过，仅有 LF/CRLF 提示。

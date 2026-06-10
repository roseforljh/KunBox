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
- sing-box V2Ray Transport HTTPUpgrade 字段已核对，`host` 是独立字符串字段，路径使用 `path`，额外头使用 `headers`。
  - 来源：<https://sing-box.sagernet.org/configuration/shared/v2ray-transport/>
  - 源码：<https://github.com/SagerNet/sing-box/blob/v1.13.13/option/v2ray_transport.go>
- Android `BroadcastReceiver.goAsync()` 异步处理后需要结束 `PendingResult`。
  - 来源：<https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()>
- Android WorkManager 周期任务最小间隔为 15 分钟。
  - 来源：<https://developer.android.com/reference/androidx/work/PeriodicWorkRequest#MIN_PERIODIC_INTERVAL_MILLIS>
- Android `ConnectivityManager.bindProcessToNetwork()` 为进程级网络绑定。
  - 来源：<https://developer.android.com/reference/android/net/ConnectivityManager#bindProcessToNetwork(android.net.Network)>
- sing-box DNS rule `outbound` 匹配项已在 `1.12.0` 废弃，并将在 `1.14.0` 移除，官方迁移方向是 `domain_resolver` 或 `route.default_domain_resolver`。
  - DNS Rule：<https://sing-box.sagernet.org/configuration/dns/rule/>
  - Dial Fields：<https://sing-box.sagernet.org/configuration/shared/dial/>
  - Migration：<https://sing-box.sagernet.org/migration/#migrate-outbound-dns-rule-items-to-domain-resolver>
- sing-box WireGuard outbound `local_address` 为必填字段，节点链接导入必须生成该字段才是有效 outbound。
  - 来源：<https://sing-box.sagernet.org/configuration/outbound/wireguard/>
- sing-box 官方 Android 客户端 `LocalDNSTransport` 实现会调用 Android `DnsResolver` / `Network.getAllByName()`，成功后通过 `ctx.success()` 或 `ctx.rawSuccess()` 回传结果。
  - 来源：<https://github.com/SagerNet/sing-box-for-android/blob/main/app/src/main/java/io/nekohasekai/sfa/bg/LocalResolver.kt>

## 内核处理

- 已运行 `.kernel-sync-local/sync-kernel.ps1`。
- `app/libs/libbox.aar` 已更新，当前文件大小 `77350015` 字节。
- 上游 sing-box 工作目录：`.kernel-sync-local/upstream-sing-box`
- 当前上游标签：`v1.13.13-dirty`
- 当前上游提交：`78b2e12f`
- 同步脚本已修正版本排序 fallback，避免 `v1.13.8` 排在 `v1.13.11` 之后造成误判。
- 同步脚本 NDK 默认优先级已对齐项目文档中的 `29.0.14206865`。
- 2026-06-09 追加核验：重新运行 `.kernel-sync-local/sync-kernel.ps1`，脚本解析最新稳定版为 `v1.13.13`，使用精确补丁 `kunbox-v1.13.13.patch`，完成 AAR 方法校验、替换、`assembleDebug`、`testDebugUnitTest`、`detekt`。
- 2026-06-09 追加核验：新构建 `app/libs/libbox.aar` 与当前已跟踪 AAR 无 git 内容差异，说明提交中的 AAR 已是同一份最新构建产物。

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

- `ConfigRepository` 修复 DNS 覆写 rules 对节点域名解析不完全生效的问题：域名匹配、catch-all、`outbound:any`、指定 outbound tag 的覆写规则都会同步映射到节点 `domain_resolver`。
- `ConfigRepository` 修复 `dnsPreResolve` 与 DNS 覆写冲突：当覆写规则应接管某个节点域名解析时，启动期不再先用本地 DNS 预解析成 IP，避免运行期 rules 失去匹配对象。
- `ConfigRepository` 保留 `geosite`、`rule_set`、`inbound`、`package_name`、`user_id` 等无法直接归约为单节点域名 resolver 的规则边界，避免把上下文相关 DNS rule 误应用到节点服务器域名解析。
- `DnsRule` 对 `domain`、`domain_suffix`、`domain_keyword`、`domain_regex`、`geosite`、`rule_set`、`query_type`、`inbound`、`package_name` 增加字符串或数组兼容解析，避免 sing-box 常见单字符串写法导致 DNS 覆写 JSON 整段解析失败。
- `NodeLinkParser` 增加 VMess / VLESS / Trojan 的 `httpupgrade` 支持。
- `NodeLinkParser` 修正 VLESS 在 `httpupgrade` 下的 TLS 推断。
- 对照 sing-box v1.13.13 `V2RayHTTPUpgradeOptions`，修正 `httpupgrade.host` 为独立字符串字段，避免误写 `headers.Host` 后运行态配置缺少主 Host。
- `TransportConfig.host` 增加单项字符串、多项数组的 Gson 适配，兼容 HTTP/XHTTP 的列表字段和 HTTPUpgrade 的字符串字段。
- `ClashYamlParser` 的 `v2ray-http-upgrade` 分支将 Host 迁入 `transport.host`，自定义 headers 保留但去除重复 Host。
- `ClashYamlParser` 解析 `proxy-groups` 时归一化 Clash 内建引用：未被同名 proxy/group 占用的 `DIRECT` 转为 sing-box 运行态 `direct`，`REJECT`、`REJECT-DROP`、`PASS`、`GLOBAL` 等非 outbound tag 从 selector/urltest 引用中移除，避免直连选项被误删或无效 tag 写入配置。
- `OutboundFixer` 对旧库中已保存的 HTTPUpgrade `headers.Host` 做运行态归一化，迁入 `transport.host` 并同步修正 TLS SNI。
- `CommonParsers.SingBoxParser` 导入完整 sing-box JSON 时保留完整配置，只规范化 outbounds，避免 DNS、route、experimental 被裁掉。
- `OutboundFixer` 对 Shadowsocks 运行态保留 `tls` 和 `transport`，修复 v2ray-plugin 等传输字段丢失。
- `NodeExtractor` 识别 `url-test` 别名。
- `NodeLinkExporter` 导出 Shadowsocks 时支持 SIP002 `plugin=` 参数，并通过解析器回读测试。
- `NodeLinkExporter` 导出 HTTPUpgrade 时保留 `path` 和 `host`，避免导出链接丢失传输参数。
- `NodeLinkExporter` 导出 VMess 时保留 `alter_id` 为分享链接中的 `aid`，避免旧式 VMess 节点被导出成 `aid=0`。
- `NodeLinkExporter` 导出 Base64 优先使用 JVM Base64，Android Base64 作为兜底，避免 JVM 单测中 `android.util.Base64` 默认值导致导出结果不可验证。
- `Base64Parser` 先使用 JVM Base64 并补齐 padding，Android Base64 仅作兜底，避免 JVM 单测和无 padding 订阅解析失败。
- `Base64Parser` 提取聊天或 Markdown 文本中的节点链接时裁剪尾部右括号、句号和引号，避免 `host`、`sni` 等 query 参数被标点污染。
- `NodeLinkParser` 修复 `wireguard://` 链接导入：解析 `address` / `local_address` / `ip` 到 sing-box 必填 `local_address`，缺少 private key、public key、server 或 local address 时拒绝导入，避免生成运行期必失败的 WireGuard outbound。
- `ConfigRepository` 和 `RuleSetRepository` 的原子写 / 下载临时文件改为同目录唯一临时文件，避免并发写同一目标时共用固定 `.tmp` 路径互相覆盖或失败。

### 订阅、规则集与 Worker

- `SubscriptionAutoUpdateWorker` 将自动更新间隔 `1..14` 分钟归一到 15 分钟，`<=0` 禁用。
- `SubscriptionAutoUpdateWorker` 顶层异常 retry 增加尝试次数上限。
- `ConfigRepository` 导入订阅和更新 profile metadata 时保存归一化后的自动更新间隔。
- `RuleSetUpdateWorker` 后台更新允许网络下载缺失远程规则集。
- `RuleSetAutoUpdateWorker` 顶层异常 retry 增加尝试次数上限。
- `RuleSetAutoUpdateWorker` 增加 15 分钟周期归一化，`SettingsRepository` 保存规则集自动更新间隔时同步归一化。
- `RuleSetRepository` 删除与 `directClient` 属性 getter 冲突的同名私有函数，修复 Kotlin JVM 签名冲突。
- 新增 `SubscriptionAutoUpdateWorkerTest` 覆盖 WorkManager 15 分钟规则和异常 retry。
- 新增 `RuleSetAutoUpdateWorkerTest` 覆盖规则集自动更新的 WorkManager 15 分钟规则。

### VPN 生命周期与 IPC

- `SingBoxService.onDestroy()` 在非手动停止且 VPN 仍运行时保留运行态，便于 keepalive 恢复。
- `ShutdownManager` 只在最终停止服务时取消 `VpnKeepaliveWorker`。
- `VpnKeepaliveWorker` 恢复路径改用实际运行配置 `running_config.json`。
- `VpnKeepaliveWorker` 在后台进程死亡后恢复前先校验 `running_config.json` 存在、可读且非空；缺失时清理 stale runtime state 和 core mode，避免周期任务反复拉起必然失败的服务。
- `StartupManager` 将 `VpnService.prepare()` 权限检查前移，避免权限缺失分支泄漏锁和广播接收器。
- `SingBoxRemote` 增加 `bindingInProgress`，避免重复 bind 和重连风暴。
- `SingBoxRemote` 重连条件提取为 `canAttemptReconnect()`，detekt 通过。
- `RouteGroupSelector` 去除 `!!`，修正 `#AUTO` 组内层 auto group RPC tag 选择。
- `SingBoxService` 和 `ProxyOnlyService` 在无配置路径启动且配置生成失败、Proxy-only 配置文件缺失时立即清理 `vpn_pending=starting`、运行态和 core mode，避免 Tile、IPC 和 keepalive 长时间保留错误启动态。
- `VpnTileService.updateTile()` 清理持久运行态前统一检查 pending、服务绑定状态和系统 VPN transport，避免系统短暂未报告 VPN transport 时把正在运行的 VPN 写成停止态。
- `SingBoxIpcService` 移除对自身本地 Binder 的 `linkToDeath()`，避免服务端本地监听失败或误触发时调用 `SingBoxIpcHub.onServiceBinderDied()` 清空运行态；远端死亡仍由 `SingBoxRemote` 监听。
- `LocalResolverImpl` 按官方 Android 客户端同类实现补齐平台 DNS 查询路径：Q+ 使用 Android `DnsResolver`，低版本使用 `Network.getAllByName()`，不再对所有 local DNS lookup 直接返回 SERVFAIL。

### 数据与文件原子性

- `ConfigRepository.writeConfigFileOrThrow()` 和 `generateConfigFile()` 改为同目录 tmp + NIO 原子替换写入，避免 profile 配置和 `running_config.json` 被半截写入；成功后不保留 `.bak`。
- 订阅更新失败后恢复旧配置文本、cache 和节点数据。
- 删除 profile 时清理该 profile 的 DNS 预解析记录。
- 手动创建节点和添加单节点到已有 profile 时保留原 sing-box 配置中的 `dns`、`route`、`inbounds`、`experimental` 等字段，只替换 `outbounds`，避免用户自定义 DNS、路由和缓存配置被清空。
- `DataExportRepository` 导入覆盖失败时恢复旧配置文件。
- `DataExportRepository` 回滚快照缺配置文件时中止导入。
- `DataExportRepository` 导入失败回滚按 `importSettings` / `importProfiles` 范围执行，避免设置失败回滚 profile 或 profile 失败覆盖设置。
- `DataExportRepository` 修正 `importRules=false`，跳过 customRules、ruleSets、appRules、appGroups 和规则集自动更新设置。
- `NodeEntity.tags` 改用 Gson 序列化和反序列化，避免 tag 中包含逗号或引号时回读损坏。
- `NodeEntity`、`ProfileEntity` 增加 Room 查询索引，覆盖 `WHERE ... ORDER BY sortOrder` 的高频列表查询。
- `AppDatabase` 升级到 version 7，新增 `MIGRATION_6_7` 创建节点和 profile 复合索引。
- `RuleSetRepository` 对远程规则集缓存文件名做安全映射，阻断导入或手填 tag 中的路径穿越。
- `TrafficRepository` 增加统一状态锁和快照返回，避免高频流量写入、UI 查询、清空、重载并发时丢增量或暴露可变对象。
- `SettingsStore` 将设置读改写、StateFlow 更新和 Room 保存纳入同一互斥区，避免并发设置更新互相覆盖。

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
- `VpnTileService` 增加 tile refresh receiver 注册状态，`onStopListening()` 和 `onDestroy()` 成对反注册，避免重复注册和泄漏。
- `VpnTileService` 增加 stale pending 判定：无服务、无 VPN transport 且不在本地启动宽限内时清理 `starting/stopping`，避免服务启动失败后 QS Tile 卡在启动中。
- `RuleSetAutoUpdateWorker` 改用 `ConfigRepository.getInstance()`，避免 Worker 直接 new 出带协程 scope 和 executor 的额外仓库实例。
- `NodesViewModel`、`DashboardViewModel` 将节点列表筛选和排序切到 `Dispatchers.Default`，降低大量节点和频繁延迟刷新时的主线程卡顿。
- `MainActivity` 将初始 intent 与 `onNewIntent()` 统一送入事件流，修复 `singleTask` 前台状态下重复打开订阅 deep link 或节点切换快捷方式不响应的问题。
- `SingBoxRemote.ensureBound()` 在已绑定但 AIDL 调用失败时立即断开并重绑，修复前台恢复或 binder 半死状态下只打日志不重连的问题。
- `InstalledAppsRepository` 增加加载互斥锁，避免多个页面、卸载广播和刷新入口并发触发包列表扫描，造成重复 I/O 和加载状态互相覆盖。
- `ProfileEditorScreen` 从空保存按钮改为读取并保存指定 Custom profile 的真实 JSON 配置，路由增加 `profileId` 参数，保存后通过 `ConfigRepository` 原子写入并刷新节点缓存。
- `NodeDetailScreen` 节点传输编辑按 sing-box V2Ray Transport 当前字段归一化 Host：WebSocket 写入 `headers.Host`，HTTP / HTTPUpgrade / XHTTP 写入 `host`，切换协议时清理旧字段并保留非 Host 自定义头，避免编辑页显示空 Host 或保存出错误 schema。
- `DashboardViewModel.testAllNodesLatency()` 改为读取首页当前节点列表并调用批量测速 API，修复首页“测试所有节点延迟”实际只测当前节点的问题。
- `MainActivity` 自动连接延迟复查将运行态纳入 `LaunchedEffect` key，避免 1 秒延迟内运行态变化后仍用旧状态触发连接。
- `NodeDetailScreen` 加载 outbound 的 effect 监听节点列表恢复，避免冷启动仓库尚未恢复时页面一直停留在加载状态。
- `NodeDetailScreen` 创建和编辑节点时将实际写入切到 `Dispatchers.IO`，只有写入成功后才提示成功并返回；写入失败时显示失败消息，避免同步 I/O 卡主线程和失败误报成功。
- `DashboardScreen` 活跃节点名的记忆 key 纳入节点列表，避免节点列表刷新但 active id 不变时显示旧名称。
- `TrafficStatsViewModel` 为流量数据加载增加单一 Job 和周期守卫，避免快速切换周期时旧加载结果覆盖新 UI 状态。

## 未直接改动项

- `AppDatabase.allowMainThreadQueries()` 仍保留。当前 `SettingsStore` 初始化和若干同步 DAO 路径依赖同步读取，直接移除会导致启动态设置读取失败或主线程崩溃。该项已记录为架构风险。
- `android:usesCleartextTraffic="true"` 仍保留。订阅源、局域网地址和用户自填 HTTP 地址存在兼容需求，直接关闭会改变现有导入与更新行为。该项已记录为安全风险。
- `QUERY_ALL_PACKAGES`、前台服务 `specialUse` / `dataSync` 属于上架政策项。代码已能构建，发布合规性需要按发布渠道申报口径核对。
- Clash YAML 导入当前重点转换 `proxies` / `proxy-groups`。如果验收口径是完整 Clash config 导入，DNS 与 rules 忽略属于功能边界风险。
- Room migration 已保留迁移代码，尚未新增专门迁移测试矩阵。

## 验证记录

- 内核版本复核：2026-06-09 查阅 GitHub `SagerNet/sing-box` releases，稳定版 latest 为 `v1.13.13`，`v1.14.0-alpha.29` 为预发布；本地 `.kernel-sync-local/upstream-sing-box` 当前 tag 为 `v1.13.13`，`app/libs/libbox.aar` 已于 2026-06-09 更新，无需再次同步。
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
- 三次审查追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.RuleSetRepositoryTest" --tests "com.kunk.singbox.database.entity.NodeEntityTest"`：通过。
- 三次审查追加 `.\\.kernel-sync-local\\sync-kernel.ps1`：通过，脚本内完成 `assembleDebug`、`testDebugUnitTest`、`detekt`。
- 四次审查追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.AppShortcutsResourceTest" --tests "com.kunk.singbox.repository.InstalledAppsRepositorySourceTest"`：通过。
- 四次审查追加 `.\gradlew detekt`：首次发现 `MainActivity` 基线失效、`InstalledAppsRepository` 复杂度、`ProfileEditorScreen` 长方法和保存函数参数过多，拆分后复跑通过。
- 四次审查追加 `.\gradlew assembleDebug`：通过。
- 四次审查追加 `git diff --check`：通过，仅有 LF/CRLF 提示。
- IPC 修复追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.ipc.SingBoxRemoteStateTest"`：通过。
- IPC 修复追加 `.\gradlew detekt`：首次发现 `SingBoxRemote` 大类问题，将 ensureBound 决策 helper 移到同文件 top-level 后复跑通过。
- IPC 修复追加 `.\gradlew assembleDebug`：通过。
- 协议修复追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.utils.parser.NodeLinkParserTest" --tests "com.kunk.singbox.utils.ClashConfigParserTest" --tests "com.kunk.singbox.repository.config.NodeLinkExporterTest" --tests "com.kunk.singbox.model.ModelSerializationTest"`：通过。
- 协议修复追加 `.\gradlew detekt`：首次发现 `ClashYamlParser.parseTrojanTransport` 复杂度和 helper 参数过多，抽取共用构造函数后复跑通过。
- 协议修复追加 `.\gradlew assembleDebug`：通过。
- 订阅解析修复追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.utils.parser.SubscriptionManagerTest"`：先复现无 padding Base64 和 Markdown 右括号污染失败，修复后复跑通过。
- 订阅解析修复追加 `.\gradlew detekt`：首次发现 `CommonParsers.tryDecodeBase64` 返回点过多，合并返回路径后复跑通过。
- 订阅解析修复追加 `.\gradlew assembleDebug`：通过。
- 订阅解析修复追加 `.\gradlew testDebugUnitTest`：通过。
- HTTPUpgrade 旧数据兼容追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.config.OutboundFixerTest.testBuildForRuntimeMigratesHttpUpgradeHostHeader"`：先复现失败，修复后通过。
- HTTPUpgrade 旧数据兼容追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.config.OutboundFixerTest" --tests "com.kunk.singbox.utils.parser.NodeLinkParserTest" --tests "com.kunk.singbox.model.ModelSerializationTest"`：通过。
- HTTPUpgrade 旧数据兼容追加 `.\gradlew detekt`：通过。
- HTTPUpgrade 旧数据兼容追加 `.\gradlew assembleDebug`：通过。
- 节点编辑 Host 归一化追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.ui.screens.NodeDetailScreenTransportTest"`：通过。
- 节点编辑 Host 归一化追加 `.\gradlew detekt`：通过。
- 节点编辑 Host 归一化追加 `.\gradlew assembleDebug`：通过。
- 手动添加节点保留 profile 设置追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.ConfigRepositoryTest.testBuildConfigWithOutboundsPreservesExistingProfileSettings"`：通过。
- 手动添加节点保留 profile 设置追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.ConfigRepositoryTest"`：通过。
- 手动添加节点保留 profile 设置追加 `.\gradlew detekt`：通过。
- 手动添加节点保留 profile 设置追加 `.\gradlew assembleDebug`：通过。
- Clash proxy-groups 内建引用归一化追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.utils.ClashConfigParserTest.testParseProxyGroupNormalizesClashBuiltinRefs" --tests "com.kunk.singbox.utils.ClashConfigParserTest.testParseProxyGroupKeepsProxyNamedDirect"`：通过。
- Clash proxy-groups 内建引用归一化追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.utils.ClashConfigParserTest"`：通过。
- Clash proxy-groups 内建引用归一化追加 `.\gradlew detekt --rerun-tasks`：通过。
- Clash proxy-groups 内建引用归一化追加 `.\gradlew assembleDebug`：通过。
- DNS 覆写修复追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.ConfigRepositoryTest.testDnsOverrideCatchAllRuleWinsOverLocalDefaultDomainResolver" --tests "com.kunk.singbox.repository.ConfigRepositoryTest.testDnsOverrideOutboundAnyRuleWinsOverLocalDefaultDomainResolver" --tests "com.kunk.singbox.repository.ConfigRepositoryTest.testDnsOverrideSpecificOutboundRuleOnlyAppliesMatchingOutbound" --tests "com.kunk.singbox.repository.ConfigRepositoryTest.testDnsOverrideCatchAllRuleSkipsProfileDnsPreResolve" --tests "com.kunk.singbox.repository.ConfigRepositoryTest.testDnsOverrideOutboundAnyRuleSkipsProfileDnsPreResolve"`：先复现 3 个 resolver 断言失败，修复后通过。
- DNS 覆写解析兼容追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.ConfigRepositoryTest.testDnsOverrideRuleStringFieldsParseAsLists"`：先复现 `JsonSyntaxException`，修复后通过。
- DNS 覆写模型兼容追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.ConfigRepositoryTest" --tests "com.kunk.singbox.model.ModelSerializationTest"`：通过。
- DNS 覆写修复追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.ConfigRepositoryTest"`：通过。
- DNS 覆写修复追加 `.\gradlew detekt`：通过。
- VPN/文件状态修复追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.service.VpnTileServiceStateTest"`：通过。
- VPN/文件状态修复追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.ConfigRepositoryTest.testAtomicTextWriteReplacesExistingFileAndCleansTempFiles" --tests "com.kunk.singbox.repository.ConfigRepositoryTest.testAtomicTextWriteKeepsExistingFileWhenTempWriteFails"`：通过。
- VPN/文件状态修复追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.ConfigRepositoryTest" --tests "com.kunk.singbox.ipc.SingBoxRemoteStateTest" --tests "com.kunk.singbox.service.VpnTileServiceStateTest" --tests "com.kunk.singbox.service.ProxyOnlyServiceStateTest" --tests "com.kunk.singbox.service.RecoveryLogicTest" --tests "com.kunk.singbox.repository.store.SettingsStoreTest" --tests "com.kunk.singbox.repository.DataExportRepositoryTest" --tests "com.kunk.singbox.repository.RuleSetRepositoryTest"`：通过。
- VPN/文件状态修复追加 `.\gradlew detekt --rerun-tasks`：先发现新增 helper 返回点和 `startVpn` 返回点问题，拆分后复跑通过。
- VPN/文件状态修复追加 `.\gradlew assembleDebug`：通过。
- VPN/文件状态修复追加 `git diff --check`：通过，仅有 LF/CRLF 提示。
- keepalive 恢复修复查阅 Android 官方文档：WorkManager 周期任务最小间隔 15 分钟且执行时间受系统调度影响；前台服务从后台启动和 `startForeground()` 时限受 Android 系统约束。
- keepalive 恢复修复追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.service.VpnKeepaliveWorkerTest"`：通过。
- keepalive 恢复修复追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.service.*"`：通过。
- keepalive 恢复修复追加 `.\gradlew detekt --rerun-tasks`：通过。
- keepalive 恢复修复追加 `.\gradlew assembleDebug`：通过。
- keepalive 恢复修复追加 `git diff --check`：通过，仅有 LF/CRLF 提示。
- UI/ViewModel 追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.viewmodel.DashboardViewModelSourceTest"`：先复现首页批量测速误走单节点 API，修复后通过。
- UI/ViewModel 追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.MainActivityAutoConnectSourceTest"`：先复现自动连接 effect key 缺运行态，修复后通过。
- UI/ViewModel 追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.ui.screens.NodeDetailScreenSourceTest"`：先复现节点详情页冷恢复不重试加载，修复后通过。
- UI/ViewModel 追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.ui.screens.DashboardActiveNodeSourceTest"`：先复现活跃节点名不随节点列表刷新，修复后通过。
- UI/ViewModel 追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.viewmodel.TrafficStatsViewModelSourceTest"`：先复现流量加载缺少旧任务取消和周期守卫，修复后通过。
- 协议导出追加 `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.config.NodeLinkExporterTest.exportVmessShouldPreserveAlterIdAsAid"`：先复现 VMess `aid` 未保留，修复后通过。
- 本轮追加 `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.viewmodel.DashboardViewModelSourceTest" --tests "com.kunk.singbox.MainActivityAutoConnectSourceTest" --tests "com.kunk.singbox.ui.screens.NodeDetailScreenSourceTest" --tests "com.kunk.singbox.ui.screens.DashboardActiveNodeSourceTest" --tests "com.kunk.singbox.viewmodel.TrafficStatsViewModelSourceTest" --tests "com.kunk.singbox.repository.config.NodeLinkExporterTest" --tests "com.kunk.singbox.repository.RuleSetRepositoryTest"`：通过。
- 本轮追加 `.\gradlew --no-daemon testDebugUnitTest`：通过。
- 本轮追加 `.\gradlew --no-daemon detekt`：首次发现 `SettingsRepository` 缩进和 `RuleSetsScreenSourceTest` 行宽问题，修复后通过。
- 本轮追加 `.\gradlew --no-daemon assembleDebug`：通过。
- 本轮追加 `git diff --check`：通过，仅有 LF/CRLF 提示。
- 本轮收尾追加 `.\gradlew.bat --no-daemon testDebugUnitTest`：通过。
- 本轮收尾追加 `.\gradlew.bat --no-daemon detekt`：首次发现 `ProxyOnlyService` 复杂条件、`AppNavigation` 历史长函数、`NodeLinkParser` WireGuard 解析复杂度、一个未用私有函数和 4 处测试行宽，修复后通过。
- 本轮收尾追加 `.\gradlew.bat --no-daemon assembleDebug`：通过。
- 本轮收尾追加 `git diff --check`：通过，仅有 LF/CRLF 提示。

## 2026-06-09 深度审查追加：导入导出、规则集、VPN 生命周期

### 数据导入导出与设置持久化

- 发现：只导入 settings 时仍调用全量 `exportAllData()` 创建 rollback snapshot。任一现有 profile 配置缺失或损坏，会导致 settings-only 导入被错误中止。
- 修复：`DataExportRepository.createImportSnapshot()` 在 `!importProfiles` 时创建 settings-only snapshot，不再要求 profile 配置完整。
- 发现：`importSettings()` 按字段逐个 setter 写入 Room，遗漏 `appLanguage`、`showNotificationSpeed`、`ipVersionMode`、`tunMtuAuto`、`fakeIpExcludeDomains`、`tcpKeepAliveEnabled`、`backgroundPowerSavingDelay` 等导出字段，并产生多次重启事件。
- 修复：新增 `SettingsRepository.replaceImportedSettings()`，一次性合并并持久化完整 `AppSettings`；`importRules=false` 时保留当前规则、规则集、应用规则和自动更新设置；导入失败会抛错触发 rollback。
- 发现：`SettingsStore.updateSettingsAndWait()` 不返回持久化结果，Room 写失败时调用方可能误报成功。
- 修复：`updateSettingsAndWait()` 返回 `Boolean`，失败时回滚内存态；导入设置路径据此判断失败。
- 发现：profile 导入和订阅更新失败回滚存在直接 `writeText()` 覆盖配置文件路径。
- 修复：复用 `ConfigRepository.writeTextFileAtomically()`，统一同目录临时文件加 NIO 原子替换语义。
- 发现：`ImportResult.PartialSuccess` 分支在当前“有错误即 rollback 并 Failed”语义下不可达。
- 修复：`DataExportRepository.importData()` 删除不可达构造路径，保留全量成功或全量失败语义。

### 规则集下载、校验与配置生成

- 官方依据：sing-box 文档中 rule-set 支持 `source` 与 `binary` 格式，source rule-set 是包含 `version`、`rules` 的 JSON；二进制 `.srs` 由 `sing-box rule-set compile/convert` 生成。
- 参考：[Route Rule Set](https://sing-box.sagernet.org/configuration/route/rule_set/)、[Source Format](https://sing-box.sagernet.org/configuration/rule-set/source-format/)、[Binary Format](https://sing-box.sagernet.org/configuration/rule-set/binary-format/)。
- 发现：`RuleSetAutoUpdateWorker` 从“已能生成配置的远程规则集”出发更新，缓存缺失或无效的启用远程规则集不会被下载修复。
- 修复：自动更新直接遍历 `settings.ruleSets.filter { enabled && REMOTE }`，配置有效性过滤只用于生成运行配置。
- 发现：source JSON 下载校验只看前 64 字节是否包含 `"rules"`，合法 JSON 在 metadata 后出现 rules 时会被误杀。
- 修复：source 下载校验改为 `JsonReader` 结构解析，确认顶层存在 `rules`。
- 发现：二进制规则集校验对非 HTML、非 JSON 文本默认放行，`429 Too Many Requests`、`rate limit exceeded` 等错误文本可能污染 `.srs` 缓存。
- 修复：binary 校验要求 `SRS` 魔数；文本错误响应和未识别文本默认拒绝。
- 发现：规则集替换先 delete 再 rename，崩溃窗口内会丢失最后可用缓存。
- 修复：`RuleSetRepository.replaceRuleSetFile()` 改用 `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)`，不支持原子移动时降级为同 API 替换。
- 发现：配置生成读路径会删除无效规则集缓存，可能误删最后可用缓存。
- 修复：`ConfigRepository.buildCustomRuleSets()` 只排除无效文件，不在配置生成阶段删除缓存；同时只把启用规则集写入 `route.rule_set`。
- 发现：无法识别的二进制规则集被默认当成 IP 规则集，tag 为 `ads/openai` 的域名二进制规则集不会生成 DNS rule-set 规则。
- 修复：无 tag 语义提示的二进制规则集类型保持 `UNKNOWN`，DNS 规则只跳过明确 `IP`。

### VPN 生命周期与 Keepalive

- 官方依据：Android 官方文档说明后台启动前台服务在 Android 12+ 受限制；WorkManager 周期任务最小 15 分钟且执行时间受系统调度影响。
- 参考：[Foreground service launch restrictions](https://developer.android.com/develop/background-work/services/fgs/launch)、[PeriodicWorkRequest](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest)。
- 发现：Keepalive 后台恢复遇到前台服务后台启动限制或连续失败后，会保留旧 `CoreMode`，后续可能反复恢复并让 UI/Tile 看到假运行态。
- 修复：`VpnKeepaliveWorker` 对后台 FGS 拒绝按异常类名识别，立即清 active/pending/runtime/mode 并写 lastError；连续恢复失败达到 retry 阈值后同样清 stale runtime。
- 发现：VPN 权限缺失路径只清 active/pending，不清 `CoreMode`。
- 修复：`StartupManager.handlePermissionRequired()` 清 transient runtime、置 `CoreMode.NONE`，并记录 `"VPN permission required"`。

### 本轮验证

- GitHub Releases API 只读核对：`SagerNet/sing-box` 最新正式版为 `v1.13.13`，发布时间 `2026-06-04T13:32:10Z`；本地 `.kernel-sync-local/upstream-sing-box` 为 `v1.13.13-dirty`，存在 `kunbox-v1.13.13.patch`，无需再次同步内核。
- `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.RuleSetRepositoryTest"`：通过，9 个测试、0 失败、0 错误。
- `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.SettingsRepositoryTest"`：通过。
- `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.ConfigRepositoryTest"`：通过。
- `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.service.RuleSetAutoUpdateWorkerTest"`：通过。
- `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.DataExportRepositoryTest"`：通过。
- `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.repository.store.SettingsStoreTest"`：通过。
- `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.service.VpnKeepaliveWorkerTest"`：通过。
- `.\gradlew testDebugUnitTest --tests "com.kunk.singbox.service.manager.StartupManagerTest"`：通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.repository.RuleSetRepositoryTest" --tests "com.kunk.singbox.repository.ConfigRepositoryTest" --tests "com.kunk.singbox.repository.SettingsRepositoryTest" --tests "com.kunk.singbox.repository.DataExportRepositoryTest" --tests "com.kunk.singbox.repository.store.SettingsStoreTest" --tests "com.kunk.singbox.service.RuleSetAutoUpdateWorkerTest" --tests "com.kunk.singbox.service.VpnKeepaliveWorkerTest" --tests "com.kunk.singbox.service.manager.StartupManagerTest"`：通过。
- `.\gradlew --no-daemon detekt`：首次发现 `VpnKeepaliveWorker.performKeepaliveCheck` 返回点过多，拆分后复跑通过。
- `.\gradlew --no-daemon assembleDebug`：通过。

## 2026-06-09 深度审查追加：网络请求、规则集刷新、proxy-only 启停、协议边界

### 发现与修复

- 发现：订阅更新使用 `response.body?.string()` 一次性读取响应，远端可返回超大 HTML/YAML/Base64，绕过本地导入大小限制并触发 OOM 风险。
- 修复：订阅响应增加 `Content-Length` 预判和 1 MiB 流式读取上限，超限立即中止。
- 发现：规则集 `forceUpdate=true` 仍被 24 小时过期判断限制，手动更新和自动更新可能显示成功但不发请求。
- 修复：`forceUpdate` 直接触发下载；强制刷新失败时不再用旧缓存掩盖为成功。
- 发现：规则集和版本检查在代理 client 存在时，代理失败直接返回失败，不再尝试 direct fallback。
- 修复：代理失败后继续 direct fallback；代理 OkHttp client 默认增加 `callTimeout`，避免长时间占用后台任务。
- 发现：App 更新通知在 Android 13+ 通知权限缺失时仍写入 `last_notified_version`，用户看不到通知但后续不再提醒。
- 修复：发送通知前检查 `POST_NOTIFICATIONS`，仅通知实际发出后记录已通知版本。
- 发现：`KernelHttpClient` 的 `timeoutMs / 1000` 会把小于 1000ms 的请求变成 0 秒，并且 kernel 请求未被总超时包住。
- 修复：毫秒超时向上取整且最小 1 秒，完整请求链路用 `withTimeout` 包住；OkHttp HTTP 非 2xx 的 `success` 与 `response.isSuccessful` 对齐。
- 发现：QS Tile 点击入口在 proxy-only 模式和停止路径也先请求 VPN 权限。
- 修复：停止路径优先执行停止；启动时仅 `tunEnabled=true` 才检查 VPN 权限。
- 发现：ProxyOnlyService 无 `configPath` 启动时先异步生成配置，可能超过 Android 前台服务 5 秒窗口。
- 修复：收到无配置 `ACTION_START` 后立即调用 `startForeground()`，再生成配置。
- 发现：ProxyOnlyService 端口长时间未释放时直接 `killProcess()`，可能绕过正常状态清理并留下残留通知/状态。
- 修复：改为设置明确错误、清启动态、正常 `stopSelf()`；停止清理阶段只记录端口未释放错误，不杀进程。
- 发现：Dashboard 在 `CoreMode.NONE` 时停止只发 VPN 服务停止，proxy-only 启动中点击停止会漏停 ProxyOnlyService。
- 修复：`CoreMode.NONE` 时同时向 ProxyOnlyService 和 SingBoxService 发送停止。
- 发现：TrafficRepository 统计持久化使用固定 `.tmp`、`renameTo/copyTo`，异常路径可能留下半写或固定临时文件冲突。
- 修复：改为同目录唯一临时文件加 `Files.move(REPLACE_EXISTING, ATOMIC_MOVE)`，Windows 原子移动失败时降级为同 API 替换。
- 发现：VLESS/Trojan 缺少必填字段硬校验，空 uuid/password/server 仍可能生成 Outbound。
- 修复：VLESS/Trojan 解析阶段校验 server、credential、端口范围，缺失直接拒绝。
- 发现：Clash `url-test` 的 `url` 原样导入，`file://`、loopback、私网地址可进入探测 URL。
- 修复：仅允许 http/https 且拒绝 localhost、loopback、link-local、site-local 字面 IP；非法值回落默认 `generate_204`。
- 发现：导入支持 `http/socks/ssh/wireguard`，导出器未覆盖这些协议，UI 导出返回 null。
- 修复：补齐 `http`、`socks`、`ssh`、`wireguard` 分享链接导出，并用现有解析器做回读测试。
- 发现：`VpnServiceManager` 仍读旧 SharedPreferences `tun_enabled`，快捷方式可能按旧配置启动错误服务。
- 修复：统一从 `SettingsRepository.settings.value.tunEnabled` 读取当前 TUN 模式。

### 官方文档核对

- Android 官方前台服务文档说明 `startForegroundService()` 后必须在短时间内调用 `startForeground()`，否则系统会抛出前台服务未及时启动异常。
- Android 官方通知权限文档说明 Android 13+ 非豁免通知需要 `POST_NOTIFICATIONS` 运行时权限。
- 参考：[Troubleshoot foreground services](https://developer.android.com/develop/background-work/services/fgs/troubleshooting)、[Notification runtime permission](https://developer.android.com/guide/topics/ui/notifiers/notification-permission)。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.repository.ConfigRepositoryTest" --tests "com.kunk.singbox.repository.RuleSetRepositoryTest" --tests "com.kunk.singbox.service.RuleSetAutoUpdateWorkerTest" --tests "com.kunk.singbox.utils.KernelHttpClientTest" --tests "com.kunk.singbox.utils.AppUpdateCheckerSourceTest" --tests "com.kunk.singbox.service.VpnTileServiceStateTest" --tests "com.kunk.singbox.service.ProxyOnlyServiceStateTest" --tests "com.kunk.singbox.manager.VpnServiceManagerTest" --tests "com.kunk.singbox.viewmodel.DashboardViewModelSourceTest" --tests "com.kunk.singbox.repository.TrafficRepositorySourceTest" --tests "com.kunk.singbox.utils.parser.NodeLinkParserTest" --tests "com.kunk.singbox.utils.ClashConfigParserTest" --tests "com.kunk.singbox.repository.config.NodeLinkExporterTest"`：通过。
- `.\gradlew --no-daemon detekt`：首次发现 `ConfigRepository` require、`ProxyOnlyService.onStartCommand`、`ClashYamlParser.sanitizeUrlTestUrl`、`NodeLinkParser.parseVLessLink`、`AppUpdateCheckerSourceTest` 行宽问题，修复后通过。
- `.\gradlew --no-daemon assembleDebug`：通过。
- `git diff --check`：通过，仅有 LF/CRLF 提示。

## 2026-06-09 深度审查追加：VPN 管理器与编辑草稿状态

### 发现与修复

- 发现：`VpnConnectionManager.toggleConnection()` 在读取 `tunEnabled` 前先检查系统 VPN。用户只启用 proxy-only 时，如果系统已有其他 VPN transport，会被错误拦截，无法启动本地代理核心。
- 修复：先读取 `SettingsRepository.settings.first()`，只有 `tunEnabled=true` 时才执行系统 VPN 冲突检查；proxy-only 路径不再受系统 VPN transport 影响。
- 发现：`VpnConnectionManager.stopVpnInternal()` 在 `CoreMode.NONE` 时只向 `SingBoxService` 发停止。服务启动中、状态恢复失败或跨进程状态丢失时，proxy-only 可能漏停。
- 修复：`CoreMode.NONE` 时同时向 `ProxyOnlyService` 和 `SingBoxService` 发送停止命令，和 Dashboard 停止语义保持一致。
- 发现：订阅输入弹窗中名称、URL、自动更新时间、DNS 预解析、DNS 服务器和 DNS 覆盖文本只使用 `remember`。设备旋转或 Activity 重建后，用户已输入内容会丢失。
- 修复：上述可保存草稿状态改为 `rememberSaveable`；临时下拉展开状态继续使用 `remember`。
- 发现：节点详情编辑页把整份 `editingOutbound` 放在 `remember` 中。复杂节点参数编辑到一半遇到旋转或重建会回退到原节点，用户未保存草稿丢失。
- 修复：增加 `Outbound` Gson `Saver`，用 `rememberSaveable(nodeId, createProtocol, saver = ...)` 保存节点编辑草稿，并在切换节点或创建协议时重置。
- 发现：添加节点弹窗和选择目标配置弹窗中的链接、新建配置名、创建/选择模式和选中配置只使用 `remember`，重建后丢失。
- 修复：这些用户草稿和目标选择状态改为 `rememberSaveable`。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.viewmodel.VpnConnectionManagerSourceTest"`：先复现 VPN 管理器两个边界问题，修复后通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.ui.screens.ProfilesScreenSourceTest"`：先复现订阅输入弹窗草稿未保存，修复后通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.ui.screens.NodeDetailScreenSourceTest"`：先复现节点详情页编辑草稿未保存，修复后通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.ui.components.AddNodeDialogSourceTest" --tests "com.kunk.singbox.ui.components.SelectProfileDialogSourceTest"`：先复现两个弹窗草稿未保存，修复后通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.viewmodel.VpnConnectionManagerSourceTest" --tests "com.kunk.singbox.ui.screens.ProfilesScreenSourceTest" --tests "com.kunk.singbox.ui.screens.NodeDetailScreenSourceTest" --tests "com.kunk.singbox.ui.components.AddNodeDialogSourceTest" --tests "com.kunk.singbox.ui.components.SelectProfileDialogSourceTest"`：通过。
- `.\gradlew --no-daemon detekt`：通过。
- `.\gradlew --no-daemon assembleDebug`：通过。
- `git diff --check`：通过，仅有 LF/CRLF 提示。

## 2026-06-09 深度审查追加：通用输入与规则集编辑草稿

### 发现与修复

- 发现：通用 `InputDialog` 使用 `remember` 保存输入文本。DNS、TUN、路由设置、剪贴板导入命名、搜索等多处弹窗在 Activity 重建后会丢失用户刚输入的文本。
- 修复：`InputDialog` 文本改为 `rememberSaveable(initialValue)`，初始值变化时重置，同一弹窗重建时保留草稿。
- 发现：`RuleSetEditorDialog` 的 tag、type、format、url、path 都使用 `remember`。编辑远程规则集 URL 或本地 path 时旋转会回到原值或空值。
- 修复：规则集编辑草稿改为 `rememberSaveable`，并在保存时对 tag、url、path 做 `trim()`，避免首尾空白进入配置生成链路。
- 发现：自定义配置弹窗使用 `remember` 保存配置名，并用 `mutableStateListOf` 保存已勾选节点。用户勾选多个订阅节点后遇到重建会清空选择。
- 修复：配置名改为 `rememberSaveable`；已选节点改成可保存的不可变字符串列表状态，通过 `updatedCustomSelection()` 替换更新。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.ui.components.CommonDialogsSourceTest" --tests "com.kunk.singbox.ui.screens.RuleSetsScreenSourceTest"`：先复现通用输入和规则集编辑草稿未保存，修复后通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.ui.screens.ProfilesScreenSourceTest"`：先复现自定义配置弹窗名称和节点选择未保存，修复后通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.ui.components.CommonDialogsSourceTest" --tests "com.kunk.singbox.ui.screens.RuleSetsScreenSourceTest" --tests "com.kunk.singbox.ui.screens.ProfilesScreenSourceTest"`：通过。
- `.\gradlew --no-daemon detekt`：通过。
- `.\gradlew --no-daemon assembleDebug`：通过。

## 2026-06-09 深度审查追加：配置编辑器大文本草稿

### 发现与修复

- 发现：`ProfileEditorScreen` 使用 `remember` 保存整份配置正文、加载状态和保存状态。Activity 重建会重新读取文件并覆盖未保存草稿。
- 风险：直接改成 `rememberSaveable` 会把接近 1 MiB 的 JSON 文本放入 saved state Bundle，容易触发系统事务大小限制。
- 修复：新增页面级 `ProfileEditorViewModel` 持有正文、加载状态、保存状态和已加载 profileId；重建后不重复加载同一 profile，避免覆盖未保存草稿，也避免大文本进入 Bundle。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.ui.screens.ProfileEditorScreenSourceTest"`：先复现配置编辑正文由 `remember` 持有，修复后通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.ui.screens.ProfileEditorScreenSourceTest" --tests "com.kunk.singbox.ui.components.CommonDialogsSourceTest" --tests "com.kunk.singbox.ui.screens.RuleSetsScreenSourceTest" --tests "com.kunk.singbox.ui.screens.ProfilesScreenSourceTest" --tests "com.kunk.singbox.ui.screens.NodeDetailScreenSourceTest" --tests "com.kunk.singbox.ui.components.AddNodeDialogSourceTest" --tests "com.kunk.singbox.ui.components.SelectProfileDialogSourceTest"`：通过。
- `.\gradlew --no-daemon detekt`：通过。
- `.\gradlew --no-daemon assembleDebug`：通过。

## 2026-06-09 深度审查追加：应用分流编辑草稿

### 发现与修复

- 发现：`AppRuleEditorDialog` 使用 `remember` 保存已选应用、出站模式和目标值。编辑单应用分流时重建会丢失已选 app 或目标节点/profile。
- 修复：已选应用拆成可保存的 packageName 和 appName；出站模式与目标值改为 `rememberSaveable`。
- 发现：`MultiAppSelectorDialog` 使用不可保存的 `Set<AppInfo>` 作为临时选择。应用组里批量选择多个 app 后重建会清空临时选择。
- 修复：临时选择改成可保存的字符串列表，展示和提交时再还原为 `AppInfo`。
- 发现：`AppGroupEditorDialog` 使用 `remember` 保存分组名、出站模式、目标值和 app 列表。编辑应用组时旋转会丢失分组配置草稿。
- 修复：分组名、出站模式、目标值改为 `rememberSaveable`；app 列表以可保存字符串列表保存，确认时还原为 `AppInfo`。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.ui.screens.AppRoutingComponentsSourceTest"`：先复现应用分流编辑草稿未保存，修复后通过。
- `.\gradlew --no-daemon detekt`：通过。
- `.\gradlew --no-daemon assembleDebug`：通过。

## 2026-06-09 深度审查追加：自定义规则与域名规则编辑草稿

### 发现与修复

- 发现：`CustomRuleEditorDialog` 使用 `remember` 保存规则名、规则类型、规则内容和出站目标。编辑过程中 Activity 重建会丢失草稿。
- 修复：自定义规则编辑字段改为 `rememberSaveable`；保存时对规则名和内容做 `trim()`。
- 发现：`DomainRuleEditorDialog` 使用 `remember` 保存域名内容、出站模式和目标值。用户选择节点/profile 后重建会丢失目标。
- 修复：域名规则内容、出站模式、目标值改为 `rememberSaveable`；继续复用已有保存前 `value.trim()` 清理逻辑。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.ui.screens.CustomRulesScreenSourceTest" --tests "com.kunk.singbox.ui.screens.DomainRulesScreenSourceTest"`：先复现两个规则编辑器草稿未保存，修复后通过。
- `.\gradlew --no-daemon detekt`：首次发现 `DomainRulesScreenSourceTest` 行宽问题，拆行后复跑通过。
- `.\gradlew --no-daemon assembleDebug`：通过。

## 2026-06-10 深度审查追加：生命周期前后台判定

### 发现与修复

- 官方核对：GitHub Releases 显示 sing-box 最新正式版仍为 `v1.13.13`；`v1.14.0-alpha.29` 为预发布。当前 `.kernel-sync-local/upstream-sing-box` 为 `v1.13.13-dirty`，本轮无需同步正式内核。
- 发现：`ScreenStateManager` 用单个 `onActivityStopped()` 把 app 标记为后台。多 Activity 切换、配置变化或新旧 Activity 交接时，旧 Activity stop 可能在新 Activity 已启动后发生，导致 VPN 进程误判前后台。
- 风险：前后台误判会影响恢复判定、省电逻辑和远端状态更新，出现用户仍在前台但后台计时/恢复逻辑被触发的情况。
- 修复：增加 started Activity 计数，只在计数从 0 到非 0 时标记前台，只在计数回到 0 时标记后台；`onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` 路径会清零计数并标记后台。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.service.manager.ScreenStateManagerTest"`：先复现缺少 Activity 计数辅助逻辑，修复后通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.service.manager.ScreenStateManagerTest" --tests "com.kunk.singbox.service.RecoveryLogicTest"`：通过。
- `.\gradlew --no-daemon detekt`：通过。
- `.\gradlew --no-daemon assembleDebug`：通过。

## 2026-06-10 深度审查追加：二维码图片解码内存

### 发现与修复

- 发现：`QrScannerActivity` 从相册读取二维码图片时，已做输入流关闭、尺寸预读和采样下缩，但解码后的 `Bitmap` 没有主动释放。大图或失败路径会等待 GC 回收，容易增加短时内存压力。
- 修复：相册二维码解码调用改成 `try/finally`，无论识别成功、失败或异常，都会执行 `bitmap.recycle()`。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.ui.scanner.QrScannerActivitySourceTest"`：先复现缺少 Bitmap 回收，修复后通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.service.manager.ScreenStateManagerTest" --tests "com.kunk.singbox.service.RecoveryLogicTest" --tests "com.kunk.singbox.ui.scanner.QrScannerActivitySourceTest"`：通过。
- `.\gradlew --no-daemon detekt`：通过。
- `.\gradlew --no-daemon assembleDebug`：通过。

## 2026-06-10 深度审查追加：应用包变更同步

### 发现与修复

- 排除：`InstalledAppsRepository` 在系统返回空应用列表时不会卡在 Loading；循环跳过后仍会写入空列表并置为 Loaded。
- 发现：`PackageRemovedReceiver` 对 `Intent.EXTRA_REPLACING=true` 的包变更一律跳过。应用升级时系统会发送替换卸载和替换安装事件，卸载事件不能清理用户分应用规则，但安装完成事件应该刷新已安装应用缓存。
- 风险：应用升级后 app 名称、图标、系统应用标记或可见状态变化时，应用分流选择页继续显示旧缓存，直到用户手动刷新或进程重建。
- 修复：`shouldReloadInstalledAppsForPackageChange` 增加 action 维度，普通安装刷新、普通卸载刷新并清理规则、替换卸载跳过、替换安装刷新缓存。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.repository.PerAppPackageSyncTest"`：通过。
- `.\gradlew --no-daemon detekt`：通过。

## 2026-06-10 深度审查追加：后台 WorkManager 调度

### 官方核对

- AndroidX WorkManager 2.8.0 起新增 `ExistingPeriodicWorkPolicy.UPDATE`，官方 release note 说明它比 `REPLACE` 更少侵入，不取消正在运行的 Worker，并保留原始 enqueue time。
- Android 官方自定义初始化文档要求禁用默认 initializer 后自行提供初始化配置；多进程应用若需要统一调度到单一进程，官方 release note 提供 `androidx.work:work-multiprocess` 和 `Configuration.Builder.setDefaultProcessName(String)` 路径。

### 发现与修复

- 发现：订阅配置禁用时，`ConfigRepository.toggleProfileEnabled()` 只改 `enabled` 并保存配置，未取消该 profile 已存在的周期更新任务；Worker 遇到禁用配置只返回 success，周期 Work 仍保留并继续唤醒。
- 修复：`toggleProfileEnabled()` 记录切换后的 profile。订阅配置启用且 interval 合法时重新 schedule；订阅配置禁用或 interval 为 0 时 cancel。`SubscriptionAutoUpdateWorker` 在发现 profile 已禁用时也取消自身。
- 发现：`VpnKeepaliveWorker.schedule()` 每次使用 `ExistingPeriodicWorkPolicy.REPLACE` 并设置 15 分钟初始延迟。App 启动、VPN 启动或服务恢复多次触发 schedule 时，会重建周期 Work 并刷新初始延迟，保活恢复检查可能长期被推迟。
- 修复：保活周期 Work 改用 `ExistingPeriodicWorkPolicy.UPDATE`，更新约束和配置时保留原 enqueue time，避免频繁重置首轮检查时间。
- 发现：`SingBoxApplication` 已实现 `Configuration.Provider` 且 Manifest 移除了默认 initializer，但 `onCreate()` 仍手动调用 `WorkManager.initialize()`。该逻辑会在主进程和 `:bg` 进程都提前初始化 WorkManager；多进程服务再调度 Work 时，会增加跨进程 SQLite 竞争和初始化口径不一致风险。
- 修复：移除手动初始化和初始化探测函数，按官方建议只保留 `Configuration.Provider` 懒初始化；配置中设置 `setDefaultProcessName(packageName)`，并新增 `androidx.work:work-multiprocess:2.9.0` 依赖，让调度统一到主进程口径。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.repository.PerAppPackageSyncTest" --tests "com.kunk.singbox.repository.ConfigRepositorySourceTest" --tests "com.kunk.singbox.service.SubscriptionAutoUpdateWorkerTest" --tests "com.kunk.singbox.service.VpnKeepaliveWorkerTest"`：通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.SingBoxApplicationSourceTest" --tests "com.kunk.singbox.service.VpnKeepaliveWorkerTest" --tests "com.kunk.singbox.service.SubscriptionAutoUpdateWorkerTest"`：通过。
- `.\gradlew --no-daemon detekt`：通过。
- `.\gradlew --no-daemon assembleDebug`：通过。

## 2026-06-10 深度审查追加：导入配置半落库

### 发现与修复

- 排除：`replaceImportedSettings()` 已通过 `buildImportedSettings()` 覆盖此前容易遗漏的设置字段；`importRules=false` 时会保留当前规则集、分应用规则和规则集自动更新设置。
- 发现：`ConfigRepository.importProfileDirectly()` 原顺序为先插入 Room、写入配置缓存，再解析节点。若导入配置里的 outbound 结构异常导致节点解析抛错，`DataExportRepository.importProfile()` 只能恢复配置文件，Room 记录、内存 profile 列表或配置缓存可能已经被污染。
- 风险：导入失败后界面可能出现一个半导入的 profile；它有数据库记录或缓存配置，但节点列表没有正确生成。再次导入或切换配置时，会出现重复、空节点或启动失败。
- 修复：调整 `importProfileDirectly()` 顺序，先对去重后的配置执行 `extractNodesFromConfigSync()`。只有节点解析成功后，才写入 Room、配置缓存、内存节点表和 profile 列表。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.repository.ConfigRepositorySourceTest" --tests "com.kunk.singbox.repository.DataExportRepositoryTest"`：通过。
- `.\gradlew --no-daemon detekt`：通过。
- `.\gradlew --no-daemon assembleDebug`：首次因沙箱无法访问用户级 Gradle wrapper 锁文件失败；提权重跑后通过。

## 2026-06-10 深度审查追加：导入结果语义冲突

### 发现与修复

- 发现：`DataExportRepository.importData()` 已采用全量事务语义，任一 profile 或 settings 导入失败都会执行 rollback 并返回 `ImportResult.Failed`。但模型、ViewModel 和导入弹窗仍保留 `PartialSuccess` 状态。
- 风险：生产路径永远不会返回局部成功，UI 却展示“部分导入成功”的死分支。后续维护者可能误以为导入支持部分提交，增加回滚逻辑和用户提示的语义冲突。
- 修复：删除不可达的 `ImportResult.PartialSuccess`、`ImportState.PartialSuccess` 和导入弹窗的局部成功分支，保留清晰的“全量成功 / 全量失败”结果语义。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.viewmodel.SettingsViewModelSourceTest" --tests "com.kunk.singbox.repository.DataExportRepositoryTest"`：首次因沙箱无法访问用户级 Gradle wrapper 锁文件失败；提权重跑后通过。
- `.\gradlew --no-daemon detekt`：首次因沙箱无法访问用户级 Gradle wrapper 锁文件失败；提权重跑后通过。
- `.\gradlew --no-daemon assembleDebug`：首次因沙箱无法访问用户级 Gradle wrapper 锁文件失败；提权重跑后通过。

## 2026-06-10 深度审查追加：启动主线程 I/O

### 发现与修复

- 发现：`SingBoxApplication.onCreate()` 主线程直接创建 `SettingsRepository`。`SettingsRepository` 初始化会创建 `SettingsStore`，`SettingsStore.init` 会调用 `settingsDao.getSettingsSync()` 同步读取 Room。生产库因为启用了 `allowMainThreadQueries()` 不会崩溃，但会把数据库读盘放进启动主线程。
- 发现：`cleanupOrphanedTempFiles()` 在 `onCreate()` 主线程扫描并删除 cache 目录里的临时文件，设备 I/O 慢或残留文件较多时会拖慢冷启动。
- 风险：冷启动、`:bg` 服务进程创建、快捷方式入口都会经历 Application 初始化。主线程同步 Room 读盘和文件扫描会放大启动卡顿，并掩盖后续同步 DAO 滥用。
- 修复：`SettingsRepository.getInstance()` 移入 `withContext(Dispatchers.IO)` 后再读取当前设置和注册日志开关监听；临时文件清理移入 `applicationScope.launch(Dispatchers.IO)`。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.SingBoxApplicationSourceTest" --tests "com.kunk.singbox.repository.ConfigRepositorySourceTest"`：通过。
- `.\gradlew --no-daemon detekt`：通过。
- `.\gradlew --no-daemon assembleDebug`：通过。

## 2026-06-10 深度审查追加：流量统计主线程计算

### 发现与修复

- 发现：`TrafficStatsViewModel` 构造时直接创建 `TrafficRepository`。`TrafficRepository.init` 会同步读取 `traffic_stats.json`、`traffic_daily.json`，并执行月份检查和旧记录清理；ViewModel 在主线程创建时会把这些 I/O 带到 UI 线程。
- 发现：`loadTrafficData()` 在默认 `viewModelScope.launch` 中直接执行流量汇总、Top 节点排序、百分比计算和节点名补全。流量日记录较多时，切换统计周期可能造成主线程卡顿。
- 发现：`loadTrafficData()` 对同一个周期分别调用 `getTrafficSummary()`、`getTopNodes(period)`、`getNodeTrafficPercentages(period)`，后两者内部又重新汇总，`THIS_WEEK` / `ALL_TIME` 会重复遍历日记录。
- 发现：`ConfigRepository.extractNodesFromConfig()` 在 `Dispatchers.Default` 节点解析块中首次获取 `TrafficRepository`，仓库首次初始化可能读盘，容易把阻塞 I/O 混入 CPU 调度器。
- 发现：`ConfigRepository.importProfileDirectly()` 虽然当前由导入仓库在 IO 中调用，但自身是公开 suspend 方法，未兜底调度；后续直接调用时可能把 Room、文件写入和同步节点解析放到调用线程。
- 修复：`TrafficStatsViewModel` 改为在 `Dispatchers.IO` 获取仓库和重载/清空流量数据，在 `Dispatchers.Default` 执行汇总、排序、百分比和节点名组装，最后回到主协程更新 UI 状态；保留旧 Job 取消和周期守卫。
- 修复：`TrafficRepository` 增加基于已计算 `TrafficSummary` 的 `getTopNodes(summary, limit)`、`getNodeTrafficPercentages(summary)`，旧按周期 API 继续保留并委托新入口。
- 修复：`ConfigRepository.extractNodesFromConfig()` 先在 `Dispatchers.IO` 初始化 `TrafficRepository`，再进入 `Dispatchers.Default` 做节点解析。
- 修复：`importProfileDirectly()` 增加 `withContext(Dispatchers.IO)` 边界，公开入口自身保证导入写库、写文件和同步节点解析不落在主线程。
- 修复：`TrafficRepository.getWeekTrafficLocked()` 删除空 `catch`，异常日期键会写入日志，避免静默吞掉坏数据。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.viewmodel.TrafficStatsViewModelSourceTest" --tests "com.kunk.singbox.repository.TrafficRepositorySourceTest" --tests "com.kunk.singbox.repository.ConfigRepositorySourceTest"`：通过。

## 2026-06-10 深度审查追加：节点测速并发状态

### 发现与修复

- 发现：`NodesViewModel.testAllLatency()` 传给 `ConfigRepository.testAllNodesLatency()` 的完成回调会在 IO 调度器和多个子协程中并发触发，但 ViewModel 内部使用普通 `Int` 变量执行 `completedCount++`、`successCount++`、`timeoutCount++`、`ipv6OnlyCount++`。
- 风险：多个节点几乎同时完成测速时，普通自增不是原子操作，可能出现完成数少计、统计结果少计、进度条显示未达到实际完成数。
- 发现：单节点测速和批量测速完成回调都通过 `_testingNodeIds.value = _testingNodeIds.value - id` 读改写集合。并发完成时，两个回调可能基于同一份旧集合更新，导致已完成节点重新留在 testing 集合里，界面持续显示测速中。
- 修复：批量测速统计改用 `AtomicInteger`；testing 节点集合改用 `MutableStateFlow.update` 做原子更新；单节点测速开始和结束也改为原子更新，避免重复点击同一节点造成重复任务。
- 发现：`NodesViewModel.deleteNode()` 在默认主协程中调用 `ConfigRepository.deleteNode()`；仓库函数会同步 `loadConfig()`、`writeConfigFileOrThrow()`，删除节点时可能在 UI 线程读写配置文件。
- 修复：`ConfigRepository.deleteNode()` 改为 suspend 并包裹 `withContext(Dispatchers.IO)`，调用方等待删除完成后再发 toast。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.viewmodel.NodesViewModelSourceTest"`：通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.viewmodel.NodesViewModelSourceTest" --tests "com.kunk.singbox.viewmodel.DashboardViewModelSourceTest" --tests "com.kunk.singbox.viewmodel.VpnConnectionManagerSourceTest"`：通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.repository.ConfigRepositorySourceTest" --tests "com.kunk.singbox.viewmodel.NodesViewModelSourceTest"`：通过。

## 2026-06-10 深度审查追加：规则集下载状态并发

### 发现与修复

- 发现：`SettingsViewModel` 的默认规则集下载、添加单个规则集、批量添加规则集、启用远程规则集都会更新 `_downloadingRuleSets`。其中批量添加会为多个远程规则集启动并发下载子任务，但状态集合使用 `_downloadingRuleSets.value += tag` / `-= tag` 读改写。
- 风险：多个规则集几乎同时完成下载时，后完成的任务可能基于旧集合写回，导致某个已完成规则集仍显示下载中，或正在下载的规则集被误删。
- 修复：新增 `markRuleSetDownloading()`、`markRuleSetDownloadFinished()`、`tryMarkRuleSetDownloading()`，统一使用 `MutableStateFlow.update` 原子更新下载集合；启用远程规则集时用原子方式判断并标记，避免重复启动同一 tag 下载。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.viewmodel.SettingsViewModelSourceTest"`：通过。

## 2026-06-10 深度审查追加：Room 主线程查询白名单

### 发现与修复

- 发现：`AppDatabase.buildDatabase()` 全局启用了 `allowMainThreadQueries()`。这会让所有同步 Room 查询在主线程上直接执行，掩盖后续 `getSync()`、`getAllSync()`、`saveSync()` 滥用。
- 发现：`SettingsStore.init` 使用 `settingsDao.getSettingsSync()`，`SettingsStore.reload()`、`hasSettings()` 也暴露同步查询路径；`SettingsRepository.getInstance()` 被 `MainActivity`、多个 ViewModel、服务和工具类直接调用，存在首帧或服务初始化时同步读库风险。
- 发现：`ConfigRepository.init` 同步执行 `loadProfileNodeMemory()` 和 `loadSavedProfiles()`，后者会同步读取 profiles、active_state、node_latencies，并可能迁移旧 JSON。多个 ViewModel 在属性初始化阶段创建 `ConfigRepository`，会把这段磁盘和数据库 I/O 带到主线程。
- 发现：`ConfigRepository.deleteProfile()`、`renameNode()`、`updateNode()` 原本是普通函数，其中 `deleteProfile()` 会直接删除配置文件，`renameNode()` / `updateNode()` 会直接读取并写回配置文件。UI 删除配置、节点详情保存等入口容易把文件 I/O 带到调用线程。
- 发现：节点列表导出链接点击回调同步调用 `NodesViewModel.exportNode()`，仓库会通过 `loadConfig()` 读取配置文件，再生成分享链接。
- 发现：节点详情页 `LaunchedEffect` 里直接调用 `getOutboundByNodeId()`，诊断路由测试直接调用 `getActiveConfig()`；两者都会通过 `loadConfig()` 读取配置文件。
- 风险：配置较多或数据库迁移时，应用冷启动、页面创建、快捷方式入口可能发生明显卡顿；全局白名单还会让新增主线程 Room 访问无法被 Room 运行时保护及时暴露。
- 修复：移除 `allowMainThreadQueries()`；删除 Settings DAO 的同步 settings 读写接口，`SettingsStore` 改用 suspend DAO 并在 IO dispatcher 中读取和 reload；`hasSettings()` 改为 suspend IO 查询。
- 修复：`ConfigRepository` 初始 profile/node memory 加载改由既有 IO scope 执行；`generateConfigFile()` 在生成 VPN 运行配置前等待初始 profile 加载完成，避免启动链路读到空 active profile。
- 修复：`deleteProfile()`、`renameNode()`、`updateNode()` 改为 suspend IO 边界；`ProfilesViewModel.deleteProfile()` 通过 `viewModelScope.launch` 调用，导入回滚和覆盖删除仍会等待删除完成。
- 修复：移除 `ActiveStateDao`、`ProfileDao`、`NodeLatencyDao` 中剩余的同步 Room 方法；`ConfigRepository` 和旧 `ProfilePersistence` 改用 suspend DAO，避免同步 DAO 入口以后被 UI 线程误用。
- 修复：节点导出链路改为 suspend IO；`NodesScreen` 点击导出时通过 `rememberCoroutineScope().launch` 调用，避免点击回调同步读配置文件。
- 修复：`getActiveConfig()`、`getOutboundByNodeId()` 改为 suspend IO；节点详情和诊断调用点继续在既有协程中等待结果。

### 本轮验证

- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.database.AppDatabaseSourceTest" --tests "com.kunk.singbox.repository.store.SettingsStoreTest" --tests "com.kunk.singbox.repository.SettingsRepositoryTest" --tests "com.kunk.singbox.repository.ConfigRepositorySourceTest"`：通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.repository.ConfigRepositorySourceTest" --tests "com.kunk.singbox.viewmodel.ProfilesViewModelSourceTest" --tests "com.kunk.singbox.repository.DataExportRepositoryTest"`：通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.database.AppDatabaseSourceTest" --tests "com.kunk.singbox.repository.ConfigRepositorySourceTest" --tests "com.kunk.singbox.repository.DataExportRepositoryTest" --tests "com.kunk.singbox.repository.store.SettingsStoreTest" --tests "com.kunk.singbox.viewmodel.ProfilesViewModelSourceTest"`：通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.viewmodel.NodesViewModelSourceTest" --tests "com.kunk.singbox.repository.ConfigRepositorySourceTest"`：首次发现点击 lambda 返回 `Job`，修复后通过。
- `.\gradlew --no-daemon testDebugUnitTest --tests "com.kunk.singbox.repository.ConfigRepositorySourceTest" --tests "com.kunk.singbox.ui.screens.NodeDetailScreenSourceTest" --tests "com.kunk.singbox.viewmodel.DiagnosticsViewModelNodeLineTest"`：通过。
- `.\gradlew --no-daemon detekt`：首次发现 `SettingsDao.kt` 结尾格式问题；后续发现节点编辑函数复杂度超标，拆分节点刷新逻辑后复跑通过。
- `.\gradlew --no-daemon assembleDebug`：通过。

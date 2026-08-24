# Root 透明代理模式完整实施计划

状态：DEVICE_GATE，最终候选已通过桌面与蓝军门禁；上一版真机失败已作废，等待新 APK 真机验收

日期：2026-08-23

目标：在 Root Android 设备上新增完整的 `ROOT_TRANSPARENT` 流量接管模式。该模式不启动 Android `VpnService`，不创建 TUN 网卡，继续复用 KunBox 当前 `libbox.aar`、节点、规则、DNS、分应用策略、连接事件、资源保护和诊断链路，并完整接管 TCP、UDP、QUIC、IPv4 与 IPv6。

## 一、不可妥协的验收条件

1. Root 模式运行时系统没有 KunBox VPN transport、VPN 图标和 KunBox TUN 网卡。
2. 被接管应用的 TCP、UDP、QUIC 进入当前 sing-box 路由；绕过应用业务连接直连。
3. Root 模式统一接管系统 TCP/UDP 53 DNS，UI 明示该语义。
4. 双栈完整接管 IPv4/IPv6；IPv4-only 明确拒绝被接管 UID 的 IPv6，禁止静默泄漏。
5. RootService、应用进程、libbox、watchdog 任意单点死亡时恢复直连。
6. 应用和 RootService 同时死亡、force-stop、APK 卸载时由独立 watchdog 清理 netfilter。
7. 停止、失败回滚、重复启动和策略更新幂等，不残留链、mark、ip rule、路由表和 Root 进程。
8. 分应用白名单、黑名单、shared UID、多 Android 用户和策略 revision/digest 保持现有语义。
9. selector、热重载、连接事件、流量统计、FD 风暴保护和故障恢复仍可用。
10. 不重复打包 standalone sing-box，不安装 Magisk 模块，不切换 SELinux permissive。

## 二、采用的行业方案

- Root 执行环境：`topjohnwu/libsu 6.0.0` 的 `RootService` 与 Binder IPC。
- 代理数据面：官方 sing-box `redirect` TCP 入站与 `tproxy` UDP 入站。
- 内核数据面：iptables/ip6tables `nat REDIRECT`、`mangle MARK/TPROXY` 与 policy routing。
- 生命周期：普通前台 Service 持有 RootService，独立双向租约 watchdog 负责双进程死亡与卸载清理。
- 核心：RootService 直接加载当前 gomobile `libbox.so`；`go.Seq` 已确认调用 `System.loadLibrary("box")`。
- eBPF 暂缓：当前成熟 Android 实现依赖非官方 sing-box 1.14 beta fork，不能替换稳定内核基线。

## 三、运行模式和迁移

新增 `TrafficCaptureMode`：

```kotlin
enum class TrafficCaptureMode {
    VPN,
    ROOT_TRANSPARENT,
    PROXY_ONLY
}
```

迁移：

- `tunEnabled=true` → `VPN`
- `tunEnabled=false` → `PROXY_ONLY`
- 仅用户主动选择才能进入 `ROOT_TRANSPARENT`

`tunEnabled` 保留一个兼容版本，只作为旧数据读取入口。所有新业务逻辑只读取 `TrafficCaptureMode`。

涉及文件：

- `model/Settings.kt`
- `database/entity/SettingsEntity.kt`
- `repository/store/SettingsStore.kt`
- `repository/SettingsRepository.kt`
- `viewmodel/SettingsViewModel.kt`
- `manager/VpnServiceManager.kt`
- `ipc/VpnStateStore.kt`

验证：迁移单测、序列化单测、三模式启动命令单测、旧设置导入测试。

## 四、服务和进程结构

```text
UI / ViewModel
    ↓
VpnServiceManager
    ↓
RootTransparentForegroundService (:bg，普通前台 Service)
    ↓ Binder
KunBoxRootService (libsu RootService，UID 0，非 daemon)
    ├── CommandManager / PlatformInterfaceImpl
    ├── RootPlatformInterface
    └── RootNetfilterManager
    ↓ 双向租约
KunBox Root Watchdog (/system/bin/sh，独立 Root 进程)
```

职责：

- `RootTransparentForegroundService`：通知、主应用 IPC、Binder 生命周期、CommandClient 和状态桥接。
- `KunBoxRootService`：Root 环境、libbox CommandServer 生命周期、透明 socket 和 netfilter。
- 共享控制能力：直接复用现有 `CommandManager`、`PlatformInterfaceImpl`、`SelectorManager` 和 `SingBoxCore`，不新增只服务一个调用方的控制器抽象。
- `RootNetfilterManager`：能力探测、规则事务、回读和清理。
- watchdog：只处理租约和固定清理清单，禁止读取节点与配置。

禁止复制第三份两千行 Service。Root 外壳直接组合现有控制组件，保持最小改动。

新增文件：

- `service/root/RootTransparentForegroundService.kt`
- `service/root/KunBoxRootService.kt`
- `service/root/RootServiceConnection.kt`
- `service/root/RootNetfilterManager.kt`
- `service/root/RootCapabilityProbe.kt`
- `service/root/RootWatchdogInstaller.kt`
- `service/root/RootRuntimeStateMachine.kt`
- `service/root/RootPlatformInterface.kt`
- `aidl/.../IRootSingBoxService.aidl`
- `assets/root/kunbox-root-watchdog.sh`

## 五、libsu 和 JNI 硬门禁

Gradle 增加：

```kotlin
implementation("com.github.topjohnwu.libsu:core:6.0.0")
implementation("com.github.topjohnwu.libsu:service:6.0.0")
```

RootService 静态初始化仅在 `Process.myUid() == 0` 时触发 `go.Seq.touch()`/`Libbox.version()`。

Root 能力报告必须验证：

1. RootService UID 为 0。
2. JNI 可加载当前 ABI `libbox.so`。
3. `Libbox.version()`、`Libbox.setup()`、最小 CommandServer 启停成功。
4. `CAP_NET_ADMIN`、`CAP_NET_RAW` 和 SELinux domain 可用。
5. TCP `REDIRECT` 与 UDP `TPROXY` 临时规则可安装，UDP 入站可以设置 `IP_TRANSPARENT`。

任何门禁失败：Root 模式不可用，设备保持直连，不自动退化为 REDIRECT 或 VPN。

## 六、配置生成

`Inbound` 增加：

- `network`
- `udp_timeout`
- `routing_mark`

`InboundBuilder`：

- VPN：现有 `tun-in`
- Root：`redirect-in` 监听 TCP，`tproxy-in` 监听 UDP
- 仅代理：现有 `mixed-in`

所有内置规则使用当前捕获入站标签，禁止写死 `tun-in`。Root 模式复用 outbounds、DNS、route、selector、规则集、计费节点保护和内核物理拨号预算。

涉及文件：

- `model/SingBoxConfig.kt`
- `repository/config/InboundBuilder.kt`
- `repository/ConfigRepository.kt`
- 相关配置测试

## 七、REDIRECT、TPROXY 与 netfilter 事务

数据路径：

```text
选中 UID TCP → nat OUTPUT REDIRECT → redirect-in → Root libbox
选中 UID UDP → mangle OUTPUT mark → policy route → PREROUTING TPROXY → tproxy-in → Root libbox
```

规则要求：

- 独立 IPv4/IPv6 链、mark 和路由表。
- 启动前检测链名、mark、表号冲突。
- KunBox UID、回环、本地监听端口、bypass mark 提前 RETURN。
- 核心出站先由 `Network.bindSocket()` 生成 Android 原生 fwmark，再读回并只追加 bit 28 的 KunBox
  bypass 位；禁止覆盖 netId、explicit、protected、permission 和 OEM vendor 位。
- Root 进程读取 `SO_MARK` 失败时必须在安装 netfilter 前失败关闭；UID 0 排除作为第二层防回环。
- OUTPUT/PREROUTING 主链跳转最后安装、最先删除。
- 安装后回读实际规则和路由，不能只相信命令退出码。
- 双栈任一侧失败时整次事务回滚。
- IPv4-only 对选中 UID 安装 IPv6 REJECT。

分应用：

- 复用 `PerAppVpnPolicy` 与 shared UID 展开。
- 主进程传递校验后的 UID 列表，不传包名到 Root shell。
- 白名单、黑名单最终生成 UID RETURN/MARK 规则。
- TCP REDIRECT 的 owner 查询先匹配完整 tuple，NAT 改写导致 miss 时仅接受唯一 source endpoint UID；
  多 UID、UID 0、脏数据全部返回 native unknown `-1`，Root 模式禁止回退到 ConnectivityManager 误认 UID。
- 策略变化通过同一生命周期互斥锁执行完整 Root 重启，旧规则先验证清理，新规则安装后严格回读，禁止并发 START/STOP/RESTART 覆盖用户最后一次操作。

## 八、DNS 语义

首版固定全局明文 DNS 劫持：

- 系统 TCP/UDP 53 统一进入 KunBox DNS。
- 绕过应用的业务连接仍直连，但 DNS 也由 KunBox 处理。
- 853 不做 UID 0 全局特例；系统 Private DNS 保持普通连接，被接管应用自己的 DoT 按当前路由策略处理。
- DoH、DoQ 按普通 TCP/UDP/QUIC 连接处理。
- 设置页明确显示该语义。

原因：Android system resolver、isolated process 和 SDK sandbox 可能由其他 UID 代发，稳定还原原应用 UID 无法保证。

## 九、watchdog 和清理

watchdog 安装到 `/data/adb/kunbox/`，权限 root-only，只包含：

- 版本化固定脚本
- runtime session ID
- RootService PID
- 租约和 ACK
- 固定清理清单

双向租约：

- RootService 每秒更新 lease。
- watchdog 每秒更新 ACK。
- watchdog 发现 lease 超过 3 秒、PID 消失或 APK 路径消失，立即撤销主链跳转并清理。
- RootService 发现 ACK 超过 2 秒或 session ID 不一致，立即撤销跳转并停止本次 Root 会话。

清理顺序：

1. 删除系统主链跳转。
2. 删除 ip rule 与 local route。
3. 清空并删除 KunBox 自定义链。
4. 停止残留核心。
5. 删除运行目录。

冷启动、Root 模式启动和开机恢复都先运行 stale janitor。清理只匹配 KunBox 固定 schema/version，禁止模糊删除其他软件规则。

## 十、状态机和 IPC

状态机：

```text
STOPPED
→ ROOT_BINDING
→ CORE_STARTING
→ RULES_STAGING
→ RUNNING
→ CLEANING
→ STOPPED
```

异常状态：

- `FAILED_UNPROTECTED`：无流量跳转，设备直连。
- `FAILED_RULES_PRESENT`：仍检测到残留规则，UI 红色故障并持续清理。

每次完整启动生成 UUID `runtimeSessionId`；会话内 `snapshotGeneration` 单调递增。普通进程轮询 RootService 实时快照，快照携带 sessionId、ruleRevision、generation。不同 session 或旧 generation 全部拒绝。

RootService 实时快照是真相源，`VpnStateStore` 只保存缓存和恢复意图。Binder 重连必须先查询 RootService 与实际 netfilter 状态。

`DataPlaneReadinessSnapshot` 增加：

- capture mode
- RootService PID
- runtime session ID
- netfilter revision
- REDIRECT 与 TPROXY v4/v6 状态
- watchdog lease/ACK 状态
- cleanup 状态

Root 模式下 `tunEstablished=false`、`systemVpnTransport=false` 属于正常状态。

## 十一、UI

将“启用 TUN”替换为“流量接管模式”：

- Android VPN
- Root 透明代理
- 仅代理

Root 模式显示：

- Root 授权
- REDIRECT、TPROXY IPv4/IPv6 能力
- 当前接管应用数量
- 无 Android VPN
- 系统 DNS 统一接管
- 最近一次清理结果

能力不足时禁止选择并展示精确原因。文案禁止使用“隐身”“反检测”。Dashboard 和通知显示 `ROOT` 模式。

涉及文件：

- `ui/screens/TunSettingsScreen.kt`
- `ui/screens/DashboardScreen.kt`
- `viewmodel/SettingsViewModel.kt`
- `viewmodel/DashboardViewModel.kt`
- `values/strings.xml`
- `values-en/strings.xml`

## 十二、安全边界

- Root Binder 校验调用 UID，只接受 KunBox UID。
- 用户输入不能进入 shell 命令文本。
- Root 命令只接收校验后的数字 UID、端口、mark 和表号。
- watchdog 不存配置、节点、订阅和凭据。
- 不开放 Root HTTP 控制端口。
- 不修改 SELinux、不执行 permissive、不安装持久 Magisk 模块。
- 所有 Root 错误进入脱敏诊断，包含问题、原因和恢复结果。

## 十三、实施阶段

### 阶段 0：计划与边界

- [x] 完成设计与三轮外部审查
- [x] 完成项目内实施计划
- [x] 本地蓝军审查通过

### 阶段 1：模式模型和配置

- [x] 新增 `TrafficCaptureMode` 与存储迁移
- [x] 三模式服务分发
- [x] `redirect-in` TCP 与 `tproxy-in` UDP 配置生成
- [x] 捕获入站标签去硬编码
- [x] 单元测试

### 阶段 2：RootService 技术门禁

- [x] 引入 libsu
- [x] RootService AIDL 与绑定
- [x] JNI libbox 加载
- [x] Root capability report
- [x] REDIRECT TCP 与 TPROXY UDP 规则能力探测，真实监听由每次内核启动硬门禁

### 阶段 3：共享核心生命周期

- [x] 复用 `CommandManager`、`PlatformInterfaceImpl`、`SelectorManager` 与 `SingBoxCore`
- [x] RootService 接入共享控制能力
- [x] selector、连接事件、资源保护和诊断接回

### 阶段 4：netfilter 和 watchdog

- [x] IPv4/IPv6 REDIRECT 与 TPROXY 规则事务
- [x] UID 白名单/黑名单与多用户 UID 范围
- [x] 全局 DNS 劫持
- [x] 双向租约 watchdog
- [x] stale janitor
- [x] 崩溃、快速重启与卸载清理
- [x] REDIRECT 与 TPROXY 监听端口 INPUT 防护

### 阶段 5：状态、UI 和恢复

- [x] Root 状态机与 session/generation 门控
- [x] SingBoxIpcHub/Remote 接入
- [x] 设置页、Dashboard、通知和诊断
- [x] 分应用策略串行重启更新与严格清理回读
- [x] 节点热切换与故障恢复

### 阶段 6：验证

- [x] 模式迁移和配置单测
- [x] netfilter 生成/解析/失败关闭单测
- [x] watchdog 脚本静态契约检查
- [x] `detekt`
- [x] `testDebugUnitTest`
- [x] `assembleDebug`
- [x] `assembleDebugAndroidTest`
- [ ] Root 真机：TCP、UDP、QUIC、DNS、IPv6、白名单、黑名单、shared UID、多用户
- [ ] Root 真机：RootService kill、应用 force-stop、双进程 kill、watchdog kill、规则半失败、应用更新、卸载模拟、重启

## 十四、测试映射

```text
Settings migration
  → SettingsStoreTest / ModelSerializationTest

Mode dispatch
  → VpnServiceManagerTest / VpnStateStoreTest

Root config
  → ConfigRepositoryTest / ModelSerializationTest

UID policy
  → PerAppVpnPolicyTest / RootNetfilterPlanTest

State ordering
  → RootRuntimeStateMachineTest / SingBoxRemote tests

watchdog cleanup
  → RootWatchdogContractTest + Root 真机故障矩阵

JNI / TPROXY / SELinux
  → Root instrumentation test，只在已 Root 设备运行
```

## 十五、失败和救援表

| 失败 | 用户可见结果 | 自动救援 | 最终状态 |
|---|---|---|---|
| 无 Root | Root 模式不可选 | 无 | 直连 |
| JNI 加载失败 | 显示 ABI/JNI 原因 | 清理临时状态 | 直连 |
| IPv4 REDIRECT、TPROXY 或 SELinux 不支持 | 显示具体能力项 | 删除探测规则 | 直连 |
| IPv6 NAT 不支持 | 显示 IPv6 降级状态 | 阻断被接管 UID 的 IPv6 | IPv4 代理 |
| 核心启动失败 | 显示内核错误 | 停核心、删规则 | 直连 |
| 规则安装半失败 | 显示回滚结果 | 事务回滚、回读 | 直连 |
| RootService 死亡 | 显示 Root 数据面死亡 | watchdog 清理 | 直连 |
| watchdog 死亡 | 显示清理保护失效 | RootService 主动撤销跳转 | 直连 |
| 双进程死亡/force-stop | 下次打开显示上次清理 | watchdog 清理 | 直连 |
| 卸载 | 无 UI | watchdog 发现 APK 消失并自清理 | 直连 |
| 残留规则 | 红色故障 | janitor 重试并回读 | `FAILED_RULES_PRESENT` |

## 十六、明确不做

- 不隐藏 Root、Magisk、KernelSU、APatch。
- 不绕过第三方安全策略。
- 不代理热点和 USB 共享流量。
- 不引入非官方 sing-box fork。
- 不在 IPv4 REDIRECT 或 UDP TPROXY 不可用时静默退化。
- 不把 standalone sing-box 再打包一份。

## 十七、计划审查记录

审查日期：2026-08-23

结论：`9/10 PASS`，允许进入实现。

审查证据：

1. Gradle 已配置 JitPack，可直接引入官方 `libsu 6.0.0`。
2. 当前 AAR 的 `go.Seq` 已反编译确认调用 `System.loadLibrary("box")`，符合 libsu RootService JNI 加载方式。
3. Go 官方构建约束确认 `GOOS=android` 同时匹配 Linux 文件，当前 libbox 包含 sing-box TPROXY Linux 实现。
4. 代码库存在约 190 处 `tunEnabled`、`CoreMode` 和服务模式关联，计划已将迁移、分发、状态和 UI 纳入同一阶段，禁止只改设置页。
5. 配置中存在 17 处 `tun-in` 断言或硬编码，计划已要求捕获入站标签统一生成并补齐回归测试。
6. `ProxyOnlyService` 已有完整 CommandServer 链路，`CoreManager`、`CommandManager`、`StartupManager` 可作为共享控制器抽取基础。
7. 当前工作区已有用户修改，涉及 `SingBoxService`、`CommandManager`、`StartupManager`、Dashboard 和字符串。实现前逐文件回读差异，只做追加式合并，禁止覆盖或回退。
8. Android SDK 自带 adb 可用，但当前没有设备连接。Root 真机矩阵仍是最终完成门禁；未通过前禁止声称 Root 数据面完整交付。

蓝军修订：

- 明确 Root 真机是最终门禁，桌面单测和 APK 构建不能替代。
- 明确 mode 迁移必须覆盖配置、IPC、恢复、Tile、通知和诊断。
- 明确当前脏文件按用户改动处理，逐块合并。
- 明确 IPv4 REDIRECT 或 UDP TPROXY 缺失时拒绝 Root 模式，禁止静默 VPN 降级。

## 十八、最终实施审查记录

审查日期：2026-08-23

结论：`APPROVED`，无剩余 P0/P1。

最终修复项：

1. 为 IPv4/IPv6 REDIRECT、TPROXY 端口增加 INPUT 链，只允许本机重定向或带 KunBox mark 的透明流量进入。
2. 所有安装失败、watchdog 丢失、FD 超限和停止路径都执行严格残留回读；无法确认清理时进入 `FAILED_RULES_PRESENT`。
3. watchdog 保存子进程并在正常停止时终止；清理绑定 session ID，旧 watchdog 禁止清理新会话。
4. START、STOP、RESTART 与节点重启使用同一 Mutex 串行，完整重启期间禁止命令插队。
5. CommandServer 启动中途失败可被回滚关闭；CommandClient 接管裸 FD 失败时立即关闭 FD。
6. PREROUTING、INPUT、OUTPUT 按安全顺序激活；主链、策略路由和本地路由安装后回读。

桌面验证证据：

- `compileDebugKotlin`：通过。
- Root 专项单测：通过。
- `detekt`：通过。
- `testDebugUnitTest`：通过。
- `assembleDebug`：通过。
- `assembleDebugAndroidTest`：通过。
- `git diff --check`：通过。
- Debug APK 已确认包含 `libbox.so`、libsu `RootService`、`KunBoxRootService` 和 `assets/root/kunbox-root-watchdog.sh`。

剩余真机缺口：该设备缺少 IPv6 nat 表，无法验证 REDIRECT IPv6；多用户和完整故障注入矩阵尚未执行。

真机修订：PJZ110 / Android 16 / KernelSU 上，本机 TCP TPROXY 不产生 TCP accept，UDP TPROXY 正常。最终采用 TCP REDIRECT + UDP TPROXY；该机缺少 IPv6 nat 表，运行时自动阻断被接管 UID 的 IPv6并使用 IPv4。真机日志已确认 TCP 进入 `redirect-in-v4`，完成 TLS sniff、规则匹配、代理出站和上下行传输，系统无 Android VPN transport。

## 十九、2026-08-24 真机断网 RCA

故障现象：Root 模式下 TG 正常，X、Reddit、YouTube 大面积打不开或持续转圈；VPN 模式正常。

证据与根因：

1. 当前 Root 日志中 `api.x.com` 27 次被系统 DNS 解析到 Facebook 地址 `31.13.96.192`，另有 15 次落到
   Twitter 网段；客户端在真假地址间反复重试。
2. 系统 DNS 由 UID 0 的 netd 代发。旧 netfilter 在 53 规则前先 RETURN UID 0，导致“全局 DNS 接管”实际失效。
3. TCP REDIRECT 后完整 tuple owner 查询 miss，旧实现又把 unknown 零值或 ConnectivityManager 结果当成 UID 0/1000，
   分应用规则失效。
4. Root 规则集仍可能携带 `tun-in`，无法匹配 redirect/tproxy 入站。
5. Hysteria2 的真实错误文案 `timeout: no recent network activity` 未进入 Root 自动切换；远程 DNS 超时也错误归到
   主 `PROXY`，无法切换实际故障的 `P:*` 组。

落地修复：

- 只在 UID 0 之前全局接管 TCP/UDP 53；853 保持系统 Private DNS 普通连接语义。
- 保留 Android canonical SO_MARK，只追加独立 bypass 位，防止 sing-box 出站自环。
- Root TCP owner 使用完整 tuple 后的唯一 source endpoint 回退，unknown 固定为 `-1`，禁止回退 ConnectivityManager。
- `tun`、`tun-in` 按运行模式展开到实际 Root 入站，并覆盖规则集、原始 DNS 规则和 DNS override。
- 自动切换识别真实超时文案、远程 DNS 的实际 selector，并将连续超时窗口扩到 20 秒。
- 修正 selector 缓存清理路径为 `filesDir/singbox_data/cache.db`。

历史结论更正：该轮桌面审查没有覆盖真实污染 IP、CommandLog 静默卡死和显式 NODE 绑死节点。随后真机
X、Reddit、YouTube、Instagram 仍失败，因此该轮不能记为最终 `PASS`。

## 二十、2026-08-24 第二次真机失败与最终修订

### 20.1 真机事实

1. 已安装 APK 与本地 Debug APK SHA-256 完全一致，排除装错包。
2. RootService、IPv4 REDIRECT、UDP TPROXY、policy route、watchdog 均处于运行态。
3. X、Reddit、YouTube、Instagram、TG、Chrome 均在接管范围；非 TG TCP 确实进入 `redirect-in-v4`。
4. sing-box 已嗅探出正确域名并正确匹配域名规则，但拨号仍使用 App 提交的错误 IP：
   - `graph.instagram.com` 被拨到 Dropbox IP `108.160.162.109`。
   - Instagram CDN 被拨到 Twitter Asia 网段。
   - 运行 38 分钟后的新连接仍出现 `www.google.com` 对应非 Google IP。
5. `running.log` 在 Root 启动约 18 秒后永久停止；Clash API 后续仍持续出现连接。旧 CommandLog socket
   表面保持连接，内部 stream 已失明，自动切换也失去故障输入。
6. YouTube、Instagram 的显式 NODE 规则绑定具体 H2 节点。旧逻辑切换 `P:*` 或 `PROXY`，真实业务仍走 H2。

### 20.2 根因

1. Root REDIRECT 保留的是 App 已提交的目标 IP。sing-box 1.13.19 的 sniff 只补域名用于选路，不再自动覆盖
   最终拨号目标，所以 DNS 污染后的 IP 会穿过正确的域名分流继续被代理拨出。
2. CommandLog 是 server-stream。旧客户端只处理显式断线，没有代次隔离、订阅 ready、应用层心跳和静默卡死
   检测。旧流还能跨重启回调，历史日志重放也可能被误当成新故障。
3. 显式 NODE 规则没有可切换的承载组；自动切换即使执行，也可能切错 selector。
4. Root 的 `blockQuic` 在 core 内拒绝，连续拒绝后可能退化成静默丢包，Cronet 需要等待 QUIC 超时才回落 TCP。

### 20.3 最终修订设计

1. 在实际具体 outbound 进入 `ConnectionManager.NewConnection` 时执行 late FQDN override：
   - 只允许 `redirect-in-v4/v6`、TCP、80/443。
   - 只允许明确的远程代理协议白名单。
   - `direct`、`block`、`dns`、selector/urltest 本体、VPN、仅代理、UDP 全部跳过。
   - 已有 `override_address/port` 时跳过。
   - 清空旧地址列表，把 FQDN 直接交给代理端解析，不再依赖手机本地 DNS。
   - selector 保持实际叶子拨号；urltest 和嵌套组仅在启动时证明所有可达叶子都是远程代理后才允许覆盖。
     原有组级 interrupt、测速历史和失败淘汰链保持不变；任何分支含直连或循环时整体跳过。
2. Root 的显式 NODE 规则生成 `F:<stableNodeId>` 临时 selector：
   - route 与对应远程 DNS detour 同时引用 `F:*`。
   - 默认成员仍是用户指定节点，备用成员只取同 profile 的普通可测节点。
   - WireGuard endpoint 不进入 `F:*`。
   - 仅 `F:` 加 canonical UUID 被内核识别为临时 selector，不读写 cache，重启仍回到用户原始选择。
3. Root 运行配置独立使用 debug 日志，保证实际 route group 与 DNS detour 可被结构化解析；VPN 和仅代理仍为 info。
4. Root 自动切换按连接真实 `F:*` 组执行，读取并过滤隔离表，复用
   `NodeAutoFailoverPolicy.evaluateProbe(treatCurrentAsFailed=true)`，成功后关闭旧连接并 resetNetwork。
5. CommandLog 使用完整监督状态机：
   - runtime generation 与 client token 双门禁。
   - `SetDefaultLogLevel` 作为订阅成功确认；Root 启动还要等 Status、Group、Connections 首包。
   - server 每 5 秒发送空 Log/Group 心跳，Connections 每个周期即使无事件也发送空心跳。
   - 15 秒无心跳视为静默卡死，持续封顶退避重连；短命连接不会永远 500ms 抖动。
   - replay 只恢复 UI，不进入故障观察器；每条 live 日志投递前重新核对代次和 token。
   - Status、Group、Connections 任一持续失健康时，Root 执行受控完整重启；单独 Log 抖动只重连 Log。
   - Root FD 所有权单向交给 libbox，禁止 Kotlin 二次关闭。
6. Root `blockQuic=true` 时，filter OUTPUT 对被接管 UID 的 UDP/443 立即 REJECT，让 Cronet 立即降级 TCP。
   正常清理、失败回滚、残留回读和独立 watchdog 全部覆盖 `KBX_QUIC4/6`。

### 20.4 验收门禁

桌面候选必须全部通过：

- 内核 patch 固定 hash、允许文件集、clean apply。
- Go `test`、`test -race`、`vet` 覆盖 route、protocol/group、daemon 及原物理拨号包。
- AAR API/ABI、四 ABI 原生 marker、Gradle 单测、detekt、assembleDebug。
- 三轮独立蓝军 P0=0、P1=0。

最终真机必须同时看到：

- `kunbox root override destination: <错误IP> => <真实域名>`。
- X、Reddit、YouTube、Instagram 均有真实下行并能打开内容。
- 运行配置出现对应 `F:<nodeId>`，故障时切换提交到该真实组。
- CommandLog 心跳持续；主动或自然断流后出现 `disconnected/reconnected` 且日志继续增长。
- `blockQuic=true` 时 UDP/443 立即回落 TCP，无 30 秒静默等待。

真机矩阵通过前，禁止再次写“Root 数据面已最终修复”。

### 20.5 最终候选构建证据

- 最终 patch SHA-256：`417EA7700B74CD3F953275E9A23A0B2CEA3F837910AF8EBC7DB3DDF419EDA1E5`。
- `sync-kernel.ps1` Stage 1 至 Stage 8 全部通过，AAR 未回滚。
- Go 普通测试、race、vet 通过；patched sing-tun 默认与 gVisor 测试、race、vet 通过。
- AAR 公共 API 与官方基线一致，四 ABI 均通过原生策略扫描。
- `app/libs/libbox.aar` SHA-256：`17C1589A4D318181C0A81EA83CC7379624CEC0458A272FEF49DEFE82805A988C`。
- arm64 Debug APK SHA-256：`22627608638DE2BD97B819CB582816B16654A9D68A57F63FBAF8749FBF86FF79`。
- AAR 与 arm64 Debug APK 内的 `libbox.so` 均确认包含
  `kunbox root override destination:` 原生 marker。
- Gradle 全量 Debug 单测、detekt、assembleDebug、assembleDebugAndroidTest 通过。
- 三路独立蓝军最终结论：P0=0、P1=0。
- 尚未通过的新门禁只有 PJZ110 真机 X、Reddit、YouTube、Instagram 联合验收。

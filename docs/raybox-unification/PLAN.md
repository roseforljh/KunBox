# RayBox 统一迁移计划

## Context

KunBox、SimpleNet、KunBoxForWindows 当前分别存在 sing-box、外置 Xray 插件和 RayBox 混用路径，导致协议能力、配置字段、核心版本和发布物无法长期一致。目标是以 `D:\RayBox-rx` 为唯一内核源码来源，让 Android、Windows 和 SimpleNet 后端统一消费同一提交对应的 RayBox 产物；RayBox 内核缺能力时补内核，客户端缺接入能力时补客户端，并把架构、过程、验证和回滚规则写入四个项目。

当前已确认：

- RayBox 是 sing-box 外壳内嵌 Xray outbound，不是第二个未受保护的 Xray 进程。
- Android AAR 的 Java 类集合基本兼容 KunBox；RayBox 新增 `PlatformInterface.useProcFS()` 和 `xrayVersion()`，删除静态 `Libbox.findConnectionOwner()`，KunBox 当前主要使用平台回调而非该静态 API。
- KunBox 有物理拨号预算、连接归属、selector fallback、域名改写、WireGuard 等定制补丁，不能直接覆盖 AAR 或丢失补丁。
- SimpleNet 已有 RayBox commit/tag/ABI/SHA-256 锁定与下载校验，可作为共同发布契约的起点；其 `simplenet-dispatch` 现有修改必须保留。
- KunBoxForWindows 当前将 XHTTP 节点桥接到独立 `xray.exe`，而 RayBox Windows 产物尚未建立，不能假设 Android AAR 可用于 Windows。
- RX/OOB 默认关闭，现有实验记录不能直接作为三客户端生产验收结论。
- 当前 SimpleNet Android lock/build 与 Android release 的 commit/tag 存在身份不一致，dispatch server 也锁定另一提交；在身份链统一前不得把现有产物当作可复用基线。
- 2026-11-05 接口复核纠正：KunBox 当前实际使用 `CommandServer.pause/wake/resetNetwork`，RayBox 已实现；不新增历史私有恢复 API。阻塞是安全补丁、平台接口和工件来源校验，不是这些旧扩展。

## 推荐架构

1. RayBox-rx 是唯一源码基线；四个仓库不再各自维护一份运行核心。
2. RayBox 对外发布不可变 commit 产物，统一包含源码提交、版本、目标平台/架构、构建工具链、文件大小和 SHA-256 的 manifest，以及源码/许可证说明。
3. Android 消费 `libbox.aar` 和 `libbox-legacy.aar`；Windows 消费 RayBox 原生 Windows 可执行文件；SimpleNet 后端继续消费 RayBox server core。三种产物都必须通过同一套 provenance 规则校验。
4. 节点配置保留显式 `core` 语义：普通协议继续走 RayBox 的 sing-box 原生 outbound；VLESS/VMess/Trojan 的 Xray 专属能力生成 `type: "xray"` outbound。客户端只负责生成配置和能力校验，拨号、TUN、DNS、路由、保护和生命周期仍由 RayBox 统一完成。
5. 外置 Xray bridge 仅作为迁移期间回滚路径，RayBox Windows 原生核心验证通过后删除默认桥接路径。

## 阶段与依赖

### Phase 0：基线、工作树隔离与项目记录

- 记录四个仓库当前分支、HEAD、dirty 文件、已有构建物和用户修改；不得重置或覆盖 `SimpleNet/simplenet-dispatch`、RayBox 的 README/workflow 修改、KunBoxForWindows 的 `.playwright-mcp`。
- 在四个项目分别新增并维护同名迁移文档 `docs/raybox-unification/`：KunBox、SimpleNet、RayBox-rx、KunBoxForWindows 各自保存总计划副本、阶段状态、变更日志、测试门禁和回滚步骤；RayBox 同步脚本旁增加维护说明。四份文档的协议字段、RayBox commit、manifest 和验收结论必须保持一致，项目特有的实现细节写入各自的过程日志。
- 建立跨项目的 manifest 字段定义和兼容矩阵，明确“同一 RayBox commit”而不是只比较 `Libbox.version()`。

依赖：无。

### Phase 1：RayBox 核心基线与补丁移植

- 以 RayBox 当前 sing-box/Xray 组合源码为基线，先完成 sing-box 版本与 KunBox 定制补丁的兼容性分析，再逐项移植 KunBox 补丁；不能把 `.kernel-sync-local/patches/kunbox-v1.14.4.patch` 直接整体套用。
- 先建立唯一、可验证的源码身份链：RayBox repository、完整 commit、Android tag、server tag、Windows tag、各自 `build.json`/`manifest.json` 和资产 SHA-256 必须互相一致；修复现有 Android lock 与 release/build manifest 不一致、dispatch server 仍锁旧提交的问题后，才能进入客户端消费阶段。
- 对已知冲突逐文件处理：`common/dialer/default.go`、`protocol/direct/outbound.go`、`protocol/group/selector.go`、`protocol/group/urltest.go`、`route/conn.go`、`transport/wireguard/endpoint.go`。每个补丁保留原行为和 RayBox Xray protected dialer 约束。
- 为 `protocol/xray` 增加 sing-box protected dialer、Xray instance context、detour/TCP fast open/OOB 边界的单元测试；禁止使用 `context.Background()` 破坏保护链。
- 以当前 Kotlin 调用核对 libbox API：保留 `CommandServer.pause/wake/resetNetwork`、CommandClient 状态/日志/连接和平台回调。不要恢复旧私有 API；同步脚本禁止的方法和实现仍保持禁止。安全补丁与 AAR API 检查通过前不替换运行核心。
- 明确 RX/OOB 的能力边界：默认关闭，只接受客户端显式合法配置；不把普通 XHTTP 成功误标为 RX/OOB 验收。
- 若缺失能力属于核心公共能力，先在 RayBox 补齐并补 Go 测试，再让客户端接入；不要在三个客户端复制内核逻辑。

关键文件：`D:\RayBox-rx\sing-box\protocol\xray\outbound.go`、`D:\RayBox-rx\sing-box\experimental\libbox\platform.go`、`command_server.go`、`service.go`、Xray-core `splithttp`/TLS 实现、KunBox `.kernel-sync-local/patches/`。

依赖：Phase 0。

### Phase 2：统一产物、manifest 与发布门禁

- 统一 RayBox Android workflow：Go 1.27.3/JDK 17/NDK 29.0.14206865 或更高，产出 ARM32/ARM64/x86/x86_64 四 ABI AAR；SimpleNet APK 仍仅交付两 ARM。校验实际 ZIP/JNI/ELF、16KiB 对齐、Java API 与 SHA-256，不只检查清单自声明。
- 解决 RayBox `with_tailscale` 与 KunBox 禁用策略冲突，不以放宽门禁替代迁移；保留 Xray 自身 Encryption，禁止重新引入旧 sing-box 私有 Encryption。
- Android/server/Windows 的 push 只编译验证并保存 CI artifact；仅 `workflow_dispatch` 显式 `publish=true` 才允许发布 job 获得写权限。正式工件必须来自干净源码；本地 dirty 候选记录源码摘要并禁止标为正式不可变工件。
- 为 RayBox 增加 Windows 原生构建 workflow，至少提供 x64；如客户端需要再提供 arm64。产出 RayBox Windows executable、manifest、LICENSE、SOURCE，并在构建阶段验证 PE 架构、版本中包含完整提交前缀、SHA-256 与 commit 一致。当前 KunBoxForWindows 没有 RayBox Windows 工件、下载 URL、ABI 或打包依赖，因此该 workflow 和 lock 是 Windows 接入的硬前置。
- 保留 SimpleNet 使用的 Linux server workflow，统一 manifest 字段命名；Android、Windows、Linux 可有平台专属资产字段，但都必须包含 repository、commit、version、architecture、toolchain、sha256、rxDefault/oobDefault 和 source/license 信息。
- 发布 tag 必须由完整 commit 派生且不可变；已发布 tag 再次构建时必须比较 manifest 与资产，发现差异直接失败。
- 在 KunBox、SimpleNet、KunBoxForWindows 中只提交 lock/manifest/脚本和小型元数据，不提交 AAR、exe、server 二进制、私钥或现场抓包。

关键文件：RayBox `sing-box/build-libbox.ps1`、`.github/workflows/android-core.yml`、`.github/workflows/server-core.yml`，新增 Windows workflow；SimpleNet `scripts/fetch-raybox.mjs`、`third_party/raybox.source.json`；KunBox `app/build.gradle.kts`、新增 `.kernel-sync-local/sync-raybox.ps1`。

依赖：Phase 1。

### Phase 3：KunBox Android 接入

- 用 RayBox manifest 替换当前只检查 AAR/ABI 的构建门禁，校验 repository、完整 commit、tag、架构、两份 AAR SHA-256；保留现有 AAR 备份/回滚流程。
- 在三个 `PlatformInterface` 实现补齐 `useProcFS()`：普通 VPN 调用 `shouldExposeProcFsToLibbox(getProcFsReadable(), settings)`；Root 根据强制 connection-owner routing 返回 false；Proxy-only 和测试平台返回 false。保留现有 `findConnectionOwner()` 的 ProcFS/ConnectivityManager 回退策略。
- 将 `ConfigRepository.generateConfigFile()` → `buildRunOutbounds()` → `OutboundFixer` 作为唯一配置入口；扩展 `Outbound`/`TransportConfig`/解析器/导出器，使 Xray 专属节点生成 RayBox `type: "xray"` 的 `outbound`，而不是继续把 XHTTP/VLESS Encryption 静默丢弃或依赖外置 Xray。
- 复用现有的 TLS、Reality、WS、XHTTP 字段和校验；新增 RX/OOB 显式开关与边界校验，OOB 不自动开启，detour/TCP fast open 与 OOB 冲突时明确报错。
- 诊断中同时记录 sing-box version、可选 xrayVersion、RayBox manifest commit 和 AAR digest；不得用 `Libbox.version()` 单独识别内核来源。
- 保留所有 VPN、Root、Proxy-only、多进程 IPC、`VpnStateStore`、节点切换和现有安全补丁行为。

关键文件：`PlatformInterfaceImpl.kt`、`RootPlatformInterface.kt`、`ProxyPlatformInterface.kt`、`SingBoxCoreTestPlatform.kt`、`ConfigRepositoryPart5.kt`、`OutboundFixer.kt`、`SingBoxConfig.kt`、`SingBoxTransportConfig.kt`、`NodeLinkParser.kt`、`NodeLinkExporter.kt`、`app/build.gradle.kts`。

依赖：Phase 2。

### Phase 4：SimpleNet 统一接入

- 保留现有 `raybox.source.json` 和 `fetch-raybox.mjs` 的锁定/下载/SHA-256 校验，升级为共享 manifest 规则；不要覆盖 `simplenet-dispatch` 现有修改。
- 让 `SingBoxConfig.kt` 与 `XrayConfig.kt` 使用与 KunBox 相同的公共字段语义：`core`、协议、TLS/Reality、XHTTP、VLESS Encryption、RX、OOB、detour；继续将 RX/OOB 默认关闭。
- 补齐配置校验、节点导入/导出和启动前失败提示，确保不支持的 Xray 参数不会静默降级为 sing-box 配置。
- 对 Android AAR 做最小编译/启动/配置检查；对后端 server core 保留 commit、架构、版本和 SHA-256 绑定。
- 保留签名配置原文字节、RS256、受众、硬到期、防回滚高水位、撤销/墓碑和恢复预算。现有直连 OOB→RX fallback、前置去除 OOB 保留 RX 及能力协商不能被旧 README 口径覆盖；RX/OOB 只用于 Cloudflare 原范围。
- dispatch 只做本地兼容验证：保留 manifest/ELF/hash、config-test、版本目录、稳定检查、失败回滚和保持停止；不覆盖子仓现有改动，不部署。

关键文件：`scripts/fetch-raybox.mjs`、`third_party/raybox.source.json`、`app/build.gradle.kts`、`XrayConfig.kt`、`SingBoxConfig.kt`、`SingBoxVpnService.kt`、相关节点模型/解析器和 `docs/`。

依赖：Phase 2；配置契约依赖 Phase 3 的字段定义，但可并行实现后在 Phase 6 对齐。

### Phase 5：KunBoxForWindows 接入

- 新增 RayBox Windows 产物 lock/manifest 下载与 SHA-256/PE 架构校验，改造 `src-tauri/src/commands/kernel.rs` 的版本列表、下载、安装、回滚和本地版本读取；文件名和用户提示从 `sing-box.exe` 抽象为 RayBox 核心，但保留兼容迁移识别。
- 改造 `commands/singbox.rs` 的配置生成：优先生成 RayBox 原生 `type: "xray"` outbound，不再为 XHTTP 节点启动独立 `xray.exe` bridge。现有 `plugin-bridges.json`、`start_plugin_bridges()`、`build_xray_plugin_config()` 保留为明确的旧版本回滚路径，待原生路径验收后再移除。
- 统一 Windows 节点模型、前端编辑器和能力提示，增加 XHTTP/Encryption/RX/OOB 的合法字段与默认关闭策略；不能让 UI 允许核心不支持的组合。
- 补充启动/停止/重启、TUN、DNS、selector、节点切换和 latency 测试，验证 RayBox 原生 Xray outbound 与现有路由/插件链不互相污染。
- Windows 继续使用 sing-box 兼容 `version/check/run` 和回环 Clash API，不新增 FFI/DLL 控制层；正式与临时测速均切到原生 Xray outbound。进程清理只针对本客户端持有的 PID/句柄，不按全局映像名称终止其他应用。

关键文件：`src-tauri/src/commands/kernel.rs`、`commands/singbox.rs`、`state.rs`、`types.rs`、profiles 命令、前端 `KernelSettings.tsx`、`Profiles.tsx`、`node-editor.ts`、共享类型、`build.ps1`、新增 lock/manifest 文档。

依赖：Phase 2；Phase 5 的原生 Xray 配置依赖 Phase 1/3 的公共配置契约。

### Phase 6：三客户端配置契约与功能矩阵统一

- 固化一份不带密钥的配置契约文档，至少定义：`core`、`protocol`、`transport`、TLS/Reality、VLESS Encryption、XHTTP mode/extra、RX 参数、OOB fallback、detour、routing 和错误分类。
- 明确能力矩阵：sing-box 原生协议、RayBox Xray 支持的 VLESS/VMess/Trojan、XHTTP、WebSocket、Reality、Encryption、RX、OOB；每个客户端只实现 UI/解析/转换，公共语义由文档和 RayBox 校验约束。
- 对同一节点样例在 KunBox、SimpleNet、KunBoxForWindows 生成配置并进行结构 diff：字段值一致、核心选择一致、受保护拨号链不丢失、默认 RX/OOB 均关闭。
- 对旧配置做兼容迁移：保留普通 sing-box 节点；只有明确满足 Xray 专属能力时才升级为 `type: "xray"`，迁移失败必须给出节点级错误而不是静默删除。

依赖：Phase 3、Phase 4、Phase 5。

### Phase 7：验收、文档、回滚与长期维护

- RayBox：运行 `go test`（sing-box dialer/xray、Xray splithttp/TLS/conf），构建 Android AAR、Windows、Linux 产物并验证 manifest。
- KunBox：运行针对 `PlatformInterface`、配置转换、XHTTP/Encryption/RX/OOB、节点解析/导出的单元测试；执行 `detekt`，再做最小 debug 编译和配置检查。
- SimpleNet：运行现有脚本测试和 Android 单元测试，验证下载脚本锁定/篡改/校验失败路径；验证不影响 `simplenet-dispatch` 现有改动。
- KunBoxForWindows：运行 `cargo test`、`cargo check`、前端构建；对生成配置做 JSON/核心 `check`，验证启动、节点切换和 bridge 回滚。
- 端到端至少验证：普通 VLESS/VMess/Trojan、Reality、WS、XHTTP 普通模式、显式 RX、OOB fallback、detour 冲突拒绝、TUN/Proxy-only/Root 三种 Android 平台路径、Windows TUN 和 server core 产物。
- 每次验证记录命令、commit、产物 digest、设备/系统、结果和已知限制；不记录凭据、私钥、抓包或原始敏感日志。
- 回滚顺序：客户端 lock 回退到上一份已验证 RayBox commit；Android 恢复上一份 AAR，Windows 恢复上一份 exe，Windows 必要时启用旧 Xray bridge；配置层关闭 RX/OOB 或恢复旧普通 outbound；不删除用户配置和业务数据。
- 后续维护只允许通过 RayBox commit 更新和 manifest 校验；禁止客户端私自替换核心、增加独立 Xray 分支或复制内核补丁。

依赖：Phase 6。

## 与另一 Claude 会话的协作边界

- 另一个会话只负责其明确分配的单仓库只读盘点、实现或验证；先在对应项目文档登记文件范围和当前 HEAD。
- 不允许两个会话同时修改同一文件、重置工作树、替换共享 AAR/二进制或清理已有 dirty 文件。
- 每个阶段结束后通过项目文档记录 commit、改动文件、测试结果和待办；主会话负责合并契约和最终跨项目验收。

## 失败处理与停止条件

- 核心 Go 测试、manifest 校验、Android/Windows 启动检查任一失败，停止向后扩散，不用客户端兜底掩盖核心问题。
- 若 RayBox 无法提供 Windows 原生产物，则先完成 Android/SimpleNet 统一，Windows 保留可验证的旧 bridge 回滚路径，并把 Windows 原生产物列为阻塞项，不宣称三端已统一。
- 不进行提交、推送、发布或部署；这些动作另行获得明确授权后执行。

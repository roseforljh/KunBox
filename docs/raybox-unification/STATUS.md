## RayBox 0.0.1 正式内核（2026-11-08，最新）

- 内核版本改为语义版本 0.0.1；正式发布 `android-v0.0.1`（commit `a7271fd63a1d5946b7563f15827528e2d516f6b4`），标准 AAR SHA-256 `2add780c320bc75bd78abf2c82f8c6d582bb18a03ad2897dfd6c3bc3008e430a`，legacy `35f2a2e1923c4a0ae211534ccc5da2f0685a4ba89e590a8a3e78706de8b98b26`，manifest SHA-256 `e4da35d37376b55baa3a4fd7fa79d371ed85cd69d6c5ca0575a786d6f0c46a88`。
- lock 已更新为 `android-v0.0.1` + `version 0.0.1`；`tools/sync_raybox.py` 与 `app/build.gradle.kts` 同时接受旧 SHA 标签和新区语义标签两种身份。安装备份：`app/libs/raybox-backup/ec84bc1baf734209959e741a3a835f6c/`。

## Android 正式内核规则修正（2026-11-06）

- 上一版正式 AAR 对应的 RayBox 提交含四个 Wintun DLL，违反 RayBox 仓库禁止提交二进制的规则，已作废，不再使用。
- 修正后的正式双 AAR 已从 `8d7fac540fd5a8f74fe844f406fe435631e09294` 重建并严格安装；旧提交因包含 Wintun 二进制而作废。当前标准 AAR SHA-256 `6aef7b39e65cf6598abcddbcbdefc7173c3f5b4f43038274c1d94b731728205a`，legacy SHA-256 `f7d65c976af3c258ead70d7a557cf30ed5b1c97887fdd25e45d80e77a6278b6f`，manifest SHA-256 `51300df4e19c8e23b084a00b498732346f67bf961b7be8edddd1e62b5e3dd84f`。
- 修正后的 KunBox Release 构建已完成：`app-arm64-v8a-release.apk` 32,127,031 字节，SHA-256 `b326f0c51cc63f373a71d9487492ab1149d6f9f63dcc1f95b683e5c4b1505f2f`；`app-armeabi-v7a-release.apk` 32,078,609 字节，SHA-256 `8578923717b3ef22153a5a294a467e33b814a3d30174cc719e82d7778c1ce07f`。
- 两个 APK 均通过 `apksigner verify --verbose --print-certs`，退出码均为 0，使用 APK Signature Scheme v2；签名者为 `CN=KunK`，证书 SHA-256 `00223ed2458c0322265f7245c93aaa127cd68b74987eba4aa9aaa0125ed2ba8b`，两包证书一致。
- 构建元数据：versionName `v2.18.1-165-g0de087c7`，versionCode `6993`；内核来源为 RayBox commit `8d7fac540fd5a8f74fe844f406fe435631e09294`，标准 AAR SHA-256 `6aef7b39e65cf6598abcddbcbdefc7173c3f5b4f43038274c1d94b731728205a`。当前设备测试仍待用户连接设备后进行；旧 APK 仅作回退备份。


- 用户将本轮范围限定为 RayBox Android 内核正式化及 KunBox 测试，明确批准 RayBox 本地提交与 Android tag；其他客户端暂不处理。
- 正式 AAR 必须从干净 RayBox 提交重新构建，安装时验证实际 tag 解引用、源码摘要、工具链、两份 AAR 的 ABI/API/ELF/安全标记及哈希。不得给原 dirty AAR 重标正式身份。
- KunBox 本地/正式安装脚本 10 项测试和配置契约脚本 2 项测试通过；`detekt` 成功（任务为 up-to-date）。
- 正式双 AAR 已从干净提交 `da676acaa7f25cb9a5f804d2d22520eb6edd0732` 重建并严格安装，`dirty=false`；本地 tag 为 `android-da676acaa7f25cb9a5f804d2d22520eb6edd0732`，来源摘要 `b8c146cb757c57ebf228dfb39f1166226fcb2752737d156abc8d804c980fd5b7`。
- 标准 AAR：124567766 字节，SHA-256 `3ffbd5dd03562a4996178154b775f370f63c4565691d48e2a840cd030934a324`；legacy：104544454 字节，SHA-256 `7d28ff89b9e9fd1937cb47f902620af20dba653de2cc55c5f284b37caed9f743`。版本为 sing-box `1.14.3-da676acaa7f2` / Xray `26.10.31`，四 ABI、Java API、安全标记和 ELF 对齐通过。
- 固定元数据见 `raybox-android.lock.json`；该 lock 记录本地正式构建，不声明远端资产已发布。上一核心备份为 `app/libs/raybox-backup/7100d6f9275b48b595e6c90ef6508dfd/`。
- `verifyRayboxCore`、RayBox 转换器/OutboundFixer/平台/诊断定向测试及 `assembleRelease` 全部通过；Release APK 已签名并校验。arm64：32127107 字节，SHA-256 `d06955846c74ed3ab34af5e1422bcdb4d230d3f57e4181a8ed41f594ce1e556d`；armeabi-v7a：32078689 字节，SHA-256 `d188dc47376ffa976e16ae43553d949b61dba4446205577aa8ecfbb2b63efe32`。签名证书 SHA-256 为 `00223ed2458c0322265f7245c93aaa127cd68b74987eba4aa9aaa0125ed2ba8b`，构建耗时 4 分 40 秒。


以下为历史迁移记录，最新工件身份以正式构建记录与 `app/libs/raybox-build.json` 为准。

## 2026-11-05

- 完成四仓库只读盘点：KunBox、SimpleNet、RayBox-rx、KunBoxForWindows。
- 写入统一总计划：`C:\Users\33039\.claude\plans\parsed-squishing-pnueli.md`。
- 在本项目创建 `docs/raybox-unification/README.md`。
- 第一阶段为规划记录；当前已开始 RayBox 内核与构建门禁实现，客户端现有 AAR 保持不变。
- 2026-11-05 另一会话 `kunbox-7f` 明确当前无仓库/文件占用，暂不承接实现；本会话负责推进，子任务按文件范围隔离。
- 主会话复核 `BoxWrapperManager.kt:33-66` 和 RayBox `command_server.go:256-302`：实际硬依赖是已有 `pause/wake/resetNetwork`，删除总计划中恢复旧私有 API 的错误要求。
- 四仓 PLAN 已同步 NDK29、四 ABI、显式发布、签名安全与 Windows CLI/Clash API 复用约束。

## 当前硬阻塞

- RayBox 安全预算/sing-tun 补丁与平台接口、API/ABI 尚待定向验证；已有标准恢复 API 不缺失。
- SimpleNet Android/server 的 commit、tag、manifest 身份链不一致。
- Windows 原生 amd64 候选与三平台 workflow 已建立；Windows 客户端接入和下载链仍在实现。

## 本地纠错与验证（2026-11-05）

- 保留纯 `RayBoxOutboundConverter` 与模型字段；撤回旧 AAR 下提前启用的两个 `OutboundFixer` 接线，避免生成旧核心不认识的 `type=xray`。
- 转换器新增 TCP 参数不得静默丢失回归。定向执行 `RayBoxOutboundConverterTest` 5 项、`OutboundFixerTest` 43 项，共 48 项，失败/错误 0；Detekt 通过，整条 Gradle 命令耗时 37 秒。
- RayBox 安全门禁已恢复原 `system TCP connection limit reached: active=`，dirty 也必须严格通过；此前将其替换为 physical budget 标记并仅 warning 的做法已纠正。
- 两份 Android 候选清单已实检 `outputs` 为两个直接对象，但该候选仍缺 sing-tun TCP 限额，不接入客户端；正在受控源码目录移植补丁及 ICMP 预算。
- 新 AAR 安全/API/ABI全部通过后再一次性适配 VPN/Root/Proxy-only/测速平台并接通唯一运行配置入口。

## 本轮接入进度（以此节替代上方早期记录）

- 新 RayBox dirty AAR 已经通过 ZIP/四 ABI/ELF 对齐/Java API/安全标记检查并接入 KunBox；旧 AAR 保存在 `app/libs/raybox-backup/`。后续重复安装使用独立子目录，不覆盖最初备份。
- VPN、Root、Proxy-only、测速四条平台路径已适配新接口；修复 Root 强制 connection-owner routing 时仍误启用原生 ProcFS 的问题，平台策略定向测试 32 项通过。
- `OutboundFixer` 的运行与测速公共入口已接入 `type=xray`；当前转换与 Fixer 两组测试曾通过 49 项，正在补配置保真、显式 CF RX/OOB 与跨客户端 fixture。
- 构建不再接受缺失来源 metadata、未校验 SFA 替换或 dirty release；诊断启动日志同时记录两核版本、commit、源码摘要、AAR SHA-256 与 dirty 标记，诊断包保留构建身份。
- 本地安装及正式下载门禁 10 项脚本测试通过：来源/工具链/API/构建标签篡改、dirty 显式授权、远端错 tag、错 checksum、失败回滚和备份保留；另有跨端契约检查 2 项拒绝回归通过。
- RayBox 的路由、selector、VLESS、build_shared，以及 Xray RX/OOB/TLS/conf 定向 Go 测试通过；Windows workflow 的来源摘要算法已与公共格式统一。
- 最新综合 Gradle 验证成功：定向单元测试合计 252 项，失败 0、错误 0；`detekt`、`verifyRayboxCore` 和 `assembleDebug` 均成功。两份 ARM APK 已生成，真实 AAR 来源和原生库由门禁校验。

## 尚未完成的验收

- Android/Windows/server 三平台新候选已全部构建成功，完整 commit 为 `c8349f9ea677d56bc107b34349c4d4ebb5a5aa7e`，共同源码摘要为 `3d77f9554e5d4b2f95dbbca37f05717c6a75a72121e0db8a92ffa70d27b55bec`，dirty=true/tag=null。新 AAR 已安全安装并保留备份；工件指纹见 `CANDIDATE-VERIFICATION.json`。
- 新 AAR 来源 Gradle 门禁成功；release 实测以 `Dirty RayBox candidates are allowed only in local debug builds` 拒绝。Windows 新核心实际完成 check/run、Clash API 两次节点切换和自有进程停止；Linux amd64 实际 version/config-test 成功，三架构 ELF/size/hash 已复核。
- 原生核心 9 项配置实检通过：VLESS/VMess/Trojan XHTTP、WS TLS、Reality、显式 RX、OOB→RX fallback，以及 OOB 与 detour/TFO 两项冲突拒绝。客户端转换仍需最终综合验证。
- 正式不可变 commit/lock 需要明确提交授权，发布资产还需要独立发布授权；dirty 候选绝不冒用旧 HEAD 的正式工件。
- `adb devices -l` 当前无连接设备；手机 TUN/Root/Proxy-only 的真实运行验收还缺设备。Windows 已有用户进程在运行，候选检查只启动独立临时进程，不修改现有系统代理/TUN。


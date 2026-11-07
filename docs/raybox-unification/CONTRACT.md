# 工件与配置共同契约

## 来源与本地候选

- 唯一仓库 `roseforljh/RayBox-rx`，正式提交必须为40位小写十六进制 SHA。
- Android、Windows、server 分别使用 `android-<SHA>`、`windows-<SHA>`、`server-<SHA>`；实际 Git ref 解引用后的 commit 必须等于 lock，不接受只有标签名字匹配。
- 正式构建必须干净；`dirty=true` 候选仅用于本地验证，tag 为空，不消费到正式下载路径。构建前后核对 HEAD、dirty 与源码摘要，发生并发源码变化直接失败。
- 共同源码摘要算法为 `sha256-git-path-kind-content-v1`；本地三平台候选从同一冻结快照构建并比对摘要。生成缓存不纳入来源，`graphify-out/` 仅作本地关系图。
- Windows 实际 `version` 输出同时含 `sing-box version` 与 `Xray version`；主版本带至少12位提交前缀，`version --name` 保持兼容。
- 本地候选独立输出，不覆盖现有工件；新正式 SHA 需要经用户明确授权提交后才能固定，不假造提交，不将 dirty 代码标成旧 HEAD。
- manifest 至少含 `schemaVersion/repository/commit`、真实版本、工具链、架构、资产名字/size/sha256。Android 保留既有 `outputs[]`，server 保留 `assets`，平台扩展不要求无意义的 JSON 统一。
- 下载与发布重试均校验实际资产。已发布标签或资产不覆盖、不删除；来源或 hash 不一致直接失败。

## Android

- 标准与 legacy AAR 都含 `arm64-v8a/armeabi-v7a/x86/x86_64`；两个客户端最终 APK 仍可只交付 ARM32/64。
- Go1.27.3、JDK17、gomobile0.1.13、NDK29.0.14206865；标准 API24、legacy API21，legacy 不启用 Naive。
- 实检 AAR ZIP 中 JNI，ELF机器号分别183/40/3/62；64位 LOAD 满足16KiB、32位4KiB对齐。
- Java保留包 `io.nekohasekai.libbox`；校验公开方法签名 `CommandServer.pause/wake/resetNetwork:()V`、`PlatformInterface.useProcFS:()Z`、`Libbox.xrayVersion:()Ljava/lang/String;`。
- 每 ABI 保留 KunBox 拨号、WireGuard、目标改写和 sing-tun TCP 限额标记；禁止旧恢复、Tailscale、sing-box私有Encryption实现。Xray原生Encryption不在禁止范围。
- 安全标记只是构建门禁；不能替代 Go 定向测试、API验证及手机运行验证。

## Windows

- Windows x64 原生 `sing-box/cmd/sing-box` 外壳，包含内嵌 Xray；不把 `Xray-core/main` 的 server executable 当客户端核心。
- 保持 `version/check -c/run -c` 与回环 Clash API。manifest验证PE machine=0x8664、size/hash、实际双核版本、tags和完整来源。
- 不添加 FFI/DLL 控制层；有实际Naive依赖再纳入匹配的支持文件，不能凭空声明已有。
- 原生出站接入需同时改正式连接和测速；单个受管 RayBox 核心进程，无遗留独立 Xray bridge。停止只作用自有 PID/句柄，保留系统代理/PAC恢复。

## 服务端

- 当前 server 产物为同仓 `Xray-core/main`，Linux amd64/arm64/armv7；允许与客户端不同入口，但必须同一组合仓提交且保留许可证。
- manifest/ELF/架构/size/hash、配置检查、不可变目录、稳定检查、失败回滚和保持停止均保留。源码统一不表示跳过 dispatch 业务签名或节点准入。

## 节点与配置

- `core=sing-box|xray` 表示内部执行器，不改成品牌字符串 `raybox`。
- sing-box外壳控制TUN、DNS、路由和拨号；Xray专属节点生成 `type=xray`，内层 `outbound` 只允许 `protocol/settings/streamSettings/tag`，不允许 sockopt 绕开保护拨号。
- 仅支持内层 VLESS/VMess/Trojan；普通节点保持原配置路径。TLS/Reality/传输字段必须保真，不支持时明确节点级报错，不静默删除、降级或泄露密钥。
- RX/OOB默认关闭，只在原Cloudflare范围显式启用；保留已有直连OOB→RX fallback、前置去OOB保留RX。未具备产品范围上下文的公共转换器不得默认泛化开启。
- SimpleNet签名原文、RS256、受众、有效期、高水位、撤销/墓碑、安装token和恢复预算不变；回滚不能恢复失效节点。

## 发布

push只编译验证与保存CI artifact；`workflow_dispatch` 的 `publish` 默认false，仅显式true进入拥有contents:write的发布job。脚本能力不等于本轮发布授权；本轮不执行远程发布。

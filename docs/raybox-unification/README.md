# RayBox 统一迁移总计划

## 目标

KunBox、SimpleNet、KunBoxForWindows 统一消费 `D:\RayBox-rx` 生成的 RayBox 产物。RayBox 是 sing-box 外壳与内嵌 Xray outbound 的组合内核；客户端不再各自维护独立 Xray 分支。

## 统一规则

- 唯一内核源码：`roseforljh/RayBox-rx`。
- 每个产物必须绑定完整源码 commit、目标平台/架构、构建工具链、文件 SHA-256、源码与许可证信息。
- Android 使用 `libbox.aar`/`libbox-legacy.aar`；Windows 使用 RayBox 原生可执行文件；SimpleNet 后端使用 RayBox server core。
- 普通协议使用 sing-box outbound；Xray 专属 VLESS/VMess/Trojan 能力使用 RayBox `type: "xray"` outbound。
- RX/OOB 默认关闭，只允许显式合法配置；普通 XHTTP 成功不等于 RX/OOB 验收。
- `Libbox.version()` 不能作为 RayBox 身份证明，必须校验 manifest。

## 阶段

1. 统一四仓库源码身份、commit/tag、manifest 和工作树记录。
2. 将 KunBox 的物理拨号预算、连接归属、selector、域名改写、WireGuard 等补丁逐文件移植到 RayBox；保留当前实际依赖的 `CommandServer.pause/wake/resetNetwork`，不恢复已淘汰的私有 API。
3. 建立 Android、Windows、Linux/server 不可变产物和 SHA-256 发布门禁。
4. KunBox 接入 RayBox AAR，保留 VPN、Root、Proxy-only、IPC、状态持久化和现有安全逻辑。
5. SimpleNet 统一 Android AAR 与 server lock，并修复现有 commit/tag/build 身份不一致。
6. KunBoxForWindows 建立 RayBox Windows 原生产物链；原生 Xray outbound 验证前保留旧 bridge 作为回滚路径。
7. 固化三客户端配置契约，完成普通协议、Reality、WS、XHTTP、Encryption、RX/OOB、TUN/Root/Proxy-only 验收。

## 当前状态

- 本轮仅推进 Android 正式内核和 KunBox 测试；RayBox 已建立干净提交 `da676acaa7f25cb9a5f804d2d22520eb6edd0732` 及真实 Android tag，正式双 AAR 已构建、严格校验并安装到 KunBox，`dirty=false`。
- KunBox 的运行和测速已接入原生 `type=xray` 公共转换入口；最新安装工件和验证记录以 `STATUS.md`、`app/libs/raybox-build.json` 为准。
- 本地正式构建与远端 GitHub Release 分开：本轮已完成 KunBox Release APK 构建、签名和哈希校验；RayBox Android 仍以 `8d7fac540fd5a8f74fe844f406fe435631e09294` 的本地正式 AAR 身份固定，不声明远端资产已发布；旧 SimpleNet 错配 tag 不复用。
- 其他客户端及其既有改动暂不处理。

## 获取内核

本地候选：`python -I tools/sync_raybox.py --source D:/RayBox-rx --allow-dirty`。

本地正式工件：在对应 RayBox 干净源码中创建真实 `android-<commit>` tag 并运行 `sing-box/build-libbox.ps1` 后，执行 `python -I tools/sync_raybox.py --source D:/RayBox-rx`；不带 `--allow-dirty`，脚本验证本地真实 tag、当前源码摘要和两 AAR 后安装。

远端正式工件：`python -I tools/sync_raybox.py --source <对应干净源码> --release-lock <锁文件>`。锁文件必须含 `repository`、40 位 `commit`、匹配 `android-<commit>` 的 `tag`、`dirty=false` 与两 AAR 的 `sha256` 映射；脚本校验远端真实 tag、源码摘要、工具链、两份实际 AAR 后才原子安装。本轮不创建远端 Release，不把本地 lock 描述为远端可下载资产。

## 验收门禁

- RayBox：Go 单测、protected dialer/Xray、RX/OOB 边界、Android/Windows/Linux 产物 manifest。
- KunBox：配置转换、PlatformInterface、解析导出、XHTTP/Encryption/RX/OOB 单测，`detekt` 和最小编译。
- SimpleNet：下载锁定与篡改校验、Android 单测、server manifest 和回滚。
- Windows：`cargo check`、`cargo test`、前端 typecheck/build、配置 check、启动/停止/切换和 bridge 回滚。

## 回滚

客户端 lock 回退到上一份已验证 RayBox commit；Android 恢复上一份 AAR；Windows 恢复上一份 EXE，必要时启用旧 Xray bridge；配置层关闭 RX/OOB。不得删除用户配置或业务数据。

详细实施状态见 `STATUS.md` 和 `C:\Users\33039\.claude\plans\parsed-squishing-pnueli.md`。

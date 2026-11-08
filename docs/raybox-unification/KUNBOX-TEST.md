# KunBox 正式内核本地测试

## 范围

本轮只固定 RayBox Android 内核并构建 KunBox 测试包，其他客户端保持原样。内核正式源码为 `da676acaa7f25cb9a5f804d2d22520eb6edd0732`，本地 Android tag 为 `android-da676acaa7f25cb9a5f804d2d22520eb6edd0732`，源码摘要为 `b8c146cb757c57ebf228dfb39f1166226fcb2752737d156abc8d804c980fd5b7`。这不是远端 GitHub Release 已发布的声明。

正式双 AAR 已重新构建并安装，`dirty=false`；标准 AAR SHA-256 为 `3ffbd5dd03562a4996178154b775f370f63c4565691d48e2a840cd030934a324`。固定工件元数据见 `raybox-android.lock.json`；最终安装包的大小、SHA-256、签名校验和构建结果以本目录正式验证记录为准。

## 当前测试包（2026-11-06）

- arm64：`app/build/outputs/apk/release/app-arm64-v8a-release.apk`，32,127,107 字节，SHA-256 `d06955846c74ed3ab34af5e1422bcdb4d230d3f57e4181a8ed41f594ce1e556d`。
- armeabi-v7a：`app/build/outputs/apk/release/app-armeabi-v7a-release.apk`，32,078,689 字节，SHA-256 `d188dc47376ffa976e16ae43553d949b61dba4446205577aa8ecfbb2b63efe32`。
- 两个 APK 的签名校验均通过，证书 SHA-256 均为 `00223ed2458c0322265f7245c93aaa127cd68b74987eba4aa9aaa0125ed2ba8b`。
- `verifyRayboxCore`、RayBox 转换器/OutboundFixer/平台/诊断定向测试及 `assembleRelease` 全部通过；Gradle 构建耗时 4 分 40 秒。


- 优先使用 `arm64-v8a` 签名 Release APK，32 位设备使用 `armeabi-v7a`。
- 与原 Release 包保持同一 applicationId `com.kunk.singbox` 和签名，支持正常覆盖安装；不要为测试清除数据或卸载原包。
- 若系统提示签名冲突，先保留原安装与数据，提供提示或原包身份，不强制卸载。
- 手机界面由用户操作；助手仅通过 adb 读取用户指定的诊断文件和日志。

## 最短测试顺序

1. VPN/TUN：使用已知正常节点连接，检查网页访问、测速及连接状态是否一致。
2. 节点切换：普通节点与 XHTTP 节点互切，再停止、重新连接，检查实际访问是否跟随切换。
3. 后台恢复：切后台、息屏后恢复，检查 VPN 仍运行及访问恢复。
4. 若平时使用 Root 或 Proxy-only，再分别测试对应模式的启停和访问。
5. Reality、WebSocket、VLESS Encryption 使用各自已有可用线路测试；普通节点不应被强制转换为 Xray。
6. RX/OOB 默认关闭；只测试原 Cloudflare 范围内显式启用的节点。OOB 与 detour/TCP fast open 冲突应显示明确错误。

构建、配置检查和单元测试不替代上述真机网络验收。发现问题时记录模式、协议、操作步骤和大致时间，使用应用诊断导出，不公开原始凭据。

## 回退

上一版签名 APK 保存在 `D:/RayBox-rx/artifacts/kunbox-before-da676acaa7f2/`；核心安装工具另保留 `app/libs/raybox-backup/`，准确子目录见正式安装记录。

若 Android 因 versionCode 不允许旧包直接覆盖，不通过卸载或清除数据绕过；应恢复备份 AAR 并构建版本号不降低的回退包。保留原配置和业务数据。

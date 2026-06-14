<div align="center">

# KunBox for Android

> **OLED Hyper-Minimalist**
>
> 专为追求极致性能与视觉纯粹主义者打造的下一代 Android 代理客户端。
> <br/>摒弃繁杂，回归网络本质。

[下载安装](#-下载安装) • [功能特性](#-核心特性) • [协议支持](#-协议矩阵) • [项目架构](#-项目结构) • [快速开始](#-构建指南) • [交流群](https://t.me/+978J0WfmJLk4ZmQ1)

**[English](README.md)**

</div>

---

## 💖 赞助支持

感谢以下用户的慷慨支持：

通过 [ForZTH Sponsorship](https://sponsorship.forzth.com/github/roseforljh/KunBox) 支持 KunBox。

| 赞助者 | 金额 |
|:---|:---|
| [@WestWood](https://github.com/yuedaochangmendian) | ¥30 |

> 您的支持是我们持续开发的动力！如有意愿赞助，请通过 [Telegram](https://t.me/+978J0WfmJLk4ZmQ1) 联系我们。

---

## 📥 下载安装

### 从 GitHub Releases 下载

前往 [Releases 页面](https://github.com/roseforljh/KunBox/releases) 下载最新版本的 APK 文件。

> **提示**: 发布产物包含 `arm64-v8a` 与 `armeabi-v7a` 两个架构版本。

### 系统要求

| 项目 | 最低要求 |
|:---|:---|
| Android 版本 | Android 7.0 (API 24) |
| 架构 | arm64-v8a / armeabi-v7a |
| 存储空间 | 约 15MB |

### 安装方式

1. **直接安装**: 下载 APK 后点击安装（需要允许安装未知来源应用）
2. **ADB 安装**: `adb install KunBox-x.x.x.apk`

---

## ✨ 核心特性

### 🎨 OLED 纯黑美学 (Hyper-Minimalist UI)
区别于传统的 Material Design，我们采用了深度定制的 **True Black** 界面。不仅在 OLED 屏幕上实现像素级省电，更带来深邃、沉浸的视觉体验。无干扰的 UI 设计让关键信息（延迟、流量、节点）一目了然。

- **耿鬼动态效果**: 首页独特的耿鬼形象随 VPN 状态切换
- **流畅动画**: 基于 Jetpack Compose 的丝滑过渡动画
- **自适应图标**: 支持 Android 13+ 主题自适应图标

### 🚀 极致性能核心 (High-Performance Core)
基于 Golang 编写的 **Sing-box (libbox)** 下一代通用代理核心。
- **内存占用**: 相比传统核心降低 30%+
- **启动速度**: 毫秒级冷启动
- **连接稳定性**: 优秀的连接复用与保活机制
- **热重载支持**: 配置变更无需重启 VPN 服务

### 🛡️ 智能分流与规则集中心 (Smart Routing & RuleSet Hub)
内置强大的路由引擎，支持复杂的规则集匹配。
- **规则集中心**: 在线下载与管理海量规则集（GeoSite/GeoIP/AdGuard 等），支持 Source 与 Binary 格式。
- **精准应用分流**: 采用 `UID` + `Package Name` 双重匹配机制，有效解决部分系统环境下应用分流失效的问题。
- **灵活策略**: 支持 GeoSite、GeoIP、域名后缀、关键字、进程名等多种匹配维度。
- **自动更新**: 规则集支持定时自动更新

### ⚡ 便捷交互 (Quick Actions)
- **Quick Settings Tile**: 支持系统下拉栏快捷开关，无需进入应用即可一键启停 VPN。
- **桌面快捷方式**: 支持节点选择和 VPN 切换快捷方式
- **真·延迟测试**: 基于 URL-Test 的真实连接测试，准确反映 YouTube/Google 等目标网站的真实加载速度。
- **实时流量监控**: 通知栏实时显示上传/下载速度

### 🔄 后台保活与省电优化
- **智能保活**: 多层次的息屏保活机制
- **后台省电**: 可配置的后台自动休眠，平衡续航与可用性
- **快速恢复**: 从后台恢复时优化重连速度

---

## 🌐 协议矩阵

我们构建了全方位的协议支持网络，兼容市面上绝大多数代理协议与高级特性。

### 核心代理协议

| 协议 | 标识 | 链接格式 | 核心特性支持 |
|:---|:---|:---|:---|
| **Shadowsocks** | `SS` | `ss://` | SIP002, SIP008, AEAD (AES-128/256-GCM, Chacha20-Poly1305) |
| **VMess** | `VMess` | `vmess://` | WS, gRPC, HTTP/2, Auto Secure, Packet Encoding |
| **VLESS** | `VLESS` | `vless://` | **Reality**, **Vision**, XTLS Flow, uTLS |
| **Trojan** | `Trojan` | `trojan://` | Trojan-Go 兼容, Mux |
| **Hysteria 2** | `Hy2` | `hysteria2://` | 最新 QUIC 协议, 端口跳跃 (Port Hopping), 拥塞控制 |
| **TUIC v5** | `TUIC` | `tuic://` | 0-RTT, BBR 拥塞控制, QUIC 传输 |
| **WireGuard** | `WG` | `wireguard://` | 内核级 VPN 隧道, 预共享密钥 (PSK) |
| **SSH** | `SSH` | `ssh://` | 安全隧道代理, Private Key 认证 |
| **HTTP(S)** | `HTTP(S)` | `http(s)://` | HTTP/HTTPS 代理支持 |
| **SOCKS5** | `SOCKS5` | `socks5://` | SOCKS5 代理支持 |
| **AnyTLS** | `AnyTLS` | `anytls://` | 通用 TLS 包装, 流量伪装 |
| **Naive** | `Naive` | `naive+https://` | 原生 sing-box Naive 协议支持 |
| **xHTTP** | `xHTTP` | `xhttp://` | xHTTP 传输协议支持 |

### 订阅生态支持
- **Sing-box JSON**: 原生支持，特性最全。
- **Clash YAML**: 完美兼容 Clash / Clash Meta (Mihomo) 配置，自动转换策略组。
- **Standard Base64**: 兼容 V2RayN / Shadowrocket 订阅格式。
- **导入方式**: 支持 剪贴板导入、URL 订阅导入、二维码扫描、本地文件导入。

---

## 🏗️ 项目结构

本项目遵循现代 Android 架构的最佳实践，采用 MVVM 模式与 Clean Architecture 设计理念。

```
KunBox-Android/
├── app/src/main/java/com/kunk/singbox/
│   ├── core/              # libbox JNI 封装 (BoxWrapperManager, SingBoxCore)
│   ├── database/          # Room 数据库 (dao/, entity/)
│   ├── ipc/               # VPN 跨进程通信 (SingBoxIpcHub, VpnStateStore)
│   ├── model/             # 数据模型 (SingBoxConfig, RoutingModels, Settings)
│   ├── repository/        # 数据仓库层
│   │   ├── config/        # 配置构建器 (InboundBuilder, OutboundFixer)
│   │   ├── store/         # 设置存储
│   │   └── subscription/  # 订阅获取器
│   ├── service/           # Android 服务
│   │   ├── manager/       # VPN 生命周期管理 (CoreManager, ConnectManager)
│   │   ├── network/       # 网络监控
│   │   └── tun/           # TUN 设备管理
│   ├── ui/                # Jetpack Compose UI
│   │   ├── components/    # 可复用组件
│   │   ├── screens/       # 页面级 Composables
│   │   └── navigation/    # 导航配置
│   ├── utils/parser/      # 协议解析器 (NodeLinkParser, ClashYamlParser)
│   └── viewmodel/         # ViewModel 层
│
├── .kernel-sync-local/    # 仅本地使用的 sing-box 同步工作区与一键脚本
│   ├── sync-kernel.ps1    # 对齐官方最新稳定版、构建并替换 libbox.aar
│   └── upstream-sing-box/ # 官方 sing-box 工作树与 KunBox 补丁应用目标
│
└── config/detekt/         # 代码质量检查配置
```

### 架构亮点

#### 多进程架构
- VPN 服务运行在独立进程 (`:vpn_service`)
- UI 通过 `SingBoxIpcHub` 进行跨进程通信
- 使用 `VpnStateStore` (MMKV) 实现跨进程状态同步

#### VPN 数据流
```
SingBoxService -> CoreManager -> BoxWrapperManager -> libbox.aar
```

---

## 🛠️ 技术栈详情

| 维度 | 技术选型 | 说明 |
|:---|:---|:---|
| **Language** | Kotlin 1.9 | 100% 纯 Kotlin 代码，利用 Coroutines 和 Flow 处理异步流 |
| **UI Framework** | Jetpack Compose | 声明式 UI，Material 3 设计规范 |
| **Architecture** | MVVM | 配合 ViewModel 和 Repository 实现关注点分离 |
| **Core Engine** | Sing-box (Go) | 通过 JNI 与 Go 核心库通信 |
| **Database** | Room | 本地数据持久化 |
| **KV Storage** | MMKV | 高性能跨进程键值存储 |
| **Network** | OkHttp 4 | 用于订阅更新、延迟测试等网络请求 |
| **Serialization** | Gson & SnakeYAML | 高性能 JSON 和 YAML 解析 |
| **Build System** | Gradle 8.x | 混合构建系统支持 |
| **Code Quality** | Detekt | 静态代码分析与格式化 |

---

## 🚀 构建指南

### 环境要求

- **JDK**: 17 或更高版本
- **Android Studio**: Hedgehog (2023.1.1) 或更高版本
- **Go**: 1.24+ (仅编译 libbox 核心时需要)
- **NDK**: r29 或更高版本

### 克隆项目

```bash
git clone https://github.com/roseforljh/KunBox.git
cd KunBox
```

### 构建 Debug APK

```powershell
# Windows
.\gradlew assembleDebug

# macOS/Linux
./gradlew assembleDebug
```

### 构建 Release APK

Release 构建需要配置签名。创建 `signing.properties` 文件：

```properties
STORE_FILE=release.keystore
KEYSTORE_PASSWORD=your_keystore_password
KEY_ALIAS=your_key_alias
KEY_PASSWORD=your_key_password
```

然后执行：

```powershell
.\gradlew assembleRelease
```

### 同步 libbox 内核（仅本地工作区）

KunBox 现在统一使用 `.kernel-sync-local/` 做内核同步。该目录**不要提交到 git**。脚本默认会自动解析官方 `SagerNet/sing-box` 最新稳定版，并明确排除 GitHub `prerelease` 条目以及带有 `alpha`、`beta`、`rc` 后缀的 tag。

同步工作区现在默认复用安全缓存。不再执行 `git clean -fdx` 粗暴清空全部未跟踪内容，而是先对上游工作树做 `reset --hard`，再只清理已知垃圾；如果定向清理后仍残留 tracked / untracked 变更，会直接报错退出。脚本同时复用固定校验临时目录，并把 `libbox.aar.backup-before-replace.*` 收敛为最近 3 份。

在仓库根目录直接执行：

```powershell
.\.kernel-sync-local\sync-kernel.ps1
```

如需手动锁定某个官方 tag，可显式传参：

```powershell
.\.kernel-sync-local\sync-kernel.ps1 -Tag v1.13.4
```

当前检查时官方最新稳定版仍是 `v1.13.4`，但它不再是脚本里的固定默认逻辑。

脚本会依次完成：

1. 检查 Git、Go、Java 17、Android SDK、Android NDK、`gomobile`、`gobind`
2. 解析并打印目标官方 tag，然后确保 `.kernel-sync-local/upstream-sing-box` 存在，强制对齐到该 tag，并执行定向垃圾清理而不是清空整个缓存
3. 优先应用目标 tag 对应的本地 KunBox 扩展补丁；若不存在同名文件，则回退到当前可用的最小 KunBox 补丁继续尝试
4. 构建 `libbox.aar`
5. 校验新 AAR 至少仍导出：
   - `getKunBoxVersion`
   - `resetAllConnections`
   - `recoverNetworkAuto`
   - `checkNetworkRecoveryNeeded`
   - `closeAllTrackedConnections`
   - `getConnectionCount`
   - `closeIdleConnections`
6. 备份并替换 `app/libs/libbox.aar`，同时把历史备份裁剪到最近 3 份
7. 运行 `assembleDebug`、`testDebugUnitTest`、`detekt`

定向清理会处理历史 `tmp-sync-kernel-*`、`tmp-libbox-*-check`、`libbox-sources.jar`、`libbox-legacy-sources.jar`、`libbox-legacy.aar` 等明显垃圾，并清理上一次补丁应用遗留的未跟踪新增文件；同步成功后还会移除上游目录里临时生成的 `libbox.aar`。

如果脚本无法解析稳定版 tag、找不到任何 `patches/kunbox-*.patch`、在定向清理后仍检测到脏文件，或在 `git apply --check`、补丁应用、AAR 方法校验、Gradle 验证阶段报错，中断都是预期行为。仅仅缺少同名 `patches/kunbox-<tag>.patch` 已不再是前置阻塞。请按报错点修复 Kotlin 兼容层或内核导出层耦合，再重新执行脚本。

### 运行测试

```powershell
# 运行所有单元测试
.\gradlew testDebugUnitTest

# 运行特定测试类
.\gradlew testDebugUnitTest --tests "com.kunk.singbox.utils.parser.NodeLinkParserTest"

# 运行特定测试方法
.\gradlew testDebugUnitTest --tests "com.kunk.singbox.utils.parser.NodeLinkParserTest.testVmessLink"

# 运行 Detekt 代码检查
.\gradlew detekt
```

### 清理构建

```powershell
.\gradlew clean
```

---

## 📝 URL Scheme 支持

KunBox 支持通过 URL Scheme 快速导入配置：

```
kunbox://import?url=<subscription_url>
```

示例：
```
kunbox://import?url=https%3A%2F%2Fexample.com%2Fsubscription
```

---

## 🧪 测试

项目包含完整的单元测试覆盖：

| 测试类 | 说明 |
|:---|:---|
| `NodeLinkParserTest` | 协议链接解析测试 |
| `ClashConfigParserTest` | Clash 配置解析测试 |
| `ConfigRepositoryTest` | 配置生成测试 |
| `ModelSerializationTest` | 模型序列化测试 |
| `VpnStateStoreTest` | IPC 状态存储测试 |

运行测试前请确保：
1. 已配置 Android SDK
2. 已安装 NDK
3. 测试数据库目录可写

---

## 🤝 贡献指南

我们欢迎所有形式的贡献！

### 提交 Issue

- 使用清晰的标题描述问题
- 提供设备型号、Android 版本、应用版本
- 附上复现步骤和相关日志

### 提交 PR

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

### 代码规范

- 使用 4 空格缩进（不使用 Tab）
- 最大行长度 120 字符
- 类名使用 PascalCase，函数和变量使用 camelCase
- 提交前运行 `./gradlew detekt` 确保代码检查通过
- 禁止空 catch 块，使用 `Log.e()` 替代 `printStackTrace()`

---

## 📋 常见问题

### Q: 为什么安装后无法连接？
A: 请检查：
1. 是否授予 VPN 权限
2. 节点配置是否正确
3. 尝试切换不同的 DNS 设置

### Q: 如何导入订阅？
A: 支持多种方式：
1. 点击右上角 "+" 选择 "从剪贴板导入"
2. 长按订阅链接选择 "用 KunBox 打开"
3. 使用 URL Scheme: `kunbox://import?url=<url>`

### Q: 耗电量大怎么办？
A: 建议：
1. 开启"后台省电"功能
2. 减少不必要的规则集
3. 关闭不使用的功能（如详细日志）

### Q: 支持哪些 Android 版本？
A: 最低支持 Android 7.0 (API 24)，推荐 Android 10+ 以获得最佳体验。

## ❤️ 致谢与引用

本项目站在巨人的肩膀上，特别感谢以下开源项目：

* **[SagerNet/sing-box](https://github.com/SagerNet/sing-box)**: 下一代通用代理平台核心
* **[MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)**: 优秀的 Android 代理客户端参考
* **[v2ray/v2ray-core](https://github.com/v2ray/v2ray-core)**: V2Ray 团队为代理生态做出的开创性贡献
* **[Jetpack Compose](https://developer.android.com/jetpack/compose)**: 现代化的 Android UI 工具包

---

## 📝 许可证

```
Copyright © 2024-2025 KunK.

Licensed under the MIT License.
You may obtain a copy of the License at

    https://opensource.org/licenses/MIT

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

<div align="center">

**[⬆ 回到顶部](#kunbox-for-android)**

<sub>本项目仅供学习和研究网络技术使用，请遵守当地法律法规。</sub>

</div>

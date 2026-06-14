<div align="center">

# KunBox for Android

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4.svg?style=flat&logo=android)](https://developer.android.com/jetpack/compose)
[![Sing-box](https://img.shields.io/badge/Core-Sing--box-success.svg?style=flat)](https://github.com/SagerNet/sing-box)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat)](LICENSE)
[![Telegram](https://img.shields.io/badge/Telegram-Chat-blue?style=flat&logo=telegram)](https://t.me/+978J0WfmJLk4ZmQ1)
[![Downloads](https://img.shields.io/github/downloads/roseforljh/KunBox/total.svg?style=flat&logo=github)](https://github.com/roseforljh/KunBox/releases)

> **OLED Hyper-Minimalist**
>
> A next-generation Android proxy client designed for those who pursue ultimate performance and visual purity.
> <br/>Cut the clutter, return to the essence of networking.

[Download](#-download-and-installation) • [Features](#-core-features) • [Protocols](#-protocol-matrix) • [Architecture](#-project-structure) • [Quick Start](#-build-guide) • [Community](https://t.me/+978J0WfmJLk4ZmQ1)

**[中文文档](README_CN.md)**

</div>

---

## 💖 Sponsorship

Thanks to the following users for their generous support:

Thanks to [ForZTN](https://sponsorship.forzth.com/github/roseforljh/KunBox) for supporting KunBox.

| Sponsor | Amount |
|:---|:---|
| [@WestWood](https://github.com/yuedaochangmendian) | ¥30 |

> Your support is our motivation for continuous development! If you wish to sponsor, please contact us via [Telegram](https://t.me/+978J0WfmJLk4ZmQ1).

---

## 📥 Download and Installation

### Download from GitHub Releases

Visit the [Releases page](https://github.com/roseforljh/KunBox/releases) to download the latest APK file.

> **Note**: Release assets include `arm64-v8a` and `armeabi-v7a` builds.

### System Requirements

| Item | Minimum Requirement |
|:---|:---|
| Android Version | Android 7.0 (API 24) |
| Architecture | arm64-v8a / armeabi-v7a |
| Storage | ~15MB |

### Installation Methods

1. **Direct Install**: Download APK and tap to install (requires permission to install from unknown sources)
2. **ADB Install**: `adb install KunBox-x.x.x.apk`

---

## ✨ Core Features

### 🎨 OLED Pure Black Aesthetics (Hyper-Minimalist UI)
Unlike traditional Material Design, we've adopted a deeply customized **True Black** interface. Not only does it achieve pixel-level power saving on OLED screens, but it also brings a deep, immersive visual experience. The distraction-free UI design makes key information (latency, traffic, nodes) clear at a glance.

- **Gengar Dynamic Effect**: Unique Gengar character on the home page switches with VPN status
- **Smooth Animations**: Silky transition animations based on Jetpack Compose
- **Adaptive Icons**: Support for Android 13+ themed adaptive icons

### 🚀 High-Performance Core
Based on the **Sing-box (libbox)** next-generation universal proxy core written in Golang.
- **Memory Usage**: 30%+ lower than traditional cores
- **Startup Speed**: Millisecond-level cold start
- **Connection Stability**: Excellent connection reuse and keepalive mechanisms
- **Hot Reload Support**: Configuration changes without restarting VPN service

### 🛡️ Smart Routing & RuleSet Hub
Built-in powerful routing engine supporting complex rule set matching.
- **RuleSet Hub**: Online download and management of massive rule sets (GeoSite/GeoIP/AdGuard, etc.), supporting both Source and Binary formats.
- **Precise App Routing**: Uses `UID` + `Package Name` dual matching mechanism, effectively solving app routing issues in some system environments.
- **Flexible Policies**: Supports GeoSite, GeoIP, domain suffix, keyword, process name, and other matching dimensions.
- **Auto Update**: Rule sets support scheduled automatic updates

### ⚡ Quick Actions
- **Quick Settings Tile**: Support system dropdown quick toggle, no need to enter the app to start/stop VPN.
- **Desktop Shortcuts**: Support node selection and VPN toggle shortcuts
- **Real Latency Test**: URL-Test based real connection testing, accurately reflecting actual loading speed of YouTube/Google and other target websites.
- **Real-time Traffic Monitoring**: Notification bar displays real-time upload/download speed

### 🔄 Background Keepalive & Power Saving
- **Smart Keepalive**: Multi-level screen-off keepalive mechanism
- **Background Power Saving**: Configurable auto-sleep in background, balancing battery life and availability
- **Quick Recovery**: Optimized reconnection speed when returning from background

---

## 🌐 Protocol Matrix

We've built a comprehensive protocol support network, compatible with most proxy protocols and advanced features on the market.

### Core Proxy Protocols

| Protocol | Identifier | Link Format | Core Feature Support |
|:---|:---|:---|:---|
| **Shadowsocks** | `SS` | `ss://` | SIP002, SIP008, AEAD (AES-128/256-GCM, Chacha20-Poly1305) |
| **VMess** | `VMess` | `vmess://` | WS, gRPC, HTTP/2, Auto Secure, Packet Encoding |
| **VLESS** | `VLESS` | `vless://` | **Reality**, **Vision**, XTLS Flow, uTLS |
| **Trojan** | `Trojan` | `trojan://` | Trojan-Go compatible, Mux |
| **Hysteria 2** | `Hy2` | `hysteria2://` | Latest QUIC protocol, Port Hopping, Congestion Control |
| **TUIC v5** | `TUIC` | `tuic://` | 0-RTT, BBR congestion control, QUIC transport |
| **WireGuard** | `WG` | `wireguard://` | Kernel-level VPN tunnel, Pre-shared Key (PSK) |
| **SSH** | `SSH` | `ssh://` | Secure tunnel proxy, Private Key authentication |
| **HTTP(S)** | `HTTP(S)` | `http(s)://` | HTTP/HTTPS proxy support |
| **SOCKS5** | `SOCKS5` | `socks5://` | SOCKS5 proxy support |
| **AnyTLS** | `AnyTLS` | `anytls://` | Universal TLS wrapper, Traffic obfuscation |
| **Naive** | `Naive` | `naive+https://` | Native sing-box Naive protocol support |
| **xHTTP** | `xHTTP` | `xhttp://` | xHTTP transport protocol support |

### Subscription Ecosystem Support
- **Sing-box JSON**: Native support with full features.
- **Clash YAML**: Perfect compatibility with Clash / Clash Meta (Mihomo) configurations, automatic policy group conversion.
- **Standard Base64**: Compatible with V2RayN / Shadowrocket subscription formats.
- **Import Methods**: Supports clipboard import, URL subscription import, QR code scanning, local file import.

---

## 🏗️ Project Structure

This project follows best practices of modern Android architecture, adopting MVVM pattern and Clean Architecture design principles.

```
KunBox-Android/
├── app/src/main/java/com/kunk/singbox/
│   ├── core/              # libbox JNI wrapper (BoxWrapperManager, SingBoxCore)
│   ├── database/          # Room database (dao/, entity/)
│   ├── ipc/               # VPN inter-process communication (SingBoxIpcHub, VpnStateStore)
│   ├── model/             # Data models (SingBoxConfig, RoutingModels, Settings)
│   ├── repository/        # Data repository layer
│   │   ├── config/        # Configuration builders (InboundBuilder, OutboundFixer)
│   │   ├── store/         # Settings storage
│   │   └── subscription/  # Subscription fetcher
│   ├── service/           # Android services
│   │   ├── manager/       # VPN lifecycle management (CoreManager, ConnectManager)
│   │   ├── network/       # Network monitoring
│   │   └── tun/           # TUN device management
│   ├── ui/                # Jetpack Compose UI
│   │   ├── components/    # Reusable components
│   │   ├── screens/       # Screen-level Composables
│   │   └── navigation/    # Navigation configuration
│   ├── utils/parser/      # Protocol parsers (NodeLinkParser, ClashYamlParser)
│   └── viewmodel/         # ViewModel layer
│
├── .kernel-sync-local/    # Local-only sing-box sync workspace and one-click script
│   ├── sync-kernel.ps1    # Re-sync the latest official stable upstream, build and replace libbox.aar
│   └── upstream-sing-box/ # Official sing-box worktree + KunBox patch application target
│
└── config/detekt/         # Code quality check configuration
```

### Architecture Highlights

#### Multi-process Architecture
- VPN service runs in a separate process (`:vpn_service`)
- UI communicates across processes via `SingBoxIpcHub`
- Uses `VpnStateStore` (MMKV) for cross-process state synchronization

#### VPN Data Flow
```
SingBoxService -> CoreManager -> BoxWrapperManager -> libbox.aar
```

---

## 🛠️ Tech Stack Details

| Dimension | Technology | Description |
|:---|:---|:---|
| **Language** | Kotlin 1.9 | 100% pure Kotlin code, using Coroutines and Flow for async streams |
| **UI Framework** | Jetpack Compose | Declarative UI, Material 3 design specification |
| **Architecture** | MVVM | Separation of concerns with ViewModel and Repository |
| **Core Engine** | Sing-box (Go) | Communicates with Go core library via JNI |
| **Database** | Room | Local data persistence |
| **KV Storage** | MMKV | High-performance cross-process key-value storage |
| **Network** | OkHttp 4 | For subscription updates, latency tests, and other network requests |
| **Serialization** | Gson & SnakeYAML | High-performance JSON and YAML parsing |
| **Build System** | Gradle 8.x | Hybrid build system support |
| **Code Quality** | Detekt | Static code analysis and formatting |

---

## 🚀 Build Guide

### Environment Requirements

- **JDK**: 17 or higher
- **Android Studio**: Hedgehog (2023.1.1) or higher
- **Go**: 1.24+ (only needed when compiling libbox core)
- **NDK**: r29 or higher

### Clone Project

```bash
git clone https://github.com/roseforljh/KunBox.git
cd KunBox
```

### Build Debug APK

```powershell
# Windows
.\gradlew assembleDebug

# macOS/Linux
./gradlew assembleDebug
```

### Build Release APK

Release builds require signing configuration. Create a `signing.properties` file:

```properties
STORE_FILE=release.keystore
KEYSTORE_PASSWORD=your_keystore_password
KEY_ALIAS=your_key_alias
KEY_PASSWORD=your_key_password
```

Then execute:

```powershell
.\gradlew assembleRelease
```

### Sync libbox Core (Local-only)

KunBox now uses the local workspace `.kernel-sync-local/` for kernel sync. This directory is **not committed to git**. By default, the script resolves the latest official stable `SagerNet/sing-box` tag automatically and explicitly skips GitHub prerelease entries plus tags with `alpha`, `beta`, or `rc` suffixes.

The sync workspace now reuses safe caches by default. Instead of `git clean -fdx`, the script hard-resets the upstream worktree, removes only known generated garbage, fails explicitly if leftover tracked or untracked files still exist, reuses a fixed verification temp directory, and keeps only the newest 3 `libbox.aar.backup-before-replace.*` backups.

Run the one-click script from the repository root:

```powershell
.\.kernel-sync-local\sync-kernel.ps1
```

If you need to pin a specific official tag manually, pass it explicitly:

```powershell
.\.kernel-sync-local\sync-kernel.ps1 -Tag v1.13.4
```

At the time of writing, the latest official stable release still resolves to `v1.13.4`, but that value is no longer hardcoded as the default logic.

The script performs these stages in order:

1. Check Git, Go, Java 17, Android SDK, Android NDK, `gomobile`, and `gobind`
2. Resolve and print the target official tag, then ensure `.kernel-sync-local/upstream-sing-box` exists, hard-reset it to that tag, and run targeted garbage cleanup instead of wiping the whole cache
3. Prefer the matching local KunBox patch for the target tag, or fall back to the current available minimal KunBox patch if no exact filename exists
4. Build `libbox.aar`
5. Verify the built AAR still exports:
   - `getKunBoxVersion`
   - `resetAllConnections`
   - `recoverNetworkAuto`
   - `checkNetworkRecoveryNeeded`
   - `closeAllTrackedConnections`
   - `getConnectionCount`
   - `closeIdleConnections`
6. Backup and replace `app/libs/libbox.aar`, then trim old backups down to the newest 3 copies
7. Run `assembleDebug`, `testDebugUnitTest`, and `detekt`

The targeted cleanup removes obvious garbage such as stale `tmp-sync-kernel-*`, `tmp-libbox-*-check`, `libbox-sources.jar`, `libbox-legacy-sources.jar`, and `libbox-legacy.aar`, and also deletes untracked files that were created by a previous patch application. After a successful sync it also removes the temporary upstream `libbox.aar` copy.

If the script cannot resolve a stable official tag, cannot find any `patches/kunbox-*.patch`, detects leftover dirty files after targeted cleanup, or the selected patch stops on `git apply --check` / patch application, AAR method checks, or Gradle verification, treat that as a real coupling break. Missing an exact `kunbox-<tag>.patch` filename alone is no longer a blocker. Fix the Kotlin compatibility layer or the kernel export layer at the reported failure point, then rerun the script.

### Run Tests

```powershell
# Run all unit tests
.\gradlew testDebugUnitTest

# Run specific test class
.\gradlew testDebugUnitTest --tests "com.kunk.singbox.utils.parser.NodeLinkParserTest"

# Run specific test method
.\gradlew testDebugUnitTest --tests "com.kunk.singbox.utils.parser.NodeLinkParserTest.testVmessLink"

# Run Detekt code check
.\gradlew detekt
```

### Clean Build

```powershell
.\gradlew clean
```

---

## 📝 URL Scheme Support

KunBox supports quick configuration import via URL Scheme:

```
kunbox://import?url=<subscription_url>
```

Example:
```
kunbox://import?url=https%3A%2F%2Fexample.com%2Fsubscription
```

---

## 🧪 Testing

The project includes complete unit test coverage:

| Test Class | Description |
|:---|:---|
| `NodeLinkParserTest` | Protocol link parsing tests |
| `ClashConfigParserTest` | Clash configuration parsing tests |
| `ConfigRepositoryTest` | Configuration generation tests |
| `ModelSerializationTest` | Model serialization tests |
| `VpnStateStoreTest` | IPC state storage tests |

Before running tests, ensure:
1. Android SDK is configured
2. NDK is installed
3. Test database directory is writable

---

## 🤝 Contributing Guide

We welcome all forms of contributions!

### Submit Issues

- Use a clear title to describe the problem
- Provide device model, Android version, app version
- Include reproduction steps and relevant logs

### Submit PRs

1. Fork this repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push branch (`git push origin feature/amazing-feature`)
5. Create a Pull Request

### Code Standards

- Use 4-space indentation (no tabs)
- Maximum line length 120 characters
- Class names use PascalCase, functions and variables use camelCase
- Run `./gradlew detekt` before committing to ensure code check passes
- No empty catch blocks, use `Log.e()` instead of `printStackTrace()`

---

## 📋 FAQ

### Q: Why can't I connect after installation?
A: Please check:
1. Whether VPN permission is granted
2. Whether node configuration is correct
3. Try switching different DNS settings

### Q: How to import subscriptions?
A: Multiple methods supported:
1. Click "+" in the top right corner and select "Import from Clipboard"
2. Long press subscription link and select "Open with KunBox"
3. Use URL Scheme: `kunbox://import?url=<url>`

### Q: What to do about high battery usage?
A: Suggestions:
1. Enable "Background Power Saving" feature
2. Reduce unnecessary rule sets
3. Turn off unused features (like detailed logs)

### Q: Which Android versions are supported?
A: Minimum support is Android 7.0 (API 24), Android 10+ recommended for best experience.

## ❤️ Acknowledgments

This project stands on the shoulders of giants, special thanks to the following open source projects:

* **[SagerNet/sing-box](https://github.com/SagerNet/sing-box)**: Next-generation universal proxy platform core
* **[MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid)**: Excellent Android proxy client reference
* **[v2ray/v2ray-core](https://github.com/v2ray/v2ray-core)**: V2Ray team's pioneering contribution to the proxy ecosystem
* **[Jetpack Compose](https://developer.android.com/jetpack/compose)**: Modern Android UI toolkit

---

## 📝 License

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

**[⬆ Back to Top](#kunbox-for-android)**

<sub>This project is for learning and researching network technology only. Please comply with local laws and regulations.</sub>

</div>

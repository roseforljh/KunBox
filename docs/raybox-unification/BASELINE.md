# 迁移现场基线（2026-11-05）

本表记录开始实现前状态，不代表迁移候选已验收。不得用旧 commit 标注后续 dirty 构建为正式发布。

| 仓库 | 基线完整提交 | 开始前已有改动 |
|---|---|---|
| KunBox | `0de087c71d4d1bab13e4916e7f00c5883fc988fb` | `.gitignore` 与本轮迁移文档 |
| RayBox-rx | `c8349f9ea677d56bc107b34349c4d4ebb5a5aa7e` | `README.md`、未跟踪 `.github/workflows/server-core.yml`、迁移文档 |
| SimpleNet | `9e75a68265758a5d13e6810a52d755311a0b76b4` | `simplenet-dispatch` 现有修改、迁移文档 |
| KunBoxForWindows | `9653dce69549058288f90e4f8b628182f86aa73b` | `.playwright-mcp/`、迁移文档 |

## 接口复核

- KunBox `BoxWrapperManager.kt:33-66` 使用 `CommandServer.pause/wake/resetNetwork`。
- RayBox `sing-box/experimental/libbox/command_server.go:256-302` 已提供这三个方法。
- KunBox `app/src` 搜索旧计划列出的四个私有 API 无调用；不得为满足错误计划恢复旧接口。
- KunBox `.kernel-sync-local/sync-kernel.ps1:214-252` 要求四 ABI，并禁止旧私有连接恢复、sing-box 私有 VLESS Encryption 和 Tailscale 实现。Xray 自己的 Encryption 保留。

## 来源错配（主会话实证）

命令：

```text
gh api repos/roseforljh/RayBox-rx/git/ref/tags/android-f38675b6269e6d116e78931bce9880e30f587092 --jq .object
```

返回真实提交 `c8349f9ea677d56bc107b34349c4d4ebb5a5aa7e`，而 SimpleNet Android lock 声明 `f38675b6269e6d116e78931bce9880e30f587092`。不改写旧资产/标签；新客户端正式 lock 等待新的干净完整提交及相应真实资产。

## 旧 RayBox AAR 实检

`tools/check_android_artifacts.py` 检查标准/legacy 的 ZIP、四 ABI、ELF、关键 Java public 方法签名，结构检查通过。实际两份文件均有 ARM32、ARM64、x86、x86_64，与旧脚本两 ARM 声明不符。

| 文件 | 字节 | SHA-256 |
|---|---:|---|
| `artifacts/libbox/libbox.aar` | 141246738 | `0333400c60de3957d113ce537e46aa88c017696e375076a8085ef10a4651615c` |
| `artifacts/libbox/libbox-legacy.aar` | 121012379 | `bbffb1ff3839a66f6c787905d0ecbf1cc0819f05c8c706f5f89254739eab2486` |

64位 ELF LOAD 对齐16384字节；32位4096字节。启用 `--require-safety` 后旧产物被拒绝：四 ABI 均缺5项 KunBox 安全标记，且包含禁用的 `controlplane.tailscale.com`。不能用这些旧文件替换 KunBox。

## 初始验证

- `python -I D:/RayBox-rx/tools/test_check_android_artifacts.py`：3项通过。
- `build-libbox.ps1` PowerShell AST 语法：通过。
- `gradlew.bat :app:testDebugUnitTest --tests com.kunk.singbox.service.manager.PlatformInterfaceImplTest --console=plain`：46秒，30项、失败0、错误0；这是当前 KunBox 原 AAR 基线，不是新内核验收。

## 写入协作

另一会话 `kunbox-7f` 已明确不占文件、暂不承接实现。本会话分工：

- Go代理：RayBox `sing-box/` Go源码/依赖及安全定向测试。
- 发布门禁代理：Android/server 两条 workflow。
- Windows工件代理：`build-windows.ps1`、Windows workflow及对应本地验证。
- SimpleNet来源代理：fetch校验与脚本测试，不改 lock、Kotlin或dispatch。
- 主会话：Android构建脚本/真实AAR校验、四仓文档与验收协调。

新本地 Android dirty 候选独立写入 `artifacts/libbox-candidate/`，带源码摘要，不覆盖原工件；候选校验通过前不替换客户端核心。

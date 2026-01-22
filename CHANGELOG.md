# Changelog

## [2.4.6](https://github.com/roseforljh/KunBox/compare/v2.4.5...v2.4.6) (2026-01-22)


### Bug Fixes

* **ipc:** 修复从后台恢复后 UI 一直加载中的问题 ([4593912](https://github.com/roseforljh/KunBox/commit/4593912a9b7f2a42ead365e38a1ec21e32b9294f))
* **ipc:** 应用返回前台时重置所有连接，修复 TG 等应用一直加载中的问题 ([9f5a444](https://github.com/roseforljh/KunBox/commit/9f5a44402205f90ef7d703bb263016647976d204))
* **parser:** 修复 vmess 链接无法从剪贴板导入的问题 ([4c6046a](https://github.com/roseforljh/KunBox/commit/4c6046a3ca4a27716a4f5e620e018249a3e6194c))
* **ui:** InputDialog 输入框支持水平滚动 ([baa1d60](https://github.com/roseforljh/KunBox/commit/baa1d601014142420cec99ade3e9eedefc5dfcda))
* **ui:** 应用分流仅显示仅允许列表中的应用并移除直连选项 ([f58f50e](https://github.com/roseforljh/KunBox/commit/f58f50e642136c70dda0ac79d8682d19446ce44a))

## [2.4.5](https://github.com/roseforljh/KunBox/compare/v2.4.4...v2.4.5) (2026-01-18)


### Bug Fixes

* 同步官方内核 ([471ed80](https://github.com/roseforljh/KunBox/commit/471ed808fab514a6eb991be71ed9073e8ef8005a))

## [2.4.4](https://github.com/roseforljh/KunBox/compare/v2.4.3...v2.4.4) (2026-01-18)


### Bug Fixes

* **filter:** preserve filter keywords when switching filter modes ([1177d35](https://github.com/roseforljh/KunBox/commit/1177d355acbff542233c4523a35409fef4959c0e))
* **latency:** restore latency data when switching profiles ([7fc75a0](https://github.com/roseforljh/KunBox/commit/7fc75a0793336c44acbc8a02cff400c32a0664a7))
* **latency:** support latency testing for nodes from all profiles ([b8c5054](https://github.com/roseforljh/KunBox/commit/b8c5054b0a9e2cc0081c4d9a7f02e3c5994ab3e9))

## [2.4.3](https://github.com/roseforljh/KunBox/compare/v2.4.2...v2.4.3) (2026-01-18)


### Bug Fixes

* **db:** remove foreign key constraint to fix node latency persistence ([663966c](https://github.com/roseforljh/KunBox/commit/663966c415133a671754d69b4475fd5653d1a385))

## [2.4.2](https://github.com/roseforljh/KunBox/compare/v2.4.1...v2.4.2) (2026-01-17)


### Bug Fixes

* **data:** persist node latency and improve update check ([2a21b55](https://github.com/roseforljh/KunBox/commit/2a21b5519d4ebb858dd6798e1f71cefd418b2e51))
* **power:** enable main process self-termination for power saving ([549e079](https://github.com/roseforljh/KunBox/commit/549e079d8d54f91a1d282128a5fcdec697b68356))

## [2.4.1](https://github.com/roseforljh/KunBox/compare/v2.4.0...v2.4.1) (2026-01-17)


### Bug Fixes

* **ipc:** resolve UI stuck loading after Doze wake and proxy idle ([f834ff9](https://github.com/roseforljh/KunBox/commit/f834ff9b482b47dde81abaf245bcf68f268f0e07))
* **latency:** enforce user-configured timeout for single node test ([d2ce2b4](https://github.com/roseforljh/KunBox/commit/d2ce2b48beed652eeab54650f4b9f325dae802f9))
* **latency:** pass latency value directly in callback to fix race condition ([79a3ab6](https://github.com/roseforljh/KunBox/commit/79a3ab6a445474e58e293358069be4ad71498513))
* **memory:** optimize memory usage with LRU caches and static TypeTokens ([e8d4f87](https://github.com/roseforljh/KunBox/commit/e8d4f872b2970d37a25423b927da4cd54e4e890f))
* **notification:** persist activeLabel to VpnStateStore for cross-process sync ([a2d3b03](https://github.com/roseforljh/KunBox/commit/a2d3b03eb918e108912e8481b2ee03e160fb09c5))
* **parser:** handle empty string fields in Clash YAML hysteria2 nodes ([8910bac](https://github.com/roseforljh/KunBox/commit/8910bac91b3dbf8781e99950253218ece012a274))
* **power:** add background power saving with configurable delay ([572e921](https://github.com/roseforljh/KunBox/commit/572e9214c35483bf2296d28b084204b06c0bc1b2))

## [2.4.0](https://github.com/roseforljh/KunBox/compare/v2.3.1...v2.4.0) (2026-01-17)


### Features

* **core:** add kernel-level hot reload for config changes ([4a059bc](https://github.com/roseforljh/KunBox/commit/4a059bcefe2fdd9c9aa21701b5e9306137c46b2e))
* **routing:** smart domain recognition + fix domain routing priority ([63bbfc4](https://github.com/roseforljh/KunBox/commit/63bbfc4674a8bfb86e91c7bb148c28ccf8133e71))


### Bug Fixes

* **config:** node state persistence and profile node memory ([7ae5d66](https://github.com/roseforljh/KunBox/commit/7ae5d66e02f6132a537cbe7077d9cb083f14e00a))
* **parser:** handle multiple links in clipboard import ([b69d0d2](https://github.com/roseforljh/KunBox/commit/b69d0d252e8a55878a8a2ee1fc9bd21f900c124d))
* **service:** notification bar node switch not working ([fdc36b6](https://github.com/roseforljh/KunBox/commit/fdc36b66496f274e9644f2e52b88aca8de9cc54b))
* **service:** notification not dismissed when app removed from recents ([a6f55c0](https://github.com/roseforljh/KunBox/commit/a6f55c05e98a17a0a0f2935484793f3d3655b5c3))
* **shortcut:** node selection and VPN toggle not working from app shortcut ([6099561](https://github.com/roseforljh/KunBox/commit/60995613c0dd048cc3c1249d3bb0173f1b173c55))
* **vpn:** hot reload failure causes network loss ([42d4d14](https://github.com/roseforljh/KunBox/commit/42d4d1438f242bdb0a8cf4cb0f24aa6e5399f10c))
* **vpn:** per-app proxy settings not applied to TUN interface ([09d1605](https://github.com/roseforljh/KunBox/commit/09d1605d374c0e331e443f19382b61d98dfdbb36))

## [2.3.1](https://github.com/roseforljh/KunBox/compare/v2.3.0...v2.3.1) (2026-01-15)


### Performance Improvements

* implement 4 performance optimizations ([6b32e46](https://github.com/roseforljh/KunBox/commit/6b32e468511496e696652d25d584203048e61e5a))

## [2.3.0](https://github.com/roseforljh/KunBox/compare/v2.2.0...v2.3.0) (2026-01-15)


### Features

* **kernel:** add kernel-level HTTP fetch and URLTest extensions ([94718c5](https://github.com/roseforljh/KunBox/commit/94718c59246702cae51f185dddc9870fa66e041d))
* **nodes:** add profile selection for manual node creation ([ed9f8ae](https://github.com/roseforljh/KunBox/commit/ed9f8aea4b8f867d2cce1f3557a9df071344987a))
* **parser:** add Clash Meta advanced features support ([5b8e5df](https://github.com/roseforljh/KunBox/commit/5b8e5dfd5a5a56efa9514689812e9502fe0818b0))
* **update:** implement app version check and notification ([91fa6e2](https://github.com/roseforljh/KunBox/commit/91fa6e2b8ca5a672ae7a6813e958c3c014a9db24))


### Bug Fixes

* **latency:** resolve SS+ShadowTLS dependency for latency testing ([215a108](https://github.com/roseforljh/KunBox/commit/215a1080675face4095eb1b5bf49a98904182d26))
* 删除组逻辑 ([dc48bdb](https://github.com/roseforljh/KunBox/commit/dc48bdb974b99c184b889e3208b13794bd47f8e6))
* 只读节点信息 ([62f0a56](https://github.com/roseforljh/KunBox/commit/62f0a56a966f914b3f12411fcbc4c16556413900))


### Performance Improvements

* **network:** optimize throughput and enable GSO ([3e2cb1d](https://github.com/roseforljh/KunBox/commit/3e2cb1dcc69f5bbaf5fd8a0ca4a8a0e27f313e8d))
* **repository:** optimize node import performance and add Kryo serialization ([fd6e219](https://github.com/roseforljh/KunBox/commit/fd6e2193d44ed690d2938433f507a2986c5d29d4))
* **repository:** parallelize node extraction and subscription updates ([80aca3b](https://github.com/roseforljh/KunBox/commit/80aca3b371f8672916aeffa305dc90e494adcde9))

## [2.2.0](https://github.com/roseforljh/KunBox/compare/v2.1.8...v2.2.0) (2026-01-14)


### Features

* improve node link parsing and latency test settings ([b19c63e](https://github.com/roseforljh/KunBox/commit/b19c63e1574f141f1996583fddecd324bebf1850))
* **ipc:** improve VPN state management and add kernel build docs ([0bae618](https://github.com/roseforljh/KunBox/commit/0bae6186598726bf04744d64f8ef84ea49729a40))
* **latency:** persist node latency data and optimize libbox build ([c62c9c0](https://github.com/roseforljh/KunBox/commit/c62c9c01d592ea0a9354268ace1cbde05f54901c))
* **nodes:** add latency test progress bar with success/timeout stats ([744eb33](https://github.com/roseforljh/KunBox/commit/744eb330dd2865cf5431cf0d6d1c2353c438e49c))
* **nodes:** 优化搜索栏设计 ([c84a7e8](https://github.com/roseforljh/KunBox/commit/c84a7e87c469ae1b26e09179b2948d3a1cd258bf))
* **nodes:** 搜索栏跟随滚动显隐 ([be8ae78](https://github.com/roseforljh/KunBox/commit/be8ae78b22265a893cc16419337f9422dfb06798))
* **ui:** improve navigation animations and add gengar floating effect ([eacb729](https://github.com/roseforljh/KunBox/commit/eacb729e94542a7c19ab7d2ce7c64a73b29e28f2))
* **ui:** optimize app selector dialog layout and sort selected apps first ([3ee9e49](https://github.com/roseforljh/KunBox/commit/3ee9e490534a70eb60bbed880de0b77cedcba841))
* **ui:** sync node selector dialog state with nodes list ([d2e7e7a](https://github.com/roseforljh/KunBox/commit/d2e7e7abe9843e7aa431bbf46cf2d93df2605143))


### Bug Fixes

* **ipc:** 修复后台恢复时UI卡住的问题 - 参考NekoBox实现 ([5fcb6f7](https://github.com/roseforljh/KunBox/commit/5fcb6f7762b0a07c14f67962c1ca3b7fb3cf378b))
* **latency-test:** resolve no available network interface error ([08d8b45](https://github.com/roseforljh/KunBox/commit/08d8b456478198809efc03b6b9739839a6e3c6b7))
* **nav:** correct route mapping for Diagnostics and Logs pages ([0016b03](https://github.com/roseforljh/KunBox/commit/0016b03dffe9f91397f49a4c081857daefd72875))
* **rulesets:** initialize defaults with toggleable downloads ([2923af1](https://github.com/roseforljh/KunBox/commit/2923af1ca5046c9ab92afdb9591948b4b6a8e257))
* **rulesets:** load geosite list via github tree ([0a01fc8](https://github.com/roseforljh/KunBox/commit/0a01fc885998067915e3ed4f36fa1edd211b5ba9))
* **service:** 修复息屏后返回App时Telegram等应用一直加载的问题 ([d5cc889](https://github.com/roseforljh/KunBox/commit/d5cc8891bae11e38a65e73fb6d281e4b21a2afbc))
* **subscription:** 兼容订阅剩余/到期信息 ([c44c68d](https://github.com/roseforljh/KunBox/commit/c44c68da7a85a1e2db765936ae61eb8222f3b82f))
* **tun:** refine app list dialog quick select ([f2f0f3f](https://github.com/roseforljh/KunBox/commit/f2f0f3f5676b55ec19314876e162351bc70d1dd2))
* **ui:** add smooth crossfade animation for toggle icon transition ([9482e06](https://github.com/roseforljh/KunBox/commit/9482e0621c0322aad4657d61ef51a1bdd469f596))
* **ui:** adjust ProfileCard layout weight for better text display ([fe2369b](https://github.com/roseforljh/KunBox/commit/fe2369b3da899d7550527d97570071147a3de0e5))
* **ui:** clean build warnings and consolidate stability notes ([fe669be](https://github.com/roseforljh/KunBox/commit/fe669be56761f250d418db47fa0e03a633cd01d6))
* **ui:** improve latency display placeholder ([e26c45f](https://github.com/roseforljh/KunBox/commit/e26c45f3d2f8fa8827e668db03c9c3a1953c7891))
* **ui:** prevent gengar image clipping during crossfade animation ([dc3e93c](https://github.com/roseforljh/KunBox/commit/dc3e93ca1bc7fd00e8a5b4910b0f8e5ea1835fc7))
* **ui:** toggle fab visibility on scroll ([fc8b2af](https://github.com/roseforljh/KunBox/commit/fc8b2afd5817a17ad6a7be09554263a078284e00))
* **ui:** 列表到底部时隐藏悬浮按钮 ([708b0ff](https://github.com/roseforljh/KunBox/commit/708b0ff69c2610836b1acdc4abd074723a2416f3))
* **ui:** 调整 BigToggle 图像显示 ([b3af454](https://github.com/roseforljh/KunBox/commit/b3af4542ff40bde528e808ef2f8e33d964103762))

## [2.1.8](https://github.com/roseforljh/KunBox/compare/v2.1.7...v2.1.8) (2026-01-10)


### Bug Fixes

* **settings:** 移除断线重连功能和UI ([57a044e](https://github.com/roseforljh/KunBox/commit/57a044eff54fc6fd17b1fb8ddb2d44f4789d70ae))
* **subscription:** 修复取消导入后弹窗重复弹出及应用无法使用自身代理的问题 ([37e4b1b](https://github.com/roseforljh/KunBox/commit/37e4b1b5d2e71360177e32c5bddac1c1e35da507))
* **vpn:** 修复多个 VPN 启动和节点切换问题 ([684c9fa](https://github.com/roseforljh/KunBox/commit/684c9fac81cde7daf74f017de9a0f28542f223c1))
* **vpn:** 修复息屏后 Telegram 等应用卡在加载中的问题 ([de6c7ef](https://github.com/roseforljh/KunBox/commit/de6c7eff56ee06609cc19878a7a5fb815c093430))
* 修复跨配置切换节点时无法上网的问题 ([c953cf3](https://github.com/roseforljh/KunBox/commit/c953cf3aafbe26ea459c269e37a3233ea3f1cd9b))

## [2.1.7](https://github.com/roseforljh/KunBox/compare/v2.1.6...v2.1.7) (2026-01-09)


### Bug Fixes

* ? ([ce75fb3](https://github.com/roseforljh/KunBox/commit/ce75fb3b7ad19b183aa1d8fcac66560357e490bb))
* 2 ([14dff9d](https://github.com/roseforljh/KunBox/commit/14dff9dfece056188d862bc683b54f2546d726ea))
* bug ([daea0e7](https://github.com/roseforljh/KunBox/commit/daea0e7f8c845104114261e6aec5c91f834ecc7c))
* huawei ([50e13fc](https://github.com/roseforljh/KunBox/commit/50e13fc67575c2392f13d38f4b9018e5cd178609))
* 优化 SingBoxCore 代码注释和错误处理 ([9619b59](https://github.com/roseforljh/KunBox/commit/9619b59b1e9cb33fcda7ee75d8338f4a5658b372))
* 优化构建配置和 ProGuard 混淆规则 ([217080d](https://github.com/roseforljh/KunBox/commit/217080d2679892361927977f542e196401cff229))
* 修复从通知栏进入app后返回键需按两次才能退出的问题 ([814b4d3](https://github.com/roseforljh/KunBox/commit/814b4d37749f33266bb01c6d494f63cd23a70ecb))
* 新增 URL Scheme 深度链接支持和问题修复文档 ([9b3f8b3](https://github.com/roseforljh/KunBox/commit/9b3f8b34bc9e27079c319864b1dd4679ca536f04))
* 新增应用启动时清理遗留临时数据库文件的功能 ([eb21abb](https://github.com/roseforljh/KunBox/commit/eb21abb803f0031197f138d486ad3165a3dfe98c))
* 新增项目文档索引和优化指南 ([d0c89c2](https://github.com/roseforljh/KunBox/commit/d0c89c240f259fea326d746c909042be52cd570c))
* 更新 planning-with-files submodule 和临时修复代码 ([9bf826d](https://github.com/roseforljh/KunBox/commit/9bf826d907c96dab2d24aec38e1c30d89019e60e))
* 更新优化后的 libbox.aar ([421de3b](https://github.com/roseforljh/KunBox/commit/421de3b22c6cd204b5f09cef74b4f6d6d4bc1a8f))
* 清理不必要的临时文件和 planning-with-files ([372f471](https://github.com/roseforljh/KunBox/commit/372f471ebd6c8d9f5dc90e392facfa72e27c40fd))
* 清理冗余调试日志以提升性能和可读性 ([2986e53](https://github.com/roseforljh/KunBox/commit/2986e5322a00e3e37dea860417b3b3b59cf439b5))
* 清理废弃的构建脚本并优化 libbox 构建流程 ([8d87215](https://github.com/roseforljh/KunBox/commit/8d8721557717d3aff3fb4f643ebe15d2d97756f4))
* 移除内核版本管理功能并优化核心服务 ([c661d71](https://github.com/roseforljh/KunBox/commit/c661d718cff1630d9700d1fcd54a5afdefcb887d))
* 移除已过时的 bugfix 文档 ([df0dc64](https://github.com/roseforljh/KunBox/commit/df0dc643c75b8e6588e7429fe50cb5b9c6911196))
* 统一 TUN MTU 默认值并移除冗余日志 ([3ea5b7b](https://github.com/roseforljh/KunBox/commit/3ea5b7b9200bc9969dbdc0ed1f3c9d779a259d9a))

## [2.1.6](https://github.com/roseforljh/KunBox/compare/v2.1.5...v2.1.6) (2026-01-08)


### Bug Fixes

* **core:** 修复 VPN 服务因 bbolt 数据库并发冲突导致自动停止的 bug ([a2e689a](https://github.com/roseforljh/KunBox/commit/a2e689a71d2970a0290c2d323b5b051f6323ea4d))
* **core:** 彻底修复 bbolt panic - 根本原因是多进程并发访问同一数据库 ([497b0d9](https://github.com/roseforljh/KunBox/commit/497b0d9751840611c417a2dc26900c2914c152ad))
* **core:** 确保测试数据库目录存在 - 添加 mkdirs() 调用 ([b76a1e6](https://github.com/roseforljh/KunBox/commit/b76a1e6bc3d8db3952e4f13f3b9240c664d21007))
* internet op ([b4c4d0f](https://github.com/roseforljh/KunBox/commit/b4c4d0fcb03a8f964835cdf9b5c1de83d9fb4923))
* **vpn:** 修复VPN自动关闭和启动期间连接泄漏问题 ([3d9f2f2](https://github.com/roseforljh/KunBox/commit/3d9f2f21cf0576e7bd459bcdacd85ffa70fabd7b))
* **vpn:** 全面加固 VPN 息屏保活机制,防止被系统杀死 ([826bee3](https://github.com/roseforljh/KunBox/commit/826bee388ca2e5d40025bb0f2cbb2f88d7e24791))
* **vpn:** 添加周期性健康检查防止 VPN 服务僵尸状态 ([7426735](https://github.com/roseforljh/KunBox/commit/7426735cd84be66fe4fb973158ba41b00e7df237))
* 优化 ([c9036f1](https://github.com/roseforljh/KunBox/commit/c9036f115a37eef2ee12c8ebad6f010e2fbf9037))
* 修复节点选择状态未正确保存和显示的问题 ([4272779](https://github.com/roseforljh/KunBox/commit/42727791c22bbe99c88578a6719e6d6d5aa183ea))

## [2.1.5](https://github.com/roseforljh/KunBox/compare/v2.1.4...v2.1.5) (2026-01-04)


### Bug Fixes

* 优化延迟测试 ([ec969a0](https://github.com/roseforljh/KunBox/commit/ec969a0f02bc3b3bbfccb7ce56a127b720e5a532))
* 优化延迟测试 ([813c358](https://github.com/roseforljh/KunBox/commit/813c35883c518a8d8dbb6a018560d4cd93b3ee6b))
* 优化订阅更新逻辑 ([ccae9b1](https://github.com/roseforljh/KunBox/commit/ccae9b1abd5de21a3834d8eb6977c5bb1d4c6824))
* 可能解决了一些闪退的问题 ([9d67bb8](https://github.com/roseforljh/KunBox/commit/9d67bb808831a7ec225192b615da31efff01b876))
* 多处优化 ([988b49c](https://github.com/roseforljh/KunBox/commit/988b49c9cc1154a0c7c55090b17b9007fdaa21f8))
* 实时显示上传和下载速度 ([a312d2e](https://github.com/roseforljh/KunBox/commit/a312d2e4a0895c707c9bf2f18ca92cf2d73ba2c0))
* 部分订阅节点丢失 ([c766968](https://github.com/roseforljh/KunBox/commit/c7669681064ce9862762552ea19234914f9836e9))
* 长按功能 ([a5d6c4c](https://github.com/roseforljh/KunBox/commit/a5d6c4cdbb246035abe9b4a24418bb7e5e97a209))

## [2.1.4](https://github.com/roseforljh/KunBox/compare/v2.1.3...v2.1.4) (2026-01-03)


### Bug Fixes

* Add English;随便修了一些bug ([24d58a3](https://github.com/roseforljh/KunBox/commit/24d58a3542b62281edc2398c9937f8c1ffbd554d))
* 优化网络稳定 ([5dbe537](https://github.com/roseforljh/KunBox/commit/5dbe53795435cf29e1488c0ee883fa014e0c2fd1))
* 关于应用里加上app本体及singbox的版本号 ([520f158](https://github.com/roseforljh/KunBox/commit/520f1583d301f30cb82b184d2e1f737f8ca774fc))
* 实现配置管理，加号按钮里面本地文件和扫描二维码 ([24e41ee](https://github.com/roseforljh/KunBox/commit/24e41ee59a6fe44793f4695f1936ce5267d695b6))
* 性能优化 ([0f271df](https://github.com/roseforljh/KunBox/commit/0f271dfe88f0052ec0ef7b02253417f1a54f7f25))
* 更新镜像、添加导入导出数据功能 ([1c2c39d](https://github.com/roseforljh/KunBox/commit/1c2c39de187f4cb5788f82fe3c8281999c977ebe))
* 移除节点名称自动添加协议后缀 ([797ec2b](https://github.com/roseforljh/KunBox/commit/797ec2b327dcb9df70e5825864a7e7b887f08704))
* 规则集定时自动更新 ([e5da3c9](https://github.com/roseforljh/KunBox/commit/e5da3c9bd6b03f4efa7f0659f32251ff88de3672))
* 订阅定时更新 ([4312b63](https://github.com/roseforljh/KunBox/commit/4312b6348ec03f16ecff473005e8a051aa1f51b3))
* 长按图标出现的操作选项 ([a7c1e1c](https://github.com/roseforljh/KunBox/commit/a7c1e1c106e9797cddf693c189e88eab7dfeaceb))

## [2.1.3](https://github.com/roseforljh/KunBox/compare/v2.1.2...v2.1.3) (2025-12-31)


### Bug Fixes

* bug修复 ([c531ab3](https://github.com/roseforljh/KunBox/commit/c531ab3d11790f7513c722e0f02749f115205064))
* 优化重连、断连、热重载 ([80890a4](https://github.com/roseforljh/KunBox/commit/80890a4e5aa9743300b7060e9cd8d9c3abc3772e))
* 修复了热切换节点后 连接延迟的问题 ([d0b9027](https://github.com/roseforljh/KunBox/commit/d0b9027814fdfd5d1a0178e0cee00c5b1d74e717))
* 增加日志开关 ([772aa41](https://github.com/roseforljh/KunBox/commit/772aa4112dd3ff3e70c3f0ee3477185dc01656b7))
* 稳定网络 ([6c0e3f0](https://github.com/roseforljh/KunBox/commit/6c0e3f08ab06e765b7c6b5acf60d0b41262926f6))

## [2.1.2](https://github.com/roseforljh/KunBox/compare/v2.1.1...v2.1.2) (2025-12-30)


### Bug Fixes

* ：JSON 格式不标准 (Gson 解析异常) ([8ec60eb](https://github.com/roseforljh/KunBox/commit/8ec60ebd939dd08a05062d6deaebe6ad7b26c75d))
* ui控制参数 ([6ae7bfb](https://github.com/roseforljh/KunBox/commit/6ae7bfbad2e35cc2a85284f2cc025dbc721a33ee))
* 主题适配 ([2d82a90](https://github.com/roseforljh/KunBox/commit/2d82a90314f9e5f9693abf109aa9b27bec01fc5c))
* 全局禁用endpoint_independent_nat和auto_route ([9f3b3f4](https://github.com/roseforljh/KunBox/commit/9f3b3f46c2643dade479c0b2f7693fd429482267))
* 底部导航切换过渡动画、磁贴按钮 ([6c69d4e](https://github.com/roseforljh/KunBox/commit/6c69d4e77d3b35e40310b8784bca6f3d1a2b5e14))
* 解决 VPN 快速重启导致的网络接口不同步问题 ([0052091](https://github.com/roseforljh/KunBox/commit/0052091e2fbf1bd18300e53fe9bd815aa201844b))
* 解析器识别 anytls 节点 ([63d0c1b](https://github.com/roseforljh/KunBox/commit/63d0c1b93a790020f7b2d1dd40705f905524e092))
* 首页设计 ([3d31cf9](https://github.com/roseforljh/KunBox/commit/3d31cf9c76c209761c25b0e1511624254453621f))

## [2.1.1](https://github.com/roseforljh/singboxforandriod/compare/v2.1.0...v2.1.1) (2025-12-30)


### Bug Fixes

* force release pr generation ([ffcdc99](https://github.com/roseforljh/singboxforandriod/commit/ffcdc99ef13e6b3c3dd0aabc4050db3b33349e78))

## [2.1.0](https://github.com/roseforljh/singboxforandriod/compare/v2.0.7...v2.1.0) (2025-12-30)


### Features

* add release-please workflow to auto-create release PRs ([c0bb4e7](https://github.com/roseforljh/singboxforandriod/commit/c0bb4e7f7f48b8ebfda83ad4c9942accd8ec67f0))
* update workflow logic and dynamic versioning ([0fef687](https://github.com/roseforljh/singboxforandriod/commit/0fef68710ad0c4e62b2ffbd489ae48bd6e089b62))


### Bug Fixes

* 多节点闪退 ([a434516](https://github.com/roseforljh/singboxforandriod/commit/a4345164701d540912c112de744eeaf6d03f8803))

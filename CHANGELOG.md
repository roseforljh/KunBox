# Changelog

## [2.19.1](https://github.com/roseforljh/KunBox/compare/v2.19.0...v2.19.1) (2026-07-12)


### Bug Fixes

* 修复 urltest idle_timeout、端口区间与本地假节点过滤 ([ae1d716](https://github.com/roseforljh/KunBox/commit/ae1d7165a8f5eb78cb8b0aa71e1db34a01366ca8))
* 修复国内 DNS、配置卡灰边与延迟即时刷新 ([4a8af9c](https://github.com/roseforljh/KunBox/commit/4a8af9cbc11f6b375d4415f050d06a186a781ab4))
* 修复节点排序时选中边框滞后 ([3d7e0fc](https://github.com/roseforljh/KunBox/commit/3d7e0fcd0d139d4fe61a3bf322ba99f1b3c0733a))
* 清理冗余 SelectorManager/ConnectManager，提取 StringIteratorImpl，修复 WireGuard 默认路由与复杂度检测 ([4f1a89a](https://github.com/roseforljh/KunBox/commit/4f1a89ab80cef8af693942fee3ab770a4255fc3b))

## [2.19.0](https://github.com/roseforljh/KunBox/compare/v2.18.1...v2.19.0) (2026-07-08)


### Features

* optimize kotlin 2.4 upgraded stack ([4e66d8e](https://github.com/roseforljh/KunBox/commit/4e66d8e2ec744c0efff9082f75c89b010b73161d))


### Bug Fixes

* adjust bottom nav safe padding ([39b26cd](https://github.com/roseforljh/KunBox/commit/39b26cd05c03ea1f89f5e6f49178cdff92819bf8))
* adjust latency probe dns fallback ([1abdc2d](https://github.com/roseforljh/KunBox/commit/1abdc2dde26a02ddc1cdc8e44803e03564fc7b2b))
* collapse split implementations ([c77217c](https://github.com/roseforljh/KunBox/commit/c77217c2c5f250b3018d69faf7945a1bb7cceed6))
* improve debug config logging ([4d49620](https://github.com/roseforljh/KunBox/commit/4d49620e233edc945b5d13eba4e462fc51c21b02))
* improve ruleset badge contrast ([20526dd](https://github.com/roseforljh/KunBox/commit/20526dd91bb392f8261a595080dda7d4bb7f4c2d))
* merge PR [#101](https://github.com/roseforljh/KunBox/issues/101) liquid glass UI fixes ([874b97a](https://github.com/roseforljh/KunBox/commit/874b97a6547e871eafcc437789db19bba2ea234b))
* queue vpn restart after shutdown ([3078c13](https://github.com/roseforljh/KunBox/commit/3078c13f7818f3e2918604cbde2fb11e2b314f9e))
* refresh detekt baseline ([a80fa3b](https://github.com/roseforljh/KunBox/commit/a80fa3b9c5c58f13406fd6fcea3c7b5131b19508))
* remove unused dependencies and obsolete files ([e2de144](https://github.com/roseforljh/KunBox/commit/e2de1445713d21901959aad663ae806df3769c6a))
* serialize node switching ([a109540](https://github.com/roseforljh/KunBox/commit/a1095402b3f54b42859c4be954173c246faa8254))
* strip latency runtime ECH metadata before config JSON serialization ([6e94328](https://github.com/roseforljh/KunBox/commit/6e94328f13705414fbd5163682060a3a56d51246))
* sync vpn tile and node add state ([a3c82d8](https://github.com/roseforljh/KunBox/commit/a3c82d816c98d759fb6b0009658c2e252c738da0))
* upgrade kotlin android dependencies ([9b077a8](https://github.com/roseforljh/KunBox/commit/9b077a8740c90f0c5d5da86c0f400d12b4b4b232))
* 优化健康探测与自动切换 ([510ad0a](https://github.com/roseforljh/KunBox/commit/510ad0a56617029fd37ea4edda49b3d5c9049de2))
* 优化健康探测自动切换 ([e9a0438](https://github.com/roseforljh/KunBox/commit/e9a0438142ea85407a5f96e0682f480d7da5343a))
* 优化液态玻璃与服务状态同步 ([24d31c8](https://github.com/roseforljh/KunBox/commit/24d31c891470703b44287d83c06a392e27113d41))
* 修复订阅更新闪退和卡片布局 ([b2fd191](https://github.com/roseforljh/KunBox/commit/b2fd19174eeb53040c4f602ec56f80aa646ab7f0))
* 新增 URL Test 延迟测试方式 ([bcbf0dd](https://github.com/roseforljh/KunBox/commit/bcbf0dd89328d3aa358edbf6196e6b88d7b74859))
* 更新 sing-box 内核 ([4600a64](https://github.com/roseforljh/KunBox/commit/4600a6448e2923dc02d30668a6d26a804a6a87da))

## [2.18.1](https://github.com/roseforljh/KunBox/compare/v2.18.0...v2.18.1) (2026-06-22)


### Bug Fixes

* adjust bottom nav bar spacing and routing chips styling ([edd3a06](https://github.com/roseforljh/KunBox/commit/edd3a065a49bf4b389a20aed95bee7d1a2d1f264))
* adjust switch colors in default theme mode ([07ec2a6](https://github.com/roseforljh/KunBox/commit/07ec2a634d0975658ece008300fc7dc812609ddd))
* enhance liquid glass dialog option animation and fab menu scrim blur ([781ba8b](https://github.com/roseforljh/KunBox/commit/781ba8b24b87cf6e78e2309e5516414d8b26a106))
* ignore and remove graphify-out ([19c84cb](https://github.com/roseforljh/KunBox/commit/19c84cb802f5e9bd47960d69d88abce37b2c8042))
* keep standard liquid glass panel style for dialog options while disabling selection animations ([c3769bd](https://github.com/roseforljh/KunBox/commit/c3769bd2377b3e321868452e6698c034996e0f8d))
* prevent overlap between delete button and switch in app routing items ([586b872](https://github.com/roseforljh/KunBox/commit/586b872c29642958faeed55bda17eb38cb99f99c))
* remove dashboard card border and simplify liquid glass dialog option selection styling ([9baa6e3](https://github.com/roseforljh/KunBox/commit/9baa6e3f7495d39eca20f972eb883dc8aeb43629))
* support pinSHA256 for Hysteria2 and fix subscription UA block ([2927481](https://github.com/roseforljh/KunBox/commit/292748149000c383ee2a9d822df205a4ef7424ef))

## [2.18.0](https://github.com/roseforljh/KunBox/compare/v2.17.0...v2.18.0) (2026-06-21)


### Features

* add connection info screen ([3385e17](https://github.com/roseforljh/KunBox/commit/3385e17d9bd87ff163b512ec37247c118bd64234))
* add liquid glass bottom navigation ([9ed1c67](https://github.com/roseforljh/KunBox/commit/9ed1c67ab1bbb6f1179a31702842e3ce040b19fc))
* add theme style setting ([d4430ab](https://github.com/roseforljh/KunBox/commit/d4430ab59ae5e339683ab0694ee7dc2b1fa14333))
* extend liquid glass theme styling ([35c3aed](https://github.com/roseforljh/KunBox/commit/35c3aedc551eff7189843bf678f07065865792ce))
* support configurable grid column count for node list view ([e74b4ae](https://github.com/roseforljh/KunBox/commit/e74b4aebab38d6d04fa84570ca3c110040fbc9f5))
* support parsing subscription name from content disposition or profile title headers and qr code url hashes ([6114b05](https://github.com/roseforljh/KunBox/commit/6114b0588a10c693bfbfd99e3add5f6f91c39962))
* 优化液态玻璃主题视觉体验，全局修复弹窗模糊失效及亮色模式文字对比度过低问题 ([14a7e08](https://github.com/roseforljh/KunBox/commit/14a7e08ca687fc015fc31bd0167b8fe868d32963))


### Bug Fixes

* block liquid glass nav click-through ([1828f2f](https://github.com/roseforljh/KunBox/commit/1828f2f0deaeebb5300a3fae3ac014322e2c1e06))
* cover connection info policy ([ea2ab48](https://github.com/roseforljh/KunBox/commit/ea2ab482f0f8593033f4a6332df8fa7116f86408))
* optimize liquid glass button colors ([1dcf5f3](https://github.com/roseforljh/KunBox/commit/1dcf5f3a4817ca09a9f0c6e6bca14b34935e0f6e))
* polish liquid glass components ([748f4d9](https://github.com/roseforljh/KunBox/commit/748f4d92670d5b64bffb943b46d5a99a213d06b9))
* resolve detekt warnings ([c4587f8](https://github.com/roseforljh/KunBox/commit/c4587f82fc1aaa7b2a66d61291bc791307c5396b))
* resolve import file size limit exceeds 2048K https://github.com/roseforljh/KunBox/issues/97 ([f3e607c](https://github.com/roseforljh/KunBox/commit/f3e607c4f80e42e3fe05b3ebbe49738f404e2350))
* 优化对话框动画效果及液态玻璃主题样式细节 ([c00da02](https://github.com/roseforljh/KunBox/commit/c00da020381d3503480df46960627c8117d33bd5))
* 优化液态玻璃主题样式 ([3f1f50b](https://github.com/roseforljh/KunBox/commit/3f1f50bd8e7a15ff55751b2ca2bca0e0dd2f8cbf))
* 优化液态玻璃点击反馈 ([93f1f27](https://github.com/roseforljh/KunBox/commit/93f1f27bf3dab782a116277acb99ca8850e0733d))
* 优化液态玻璃筛选芯片反馈 ([68c6bfe](https://github.com/roseforljh/KunBox/commit/68c6bfebe3fe181fbb114e81a9fea830b000ca0e))
* 优化液态玻璃组件交互样式 ([36d5c41](https://github.com/roseforljh/KunBox/commit/36d5c41bd19d7d066b3f503bec83e4d019a41d52))
* 优化页面切换过渡动画（引入交叉淡入淡出与微距平移） ([b47b2a6](https://github.com/roseforljh/KunBox/commit/b47b2a6e670265cd4bb5ef895e8726dfe718fd5f))
* 修复液态玻璃白块问题 ([9c42cdd](https://github.com/roseforljh/KunBox/commit/9c42cdd957bc058c6a169c4d05eebb921ebb3e98))
* 增加液态玻璃覆盖测试 ([72f15e4](https://github.com/roseforljh/KunBox/commit/72f15e4e1f4f62858f5b163d586893ddbbb9b713))
* 完善液态玻璃主题控件样式 ([dbd82db](https://github.com/roseforljh/KunBox/commit/dbd82db70f0d5901c607d8d526892dd250d89757))
* 完善液态玻璃主题控件样式 ([5aa2192](https://github.com/roseforljh/KunBox/commit/5aa2192c59cdcadeec22498ec4798a4940cf04f9))
* 完善液态玻璃主题组件样式 ([8610881](https://github.com/roseforljh/KunBox/commit/8610881dc4b027d0f66dd42b0e13ed3ed55986ac))
* 完善液态玻璃主题组件样式 ([c27b7ec](https://github.com/roseforljh/KunBox/commit/c27b7ec81106fab8ebe7785a896f8f205557c144))
* 完善液态玻璃主题组件样式 ([8cd50b9](https://github.com/roseforljh/KunBox/commit/8cd50b99acec54f329f3f992aa1f36c3021f8e56))
* 完善液态玻璃主题组件覆盖 ([9a5567d](https://github.com/roseforljh/KunBox/commit/9a5567d258da2c2512386b14b6c4b34d46a57836))
* 完善液态玻璃主题菜单样式 ([cb7ac26](https://github.com/roseforljh/KunBox/commit/cb7ac268453689ec9652d893529b973259249770))
* 完善液态玻璃主题覆盖 ([6b2d336](https://github.com/roseforljh/KunBox/commit/6b2d33640d8f998d496eb7734e0cdf6071a0b31d))
* 完善液态玻璃小表面覆盖 ([c1a694f](https://github.com/roseforljh/KunBox/commit/c1a694f5f89606a936b37a31212d6743b0cda04d))
* 完善液态玻璃扫码和卡片样式 ([f6f84c1](https://github.com/roseforljh/KunBox/commit/f6f84c179a4ed3ba22389c317fa680ecb48c9304))
* 完善液态玻璃筛选芯片样式 ([ac853a1](https://github.com/roseforljh/KunBox/commit/ac853a1c63e8bb2a3f873b490d9d057fffb1c5ac))
* 扩展液态玻璃主题覆盖 ([ee2bb78](https://github.com/roseforljh/KunBox/commit/ee2bb788837f92219982b6a156ec54b970ce084f))
* 扩展液态玻璃组件覆盖 ([64e8a48](https://github.com/roseforljh/KunBox/commit/64e8a48095f86fc21e88372c3596c62a6f3d15cd))
* 补齐液态玻璃主题组件色彩 ([c126bd0](https://github.com/roseforljh/KunBox/commit/c126bd03278e6f6f4858b3cb0cdb6634c4ca3248))

## [2.17.0](https://github.com/roseforljh/KunBox/compare/v2.16.2...v2.17.0) (2026-06-13)


### Features

* split oversized Kotlin files ([d244fc8](https://github.com/roseforljh/KunBox/commit/d244fc8d34c6360ccd2410247be8f45ccca981c5))


### Bug Fixes

* 修复DNS覆写与测速解析链路 ([95f1bc1](https://github.com/roseforljh/KunBox/commit/95f1bc187c73b0b6b9f1187a081d36738dd8b04b))
* 修复节点域名解析DNS选择 ([ea123f5](https://github.com/roseforljh/KunBox/commit/ea123f50e6cc4ecba70b82e7c0f479c57abe7709))
* 修复首页假连接状态同步 ([22d4be8](https://github.com/roseforljh/KunBox/commit/22d4be8c61cdbbdd5da259dee2a8256de1741b2c))
* 全面修复DNS解析、协议解析、服务稳定性与配置原子性 ([b6bdf7e](https://github.com/roseforljh/KunBox/commit/b6bdf7ed5712ebffb61c765ea81fc0a232c89e69))
* 全面修复内核同步与运行稳定性问题 ([d284a8d](https://github.com/roseforljh/KunBox/commit/d284a8daf435796fa8a00f5af9d19f66d2fcbd55))

## [2.16.2](https://github.com/roseforljh/KunBox/compare/v2.16.1...v2.16.2) (2026-06-04)


### Bug Fixes

* unify latency test path ([ca29b4c](https://github.com/roseforljh/KunBox/commit/ca29b4cc9622c3fe66d025bdce21f354ab380cff))
* 修复 ECH DNS 规则优先级 ([add8a32](https://github.com/roseforljh/KunBox/commit/add8a32bf63504a8578d6afd455e0ea08f675791))
* 修复DNS覆写节点域名解析 ([636d08e](https://github.com/roseforljh/KunBox/commit/636d08e2d60d0f1bd6d3cc94db14d4c1e1822b32))

## [2.16.1](https://github.com/roseforljh/KunBox/compare/v2.16.0...v2.16.1) (2026-05-26)


### Bug Fixes

* preserve DNS override on subscription import ([48aa19c](https://github.com/roseforljh/KunBox/commit/48aa19c477eb6bf32ccdacbf9ec522e67a1d3a12))
* 修复订阅导入节点误去重 ([3fac3cb](https://github.com/roseforljh/KunBox/commit/3fac3cb7e21b08362aaa9fa67650ea84559a35f0))
* 修复订阅链接误识别为HTTPS节点 ([926e30c](https://github.com/roseforljh/KunBox/commit/926e30cb0e4be09b9162ae462191b41d5bf01336))

## [2.16.0](https://github.com/roseforljh/KunBox/compare/v2.15.0...v2.16.0) (2026-05-23)


### Features

* 添加应用代理黑名单模式 ([9c4b526](https://github.com/roseforljh/KunBox/commit/9c4b526858c46dbaabe1d464608d9dbb09e8b768))


### Bug Fixes

* VPN启动成功后清除pendingNodeName避免通知残留 ([c977e59](https://github.com/roseforljh/KunBox/commit/c977e595188ae1bb4478b9c91541e5b4d3a58eb6))
* 修复分应用模式卸载应用后计数不一致及记忆丢失问题 ([d8b83ed](https://github.com/roseforljh/KunBox/commit/d8b83ed1aad6b8c1d7dbadb11720f657300b3de3))
* 修复同配置切换节点通知栏不更新 ([a195054](https://github.com/roseforljh/KunBox/commit/a195054347ce78a6a83c7cadd4c62572f21ea79e))
* 修复配置切换后热切通知仍显示旧节点 ([3e93b10](https://github.com/roseforljh/KunBox/commit/3e93b109b067068bb5055db3a38c3883863dcb60))
* 修复配置切换后通知栏显示旧节点 ([41375e7](https://github.com/roseforljh/KunBox/commit/41375e7780fdffee10046b7997b8b3c2a9ea367c))
* 修复配置切换后通知栏节点滞留 ([a7edb78](https://github.com/roseforljh/KunBox/commit/a7edb78eb52bcc46d52658db482c847c9533121e))
* 修复配置切换节点记忆失效 ([af8d3b4](https://github.com/roseforljh/KunBox/commit/af8d3b4c768bf5a2fa9bee6dac057a29f1c4ca64))

## [2.15.0](https://github.com/roseforljh/KunBox/compare/v2.14.0...v2.15.0) (2026-05-21)


### Features

* 添加DNS泄露检测 ([43d81f6](https://github.com/roseforljh/KunBox/commit/43d81f67eaca52d9311a5951045b71e11e0e0787))


### Bug Fixes

* DNS泄露全面修复及DoH解析优化 ([6b22dd2](https://github.com/roseforljh/KunBox/commit/6b22dd250bc9052820548b09b68c8858035f5444))
* VPN DNS服务器使用TUN本地地址导致Chrome报DNS_PROBE_FINISHED_BAD_CONFIG ([5ae6171](https://github.com/roseforljh/KunBox/commit/5ae61717775ae4bbb383ab526f222f1c2e23231d))

## [2.14.0](https://github.com/roseforljh/KunBox/compare/v2.13.4...v2.14.0) (2026-05-18)


### Features

* DNS覆写支持，合并订阅原始DNS配置及Profile级自定义DNS JSON ([21df88d](https://github.com/roseforljh/KunBox/commit/21df88de171c055204e18dfe5ca90bbd6b3d35db))


### Bug Fixes

* improve VPN network recovery ([b7afd43](https://github.com/roseforljh/KunBox/commit/b7afd43142b539bf830d91619d5e300c85038955))
* lifecycle重复通知、广告DNS重试风暴、网络接口类型误判 ([41bbd00](https://github.com/roseforljh/KunBox/commit/41bbd00e42549de214d864e5b2acafbaf0f1a2ec))
* 应用分流选择器列表为空 ([dccf94e](https://github.com/roseforljh/KunBox/commit/dccf94efeaf91c3163c8f1e629245ef5a3543203))
* 省电模式息屏后失效，改用AlarmManager穿透Doze ([d20fc6e](https://github.com/roseforljh/KunBox/commit/d20fc6e2ef6a64660f4aa8b5258b3bfe1b03149a))
* 统一通知状态栏图标为应用logo ([9e84b09](https://github.com/roseforljh/KunBox/commit/9e84b09706c52fa88e6f6bb48dc06cca964ba606))

## [2.13.4](https://github.com/roseforljh/KunBox/compare/v2.13.3...v2.13.4) (2026-05-16)


### Bug Fixes

* ECH DNS规则改用直连避免loopback循环依赖 ([67bf416](https://github.com/roseforljh/KunBox/commit/67bf4167a82bb95e55fb380e4b647a7e3c72a3fd))
* ECH场景DNS无detour导致污染 + 日志功能增强 ([9613a09](https://github.com/roseforljh/KunBox/commit/9613a09a6b179f6198a76c2eb20158ee0bdd6a04))
* 修复 ECH 订阅节点 DNS 路由 ([c8ba34d](https://github.com/roseforljh/KunBox/commit/c8ba34d8a3660e65c618992c0dbabe7712ecb585))
* 修复DNS规则分流异常 ([0f18dbe](https://github.com/roseforljh/KunBox/commit/0f18dbe23c82ba4da815c9447f59881f1eeb94ae))
* 修复运行时DNS与恢复异常 ([2598ae3](https://github.com/roseforljh/KunBox/commit/2598ae3bc6e402d9734382ec23764f8b73974ccd))
* 限制运行日志采集到调试模式 ([ddecd23](https://github.com/roseforljh/KunBox/commit/ddecd23e885c29dd5f33cdb4e0db1d0559f11100))

## [2.13.3](https://github.com/roseforljh/KunBox/compare/v2.13.2...v2.13.3) (2026-05-16)


### Bug Fixes

* bug fix ([e480d99](https://github.com/roseforljh/KunBox/commit/e480d99d0f9103d6a26321508b6992117337a891))
* 同步singbox官方最新版本内核 ([462d30f](https://github.com/roseforljh/KunBox/commit/462d30f00486811121644b623d7ec4d0f827aba1))
* 磁贴启动VPN使用错误节点，auto-failover不应持久化节点选择 ([39dda31](https://github.com/roseforljh/KunBox/commit/39dda315be3a53fd7ed6d434919b4f415bbf5cac))

## [2.13.2](https://github.com/roseforljh/KunBox/compare/v2.13.1...v2.13.2) (2026-04-25)


### Bug Fixes

* 修复 VLESS PQC 节点 DNS 失败 ([2050958](https://github.com/roseforljh/KunBox/commit/20509585e9f4d9c71cb2717af9a687bf6696702b))

## [2.13.1](https://github.com/roseforljh/KunBox/compare/v2.13.0...v2.13.1) (2026-04-24)


### Bug Fixes

* 修复VMess http导入传输解析 ([891ed61](https://github.com/roseforljh/KunBox/commit/891ed6136f49d39020e52910116013fc73474738))
* 修复出站 socket 物理网络绑定 ([24ca01b](https://github.com/roseforljh/KunBox/commit/24ca01b82b428f977d61de2f79acb29a1b1b0574))
* 修复禁用SNI配置生成 ([d757e3b](https://github.com/roseforljh/KunBox/commit/d757e3bbaf24f82a0865af216864e5b99d6fd949))
* 统一双栈 IP 模式与 DNS 路由 ([4935a93](https://github.com/roseforljh/KunBox/commit/4935a93db0b4b7c37dc305f1190f598a7000763f))
* 补充VMess http导入回归验证 ([fbf2fd3](https://github.com/roseforljh/KunBox/commit/fbf2fd31740d45d3ac1c425545a3208f418a4022))

## [2.13.0](https://github.com/roseforljh/KunBox/compare/v2.12.1...v2.13.0) (2026-04-24)


### Features

* align custom config node selection UI with rule sets node selection UI ([0f78055](https://github.com/roseforljh/KunBox/commit/0f78055caffe6954bfce0c6459a9e7a2119f42a7))
* 内核更新 ([36cd825](https://github.com/roseforljh/KunBox/commit/36cd82527d58defcf712fcb97f847b83cd4ea2c3))


### Bug Fixes

* 从 SagerNet API 同时获取 geosite 和 geoip 在线规则集 ([63fed50](https://github.com/roseforljh/KunBox/commit/63fed50304fa685a28a93eccf5ba0795b67b451d))
* 修复 VLESS WebSocket early data 导入 ([5d44109](https://github.com/roseforljh/KunBox/commit/5d441094552e7464049594f36c775fcd213b4c81))
* 修复 VMess http 导入与单节点测速误判 ([d7ea395](https://github.com/roseforljh/KunBox/commit/d7ea3952cb57b55a14edce205d27c7365f2faf19))
* 修复TUIC禁用SNI配置冲突 ([c97e56d](https://github.com/roseforljh/KunBox/commit/c97e56d47b3694286dedbe4ca37aaa5748a368cd))
* 修复云规则地址重复添加 raw.githubusercontent.com 前缀的问题 ([aed1dce](https://github.com/roseforljh/KunBox/commit/aed1dce1618e78a19d8103c04c99c04931fca717))
* 修复规则集Hub添加云规则时URL重复拼接协议前缀的bug ([aa3d859](https://github.com/roseforljh/KunBox/commit/aa3d859cbff68dfc11eb6f345aaaff208a8e7e2f))
* 在配置导入入口接通自定义配置弹窗 ([cb35298](https://github.com/roseforljh/KunBox/commit/cb352983a12ef86667f8d77819fef9f34255787c))
* 收敛外部 VPN revoke 后的停止与恢复状态 ([9bfd67b](https://github.com/roseforljh/KunBox/commit/9bfd67b5043a241810d669b236e8137aafaa9a72))
* 智能识别 GeoIP 规则类型，防止 IP 规则错误添加到 DNS 层 ([1709180](https://github.com/roseforljh/KunBox/commit/17091802997956334f2a48d45188448c502b8ce5))
* 添加内置 geoip 规则集支持，修复搜索 geoip 无结果的问题 ([1f90f94](https://github.com/roseforljh/KunBox/commit/1f90f94b3b8620b13a9e59551e373b1fed3fcd90))
* 自定义配置顺序 ([9e4e665](https://github.com/roseforljh/KunBox/commit/9e4e6656f396e5f9aedcc4fcc5816722dafb138a))
* 补齐自定义配置的数据创建链路 ([53e99f5](https://github.com/roseforljh/KunBox/commit/53e99f5f10636ac5ba4a06ea4074021366429f60))
* 规则集页面补上和配置页一致的平滑拖拽与松手归位动画 ([e1a2d0a](https://github.com/roseforljh/KunBox/commit/e1a2d0ac29ad1d2fb8f49a541c70429589885149))

## [2.12.1](https://github.com/roseforljh/KunBox/compare/v2.12.0...v2.12.1) (2026-04-18)


### Bug Fixes

* 更新内核、重新支持Vless-xhttp-reality-enc协议节点 ([48e8d80](https://github.com/roseforljh/KunBox/commit/48e8d80009c5b299f68cd07090c6e7f85f9ebc32))
* 补齐 Clash YAML 导入 xhttp encryption 映射 ([ef661b0](https://github.com/roseforljh/KunBox/commit/ef661b0878f7154c28b7c767a9a0a42aea474e5e))
* 补齐 fakeip DNS server 的地址段配置 ([3414cf7](https://github.com/roseforljh/KunBox/commit/3414cf7dd4194e7a12dd31a72da92f96372f71c9))

## [2.12.0](https://github.com/roseforljh/KunBox/compare/v2.11.7...v2.12.0) (2026-04-04)


### Features

* add node line query diagnostics ([c14668b](https://github.com/roseforljh/KunBox/commit/c14668b1417bc9f47da7142e01c83f52a7b5ccd6))
* update diagnostics and network recovery flows ([8c7c52b](https://github.com/roseforljh/KunBox/commit/8c7c52bc6dd44f50815fd955aba84272f52143ac))


### Bug Fixes

* add automatic failover for stalled proxy traffic ([a9d6935](https://github.com/roseforljh/KunBox/commit/a9d69352a48c0d9c570b38969172c98d4df87119))
* harden import and recovery safety flows ([7a5b49c](https://github.com/roseforljh/KunBox/commit/7a5b49cb75d31b7cec6dabed38d0871441e1266a))
* resolve IPC lifecycle and state synchronization issues ([c39c20c](https://github.com/roseforljh/KunBox/commit/c39c20c26a171e088d7c6615dc8a6950f7385e36))
* resolve lifecycle notification absence and IPC race conditions ([217da11](https://github.com/roseforljh/KunBox/commit/217da116dfe7cf72e897b13aa39728591affed90))
* speed up startup selection for profile route groups ([9f34cbd](https://github.com/roseforljh/KunBox/commit/9f34cbdd66f0013c342b0b9622ac0a8d2ec17023))
* 同步官方内核、一些优化 ([060b4b9](https://github.com/roseforljh/KunBox/commit/060b4b94d86aae0b6689450b05faecba193b49ea))

## [2.11.7](https://github.com/roseforljh/KunBox/compare/v2.11.6...v2.11.7) (2026-03-29)


### Bug Fixes

* preserve node sort preference after latency tests ([372f5af](https://github.com/roseforljh/KunBox/commit/372f5af83f197911a87fa2494376e023ef7f27c2))
* 优化订阅更新等待与阶段反馈 ([553d930](https://github.com/roseforljh/KunBox/commit/553d930ab4335b339c3bc9be1377ca3a91d86e6d))
* 修正订阅 HTML 误判导致的节点丢失 ([8fcd8f0](https://github.com/roseforljh/KunBox/commit/8fcd8f0cb2126b22aba613c1cde7330bfaa1adf9))

## [2.11.6](https://github.com/roseforljh/KunBox/compare/v2.11.5...v2.11.6) (2026-03-29)


### Bug Fixes

* 优化切网后的分流自动选路与连接收敛 ([c77a9bf](https://github.com/roseforljh/KunBox/commit/c77a9bf46ecfc8b5a7ff0ce948d020c71c3e669f))
* 优化订阅更新主链路与后台 DNS 预解析 ([7b0efae](https://github.com/roseforljh/KunBox/commit/7b0efae767244271434e04476548911f6d431e7e))
* 修正 urltest 运行时配置字段回写 ([5aa26bf](https://github.com/roseforljh/KunBox/commit/5aa26bffdce3b957faa6132d384555a00fca0956))
* 补充订阅更新阶段状态展示 ([4115473](https://github.com/roseforljh/KunBox/commit/4115473d636a2438d207846b4584b7966535cfa6))

## [2.11.5](https://github.com/roseforljh/KunBox/compare/v2.11.4...v2.11.5) (2026-03-29)


### Bug Fixes

* clear ipc hub binder stale state ([8a5e242](https://github.com/roseforljh/KunBox/commit/8a5e24254b2fa5acc72281db21c78f5217957ee0))
* clear stale vpn runtime state ([54607e8](https://github.com/roseforljh/KunBox/commit/54607e85e44885b17197687cb1150ddca19a41cb))
* stabilize vpn handover recovery ([2cfa995](https://github.com/roseforljh/KunBox/commit/2cfa9950b27c06309e3c669e4600cdb914cf958c))
* 内核同步官方1.13.4 ([f1c8ac9](https://github.com/roseforljh/KunBox/commit/f1c8ac99fb8d54033be149dc478d3c981bccf74e))

## [2.11.4](https://github.com/roseforljh/KunBox/compare/v2.11.3...v2.11.4) (2026-03-29)


### Bug Fixes

* 仅自动更新已应用的规则集 ([b678542](https://github.com/roseforljh/KunBox/commit/b6785421aae78c7ee9686efdfc67a4bd5f9e220d))
* 修复 DNS detour 与状态同步问题 ([dd84f6b](https://github.com/roseforljh/KunBox/commit/dd84f6b20cc054c4071fa2f8217d1a10135a2123))
* 修复 PROFILE/NODE 分流下 DNS detour 对齐 ([a99f047](https://github.com/roseforljh/KunBox/commit/a99f047bbd6abd99b62082d10dadd366ce77bcb4))
* 针对分流的一些修复 ([5492fb7](https://github.com/roseforljh/KunBox/commit/5492fb7c97a4d0fdd3642d0e6e1f5b03e7bbd44f))

## [2.11.3](https://github.com/roseforljh/KunBox/compare/v2.11.2...v2.11.3) (2026-03-20)


### Bug Fixes

* ... ([fc3ef4c](https://github.com/roseforljh/KunBox/commit/fc3ef4c11b4c41d9a8e90cb595bf7e0be2693bdf))
* Add NodesViewModel for managing node display logic, filtering, sorting, and latency testing. ([3a2ae4c](https://github.com/roseforljh/KunBox/commit/3a2ae4c9e5d288c2c7804ee4960f603063e3ac78))
* 内核更新、bug修复 ([199f481](https://github.com/roseforljh/KunBox/commit/199f4814148446f2d1f316df2efa81eaae5ffce1))

## [2.11.2](https://github.com/roseforljh/KunBox/compare/v2.11.1...v2.11.2) (2026-03-06)


### Bug Fixes

* Implement SingBox node configuration management with link parsing, exporting, and detail UI. ([b97c436](https://github.com/roseforljh/KunBox/commit/b97c436a9f019c8d3e9ee7bc76a3f787f90c6c4a))
* Introduce SingBox core components, including configuration repository, service, and node parsing utilities. ([f6da4da](https://github.com/roseforljh/KunBox/commit/f6da4daefbbc3d4e1c08703ffde594782ca9e8d0))

## [2.11.1](https://github.com/roseforljh/KunBox/compare/v2.11.0...v2.11.1) (2026-03-05)


### Bug Fixes

* Introduce `ConfigRepository` and related components for comprehensive SingBox configuration, profile, and node management. ([cca6db5](https://github.com/roseforljh/KunBox/commit/cca6db5c7e3bdf69c8877e6138a9c99db37aec1f))
* 内核更新 ([a0ce160](https://github.com/roseforljh/KunBox/commit/a0ce160e60c3414755d64fedece23e614bd67bd1))
* 应用官方最新规范 ([8c1610d](https://github.com/roseforljh/KunBox/commit/8c1610d65e24960e40d77498a80cb7503be5bb14))

## [2.11.0](https://github.com/roseforljh/KunBox/compare/v2.10.3...v2.11.0) (2026-03-01)


### Features

* Implement initial SingBox client application with core VPN functionality, UI, and data management. ([1e6e439](https://github.com/roseforljh/KunBox/commit/1e6e4398d707d7b8a7db6231db6bafebefe3b25f))


### Bug Fixes

* Add node and profile management screens, view models, and Libbox compatibility layer with supporting utilities. ([5d7ff2b](https://github.com/roseforljh/KunBox/commit/5d7ff2b9c22c8f584f1e62e3109cca1e18514863))
* Implement comprehensive node link parsing for various protocols, outbound configuration, startup management, and node detail display. ([4bb9174](https://github.com/roseforljh/KunBox/commit/4bb9174667519e00b3c4c7a497602b799c847151))
* Implement comprehensive node management, configuration settings, and related UI screens with data persistence and parsing capabilities. ([f9cfeca](https://github.com/roseforljh/KunBox/commit/f9cfecadb3d1e6f21e751c2af353b87ef72be75b))
* Implement core SingBox client functionality including VPN management, configuration, data persistence, and diagnostics. ([9e65bf4](https://github.com/roseforljh/KunBox/commit/9e65bf46f0f35dbebe32d7b16d684d0a13a5987d))
* Implement initial MainActivity and SingBoxApp setup, including UI, lifecycle management, and core feature integrations. ([c9b09cd](https://github.com/roseforljh/KunBox/commit/c9b09cdbda5f01610d2cf34cf7162915684ea9ae))
* Implement new screens for settings, traffic statistics, and routing configuration, supported by new UI components, data models, and localization. ([fd9f665](https://github.com/roseforljh/KunBox/commit/fd9f6652507fedd9951edd2d0a8a8711ef9cfe7f))
* implement outbound configuration normalization and add domain rules screen. ([dbdcfb5](https://github.com/roseforljh/KunBox/commit/dbdcfb5249c8fe26b24c3f572c017f5ea66fc9c8))
* implement the initial SingBox Android client with core functionalities, UI, and data management. ([d4f3703](https://github.com/roseforljh/KunBox/commit/d4f37037df3aab04e1038c0b96339e52095ce5c2))

## [2.10.3](https://github.com/roseforljh/KunBox/compare/v2.10.2...v2.10.3) (2026-02-23)


### Bug Fixes

* . ([ab7659a](https://github.com/roseforljh/KunBox/commit/ab7659a9b3cffd1c22afed480f8f80d34fbd4ec0))

## [2.10.2](https://github.com/roseforljh/KunBox/compare/v2.10.1...v2.10.2) (2026-02-23)


### Bug Fixes

* 工作流bug ([6f0a30b](https://github.com/roseforljh/KunBox/commit/6f0a30b2b9d4b8dea0d401d34411ae3a0fbfdaed))

## [2.10.1](https://github.com/roseforljh/KunBox/compare/v2.10.0...v2.10.1) (2026-02-23)


### Bug Fixes

* add ConfigRepository for managing application configurations, profiles, and network-related settings. ([b4c5909](https://github.com/roseforljh/KunBox/commit/b4c59092f23885e229baca448f6fbeb6a5b3edf9))
* 基于上一个版本的一些小补丁 ([e26ba3e](https://github.com/roseforljh/KunBox/commit/e26ba3e72520f6926432f168d22ec93421791191))

## [2.10.0](https://github.com/roseforljh/KunBox/compare/v2.9.0...v2.10.0) (2026-02-23)


### Features

* add ConfigRepository for managing SingBox configurations, profiles, and subscriptions. ([b435cd9](https://github.com/roseforljh/KunBox/commit/b435cd943d8ccb1de0dc55615f17eaea704b1a58))
* Add DashboardScreen and ViewModel for VPN connection management, statistics display, and profile/node selection. ([a54464d](https://github.com/roseforljh/KunBox/commit/a54464d0f812dc5af276437eb49c31ed2e6bd923))
* Add initial application structure, core UI for dashboard and node management, connection logic, and comprehensive settings with localization. ([a90a493](https://github.com/roseforljh/KunBox/commit/a90a49382da46e511ded8031ab3486ad07c8060e))
* add NodeDetailScreen for managing Singbox node configurations. ([25da274](https://github.com/roseforljh/KunBox/commit/25da2749c1652ba299b9d5226417d490e0508549))
* Implement initial SingBox integration with IPC, configuration fixing, latency probing, and dashboard UI. ([3c01657](https://github.com/roseforljh/KunBox/commit/3c0165766ef5f55316020ae269ba37e2fad7eb29))
* introduce core SingBox integration, IPC, services, and initial dashboard UI with localization. ([af6b302](https://github.com/roseforljh/KunBox/commit/af6b302930032f6cdb97274b6d72b2f6cd763bf0))
* Introduce core SingBox services, managers, repositories, and a diagnostics view model for initial application setup. ([e6157ea](https://github.com/roseforljh/KunBox/commit/e6157ea0d00040e3052fab6c6478a46af314a2ee))

## [2.9.0](https://github.com/roseforljh/KunBox/compare/v2.8.1...v2.9.0) (2026-02-21)


### Features

* Add DashboardViewModel and UI screens for app groups, domain rules, and logs. ([1ec9993](https://github.com/roseforljh/KunBox/commit/1ec9993b4f1c528e55e18fe7727ef56ee1282e05))
* Implement `ConfigRepository` for profile and node management, alongside `EditableSettingItem` and `NodeDetailScreen` UI. ([876a1b1](https://github.com/roseforljh/KunBox/commit/876a1b1a44c159020916300c9ce38d652d5da71d))
* Implement the foundational SingBox application with its primary UI screens and core services. ([b6af8c5](https://github.com/roseforljh/KunBox/commit/b6af8c5e54eff03b4bc0a0ea8ca6358de85fcabd))
* Integrate SingBox core library and service, introducing a state-recording background power manager. ([aaeca96](https://github.com/roseforljh/KunBox/commit/aaeca9603dd2dddaaa284aba31b72f9423679948))

## [2.8.1](https://github.com/roseforljh/KunBox/compare/v2.8.0...v2.8.1) (2026-02-13)


### Bug Fixes

* 同步官方内核 ([2949531](https://github.com/roseforljh/KunBox/commit/2949531c98a01f4dfd20cd6c35a66ad6d4d51096))

## [2.8.0](https://github.com/roseforljh/KunBox/compare/v2.7.3...v2.8.0) (2026-02-12)


### Features

* **inbound:** 为 mixed inbound 添加 reuse_addr 选项 ([a947148](https://github.com/roseforljh/KunBox/commit/a947148f8b580916b999273215573902c48bb512))
* **network:** 实现智能网络恢复机制解决后台切换卡顿问题 ([e7ef770](https://github.com/roseforljh/KunBox/commit/e7ef770f65721f30b87a37ec7a339cc9009d5516))
* **traffic:** 实现按 outbound 分组的流量统计 ([dc822cb](https://github.com/roseforljh/KunBox/commit/dc822cb6d5ade32a44db07b01dca9f4b4a983e1b))


### Bug Fixes

* **detekt:** 修复所有 detekt 静态分析问题 ([8976225](https://github.com/roseforljh/KunBox/commit/89762250339bcfaf7b2e8855d8d46e1ffbd61f81))
* https://github.com/roseforljh/KunBox/issues/55 ([4404960](https://github.com/roseforljh/KunBox/commit/440496068b37a3f6e9d6d69f45f6fabcb1341534))
* **network:** 优化WiFi与移动数据切换时的网络恢复机制 ([d7eebc2](https://github.com/roseforljh/KunBox/commit/d7eebc22b228c184d2dbfa663ff0b02036bae4bd))
* **network:** 收敛节点切换重启触发并增加前台恢复HARD兜底 ([f7dba7a](https://github.com/roseforljh/KunBox/commit/f7dba7a83446d9679b6bb8f0eb7ac8cd85bf9e62))
* **sniff:** 将协议嗅探移至 inbound 层并启用 sniff_override_destination ([786d609](https://github.com/roseforljh/KunBox/commit/786d609475715074cf7d79e668110d1ca90ff2a9))
* **vpn:** speed up stop flow and stabilize QS tile state ([a4238e7](https://github.com/roseforljh/KunBox/commit/a4238e7f01d1d6e2b0dd910213ead50e53ea606b))
* **vpn:** 修复快速停止启动时端口未释放导致启动失败的问题 ([1fe74e7](https://github.com/roseforljh/KunBox/commit/1fe74e73d4fa90b8eccdadb95921a33715c0ef35))
* **vpn:** 修复热重载误判和端口释放问题 ([92cdfe9](https://github.com/roseforljh/KunBox/commit/92cdfe9276b21a59c69c74b460d8b48a5c8a2a5f))
* **vpn:** 重启时刷新设置缓存以应用最新仅允许列表 ([589806e](https://github.com/roseforljh/KunBox/commit/589806e84f117784b429d7c49cc9a26645dced7c))
* 修复测速与状态恢复相关问题并同步本地改动 ([e8583cc](https://github.com/roseforljh/KunBox/commit/e8583ccd0f30f4016b3556b4fe96dbeef785655f))
* 同步官方内核 ([7d6c376](https://github.com/roseforljh/KunBox/commit/7d6c3764cb5c4d5fa05c81ed13d6dc172d6dd7a3))

## [2.7.3](https://github.com/roseforljh/KunBox/compare/v2.7.2...v2.7.3) (2026-02-02)


### Bug Fixes

* 000 ([03db480](https://github.com/roseforljh/KunBox/commit/03db4805b99676ffab95396576e44aa6de6b9112))
* **ipc:** 移除IPC广播中的Thread.sleep阻塞,优化后台恢复机制 ([7af318b](https://github.com/roseforljh/KunBox/commit/7af318bb2c32cbffb483316104857b475f1a6336))
* 常规优化 ([c46a883](https://github.com/roseforljh/KunBox/commit/c46a8830d2fe2112bb37a53735026dc78034d5ab))

## [2.7.2](https://github.com/roseforljh/KunBox/compare/v2.7.1...v2.7.2) (2026-02-02)


### Bug Fixes

* 修复从后台恢复时卡在加载状态的问题 ([2a1363e](https://github.com/roseforljh/KunBox/commit/2a1363e9783a88abeaa219501d4c077a56d332bd))

## [2.7.1](https://github.com/roseforljh/KunBox/compare/v2.7.0...v2.7.1) (2026-01-31)


### Bug Fixes

* . ([46ae75e](https://github.com/roseforljh/KunBox/commit/46ae75e79e52c8a39f6e897403d234a57115973e))
* 同步官方最新内核 ([f9b112c](https://github.com/roseforljh/KunBox/commit/f9b112c9baf7dc54ee3e9c57a652da323bc97e5a))

## [2.7.0](https://github.com/roseforljh/KunBox/compare/v2.6.8...v2.7.0) (2026-01-31)


### Features

* **traffic:** 新增流量统计页面，修复分流节点流量记录问题 ([9f687e1](https://github.com/roseforljh/KunBox/commit/9f687e18c38212bc165fe7e5f2407d8b306a82ab))
* **traffic:** 添加刷新按钮，支持手动刷新流量数据 ([96c4f0a](https://github.com/roseforljh/KunBox/commit/96c4f0a05ba579dcfd58eec4fb4f79d67d44e446))


### Bug Fixes

* . ([f7942af](https://github.com/roseforljh/KunBox/commit/f7942aff183801fe79583093a672cfc238ee5105))
* **traffic:** 修复分流流量统计记录错误的问题 ([d8545df](https://github.com/roseforljh/KunBox/commit/d8545df1aa1f6ba48b60dfe050760a97fa5378b4))
* **traffic:** 完善流量统计功能 ([1c2e73e](https://github.com/roseforljh/KunBox/commit/1c2e73e59f146d0586012c45cf0d1049c2e2af29))
* **vpn:** 启动前检测并处理外部 VPN，确保顺利接管 ([fa34d3b](https://github.com/roseforljh/KunBox/commit/fa34d3bedd62ea7660214d493d21acecda7b6c9b))
* 刷新 ([12c2a06](https://github.com/roseforljh/KunBox/commit/12c2a061456bea16b5fdaa45aadff3dfc820cbbb))

## [2.6.8](https://github.com/roseforljh/KunBox/compare/v2.6.7...v2.6.8) (2026-01-30)


### Bug Fixes

* .... ([d011867](https://github.com/roseforljh/KunBox/commit/d011867ad1357493e64159a8256d87798fe2917f))
* **dns:** 使用竞速模式优化 DNS 解析速度 ([285f7f1](https://github.com/roseforljh/KunBox/commit/285f7f19baf9d2f279035dd4af8932c893be125a))
* **lint:** 修复 detekt 报告的代码问题 ([901485c](https://github.com/roseforljh/KunBox/commit/901485c61c8e531dbadf1c81650335c6a757ab12))
* **profile:** 添加 DNS 预解析功能绕过 DNS 污染 ([3cd6095](https://github.com/roseforljh/KunBox/commit/3cd6095ae833beea7684021ddc5752227d1f8835))
* 很吊 ([def14cf](https://github.com/roseforljh/KunBox/commit/def14cf7312207d09e74142a6b30ddcd7d8e4079))

## [2.6.7](https://github.com/roseforljh/KunBox/compare/v2.6.6...v2.6.7) (2026-01-29)


### Bug Fixes

* optimize network recovery for faster app reconnection after background ([f6b6c7a](https://github.com/roseforljh/KunBox/commit/f6b6c7a431aeeee87c2e416892859e83a0fa1cfa))
* **recovery:** implement per-app connection state API for stale connection detection ([d8ad017](https://github.com/roseforljh/KunBox/commit/d8ad01718d6c0b3b57dee33071604647b6d278f2))
* remove periodic idle connection cleanup to prevent TG reconnection issues ([f7f34b6](https://github.com/roseforljh/KunBox/commit/f7f34b66a27a84cb34ce18140d27789fe155f55d))
* remove unused import and fix indentation ([37c31a8](https://github.com/roseforljh/KunBox/commit/37c31a89d0f9eb99aab9d77eb4579ce88f469fba))
* **routing:** 优化规则集排序，特定服务规则优先于国家泛化规则 ([467360e](https://github.com/roseforljh/KunBox/commit/467360e1418b3b00150142c852efc730f7a9c681))
* **vpn:** add data plane connectivity verification and auto-recovery escalation ([4e66000](https://github.com/roseforljh/KunBox/commit/4e660001117d2a0fc86e1c1d35989cb647be395f))
* 一些优化 ([a671207](https://github.com/roseforljh/KunBox/commit/a67120767789fa0e4ee949c676edca079dd854f9))

## [2.6.6](https://github.com/roseforljh/KunBox/compare/v2.6.5...v2.6.6) (2026-01-27)


### Bug Fixes

* Hysteria2 port range format and code structure issues ([65fbea1](https://github.com/roseforljh/KunBox/commit/65fbea158e3d16118f9c476109db0769a3ec4095))
* **startup:** filter invalid domains in DNS prewarm ([21f404f](https://github.com/roseforljh/KunBox/commit/21f404f93c6edfbd2a1eb065a301ce10e63de957))
* **startup:** prevent DoH URL from triggering DNS lookup timeout ([951987a](https://github.com/roseforljh/KunBox/commit/951987ac08df8aa04cb6c7fc435186e5e102a9e3))
* 大量重构 ([8b99ba1](https://github.com/roseforljh/KunBox/commit/8b99ba1ac1602164e533968bac7f3584e6002b5b))

## [2.6.5](https://github.com/roseforljh/KunBox/compare/v2.6.4...v2.6.5) (2026-01-27)


### Bug Fixes

* 一些已知问题 ([2622695](https://github.com/roseforljh/KunBox/commit/2622695a3d9565d76b1007f9c3401ff7b5f87b61))
* 修复首次前台误触发DEEP恢复导致连接卡住 ([ed7a774](https://github.com/roseforljh/KunBox/commit/ed7a774c073c715fa58b9bb579a6976502e1fe3c))

## [2.6.4](https://github.com/roseforljh/KunBox/compare/v2.6.3...v2.6.4) (2026-01-26)


### Bug Fixes

* 多处变动 ([9299dcb](https://github.com/roseforljh/KunBox/commit/9299dcb9159b8643e6aca234f4a723070d202b92))

## [2.6.3](https://github.com/roseforljh/KunBox/compare/v2.6.2...v2.6.3) (2026-01-25)


### Bug Fixes

* 增强稳定性 ([223c19f](https://github.com/roseforljh/KunBox/commit/223c19f2f004701afef7988c8f9a2358c026d07e))
* 尝试修复fakeip的问题 ([3ffc626](https://github.com/roseforljh/KunBox/commit/3ffc6268fb68b220d691d398e0ca4dc24091668e))
* 常规优化 ([5a8f38d](https://github.com/roseforljh/KunBox/commit/5a8f38d056e2b0eaa26fd4a12cba6636cf7d5b2b))
* 新内核 ([a1bcad1](https://github.com/roseforljh/KunBox/commit/a1bcad131ffc42bf68a7caf0f578a6c23f588893))

## [2.6.2](https://github.com/roseforljh/KunBox/compare/v2.6.1...v2.6.2) (2026-01-23)


### Bug Fixes

* **latency:** remove retry and fallback in batch latency test ([2deac44](https://github.com/roseforljh/KunBox/commit/2deac4457673cea8cd766868ce5ececc280b64b0))

## [2.6.1](https://github.com/roseforljh/KunBox/compare/v2.6.0...v2.6.1) (2026-01-23)


### Bug Fixes

* 修复了一些已知问题 ([51f6994](https://github.com/roseforljh/KunBox/commit/51f6994d20f6edf52f71f8ed163383feced4fb15))

## [2.6.0](https://github.com/roseforljh/KunBox/compare/v2.5.0...v2.6.0) (2026-01-22)


### Features

* add release-please workflow to auto-create release PRs ([43a0516](https://github.com/roseforljh/KunBox/commit/43a051623d0e07eb8507fd25c6efb08fa1be3e2c))
* **core:** add kernel-level hot reload for config changes ([c4732a3](https://github.com/roseforljh/KunBox/commit/c4732a37c5673c547c01970d2b5b37c2ab90133c))
* improve node link parsing and latency test settings ([3bf90af](https://github.com/roseforljh/KunBox/commit/3bf90af0a7b0a420a2e51827e0293446ba38e51e))
* **ipc:** improve VPN state management and add kernel build docs ([ce8d5e9](https://github.com/roseforljh/KunBox/commit/ce8d5e972f2602596f2a1c28c89b46a8beccab94))
* **kernel:** add kernel-level HTTP fetch and URLTest extensions ([fa6cd20](https://github.com/roseforljh/KunBox/commit/fa6cd20bc48f86b8a64b48da48185e073b621cf0))
* **latency:** persist node latency data and optimize libbox build ([316ed9b](https://github.com/roseforljh/KunBox/commit/316ed9b24f1ef98bb0a4e5bdcbcfc70c2623c0f0))
* **network:** add NetworkSwitchManager for WiFi/Cellular handoff ([a88a18f](https://github.com/roseforljh/KunBox/commit/a88a18fa0cd040b5b0d55d7c9b4385ca6cdc2610))
* **nodes:** add latency test progress bar with success/timeout stats ([7d333a3](https://github.com/roseforljh/KunBox/commit/7d333a332536fb59f999c511ae99263c6f768cdb))
* **nodes:** add profile selection for manual node creation ([4da7bef](https://github.com/roseforljh/KunBox/commit/4da7bef9ce9708ab0b4878ea8c7ebc525c6388d3))
* **nodes:** 优化搜索栏设计 ([1e3a2db](https://github.com/roseforljh/KunBox/commit/1e3a2dbb49c5682dc4c0e6d6b81ba03cd06d08da))
* **nodes:** 搜索栏跟随滚动显隐 ([dfda72a](https://github.com/roseforljh/KunBox/commit/dfda72a54ed1854e7f0af8223982de9f6a345916))
* **parser:** add Clash Meta advanced features support ([d60e3a2](https://github.com/roseforljh/KunBox/commit/d60e3a2639b73bcaef2ea9b6b74c4a2bded9be6a))
* **routing:** smart domain recognition + fix domain routing priority ([4014073](https://github.com/roseforljh/KunBox/commit/4014073ebc221ff0da0bfc1b793ea275e82d4e5c))
* **ui:** improve navigation animations and add gengar floating effect ([6f8c214](https://github.com/roseforljh/KunBox/commit/6f8c214e4ef14d7a725e21e2f63d4d7a911a5268))
* **ui:** optimize app selector dialog layout and sort selected apps first ([35b9ea2](https://github.com/roseforljh/KunBox/commit/35b9ea26d335419f688eb069f09b5d684bc1a2d4))
* **ui:** sync node selector dialog state with nodes list ([624b1d0](https://github.com/roseforljh/KunBox/commit/624b1d0788950fb141e1211b04d01144177a2b11))
* update workflow logic and dynamic versioning ([e76fbdf](https://github.com/roseforljh/KunBox/commit/e76fbdfa5d19963a87ce312da5bdace6031dbc64))
* **update:** implement app version check and notification ([ee5ee2f](https://github.com/roseforljh/KunBox/commit/ee5ee2f8d2c56ce27d4abe684aee04cfdc45113b))
* 优化磁贴交互与CI配置，完善文档 ([49447bc](https://github.com/roseforljh/KunBox/commit/49447bc146990562c06cb9dea8f70d21196c2ced))
* 应用分流 ([6193046](https://github.com/roseforljh/KunBox/commit/6193046e9e36400031d6b8abd77d0e7a6ff653db))


### Bug Fixes

* ：JSON 格式不标准 (Gson 解析异常) ([a9f2c86](https://github.com/roseforljh/KunBox/commit/a9f2c8621408523b3df34266b376aa5b4b9c9303))
* ? ([57a84d7](https://github.com/roseforljh/KunBox/commit/57a84d73670733961c794000925d7d29ece33cfe))
* 2 ([795f2ec](https://github.com/roseforljh/KunBox/commit/795f2ec3a768c516bef42a71069ad92f4b85f628))
* Add English;随便修了一些bug ([f0a12ff](https://github.com/roseforljh/KunBox/commit/f0a12ff70727ecaaec5c1f2d354255b0b3623751))
* bug ([cb5f10a](https://github.com/roseforljh/KunBox/commit/cb5f10aaa95771d0e47b3289797654b30e64ed87))
* bug修复 ([f6ab50f](https://github.com/roseforljh/KunBox/commit/f6ab50f1f5d610507122213d6af08edba1309234))
* **ci:** merge build into release-please workflow ([9845fda](https://github.com/roseforljh/KunBox/commit/9845fdaac2d0ef089bad73533be87c5586feedf6))
* **ci:** separate release-please and build workflows ([d879764](https://github.com/roseforljh/KunBox/commit/d879764566791477d43d6809d815a71279b5a7ff))
* **config:** node state persistence and profile node memory ([a34bab3](https://github.com/roseforljh/KunBox/commit/a34bab312758b6c324c518441c2f4c3cedd0c9c7))
* **core:** 修复 VPN 服务因 bbolt 数据库并发冲突导致自动停止的 bug ([ac301f7](https://github.com/roseforljh/KunBox/commit/ac301f7df98f35c1e6596f895f04d2e2a88b65bf))
* **core:** 彻底修复 bbolt panic - 根本原因是多进程并发访问同一数据库 ([42ba8cf](https://github.com/roseforljh/KunBox/commit/42ba8cf0018dda3f9e02e8702e8971f4ee75ac86))
* **core:** 确保测试数据库目录存在 - 添加 mkdirs() 调用 ([d57838d](https://github.com/roseforljh/KunBox/commit/d57838d98dca559ec49f06eaad68e6db03c82764))
* **data:** persist node latency and improve update check ([a017357](https://github.com/roseforljh/KunBox/commit/a017357603793546c4a54462d236f6fd9ea282a8))
* **db:** remove foreign key constraint to fix node latency persistence ([905b07e](https://github.com/roseforljh/KunBox/commit/905b07e10782d4d12c68f98db6c799ed3643ff59))
* **filter:** preserve filter keywords when switching filter modes ([e21efa8](https://github.com/roseforljh/KunBox/commit/e21efa8dc620ded02557bb28d5a18c501b877086))
* force release pr generation ([9a7398a](https://github.com/roseforljh/KunBox/commit/9a7398ad4fbc59fa431aecedb55f91796b9b8d93))
* huawei ([b4ad894](https://github.com/roseforljh/KunBox/commit/b4ad8941688d31aaa3b6ad5ec550fa04a2d6b4dc))
* internet op ([87c8238](https://github.com/roseforljh/KunBox/commit/87c8238dcf2d5b27f68bf33293adb291e88ada93))
* **ipc:** resolve UI stuck loading after Doze wake and proxy idle ([14d1713](https://github.com/roseforljh/KunBox/commit/14d171353071b3076122efcdefc9d7ce256a88bd))
* **ipc:** 修复从后台恢复后 UI 一直加载中的问题 ([806fa42](https://github.com/roseforljh/KunBox/commit/806fa42f911c13f022e75767ce99e2dceb5cb031))
* **ipc:** 修复后台恢复时UI卡住的问题 - 参考NekoBox实现 ([624361a](https://github.com/roseforljh/KunBox/commit/624361ac8348093f9fd00eae6767f3b9ee50cfe9))
* **ipc:** 应用返回前台时重置所有连接，修复 TG 等应用一直加载中的问题 ([3549747](https://github.com/roseforljh/KunBox/commit/35497474d0fec6031cafa1112d13e118885c3e07))
* **latency-test:** resolve no available network interface error ([ef5b60c](https://github.com/roseforljh/KunBox/commit/ef5b60cbcb2aa8bae7e23140a8de4cd0a408effd))
* **latency:** enforce user-configured timeout for single node test ([9103dba](https://github.com/roseforljh/KunBox/commit/9103dba01b0f5ac7a1c68c9c358bca52ae804a53))
* **latency:** pass latency value directly in callback to fix race condition ([ac2e134](https://github.com/roseforljh/KunBox/commit/ac2e134db6c9c7c5febc2adcff95849cd5a579d2))
* **latency:** resolve SS+ShadowTLS dependency for latency testing ([451e29e](https://github.com/roseforljh/KunBox/commit/451e29eded484e531a0861faab0043fbeac0bfea))
* **latency:** restore latency data when switching profiles ([67afd6f](https://github.com/roseforljh/KunBox/commit/67afd6f276d17574f5491fab5752a7fe33be3aaf))
* **latency:** support latency testing for nodes from all profiles ([020f82b](https://github.com/roseforljh/KunBox/commit/020f82b1b6c755af1e2ae0a578c323f9051ffd7d))
* **libbox:** adapt to new CommandServer API and fix service shutdown ([6b3df81](https://github.com/roseforljh/KunBox/commit/6b3df8176adfec224a03b180fb21880d8214237b))
* **memory:** optimize memory usage with LRU caches and static TypeTokens ([f25fa5e](https://github.com/roseforljh/KunBox/commit/f25fa5eea01a9343fd4208448dd1bb731e622769))
* **nav:** correct route mapping for Diagnostics and Logs pages ([cd18066](https://github.com/roseforljh/KunBox/commit/cd18066b55adcd8aae379e7e1438db7f85629ee1))
* **network:** add idle connection cleanup for TG image loading issue ([b6c54ef](https://github.com/roseforljh/KunBox/commit/b6c54efe70dda696c0f3b5efc8d504dec6d9f32c))
* **network:** add proactive recovery for faster foreground resume ([85764bd](https://github.com/roseforljh/KunBox/commit/85764bd46b5e2770d1a9703077eecae8c336f5f6))
* **notification:** persist activeLabel to VpnStateStore for cross-process sync ([f7507ae](https://github.com/roseforljh/KunBox/commit/f7507ae62ca9119077d26d26f035c1c7afadd837))
* **parser:** add wireguard and ssh link parsing support ([3494923](https://github.com/roseforljh/KunBox/commit/3494923947477f59bcffa927734bc11e90050dea))
* **parser:** handle empty string fields in Clash YAML hysteria2 nodes ([a4edefc](https://github.com/roseforljh/KunBox/commit/a4edefc3608b4a6bc62e6cfb75be9148d2ca4aef))
* **parser:** handle multiple links in clipboard import ([223c085](https://github.com/roseforljh/KunBox/commit/223c0852fd539ee5a7cc6150dd72062735032067))
* **parser:** 修复 vmess 链接无法从剪贴板导入的问题 ([882eeca](https://github.com/roseforljh/KunBox/commit/882eeca4b2861c8c15f09b01ba3b3d51acb32db5))
* **power:** add background power saving with configurable delay ([208d59a](https://github.com/roseforljh/KunBox/commit/208d59a1f1ae3cdbbba9d3c80d2cccda4164e09b))
* **power:** enable main process self-termination for power saving ([0b39d1f](https://github.com/roseforljh/KunBox/commit/0b39d1f70e0faf5e330ee7ef82c90d6742626920))
* **rulesets:** initialize defaults with toggleable downloads ([6d522ff](https://github.com/roseforljh/KunBox/commit/6d522ff25c7eeba18f0073762b3d363668d97927))
* **rulesets:** load geosite list via github tree ([79e06b4](https://github.com/roseforljh/KunBox/commit/79e06b4f1b0865f10fb7204ec98504d47cfd615e))
* **service:** notification bar node switch not working ([3dfb731](https://github.com/roseforljh/KunBox/commit/3dfb731d438376eb176245e0b314f6d071c76b3a))
* **service:** notification not dismissed when app removed from recents ([1dc9834](https://github.com/roseforljh/KunBox/commit/1dc983460b537444372366ceeba44f29ca71e4fd))
* **service:** 修复息屏后返回App时Telegram等应用一直加载的问题 ([137e64d](https://github.com/roseforljh/KunBox/commit/137e64d65dfaa8b7181dcd2990181f34c3ee8e7c))
* **settings:** 移除断线重连功能和UI ([bc2e5b8](https://github.com/roseforljh/KunBox/commit/bc2e5b8bc573e93fcaff264f597641f989028cf1))
* **shortcut:** node selection and VPN toggle not working from app shortcut ([1253563](https://github.com/roseforljh/KunBox/commit/12535633853cc1dcee0ea08188405d6d1672f10b))
* **subscription:** 修复取消导入后弹窗重复弹出及应用无法使用自身代理的问题 ([ef12cbb](https://github.com/roseforljh/KunBox/commit/ef12cbbf9166d2e87deed1f5d073ade70e38e3a0))
* **subscription:** 兼容订阅剩余/到期信息 ([8af42e1](https://github.com/roseforljh/KunBox/commit/8af42e1657ebf1dba2eb3a3d6edd56b13b9e58a5))
* **tun:** refine app list dialog quick select ([21c4c43](https://github.com/roseforljh/KunBox/commit/21c4c430db38b1a72cf2dca448a3269dcab4b92d))
* **ui:** add smooth crossfade animation for toggle icon transition ([4cdb257](https://github.com/roseforljh/KunBox/commit/4cdb2573bfcbedadee9401c3ef14b89a2d53d87d))
* **ui:** adjust ProfileCard layout weight for better text display ([480ca11](https://github.com/roseforljh/KunBox/commit/480ca11005d3e2ca996dab2c3aa4caae133bbded))
* **ui:** clean build warnings and consolidate stability notes ([3eee087](https://github.com/roseforljh/KunBox/commit/3eee087a49171f9ab80175f646cb9ad1caaa045c))
* **ui:** improve latency display placeholder ([73fb2a0](https://github.com/roseforljh/KunBox/commit/73fb2a09ff46672b91e63a5cfb8e9c536c5149bb))
* **ui:** InputDialog 输入框支持水平滚动 ([a63fcd1](https://github.com/roseforljh/KunBox/commit/a63fcd1ec7dfcb761b13cd02fe2abf234fdc6d05))
* **ui:** prevent gengar image clipping during crossfade animation ([39055b9](https://github.com/roseforljh/KunBox/commit/39055b9f5cfe1e0c624212bfb29ea77dad7d271b))
* **ui:** toggle fab visibility on scroll ([c42cff8](https://github.com/roseforljh/KunBox/commit/c42cff82b5f3c9e7e21c951ee0b617f54866799a))
* **ui:** 列表到底部时隐藏悬浮按钮 ([a232719](https://github.com/roseforljh/KunBox/commit/a23271949474e9b777c2872d64ea29069e9c00e6))
* **ui:** 应用分流仅显示仅允许列表中的应用并移除直连选项 ([05ff1da](https://github.com/roseforljh/KunBox/commit/05ff1da8acb967b3be6b1de88c53460200603180))
* ui控制参数 ([6745e0d](https://github.com/roseforljh/KunBox/commit/6745e0d65bf1d4f0a81b92bca73137e1fa7eef3b))
* **ui:** 调整 BigToggle 图像显示 ([8a53ced](https://github.com/roseforljh/KunBox/commit/8a53ced548eaf4cca29909706eb37c3a59bae928))
* **vpn:** hot reload failure causes network loss ([2000436](https://github.com/roseforljh/KunBox/commit/20004364e3aa3e56a1d731f9a2e5f1762f70b335))
* **vpn:** per-app proxy settings not applied to TUN interface ([f3a9320](https://github.com/roseforljh/KunBox/commit/f3a93203861231fb83704c739687ec12c9b36d42))
* **vpn:** 修复VPN自动关闭和启动期间连接泄漏问题 ([9975a2e](https://github.com/roseforljh/KunBox/commit/9975a2ea6f4f8746f7f999477aff6ec864f059ba))
* **vpn:** 修复多个 VPN 启动和节点切换问题 ([f61fb11](https://github.com/roseforljh/KunBox/commit/f61fb11acc18bcb158698204e2393ce541a0ede7))
* **vpn:** 修复息屏后 Telegram 等应用卡在加载中的问题 ([093ab71](https://github.com/roseforljh/KunBox/commit/093ab71863228341421a8e61f6aee6614ef21cb1))
* **vpn:** 全面加固 VPN 息屏保活机制,防止被系统杀死 ([0b39d09](https://github.com/roseforljh/KunBox/commit/0b39d0981024dd5be46fc6efa160eab2a41a8ffd))
* **vpn:** 添加周期性健康检查防止 VPN 服务僵尸状态 ([843d4ec](https://github.com/roseforljh/KunBox/commit/843d4ecb413485663f72fd4c56a00c01f90cc2cb))
* 主题适配 ([234924c](https://github.com/roseforljh/KunBox/commit/234924cc7d537167d50e091c5a70abff23e5b2a1))
* 优化 ([567b554](https://github.com/roseforljh/KunBox/commit/567b5542ef48b785fb10fbf884f8474b8f19563f))
* 优化 SingBoxCore 代码注释和错误处理 ([d69bd8d](https://github.com/roseforljh/KunBox/commit/d69bd8dc5b4849179e17e1170e049098d286c1ec))
* 优化延迟测试 ([a1fdcb4](https://github.com/roseforljh/KunBox/commit/a1fdcb47f221edc69228f6af080e9453b8be0251))
* 优化延迟测试 ([7b67a0a](https://github.com/roseforljh/KunBox/commit/7b67a0a26888099c0e06a66dae838cda10a3afb9))
* 优化构建配置和 ProGuard 混淆规则 ([046362a](https://github.com/roseforljh/KunBox/commit/046362af725fb7d87bcd9ea3e93a6365a15b0aca))
* 优化网络稳定 ([b03dd47](https://github.com/roseforljh/KunBox/commit/b03dd47ed083fcd9795ecf0baabb5066607d5a72))
* 优化订阅更新逻辑 ([75fa6c5](https://github.com/roseforljh/KunBox/commit/75fa6c5d0ec4227f8e10b02b5734ec22e8e6036a))
* 优化重连、断连、热重载 ([95b3bfd](https://github.com/roseforljh/KunBox/commit/95b3bfd547be8ba80e091f096b0eb7049c6cef6e))
* 修复了热切换节点后 连接延迟的问题 ([894b2af](https://github.com/roseforljh/KunBox/commit/894b2affa8ce6a8a6ac53c3837b11b75b0d22f71))
* 修复从通知栏进入app后返回键需按两次才能退出的问题 ([1240f82](https://github.com/roseforljh/KunBox/commit/1240f8246a52b05189a33502577157873d223bb2))
* 修复节点选择状态未正确保存和显示的问题 ([1cdf3f7](https://github.com/roseforljh/KunBox/commit/1cdf3f7f69ff23dff50cb18ee748725d595e21b7))
* 修复跨配置切换节点时无法上网的问题 ([ab3ba67](https://github.com/roseforljh/KunBox/commit/ab3ba67ac8c0deea104f1ee2cde16d7250ec09da))
* 全局禁用endpoint_independent_nat和auto_route ([2aa437e](https://github.com/roseforljh/KunBox/commit/2aa437ecba131769cb32b2f94a84bdcb4206a4a2))
* 关于应用里加上app本体及singbox的版本号 ([2c6bd61](https://github.com/roseforljh/KunBox/commit/2c6bd61777b3f61d615ca4496ff6358539c9109b))
* 删除组逻辑 ([5aa7021](https://github.com/roseforljh/KunBox/commit/5aa702157adf0cfbb92aec1eb810105ca96e990b))
* 只读节点信息 ([4c0976b](https://github.com/roseforljh/KunBox/commit/4c0976b9e2eb3f8305cb91a32bd2b97ecce55fd5))
* 可能解决了一些闪退的问题 ([ef5e728](https://github.com/roseforljh/KunBox/commit/ef5e7283710ac2839548fe1bfe9a99303c8304f3))
* 同步官方内核 ([f054302](https://github.com/roseforljh/KunBox/commit/f054302a5c90c74e9ff95ae52ab0929b844e363e))
* 增加日志开关 ([9beb8ff](https://github.com/roseforljh/KunBox/commit/9beb8ff72f75911b04fef7e0335e9a4c650c53ea))
* 多处优化 ([189e8c9](https://github.com/roseforljh/KunBox/commit/189e8c9077b167f9d6ca10b3869aff544af48bca))
* 多节点闪退 ([d0d26e5](https://github.com/roseforljh/KunBox/commit/d0d26e5a7d9ce3a386107a91b771976c16f8ccab))
* 实时显示上传和下载速度 ([0af613e](https://github.com/roseforljh/KunBox/commit/0af613ee7a5aa3afb2b2d4140ed0c2de11f601cd))
* 实现配置管理，加号按钮里面本地文件和扫描二维码 ([ec24666](https://github.com/roseforljh/KunBox/commit/ec24666f53992d0137469513f520f3db9275fb0f))
* 底部导航切换过渡动画、磁贴按钮 ([1a4f92c](https://github.com/roseforljh/KunBox/commit/1a4f92c06b4b104088149d52bb223894305c66b3))
* 性能优化 ([4e3cfee](https://github.com/roseforljh/KunBox/commit/4e3cfee07cfb44ad8b66a291df9561b6cbc729bc))
* 性能优化 ([38b27c0](https://github.com/roseforljh/KunBox/commit/38b27c07aba1165c203ba1dc04fb133ae38c0c83))
* 新增 URL Scheme 深度链接支持和问题修复文档 ([5432a6b](https://github.com/roseforljh/KunBox/commit/5432a6b948e705bc49fec833e626c521f612a829))
* 新增应用启动时清理遗留临时数据库文件的功能 ([1bf5a2a](https://github.com/roseforljh/KunBox/commit/1bf5a2a6f27b32a7be22fb7af9c15e36223756e7))
* 新增项目文档索引和优化指南 ([108d3b9](https://github.com/roseforljh/KunBox/commit/108d3b9a1fcb3fa891dff3fcd9686d4f5358b537))
* 更新 planning-with-files submodule 和临时修复代码 ([dffd1e9](https://github.com/roseforljh/KunBox/commit/dffd1e90c5b92f5ca27e3f103df7d645a39a5dad))
* 更新镜像、添加导入导出数据功能 ([a20a115](https://github.com/roseforljh/KunBox/commit/a20a115986e7d0ef646dca830a4788135a4c3433))
* 清理不必要的临时文件和 planning-with-files ([04f9670](https://github.com/roseforljh/KunBox/commit/04f96701c1f8f8c4fee5422cdecb9f08aa459776))
* 清理冗余调试日志以提升性能和可读性 ([dd3a404](https://github.com/roseforljh/KunBox/commit/dd3a404a0301ed2a8be4550a9b3ef196e34b4726))
* 清理废弃的构建脚本并优化 libbox 构建流程 ([de78f57](https://github.com/roseforljh/KunBox/commit/de78f576f6e998a326cc9a0d3d55dbcbc84990c4))
* 移除内核版本管理功能并优化核心服务 ([931e169](https://github.com/roseforljh/KunBox/commit/931e1697574444ecf59c565b402aac169a5b7d93))
* 移除已过时的 bugfix 文档 ([2e80582](https://github.com/roseforljh/KunBox/commit/2e8058272a2c113347d9a196c8af7c621734ee78))
* 移除节点名称自动添加协议后缀 ([2bd0423](https://github.com/roseforljh/KunBox/commit/2bd0423a97ccb72c40b725c5df226b1fc98710f7))
* 稳定网络 ([2bcd87d](https://github.com/roseforljh/KunBox/commit/2bcd87d8e21d210704662f487fe0849450d6fea2))
* 统一 TUN MTU 默认值并移除冗余日志 ([7af9639](https://github.com/roseforljh/KunBox/commit/7af96395f66d20b3c79a7d105eb5bbcf9eab643e))
* 规则集定时自动更新 ([b1803b4](https://github.com/roseforljh/KunBox/commit/b1803b40ab9590ebb5d358ac6219ed16a6a72898))
* 解决 VPN 快速重启导致的网络接口不同步问题 ([0732fd6](https://github.com/roseforljh/KunBox/commit/0732fd607529d3d28522320a698f5bab67ea10dd))
* 解析器识别 anytls 节点 ([742cd95](https://github.com/roseforljh/KunBox/commit/742cd95d84e429df2f22ffc7ebab552b7f63fb27))
* 订阅定时更新 ([0363898](https://github.com/roseforljh/KunBox/commit/0363898cc9377c9f4cd6042a74f01cfbcc24c94a))
* 部分订阅节点丢失 ([ecb9f74](https://github.com/roseforljh/KunBox/commit/ecb9f745eda3443e02a25014ec6e626c195504a3))
* 长按功能 ([276fc8e](https://github.com/roseforljh/KunBox/commit/276fc8e3589660a9e19573f23c4a5678fcac13dd))
* 长按图标出现的操作选项 ([db340c0](https://github.com/roseforljh/KunBox/commit/db340c095572ce3cdf5cd6414ef903a21b445bfa))
* 首页设计 ([3a86145](https://github.com/roseforljh/KunBox/commit/3a86145d750e62abb38de4a0f6521ba0efc0cb51))


### Performance Improvements

* implement 4 performance optimizations ([465a7ee](https://github.com/roseforljh/KunBox/commit/465a7eea13a46c2a8a6448dfa82acc29d9d30b69))
* **network:** optimize throughput and enable GSO ([ed83201](https://github.com/roseforljh/KunBox/commit/ed832018a458ed55de3a627f0fda47ef457f4b1e))
* **repository:** optimize node import performance and add Kryo serialization ([29f8268](https://github.com/roseforljh/KunBox/commit/29f8268ee3d93492684fd510e648ac3b4df09bdf))
* **repository:** parallelize node extraction and subscription updates ([a86228e](https://github.com/roseforljh/KunBox/commit/a86228e6994b0cf6662fd3d359473e53434519de))

## [2.5.0](https://github.com/roseforljh/KunBox/compare/v2.4.5...v2.5.0) (2026-01-22)


### Features

* add release-please workflow to auto-create release PRs ([43a0516](https://github.com/roseforljh/KunBox/commit/43a051623d0e07eb8507fd25c6efb08fa1be3e2c))
* **core:** add kernel-level hot reload for config changes ([c4732a3](https://github.com/roseforljh/KunBox/commit/c4732a37c5673c547c01970d2b5b37c2ab90133c))
* improve node link parsing and latency test settings ([3bf90af](https://github.com/roseforljh/KunBox/commit/3bf90af0a7b0a420a2e51827e0293446ba38e51e))
* **ipc:** improve VPN state management and add kernel build docs ([ce8d5e9](https://github.com/roseforljh/KunBox/commit/ce8d5e972f2602596f2a1c28c89b46a8beccab94))
* **kernel:** add kernel-level HTTP fetch and URLTest extensions ([fa6cd20](https://github.com/roseforljh/KunBox/commit/fa6cd20bc48f86b8a64b48da48185e073b621cf0))
* **latency:** persist node latency data and optimize libbox build ([316ed9b](https://github.com/roseforljh/KunBox/commit/316ed9b24f1ef98bb0a4e5bdcbcfc70c2623c0f0))
* **network:** add NetworkSwitchManager for WiFi/Cellular handoff ([a88a18f](https://github.com/roseforljh/KunBox/commit/a88a18fa0cd040b5b0d55d7c9b4385ca6cdc2610))
* **nodes:** add latency test progress bar with success/timeout stats ([7d333a3](https://github.com/roseforljh/KunBox/commit/7d333a332536fb59f999c511ae99263c6f768cdb))
* **nodes:** add profile selection for manual node creation ([4da7bef](https://github.com/roseforljh/KunBox/commit/4da7bef9ce9708ab0b4878ea8c7ebc525c6388d3))
* **nodes:** 优化搜索栏设计 ([1e3a2db](https://github.com/roseforljh/KunBox/commit/1e3a2dbb49c5682dc4c0e6d6b81ba03cd06d08da))
* **nodes:** 搜索栏跟随滚动显隐 ([dfda72a](https://github.com/roseforljh/KunBox/commit/dfda72a54ed1854e7f0af8223982de9f6a345916))
* **parser:** add Clash Meta advanced features support ([d60e3a2](https://github.com/roseforljh/KunBox/commit/d60e3a2639b73bcaef2ea9b6b74c4a2bded9be6a))
* **routing:** smart domain recognition + fix domain routing priority ([4014073](https://github.com/roseforljh/KunBox/commit/4014073ebc221ff0da0bfc1b793ea275e82d4e5c))
* **ui:** improve navigation animations and add gengar floating effect ([6f8c214](https://github.com/roseforljh/KunBox/commit/6f8c214e4ef14d7a725e21e2f63d4d7a911a5268))
* **ui:** optimize app selector dialog layout and sort selected apps first ([35b9ea2](https://github.com/roseforljh/KunBox/commit/35b9ea26d335419f688eb069f09b5d684bc1a2d4))
* **ui:** sync node selector dialog state with nodes list ([624b1d0](https://github.com/roseforljh/KunBox/commit/624b1d0788950fb141e1211b04d01144177a2b11))
* update workflow logic and dynamic versioning ([e76fbdf](https://github.com/roseforljh/KunBox/commit/e76fbdfa5d19963a87ce312da5bdace6031dbc64))
* **update:** implement app version check and notification ([ee5ee2f](https://github.com/roseforljh/KunBox/commit/ee5ee2f8d2c56ce27d4abe684aee04cfdc45113b))
* 优化磁贴交互与CI配置，完善文档 ([49447bc](https://github.com/roseforljh/KunBox/commit/49447bc146990562c06cb9dea8f70d21196c2ced))
* 应用分流 ([6193046](https://github.com/roseforljh/KunBox/commit/6193046e9e36400031d6b8abd77d0e7a6ff653db))


### Bug Fixes

* ：JSON 格式不标准 (Gson 解析异常) ([a9f2c86](https://github.com/roseforljh/KunBox/commit/a9f2c8621408523b3df34266b376aa5b4b9c9303))
* ? ([57a84d7](https://github.com/roseforljh/KunBox/commit/57a84d73670733961c794000925d7d29ece33cfe))
* 2 ([795f2ec](https://github.com/roseforljh/KunBox/commit/795f2ec3a768c516bef42a71069ad92f4b85f628))
* Add English;随便修了一些bug ([f0a12ff](https://github.com/roseforljh/KunBox/commit/f0a12ff70727ecaaec5c1f2d354255b0b3623751))
* bug ([cb5f10a](https://github.com/roseforljh/KunBox/commit/cb5f10aaa95771d0e47b3289797654b30e64ed87))
* bug修复 ([f6ab50f](https://github.com/roseforljh/KunBox/commit/f6ab50f1f5d610507122213d6af08edba1309234))
* **ci:** separate release-please and build workflows ([d879764](https://github.com/roseforljh/KunBox/commit/d879764566791477d43d6809d815a71279b5a7ff))
* **config:** node state persistence and profile node memory ([a34bab3](https://github.com/roseforljh/KunBox/commit/a34bab312758b6c324c518441c2f4c3cedd0c9c7))
* **core:** 修复 VPN 服务因 bbolt 数据库并发冲突导致自动停止的 bug ([ac301f7](https://github.com/roseforljh/KunBox/commit/ac301f7df98f35c1e6596f895f04d2e2a88b65bf))
* **core:** 彻底修复 bbolt panic - 根本原因是多进程并发访问同一数据库 ([42ba8cf](https://github.com/roseforljh/KunBox/commit/42ba8cf0018dda3f9e02e8702e8971f4ee75ac86))
* **core:** 确保测试数据库目录存在 - 添加 mkdirs() 调用 ([d57838d](https://github.com/roseforljh/KunBox/commit/d57838d98dca559ec49f06eaad68e6db03c82764))
* **data:** persist node latency and improve update check ([a017357](https://github.com/roseforljh/KunBox/commit/a017357603793546c4a54462d236f6fd9ea282a8))
* **db:** remove foreign key constraint to fix node latency persistence ([905b07e](https://github.com/roseforljh/KunBox/commit/905b07e10782d4d12c68f98db6c799ed3643ff59))
* **filter:** preserve filter keywords when switching filter modes ([e21efa8](https://github.com/roseforljh/KunBox/commit/e21efa8dc620ded02557bb28d5a18c501b877086))
* force release pr generation ([9a7398a](https://github.com/roseforljh/KunBox/commit/9a7398ad4fbc59fa431aecedb55f91796b9b8d93))
* huawei ([b4ad894](https://github.com/roseforljh/KunBox/commit/b4ad8941688d31aaa3b6ad5ec550fa04a2d6b4dc))
* internet op ([87c8238](https://github.com/roseforljh/KunBox/commit/87c8238dcf2d5b27f68bf33293adb291e88ada93))
* **ipc:** resolve UI stuck loading after Doze wake and proxy idle ([14d1713](https://github.com/roseforljh/KunBox/commit/14d171353071b3076122efcdefc9d7ce256a88bd))
* **ipc:** 修复从后台恢复后 UI 一直加载中的问题 ([806fa42](https://github.com/roseforljh/KunBox/commit/806fa42f911c13f022e75767ce99e2dceb5cb031))
* **ipc:** 修复后台恢复时UI卡住的问题 - 参考NekoBox实现 ([624361a](https://github.com/roseforljh/KunBox/commit/624361ac8348093f9fd00eae6767f3b9ee50cfe9))
* **ipc:** 应用返回前台时重置所有连接，修复 TG 等应用一直加载中的问题 ([3549747](https://github.com/roseforljh/KunBox/commit/35497474d0fec6031cafa1112d13e118885c3e07))
* **latency-test:** resolve no available network interface error ([ef5b60c](https://github.com/roseforljh/KunBox/commit/ef5b60cbcb2aa8bae7e23140a8de4cd0a408effd))
* **latency:** enforce user-configured timeout for single node test ([9103dba](https://github.com/roseforljh/KunBox/commit/9103dba01b0f5ac7a1c68c9c358bca52ae804a53))
* **latency:** pass latency value directly in callback to fix race condition ([ac2e134](https://github.com/roseforljh/KunBox/commit/ac2e134db6c9c7c5febc2adcff95849cd5a579d2))
* **latency:** resolve SS+ShadowTLS dependency for latency testing ([451e29e](https://github.com/roseforljh/KunBox/commit/451e29eded484e531a0861faab0043fbeac0bfea))
* **latency:** restore latency data when switching profiles ([67afd6f](https://github.com/roseforljh/KunBox/commit/67afd6f276d17574f5491fab5752a7fe33be3aaf))
* **latency:** support latency testing for nodes from all profiles ([020f82b](https://github.com/roseforljh/KunBox/commit/020f82b1b6c755af1e2ae0a578c323f9051ffd7d))
* **libbox:** adapt to new CommandServer API and fix service shutdown ([6b3df81](https://github.com/roseforljh/KunBox/commit/6b3df8176adfec224a03b180fb21880d8214237b))
* **memory:** optimize memory usage with LRU caches and static TypeTokens ([f25fa5e](https://github.com/roseforljh/KunBox/commit/f25fa5eea01a9343fd4208448dd1bb731e622769))
* **nav:** correct route mapping for Diagnostics and Logs pages ([cd18066](https://github.com/roseforljh/KunBox/commit/cd18066b55adcd8aae379e7e1438db7f85629ee1))
* **network:** add idle connection cleanup for TG image loading issue ([b6c54ef](https://github.com/roseforljh/KunBox/commit/b6c54efe70dda696c0f3b5efc8d504dec6d9f32c))
* **network:** add proactive recovery for faster foreground resume ([85764bd](https://github.com/roseforljh/KunBox/commit/85764bd46b5e2770d1a9703077eecae8c336f5f6))
* **notification:** persist activeLabel to VpnStateStore for cross-process sync ([f7507ae](https://github.com/roseforljh/KunBox/commit/f7507ae62ca9119077d26d26f035c1c7afadd837))
* **parser:** add wireguard and ssh link parsing support ([3494923](https://github.com/roseforljh/KunBox/commit/3494923947477f59bcffa927734bc11e90050dea))
* **parser:** handle empty string fields in Clash YAML hysteria2 nodes ([a4edefc](https://github.com/roseforljh/KunBox/commit/a4edefc3608b4a6bc62e6cfb75be9148d2ca4aef))
* **parser:** handle multiple links in clipboard import ([223c085](https://github.com/roseforljh/KunBox/commit/223c0852fd539ee5a7cc6150dd72062735032067))
* **parser:** 修复 vmess 链接无法从剪贴板导入的问题 ([882eeca](https://github.com/roseforljh/KunBox/commit/882eeca4b2861c8c15f09b01ba3b3d51acb32db5))
* **power:** add background power saving with configurable delay ([208d59a](https://github.com/roseforljh/KunBox/commit/208d59a1f1ae3cdbbba9d3c80d2cccda4164e09b))
* **power:** enable main process self-termination for power saving ([0b39d1f](https://github.com/roseforljh/KunBox/commit/0b39d1f70e0faf5e330ee7ef82c90d6742626920))
* **rulesets:** initialize defaults with toggleable downloads ([6d522ff](https://github.com/roseforljh/KunBox/commit/6d522ff25c7eeba18f0073762b3d363668d97927))
* **rulesets:** load geosite list via github tree ([79e06b4](https://github.com/roseforljh/KunBox/commit/79e06b4f1b0865f10fb7204ec98504d47cfd615e))
* **service:** notification bar node switch not working ([3dfb731](https://github.com/roseforljh/KunBox/commit/3dfb731d438376eb176245e0b314f6d071c76b3a))
* **service:** notification not dismissed when app removed from recents ([1dc9834](https://github.com/roseforljh/KunBox/commit/1dc983460b537444372366ceeba44f29ca71e4fd))
* **service:** 修复息屏后返回App时Telegram等应用一直加载的问题 ([137e64d](https://github.com/roseforljh/KunBox/commit/137e64d65dfaa8b7181dcd2990181f34c3ee8e7c))
* **settings:** 移除断线重连功能和UI ([bc2e5b8](https://github.com/roseforljh/KunBox/commit/bc2e5b8bc573e93fcaff264f597641f989028cf1))
* **shortcut:** node selection and VPN toggle not working from app shortcut ([1253563](https://github.com/roseforljh/KunBox/commit/12535633853cc1dcee0ea08188405d6d1672f10b))
* **subscription:** 修复取消导入后弹窗重复弹出及应用无法使用自身代理的问题 ([ef12cbb](https://github.com/roseforljh/KunBox/commit/ef12cbbf9166d2e87deed1f5d073ade70e38e3a0))
* **subscription:** 兼容订阅剩余/到期信息 ([8af42e1](https://github.com/roseforljh/KunBox/commit/8af42e1657ebf1dba2eb3a3d6edd56b13b9e58a5))
* **tun:** refine app list dialog quick select ([21c4c43](https://github.com/roseforljh/KunBox/commit/21c4c430db38b1a72cf2dca448a3269dcab4b92d))
* **ui:** add smooth crossfade animation for toggle icon transition ([4cdb257](https://github.com/roseforljh/KunBox/commit/4cdb2573bfcbedadee9401c3ef14b89a2d53d87d))
* **ui:** adjust ProfileCard layout weight for better text display ([480ca11](https://github.com/roseforljh/KunBox/commit/480ca11005d3e2ca996dab2c3aa4caae133bbded))
* **ui:** clean build warnings and consolidate stability notes ([3eee087](https://github.com/roseforljh/KunBox/commit/3eee087a49171f9ab80175f646cb9ad1caaa045c))
* **ui:** improve latency display placeholder ([73fb2a0](https://github.com/roseforljh/KunBox/commit/73fb2a09ff46672b91e63a5cfb8e9c536c5149bb))
* **ui:** InputDialog 输入框支持水平滚动 ([a63fcd1](https://github.com/roseforljh/KunBox/commit/a63fcd1ec7dfcb761b13cd02fe2abf234fdc6d05))
* **ui:** prevent gengar image clipping during crossfade animation ([39055b9](https://github.com/roseforljh/KunBox/commit/39055b9f5cfe1e0c624212bfb29ea77dad7d271b))
* **ui:** toggle fab visibility on scroll ([c42cff8](https://github.com/roseforljh/KunBox/commit/c42cff82b5f3c9e7e21c951ee0b617f54866799a))
* **ui:** 列表到底部时隐藏悬浮按钮 ([a232719](https://github.com/roseforljh/KunBox/commit/a23271949474e9b777c2872d64ea29069e9c00e6))
* **ui:** 应用分流仅显示仅允许列表中的应用并移除直连选项 ([05ff1da](https://github.com/roseforljh/KunBox/commit/05ff1da8acb967b3be6b1de88c53460200603180))
* ui控制参数 ([6745e0d](https://github.com/roseforljh/KunBox/commit/6745e0d65bf1d4f0a81b92bca73137e1fa7eef3b))
* **ui:** 调整 BigToggle 图像显示 ([8a53ced](https://github.com/roseforljh/KunBox/commit/8a53ced548eaf4cca29909706eb37c3a59bae928))
* **vpn:** hot reload failure causes network loss ([2000436](https://github.com/roseforljh/KunBox/commit/20004364e3aa3e56a1d731f9a2e5f1762f70b335))
* **vpn:** per-app proxy settings not applied to TUN interface ([f3a9320](https://github.com/roseforljh/KunBox/commit/f3a93203861231fb83704c739687ec12c9b36d42))
* **vpn:** 修复VPN自动关闭和启动期间连接泄漏问题 ([9975a2e](https://github.com/roseforljh/KunBox/commit/9975a2ea6f4f8746f7f999477aff6ec864f059ba))
* **vpn:** 修复多个 VPN 启动和节点切换问题 ([f61fb11](https://github.com/roseforljh/KunBox/commit/f61fb11acc18bcb158698204e2393ce541a0ede7))
* **vpn:** 修复息屏后 Telegram 等应用卡在加载中的问题 ([093ab71](https://github.com/roseforljh/KunBox/commit/093ab71863228341421a8e61f6aee6614ef21cb1))
* **vpn:** 全面加固 VPN 息屏保活机制,防止被系统杀死 ([0b39d09](https://github.com/roseforljh/KunBox/commit/0b39d0981024dd5be46fc6efa160eab2a41a8ffd))
* **vpn:** 添加周期性健康检查防止 VPN 服务僵尸状态 ([843d4ec](https://github.com/roseforljh/KunBox/commit/843d4ecb413485663f72fd4c56a00c01f90cc2cb))
* 主题适配 ([234924c](https://github.com/roseforljh/KunBox/commit/234924cc7d537167d50e091c5a70abff23e5b2a1))
* 优化 ([567b554](https://github.com/roseforljh/KunBox/commit/567b5542ef48b785fb10fbf884f8474b8f19563f))
* 优化 SingBoxCore 代码注释和错误处理 ([d69bd8d](https://github.com/roseforljh/KunBox/commit/d69bd8dc5b4849179e17e1170e049098d286c1ec))
* 优化延迟测试 ([a1fdcb4](https://github.com/roseforljh/KunBox/commit/a1fdcb47f221edc69228f6af080e9453b8be0251))
* 优化延迟测试 ([7b67a0a](https://github.com/roseforljh/KunBox/commit/7b67a0a26888099c0e06a66dae838cda10a3afb9))
* 优化构建配置和 ProGuard 混淆规则 ([046362a](https://github.com/roseforljh/KunBox/commit/046362af725fb7d87bcd9ea3e93a6365a15b0aca))
* 优化网络稳定 ([b03dd47](https://github.com/roseforljh/KunBox/commit/b03dd47ed083fcd9795ecf0baabb5066607d5a72))
* 优化订阅更新逻辑 ([75fa6c5](https://github.com/roseforljh/KunBox/commit/75fa6c5d0ec4227f8e10b02b5734ec22e8e6036a))
* 优化重连、断连、热重载 ([95b3bfd](https://github.com/roseforljh/KunBox/commit/95b3bfd547be8ba80e091f096b0eb7049c6cef6e))
* 修复了热切换节点后 连接延迟的问题 ([894b2af](https://github.com/roseforljh/KunBox/commit/894b2affa8ce6a8a6ac53c3837b11b75b0d22f71))
* 修复从通知栏进入app后返回键需按两次才能退出的问题 ([1240f82](https://github.com/roseforljh/KunBox/commit/1240f8246a52b05189a33502577157873d223bb2))
* 修复节点选择状态未正确保存和显示的问题 ([1cdf3f7](https://github.com/roseforljh/KunBox/commit/1cdf3f7f69ff23dff50cb18ee748725d595e21b7))
* 修复跨配置切换节点时无法上网的问题 ([ab3ba67](https://github.com/roseforljh/KunBox/commit/ab3ba67ac8c0deea104f1ee2cde16d7250ec09da))
* 全局禁用endpoint_independent_nat和auto_route ([2aa437e](https://github.com/roseforljh/KunBox/commit/2aa437ecba131769cb32b2f94a84bdcb4206a4a2))
* 关于应用里加上app本体及singbox的版本号 ([2c6bd61](https://github.com/roseforljh/KunBox/commit/2c6bd61777b3f61d615ca4496ff6358539c9109b))
* 删除组逻辑 ([5aa7021](https://github.com/roseforljh/KunBox/commit/5aa702157adf0cfbb92aec1eb810105ca96e990b))
* 只读节点信息 ([4c0976b](https://github.com/roseforljh/KunBox/commit/4c0976b9e2eb3f8305cb91a32bd2b97ecce55fd5))
* 可能解决了一些闪退的问题 ([ef5e728](https://github.com/roseforljh/KunBox/commit/ef5e7283710ac2839548fe1bfe9a99303c8304f3))
* 同步官方内核 ([f054302](https://github.com/roseforljh/KunBox/commit/f054302a5c90c74e9ff95ae52ab0929b844e363e))
* 增加日志开关 ([9beb8ff](https://github.com/roseforljh/KunBox/commit/9beb8ff72f75911b04fef7e0335e9a4c650c53ea))
* 多处优化 ([189e8c9](https://github.com/roseforljh/KunBox/commit/189e8c9077b167f9d6ca10b3869aff544af48bca))
* 多节点闪退 ([d0d26e5](https://github.com/roseforljh/KunBox/commit/d0d26e5a7d9ce3a386107a91b771976c16f8ccab))
* 实时显示上传和下载速度 ([0af613e](https://github.com/roseforljh/KunBox/commit/0af613ee7a5aa3afb2b2d4140ed0c2de11f601cd))
* 实现配置管理，加号按钮里面本地文件和扫描二维码 ([ec24666](https://github.com/roseforljh/KunBox/commit/ec24666f53992d0137469513f520f3db9275fb0f))
* 底部导航切换过渡动画、磁贴按钮 ([1a4f92c](https://github.com/roseforljh/KunBox/commit/1a4f92c06b4b104088149d52bb223894305c66b3))
* 性能优化 ([4e3cfee](https://github.com/roseforljh/KunBox/commit/4e3cfee07cfb44ad8b66a291df9561b6cbc729bc))
* 性能优化 ([38b27c0](https://github.com/roseforljh/KunBox/commit/38b27c07aba1165c203ba1dc04fb133ae38c0c83))
* 新增 URL Scheme 深度链接支持和问题修复文档 ([5432a6b](https://github.com/roseforljh/KunBox/commit/5432a6b948e705bc49fec833e626c521f612a829))
* 新增应用启动时清理遗留临时数据库文件的功能 ([1bf5a2a](https://github.com/roseforljh/KunBox/commit/1bf5a2a6f27b32a7be22fb7af9c15e36223756e7))
* 新增项目文档索引和优化指南 ([108d3b9](https://github.com/roseforljh/KunBox/commit/108d3b9a1fcb3fa891dff3fcd9686d4f5358b537))
* 更新 planning-with-files submodule 和临时修复代码 ([dffd1e9](https://github.com/roseforljh/KunBox/commit/dffd1e90c5b92f5ca27e3f103df7d645a39a5dad))
* 更新镜像、添加导入导出数据功能 ([a20a115](https://github.com/roseforljh/KunBox/commit/a20a115986e7d0ef646dca830a4788135a4c3433))
* 清理不必要的临时文件和 planning-with-files ([04f9670](https://github.com/roseforljh/KunBox/commit/04f96701c1f8f8c4fee5422cdecb9f08aa459776))
* 清理冗余调试日志以提升性能和可读性 ([dd3a404](https://github.com/roseforljh/KunBox/commit/dd3a404a0301ed2a8be4550a9b3ef196e34b4726))
* 清理废弃的构建脚本并优化 libbox 构建流程 ([de78f57](https://github.com/roseforljh/KunBox/commit/de78f576f6e998a326cc9a0d3d55dbcbc84990c4))
* 移除内核版本管理功能并优化核心服务 ([931e169](https://github.com/roseforljh/KunBox/commit/931e1697574444ecf59c565b402aac169a5b7d93))
* 移除已过时的 bugfix 文档 ([2e80582](https://github.com/roseforljh/KunBox/commit/2e8058272a2c113347d9a196c8af7c621734ee78))
* 移除节点名称自动添加协议后缀 ([2bd0423](https://github.com/roseforljh/KunBox/commit/2bd0423a97ccb72c40b725c5df226b1fc98710f7))
* 稳定网络 ([2bcd87d](https://github.com/roseforljh/KunBox/commit/2bcd87d8e21d210704662f487fe0849450d6fea2))
* 统一 TUN MTU 默认值并移除冗余日志 ([7af9639](https://github.com/roseforljh/KunBox/commit/7af96395f66d20b3c79a7d105eb5bbcf9eab643e))
* 规则集定时自动更新 ([b1803b4](https://github.com/roseforljh/KunBox/commit/b1803b40ab9590ebb5d358ac6219ed16a6a72898))
* 解决 VPN 快速重启导致的网络接口不同步问题 ([0732fd6](https://github.com/roseforljh/KunBox/commit/0732fd607529d3d28522320a698f5bab67ea10dd))
* 解析器识别 anytls 节点 ([742cd95](https://github.com/roseforljh/KunBox/commit/742cd95d84e429df2f22ffc7ebab552b7f63fb27))
* 订阅定时更新 ([0363898](https://github.com/roseforljh/KunBox/commit/0363898cc9377c9f4cd6042a74f01cfbcc24c94a))
* 部分订阅节点丢失 ([ecb9f74](https://github.com/roseforljh/KunBox/commit/ecb9f745eda3443e02a25014ec6e626c195504a3))
* 长按功能 ([276fc8e](https://github.com/roseforljh/KunBox/commit/276fc8e3589660a9e19573f23c4a5678fcac13dd))
* 长按图标出现的操作选项 ([db340c0](https://github.com/roseforljh/KunBox/commit/db340c095572ce3cdf5cd6414ef903a21b445bfa))
* 首页设计 ([3a86145](https://github.com/roseforljh/KunBox/commit/3a86145d750e62abb38de4a0f6521ba0efc0cb51))


### Performance Improvements

* implement 4 performance optimizations ([465a7ee](https://github.com/roseforljh/KunBox/commit/465a7eea13a46c2a8a6448dfa82acc29d9d30b69))
* **network:** optimize throughput and enable GSO ([ed83201](https://github.com/roseforljh/KunBox/commit/ed832018a458ed55de3a627f0fda47ef457f4b1e))
* **repository:** optimize node import performance and add Kryo serialization ([29f8268](https://github.com/roseforljh/KunBox/commit/29f8268ee3d93492684fd510e648ac3b4df09bdf))
* **repository:** parallelize node extraction and subscription updates ([a86228e](https://github.com/roseforljh/KunBox/commit/a86228e6994b0cf6662fd3d359473e53434519de))

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

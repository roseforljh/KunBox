package com.kunk.singbox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiCleanupStructureTest {

    @Test
    fun legacyScreensAndManagerAreRemoved() {
        listOf(
            "ui/screens/AppGroupsScreen.kt",
            "ui/screens/AppRulesScreen.kt",
            "ui/screens/SplashScreen.kt",
            "ui/components/AppListLoadingDialog.kt",
            "viewmodel/VpnConnectionManager.kt"
        ).forEach { path ->
            assertFalse("旧文件仍存在: $path", mainSource(path).exists())
        }

        val navigation = mainSource("ui/navigation/AppNavigation.kt").readNormalizedText()
        assertTrue(navigation.contains("object AppRouting : Screen(\"app_rules\")"))
        assertTrue(navigation.contains("route = Screen.AppRouting.route"))
        assertTrue(navigation.contains(") { AppRoutingScreen(navController) }"))
        assertFalse(navigation.contains("Screen.AppRules"))
    }

    @Test
    fun repositoryStateFlowsAreExposedDirectly() {
        val expectations = mapOf(
            "viewmodel/DashboardViewModel.kt" to listOf(
                "val activeProfileId: StateFlow<String?> = configRepository.activeProfileId",
                "val activeNodeId: StateFlow<String?> = configRepository.activeNodeId",
                "val profiles: StateFlow<List<ProfileUi>> = configRepository.profiles"
            ),
            "viewmodel/NodesViewModel.kt" to listOf(
                "val allNodes: StateFlow<List<NodeUi>> = configRepository.allNodes",
                "val profiles: StateFlow<List<ProfileUi>> = configRepository.profiles",
                "val activeNodeId: StateFlow<String?> = configRepository.activeNodeId",
                "buildDashboardNodes("
            ),
            "viewmodel/ProfilesViewModel.kt" to listOf(
                "val profiles: StateFlow<List<ProfileUi>> = configRepository.profiles",
                "val allNodes: StateFlow<List<NodeUi>> = configRepository.allNodes",
                "val activeProfileId: StateFlow<String?> = configRepository.activeProfileId"
            ),
            "viewmodel/RuleSetViewModel.kt" to listOf(
                "val settings: StateFlow<AppSettings> = settingsRepository.settings"
            ),
            "viewmodel/SettingsViewModel.kt" to listOf(
                "val settings: StateFlow<AppSettings> = repository.settings"
            )
        )

        expectations.forEach { (path, tokens) ->
            val source = mainSource(path).readNormalizedText()
            tokens.forEach { token -> assertTrue("$path 缺少 $token", source.contains(token)) }
        }
    }

    @Test
    fun duplicateResultAndPromptFlowsAreRemoved() {
        val nodes = mainSource("viewmodel/NodesViewModel.kt").readNormalizedText()
        listOf("_switchResult", "_latencyMessage", "_addNodeResult").forEach { token ->
            assertFalse("NodesViewModel 仍包含 $token", nodes.contains(token))
        }

        val dashboard = mainSource("viewmodel/DashboardViewModel.kt").readNormalizedText()
        listOf("_updateStatus", "_testStatus", "_actionStatus").forEach { token ->
            assertFalse("DashboardViewModel 仍包含 $token", dashboard.contains(token))
        }
        assertTrue(dashboard.contains("val toastEvents: SharedFlow<String>"))

        val profiles = mainSource("viewmodel/ProfilesViewModel.kt").readNormalizedText()
        assertFalse(profiles.contains("private val _updateStatus"))
        val settingsScreen = mainSource("ui/screens/SettingsScreen.kt").readNormalizedText()
        assertFalse(settingsScreen.contains("LaunchedEffect(importState)"))
    }

    @Test
    fun installedAppMetadataStaysLightweightAndIconsLoadOnDemand() {
        listOf(
            "ui/components/AppMultiSelectDialog.kt",
            "ui/screens/AppRoutingComponents.kt",
            "ui/screens/AppRoutingScreen.kt"
        ).forEach { path ->
            val source = mainSource(path).readNormalizedText()
            listOf("getApplicationIcon", "getLaunchIntentForPackage", "packageManager", "toBitmap").forEach { token ->
                assertFalse("$path 仍在 UI 中调用 $token", source.contains(token))
            }
        }

        val model = mainSource("model/UiModels.kt").readNormalizedText()
        val installedAppUi = Regex(
            """data class InstalledAppUi\([\s\S]*?\n\)"""
        ).find(model)?.value.orEmpty()
        assertTrue("缺少 InstalledAppUi", installedAppUi.isNotEmpty())
        assertFalse("InstalledAppUi 不应持有 Bitmap", installedAppUi.contains("Bitmap"))
        assertFalse("InstalledAppUi 不应持有图标字段", installedAppUi.contains("val icon"))

        val repository = mainSource("repository/InstalledAppsRepository.kt").readNormalizedText()
        assertTrue(repository.contains("StateFlow<List<InstalledAppUi>>"))
        val metadataLoad = repository.extractFunctionBody("loadAppsLocked")
        assertFalse("元数据加载不应解码全部应用图标", metadataLoad.contains("getApplicationIcon"))
        assertFalse("元数据加载不应创建 Bitmap", metadataLoad.contains("toBitmap"))
        assertFalse("应用列表不应请求未使用的元数据", metadataLoad.contains("GET_META_DATA"))
        assertFalse("启动器状态不应逐应用查询", metadataLoad.contains("getLaunchIntentForPackage"))
        assertTrue("启动器状态应使用批量查询结果", metadataLoad.contains("queryLauncherPackages(pm)"))
        val launcherQuery = repository.extractFunctionBody("queryLauncherPackages")
        assertTrue(launcherQuery.contains("Intent.CATEGORY_INFO"))
        assertTrue(launcherQuery.contains("Intent.CATEGORY_LAUNCHER"))
        assertTrue(launcherQuery.contains("queryIntentActivities"))

        val iconLoad = repository.extractFunctionBody("loadIcon")
        assertTrue("图标加载必须切换到 IO 调度器", iconLoad.contains("withContext(Dispatchers.IO)"))
        assertTrue("图标应通过 PackageManager 按需读取", iconLoad.contains("getApplicationIcon"))
        assertTrue("图标解码应位于按需加载入口", iconLoad.contains("toBitmap"))
        assertTrue("图标缓存必须有容量上限", repository.contains("LruCache<String, Bitmap>(ICON_CACHE_MAX_KB)"))
        assertTrue("图标缓存应按 Bitmap 占用计量", repository.contains("override fun sizeOf"))

        val viewModel = mainSource("viewmodel/InstalledAppsViewModel.kt").readNormalizedText()
        assertTrue(viewModel.contains("repository.appItems"))
        assertTrue(viewModel.contains("repository.loadIcon(packageName)"))
        listOf("getApplicationIcon", "getLaunchIntentForPackage", "toBitmap").forEach { token ->
            assertFalse("InstalledAppsViewModel 不应再计算应用元数据: $token", viewModel.contains(token))
        }

        val dialog = mainSource("ui/components/AppMultiSelectDialog.kt").readNormalizedText()
        assertTrue(dialog.contains("installedAppsViewModel.loadAppsIfNeeded()"))
        assertTrue(dialog.contains("installedAppsViewModel.loadIcon(app.packageName)"))
        assertFalse(dialog.contains("installedAppsViewModel.refreshApps()"))

        val routingComponents = mainSource("ui/screens/AppRoutingComponents.kt").readNormalizedText()
        val rememberAppIcon = routingComponents.extractFunctionBody("rememberAppIcon")
        assertTrue(rememberAppIcon.contains("remember(packageName)"))
        assertTrue(rememberAppIcon.contains("LaunchedEffect(packageName, loadIcon)"))
        assertTrue(rememberAppIcon.contains("icon = loadIcon(packageName)"))

        val tunSettings = mainSource("ui/screens/TunSettingsScreen.kt").readNormalizedText()
        assertTrue(tunSettings.contains("installedAppsRepo.appItems"))
        assertTrue(tunSettings.contains("installedAppsRepo.loadApps()"))
        assertFalse(tunSettings.contains("refreshApps()"))
        assertFalse(repository.contains("StateFlow<List<InstalledApp>>"))
    }

    @Test
    fun installedAppsRetryErrorsWithoutSwallowingCancellation() {
        val repository = mainSource("repository/InstalledAppsRepository.kt").readNormalizedText()
        val needsLoadingBody = repository.extractFunctionBody("needsLoading")
        assertTrue(
            "Error 状态必须允许 loadAppsIfNeeded 重试",
            needsLoadingBody.contains("LoadingState.Error") ||
                !needsLoadingBody.contains("LoadingState.Idle")
        )

        val loadBody = repository.extractFunctionBody("loadAppsLocked")
        val cancellationCatch = Regex(
            """catch\s*\(\s*(\w+)\s*:\s*CancellationException\s*\)"""
        ).find(loadBody)
        assertTrue("CancellationException 必须单独捕获并重抛", cancellationCatch != null)

        if (cancellationCatch != null) {
            val genericCatch = Regex(
                """catch\s*\(\s*\w+\s*:\s*Exception\s*\)"""
            ).findAll(loadBody).lastOrNull()
            assertTrue("CancellationException 捕获必须位于外层 Exception 捕获之前", genericCatch != null)
            if (genericCatch != null) {
                assertTrue(cancellationCatch.range.first < genericCatch.range.first)
                val cancellationBlock = loadBody.substring(
                    cancellationCatch.range.first,
                    genericCatch.range.first
                )
                assertTrue(
                    "CancellationException 必须重抛",
                    cancellationBlock.contains("throw ${cancellationCatch.groupValues[1]}")
                )
            }
        }
    }

    @Test
    fun connectionLogsAndTrafficUseSingleEfficientPipelines() {
        val connectionViewModel = mainSource("viewmodel/ConnectionInfoViewModel.kt").readNormalizedText()
        val connectionScreen = mainSource("ui/screens/ConnectionInfoScreen.kt").readNormalizedText()
        assertEquals(
            "ConnectionInfoViewModel 只应暴露一个 UI StateFlow",
            1,
            Regex("""(?m)^\s*val\s+\w+\s*:\s*StateFlow<""")
                .findAll(connectionViewModel)
                .count()
        )
        assertTrue(connectionViewModel.contains("val uiState"))
        assertTrue(connectionViewModel.contains("SharingStarted.WhileSubscribed"))
        listOf("screenActive", "pollJob", "startPolling", "stopPolling").forEach { token ->
            assertFalse("ConnectionInfoViewModel 仍包含手动轮询生命周期: $token", connectionViewModel.contains(token))
        }
        assertTrue(connectionScreen.contains("viewModel.uiState.collectAsStateWithLifecycle()"))
        assertEquals(
            "ConnectionInfoScreen 只应收集一个状态流",
            1,
            Regex("""collectAsStateWithLifecycle\s*\(""")
                .findAll(connectionScreen)
                .count()
        )
        listOf("DisposableEffect", "setScreenActive").forEach { token ->
            assertFalse("ConnectionInfoScreen 不应手动控制订阅生命周期: $token", connectionScreen.contains(token))
        }

        val logViewModel = mainSource("viewmodel/LogViewModel.kt").readNormalizedText()
        assertTrue(logViewModel.contains("flowOn(Dispatchers.Default)"))

        val trafficViewModel = mainSource("viewmodel/TrafficStatsViewModel.kt").readNormalizedText()
        assertFalse(trafficViewModel.contains("topNodes.find"))
        assertFalse(trafficViewModel.contains("percentages.find"))
        assertFalse(trafficViewModel.contains("getNodeById("))
        assertTrue(trafficViewModel.contains("allNodes.value"))
        assertTrue(
            "节点名快照应一次构造成 id 到 name 的 Map",
            trafficViewModel.contains("associate {") || trafficViewModel.contains("associateBy(")
        )
        assertTrue(trafficViewModel.contains("buildNodeNameMap("))

        val dashboardViewModel = mainSource("viewmodel/DashboardViewModel.kt").readNormalizedText()
        assertTrue(dashboardViewModel.contains("statsUiActive"))
        assertTrue(dashboardViewModel.contains(".onStart {"))
        assertTrue(dashboardViewModel.contains(".onCompletion {"))
    }

    @Test
    fun plainThemeSkipsPressStateAnimationAndGraphicsLayer() {
        val source = mainSource("ui/theme/LiquidGlassPressFeedback.kt").readNormalizedText()
        val body = source.extractFunctionBody("liquidGlassPressFeedback")
        val firstHeavyOperation = listOf(
            body.indexOf("collectIsPressedAsState"),
            body.indexOf("animateFloatAsState"),
            body.indexOf("graphicsLayer")
        ).filter { it >= 0 }.minOrNull() ?: error("缺少 LiquidGlass 按压反馈实现")
        val plainThemeGuard = body.indexOf("if (!useLiquidGlass)")
        val liquidThemeBranch = body.indexOf("if (useLiquidGlass) {")

        assertTrue(
            "普通主题必须在创建 pressed、animation、graphicsLayer 前退出",
            plainThemeGuard in 0 until firstHeavyOperation ||
                liquidThemeBranch in 0 until firstHeavyOperation
        )
    }

    @Test
    fun liquidGlassClickFeedbackDoesNotCompeteWithComponentClicks() {
        val navSource = mainSource("ui/components/AppNavBar.kt").readNormalizedText()
        val consumeBody = navSource.extractFunctionBody("consumeUnclaimedClicks")
        assertTrue(consumeBody.contains("awaitEachGesture"))
        assertTrue(consumeBody.contains("requireUnconsumed = true"))
        assertFalse(consumeBody.contains("PointerEventPass.Final"))

        val controlsSource = mainSource("ui/theme/LiquidGlassControls.kt").readNormalizedText()
        assertFalse(controlsSource.contains("liquidGlassPressBounceEffect"))
        assertFalse(controlsSource.contains("awaitFirstDown(requireUnconsumed = false)"))
        assertFalse(controlsSource.contains("waitForUpOrCancellation"))

        val floatingActionBody = controlsSource.extractFunctionBody("LiquidGlassFloatingActionSurface")
        assertTrue(floatingActionBody.contains("collectIsPressedAsState"))
        assertTrue(floatingActionBody.contains("interactionSource = interactionSource"))
        assertTrue(floatingActionBody.contains("graphicsLayer"))
        assertTrue(floatingActionBody.contains(".clickable("))
    }

    @Test
    fun backgroundLocaleDialogBlurAndImportTextStayCentralized() {
        val localeSource = mainSource("utils/LocaleHelper.kt").readNormalizedText()
        assertTrue(localeSource.contains("fun wrapFromCache(context: Context)"))

        listOf("service/SingBoxService.kt", "service/ProxyOnlyService.kt").forEach { path ->
            val source = mainSource(path).readNormalizedText()
            assertTrue(source.contains("override fun attachBaseContext(newBase: Context)"))
            assertTrue(source.contains("LocaleHelper.wrapFromCache(newBase)"))
        }

        val dialogSource = mainSource("ui/screens/NodeDetailDialogs.kt").readNormalizedText()
        val detourDialog = dialogSource.extractFunctionBody("DetourNodeSelectDialog")
        assertTrue(detourDialog.contains("LiquidGlassDialogEffect()"))
    }

    @Test
    fun trafficRefreshAnimationExistsOnlyWhileLoading() {
        val source = mainSource("ui/screens/TrafficStatsScreen.kt").readNormalizedText()
        val infiniteTransition = source.indexOf("rememberInfiniteTransition(")
        if (infiniteTransition < 0) return

        val loadingBranch = source.lastIndexOf("if (uiState.isLoading)", infiniteTransition)
        assertTrue("刷新无限动画必须位于 isLoading 分支内", loadingBranch >= 0)
        if (loadingBranch >= 0) {
            assertTrue(
                "isLoading 分支必须包含刷新无限动画",
                source.extractBlockAt(loadingBranch).contains("rememberInfiniteTransition")
            )
        }
    }

    private fun mainSource(path: String): File = File("src/main/java/com/kunk/singbox/$path")
}

private fun File.readNormalizedText(): String = readText().replace("\r\n", "\n")

private fun String.extractFunctionBody(functionName: String): String {
    val functionStart = listOf("fun $functionName", "fun Modifier.$functionName")
        .map(::indexOf)
        .firstOrNull { it >= 0 }
        ?: -1
    assertTrue("缺少函数 $functionName", functionStart >= 0)
    return extractBlockAt(functionStart)
}

private fun String.extractBlockAt(start: Int): String {
    val bodyStart = indexOf('{', start)
    assertTrue("缺少代码块", bodyStart >= 0)

    var depth = 0
    for (index in bodyStart until length) {
        when (this[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return substring(bodyStart, index + 1)
            }
        }
    }
    throw AssertionError("代码块未闭合")
}

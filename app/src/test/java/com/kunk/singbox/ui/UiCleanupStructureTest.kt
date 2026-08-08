package com.kunk.singbox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiCleanupStructureTest {

    @Test
    fun profileEditorViewModelCanBeCreatedByLifecycleReflection() {
        val constructor = Class.forName("com.kunk.singbox.ui.screens.ProfileEditorViewModel")
            .getConstructor()

        constructor.newInstance()
    }

    @Test
    fun crowdedEditorsUseFullScreenPages() {
        val navigation = mainSource("ui/navigation/AppNavigation.kt").readNormalizedText()
        val nodes = mainSource("ui/screens/NodesScreen.kt").readNormalizedText()
        val routingComponents = mainSource("ui/screens/AppRoutingComponents.kt").readNormalizedText()
        val profileDialogs = mainSource("ui/screens/ProfilesScreenDialogs.kt").readNormalizedText()
        val appSelector = mainSource("ui/components/AppMultiSelectDialog.kt").readNormalizedText()

        assertTrue(navigation.contains("object NodeAdd : Screen(\"node_add\")"))
        assertTrue(navigation.contains("object NodeProtocolSelect : Screen(\"node_protocol_select\")"))
        assertTrue(navigation.contains("AddNodeScreen("))
        assertTrue(navigation.contains("NodeProtocolSelectScreen("))
        assertTrue(nodes.contains("navController.navigate(Screen.NodeAdd.route)"))
        assertTrue(nodes.contains("navController.navigate(Screen.NodeProtocolSelect.route)"))
        assertFalse(mainSource("ui/components/AddNodeDialog.kt").exists())

        assertTrue(routingComponents.contains("FullScreenDialogPage("))
        assertTrue(routingComponents.contains("fullScreen = true"))
        assertFalse(routingComponents.contains("AlertDialog("))
        assertTrue(appSelector.contains("FullScreenDialogPage("))
        assertFalse(appSelector.contains("fillMaxHeight(0.92f)"))
        val customConfigPage = profileDialogs.extractFunctionBody("CustomConfigPage")
        assertTrue(customConfigPage.contains("FullScreenDialogPage("))
        assertEquals(1, customConfigPage.windowed("LazyColumn(".length).count { it == "LazyColumn(" })
    }

    @Test
    fun singleNodeSelectionUsesTheSharedProfileScopedPage() {
        val picker = mainSource("ui/screens/NodePickerPage.kt").readNormalizedText()
        val pickerBody = picker.extractFunctionBody("NodePickerPage")
        assertTrue(pickerBody.contains("NodeSearchBar("))
        assertTrue(pickerBody.contains("NodeFilterDialog("))
        assertTrue(pickerBody.contains("GridCells.Fixed(nodeColumnCount)"))
        assertTrue(pickerBody.contains("LiquidGlassFilterChip("))

        listOf(
            "ui/screens/DashboardScreen.kt",
            "ui/screens/AppRoutingComponents.kt",
            "ui/screens/DomainRulesScreen.kt",
            "ui/screens/RuleSetsScreen.kt",
            "ui/screens/NodeDetailScreen.kt"
        ).forEach { path ->
            assertTrue("$path 必须使用公共节点选择页", mainSource(path).readNormalizedText().contains("NodePickerPage("))
        }

        val legacyDialogs = mainSource("ui/components/NodeSelectionDialogs.kt").readNormalizedText()
        assertFalse(legacyDialogs.contains("fun ProfileNodeSelectDialog("))
        assertFalse(legacyDialogs.contains("fun NodeSelectorDialog("))
    }

    @Test
    @Suppress("LongMethod")
    fun fullScreenPagesUseEdgeToEdgeWindowsAndFloatingHeaders() {
        val fullScreenPage = mainSource("ui/components/FullScreenDialogPage.kt").readNormalizedText()
        val nodeAdd = mainSource("ui/screens/NodeAddScreens.kt").readNormalizedText()
        val nodeSelection = mainSource("ui/screens/NodePickerPage.kt").readNormalizedText()
        val appSelector = mainSource("ui/components/AppMultiSelectDialog.kt").readNormalizedText()
        val profileEditor = mainSource("ui/screens/ProfileEditorScreen.kt").readNormalizedText()
        val mainActivity = mainSource("MainActivity.kt").readNormalizedText()
        val scanner = mainSource("ui/scanner/QrScannerActivity.kt").readNormalizedText()

        assertTrue(fullScreenPage.contains("WindowCompat.setDecorFitsSystemWindows(window, false)"))
        assertTrue(fullScreenPage.contains("window.navigationBarColor = android.graphics.Color.TRANSPARENT"))
        assertTrue(fullScreenPage.contains("window.isNavigationBarContrastEnforced = false"))
        assertTrue(mainActivity.contains("window.isNavigationBarContrastEnforced = false"))
        assertTrue(fullScreenPage.contains("fun FloatingPageHeader("))
        assertTrue(fullScreenPage.contains(".statusBarsPadding()"))
        assertFalse(fullScreenPage.contains("TopAppBar("))
        assertFalse(fullScreenPage.contains(".navigationBarsPadding()"))
        assertTrue(fullScreenPage.contains("content(contentTopPadding)"))
        assertTrue(fullScreenPage.contains("shape = RoundedCornerShape(percent = 50)"))
        assertFalse(fullScreenPage.contains(".padding(top = contentTopPadding)"))
        val floatingLayers = fullScreenPage.extractFunctionBody("FloatingPageLayers")
        val contentLayer = floatingLayers.indexOf("content(contentTopPadding)")
        val fadeLayer = floatingLayers.indexOf("FloatingPageTopGradient(")
        val headerLayer = floatingLayers.indexOf("FloatingPageHeader(")
        assertTrue("内容层必须位于顶部渐变层下方", contentLayer in 0 until fadeLayer)
        assertTrue("顶部渐变层必须位于悬浮按钮下方", fadeLayer in 0 until headerLayer)
        assertTrue(floatingLayers.contains("height = contentTopPadding + 32.dp"))
        val topGradient = fullScreenPage.extractFunctionBody("FloatingPageTopGradient")
        assertTrue(topGradient.contains("MaterialTheme.colorScheme.background"))
        assertTrue(topGradient.contains("Brush.verticalGradient("))
        assertTrue(topGradient.contains(".fillMaxSize()"))
        assertTrue(topGradient.contains("1f to background.copy(alpha = 0f)"))
        assertTrue(topGradient.contains("endY = endY"))
        listOf("HazeProgressive", "hazeEffect", "hazeSource", "rememberHazeState").forEach { token ->
            assertFalse("顶部渐变不应包含模糊实现: $token", fullScreenPage.contains(token))
        }
        val floatingHeader = fullScreenPage.extractFunctionBody("FloatingPageHeader")
        assertTrue("标题必须受左右组件共同约束", floatingHeader.contains("Modifier.weight(1f)"))
        assertTrue("多操作按钮必须使用胶囊", floatingHeader.contains("RoundedCornerShape(percent = 50)"))
        assertFalse("顶部操作区不应使用圆角矩形", floatingHeader.contains("RoundedCornerShape(16.dp)"))
        assertFalse("顶部组件不应再使用相互覆盖的绝对定位", floatingHeader.contains("BoxWithConstraints("))
        assertFalse(
            floatingHeader
                .contains(".liquidGlassIconButtonPanel(\n                    shape = RoundedCornerShape(18.dp)")
        )

        assertTrue(nodeAdd.contains("FloatingPageLayout("))
        assertFalse(nodeAdd.contains("NodeAddTopBar("))
        assertTrue(nodeAdd.contains("actions = {"))
        assertTrue(nodeAdd.contains("text = stringResource(R.string.common_add)"))
        assertFalse(nodeAdd.contains("bottomBar = {"))
        assertTrue(nodeSelection.contains("FullScreenDialogPage("))
        assertTrue(scanner.contains("enableEdgeToEdge("))

        listOf(
            "AppRoutingScreen.kt",
            "ConnectionInfoScreen.kt",
            "ConnectionSettingsScreen.kt",
            "CustomRulesScreen.kt",
            "DiagnosticsScreen.kt",
            "DnsSettingsScreen.kt",
            "DomainRulesScreen.kt",
            "LogsScreen.kt",
            "NodeDetailScreen.kt",
            "ProfileEditorScreen.kt",
            "RoutingSettingsScreen.kt",
            "RuleSetHubScreen.kt",
            "RuleSetsScreen.kt",
            "TrafficStatsScreen.kt",
            "TunSettingsScreen.kt"
        ).forEach { fileName ->
            val source = mainSource("ui/screens/$fileName").readNormalizedText()
            assertTrue("$fileName 未使用覆盖式悬浮头部", source.contains("FloatingPageLayout("))
            assertFalse("$fileName 仍包含 TopAppBar", source.contains("TopAppBar("))
            assertFalse("$fileName 仍通过 Scaffold 预留顶栏", source.contains("topBar ="))
        }

        assertTrue(appSelector.contains("ExpandableSearchBar("))
        assertTrue(appSelector.contains("text = confirmText"))
        assertTrue(appSelector.contains("modifier = Modifier.fillMaxSize()"))
        assertTrue(appSelector.contains(") { headerPadding ->"))
        assertFalse(appSelector.extractFunctionBody("appSelectItemPanel").contains("liquidGlassPanel("))

        val profileEditorContent = profileEditor.extractFunctionBody("ProfileEditorContent")
        val scrollModifier = profileEditorContent.indexOf(".verticalScroll(rememberScrollState())")
        val topContentPadding = profileEditorContent.indexOf("top = contentTopPadding")
        assertTrue("配置编辑内容必须能滚入悬浮头部下方", scrollModifier >= 0)
        assertTrue("配置编辑器顶部留白必须属于可滚动内容", scrollModifier < topContentPadding)
    }

    @Test
    fun topSearchControlsOnlyAppearAtTheAbsoluteListTop() {
        val nodes = mainSource("ui/screens/NodesScreen.kt").readNormalizedText()
        val nodeSearch = nodes.extractFunctionBody("NodeSearchBar")
        val appSelector = mainSource("ui/components/AppMultiSelectDialog.kt").readNormalizedText()
        val appControls = appSelector.extractFunctionBody("AppSelectorTopControls")
        val routingComponents = mainSource("ui/screens/AppRoutingComponents.kt").readNormalizedText()
        val routingAppSelector = routingComponents.extractFunctionBody("MultiAppSelectorDialog")
        val sharedSearch = mainSource("ui/components/ExpandableSearchBar.kt").readNormalizedText()
        val topOnlyContent = mainSource("ui/components/FullScreenDialogPage.kt")
            .readNormalizedText()
            .extractFunctionBody("TopOnlySupportingContent")
        val chips = mainSource("ui/theme/LiquidGlassChipControls.kt").readNormalizedText()

        assertTrue(nodes.contains("derivedStateOf { !gridState.canScrollBackward }"))
        assertTrue(nodes.contains("TopOnlySupportingContent(visible = showTopControls)"))
        assertTrue(nodeSearch.contains("ExpandableSearchBar("))

        assertTrue(appSelector.contains("derivedStateOf { !listState.canScrollBackward }"))
        assertTrue(appSelector.contains("TopOnlySupportingContent(visible = showTopControls)"))
        assertTrue(appSelector.contains("supportingContentHeight = 92.dp"))
        assertTrue(appControls.contains("ExpandableSearchBar("))
        assertTrue(appControls.countOccurrences("LiquidGlassFilterChip(") >= 3)
        assertTrue(appControls.contains("liquidGlassIconButtonPanel("))
        assertFalse(appControls.contains("RoundedCornerShape("))
        assertFalse(appSelector.contains("OutlinedTextField("))
        assertFalse(appSelector.contains("appSelectFilterPanel("))

        assertTrue(routingAppSelector.contains("derivedStateOf { !listState.canScrollBackward }"))
        assertTrue(routingAppSelector.contains("TopOnlySupportingContent(visible = showTopControls)"))
        assertTrue(routingAppSelector.contains("supportingContentHeight = 64.dp"))
        assertTrue(routingAppSelector.contains("ExpandableSearchBar("))
        assertTrue(routingAppSelector.contains("LiquidGlassFilterChip("))
        assertFalse(routingAppSelector.contains("SelectedPulseIndicator("))
        assertFalse(routingAppSelector.contains("OutlinedTextField("))
        assertFalse(routingAppSelector.contains("multi_app_selector_filters"))

        assertTrue(sharedSearch.contains("RoundedCornerShape(percent = 50)"))
        assertTrue(sharedSearch.contains(".size(40.dp)"))
        assertTrue(sharedSearch.contains("liquidGlassIconButtonPanel(selected = false)"))
        assertTrue(topOnlyContent.contains("transition.animateFloat("))
        assertTrue(topOnlyContent.contains("EnterTransition.None"))
        assertTrue(topOnlyContent.contains("ExitTransition.None"))
        assertTrue(topOnlyContent.contains("CompositingStrategy.ModulateAlpha"))
        assertFalse(topOnlyContent.contains("fadeIn("))
        assertFalse(topOnlyContent.contains("fadeOut("))
        assertTrue(chips.contains("shape = shape"))
        assertTrue(chips.contains("showSelectionIndicator: Boolean = true"))
        assertTrue(chips.contains("FilterSelectionIndicator(selected)"))
        assertTrue(chips.contains("leadingIcon = selectionIndicator"))
        assertTrue(appControls.contains("selected = quickSelectSelected"))
        assertFalse(appControls.contains("showSelectionIndicator = false"))
        assertTrue(appSelector.contains("toggleQuickSelectionPreset("))
        assertTrue(appSelector.contains("selectionBeforeQuickSelect = null"))
        assertTrue(appSelector.contains("updateAppSelection(app.packageName, !checked)"))
        assertTrue(appSelector.contains("updateAppSelection(app.packageName, it)"))
    }

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
    fun customProfileUpdateReturnsAnImmediateToast() {
        val profiles = mainSource("viewmodel/ProfilesViewModel.kt").readNormalizedText()
        val updateStart = profiles.indexOf("fun updateProfile(profileId: String)")
        assertTrue(updateStart >= 0)
        val customGuard = profiles.indexOf("ProfileType.Custom", startIndex = updateStart)
        val updateLaunch = profiles.indexOf("viewModelScope.launch", startIndex = updateStart)

        assertTrue("自定义配置判断必须发生在更新协程启动前", customGuard in updateStart until updateLaunch)
        assertTrue(profiles.contains("R.string.profiles_custom_no_update"))
        assertTrue(File("src/main/res/values/strings.xml").readNormalizedText().contains("自定义配置无需更新"))
        assertTrue(
            File("src/main/res/values-en/strings.xml")
                .readNormalizedText()
                .contains("Custom profiles do not require updates")
        )
    }

    @Test
    fun customConfigRequiresNodesAndSupportsBothAddPaths() {
        val dialogs = mainSource("ui/screens/ProfilesScreenDialogs.kt").readNormalizedText()
        val customConfigPage = dialogs.extractFunctionBody("CustomConfigPage")
        assertTrue(customConfigPage.contains("onPasteNodeLink"))
        assertTrue(customConfigPage.contains("onManualAddNode"))
        assertTrue(customConfigPage.contains("addedNodes.isNotEmpty()"))
        assertTrue(customConfigPage.contains("selectedNodeIds.isNotEmpty()"))

        val viewModel = mainSource("viewmodel/ProfilesViewModel.kt")
            .readNormalizedText()
            .extractFunctionBody("createCustomConfig")
        assertTrue(viewModel.contains("selectedNodeIds.isEmpty()"))
        assertTrue(viewModel.contains("customDraftOutbounds"))
        assertTrue(viewModel.contains("R.string.custom_profile_name_required"))

        val repository = mainSource("repository/ConfigRepository.kt")
            .readNormalizedText()
            .extractFunctionBody("createCustomProfile")
        assertFalse(repository.contains("createEmptyProfile"))
        assertTrue(repository.contains("additionalOutbounds"))
        assertTrue(repository.contains("if (nodes.isEmpty())"))

        val repositorySource = mainSource("repository/ConfigRepository.kt").readNormalizedText()
        val outboundResolver = repositorySource.extractFunctionBody("resolveCustomProfileOutbounds")
        assertTrue(outboundResolver.contains("additionalOutbounds"))
        val outboundCombiner = repositorySource.extractFunctionBody("combineCustomProfileOutbounds")
        assertTrue(outboundCombiner.contains("Outbound(type = \"direct\", tag = \"direct\")"))

        val navigation = mainSource("ui/navigation/AppNavigation.kt").readNormalizedText()
        assertTrue(navigation.contains("CustomProfileNodeProtocolSelect"))
        assertTrue(navigation.contains("onCreateDraft"))
    }

    @Test
    fun customManualNodeNavigationHidesTheDialogAndRemovesTheProtocolBackEntry() {
        val profiles = mainSource("ui/screens/ProfilesScreen.kt").readNormalizedText()
        assertTrue(profiles.contains("currentBackStackEntryAsState()"))
        assertTrue(profiles.contains("showCustomConfigPage && currentRoute == Screen.Profiles.route"))
        assertTrue(profiles.contains("name = customProfileName"))
        assertTrue(profiles.contains("selectedNodeIds = customSelectedNodeIds"))

        val customPage = mainSource("ui/screens/ProfilesScreenDialogs.kt")
            .readNormalizedText()
            .extractFunctionBody("CustomConfigPage")
        assertFalse(customPage.contains("var name by rememberSaveable"))
        assertFalse(customPage.contains("var selectedNodeIds by rememberSaveable"))

        val navigation = mainSource("ui/navigation/AppNavigation.kt").readNormalizedText()
        assertTrue(navigation.contains("popUpTo(Screen.CustomProfileNodeProtocolSelect.route)"))
        assertTrue(navigation.contains("inclusive = true"))
    }

    @Test
    fun profileImportOptionsUseUniformCompactCards() {
        val dialogs = mainSource("ui/screens/ProfilesScreenDialogs.kt").readNormalizedText()
        val importOptionCard = dialogs.extractFunctionBody("ImportOptionCard")

        assertTrue(importOptionCard.contains(".height(88.dp)"))
        assertTrue(importOptionCard.contains("Column(modifier = Modifier.weight(1f))"))
        assertEquals(2, Regex("""maxLines\s*=\s*1""").findAll(importOptionCard).count())
    }

    @Test
    fun userDestructiveActionsRequireConfirmation() {
        val nodes = mainSource("ui/screens/NodesScreen.kt").readNormalizedText()
        assertTrue(nodes.contains("nodeToDelete"))
        assertTrue(nodes.contains("showClearLatencyConfirm"))
        assertTrue(nodes.countOccurrences("ConfirmDialog(") >= 2)
        assertFalse(nodes.contains("{ viewModel.deleteNode(node.id) }"))

        val profiles = mainSource("ui/screens/ProfilesScreen.kt").readNormalizedText()
        assertTrue(profiles.contains("profileToDelete"))
        assertTrue(profiles.contains("ConfirmDialog("))
        assertFalse(profiles.contains("onDelete = { viewModel.deleteProfile(profile.id) }"))

        val logs = mainSource("ui/screens/LogsScreen.kt").readNormalizedText()
        assertTrue(logs.contains("showClearLogsConfirm"))
        assertTrue(logs.contains("ConfirmDialog("))
        assertFalse(logs.contains("IconButton(onClick = { viewModel.clearLogs() })"))

        mapOf(
            "ui/screens/CustomRulesScreen.kt" to "showDeleteConfirm",
            "ui/screens/DomainRulesScreen.kt" to "showDeleteConfirm",
            "ui/screens/RuleSetsScreen.kt" to "showDeleteConfirmDialog",
            "ui/screens/RuleSetsDialogs.kt" to "showDeleteConfirm",
            "ui/screens/TrafficStatsScreen.kt" to "showClearDialog",
            "ui/screens/ConnectionInfoScreen.kt" to "showConfirmDeleteAll"
        ).forEach { (path, confirmationState) ->
            val source = mainSource(path).readNormalizedText()
            assertTrue("$path 缺少删除确认状态", source.contains(confirmationState))
        }

        val appRouting = mainSource("ui/screens/AppRoutingScreen.kt").readNormalizedText()
        assertTrue(appRouting.contains("showDeleteGroupConfirm"))
        assertTrue(appRouting.contains("showDeleteRuleConfirm"))
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

        val nodeDetailSource = mainSource("ui/screens/NodeDetailScreen.kt").readNormalizedText()
        assertTrue(nodeDetailSource.contains("NodePickerPage("))

        val pickerSource = mainSource("ui/screens/NodePickerPage.kt").readNormalizedText()
        val pickerPage = pickerSource.extractFunctionBody("NodePickerPage")
        assertTrue(pickerPage.contains("FullScreenDialogPage("))
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

    @Test
    fun nodeSwitchKeepsConfirmedSelectionAndShowsTargetCardProgress() {
        val viewModel = mainSource("viewmodel/NodesViewModel.kt").readNormalizedText()
        val switchBody = viewModel.extractFunctionBody("setActiveNode")
        assertTrue(viewModel.contains("val switchingNodeId: StateFlow<String?>"))
        assertTrue(switchBody.contains("_switchingNodeId.value = nodeId"))
        assertTrue(switchBody.contains("configRepository.setActiveNode(nodeId)"))
        assertTrue(switchBody.contains("finally"))
        assertTrue(switchBody.contains("_switchingNodeId.value = null"))

        val screen = mainSource("ui/screens/NodesScreen.kt").readNormalizedText()
        assertTrue(screen.contains("viewModel.switchingNodeId.collectAsStateWithLifecycle()"))
        assertTrue(screen.contains("val isSwitchingNode = switchingNodeId == node.id"))
        assertTrue(screen.contains("isSwitching = isSwitchingNode"))
        assertFalse(screen.contains("isTesting = isTestingNode || isSwitchingNode"))
        assertFalse(screen.contains("if (isNodeSwitching)"))

        val card = mainSource("ui/components/NodeCard.kt").readNormalizedText()
        assertTrue(card.contains("isSwitching: Boolean = false"))
        assertTrue(card.contains("if (isSwitching)"))
        assertFalse(switchBody.contains("_activeNodeId"))
    }

    private fun mainSource(path: String): File = File("src/main/java/com/kunk/singbox/$path")
}

private fun File.readNormalizedText(): String = readText().replace("\r\n", "\n")

private fun String.countOccurrences(token: String): Int = windowed(token.length).count { it == token }

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

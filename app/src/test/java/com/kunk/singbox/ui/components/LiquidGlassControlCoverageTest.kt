package com.kunk.singbox.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiquidGlassControlCoverageTest {

    @Test
    fun checkboxesUseLiquidGlassColors() {
        val liquidControls = liquidControlSources()
        val componentFiles = listOf("AppMultiSelectDialog.kt")
        val screenFiles = listOf(
            "AppRoutingComponents.kt",
            "ProfilesScreenDialogs.kt",
            "RuleSetsDialogs.kt",
            "RuleSetsScreen.kt"
        )

        assertTrue(liquidControls.contains("fun liquidGlassCheckboxColors("))
        componentFiles.assertComponentSourcesContain("liquidGlassCheckboxColors(")
        screenFiles.assertScreenSourcesContain("liquidGlassCheckboxColors(")
    }

    @Test
    fun iconButtonsUseLiquidGlassPanels() {
        val liquidControls = liquidControlSources()
        val componentFiles = listOf(
            "AppMultiSelectDialog.kt",
            "NodeCard.kt",
            "ProfileCard.kt"
        )
        val screenFiles = listOf(
            "AppGroupsScreen.kt",
            "AppRoutingScreen.kt",
            "AppRulesScreen.kt",
            "ConnectionInfoScreen.kt",
            "ConnectionSettingsScreen.kt",
            "CustomRulesScreen.kt",
            "DashboardScreen.kt",
            "DiagnosticsScreen.kt",
            "DnsSettingsScreen.kt",
            "DomainRulesScreen.kt",
            "LogsScreen.kt",
            "NodeDetailScreen.kt",
            "NodesScreen.kt",
            "ProfileEditorScreen.kt",
            "ProfilesScreen.kt",
            "RoutingSettingsScreen.kt",
            "RuleSetHubScreen.kt",
            "RuleSetsDialogs.kt",
            "RuleSetsScreen.kt",
            "TrafficStatsScreen.kt",
            "TunSettingsScreen.kt"
        )

        assertTrue(liquidControls.contains("fun Modifier.liquidGlassIconButtonPanel("))
        componentFiles.assertComponentSourcesContain("liquidGlassIconButtonPanel(")
        screenFiles.assertScreenSourcesContain("liquidGlassIconButtonPanel(")
    }

    @Test
    fun textButtonsUseLiquidGlassPanels() {
        val liquidControls = liquidControlSources()
        val componentFiles = listOf(
            "AddNodeDialog.kt",
            "CommonDialogs.kt",
            "ExportImportDialogs.kt",
            "NodeSelectionDialogs.kt",
            "SelectProfileDialog.kt"
        )
        val screenFiles = listOf(
            "AppRoutingComponents.kt",
            "ConnectionInfoScreen.kt",
            "CustomRulesScreen.kt",
            "DomainRulesScreen.kt",
            "NodeDetailDialogs.kt",
            "ProfilesScreenDialogs.kt",
            "RuleSetHubScreen.kt",
            "RuleSetsDialogs.kt",
            "RuleSetsScreen.kt"
        )

        assertTrue(liquidControls.contains("fun Modifier.liquidGlassTextButtonPanel("))
        componentFiles.assertComponentSourcesContain("liquidGlassTextButtonPanel(")
        screenFiles.assertScreenSourcesContain("liquidGlassTextButtonPanel(")
    }

    @Test
    fun progressIndicatorsUseLiquidGlassColors() {
        val liquidControls = liquidControlSources()
        val componentFiles = listOf(
            "AppListLoadingDialog.kt",
            "AppMultiSelectDialog.kt",
            "ExportImportDialogs.kt",
            "InfoCard.kt",
            "NodeCard.kt",
            "ProfileCard.kt"
        )
        val screenFiles = listOf(
            "AppGroupsScreen.kt",
            "NodesScreen.kt",
            "ProfilesScreenDialogs.kt",
            "RuleSetHubScreen.kt",
            "RuleSetsDialogs.kt",
            "SettingsScreen.kt",
            "TrafficStatsScreen.kt"
        )

        assertTrue(liquidControls.contains("fun liquidGlassProgressColor("))
        assertTrue(liquidControls.contains("fun liquidGlassProgressTrackColor("))
        componentFiles.assertComponentSourcesContain("liquidGlassProgressColor(")
        screenFiles.assertScreenSourcesContain("liquidGlassProgressColor(")
    }

    @Test
    fun dividersAndTabsUseLiquidGlassStyling() {
        val liquidControls = liquidControlSources()
        val exportImport = File("src/main/java/com/kunk/singbox/ui/components/ExportImportDialogs.kt")
            .readText()
        val appMultiSelect = File("src/main/java/com/kunk/singbox/ui/components/AppMultiSelectDialog.kt")
            .readText()
        val connectionInfo = File("src/main/java/com/kunk/singbox/ui/screens/ConnectionInfoScreen.kt")
            .readText()
        val appRouting = File("src/main/java/com/kunk/singbox/ui/screens/AppRoutingScreen.kt").readText()

        assertTrue(liquidControls.contains("fun liquidGlassDividerColor("))
        assertTrue(liquidControls.contains("fun Modifier.liquidGlassTabRowPanel("))
        assertTrue(liquidControls.contains("fun liquidGlassTabIndicatorColor("))
        assertTrue(exportImport.contains("liquidGlassDividerColor("))
        assertTrue(appMultiSelect.contains("liquidGlassDividerColor("))
        assertTrue(connectionInfo.contains("private fun connectionOverviewDividerBrush()"))
        assertTrue(connectionInfo.contains("private fun connectionOverviewLabelColor()"))
        assertTrue(connectionInfo.contains("private fun connectionOverviewValueColor()"))
        assertTrue(connectionInfo.contains("liquidGlassDividerColor("))
        assertTrue(appRouting.contains("liquidGlassTabRowPanel("))
        assertTrue(appRouting.contains("liquidGlassTabIndicatorColor("))
    }

    @Test
    fun dropdownMenusUseLiquidGlassWrapper() {
        val liquidControls = liquidControlSources()
        val componentFiles = listOf("NodeCard.kt", "ProfileCard.kt")
        val screenFiles = listOf("NodesScreen.kt", "RuleSetsDialogs.kt")

        assertTrue(liquidControls.contains("fun LiquidGlassDropdownMenu("))
        assertTrue(liquidControls.contains("fun liquidGlassDropdownMenuItemColors("))
        assertTrue(liquidControls.contains("containerColor = Color.Transparent"))
        componentFiles.assertComponentSourcesContain("LiquidGlassDropdownMenu(")
        componentFiles.assertComponentSourcesContain("liquidGlassDropdownMenuItemColors(")
        screenFiles.assertScreenSourcesContain("LiquidGlassDropdownMenu(")
        screenFiles.assertScreenSourcesContain("liquidGlassDropdownMenuItemColors(")
    }

    @Test
    fun dropdownMenuItemsUseLiquidGlassColors() {
        val uiDir = File("src/main/java/com/kunk/singbox/ui")
        val failures = uiDir.walkTopDown()
            .filter { file -> file.extension == "kt" }
            .flatMap { file -> dropdownMenuItemColorFailures(file, uiDir) }
            .toList()

        assertTrue("DropdownMenuItem should use liquid glass item colors: $failures", failures.isEmpty())
    }

    @Test
    fun screenScaffoldsUseLiquidGlassContainerColors() {
        val screenDir = File("src/main/java/com/kunk/singbox/ui/screens")
        val failures = mutableListOf<String>()

        screenDir.listFiles { file -> file.extension == "kt" }.orEmpty().forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (line.contains("Scaffold(")) {
                    val scaffoldHeader = lines.drop(index).take(14).joinToString("\n")
                    if (!scaffoldHeader.contains("containerColor = liquidGlass")) {
                        failures += "${file.name}:${index + 1}"
                    }
                }
            }
        }

        assertTrue("Scaffold should use liquid glass container colors: $failures", failures.isEmpty())
    }

    @Test
    fun emptyStatesUseLiquidGlassPanels() {
        val liquidControls = liquidControlSources()
        val componentFiles = listOf("NodeSelectionDialogs.kt")
        val screenFiles = listOf(
            "AppGroupsScreen.kt",
            "AppRoutingScreen.kt",
            "AppRulesScreen.kt",
            "CustomRulesScreen.kt",
            "DomainRulesScreen.kt",
            "RuleSetHubScreen.kt",
            "RuleSetsScreen.kt"
        )

        assertTrue(liquidControls.contains("fun Modifier.liquidGlassEmptyStatePanel("))
        componentFiles.assertComponentSourcesContain("liquidGlassEmptyStatePanel(")
        screenFiles.assertScreenSourcesContain("liquidGlassEmptyStatePanel(")
    }

    @Test
    fun loadingStatesUseLiquidGlassPanels() {
        val liquidControls = liquidControlSources()
        val screenFiles = listOf(
            "AppGroupsScreen.kt",
            "ProfileEditorScreen.kt",
            "RuleSetHubScreen.kt"
        )

        assertTrue(liquidControls.contains("fun Modifier.liquidGlassLoadingStatePanel("))
        screenFiles.assertScreenSourcesContain("liquidGlassLoadingStatePanel(")
    }

    @Test
    fun cardBasedSurfacesUseNamedLiquidGlassHelpers() {
        val connectionInfo = File("src/main/java/com/kunk/singbox/ui/screens/ConnectionInfoScreen.kt").readText()
        val ruleSetHub = File("src/main/java/com/kunk/singbox/ui/screens/RuleSetHubScreen.kt").readText()
        val exportImport = File("src/main/java/com/kunk/singbox/ui/components/ExportImportDialogs.kt")
            .readText()

        assertTrue(connectionInfo.contains("private fun Modifier.connectionOverviewPanel()"))
        assertTrue(connectionInfo.contains("private fun connectionItemContainerColor("))
        assertTrue(ruleSetHub.contains("private fun Modifier.ruleSetHubItemPanel("))
        assertTrue(ruleSetHub.contains("private fun ruleSetHubItemContainerColor("))
        assertTrue(exportImport.contains("private fun exportImportCardContainerColor("))
    }

    @Test
    fun qrScannerNativeControlsUseLiquidGlassButtonsOnlyForLiquidTheme() {
        val scannerActivity = File("src/main/java/com/kunk/singbox/ui/scanner/QrScannerActivity.kt").readText()
        val scannerLayout = File("src/main/res/layout/activity_qr_scanner.xml").readText()

        assertTrue(scannerActivity.contains("SettingsRepository.getInstance(this).settings.value.appThemeStyle"))
        assertTrue(scannerActivity.contains("AppThemeStyle.LIQUID_GLASS"))
        assertTrue(scannerActivity.contains("private fun applyLiquidGlassScannerControls()"))
        assertTrue(scannerActivity.contains("private fun applyLiquidGlassScannerLabels()"))
        assertTrue(scannerActivity.contains("private fun liquidGlassScannerButtonBackground()"))
        assertTrue(scannerActivity.contains("private fun liquidGlassScannerLabelBackground()"))
        assertTrue(scannerActivity.contains("StateListDrawable()"))
        assertTrue(scannerActivity.contains("GradientDrawable()"))
        assertTrue(scannerLayout.contains("@+id/txt_scanner_title"))
        assertTrue(scannerLayout.contains("@+id/txt_scanner_hint"))
        assertTrue(scannerLayout.contains("?android:attr/selectableItemBackgroundBorderless"))
    }

    @Test
    fun qrScannerViewFinderUsesLiquidGlassFrameColorsOnlyForLiquidTheme() {
        val viewFinder = File("src/main/java/com/kunk/singbox/ui/scanner/SquareViewFinderView.kt").readText()

        assertTrue(viewFinder.contains("SettingsRepository.getInstance(context).settings.value.appThemeStyle"))
        assertTrue(viewFinder.contains("AppThemeStyle.LIQUID_GLASS"))
        assertTrue(viewFinder.contains("private fun scannerFrameColor("))
        assertTrue(viewFinder.contains("private fun scannerMaskColor("))
        assertTrue(viewFinder.contains("ContextCompat.getColor(context, defaultColorRes)"))
        assertTrue(viewFinder.contains("Color.argb("))
    }

    @Test
    fun appNotificationsUseLiquidGlassToastOnlyForLiquidTheme() {
        val notificationManager = File("src/main/java/com/kunk/singbox/ui/components/AppNotificationManager.kt")
            .readText()

        assertTrue(notificationManager.contains("SettingsRepository.getInstance(context).settings.value.appThemeStyle"))
        assertTrue(notificationManager.contains("AppThemeStyle.LIQUID_GLASS"))
        assertTrue(notificationManager.contains("private fun showLiquidGlassToast("))
        assertTrue(notificationManager.contains("private fun liquidGlassToastBackground("))
        assertTrue(notificationManager.contains("Toast.makeText("))
    }

    @Test
    fun liquidGlassHelpersPreserveDefaultThemeBranches() {
        val liquidTheme = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassTheme.kt").readText()
        val controls = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassControls.kt").readText()
        val menuControls = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassMenuControls.kt").readText()
        val selections = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassSelectionControls.kt")
            .readText()

        assertTrue(liquidTheme.contains("val LocalAppThemeStyle = staticCompositionLocalOf { AppThemeStyle.DEFAULT }"))
        assertTrue(liquidTheme.contains("if (!isLiquidGlassTheme()) {\n        return this"))
        assertTrue(liquidTheme.contains("fun liquidGlassDialogContainerColor(): Color"))
        assertTrue(liquidTheme.contains("MaterialTheme.colorScheme.surface"))
        assertTrue(liquidTheme.contains("fun liquidGlassTextFieldContainerColor(defaultColor: Color): Color"))
        assertTrue(liquidTheme.contains("fun liquidGlassTextFieldBorderColor(defaultColor: Color): Color"))
        assertTrue(controls.contains("fun liquidGlassOutlinedButtonBorder(defaultBorder: BorderStroke): BorderStroke"))
        assertTrue(controls.contains("else {\n        defaultBorder"))
        assertTrue(controls.contains("private fun liquidGlassTransparentContainerColor(defaultColor: Color): Color"))
        assertTrue(controls.contains("else {\n        defaultColor"))
        assertTrue(controls.contains("private fun liquidGlassPrimaryContentColor(defaultColor: Color): Color"))
        assertTrue(menuControls.contains("else {\n        DropdownMenu("))
        assertTrue(selections.contains("unselectedColor = if (isLiquidGlassTheme())"))
        assertTrue(
            selections.contains(
                "checkedThumbColor = if (isLiquidGlassTheme()) checkedTrackColor else checkedThumbColor"
            )
        )
        assertTrue(
            selections.contains(
                "uncheckedBorderColor = if (isLiquidGlassTheme()) Color.Transparent else uncheckedBorderColor"
            )
        )
        assertTrue(selections.contains("fun liquidGlassProgressColor(defaultColor: Color): Color"))
        assertTrue(selections.contains("fun liquidGlassDividerColor(defaultColor: Color): Color"))
        assertTrue(selections.contains("fun liquidGlassTabIndicatorColor(defaultColor: Color): Color"))
    }

    @Test
    fun dialogSurfacesUseLiquidGlassPanelWrappers() {
        val uiDir = File("src/main/java/com/kunk/singbox/ui")
        val allowedDialogSurfaceMarkers = listOf(
            "liquidGlassDialogPanel",
            "nodeSelectionDialogPanel",
            "profileDialogPanel",
            "nodeDetailDialogPanel",
            "ExportImportCard",
            "loadingDialogPanel",
            "ConfirmDialog",
            "InputDialog",
            "SingleSelectDialog",
            "SelectProfileDialog",
            "AddNodeDialog",
            "AppGroupEditorDialog",
            "AppRuleEditorDialog",
            "RuleSetEditorDialog",
            "DomainRuleEditorDialog",
            "CustomRuleEditorDialog",
            "AppMultiSelectDialog"
        )
        val failures = uiDir.walkTopDown()
            .filter { file -> file.extension == "kt" }
            .filter { file ->
                val source = file.readText()
                source.contains("Dialog(") || source.contains("AlertDialog(")
            }
            .filterNot { file ->
                val source = file.readText()
                allowedDialogSurfaceMarkers.any(source::contains)
            }
            .map { file -> file.relativeTo(uiDir).invariantSeparatorsPath }
            .toList()

        assertTrue("Dialog surfaces should use liquid glass wrappers: $failures", failures.isEmpty())
    }

    @Test
    fun directDialogBlocksUseNearbyLiquidGlassPanels() {
        val uiDir = File("src/main/java/com/kunk/singbox/ui")
        val failures = uiDir.walkTopDown()
            .filter { file -> file.extension == "kt" }
            .flatMap { file -> directDialogPanelFailures(file, uiDir) }
            .toList()

        assertTrue("Direct Dialog blocks should wrap content with liquid glass panels: $failures", failures.isEmpty())
    }

    @Test
    fun toastEntrypointsStayBehindLiquidGlassNotificationManager() {
        val mainDir = File("src/main/java/com/kunk/singbox")
        val directToastFiles = mainDir.walkTopDown()
            .filter { file -> file.extension == "kt" }
            .filter { file ->
                val source = file.readText()
                source.contains("Toast.makeText(") || source.contains("Toast(context)")
            }
            .map { file -> file.name }
            .distinct()
            .toList()

        assertEquals(listOf("AppNotificationManager.kt"), directToastFiles)
    }

    @Test
    fun materialControlsStayWithinLiquidGlassAwareWrappers() {
        val uiDir = File("src/main/java/com/kunk/singbox/ui")
        val failures = findMaterialControlsWithoutLiquidGlassMarkers(uiDir)

        assertTrue("Material controls should use liquid glass aware styling: $failures", failures.isEmpty())
    }

    @Test
    fun surfaceContainersStayWithinLiquidGlassAwareWrappers() {
        val uiDir = File("src/main/java/com/kunk/singbox/ui")
        val failures = findSurfaceContainersWithoutLiquidGlassMarkers(uiDir)

        assertTrue("Surface containers should use liquid glass aware styling: $failures", failures.isEmpty())
    }

    @Test
    fun nativeXmlEntrypointsKeepLiquidGlassReadySurfaces() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val scannerLayout = File("src/main/res/layout/activity_qr_scanner.xml").readText()
        val scannerContent = File("src/main/res/layout/custom_barcode_scanner.xml").readText()
        val shortcutLayout = File("src/main/res/layout/activity_none.xml").readText()
        val themes = File("src/main/res/values/themes.xml").readText()

        assertTrue(manifest.contains("android:name=\".ui.scanner.QrScannerActivity\""))
        assertTrue(manifest.contains("android:theme=\"@style/Theme.AppCompat.NoActionBar\""))
        assertTrue(manifest.contains("android:name=\".ui.ShortcutActivity\""))
        assertTrue(manifest.contains("android:theme=\"@style/Theme.SingBoxAndroid.Transparent\""))
        assertTrue(scannerLayout.contains("app:zxing_scanner_layout=\"@layout/custom_barcode_scanner\""))
        assertTrue(scannerLayout.contains("@+id/btn_back"))
        assertTrue(scannerLayout.contains("@+id/btn_gallery"))
        assertTrue(scannerLayout.contains("@+id/btn_flash"))
        assertTrue(scannerLayout.contains("@+id/txt_scanner_title"))
        assertTrue(scannerLayout.contains("@+id/txt_scanner_hint"))
        assertTrue(scannerLayout.contains("?android:attr/selectableItemBackgroundBorderless"))
        assertTrue(scannerContent.contains("com.kunk.singbox.ui.scanner.SquareViewFinderView"))
        assertTrue(scannerContent.contains("android:id=\"@+id/zxing_viewfinder_view\""))
        assertTrue(themes.contains("<style name=\"Theme.SingBoxAndroid.Transparent\""))
        assertTrue(themes.contains("<item name=\"android:windowIsTranslucent\">true</item>"))
        assertTrue(themes.contains("<item name=\"android:windowBackground\">@android:color/transparent</item>"))
        assertTrue(shortcutLayout.contains("android:layout_width=\"0dp\""))
        assertTrue(shortcutLayout.contains("android:layout_height=\"0dp\""))
    }

    private fun liquidControlSources(): String {
        val controls = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassControls.kt").readText()
        val menus = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassMenuControls.kt").readText()
        val selections = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassSelectionControls.kt")
            .readText()
        return controls + menus + selections
    }

    private fun findMaterialControlsWithoutLiquidGlassMarkers(uiDir: File): List<String> {
        val controls = listOf(
            "Button(",
            "OutlinedButton(",
            "TextButton(",
            "IconButton(",
            "FloatingActionButton(",
            "SmallFloatingActionButton(",
            "Switch(",
            "Checkbox(",
            "RadioButton(",
            "OutlinedTextField(",
            "BasicTextField(",
            "DropdownMenu(",
            "FilterChip(",
            "TabRow(",
            "LinearProgressIndicator(",
            "CircularProgressIndicator(",
            "HorizontalDivider(",
            "Divider("
        )
        return uiDir.walkTopDown()
            .filter { file -> file.extension == "kt" }
            .flatMap { file -> materialControlFailures(file, uiDir, controls) }
            .toList()
    }

    private fun findSurfaceContainersWithoutLiquidGlassMarkers(uiDir: File): List<String> {
        val surfaces = listOf(
            "Card(",
            "Surface(",
            ".background(",
            ".border(",
            "BorderStroke("
        )
        return uiDir.walkTopDown()
            .filter { file -> file.extension == "kt" }
            .flatMap { file -> surfaceContainerFailures(file, uiDir, surfaces) }
            .toList()
    }

    private fun materialControlFailures(
        file: File,
        uiDir: File,
        controls: List<String>
    ): Sequence<String> {
        val lines = file.readLines()
        return lines.asSequence()
            .mapIndexedNotNull { index, line ->
                if (controls.none(line::contains)) return@mapIndexedNotNull null
                val context = lines.contextAround(index)
                if (liquidGlassAwareControlMarkers.any(context::contains)) return@mapIndexedNotNull null
                "${file.relativeTo(uiDir).invariantSeparatorsPath}:${index + 1}:${line.trim()}"
            }
    }

    private fun surfaceContainerFailures(
        file: File,
        uiDir: File,
        surfaces: List<String>
    ): Sequence<String> {
        val lines = file.readLines()
        return lines.asSequence()
            .mapIndexedNotNull { index, line ->
                if (surfaces.none(line::contains)) return@mapIndexedNotNull null
                val context = lines.contextAround(index)
                if (liquidGlassAwareSurfaceMarkers.any(context::contains)) return@mapIndexedNotNull null
                "${file.relativeTo(uiDir).invariantSeparatorsPath}:${index + 1}:${line.trim()}"
            }
    }

    private fun directDialogPanelFailures(
        file: File,
        uiDir: File
    ): Sequence<String> {
        val lines = file.readLines()
        return lines.asSequence()
            .mapIndexedNotNull { index, line ->
                val trimmed = line.trim()
                val isDirectDialog = trimmed.startsWith("Dialog(") ||
                    trimmed.startsWith("androidx.compose.ui.window.Dialog(")
                if (!isDirectDialog || trimmed.startsWith("AlertDialog(")) return@mapIndexedNotNull null
                val context = lines.contextAround(index)
                if (directDialogLiquidGlassMarkers.any(context::contains)) return@mapIndexedNotNull null
                val lineNumber = index + 1
                "${file.relativeTo(uiDir).invariantSeparatorsPath}:$lineNumber:$trimmed"
            }
    }

    private fun List<String>.contextAround(index: Int): String {
        val start = (index - CONTROL_CONTEXT_BEFORE).coerceAtLeast(0)
        val end = (index + CONTROL_CONTEXT_AFTER).coerceAtMost(lastIndex)
        return subList(start, end + 1).joinToString("\n")
    }

    private fun dropdownMenuItemColorFailures(
        file: File,
        uiDir: File
    ): Sequence<String> {
        val lines = file.readLines()
        return lines.asSequence()
            .mapIndexedNotNull { index, line ->
                if (!line.contains("DropdownMenuItem(")) return@mapIndexedNotNull null
                val context = lines.contextAround(index)
                if (context.contains("colors = liquidGlassDropdownMenuItemColors()")) return@mapIndexedNotNull null
                "${file.relativeTo(uiDir).invariantSeparatorsPath}:${index + 1}:${line.trim()}"
            }
    }

    private fun List<String>.assertComponentSourcesContain(pattern: String) {
        forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/components/$fileName").readText()
            assertTrue("$fileName should contain $pattern", source.contains(pattern))
        }
    }

    private fun List<String>.assertScreenSourcesContain(pattern: String) {
        forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/screens/$fileName").readText()
            assertTrue("$fileName should contain $pattern", source.contains(pattern))
        }
    }

    private companion object {
        const val CONTROL_CONTEXT_BEFORE = 32
        const val CONTROL_CONTEXT_AFTER = 80

        val liquidGlassAwareControlMarkers = listOf(
            "liquidGlass",
            "LiquidGlass",
            "DefaultAppNavBar",
            "DefaultNavItem",
            "DefaultNavDivider",
            "NavigationBarItem",
            "StandardCard",
            "InfoCard",
            "NodeCard",
            "NodeGridCard",
            "ProfileCard",
            "SettingItem",
            "StyledTextField",
            "ConfirmDialog",
            "InputDialog",
            "SingleSelectDialog",
            "ExportImportCard",
            "TargetOptionPanel",
            "QuickActionButton",
            "LogCategoryChip",
            "RuleSetBadge",
            "ImportOptionCard",
            "AppGroupCard",
            "appRuleDeleteButton",
            "appRuleEnabledSwitch",
            "nodeSearchPanel",
            "connectionSearchPanel",
            "profileEditorPanel"
        )

        val directDialogLiquidGlassMarkers = listOf(
            "liquidGlassDialogPanel",
            "liquidGlassPanel",
            "dialogPanel(",
            "nodeSelectionDialogPanel",
            "profileDialogPanel",
            "profileDnsMenuPanel",
            "nodeDetailDialogPanel",
            "loadingDialogPanel",
            "appSelectDialogPanel",
            "ExportImportCard",
            "ImportOptionCard"
        )

        val liquidGlassAwareSurfaceMarkers = liquidGlassAwareControlMarkers + listOf(
            "isLiquidGlassTheme",
            "AppBackground",
            "rootContainerColor",
            "selectedIndicator",
            "connectionItemContainerColor",
            "connectionOverviewPanel",
            "ruleSetHubItemPanel",
            "ruleSetHubItemContainerColor",
            "nodesMenuPanel",
            "profileOverflowMenuPanel",
            "nodeOverflowMenuPanel",
            "loadingDialogPanel",
            "trafficStatIconPanel",
            "trafficRankPanel",
            "trafficLegendMarkerPanel",
            "routingIconPanel",
            "routingStatusBadgePanel",
            "routingSelectablePanel",
            "RuleSetBadge",
            "modeChipIndicatorPanel"
        )
    }
}

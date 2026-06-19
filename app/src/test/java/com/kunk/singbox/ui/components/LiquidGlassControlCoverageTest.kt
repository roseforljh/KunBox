package com.kunk.singbox.ui.components

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
        assertTrue(liquidControls.contains("containerColor = Color.Transparent"))
        componentFiles.assertComponentSourcesContain("LiquidGlassDropdownMenu(")
        screenFiles.assertScreenSourcesContain("LiquidGlassDropdownMenu(")
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
        val screenFiles = listOf(
            "AppGroupsScreen.kt",
            "AppRoutingScreen.kt",
            "AppRulesScreen.kt",
            "CustomRulesScreen.kt",
            "DomainRulesScreen.kt",
            "RuleSetsScreen.kt"
        )

        assertTrue(liquidControls.contains("fun Modifier.liquidGlassEmptyStatePanel("))
        screenFiles.assertScreenSourcesContain("liquidGlassEmptyStatePanel(")
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
        assertTrue(scannerActivity.contains("private fun liquidGlassScannerButtonBackground()"))
        assertTrue(scannerActivity.contains("StateListDrawable()"))
        assertTrue(scannerActivity.contains("GradientDrawable()"))
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

    private fun liquidControlSources(): String {
        val controls = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassControls.kt").readText()
        val selections = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassSelectionControls.kt")
            .readText()
        return controls + selections
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
}

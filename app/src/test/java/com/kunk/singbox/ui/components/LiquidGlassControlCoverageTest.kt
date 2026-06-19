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
        val appRouting = File("src/main/java/com/kunk/singbox/ui/screens/AppRoutingScreen.kt").readText()

        assertTrue(liquidControls.contains("fun liquidGlassDividerColor("))
        assertTrue(liquidControls.contains("fun Modifier.liquidGlassTabRowPanel("))
        assertTrue(liquidControls.contains("fun liquidGlassTabIndicatorColor("))
        assertTrue(exportImport.contains("liquidGlassDividerColor("))
        assertTrue(appRouting.contains("liquidGlassTabRowPanel("))
        assertTrue(appRouting.contains("liquidGlassTabIndicatorColor("))
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

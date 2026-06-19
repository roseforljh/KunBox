package com.kunk.singbox.ui.components

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class ThemeStyleSourceTest {

    @Test
    fun appSettingsDefinesThemeStyleWithDefaultAndLiquidGlassOptions() {
        val source = File("src/main/java/com/kunk/singbox/model/Settings.kt").readText()

        assertTrue(
            source.contains(
                "@SerializedName(\"appThemeStyle\") val appThemeStyle: AppThemeStyle = AppThemeStyle.DEFAULT"
            )
        )
        assertTrue(source.contains("enum class AppThemeStyle"))
        assertTrue(source.contains("@SerializedName(\"DEFAULT\") DEFAULT"))
        assertTrue(source.contains("@SerializedName(\"LIQUID_GLASS\") LIQUID_GLASS"))
    }

    @Test
    fun settingsScreenExposesThemeStylePickerInGeneralSection() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/SettingsScreen.kt").readText()

        assertTrue(source.contains("showThemeStyleDialog"))
        assertTrue(source.contains("settings_theme_style"))
        assertTrue(source.contains("AppThemeStyle.entries.map"))
        assertTrue(source.contains("viewModel.setAppThemeStyle"))
    }

    @Test
    fun appNavBarKeepsDefaultRendererAndAddsLiquidGlassRenderer() {
        val source = File("src/main/java/com/kunk/singbox/ui/components/AppNavBar.kt").readText()

        assertTrue(source.contains("themeStyle: AppThemeStyle = AppThemeStyle.DEFAULT"))
        assertTrue(source.contains("AppThemeStyle.DEFAULT -> DefaultAppNavBar"))
        assertTrue(source.contains("AppThemeStyle.LIQUID_GLASS -> LiquidGlassAppNavBar"))
        assertTrue(source.contains("NavigationBar("))
        assertTrue(source.contains("RoundedCornerShape(percent = 50)"))
        assertTrue(source.contains("Brush.verticalGradient"))
    }

    @Test
    fun appThemeStyleIsProvidedThroughThemeCompositionLocal() {
        val theme = File("src/main/java/com/kunk/singbox/ui/theme/Theme.kt").readText()
        val liquidTheme = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassTheme.kt").readText()
        val main = File("src/main/java/com/kunk/singbox/MainActivity.kt").readText()

        assertTrue(theme.contains("appThemeStyle: AppThemeStyle = AppThemeStyle.DEFAULT"))
        assertTrue(theme.contains("CompositionLocalProvider(LocalAppThemeStyle provides appThemeStyle)"))
        assertTrue(liquidTheme.contains("val LocalAppThemeStyle"))
        assertTrue(liquidTheme.contains("fun isLiquidGlassTheme()"))
        assertTrue(liquidTheme.contains("fun Modifier.liquidGlassPanel("))
        assertTrue(main.contains("SingBoxTheme(appTheme = appTheme, appThemeStyle = appThemeStyle)"))
    }

    @Test
    fun commonComponentsUseLiquidGlassStyleOnlyFromSharedThemeState() {
        val componentFiles = listOf(
            "StandardCard.kt",
            "InfoCard.kt",
            "SettingItem.kt",
            "StatusChip.kt",
            "ClickableDropdownField.kt",
            "CommonDialogs.kt",
            "NodeSelectionDialogs.kt",
            "AppMultiSelectDialog.kt",
            "NodeCard.kt",
            "ProfileCard.kt",
            "BigToggle.kt"
        )

        componentFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/components/$fileName").readText()
            assertTrue("$fileName should read liquid glass theme state", source.contains("isLiquidGlassTheme"))
        }
        assertTrue(
            File("src/main/java/com/kunk/singbox/ui/components/StandardCard.kt")
                .readText()
                .contains("Card(")
        )
    }

    @Test
    fun selectionListsUseLiquidGlassPanels() {
        val appMultiSelect = File("src/main/java/com/kunk/singbox/ui/components/AppMultiSelectDialog.kt").readText()
        val nodeSelection = File("src/main/java/com/kunk/singbox/ui/components/NodeSelectionDialogs.kt").readText()
        val profileDialogs = File("src/main/java/com/kunk/singbox/ui/screens/ProfilesScreenDialogs.kt").readText()

        assertTrue(appMultiSelect.contains("appSelectIconPanel("))
        assertTrue(nodeSelection.contains("nodeSelectionListItemPanel("))
        assertTrue(nodeSelection.contains("nodeFilterModePanel("))
        assertTrue(profileDialogs.contains("profileCustomNodePanel("))
    }

    @Test
    fun profileCardBadgesUseLiquidGlassPanels() {
        val profileCard = File("src/main/java/com/kunk/singbox/ui/components/ProfileCard.kt").readText()

        assertTrue(profileCard.contains("profileBadgePanel("))
    }

    @Test
    fun selectedIndicatorsUseLiquidGlassPanels() {
        val nodeCard = File("src/main/java/com/kunk/singbox/ui/components/NodeCard.kt").readText()
        val profileCard = File("src/main/java/com/kunk/singbox/ui/components/ProfileCard.kt").readText()
        val nodeSelection = File("src/main/java/com/kunk/singbox/ui/components/NodeSelectionDialogs.kt").readText()

        assertTrue(nodeCard.contains("nodeSelectedIndicatorPanel("))
        assertTrue(profileCard.contains("profileSelectedIndicatorPanel("))
        assertTrue(nodeSelection.contains("nodeSelectorCheckPanel("))
    }

    @Test
    fun trafficAndDetailSurfacesUseSharedLiquidGlassPanel() {
        val screenFiles = listOf(
            "TrafficStatsScreen.kt",
            "ConnectionInfoScreen.kt",
            "RuleSetHubScreen.kt",
            "NodeDetailDialogs.kt",
            "ProfilesScreenDialogs.kt"
        )

        screenFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/screens/$fileName").readText()
            assertTrue("$fileName should read liquid glass theme state", source.contains("isLiquidGlassTheme"))
            assertTrue("$fileName should use shared liquid glass panel", source.contains("liquidGlassPanel"))
        }
    }

    @Test
    fun importRuleAndRoutingSurfacesUseSharedLiquidGlassPanel() {
        val componentFiles = listOf(
            "ExportImportDialogs.kt",
            "AppListLoadingDialog.kt"
        )
        val screenFiles = listOf(
            "RuleSetsDialogs.kt",
            "AppRoutingComponents.kt",
            "NodesScreen.kt"
        )

        componentFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/components/$fileName").readText()
            assertTrue("$fileName should read liquid glass theme state", source.contains("isLiquidGlassTheme"))
            assertTrue("$fileName should use shared liquid glass panel", source.contains("liquidGlassPanel"))
        }
        screenFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/screens/$fileName").readText()
            assertTrue("$fileName should read liquid glass theme state", source.contains("isLiquidGlassTheme"))
            assertTrue("$fileName should use shared liquid glass panel", source.contains("liquidGlassPanel"))
        }
    }

    @Test
    fun appRoutingSmallSurfacesUseLiquidGlassPanels() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/AppRoutingComponents.kt").readText()

        assertTrue(source.contains("routingIconPanel("))
        assertTrue(source.contains("routingEmptySelectionPanel("))
        assertTrue(source.contains("routingMoreCountPanel("))
        assertTrue(source.contains("routingStatusBadgePanel("))
        assertTrue(source.contains("routingGroupIconPanel("))
        assertTrue(source.contains("routingSelectablePanel(isSelected: Boolean)"))
        assertFalse(source.contains("isLiquidGlassTheme() && isSelected"))
    }

    @Test
    fun trafficStatsSmallSurfacesUseLiquidGlassPanels() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/TrafficStatsScreen.kt").readText()

        assertTrue(source.contains("trafficStatIconPanel("))
        assertTrue(source.contains("trafficRankPanel("))
    }

    @Test
    fun alertDialogEditorsUseLiquidGlassDialogPanel() {
        val liquidTheme = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassTheme.kt").readText()
        val componentFiles = listOf(
            "AddNodeDialog.kt",
            "SelectProfileDialog.kt"
        )
        val screenFiles = listOf(
            "CustomRulesScreen.kt",
            "DomainRulesScreen.kt",
            "RuleSetsDialogs.kt",
            "RuleSetsScreen.kt",
            "AppRoutingComponents.kt",
            "ConnectionInfoScreen.kt"
        )

        assertTrue(liquidTheme.contains("fun Modifier.liquidGlassDialogPanel("))
        assertTrue(liquidTheme.contains("fun liquidGlassDialogContainerColor("))
        componentFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/components/$fileName").readText()
            assertTrue("$fileName should apply liquid glass dialog panel", source.contains("liquidGlassDialogPanel("))
            assertTrue(
                "$fileName should make liquid dialog container transparent",
                source.contains("liquidGlassDialogContainerColor()")
            )
        }
        screenFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/screens/$fileName").readText()
            assertTrue("$fileName should apply liquid glass dialog panel", source.contains("liquidGlassDialogPanel("))
            assertTrue(
                "$fileName should make liquid dialog container transparent",
                source.contains("liquidGlassDialogContainerColor()")
            )
        }
    }

    @Test
    fun overflowMenusUseLiquidGlassPanel() {
        val nodeCard = File("src/main/java/com/kunk/singbox/ui/components/NodeCard.kt").readText()
        val profileCard = File("src/main/java/com/kunk/singbox/ui/components/ProfileCard.kt").readText()

        assertTrue(nodeCard.contains("nodeOverflowMenuPanel()"))
        assertTrue(profileCard.contains("profileOverflowMenuPanel()"))
    }

    @Test
    fun smallBadgesAndEmptyStateUseLiquidGlassPanels() {
        val ruleSetHub = File("src/main/java/com/kunk/singbox/ui/screens/RuleSetHubScreen.kt").readText()
        val ruleSetsDialogs = File("src/main/java/com/kunk/singbox/ui/screens/RuleSetsDialogs.kt").readText()
        val connectionInfo = File("src/main/java/com/kunk/singbox/ui/screens/ConnectionInfoScreen.kt").readText()

        assertTrue(ruleSetHub.contains("RuleSetBadge("))
        assertTrue(ruleSetsDialogs.contains("RuleSetBadge("))
        assertTrue(connectionInfo.contains("connectionEmptyIconPanel()"))
        assertTrue(connectionInfo.contains("connectionMetaBadgePanel("))
        assertTrue(connectionInfo.contains("connectionProtocolBadgePanel("))
        assertTrue(connectionInfo.contains("connectionCloseButtonPanel("))
    }

    @Test
    fun logsFiltersUseLiquidGlassPanels() {
        val logsScreen = File("src/main/java/com/kunk/singbox/ui/screens/LogsScreen.kt").readText()

        assertTrue(logsScreen.contains("isLiquidGlassTheme"))
        assertTrue(logsScreen.contains("liquidGlassPanel"))
        assertTrue(logsScreen.contains("LogCategoryChip("))
    }

    @Test
    fun inlineSearchAndEditorSurfacesUseLiquidGlassPanels() {
        val nodes = File("src/main/java/com/kunk/singbox/ui/screens/NodesScreen.kt").readText()
        val connectionInfo = File("src/main/java/com/kunk/singbox/ui/screens/ConnectionInfoScreen.kt").readText()
        val ruleSetHub = File("src/main/java/com/kunk/singbox/ui/screens/RuleSetHubScreen.kt").readText()
        val profileEditor = File("src/main/java/com/kunk/singbox/ui/screens/ProfileEditorScreen.kt").readText()
        val logs = File("src/main/java/com/kunk/singbox/ui/screens/LogsScreen.kt").readText()

        assertTrue(nodes.contains("nodeSearchPanel()"))
        assertTrue(connectionInfo.contains("connectionSearchPanel()"))
        assertTrue(ruleSetHub.contains("RuleSetHubSearchField("))
        assertTrue(ruleSetHub.contains("liquidGlassTextFieldPanel("))
        assertTrue(logs.contains("liquidGlassTextFieldPanel("))
        assertTrue(profileEditor.contains("profileEditorPanel()"))
    }

    @Test
    fun sharedTextFieldsUseLiquidGlassPanels() {
        val liquidTheme = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassTheme.kt").readText()
        val fields = File("src/main/java/com/kunk/singbox/ui/components/ClickableDropdownField.kt").readText()

        assertTrue(liquidTheme.contains("fun Modifier.liquidGlassTextFieldPanel("))
        assertTrue(liquidTheme.contains("fun liquidGlassTextFieldContainerColor("))
        assertTrue(liquidTheme.contains("fun liquidGlassTextFieldBorderColor("))
        assertTrue(fields.contains("liquidGlassTextFieldPanel("))
        assertTrue(fields.contains("liquidGlassTextFieldContainerColor("))
        assertTrue(fields.contains("liquidGlassTextFieldBorderColor("))
    }

    @Test
    fun dialogAndEditorTextFieldsUseLiquidGlassPanels() {
        val componentFiles = listOf(
            "AddNodeDialog.kt",
            "AppMultiSelectDialog.kt",
            "NodeSelectionDialogs.kt",
            "SelectProfileDialog.kt"
        )
        val screenFiles = listOf(
            "AppRoutingComponents.kt",
            "ProfilesScreenDialogs.kt"
        )

        componentFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/components/$fileName").readText()
            assertTrue(
                "$fileName should use liquid glass text field panel",
                source.contains("liquidGlassTextFieldPanel(")
            )
            assertTrue(
                "$fileName should make liquid glass text field containers transparent",
                source.contains("liquidGlassTextFieldContainerColor(")
            )
            assertTrue(
                "$fileName should make liquid glass text field borders transparent",
                source.contains("liquidGlassTextFieldBorderColor(")
            )
        }
        screenFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/screens/$fileName").readText()
            assertTrue(
                "$fileName should use liquid glass text field panel",
                source.contains("liquidGlassTextFieldPanel(")
            )
            assertTrue(
                "$fileName should make liquid glass text field containers transparent",
                source.contains("liquidGlassTextFieldContainerColor(")
            )
            assertTrue(
                "$fileName should make liquid glass text field borders transparent",
                source.contains("liquidGlassTextFieldBorderColor(")
            )
        }
    }

    @Test
    fun nestedDialogSelectionSurfacesUseLiquidGlassPanels() {
        val domainRules = File("src/main/java/com/kunk/singbox/ui/screens/DomainRulesScreen.kt").readText()
        val profileDialogs = File("src/main/java/com/kunk/singbox/ui/screens/ProfilesScreenDialogs.kt").readText()
        val nodeDetailDialogs = File("src/main/java/com/kunk/singbox/ui/screens/NodeDetailDialogs.kt").readText()

        assertTrue(domainRules.contains("domainRuleSelectorPanel("))
        assertTrue(profileDialogs.contains("profileDnsMenuPanel("))
        assertTrue(profileDialogs.contains("profileDnsOptionPanel("))
        assertTrue(nodeDetailDialogs.contains("nodeDetailSelectionPanel("))
        assertTrue(nodeDetailDialogs.contains("nodeDetailGroupPanel("))
    }

    @Test
    fun liquidGlassNavUsesFloatingCapsuleWithPressFeedback() {
        val source = File("src/main/java/com/kunk/singbox/ui/components/AppNavBar.kt").readText()

        assertTrue(source.contains("LiquidGlassCapsule("))
        assertTrue(source.contains("private fun LiquidGlassCapsule"))
        assertTrue(source.contains(".height(52.dp)"))
        assertTrue(source.contains("liquidGlassSelectedButtonBrush"))
        assertFalse(source.contains("liquidGlassButtonBrush(isSelected = isSelected"))
        assertFalse(source.contains("selectedIndicatorBorderColor"))
        assertTrue(source.contains("MutableInteractionSource"))
        assertTrue(source.contains("collectIsPressedAsState"))
        assertTrue(source.contains("animateFloatAsState"))
        assertTrue(source.contains("graphicsLayer"))
        assertTrue(source.contains(".consumeUnclaimedClicks()"))
        assertTrue(source.contains("PointerEventPass.Final"))
        assertTrue(source.contains("change.consume()"))
    }

    @Test
    fun liquidGlassBottomBarFloatsAndTopLevelScreensAddBottomAvoidance() {
        val source = File("src/main/java/com/kunk/singbox/MainActivity.kt").readText()
        val surfacePaddingExpression = "if (useLiquidGlassNav) 0.dp " +
            "else innerPadding.calculateBottomPadding()"

        assertTrue(source.contains("val useLiquidGlassNav = appThemeStyle == AppThemeStyle.LIQUID_GLASS"))
        assertTrue(source.contains("if (!useLiquidGlassNav)"))
        assertTrue(source.contains("val dashboardContentBottomPadding = if (useLiquidGlassNav)"))
        assertTrue(source.contains("64.dp"))
        assertTrue(source.contains("val topLevelContentBottomPadding = if (useLiquidGlassNav)"))
        assertTrue(source.contains("dashboardBottomContentPadding = dashboardContentBottomPadding"))
        assertTrue(source.contains("topLevelBottomContentPadding = topLevelContentBottomPadding"))
        assertTrue(source.contains(surfacePaddingExpression))
        assertTrue(source.contains("modifier = Modifier.align(Alignment.BottomCenter)"))
    }

    @Test
    fun dashboardScreenAcceptsBottomContentPaddingWithoutShrinkingBackground() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/DashboardScreen.kt").readText()

        assertTrue(source.contains("bottomContentPadding: Dp = 0.dp"))
        assertTrue(source.contains(".padding(bottom = bottomContentPadding)"))
    }

    @Test
    fun appNavigationPassesBottomAvoidanceToTopLevelContent() {
        val source = File("src/main/java/com/kunk/singbox/ui/navigation/AppNavigation.kt").readText()

        assertTrue(source.contains("dashboardBottomContentPadding: Dp = 0.dp"))
        assertTrue(source.contains("topLevelBottomContentPadding: Dp = 0.dp"))
        assertTrue(source.contains("bottomContentPadding = dashboardBottomContentPadding"))
        assertTrue(source.contains("bottomContentPadding = topLevelBottomContentPadding"))
        assertFalse(source.contains("modifier: Modifier = Modifier"))
        assertFalse(source.contains("modifier = modifier"))
    }
}

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
        assertTrue(theme.contains("CompositionLocalProvider("))
        assertTrue(theme.contains("LocalAppThemeStyle provides appThemeStyle"))
        assertTrue(liquidTheme.contains("val LocalAppThemeStyle"))
        assertTrue(liquidTheme.contains("fun isLiquidGlassTheme()"))
        assertTrue(liquidTheme.contains("fun Modifier.liquidGlassPanel("))
        assertTrue(main.contains("SingBoxTheme(appTheme = appTheme, appThemeStyle = appThemeStyle)"))
    }

    @Test
    fun liquidGlassThemeDisablesMaterialRippleOnlyForLiquidStyle() {
        val theme = File("src/main/java/com/kunk/singbox/ui/theme/Theme.kt").readText()

        assertTrue(theme.contains("LocalRippleConfiguration"))
        assertTrue(theme.contains("RippleConfiguration"))
        assertTrue(theme.contains("val rippleConfiguration = if (appThemeStyle == AppThemeStyle.LIQUID_GLASS)"))
        assertTrue(theme.contains("null\n    } else {\n        RippleConfiguration()"))
        assertTrue(theme.contains("LocalRippleConfiguration provides rippleConfiguration"))
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
    fun settingItemsUseLiquidGlassPanelsForIconsAndSwitches() {
        val source = File("src/main/java/com/kunk/singbox/ui/components/SettingItem.kt").readText()

        assertTrue(source.contains("liquidGlassPanel(shape = CircleShape"))
        assertTrue(source.contains("liquidGlassSwitchColors("))
        assertFalse(source.contains("liquidGlassPanelBorderColor"))
    }

    @Test
    fun dashboardStatusChipsUseLiquidGlassPanels() {
        val source = File("src/main/java/com/kunk/singbox/ui/components/StatusChip.kt").readText()

        assertTrue(source.contains("fun StatusChip("))
        assertTrue(source.contains("Modifier.liquidGlassPanel("))
        assertTrue(source.contains("modeChipIndicatorPanel("))
        assertTrue(source.contains(".liquidGlassPanel(shape = CircleShape, selected = true"))
    }

    @Test
    fun selectionListsUseLiquidGlassPanels() {
        val commonDialogs = File("src/main/java/com/kunk/singbox/ui/components/CommonDialogs.kt").readText()
        val appMultiSelect = File("src/main/java/com/kunk/singbox/ui/components/AppMultiSelectDialog.kt").readText()
        val nodeSelection = File("src/main/java/com/kunk/singbox/ui/components/NodeSelectionDialogs.kt").readText()
        val profileDialogs = File("src/main/java/com/kunk/singbox/ui/screens/ProfilesScreenDialogs.kt").readText()

        assertTrue(commonDialogs.contains("dialogOptionPanel(isSelected: Boolean)"))
        assertTrue(commonDialogs.contains("liquidGlassPanel(shape = shape, selected = isSelected"))
        assertTrue(appMultiSelect.contains("appSelectIconPanel("))
        assertTrue(appMultiSelect.contains("appSelectFilterPanel("))
        assertTrue(appMultiSelect.contains("if (useLiquidGlass)"))
        assertTrue(appMultiSelect.contains("selected = checked"))
        assertTrue(nodeSelection.contains("nodeSelectionListItemPanel("))
        assertTrue(nodeSelection.contains("nodeFilterModePanel("))
        assertTrue(nodeSelection.contains("liquidGlassPanel(shape = shape, selected = isSelected"))
        assertTrue(profileDialogs.contains("profileCustomNodePanel("))
        assertTrue(profileDialogs.contains("profileDnsOptionPanel(isSelected: Boolean)"))
        assertTrue(profileDialogs.contains("liquidGlassPanel(shape = RoundedCornerShape(12.dp), selected = isSelected"))
        assertFalse(commonDialogs.contains("isLiquidGlassTheme() && isSelected"))
        assertFalse(appMultiSelect.contains("useLiquidGlass && checked"))
        assertFalse(nodeSelection.contains("isLiquidGlassTheme() && isSelected"))
        assertFalse(profileDialogs.contains("isLiquidGlassTheme() && isSelected"))
    }

    @Test
    fun profileCardBadgesUseLiquidGlassPanels() {
        val profileCard = File("src/main/java/com/kunk/singbox/ui/components/ProfileCard.kt").readText()

        assertTrue(profileCard.contains("profileBadgePanel("))
        assertTrue(profileCard.contains("profileBadgeContentColor("))
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
    fun nodeTrafficAndDashboardIdleIndicatorUseLiquidGlassMutedColor() {
        val nodeCard = File("src/main/java/com/kunk/singbox/ui/components/NodeCard.kt").readText()
        val dashboard = File("src/main/java/com/kunk/singbox/ui/screens/DashboardScreen.kt").readText()

        assertTrue(nodeCard.contains("liquidGlassMutedContentColor(Color(0xFF9575CD))"))
        assertTrue(dashboard.contains("liquidGlassMutedContentColor(Neutral500)"))
    }

    @Test
    fun loadingDialogsUseLiquidGlassContentColors() {
        val liquidTheme = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassTheme.kt").readText()
        val loadingDialog = File("src/main/java/com/kunk/singbox/ui/components/AppListLoadingDialog.kt").readText()

        assertTrue(liquidTheme.contains("fun liquidGlassStrongContentColor("))
        assertTrue(loadingDialog.contains("liquidGlassStrongContentColor(TextPrimary)"))
        assertTrue(loadingDialog.contains("liquidGlassMutedContentColor(TextSecondary)"))
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
        assertTrue(source.contains("routingAppListItemPanel("))
        assertTrue(source.contains("routingFilterTogglePanel("))
        assertTrue(source.contains("routingSelectablePanel(isSelected: Boolean)"))
        assertFalse(source.contains("isLiquidGlassTheme() && isSelected"))
    }

    @Test
    fun appRulesOutboundChipsUseLiquidGlassPanels() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/AppRulesScreen.kt").readText()

        assertTrue(source.contains("outboundChipPanel("))
        assertTrue(source.contains("liquidGlassPanel(shape = CircleShape"))
    }

    @Test
    fun trafficStatsSmallSurfacesUseLiquidGlassPanels() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/TrafficStatsScreen.kt").readText()

        assertTrue(source.contains("trafficStatIconPanel("))
        assertTrue(source.contains("trafficRankPanel("))
        assertTrue(source.contains("trafficRankTextColor("))
        assertTrue(source.contains("trafficLegendMarkerPanel("))
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
        assertTrue(ruleSetHub.contains("ruleSetBadgeContentColor("))
        assertTrue(ruleSetsDialogs.contains("RuleSetBadge("))
        assertTrue(ruleSetsDialogs.contains("ruleSetBadgeContentColor("))
        assertTrue(connectionInfo.contains("connectionEmptyIconPanel()"))
        assertTrue(connectionInfo.contains("connectionMetaBadgePanel("))
        assertTrue(connectionInfo.contains("connectionProtocolBadgePanel("))
        assertTrue(connectionInfo.contains("connectionProtocolBadgeTextColor("))
        assertTrue(connectionInfo.contains("connectionMetaBadgeTextColor("))
        assertTrue(connectionInfo.contains("connectionCloseButtonPanel("))
        assertTrue(connectionInfo.contains("liquidGlassEmptyStatePanel("))
    }

    @Test
    fun logsFiltersUseLiquidGlassPanels() {
        val logsScreen = File("src/main/java/com/kunk/singbox/ui/screens/LogsScreen.kt").readText()
        val chipControls = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassChipControls.kt").readText()

        assertTrue(chipControls.contains("fun LiquidGlassFilterChip("))
        assertTrue(chipControls.contains("isLiquidGlassTheme"))
        assertTrue(chipControls.contains("liquidGlassPanel"))
        assertTrue(logsScreen.contains("LiquidGlassFilterChip("))
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
        assertTrue(nodes.contains("nodeActiveIndicatorPanel()"))
        assertTrue(nodes.contains("nodeTestingProgressPanel()"))
        assertTrue(connectionInfo.contains("connectionSearchPanel()"))
        assertTrue(ruleSetHub.contains("RuleSetHubSearchField("))
        assertTrue(ruleSetHub.contains("liquidGlassTextFieldPanel("))
        assertTrue(logs.contains("liquidGlassTextFieldPanel("))
        assertTrue(profileEditor.contains("profileEditorPanel()"))
    }

    @Test
    fun floatingActionsUseLiquidGlassPanels() {
        val liquidControls = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassControls.kt").readText()
        val nodes = File("src/main/java/com/kunk/singbox/ui/screens/NodesScreen.kt").readText()
        val profiles = File("src/main/java/com/kunk/singbox/ui/screens/ProfilesScreen.kt").readText()

        assertTrue(liquidControls.contains("fun Modifier.liquidGlassFloatingActionPanel("))
        assertTrue(liquidControls.contains("fun liquidGlassFloatingActionContainerColor("))
        assertTrue(liquidControls.contains("fun liquidGlassFloatingActionContentColor("))
        assertTrue(nodes.contains("liquidGlassFloatingActionPanel("))
        assertTrue(nodes.contains("liquidGlassFloatingActionContainerColor("))
        assertTrue(nodes.contains("liquidGlassFloatingActionContentColor("))
        assertTrue(profiles.contains("liquidGlassFloatingActionPanel("))
        assertTrue(profiles.contains("liquidGlassFloatingActionContainerColor("))
        assertTrue(profiles.contains("liquidGlassFloatingActionContentColor("))
    }

    @Test
    fun splashScreenUsesLiquidGlassBackgroundOnlyForLiquidTheme() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/SplashScreen.kt").readText()

        assertTrue(source.contains("isLiquidGlassTheme"))
        assertTrue(source.contains("liquidGlassSplashBackgroundBrush("))
        assertTrue(source.contains("AppBackground"))
    }

    @Test
    fun topAppBarsUseLiquidGlassTransparentContainerColor() {
        val liquidControls = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassControls.kt").readText()
        val topAppBarFiles = listOf(
            "AppGroupsScreen.kt",
            "AppRoutingScreen.kt",
            "AppRulesScreen.kt",
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
        )

        assertTrue(liquidControls.contains("fun liquidGlassTopAppBarContainerColor("))
        topAppBarFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/screens/$fileName").readText()
            assertTrue(
                "$fileName should make liquid glass top app bar transparent",
                source.contains("liquidGlassTopAppBarContainerColor(")
            )
        }
    }

    @Test
    fun topLevelScreenRootsUseLiquidGlassTransparentContainerColor() {
        val liquidControls = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassControls.kt").readText()
        val nodes = File("src/main/java/com/kunk/singbox/ui/screens/NodesScreen.kt").readText()
        val profiles = File("src/main/java/com/kunk/singbox/ui/screens/ProfilesScreen.kt").readText()
        val settings = File("src/main/java/com/kunk/singbox/ui/screens/SettingsScreen.kt").readText()

        assertTrue(liquidControls.contains("fun liquidGlassScreenContainerColor("))
        assertTrue(nodes.contains("liquidGlassScreenContainerColor(MaterialTheme.colorScheme.background)"))
        assertTrue(profiles.contains("liquidGlassScreenContainerColor(MaterialTheme.colorScheme.background)"))
        assertTrue(settings.contains("liquidGlassScreenContainerColor(MaterialTheme.colorScheme.background)"))
    }

    @Test
    fun primaryButtonsUseLiquidGlassPanels() {
        val liquidControls = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassControls.kt").readText()
        val liquidButtonColors = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassButtonColors.kt").readText()
        val componentFiles = listOf(
            "AppMultiSelectDialog.kt",
            "CommonDialogs.kt",
            "ExportImportDialogs.kt",
            "NodeSelectionDialogs.kt"
        )
        val screenFiles = listOf(
            "AppRoutingComponents.kt",
            "DomainRulesScreen.kt",
            "NodeDetailDialogs.kt",
            "ProfilesScreenDialogs.kt",
            "RuleSetHubScreen.kt"
        )

        assertTrue(liquidControls.contains("fun Modifier.liquidGlassButtonPanel("))
        assertTrue(liquidButtonColors.contains("fun liquidGlassButtonColors("))
        assertTrue(liquidButtonColors.contains("disabledContainerColor = Color.Transparent"))
        assertTrue(liquidControls.contains("fun liquidGlassButtonContainerColor("))
        assertTrue(liquidControls.contains("fun liquidGlassButtonContentColor("))
        componentFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/components/$fileName").readText()
            assertTrue("$fileName should apply liquid glass button panel", source.contains("liquidGlassButtonPanel("))
            assertTrue(
                "$fileName should use liquid glass button colors",
                source.contains("liquidGlassButtonColors(")
            )
        }
        screenFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/screens/$fileName").readText()
            assertTrue("$fileName should apply liquid glass button panel", source.contains("liquidGlassButtonPanel("))
            assertTrue(
                "$fileName should use liquid glass button colors",
                source.contains("liquidGlassButtonColors(")
            )
        }
    }

    @Test
    fun selectionControlsUseLiquidGlassColors() {
        val liquidControls = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassSelectionControls.kt")
            .readText()
        val radioFiles = listOf(
            "AddNodeDialog.kt",
            "SelectProfileDialog.kt"
        )
        val switchComponentFiles = listOf("SettingItem.kt")
        val switchScreenFiles = listOf(
            "AppRoutingComponents.kt",
            "ProfilesScreenDialogs.kt",
            "RuleSetsDialogs.kt"
        )

        assertTrue(liquidControls.contains("fun liquidGlassRadioButtonColors("))
        assertTrue(liquidControls.contains("fun liquidGlassSwitchColors("))
        assertTrue(liquidControls.contains("MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)"))
        radioFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/components/$fileName").readText()
            assertTrue(
                "$fileName should use liquid glass radio colors",
                source.contains("liquidGlassRadioButtonColors(")
            )
            assertTrue(
                "$fileName should use liquid glass radio option panels",
                source.contains("TargetOptionPanel(")
            )
        }
        switchComponentFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/components/$fileName").readText()
            assertTrue(
                "$fileName should use liquid glass switch colors",
                source.contains("liquidGlassSwitchColors(")
            )
        }
        switchScreenFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/screens/$fileName").readText()
            assertTrue(
                "$fileName should use liquid glass switch colors",
                source.contains("liquidGlassSwitchColors(")
            )
        }
        assertTrue(
            File("src/main/java/com/kunk/singbox/ui/screens/RuleSetsScreen.kt")
                .readText()
                .contains("ruleSetInboundOptionPanel(")
        )
    }

    @Test
    fun outlinedButtonsUseLiquidGlassPanels() {
        val liquidControls = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassControls.kt").readText()
        val domainRules = File("src/main/java/com/kunk/singbox/ui/screens/DomainRulesScreen.kt").readText()

        assertTrue(liquidControls.contains("fun liquidGlassOutlinedButtonBorder("))
        assertTrue(domainRules.contains("liquidGlassButtonPanel("))
        assertTrue(domainRules.contains("liquidGlassOutlinedButtonBorder("))
        assertTrue(domainRules.contains("liquidGlassButtonContainerColor("))
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
    fun fixedNeutralContentUsesLiquidGlassMutedColor() {
        val liquidControls = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassTheme.kt").readText()
        val sourceFiles = listOf(
            "src/main/java/com/kunk/singbox/ui/components/CommonDialogs.kt",
            "src/main/java/com/kunk/singbox/ui/screens/AppGroupsScreen.kt",
            "src/main/java/com/kunk/singbox/ui/screens/AppRulesScreen.kt",
            "src/main/java/com/kunk/singbox/ui/screens/AppRoutingComponents.kt",
            "src/main/java/com/kunk/singbox/ui/screens/AppRoutingScreen.kt",
            "src/main/java/com/kunk/singbox/ui/screens/ConnectionInfoScreen.kt",
            "src/main/java/com/kunk/singbox/ui/screens/NodesScreen.kt",
            "src/main/java/com/kunk/singbox/ui/screens/RoutingSettingsScreen.kt",
            "src/main/java/com/kunk/singbox/ui/screens/SplashScreen.kt"
        )

        assertTrue(liquidControls.contains("fun liquidGlassMutedContentColor("))
        sourceFiles.forEach { filePath ->
            val source = File(filePath).readText()
            assertTrue(
                "$filePath should use liquid glass muted content color",
                source.contains("liquidGlassMutedContentColor(")
            )
        }
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
        assertTrue(source.contains("val rootContainerColor = if (useLiquidGlassNav)"))
        assertTrue(source.contains("MaterialTheme.colorScheme.background"))
        assertTrue(source.contains("MaterialTheme.colorScheme.surface"))
        assertTrue(source.contains("containerColor = rootContainerColor"))
        assertTrue(source.contains("color = rootContainerColor"))
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

package com.kunk.singbox.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiquidGlassTopAppBarSourceTest {

    @Test
    fun topAppBarsUseLiquidGlassColors() {
        val liquidControls = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassControls.kt").readText()
        val topAppBarColors = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassTopAppBarColors.kt")
            .readText()
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
        assertTrue(topAppBarColors.contains("fun liquidGlassTopAppBarColors("))
        assertTrue(topAppBarColors.contains("scrolledContainerColor = Color.Transparent"))
        assertTrue(topAppBarColors.contains("titleContentColor = MaterialTheme.colorScheme.onSurface"))
        topAppBarFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/screens/$fileName").readText()
            assertTrue(
                "$fileName should use liquid glass top app bar colors",
                source.contains("liquidGlassTopAppBarColors(")
            )
        }
    }
}

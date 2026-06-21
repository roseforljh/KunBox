package com.kunk.singbox.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiquidGlassTextButtonSourceTest {

    @Test
    fun textButtonsUseLiquidGlassColors() {
        val liquidControls = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassControls.kt").readText()
        val liquidTextButtonColors = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassTextButtonColors.kt")
            .readText()
        val componentFiles = listOf(
            "AddNodeDialog.kt",
            "AppMultiSelectDialog.kt",
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
        assertTrue(liquidTextButtonColors.contains("fun liquidGlassTextButtonColors("))
        assertTrue(
            liquidTextButtonColors.contains(
                "disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)"
            )
        )
        componentFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/components/$fileName").readText()
            assertTrue(
                "$fileName should apply liquid glass text button panel",
                source.contains("liquidGlassTextButtonPanel(")
            )
            assertTrue(
                "$fileName should use liquid glass text button colors",
                source.contains("liquidGlassTextButtonColors(")
            )
        }
        screenFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/screens/$fileName").readText()
            assertTrue(
                "$fileName should apply liquid glass text button panel",
                source.contains("liquidGlassTextButtonPanel(")
            )
            assertTrue(
                "$fileName should use liquid glass text button colors",
                source.contains("liquidGlassTextButtonColors(")
            )
        }
    }
}

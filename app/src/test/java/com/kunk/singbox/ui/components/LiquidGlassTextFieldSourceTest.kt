package com.kunk.singbox.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiquidGlassTextFieldSourceTest {

    @Test
    fun outlinedTextFieldsUseLiquidGlassColors() {
        val liquidTextFieldColors = File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassTextFieldColors.kt")
            .readText()
        val componentFiles = listOf(
            "AddNodeDialog.kt",
            "AppMultiSelectDialog.kt",
            "ClickableDropdownField.kt",
            "CommonDialogs.kt",
            "NodeSelectionDialogs.kt",
            "SelectProfileDialog.kt"
        )
        val screenFiles = listOf(
            "AppRoutingComponents.kt",
            "LogsScreen.kt",
            "ProfilesScreenDialogs.kt",
            "RuleSetHubScreen.kt"
        )

        assertTrue(liquidTextFieldColors.contains("fun liquidGlassOutlinedTextFieldColors("))
        assertTrue(liquidTextFieldColors.contains("OutlinedTextFieldDefaults.colors("))
        assertTrue(liquidTextFieldColors.contains("liquidGlassTextFieldContainerColor("))
        assertTrue(liquidTextFieldColors.contains("liquidGlassTextFieldBorderColor("))
        assertTrue(liquidTextFieldColors.contains("errorBorderColor: Color = Color.Unspecified"))
        assertTrue(liquidTextFieldColors.contains("errorContainerColor: Color = Color.Unspecified"))
        val errorBorderColorMapping = "errorBorderColor = liquidGlassTextFieldBorderColor(errorBorderColor)"
        val errorContainerColorMapping = "errorContainerColor = liquidGlassTextFieldContainerColor(errorContainerColor)"
        assertTrue(liquidTextFieldColors.contains(errorBorderColorMapping))
        assertTrue(liquidTextFieldColors.contains(errorContainerColorMapping))
        componentFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/components/$fileName").readText()
            assertTrue(
                "$fileName should use liquid glass outlined text field colors",
                source.contains("liquidGlassOutlinedTextFieldColors(")
            )
        }
        screenFiles.forEach { fileName ->
            val source = File("src/main/java/com/kunk/singbox/ui/screens/$fileName").readText()
            assertTrue(
                "$fileName should use liquid glass outlined text field colors",
                source.contains("liquidGlassOutlinedTextFieldColors(")
            )
        }
    }
}

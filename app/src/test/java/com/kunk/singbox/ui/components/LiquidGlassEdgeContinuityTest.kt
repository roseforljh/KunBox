package com.kunk.singbox.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiquidGlassEdgeContinuityTest {

    @Test
    fun embossedEdgeFadesOutWithoutDrawingADarkBottomStroke() {
        val crystal = source("ui/theme/LiquidGlassTheme.kt")
            .extractFunctionBody("liquidGlassCrystalSurface")
        val embossedRim = crystal.substring(
            crystal.indexOf("val embossedRimBrush"),
            crystal.indexOf("val specularBrush")
        )

        assertFalse(embossedRim.contains("Color.Black"))
        assertTrue(embossedRim.contains("Color.White.copy(alpha = 0f)"))
    }

    @Test
    fun dropdownMenuKeepsOneGlassLayerAndEnoughShadowSpace() {
        val menu = source("ui/theme/LiquidGlassMenuControls.kt")
            .extractFunctionBody("LiquidGlassDropdownMenu")
        val profileMenu = source("ui/components/ProfileCard.kt")
            .extractFunctionBody("profileOverflowMenuPanel")
        val ruleSetMenu = source("ui/screens/RuleSetsDialogs.kt")
            .extractFunctionBody("ruleSetMenuPanel")

        assertTrue(menu.contains(".padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 20.dp)"))
        assertTrue(menu.contains(".liquidGlassPanel(shape = menuShape, shadowElevation = 12.dp)"))
        assertFalse(profileMenu.contains(".liquidGlassPanel("))
        assertFalse(ruleSetMenu.contains("liquidGlassPanel("))
    }

    private fun String.extractFunctionBody(functionName: String): String {
        val start = listOf("fun Modifier.$functionName", "fun $functionName")
            .map(::indexOf)
            .firstOrNull { it >= 0 } ?: -1
        assertTrue("Missing function $functionName", start >= 0)
        val bodyStart = indexOf('{', start)
        assertTrue("Missing body for $functionName", bodyStart >= 0)

        var depth = 0
        for (index in bodyStart until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return substring(start, index + 1)
                }
            }
        }
        throw AssertionError("Unclosed body for $functionName")
    }

    private fun source(path: String): String {
        return File("src/main/java/com/kunk/singbox/$path")
            .readText()
            .replace("\r\n", "\n")
    }
}

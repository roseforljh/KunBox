package com.kunk.singbox.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiquidGlassUnifiedSurfaceStructureTest {

    @Test
    fun sharedPanelsUseNeutralEmbossedOpticsWithoutBlueContainerLayers() {
        val theme = source("ui/theme/LiquidGlassTheme.kt")
        val panel = theme.extractFunctionBody("liquidGlassPanel")
        val material = theme.extractFunctionBody("liquidGlassMaterial")
        val crystal = theme.extractFunctionBody("liquidGlassCrystalSurface")
        val controls = source("ui/theme/LiquidGlassControls.kt")
        val modeIndicator = source("ui/components/StatusChip.kt")
            .extractFunctionBody("modeChipIndicatorPanel")

        assertTrue(theme.contains("LocalLiquidGlassBackdrop = staticCompositionLocalOf<Backdrop?>"))
        assertTrue(panel.contains("val backdrop = LocalLiquidGlassBackdrop.current"))
        assertTrue(panel.contains("val controlSurfaceColor = if (isDark) Color.Black else Color.White"))
        assertTrue(panel.contains("controlSurfaceColor.copy(alpha = if (isDark) 0.84f else 0.88f)"))
        assertTrue(panel.contains("val edgeShadowColor = if (isDark) Color.White else Color.Black"))
        assertFalse(panel.contains("Primary.copy("))
        assertFalse(panel.contains("surfaceContainer.copy(alpha = 0.20f)"))
        assertTrue(panel.contains(".drawBackdrop("))
        assertTrue(panel.contains("highlight = null"))
        assertTrue(panel.contains("vibrancy()"))
        assertTrue(panel.contains("blur(0.5.dp.toPx() * opticsScale)"))
        assertTrue(panel.contains("refractionHeight = 40.dp.toPx() * opticsScale"))
        assertTrue(panel.contains("refractionAmount = 60.dp.toPx() * opticsScale"))
        assertTrue(panel.contains("chromaticAberration = false"))
        assertTrue(panel.contains("backdropVisible = true"))
        assertTrue(material.contains("backdropVisible: Boolean = true"))

        assertTrue(crystal.contains("size.minDimension / 64.dp.toPx()"))
        assertTrue(crystal.contains("3.2.dp.toPx() * opticsScale"))
        assertTrue(crystal.contains("0.9.dp.toPx() * opticsScale"))
        assertTrue(crystal.contains("embossedRimBrush"))
        assertTrue(crystal.contains("if (accented) BlendMode.SrcOver else BlendMode.Screen"))
        assertTrue(
            crystal.lastIndexOf("if (!accented)") < crystal.indexOf("drawLine(")
        )
        assertFalse(modeIndicator.contains(".border("))

        assertTrue(
            controls.contains(
                "liquidGlassPanel(shape = shape, selected = false, shadowElevation = shadowElevation)"
            )
        )
        assertFalse(
            controls.extractFunctionBody("liquidGlassFloatingActionPanel")
                .contains("selected = true")
        )
        assertFalse(
            controls.extractFunctionBody("liquidGlassButtonPanel")
                .contains("selected = true")
        )
    }

    @Test
    fun componentBackdropSamplesOnlyTheAppBackground() {
        val activity = source("MainActivity.kt")

        assertTrue(activity.contains("val liquidGlassComponentBackdrop = rememberLayerBackdrop()"))
        assertTrue(activity.contains("Modifier.layerBackdrop(liquidGlassComponentBackdrop)"))
        assertTrue(activity.contains("LocalLiquidGlassBackdrop provides if (useLiquidGlassNav)"))
        assertTrue(activity.contains(".layerBackdrop(liquidGlassContentBackdrop)"))
        assertFalse(
            activity.contains(
                ".layerBackdrop(liquidGlassComponentBackdrop)\n" +
                    "                            .background(rootContainerColor)"
            )
        )
    }

    private fun String.extractFunctionBody(functionName: String): String {
        val start = listOf(
            "fun Modifier.$functionName",
            "fun BoxScope.$functionName",
            "fun $functionName"
        )
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

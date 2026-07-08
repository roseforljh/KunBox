package com.kunk.singbox.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiquidGlassReactBitsSurfaceTest {

    @Test
    fun liquidGlassPanelKeepsSharedThemeEntryWithoutInternalDrawLayers() {
        val source = liquidGlassThemeSource()

        listOf(
            "drawWithContent",
            "drawWithCache",
            "BlendMode.Screen",
            "Color(0xFFFF3B30)",
            "Color(0xFF0A84FF)",
            "float3 channel = float3(redMap, greenMap, blueMap) * channelAlpha;"
        ).forEach { token ->
            assertFalse(source.contains(token))
        }
        assertTrue(source.contains(".background(liquidGlassPanelBrush(selected = selected))"))
        assertTrue(source.contains("liquidGlassPanelBorderBrush(selected = selected)"))
    }

    @Test
    fun liquidGlassPanelDoesNotUseReloadVisibleInteriorSurfaceHelpers() {
        val source = liquidGlassThemeSource()

        listOf(
            "RuntimeShader(",
            "ShaderBrush(",
            "REACT_BITS_GLASS_SURFACE_SHADER",
            "ReactBitsGlassSurfaceSpec",
            "reactBitsGlassSurface",
            "drawReactBits",
            "channelAlpha",
            "innerGlowAlpha",
            "edgeAlpha"
        ).forEach { token ->
            assertFalse(source.contains(token))
        }
    }

    @Test
    fun selectedPrimaryTintDoesNotUseSeparateInteriorOverlay() {
        val source = liquidGlassThemeSource()
        val panelBody = source.extractFunctionBody("liquidGlassPanel")

        assertFalse(panelBody.contains(".liquidGlassSelectedSyncTint("))
        assertFalse(source.contains("private fun Modifier.liquidGlassSelectedSyncTint("))
        assertFalse(source.contains("liquidGlassSelectedSyncTintBrush("))
    }

    @Test
    fun selectedLiquidGlassStateDoesNotRekeyOuterShadow() {
        val source = liquidGlassThemeSource()
        val panelBody = source.extractFunctionBody("liquidGlassPanel")

        assertFalse(source.contains("LiquidGlassShadowSpec"))
        assertFalse(source.contains("liquidGlassPanelShadowSpec"))
        assertTrue(panelBody.contains("val shadowAlpha = if (isDark) 0.35f else 0.12f"))
        assertFalse(panelBody.contains("liquidGlassPanelShadowSpec(selected = selected)"))
        assertTrue(panelBody.contains("color = Color.Black"))
        assertTrue(panelBody.contains("alpha = shadowAlpha"))
    }

    @Test
    fun liquidGlassPanelBrushDoesNotKeepDeletedShaderVersionBranches() {
        val source = liquidGlassThemeSource()
        val brushBody = source.extractFunctionBody("liquidGlassPanelBrush")

        assertFalse(brushBody.contains("useShaderSurface"))
        assertFalse(brushBody.contains("Build.VERSION_CODES.TIRAMISU"))
    }

    @Test
    fun liquidGlassPanelDoesNotDrawReloadVisibleInteriorShadow() {
        val source = liquidGlassThemeSource()

        listOf(
            "bottomShadeAlpha",
            "drawReactBitsBottomShade",
            "Color.Black.copy(alpha = bottomShadeAlpha)",
            "lowerStart = size.height * 0.56f"
        ).forEach { token ->
            assertFalse(source.contains(token))
        }
    }

    @Test
    fun mainActivityUsesCurrentSettingsAsInitialStateToAvoidThemeFlash() {
        val source = File("src/main/java/com/kunk/singbox/MainActivity.kt")
            .readText()
            .replace("\r\n", "\n")

        assertTrue(source.contains("initialValue = settingsRepository.settings.value"))
        assertTrue(!source.contains("settings?.appThemeStyle ?: AppThemeStyle.DEFAULT"))
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
                    if (depth == 0) return substring(bodyStart, index + 1)
                }
            }
        }
        throw AssertionError("Unclosed body for $functionName")
    }

    private fun liquidGlassThemeSource(): String {
        return File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassTheme.kt")
            .readText()
            .replace("\r\n", "\n")
    }
}

package com.kunk.singbox.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiquidGlassReactBitsSurfaceTest {

    @Test
    fun liquidGlassUsesNeutralCrystalOpticsAndAnimatedNavigationIndicator() {
        val themeSource = liquidGlassThemeSource()
        val navSource = File("src/main/java/com/kunk/singbox/ui/components/AppNavBar.kt")
            .readText()
            .replace("\r\n", "\n")

        val crystalBody = themeSource.extractFunctionBody("liquidGlassCrystalSurface")
        assertTrue(themeSource.contains(".liquidGlassCrystalSurface("))
        assertTrue(crystalBody.contains("BlendMode.Screen"))
        assertTrue(crystalBody.contains("causticBrush"))
        assertTrue(crystalBody.contains("innerRimBrush"))
        assertFalse(crystalBody.contains("coolRefraction"))
        assertFalse(crystalBody.contains("warmRefraction"))
        assertFalse(crystalBody.contains("0xFF66D9FF"))
        assertFalse(crystalBody.contains("0xFFFF8BCB"))
        assertFalse(themeSource.contains("Primary.copy("))
        assertTrue(navSource.contains("liquid_glass_nav_indicator_offset"))
        val indicatorBody = navSource.extractFunctionBody("LiquidGlassSelectedIndicator")
        assertFalse(indicatorBody.contains(".background("))
        assertTrue(indicatorBody.contains(".liquidGlassMaterial("))
        assertTrue(indicatorBody.contains("selected = true"))
        assertFalse(indicatorBody.contains(".liquidGlassPanel("))
        assertFalse(indicatorBody.contains("shadowElevation"))
        assertTrue(navSource.contains("selectedIconColor = MaterialTheme.colorScheme.primary"))
        assertFalse(navSource.contains("0xFF76D7FF"))
    }

    @Test
    fun liquidGlassPanelKeepsSharedThemeEntryAndOpticalDrawLayer() {
        val source = liquidGlassThemeSource()
        val panelBody = source.extractFunctionBody("liquidGlassPanel")

        assertTrue(panelBody.contains(".liquidGlassMaterial("))
        assertTrue(
            source.extractFunctionBody("liquidGlassMaterial")
                .contains("backdropVisible = backdropVisible")
        )
        assertTrue(source.extractFunctionBody("liquidGlassCrystalSurface").contains("drawWithCache"))
        assertTrue(source.extractFunctionBody("hollowShadow").contains("drawWithCache"))
    }

    @Test
    fun liquidGlassPanelDoesNotUseApi33OnlyShaders() {
        val source = liquidGlassThemeSource()

        listOf(
            "RuntimeShader(",
            "ShaderBrush(",
            "REACT_BITS_GLASS_SURFACE_SHADER",
            "ReactBitsGlassSurfaceSpec",
            "reactBitsGlassSurface",
            "drawReactBits"
        ).forEach { token ->
            assertFalse(source.contains(token))
        }
    }

    @Test
    fun selectedNeutralTintDoesNotUseSeparateInteriorOverlay() {
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
        assertTrue(panelBody.contains("val edgeShadowAlpha = if (isDark) 0.08f else 0.12f"))
        assertTrue(panelBody.contains("val edgeShadowColor = if (isDark) Color.White else Color.Black"))
        assertFalse(panelBody.contains("liquidGlassPanelShadowSpec(selected = selected)"))
        assertTrue(panelBody.contains("color = edgeShadowColor"))
        assertTrue(panelBody.contains("alpha = edgeShadowAlpha"))
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
    fun liquidGlassBackdropLayersStayNeutralWhileSamplingRealContent() {
        val source = liquidGlassThemeSource()
        val backdropBody = source.extractFunctionBody("liquidGlassBackdrop")
        val mainSource = File("src/main/java/com/kunk/singbox/MainActivity.kt")
            .readText()
            .replace("\r\n", "\n")
        val navSource = File("src/main/java/com/kunk/singbox/ui/components/AppNavBar.kt")
            .readText()
            .replace("\r\n", "\n")

        assertTrue(backdropBody.contains("Brush.verticalGradient("))
        assertFalse(backdropBody.contains("coolAmbient"))
        assertFalse(backdropBody.contains("warmAmbient"))
        assertFalse(source.contains("0xFF70CFFF"))
        assertFalse(source.contains("0xFFB78CFF"))
        assertTrue(source.contains("HazeStyle("))
        assertTrue(source.contains(".hazeEffect("))
        assertTrue(mainSource.contains(".layerBackdrop(liquidGlassContentBackdrop)"))
        assertTrue(navSource.contains("backdrop = backdrop"))
        assertFalse(mainSource.contains(".hazeSource("))
        assertFalse(navSource.extractFunctionBody("LiquidGlassRefractionOverlay").contains(".hazeEffect("))
    }

    @Test
    fun mainActivityCollectsSettingsWithoutReadingStateFlowValueInComposition() {
        val source = File("src/main/java/com/kunk/singbox/MainActivity.kt")
            .readText()
            .replace("\r\n", "\n")

        assertTrue(source.contains("settings.collectAsStateWithLifecycle()"))
        assertFalse(source.contains("initialValue = settingsRepository.settings.value"))
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

    private fun liquidGlassThemeSource(): String {
        return File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassTheme.kt")
            .readText()
            .replace("\r\n", "\n")
    }
}

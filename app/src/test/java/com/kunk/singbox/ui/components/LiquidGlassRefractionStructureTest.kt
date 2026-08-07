package com.kunk.singbox.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiquidGlassRefractionStructureTest {

    @Test
    fun bottomNavigationUsesCircularLensRefractionOverLiveBackdrop() {
        val nav = source("ui/components/AppNavBar.kt")
        val activity = source("MainActivity.kt")
        val build = File("build.gradle.kts").readText().replace("\r\n", "\n")
        val capsule = nav.extractFunctionBody("LiquidGlassCapsule")
        val overlay = nav.extractFunctionBody("LiquidGlassRefractionOverlay")
        val lens = nav.extractFunctionBody("liquidNavLens")
        val indicator = nav.extractFunctionBody("LiquidGlassSelectedIndicator")

        assertTrue(build.contains("implementation(\"io.github.kyant0:backdrop:2.0.0\")"))
        assertTrue(activity.contains("rememberLayerBackdrop()"))
        assertTrue(activity.contains(".layerBackdrop(liquidGlassContentBackdrop)"))
        assertTrue(nav.contains("LiquidGlassRefractionOverlay("))
        assertTrue(overlay.contains(".drawBackdrop("))
        assertTrue(overlay.contains("backdrop = backdrop"))
        assertTrue(overlay.contains("vibrancy()"))
        assertTrue(overlay.contains("blur(0.5.dp.toPx())"))
        assertTrue(overlay.contains("liquidNavLens("))
        assertTrue(overlay.contains("edgeRefraction = 16.dp.toPx()"))
        assertTrue(overlay.contains("centerRefraction = 6.dp.toPx()"))
        assertTrue(lens.contains("runtimeShaderEffect("))
        assertTrue(lens.contains("LIQUID_NAV_REFRACTION_SHADER"))
        assertTrue(lens.contains("setFloatUniform(\"size\", size.width, size.height)"))
        assertTrue(lens.contains("setFloatUniform(\"edgeRefraction\", edgeRefraction)"))
        assertTrue(lens.contains("setFloatUniform(\"centerRefraction\", centerRefraction)"))
        assertTrue(nav.contains("float centerWeight = 1.0 - smoothstep(0.25, 0.75, edgeProximity);"))
        assertTrue(nav.contains("float horizontalOffset = centerRefraction * centerWeight;"))
        assertTrue(nav.contains("float verticalWeight = smoothstep(0.0, 0.75, edgeProximity);"))
        assertFalse(nav.contains("sign(centeredY) * centerRefraction"))
        assertFalse(overlay.contains("lens("))
        assertTrue(overlay.indexOf("vibrancy()") < overlay.indexOf("blur(0.5.dp.toPx())"))
        assertTrue(overlay.indexOf("blur(0.5.dp.toPx())") < overlay.indexOf("liquidNavLens("))
        assertFalse(overlay.contains(".hazeEffect("))
        assertFalse(overlay.contains(".liquidGlassLensRefraction()"))
        assertFalse(activity.contains(".hazeSource("))
        assertFalse(activity.contains("forceBackdropUpdates"))

        assertTrue(capsule.contains(".hollowShadow("))
        assertFalse("底栏不能在实时折射层下重复绘制一整层玻璃材质", capsule.contains(".liquidGlassPanel("))
        assertTrue(overlay.contains("MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.20f)"))
        assertTrue(overlay.contains(".liquidGlassMaterial("))
        assertFalse(overlay.contains("0.38f"))

        assertTrue(nav.contains("liquidGlassSelectedIndicatorSize = 44.dp"))
        assertTrue(indicator.contains(".size(liquidGlassSelectedIndicatorSize)"))
        assertTrue(indicator.contains("shape = CircleShape"))
        assertFalse(indicator.contains(".width("))
        assertFalse(indicator.contains(".height("))
        assertTrue(indicator.contains(".liquidGlassMaterial("))
        assertTrue(nav.contains("selectedIconColor = MaterialTheme.colorScheme.primary"))
        assertFalse(nav.contains("liquidGlassSelectedIndicatorWidth"))

        val refractionIndex = capsule.indexOf("LiquidGlassRefractionOverlay(")
        val contentInsetIndex = capsule.indexOf(".padding(4.dp)")
        assertTrue("折射层必须覆盖完整胶囊，不能被内容内边距裁小", refractionIndex in 0..<contentInsetIndex)

        assertFalse(File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassRefraction.kt").exists())
        assertFalse(File("src/main/java/com/kunk/singbox/ui/theme/LiquidGlassRefractionApi33.kt").exists())
    }

    @Test
    fun bottomNavigationSelectionSlidesWithoutTabPageOverlap() {
        val nav = source("ui/components/AppNavBar.kt")
        val navigation = source("ui/navigation/AppNavigation.kt")
        val indicator = nav.extractFunctionBody("LiquidGlassSelectedIndicator")
        val indicatorOffset = nav.extractFunctionBody("liquidGlassSelectedIndicatorOffset")
        val navItem = nav.extractFunctionBody("LiquidGlassNavItem")
        val metrics = nav.extractFunctionBody("rememberLiquidGlassNavMetrics")
        val navigateToTab = nav.extractFunctionBody("navigateToTab")
        val appNavigation = navigation.extractFunctionBody("AppNavigation")
        val topLevelPage = navigation.extractFunctionBody("TopLevelPage")

        assertTrue(nav.contains("liquidGlassSelectedIndicatorSize = 44.dp"))
        assertTrue(indicator.contains(".size(liquidGlassSelectedIndicatorSize)"))
        assertTrue(indicator.contains("shape = CircleShape"))
        assertTrue(
            indicatorOffset.contains(
                "((slotWidth - liquidGlassSelectedIndicatorSize) / 2f)"
            )
        )
        assertTrue(indicator.contains("tween(durationMillis = 220"))
        assertFalse(indicator.contains("spring("))
        assertTrue(metrics.contains("tween(durationMillis = 90"))
        assertFalse(metrics.contains("spring("))
        assertTrue(navItem.contains(".height(liquidGlassNavItemMinTouchSize)"))

        assertTrue(navigation.contains("const val NAV_ANIMATION_DURATION = 450"))
        assertFalse(navigation.contains("TAB_NAV_ANIMATION_DURATION"))
        assertFalse(navigation.contains("TAB_NAV_SLIDE_DISTANCE_DIVISOR"))
        assertFalse(appNavigation.contains("tabSlideSpec"))
        assertTrue(appNavigation.contains("animationSpec = slideSpec"))
        assertTrue(appNavigation.contains("initialOffsetX = { it / 8 }"))
        assertTrue(appNavigation.contains("initialOffsetX = { -it / 8 }"))
        assertTrue(appNavigation.contains("targetOffsetX = { -it / 8 }"))
        assertTrue(appNavigation.contains("targetOffsetX = { it / 8 }"))
        assertTrue(appNavigation.contains(") + fadeIn(animationSpec = fadeSpec)"))
        assertTrue(appNavigation.contains(") + fadeOut(animationSpec = fadeSpec)"))
        assertFalse(appNavigation.contains("EnterTransition.None"))
        assertFalse(appNavigation.contains("ExitTransition.None"))
        assertTrue(topLevelPage.contains(".liquidGlassBackdrop()"))
        assertTrue(appNavigation.countOccurrences("TopLevelPage {") == 4)
        assertTrue(navigateToTab.contains("restoreState = true"))
        assertFalse(navigateToTab.contains("delay("))
        assertFalse(navigateToTab.contains("launch("))
    }

    private fun String.countOccurrences(token: String): Int = windowed(token.length).count { it == token }

    private fun String.extractFunctionBody(functionName: String): String {
        val start = listOf(
            "fun Modifier.$functionName",
            "fun BoxScope.$functionName",
            "fun BackdropEffectScope.$functionName",
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

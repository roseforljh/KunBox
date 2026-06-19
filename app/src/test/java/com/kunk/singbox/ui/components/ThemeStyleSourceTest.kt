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

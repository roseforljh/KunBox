package com.kunk.singbox.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiquidGlassHierarchyStructureTest {

    @Test
    fun componentsUseNeutralEmbossedOpticsWhileBottomNavigationStaysCrystal() {
        val theme = source("ui/theme/LiquidGlassTheme.kt")
        val panel = theme.extractFunctionBody("liquidGlassPanel")
        val material = theme.extractFunctionBody("liquidGlassMaterial")
        val crystal = theme.extractFunctionBody("liquidGlassCrystalSurface")
        val brush = theme.extractFunctionBody("liquidGlassPanelBrush")
        val nav = source("ui/components/AppNavBar.kt")
        val overlay = nav.extractFunctionBody("LiquidGlassRefractionOverlay")
        val indicator = nav.extractFunctionBody("LiquidGlassSelectedIndicator")
        val profilesScreen = source("ui/screens/ProfilesScreen.kt")
        val commonDialogs = source("ui/components/CommonDialogs.kt")
        val dialogOption = commonDialogs.extractFunctionBody("dialogOptionPanel")
        val profileCard = source("ui/components/ProfileCard.kt")
        val nodeCard = source("ui/components/NodeCard.kt")

        assertTrue(panel.contains("val controlSurfaceColor = if (isDark) Color.Black else Color.White"))
        assertTrue(panel.contains("val edgeShadowColor = if (isDark) Color.White else Color.Black"))
        assertTrue(panel.contains("accented = true"))
        assertTrue(material.contains("accented: Boolean = true"))
        assertTrue(material.contains("if (selected && !accented)"))
        assertFalse(material.contains("Primary.copy("))
        assertTrue(crystal.contains("val highlightColor = Color.White"))
        assertTrue(crystal.contains("embossedRimBrush"))
        assertTrue(crystal.contains("if (accented)"))
        assertTrue(crystal.contains("if (accented) BlendMode.SrcOver else BlendMode.Screen"))
        assertTrue(brush.contains("accented && selected && isDark"))
        assertTrue(brush.contains("Color.Black.copy(alpha = 0.96f)"))
        assertTrue(brush.contains("Color.White.copy(alpha = 0.96f)"))
        assertFalse(brush.contains("Primary.copy("))
        assertFalse(brush.contains("surface.copy(alpha = 0.08f)"))
        assertTrue(overlay.contains("accented = false"))
        assertFalse(overlay.contains("highlight = null"))
        assertTrue(indicator.contains("selected = true"))
        assertTrue(indicator.contains("accented = false"))
        assertFalse(indicator.contains("Color.White.copy(alpha = 0.10f)"))
        assertFalse(indicator.contains("Color.Black.copy(alpha = 0.10f)"))
        assertTrue(dialogOption.contains("selected = isSelected"))
        assertTrue(profileCard.contains("liquidGlassMaterial(shape = shape, selected = selected)"))
        assertTrue(nodeCard.countOccurrences("selected = isSelected") >= 2)
        assertFalse(profilesScreen.contains("this.alpha = alpha"))
    }

    @Test
    fun dashboardSeparatesTrafficAndQuickActionSurfaces() {
        val dashboard = source("ui/screens/DashboardScreen.kt")
        val infoCard = dashboard.indexOf("InfoCard(")
        val sectionGap = dashboard.indexOf("Spacer(modifier = Modifier.height(16.dp))", infoCard)
        val quickActions = dashboard.indexOf("// Quick Actions Card", infoCard)

        assertTrue(infoCard >= 0)
        assertTrue(sectionGap in (infoCard + 1)..<quickActions)
    }

    @Test
    fun dialogShellUsesASeparateGlassDepthFromItsControls() {
        val theme = source("ui/theme/LiquidGlassTheme.kt")
        val panel = theme.extractFunctionBody("liquidGlassPanel")
        val dialogPanel = theme.extractFunctionBody("liquidGlassDialogPanel")
        val profileDialogs = source("ui/screens/ProfilesScreenDialogs.kt")
        val nodePicker = source("ui/screens/NodePickerPage.kt")
        val exportDialogs = source("ui/components/ExportImportDialogs.kt")
        val selectProfileDialog = source("ui/components/SelectProfileDialog.kt")
        val connectionInfo = source("ui/screens/ConnectionInfoScreen.kt")
        val ruleSets = source("ui/screens/RuleSetsScreen.kt")

        assertTrue(panel.contains("dialog: Boolean = false"))
        assertTrue(panel.contains("blur(0.75.dp.toPx() * opticsScale)"))
        assertTrue(panel.contains("refractionHeight = 48.dp.toPx() * opticsScale"))
        assertTrue(panel.contains("refractionAmount = 28.dp.toPx() * opticsScale"))
        assertTrue(dialogPanel.contains("dialog = true"))
        assertTrue(profileDialogs.extractFunctionBody("profileDialogPanel").contains("liquidGlassDialogPanel("))
        assertTrue(nodePicker.extractFunctionBody("NodePickerPage").contains("FullScreenDialogPage("))
        assertTrue(exportDialogs.extractFunctionBody("ExportImportCard").contains("liquidGlassDialogPanel("))
        assertTrue(exportDialogs.countOccurrences("dialog = true") >= 4)
        assertTrue(selectProfileDialog.extractFunctionBody("SelectProfileDialog").contains("LiquidGlassDialogEffect()"))
        assertTrue(connectionInfo.extractFunctionBody("ConnectionInfoScreen").contains("LiquidGlassDialogEffect()"))
        assertTrue(ruleSets.extractFunctionBody("RuleSetsScreen").contains("LiquidGlassDialogEffect()"))
    }

    @Test
    fun nodesProfilesAndSettingsUseTheFloatingTopGradientLayer() {
        val layout = source("ui/components/FullScreenDialogPage.kt")
        val mainLayout = layout.extractFunctionBody("FloatingMainPageLayout")
        val nodes = source("ui/screens/NodesScreen.kt")
        val profiles = source("ui/screens/ProfilesScreen.kt")
        val settings = source("ui/screens/SettingsScreen.kt")
        val dashboard = source("ui/screens/DashboardScreen.kt")

        val contentLayer = mainLayout.indexOf("content(contentTopPadding)")
        val gradientLayer = mainLayout.indexOf("FloatingPageTopGradient(")
        val headerLayer = mainLayout.indexOf("FloatingMainPageHeader(")
        assertTrue(contentLayer in 0 until gradientLayer)
        assertTrue(gradientLayer in 0 until headerLayer)
        assertTrue(mainLayout.contains("supportingContentHeight"))

        assertTrue(nodes.contains("FloatingMainPageLayout("))
        assertTrue(nodes.contains("top = contentTopPadding + 12.dp"))
        assertTrue(profiles.contains("FloatingMainPageLayout("))
        assertTrue(profiles.contains("top = contentTopPadding + 16.dp"))
        assertTrue(settings.contains("FloatingMainPageLayout("))
        assertTrue(settings.contains("top = contentTopPadding + 16.dp"))
        assertFalse(dashboard.contains("FloatingMainPageLayout("))
    }

    private fun String.countOccurrences(token: String): Int = windowed(token.length).count { it == token }

    private fun String.extractFunctionBody(functionName: String): String {
        val start = listOf(
            "fun Modifier.$functionName",
            "fun BoxScope.$functionName",
            "fun $functionName"
        ).map(::indexOf).firstOrNull { it >= 0 } ?: -1
        assertTrue("缺少函数 $functionName", start >= 0)
        val bodyStart = indexOf('{', start)
        assertTrue("函数 $functionName 缺少函数体", bodyStart >= 0)

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
        throw AssertionError("函数 $functionName 的函数体未闭合")
    }

    private fun source(path: String): String {
        return File("src/main/java/com/kunk/singbox/$path")
            .readText()
            .replace("\r\n", "\n")
    }
}

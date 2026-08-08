package com.kunk.singbox.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NodeCardLiquidGlassShadowTest {

    @Test
    fun nodeListCardsDoNotUseDelayedLiquidGlassShadow() {
        val nodeCard = File("src/main/java/com/kunk/singbox/ui/components/NodeCard.kt")
            .readText()
            .replace("\r\n", "\n")

        assertEquals(2, Regex("""shadowElevation = 0\.dp""").findAll(nodeCard).count())
        listOf(
            ".liquidGlassPanel(shape = shape, selected = isSelected)",
            ".liquidGlassPanel(shape = shape, selected = isSelected, shadowElevation = 8.dp)"
        ).forEach { token ->
            assertFalse(nodeCard.contains(token))
        }
    }

    @Test
    fun automaticSelectionUsesCurrentNodeCardLayoutAndStaysFirst() {
        val nodesScreen = File("src/main/java/com/kunk/singbox/ui/screens/NodesScreen.kt")
            .readText()
            .replace("\r\n", "\n")
        val automaticItemStart = nodesScreen.indexOf("key = \"automatic-selection\"")
        val regularItemsStart = nodesScreen.indexOf("itemsIndexed(", startIndex = automaticItemStart)
        val automaticItem = nodesScreen.substring(automaticItemStart, regularItemsStart)

        assertTrue(automaticItemStart >= 0)
        assertTrue(regularItemsStart > automaticItemStart)
        assertTrue(automaticItem.contains("NodeCard("))
        assertTrue(automaticItem.contains("NodeGridCard("))
        assertTrue(automaticItem.contains("showLatency = false"))
        assertTrue(automaticItem.contains("showActions = false"))
        assertFalse(automaticItem.contains("GridItemSpan"))
    }

    @Test
    fun selectedGridNodeUsesBreathingGreenDotWithoutCoveringItsName() {
        val nodeCard = File("src/main/java/com/kunk/singbox/ui/components/NodeCard.kt")
            .readText()
            .replace("\r\n", "\n")
        val listCard = nodeCard
            .substringAfter("fun NodeCard(")
            .substringBefore("private fun BreathingGreenDot(")
        val gridCard = nodeCard
            .substringAfter("fun NodeGridCard(")
            .substringBeforeLast("\n}")
        val pulseIndicator = nodeCard
            .substringAfter("internal fun SelectedPulseIndicator(")
            .substringBefore("\n}\n\n@Suppress", missingDelimiterValue = "")
        val breathingDot = nodeCard
            .substringAfter("private fun BreathingGreenDot(")
            .substringBefore("\n}\n\n@Composable\ninternal fun SelectedPulseIndicator", missingDelimiterValue = "")

        assertTrue(gridCard.contains("verticalAlignment = Alignment.Top"))
        assertTrue(gridCard.contains("modifier = Modifier.weight(1f)"))
        assertTrue(gridCard.contains("Spacer(modifier = Modifier.width(6.dp))"))
        assertTrue(gridCard.contains("SelectedPulseIndicator("))
        assertTrue(gridCard.contains("selected = isSelected"))
        assertTrue(gridCard.contains("slotSize = 16.dp"))
        assertTrue(listCard.contains("animationLabel = \"node_list_selected\""))
        assertTrue(pulseIndicator.isNotEmpty())
        assertTrue(pulseIndicator.contains("AnimatedVisibility("))
        assertTrue(pulseIndicator.contains("visible = selected"))
        assertTrue(pulseIndicator.contains("fadeIn("))
        assertTrue(pulseIndicator.contains("scaleIn("))
        assertTrue(pulseIndicator.contains("fadeOut("))
        assertTrue(pulseIndicator.contains("scaleOut("))
        assertTrue(pulseIndicator.contains("initialScale = 0.55f"))
        assertTrue(pulseIndicator.contains("targetScale = 0.55f"))
        assertTrue(breathingDot.contains("rememberInfiniteTransition("))
        assertTrue(breathingDot.contains("RepeatMode.Reverse"))
        assertTrue(breathingDot.contains("NodeSelectedPulseGreen"))
        assertTrue(nodeCard.contains("Color(0xFF22C55E)"))
    }
}

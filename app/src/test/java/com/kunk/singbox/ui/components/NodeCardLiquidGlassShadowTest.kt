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
}

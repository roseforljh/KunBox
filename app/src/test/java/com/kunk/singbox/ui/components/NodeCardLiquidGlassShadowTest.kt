package com.kunk.singbox.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}

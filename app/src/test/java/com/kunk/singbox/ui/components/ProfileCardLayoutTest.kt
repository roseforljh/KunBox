package com.kunk.singbox.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileCardLayoutTest {

    @Test
    fun profileCardKeepsDisabledAndDnsBadgesVisible() {
        val source = File("src/main/java/com/kunk/singbox/ui/components/ProfileCard.kt")
            .readNormalizedText()

        assertTrue(source.contains("Column(modifier = Modifier.weight(1f))"))
        assertTrue(source.contains("text = formatLastUpdated(lastUpdated)"))
        assertTrue(source.contains("if (!isEnabled)"))
        assertTrue(source.contains("text = stringResource(R.string.common_disabled)"))
        assertTrue(source.contains("softWrap = false"))
        assertTrue(source.contains("maxLines = 1"))
        assertTrue(source.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(source.contains("modifier = Modifier.weight(1f)"))
        assertTrue(
            source.contains(
                "Spacer(modifier = Modifier.width(12.dp))\n\n        Box(modifier = Modifier.wrapContentSize"
            )
        )
    }
}

private fun File.readNormalizedText(): String = readText().replace("\r\n", "\n")

package com.kunk.singbox.database.entity

import com.kunk.singbox.model.NodeUi
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeEntityTest {

    @Test
    fun nodeTagsRoundTripShouldPreserveCommasAndQuotes() {
        val node = NodeUi(
            id = "node-1",
            name = "example",
            protocol = "vless",
            group = "Default",
            sourceProfileId = "profile-1",
            tags = listOf("TLS", "tag,with,comma", "quote\"tag")
        )

        val restored = NodeEntity.fromUiModel(node).toUiModel()

        assertEquals(node.tags, restored.tags)
    }
}

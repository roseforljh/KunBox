package com.kunk.singbox.service.manager

import com.kunk.singbox.repository.RuntimeNodeRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTrafficAttributorTest {
    private val front = RuntimeNodeRef("front-id", "US-A")
    private val exit = RuntimeNodeRef("exit-id", "New-HTTP", meteredProtected = true)
    private val mappings = mapOf("front-tag" to front, "exit-tag" to exit)

    @Test
    fun chainTrafficIsAttributedToFrontAndExitNodes() {
        val records = ConnectionTrafficAttributor().apply(
            reset = false,
            events = listOf(
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_NEW,
                    id = "connection-1",
                    tags = listOf("front-tag", "exit-tag"),
                    uploadDelta = 10L,
                    downloadDelta = 20L
                )
            ),
            runtimeMappings = mappings
        )

        assertEquals(setOf(front, exit), records.single().targets)
        assertEquals(10L, records.single().uploadDelta)
        assertEquals(20L, records.single().downloadDelta)
    }

    @Test
    fun laterDeltaKeepsOriginalConnectionOwnershipWithoutRepeatingTags() {
        val attributor = ConnectionTrafficAttributor()
        attributor.apply(
            reset = false,
            events = listOf(
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_NEW,
                    id = "connection-1",
                    tags = listOf("exit-tag")
                )
            ),
            runtimeMappings = mappings
        )

        val records = attributor.apply(
            reset = false,
            events = listOf(
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_UPDATE,
                    id = "connection-1",
                    downloadDelta = 99L
                )
            ),
            runtimeMappings = mappings
        )

        assertEquals(setOf(exit), records.single().targets)
    }

    @Test
    fun laterUpdateCanResolveProtectedTargetWithoutRepeatingTags() {
        val attributor = ConnectionTrafficAttributor()
        attributor.resolveTargets(
            event = ConnectionTrafficEventData(
                type = ConnectionTrafficAttributor.EVENT_NEW,
                id = "connection-1",
                tags = listOf("exit-tag")
            ),
            runtimeMappings = mappings
        )

        val targets = attributor.resolveTargets(
            event = ConnectionTrafficEventData(
                type = ConnectionTrafficAttributor.EVENT_UPDATE,
                id = "connection-1"
            ),
            runtimeMappings = mappings
        )

        assertEquals(setOf(exit), targets)
    }

    @Test
    fun unknownConnectionProducesUnattributedRecord() {
        val records = ConnectionTrafficAttributor().apply(
            reset = false,
            events = listOf(
                ConnectionTrafficEventData(
                    type = ConnectionTrafficAttributor.EVENT_UPDATE,
                    id = "unknown",
                    tags = listOf("missing-tag"),
                    uploadDelta = 1L
                )
            ),
            runtimeMappings = mappings
        )

        assertTrue(records.single().targets.isEmpty())
    }
}

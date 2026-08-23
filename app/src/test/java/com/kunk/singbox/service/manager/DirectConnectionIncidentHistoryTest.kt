package com.kunk.singbox.service.manager

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectConnectionIncidentHistoryTest {
    @Test
    fun `route rule semantic keeps unknown instead of guessing`() {
        assertEquals("user_app_rule", ConnectionTrafficEventReader.classifyRouteRuleSemantic("package_name=x"))
        assertEquals("user_rule_set", ConnectionTrafficEventReader.classifyRouteRuleSemantic("rule_set=ads"))
        assertEquals("unknown", ConnectionTrafficEventReader.classifyRouteRuleSemantic("mystery=value"))
        assertEquals("unknown", ConnectionTrafficEventReader.classifyRouteRuleSemantic(null))
    }

    @Test
    fun `direct incidents are recorded once without plaintext targets`() {
        val directory = Files.createTempDirectory("direct-incidents").toFile()
        val file = directory.resolve("incidents.jsonl")
        try {
            val history = DirectConnectionIncidentHistory(file, maxSnapshots = 4)
            val event = ConnectionTrafficEventData(
                type = ConnectionTrafficAttributor.EVENT_NEW,
                id = "connection-1",
                outbound = "direct",
                routeRule = "package_name=com.browser",
                destination = "203.0.113.1:443",
                domain = "example.test",
                routeRuleSemantic = "user_app_rule",
                attributionStatus = "attributed"
            )
            assertEquals(1, history.recordNew(listOf(event)).size)
            assertEquals(0, history.recordNew(listOf(event)).size)
            val stored = history.read().single()
            assertEquals("user_app_rule", stored.routeRuleSemantic)
            assertNotEquals(event.destination, stored.destinationHash)
            assertNotEquals(event.domain, stored.domainHash)
            assertNull(stored.packageNames.firstOrNull())
            val raw = file.readText()
            assert(!raw.contains("203.0.113.1"))
            assert(!raw.contains("example.test"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `non direct events are ignored`() {
        val directory = Files.createTempDirectory("direct-incidents-ignore").toFile()
        try {
            val history = DirectConnectionIncidentHistory(directory.resolve("incidents.jsonl"))
            assertEquals(
                0,
                history.recordNew(
                    listOf(
                        ConnectionTrafficEventData(
                            type = ConnectionTrafficAttributor.EVENT_NEW,
                            id = "connection-2",
                            outbound = "proxy"
                        )
                    )
                ).size
            )
        } finally {
            directory.deleteRecursively()
        }
    }
}

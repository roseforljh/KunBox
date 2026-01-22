package com.kunk.singbox.repository

import org.junit.Assert.*
import org.junit.Test

class ConfigRepositoryTest {

    @Test
    fun testStableNodeIdConsistency() {
        val profileId = "profile-123"
        val outboundTag = "node-abc"

        val id1 = ConfigRepository.stableNodeId(profileId, outboundTag)
        val id2 = ConfigRepository.stableNodeId(profileId, outboundTag)

        assertEquals(id1, id2)
        assertTrue(id1.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun testStableNodeIdDifferentInputs() {
        val id1 = ConfigRepository.stableNodeId("profile-1", "node-a")
        val id2 = ConfigRepository.stableNodeId("profile-1", "node-b")
        val id3 = ConfigRepository.stableNodeId("profile-2", "node-a")

        assertNotEquals(id1, id2)
        assertNotEquals(id1, id3)
        assertNotEquals(id2, id3)
    }

    @Test
    fun testStableNodeIdSpecialCharacters() {
        val id = ConfigRepository.stableNodeId("profile/with/slashes", "node#with#hash")

        assertNotNull(id)
        assertTrue(id.isNotBlank())
    }

    @Test
    fun testStableNodeIdEmptyInputs() {
        val id1 = ConfigRepository.stableNodeId("", "node")
        val id2 = ConfigRepository.stableNodeId("profile", "")
        val id3 = ConfigRepository.stableNodeId("", "")

        assertNotNull(id1)
        assertNotNull(id2)
        assertNotNull(id3)
        assertNotEquals(id1, id2)
    }

    @Test
    fun testStableNodeIdUnicodeCharacters() {
        val id = ConfigRepository.stableNodeId("日本配置", "香港节点-01")

        assertNotNull(id)
        assertTrue(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))

        val id2 = ConfigRepository.stableNodeId("日本配置", "香港节点-01")
        assertEquals(id, id2)
    }

    @Test
    fun testStableNodeIdCacheEfficiency() {
        val profileId = "cache-test-profile"
        val outboundTag = "cache-test-node"

        val startTime = System.nanoTime()
        repeat(10000) {
            ConfigRepository.stableNodeId(profileId, outboundTag)
        }
        val duration = System.nanoTime() - startTime

        assertTrue(duration < 100_000_000L)
    }
}

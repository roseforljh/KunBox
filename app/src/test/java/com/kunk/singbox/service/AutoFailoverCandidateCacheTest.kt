package com.kunk.singbox.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoFailoverCandidateCacheTest {

    @Test
    fun keepsFastestNonCurrentHealthyCandidate() {
        val cache = AutoFailoverCandidateCache(maxAgeMs = 60_000L)

        cache.update(
            currentTag = "node-a",
            delays = mapOf("node-a" to 120, "node-b" to 80, "node-c" to 100),
            nowMs = 1_000L
        )

        assertEquals("node-b", cache.resolve(currentTag = "node-a", nowMs = 2_000L))
    }

    @Test
    fun dropsExpiredCandidate() {
        val cache = AutoFailoverCandidateCache(maxAgeMs = 60_000L)
        cache.update("node-a", mapOf("node-b" to 80), nowMs = 1_000L)

        assertNull(cache.resolve(currentTag = "node-a", nowMs = 70_001L))
    }

    @Test
    fun warmsFromSavedLatencies() {
        val cache = AutoFailoverCandidateCache(maxAgeMs = 60_000L)

        cache.warmFromSavedLatencies(
            currentTag = "node-a",
            tagLatencies = mapOf("node-a" to 50L, "node-b" to 90L, "node-c" to -1L),
            nowMs = 1_000L
        )

        assertEquals("node-b", cache.resolve(currentTag = "node-a", nowMs = 2_000L))
    }
}

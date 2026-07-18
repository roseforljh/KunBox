package com.kunk.singbox.utils.dns

import org.junit.Assert.*
import org.junit.Test

/**
 * DnsResolveStore.ResolvedEntry 单元测试
 */
class DnsResolveStoreTest {

    // ==================== ResolvedEntry TTL ====================

    @Test
    fun testResolvedEntry_notExpired() {
        val entry = DnsResolveStore.ResolvedEntry(
            ip = "1.2.3.4",
            resolvedAt = System.currentTimeMillis(),
            ttlSeconds = 3600,
            source = "doh"
        )
        assertFalse(entry.isExpired())
        assertTrue(entry.remainingSeconds() > 3500)
    }

    @Test
    fun testResolvedEntry_expired() {
        val entry = DnsResolveStore.ResolvedEntry(
            ip = "1.2.3.4",
            resolvedAt = System.currentTimeMillis() - 4000_000, // 4000 seconds ago
            ttlSeconds = 3600,
            source = "doh"
        )
        assertTrue(entry.isExpired())
        assertEquals(0, entry.remainingSeconds())
    }

    @Test
    fun testResolvedEntry_almostExpired() {
        val entry = DnsResolveStore.ResolvedEntry(
            ip = "1.2.3.4",
            resolvedAt = System.currentTimeMillis() - 3500_000, // 3500 seconds ago
            ttlSeconds = 3600,
            source = "doh"
        )
        assertFalse(entry.isExpired())
        assertTrue(entry.remainingSeconds() in 90..110) // roughly 100 seconds left
    }

    @Test
    fun testResolvedEntry_defaultTtl() {
        val entry = DnsResolveStore.ResolvedEntry(
            ip = "1.2.3.4",
            resolvedAt = System.currentTimeMillis()
        )
        assertEquals(DnsResolveStore.DEFAULT_TTL_SECONDS, entry.ttlSeconds)
        assertEquals("doh", entry.source)
    }

    // ==================== 常量测试 ====================

    @Test
    fun testDefaultTtl() {
        assertEquals(14400, DnsResolveStore.DEFAULT_TTL_SECONDS)
    }
}

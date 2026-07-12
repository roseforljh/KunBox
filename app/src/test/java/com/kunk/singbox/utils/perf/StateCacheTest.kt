package com.kunk.singbox.utils.perf

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateCacheTest {

    @Test
    fun networkCacheExpiresAfterOneSecond() {
        assertTrue(StateCache.isNetworkCacheFresh(cachedAtMs = 1_000L, nowMs = 1_999L))
        assertFalse(StateCache.isNetworkCacheFresh(cachedAtMs = 1_000L, nowMs = 2_000L))
        assertFalse(StateCache.isNetworkCacheFresh(cachedAtMs = 1_000L, nowMs = 999L))
    }
}

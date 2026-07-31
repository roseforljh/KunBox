package com.kunk.singbox.service.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficMonitorPolicyTest {

    @Test
    fun coreTotalsProduceElapsedTimeBasedSpeed() {
        val monitor = TrafficMonitor()

        assertEquals(TrafficMonitor.TrafficSnapshot.ZERO, monitor.updateTotals(1_000L, 2_000L, 1_000L))
        assertEquals(
            TrafficMonitor.TrafficSnapshot(
                uploadSpeed = 1_000L,
                downloadSpeed = 2_000L,
                uploadDelta = 3_000L,
                downloadDelta = 6_000L
            ),
            monitor.updateTotals(4_000L, 8_000L, 4_000L)
        )
    }

    @Test
    fun invalidOrRolledBackCountersClearStaleSpeed() {
        val monitor = TrafficMonitor()
        monitor.updateTotals(10_000L, 20_000L, 1_000L)
        monitor.updateTotals(13_000L, 26_000L, 4_000L)

        assertEquals(TrafficMonitor.TrafficSnapshot.ZERO, monitor.updateTotals(500L, 600L, 7_000L))
        assertEquals(TrafficMonitor.TrafficSnapshot.ZERO, monitor.updateTotals(-1L, -1L, 7_000L))
        assertEquals(TrafficMonitor.TrafficSnapshot.ZERO, monitor.updateTotals(500L, 600L, 10_000L))
    }

    @Test
    fun notificationTrafficDoesNotUseWholeApplicationUid() {
        val source = File("src/main/java/com/kunk/singbox/service/network/TrafficMonitor.kt").readText()

        assertFalse(source.contains("TrafficStats"))
        assertFalse(source.contains("getUidTxBytes"))
        assertFalse(source.contains("getUidRxBytes"))
    }

    @Test
    fun idleTrafficOnlyUpdatesStatistics() {
        val source = File("src/main/java/com/kunk/singbox/service/network/TrafficMonitor.kt").readText()

        assertTrue(source.contains("data class TrafficSnapshot"))
        assertFalse(source.contains("onTrafficStall"))
        assertFalse(source.contains("onProxyIdle"))
        assertFalse(source.contains("STALL_"))
        assertFalse(source.contains("PROXY_IDLE"))
    }

    @Test
    fun serviceDoesNotRecoverFromLowTrafficOrIdle() {
        val source = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt").readText()

        assertFalse(source.contains("traffic_stall"))
        assertFalse(source.contains("Proxy idle"))
    }
}

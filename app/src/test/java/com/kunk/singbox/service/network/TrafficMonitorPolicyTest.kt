package com.kunk.singbox.service.network

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficMonitorPolicyTest {

    @Test
    fun idleTrafficOnlyUpdatesStatistics() {
        val source = File("src/main/java/com/kunk/singbox/service/network/TrafficMonitor.kt").readText()

        assertTrue(source.contains("fun onTrafficUpdate(snapshot: TrafficSnapshot)"))
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

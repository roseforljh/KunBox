package com.kunk.singbox.service.manager

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkSwitchManagerTest {

    @Test
    fun defaultNetworkChangeTriggersExactlyOneCoreReset() {
        val source = File("src/main/java/com/kunk/singbox/service/manager/NetworkSwitchManager.kt").readText()

        assertEquals(1, source.windowed("cb.resetCoreNetwork()".length).count { it == "cb.resetCoreNetwork()" })
        assertTrue(source.contains("if (networkChanged)"))
    }

    @Test
    fun networkSwitchDoesNotUseValidationOrPublicDnsProbes() {
        val source = File("src/main/java/com/kunk/singbox/service/manager/NetworkSwitchManager.kt").readText()

        assertFalse(source.contains("NET_CAPABILITY_VALIDATED"))
        assertFalse(source.contains("1.1.1.1"))
        assertFalse(source.contains("8.8.8.8"))
        assertFalse(source.contains("223.5.5.5"))
    }
}

package com.kunk.singbox.service.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ResourceCleanupSourceTest {
    @Test
    fun cleanupPathsLogIgnoredCloseAndUnregisterFailures() {
        val shutdown = File("src/main/java/com/kunk/singbox/service/manager/ShutdownManager.kt").readText()
        val platform = File("src/main/java/com/kunk/singbox/service/manager/PlatformInterfaceImpl.kt").readText()
        val tun = File("src/main/java/com/kunk/singbox/service/tun/VpnTunManager.kt").readText()
        val tcpPing = File("src/main/java/com/kunk/singbox/utils/TcpPing.kt").readText()

        listOf(shutdown, platform, tun, tcpPing).forEach { source ->
            assertFalse(source.contains("catch (_: Exception) {}"))
            assertFalse(source.contains("catch (_: Exception) { }"))
        }
        assertTrue(shutdown.contains("Failed to wait for startup job before shutdown"))
        assertTrue(shutdown.contains("Failed to close default interface monitor"))
        assertTrue(shutdown.contains("Failed to close VPN interface"))
        assertTrue(platform.contains("Failed to unregister default network callback"))
        assertTrue(platform.contains("Failed to unregister VPN network callback"))
        assertTrue(tun.contains("Failed to close invalid VPN interface"))
        assertTrue(tcpPing.contains("Failed to close TCP probe socket"))
    }
}

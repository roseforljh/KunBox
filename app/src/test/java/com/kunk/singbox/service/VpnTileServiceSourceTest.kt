package com.kunk.singbox.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VpnTileServiceSourceTest {

    @Test
    fun tileUpdateUsesServiceLivenessBeforeClearingPersistedActiveState() {
        val source = File("src/main/java/com/kunk/singbox/service/VpnTileService.kt").readText()

        assertTrue(source.contains("shouldClearUnavailablePersistedActive("))
        assertTrue(source.contains("serviceActuallyRunning = serviceBound && remoteService != null"))
        assertFalse(
            source.contains(
                "if (persistedActive && coreMode == VpnStateStore.CoreMode.VPN && !hasSystemVpnTransport())"
            )
        )
    }
}

package com.kunk.singbox.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VpnTileServiceStateTest {

    @Test
    fun unavailableTileStateKeepsLocalStartingGrace() {
        assertFalse(
            VpnTileService.shouldClearUnavailablePending(
                pending = "starting",
                isStartingSequence = true,
                serviceActuallyRunning = false,
                hasVpnTransport = false
            )
        )
    }

    @Test
    fun unavailableTileStateClearsStaleStarting() {
        assertTrue(
            VpnTileService.shouldClearUnavailablePending(
                pending = "starting",
                isStartingSequence = false,
                serviceActuallyRunning = false,
                hasVpnTransport = false
            )
        )
    }

    @Test
    fun unavailableTileStateClearsStaleStopping() {
        assertTrue(
            VpnTileService.shouldClearUnavailablePending(
                pending = "stopping",
                isStartingSequence = false,
                serviceActuallyRunning = false,
                hasVpnTransport = false
            )
        )
    }

    @Test
    fun unavailableTileStateKeepsWhenServiceIsRunning() {
        assertFalse(
            VpnTileService.shouldClearUnavailablePending(
                pending = "starting",
                isStartingSequence = false,
                serviceActuallyRunning = true,
                hasVpnTransport = false
            )
        )
    }

    @Test
    fun unavailableTileStateKeepsWhenVpnTransportExists() {
        assertFalse(
            VpnTileService.shouldClearUnavailablePending(
                pending = "starting",
                isStartingSequence = false,
                serviceActuallyRunning = false,
                hasVpnTransport = true
            )
        )
    }

    @Test
    fun unavailableTileStateKeepsInactivePersistedState() {
        assertFalse(
            VpnTileService.shouldClearUnavailablePersistedActive(
                pending = "",
                persistedActive = false,
                serviceActuallyRunning = false,
                hasVpnTransport = false
            )
        )
    }

    @Test
    fun unavailableTileStateClearsPersistedActiveWhenAllowed() {
        assertTrue(
            VpnTileService.shouldClearUnavailablePersistedActive(
                pending = "",
                persistedActive = true,
                serviceActuallyRunning = false,
                hasVpnTransport = false
            )
        )
    }

    @Test
    fun unavailableTileStateKeepsPersistedActiveDuringStarting() {
        assertFalse(
            VpnTileService.shouldClearUnavailablePersistedActive(
                pending = "starting",
                persistedActive = true,
                serviceActuallyRunning = false,
                hasVpnTransport = false
            )
        )
    }

    @Test
    fun vpnPermissionIsOnlyNeededWhenStartingTunMode() {
        assertFalse(
            VpnTileService.shouldRequestVpnPermissionBeforeStartForTest(
                isActive = true,
                tunEnabled = true,
                vpnPrepareRequired = true
            )
        )
        assertFalse(
            VpnTileService.shouldRequestVpnPermissionBeforeStartForTest(
                isActive = false,
                tunEnabled = false,
                vpnPrepareRequired = true
            )
        )
        assertTrue(
            VpnTileService.shouldRequestVpnPermissionBeforeStartForTest(
                isActive = false,
                tunEnabled = true,
                vpnPrepareRequired = true
            )
        )
    }

    @Test
    fun startSequenceIsNotClearedByFixedTwoSecondDelay() {
        val source = File("src/main/java/com/kunk/singbox/service/VpnTileService.kt").readText()
        val body = source.substring(
            source.indexOf("private fun executeStartVpn()"),
            source.indexOf("private suspend fun handleStartFailure")
        )

        assertFalse(body.contains("delay(2000)"))
        assertFalse(body.contains("isStartingSequence = false\n                        startSequenceId = 0L"))
    }
}

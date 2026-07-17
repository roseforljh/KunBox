package com.kunk.singbox.service

import com.kunk.singbox.repository.resolveRestoredProfileSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VpnTileServiceStateTest {

    @Test
    fun runningStateCompletesLocalStartingSequence() {
        assertTrue(VpnTileService.shouldCompleteStartingSequence(ServiceState.RUNNING))
    }

    @Test
    fun listeningClearsStartingSequenceWhenPendingHasFinished() {
        assertTrue(
            VpnTileService.shouldClearStartingSequenceOnListen(
                isStartingSequence = true,
                pending = ""
            )
        )
    }

    @Test
    fun listeningKeepsStartingSequenceWhileStartIsPending() {
        assertFalse(
            VpnTileService.shouldClearStartingSequenceOnListen(
                isStartingSequence = true,
                pending = "starting"
            )
        )
    }

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
            VpnTileService.shouldRequestVpnPermissionBeforeStart(
                isActive = true,
                tunEnabled = true,
                vpnPrepareRequired = true
            )
        )
        assertFalse(
            VpnTileService.shouldRequestVpnPermissionBeforeStart(
                isActive = false,
                tunEnabled = false,
                vpnPrepareRequired = true
            )
        )
        assertTrue(
            VpnTileService.shouldRequestVpnPermissionBeforeStart(
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

    @Test
    fun tileRuntimeStateReadsVpnStateStoreInsteadOfLegacyPreferences() {
        val source = File("src/main/java/com/kunk/singbox/service/VpnTileService.kt").readText()

        assertTrue(source.contains("VpnStateStore.getActive()"))
        assertTrue(source.contains("VpnStateStore.getPending()"))
        assertFalse(source.contains(".getBoolean(KEY_VPN_ACTIVE"))
        assertFalse(source.contains(".getString(KEY_VPN_PENDING"))
    }

    @Test
    fun restoredActiveNodeOnlyRefreshesTileSelectionStoreFromMainProcess() {
        val source = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt").readText()
        val helperBody = source
            .substringAfter("private fun persistMainProcessSelection(")
            .substringBefore("protected fun applyActiveProfileNodes(")
        val applyBody = source
            .substringAfter("protected fun applyActiveProfileNodes(")
            .substringBefore("protected suspend fun loadProfileNodesWithLatency")

        assertTrue(helperBody.contains("if (!isMainProcess) return"))
        assertTrue(applyBody.contains("persistMainProcessSelection(profileId, _activeNodeId.value, selectedName)"))
    }

    @Test
    fun serviceSelectionRefreshesTileSelectionStore() {
        val source = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt").readText()
        val body = source
            .substringAfter("suspend fun syncActiveNodeFromProxySelection(proxyName: String?): Boolean")
            .substringBefore("suspend fun deleteProfile(profileId: String)")

        assertTrue(body.contains("VpnStateStore.setSelectedNode(activeProfileId, matched.id)"))
    }

    @Test
    fun coldStartPrefersPersistedTileSelectionOverStaleDatabaseState() {
        assertEquals(
            "profile-b" to "node-b",
            resolveRestoredProfileSelection(
                availableProfileIds = setOf("profile-a", "profile-b"),
                databaseProfileId = "profile-a",
                databaseNodeId = "node-a-first",
                persistedProfileId = "profile-b",
                persistedNodeId = "node-b"
            )
        )
    }

    @Test
    fun coldStartFallsBackToDatabaseWhenPersistedProfileWasDeleted() {
        assertEquals(
            "profile-a" to "node-a",
            resolveRestoredProfileSelection(
                availableProfileIds = setOf("profile-a"),
                databaseProfileId = "profile-a",
                databaseNodeId = "node-a",
                persistedProfileId = "deleted-profile",
                persistedNodeId = "deleted-node"
            )
        )
    }

    @Test
    fun coldStartKeepsDatabaseNodeWhenPersistedNodeIsTemporarilyEmpty() {
        assertEquals(
            "profile-a" to "node-a",
            resolveRestoredProfileSelection(
                availableProfileIds = setOf("profile-a"),
                databaseProfileId = "profile-a",
                databaseNodeId = "node-a",
                persistedProfileId = "profile-a",
                persistedNodeId = ""
            )
        )
    }

    @Test
    fun explicitTileProfileIsUsedThroughoutRuntimeConfigGeneration() {
        val source = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt")
            .readText(Charsets.UTF_8)
            .replace("\r\n", "\n")
        val generationBody = source
            .substringAfter("suspend fun generateConfigFile(")
            .substringBefore("private fun logRunningConfigPath")

        assertTrue(
            generationBody.contains("buildRunOutbounds(\n                config,\n                activeId,")
        )
        assertTrue(
            generationBody.contains(
                "buildRunEndpoints(\n                baseConfig = config,\n                activeProfileId = activeId,"
            )
        )
    }
}

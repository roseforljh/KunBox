package com.kunk.singbox.service

import android.service.quicksettings.Tile
import com.kunk.singbox.ipc.DataPlaneReadinessSnapshot
import com.kunk.singbox.ipc.DataPlaneStatus
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
    fun tileRefreshClearsCompletedLocalStartingSequenceWithoutReopeningPanel() {
        val source = File("src/main/java/com/kunk/singbox/service/VpnTileService.kt").readText()
        val body = source.substring(
            source.indexOf("private fun updateTile("),
            source.indexOf("private fun hasSystemVpnTransport()")
        )

        assertTrue(body.contains("shouldClearStartingSequenceOnListen(isStartingSequence, pending)"))
        assertTrue(body.indexOf("shouldClearStartingSequenceOnListen") < body.indexOf("val effectiveState"))
    }

    @Test
    fun freshPersistedReadinessCanDriveTileBeforeIpcBindCompletes() {
        val readiness = DataPlaneReadinessSnapshot(
            status = DataPlaneStatus.READY,
            updatedAtElapsedMs = 50_000L
        )

        assertTrue(VpnTileService.hasTileControlPlane(false, readiness, 50_001L))
        assertFalse(VpnTileService.hasTileControlPlane(false, readiness, Long.MAX_VALUE))
        assertTrue(VpnTileService.hasTileControlPlane(true, DataPlaneReadinessSnapshot.stopped(), Long.MAX_VALUE))
    }

    @Test
    fun transitionStatesUseRequestedEndStateColor() {
        assertEquals(
            Tile.STATE_ACTIVE,
            VpnTileService.resolveTileState(ServiceState.STARTING, DataPlaneStatus.STARTING, true)
        )
        assertEquals(
            Tile.STATE_INACTIVE,
            VpnTileService.resolveTileState(ServiceState.STOPPING, DataPlaneStatus.BLOCKING, false)
        )
        assertEquals(
            Tile.STATE_UNAVAILABLE,
            VpnTileService.resolveTileState(ServiceState.STARTING, DataPlaneStatus.FAILED_BLOCKED, true)
        )
    }

    @Test
    fun tileClickDoesNotTurnOrdinaryStartOrStopGray() {
        val source = File("src/main/java/com/kunk/singbox/service/VpnTileService.kt").readText()
        val body = source.substringAfter("private fun handleClick()")
            .substringBefore("private fun startActivityAndCollapseCompat")

        assertTrue(body.contains("tile.state = Tile.STATE_INACTIVE"))
        assertTrue(body.contains("tile.state = Tile.STATE_ACTIVE"))
        assertFalse(body.contains("tile.state = Tile.STATE_UNAVAILABLE"))
    }

    @Test
    fun refreshPathHydratesPersistedSnapshotAndRequestsBinding() {
        val source = File("src/main/java/com/kunk/singbox/service/VpnTileService.kt").readText()
        val updateBody = source.substring(
            source.indexOf("private fun updateTile("),
            source.indexOf("private fun hasSystemVpnTransport()")
        )
        val receiverBody = source.substring(
            source.indexOf("private val tileRefreshReceiver"),
            source.indexOf("private val remoteCallback")
        )

        assertTrue(updateBody.contains("VpnStateStore.getRuntimeStateSnapshot()"))
        assertTrue(updateBody.contains("applyRemoteStateSnapshot(runtimeSnapshot)"))
        assertFalse(updateBody.contains("persistVpnState("))
        assertFalse(updateBody.contains("persistVpnPending("))
        assertTrue(receiverBody.contains("bindService()"))
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
        val source = File(
            "src/main/java/com/kunk/singbox/repository/configrepo/ConfigRepositoryPart1.kt"
        ).readText()
        val helperBody = source
            .substringAfter("internal fun ConfigRepository.persistMainProcessSelection(")
            .substringBefore("internal fun ConfigRepository.applyActiveProfileNodes(")
        val applyBody = source
            .substringAfter("internal fun ConfigRepository.applyActiveProfileNodes(")
            .substringBefore("internal suspend fun ConfigRepository.loadProfileNodesWithLatency")

        assertTrue(helperBody.contains("if (!isMainProcess) return"))
        assertTrue(applyBody.contains("persistMainProcessSelection(profileId, _activeNodeId.value, selectedName)"))
    }

    @Test
    fun serviceSelectionRefreshesTileSelectionStore() {
        val source = File(
            "src/main/java/com/kunk/singbox/repository/configrepo/ConfigRepositoryPart4.kt"
        ).readText()
        val body = source
            .substringAfter("internal suspend fun ConfigRepository.syncActiveNodeFromProxySelection(")
            .substringBefore("internal suspend fun ConfigRepository.commitSelectedNodeState(")

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
        val source = File(
            "src/main/java/com/kunk/singbox/repository/configrepo/ConfigRepositoryPart5.kt"
        )
            .readText(Charsets.UTF_8)
            .replace("\r\n", "\n")
        val generationBody = source
            .substringAfter("internal suspend fun ConfigRepository.generateConfigFile(")
            .substringBefore("internal data class ConfigRepository.RunOutboundsContext")
        val outboundsCall = generationBody.substringAfter("buildRunOutbounds(").substringBefore(')')
        val endpointsCall = generationBody.substringAfter("buildRunEndpoints(").substringBefore(')')

        assertTrue(outboundsCall.indexOf("config,") < outboundsCall.indexOf("activeId,"))
        assertTrue(endpointsCall.contains("baseConfig = config"))
        assertTrue(endpointsCall.contains("activeProfileId = activeId"))
    }
}

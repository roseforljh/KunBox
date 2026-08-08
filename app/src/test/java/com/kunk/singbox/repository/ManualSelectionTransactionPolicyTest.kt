package com.kunk.singbox.repository

import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.service.ServiceState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSelectionTransactionPolicyTest {
    private val source = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt")
        .readText(Charsets.UTF_8)

    @Test
    fun runningSelectionPublishesOnlyAfterKernelConfirmation() {
        val body = source
            .substringAfter("suspend fun setActiveNodeWithResult(nodeId: String)")
            .substringBefore("private fun commitManualSelectionState(")
        val runningBody = body.substring(body.lastIndexOf("withContext(Dispatchers.IO) {"))
        val awaitIndex = runningBody.indexOf("awaitRuntimeSelectionAfter(")
        val commitIndex = runningBody.indexOf("commitManualSelectionState(")

        assertTrue(body.contains("NodeProtectionStore.beginManualSelection(nodeId)"))
        assertTrue(awaitIndex >= 0)
        assertTrue(commitIndex > awaitIndex)
        assertFalse(runningBody.substring(0, awaitIndex).contains("_activeNodeId.value = nodeId"))
        assertFalse(runningBody.substring(0, awaitIndex).contains("VpnStateStore.setSelectedNode("))
    }

    @Test
    fun profileSelectionUsesGuardedManualTargetResolution() {
        val body = source
            .substringAfter("suspend fun setActiveProfileWithResult(profileId: String)")
            .substringBefore("@Suppress(\"LongMethod\", \"CognitiveComplexMethod\")")

        assertTrue(body.contains("resolveManualProfileTarget("))
        assertTrue(body.contains("setActiveNodeWithResult(targetNode.id)"))
        assertTrue(body.contains("targetNode.meteredProtected"))
    }

    @Test
    fun explicitProfileCardSelectionAllowsItsOnlyMeteredNode() {
        val protectedNode = node(id = "protected", meteredProtected = true)

        assertEquals(
            protectedNode,
            ConfigRepository.resolveManualProfileTarget(
                nodes = listOf(protectedNode),
                rememberedNodeId = null,
                autoSelectionEnabled = false
            )
        )
    }

    @Test
    fun explicitProfileCardSelectionStillPrefersSafeNode() {
        val protectedNode = node(id = "protected", meteredProtected = true)
        val safeNode = node(id = "safe")

        assertEquals(
            safeNode,
            ConfigRepository.resolveManualProfileTarget(
                nodes = listOf(protectedNode, safeNode),
                rememberedNodeId = protectedNode.id,
                autoSelectionEnabled = false
            )
        )
    }

    @Test
    fun explicitProfileCardSelectionRejectsAmbiguousProtectedNodes() {
        val protectedNodes = listOf(
            node(id = "protected-a", meteredProtected = true),
            node(id = "protected-b", meteredProtected = true)
        )

        assertNull(
            ConfigRepository.resolveManualProfileTarget(
                nodes = protectedNodes,
                rememberedNodeId = protectedNodes.first().id,
                autoSelectionEnabled = false
            )
        )
    }

    @Test
    fun rollbackRestoresConfigFingerprintTogetherWithFile() {
        val body = source
            .substringAfter("private fun restoreRunningConfigSnapshot(configContent: String)")
            .substringBefore("private fun restoreAutoSelectionState(")

        assertTrue(body.contains("writeTextFileAtomically"))
        assertTrue(body.contains("NodeProtectionStore.replaceRuntimeMappings(emptyMap(), configContent)"))
    }

    @Test
    fun runningSelectionUsesCrossProcessRuntimeStateWhenIpcFlowIsUnavailable() {
        val body = source
            .substringAfter("suspend fun setActiveNodeWithResult(nodeId: String)")
            .substringBefore("private fun commitManualSelectionState(")

        assertTrue(body.contains("VpnStateStore.getActive()"))
    }

    @Test
    fun firstRunningSwitchRecoversItsBaselineInsteadOfForcingRestart() {
        val body = source
            .substringAfter("suspend fun setActiveNodeWithResult(nodeId: String)")
            .substringBefore("private fun commitManualSelectionState(")

        assertTrue(body.contains("resolveRunningOutboundTags(previousRunningConfig)"))
        assertTrue(body.contains("VpnStateStore.getSelectedProfileId()"))
        assertFalse(body.contains("isFirstSwitchWhileRunning"))
    }

    @Test
    fun selectionConfirmationWaitStopsOnManualStopOrNewRuntimeError() {
        val body = source
            .substringAfter("private suspend fun awaitRuntimeSelectionAfter(")
            .substringBefore("private suspend fun awaitConcreteRuntimeLabel()")

        assertTrue(body.contains("snapshot.manuallyStopped"))
        assertTrue(body.contains("snapshot.lastError.isNotBlank()"))
    }

    @Test
    fun selectionDeadlinePerformsFinalCrossProcessStateRead() {
        val body = source
            .substringAfter("private suspend fun awaitRuntimeSelectionAfter(")
            .substringBefore("private fun resolveRunningOutboundTags(")

        assertTrue(body.contains("confirmed ?: isRuntimeSelectionConfirmed("))
    }

    @Test
    fun finalRuntimeSnapshotAcceptsTargetThatArrivedAtDeadline() {
        val snapshot = VpnStateStore.RuntimeStateSnapshot(
            generation = 11L,
            stateOrdinal = ServiceState.RUNNING.ordinal,
            activeLabel = "target"
        )

        assertTrue(isRuntimeSelectionConfirmed(snapshot, 10L, setOf("target")))
        assertFalse(isRuntimeSelectionConfirmed(snapshot.copy(generation = 10L), 10L, setOf("target")))
    }

    @Test
    fun matchingRecoveredRuntimeBaselineUsesHotSwitch() {
        assertFalse(
            shouldReloadRuntimeForManualSelection(
                currentProfileId = "profile",
                currentTags = setOf("PROXY", "node-a", "node-b"),
                baselineProfileId = "profile",
                baselineTags = setOf("PROXY", "node-a", "node-b"),
                isVpnStartingNotReady = false
            )
        )
        assertTrue(
            shouldReloadRuntimeForManualSelection(
                currentProfileId = "profile",
                currentTags = setOf("PROXY", "node-a", "node-b"),
                baselineProfileId = "profile",
                baselineTags = setOf("PROXY", "node-a"),
                isVpnStartingNotReady = false
            )
        )
    }

    @Test
    fun manualHotSwitchDoesNotOutliveThreeSecondUiDeadline() {
        val selectorSource = File("src/main/java/com/kunk/singbox/core/SelectorManager.kt")
            .readText(Charsets.UTF_8)

        assertTrue(source.contains("MANUAL_HOT_SWITCH_CONFIRMATION_TIMEOUT_MS = 3_000L"))
        assertTrue(selectorSource.contains("SELECTION_CONFIRMATION_TIMEOUT_MS = 2_500L"))
    }

    @Test
    fun crossProfileDetourIsCheckedBeforeTheOutboundEntersRuntimeConfig() {
        val body = source
            .substringAfter("protected fun buildRunOutbounds(")
            .substringBefore("protected fun applySelectorSafeOutbounds(")
        val guardIndex = body.indexOf("config = SingBoxConfig(outbounds = listOf(sourceOutbound))")
        val addIndex = body.indexOf("fixedOutbounds.add(fixedSourceOutbound)")

        assertTrue(guardIndex >= 0)
        assertTrue(addIndex > guardIndex)
    }

    private fun node(
        id: String,
        meteredProtected: Boolean = false
    ): NodeUi {
        return NodeUi(
            id = id,
            name = id,
            protocol = "http",
            group = "Default",
            sourceProfileId = "profile",
            meteredProtected = meteredProtected
        )
    }
}

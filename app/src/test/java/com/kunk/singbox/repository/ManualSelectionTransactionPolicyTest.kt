package com.kunk.singbox.repository

import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.service.ServiceState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun runningMeteredSelectionIsRejectedBeforeManualAuthorization() {
        val body = source
            .substringAfter("suspend fun setActiveNodeWithResult(nodeId: String)")
            .substringBefore("private fun commitManualSelectionState(")
        val rejectionIndex = body.indexOf("if (remoteRunning && targetNode.meteredProtected)")
        val authorizationIndex = body.indexOf("NodeProtectionStore.beginManualSelection(nodeId)")

        assertTrue(rejectionIndex >= 0)
        assertTrue(rejectionIndex < authorizationIndex)
        assertTrue(body.contains("R.string.node_metered_hot_reload_unsupported"))
    }

    @Test
    fun meteredHotReloadFailureUsesTheRepositoryToastMessage() {
        val nodes = File("src/main/java/com/kunk/singbox/viewmodel/NodesViewModel.kt")
            .readText(Charsets.UTF_8)
        val dashboard = File("src/main/java/com/kunk/singbox/viewmodel/DashboardViewModel.kt")
            .readText(Charsets.UTF_8)
        val profiles = File("src/main/java/com/kunk/singbox/viewmodel/ProfilesViewModel.kt")
            .readText(Charsets.UTF_8)
        val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)

        assertTrue(nodes.contains("is ConfigRepository.NodeSwitchResult.Failed -> result.reason"))
        assertTrue(dashboard.contains("is ConfigRepository.NodeSwitchResult.Failed -> result.reason"))
        assertTrue(profiles.contains("emitToast(result.reason)"))
        assertTrue(strings.contains(">高价保护节点不支持热重载</string>"))
    }

    @Test
    fun unauthorizedMeteredLatencyExplainsThatTheNodeMustBeSelected() {
        val latencyBody = source
            .substringAfter("suspend fun testNodeLatency(nodeId: String): Long")
            .substringBefore("suspend fun clearAllNodesLatency()")
        val nodes = File("src/main/java/com/kunk/singbox/viewmodel/NodesViewModel.kt")
            .readText(Charsets.UTF_8)
        val strings = File("src/main/res/values/strings.xml").readText(Charsets.UTF_8)

        assertTrue(latencyBody.contains("PingResultCode.METERED_SELECTION_REQUIRED"))
        assertTrue(nodes.contains("latency == PingResultCode.METERED_SELECTION_REQUIRED"))
        assertTrue(nodes.contains("R.string.nodes_metered_select_to_test"))
        assertTrue(strings.contains(">高价节点需选中后测速</string>"))
    }

    @Test
    fun profileSelectionUsesGuardedManualTargetResolution() {
        val body = source
            .substringAfter("suspend fun setActiveProfileWithResult(profileId: String)")
            .substringBefore("@Suppress(\"LongMethod\", \"CognitiveComplexMethod\")")

        assertTrue(body.contains("resolveManualProfileTarget("))
        assertTrue(body.contains("setActiveNodeWithResult(targetNode.id)"))
        assertTrue(body.contains("targetNode.meteredProtected"))
        assertTrue(body.contains("!targetNode.autoSelectionEligible"))
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
    fun explicitProfileCardSelectionUsesRememberedProtectedNodeWhenNoSafeCandidateExists() {
        val protectedNodes = listOf(
            node(id = "protected-a", meteredProtected = true),
            node(id = "protected-b", meteredProtected = true)
        )

        assertEquals(
            protectedNodes.first(),
            ConfigRepository.resolveManualProfileTarget(
                nodes = protectedNodes,
                rememberedNodeId = protectedNodes.first().id,
                autoSelectionEnabled = false
            )
        )
    }

    @Test
    fun explicitProfileCardSelectionUsesStableProtectedFallbackWithoutMemory() {
        val protectedNodes = listOf(
            node(id = "protected-b", meteredProtected = true),
            node(id = "protected-a", meteredProtected = true)
        )

        assertEquals(
            protectedNodes.last(),
            ConfigRepository.resolveManualProfileTarget(
                nodes = protectedNodes,
                rememberedNodeId = null,
                autoSelectionEnabled = true
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
    fun runtimeSelectionConfirmationIgnoresBoundaryWhitespaceInLabels() {
        val snapshot = VpnStateStore.RuntimeStateSnapshot(
            generation = 11L,
            stateOrdinal = ServiceState.RUNNING.ordinal,
            activeLabel = "1.88u idc"
        )

        assertTrue(isRuntimeSelectionConfirmed(snapshot, 10L, setOf("1.88u idc ")))
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
        val rootGuardIndex = body.indexOf("config = SingBoxConfig(outbounds = listOf(sourceOutbound))")
        val resolverIndex = body.indexOf("resolveRuntimeOutboundDependencies(")
        val dependencyGuardIndex = body.indexOf("isProtectedReference = { sourceProfileId, reference ->")
        val addIndex = body.indexOf("resolution.outbounds.forEach")

        assertTrue(rootGuardIndex >= 0)
        assertTrue(resolverIndex > rootGuardIndex)
        assertTrue(dependencyGuardIndex > resolverIndex)
        assertTrue(addIndex > dependencyGuardIndex)
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

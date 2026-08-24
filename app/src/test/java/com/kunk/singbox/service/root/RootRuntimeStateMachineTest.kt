package com.kunk.singbox.service.root

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRuntimeStateMachineTest {
    @Test
    fun notificationNodeSwitchCyclesOnlyProvidedSafeCandidates() {
        val candidates = listOf("node-a", "node-b", "node-c")

        assertEquals("node-c", nextRootNotificationNodeId(candidates, "node-b"))
        assertEquals("node-a", nextRootNotificationNodeId(candidates, "node-c"))
        assertEquals("node-a", nextRootNotificationNodeId(candidates, "missing"))
        assertEquals(null, nextRootNotificationNodeId(listOf("node-a"), "node-a"))
    }

    @Test
    fun rootNotificationUsesSharedVpnNotificationActionsAndLiveData() {
        val rootSource = File("src/main/java/com/kunk/singbox/service/root/RootTransparentForegroundService.kt")
            .readText(Charsets.UTF_8)
        val sharedSource = File("src/main/java/com/kunk/singbox/service/notification/VpnNotificationManager.kt")
            .readText(Charsets.UTF_8)

        assertTrue(rootSource.contains("VpnNotificationManager("))
        assertTrue(rootSource.contains("switchNodeAction = ACTION_SWITCH_NODE"))
        assertTrue(rootSource.contains("resetConnectionsAction = ACTION_RESET_CONNECTIONS"))
        assertTrue(rootSource.contains("stopAction = ACTION_STOP"))
        assertTrue(rootSource.contains("snapshot.uploadSpeed"))
        assertTrue(rootSource.contains("commandManager.realTimeNodeName"))
        assertTrue(sharedSource.contains("Intent(context, actions.serviceClass)"))
    }

    @Test
    fun formatsRootStartupTimingsForAppProcessLogging() {
        assertEquals(
            "cleanup=20,core=100,watchdog=30,uid_scope=4000,netfilter=200",
            formatRootStartupTimings(
                linkedMapOf(
                    "cleanup" to 20L,
                    "core" to 100L,
                    "watchdog" to 30L,
                    "uid_scope" to 4000L,
                    "netfilter" to 200L
                )
            )
        )
    }

    @Test
    fun startingRootCanBeStoppedThroughLibsuManagementChannel() {
        val source = File("src/main/java/com/kunk/singbox/service/root/RootTransparentForegroundService.kt")
            .readText(Charsets.UTF_8)
        val stopBranch = source.substringAfter("ACTION_STOP ->")
            .substringBefore("ACTION_RESTART ->")

        assertTrue(stopBranch.contains("rootConnection.stopRootService()"))
    }

    @Test
    fun rootPolicyIsCommittedBeforeRuntimeBecomesRunning() {
        val source = File("src/main/java/com/kunk/singbox/service/root/RootTransparentForegroundService.kt")
            .readText(Charsets.UTF_8)
        val startSuccess = source.substringAfter("SelectorManager.updateCommandClient")
            .substringBefore("SingBoxIpcHub.update(")

        assertTrue(
            startSuccess.indexOf("commitAppliedPerAppPolicy") <
                startSuccess.indexOf("VpnStateStore.setActive(true)")
        )
    }

    @Test
    fun acceptsOnlyCurrentSessionAndMonotonicGeneration() {
        val current = RootRuntimeSnapshot(
            phase = RootRuntimePhase.RUNNING,
            runtimeSessionId = "session-a",
            generation = 4
        )

        assertTrue(shouldAcceptRootSnapshot(current, current.copy(generation = 5)))
        assertTrue(shouldAcceptRootSnapshot(current, current.copy(generation = 4)))
        assertFalse(shouldAcceptRootSnapshot(current, current.copy(generation = 3)))
        assertFalse(
            shouldAcceptRootSnapshot(
                current,
                current.copy(runtimeSessionId = "session-b", generation = 10)
            )
        )
    }
}

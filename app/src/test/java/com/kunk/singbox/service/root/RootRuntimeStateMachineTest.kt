package com.kunk.singbox.service.root

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRuntimeStateMachineTest {
    @Test
    fun externalRootConfigRequiresItsOriginalCandidateRequestId() {
        val failure = runCatching {
            resolveRootCandidateRequestId(
                configPathOverride = "/data/user/0/com.kunk.singbox/files/root/config.json",
                requestId = "",
                generatedId = "generated"
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(
            "request-1",
            resolveRootCandidateRequestId(
                configPathOverride = "/data/user/0/com.kunk.singbox/files/root/config.json",
                requestId = "request-1",
                generatedId = "generated"
            )
        )
        assertEquals(
            "",
            resolveRootCandidateRequestId(
                configPathOverride = "/data/user/0/com.kunk.singbox/files/running_config.json",
                requestId = "",
                generatedId = "generated"
            )
        )
    }

    @Test
    fun rootGeneratedConfigCreatesOneCandidateRequestId() {
        assertEquals(
            "generated",
            resolveRootCandidateRequestId(
                configPathOverride = null,
                requestId = "",
                generatedId = "generated"
            )
        )
    }

    @Test
    fun terminalStartFailureDoesNotRepeatTheSameSynchronousCleanup() {
        assertFalse(
            rootStartFailureRequiresSynchronousStop(
                RootRuntimeSnapshot(phase = RootRuntimePhase.FAILED_VERIFICATION)
            )
        )
        assertFalse(
            rootStartFailureRequiresSynchronousStop(
                RootRuntimeSnapshot(phase = RootRuntimePhase.FAILED_BLOCKED, rulesInstalled = true)
            )
        )
        assertTrue(rootStartFailureRequiresSynchronousStop(RootRuntimeSnapshot(phase = RootRuntimePhase.RUNNING)))
        assertTrue(rootStartFailureRequiresSynchronousStop(null))
    }

    @Test
    fun rootServiceDoesNotRepeatCleanupAfterTerminalStartFailure() {
        assertFalse(
            rootDestroyRequiresCleanup(
                RootRuntimeSnapshot(phase = RootRuntimePhase.FAILED_VERIFICATION),
                activeTransactions = 0
            )
        )
        assertFalse(
            rootDestroyRequiresCleanup(
                RootRuntimeSnapshot(phase = RootRuntimePhase.FAILED_BLOCKED, rulesInstalled = true),
                activeTransactions = 0
            )
        )
        assertFalse(rootDestroyRequiresCleanup(RootRuntimeSnapshot(), activeTransactions = 1))
        assertTrue(
            rootDestroyRequiresCleanup(
                RootRuntimeSnapshot(phase = RootRuntimePhase.ROOT_BINDING),
                activeTransactions = 0
            )
        )
    }

    @Test
    fun stopInvalidatesEveryOlderStartOrReloadGeneration() {
        val lifecycle = RootLifecycleCoordinator()
        val start = lifecycle.requestRunning(reload = false) ?: error("start request rejected")
        val reload = lifecycle.requestRunning(reload = true) ?: error("reload request rejected")
        val stop = lifecycle.requestStopped()

        assertFalse(lifecycle.transition(start, RootLifecycleState.RUNNING))
        assertFalse(lifecycle.transition(reload, RootLifecycleState.RUNNING))
        assertTrue(lifecycle.transition(stop, RootLifecycleState.STOPPED))
        assertEquals(RootDesiredState.STOPPED, lifecycle.snapshot().desiredState)
    }

    @Test
    fun startRequestedWhileStoppingIsRejected() {
        val lifecycle = RootLifecycleCoordinator()
        lifecycle.requestRunning(reload = false)
        val stop = lifecycle.requestStopped()
        val finalStart = lifecycle.requestRunning(reload = false)

        assertEquals(RootLifecycleState.STOPPING, lifecycle.snapshot().state)
        assertEquals(null, finalStart)
        assertEquals(RootDesiredState.STOPPED, lifecycle.snapshot().desiredState)
        assertTrue(lifecycle.transition(stop, RootLifecycleState.STOPPED))
        assertEquals(RootLifecycleState.STOPPED, lifecycle.snapshot().state)
    }

    @Test
    fun settingsPageConstructionCannotRestartVpn() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/SettingsViewModel.kt")
            .readText(Charsets.UTF_8)

        assertFalse(source.contains("reconcilePerAppPolicyOnce"))
    }

    @Test
    fun reloadValidatesCandidateBeforeTouchingActiveNetworkAndStopAlwaysCleans() {
        val source = File("src/main/java/com/kunk/singbox/service/root/runtime/KunBoxRootServiceRuntime.kt")
            .readText(Charsets.UTF_8)
        val reload = source.substringAfter("fun KunBoxRootService.hotReloadLocked")
            .substringBefore("fun KunBoxRootService.unionGuardConfig")
        val stop = source.substringAfter("fun KunBoxRootService.stopLocked")
            .substringBefore("fun KunBoxRootService.rollbackLocked")

        assertTrue(reload.indexOf("readValidatedArtifacts") < reload.indexOf("installGuard"))
        assertTrue(reload.indexOf("reloadCommandServer") < reload.indexOf("installGuard"))
        assertTrue(reload.contains("candidateNetfilterConfig == previousNetfilterConfig"))
        assertTrue(
            File("src/main/java/com/kunk/singbox/service/root/KunBoxRootService.kt")
                .readText()
                .contains("installGuardAndStage")
        )
        assertTrue(stop.indexOf("closeCommandServer") < stop.indexOf("cleanupRulesVerified"))
        assertFalse(stop.contains("snapshot.phase == RootRuntimePhase.STOPPED") && stop.contains("return snapshot"))
    }

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
        val rootSource = listOf(
            "src/main/java/com/kunk/singbox/service/root/RootTransparentForegroundService.kt",
            "src/main/java/com/kunk/singbox/service/root/runtime/RootTransparentForegroundRuntime.kt"
        ).joinToString("\n") { File(it).readText(Charsets.UTF_8) }
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
            "legacy_cleanup_ms=20,guard_ms=30,rules_staging_ms=200,core_ms=100," +
                "xtables_wait_ms=40,total_ms=4000",
            formatRootStartupTimings(
                linkedMapOf(
                    "legacy_cleanup_ms" to 20L,
                    "guard_ms" to 30L,
                    "rules_staging_ms" to 200L,
                    "core_ms" to 100L,
                    "xtables_wait_ms" to 40L,
                    "total_ms" to 4000L
                )
            )
        )
    }

    @Test
    fun startingRootStopUsesPreemptionSignalThenVerifiedCleanup() {
        val source = listOf(
            "src/main/java/com/kunk/singbox/service/root/RootTransparentForegroundService.kt",
            "src/main/java/com/kunk/singbox/service/root/runtime/RootTransparentForegroundRuntime.kt"
        ).joinToString("\n") { File(it).readText(Charsets.UTF_8) }
        val stopBranch = source.substringAfter("ACTION_STOP ->")
            .substringBefore("ACTION_RESTART ->")
        val stopRuntime = source.substringAfter("suspend fun stopRuntimeLocked")
            .substringBefore("fun RootTransparentForegroundService.restartRuntime")

        assertTrue(stopBranch.contains("requestStopRuntime"))
        assertTrue(source.contains("rootConnection.service?.requestStop(sessionId)"))
        assertTrue(
            stopRuntime.indexOf("stopped.phase == RootRuntimePhase.STOPPED") <
                stopRuntime.indexOf("rootConnection.stopRootService()")
        )
        assertFalse(source.contains("ROOT_STOP_OPERATION_TIMEOUT_MS"))
        assertTrue(source.contains("stopRemoteRuntime()"))
        assertTrue(source.contains("val rootService = rootConnection.service ?: return if"))
        assertTrue(!stopRuntime.contains("rootConnection.service ?: rootConnection.bind()"))
        val stopEntry = source.substringAfter("suspend fun stopRuntime(stopSelfAfter: Boolean, token: Long)")
            .substringBefore("suspend fun stopRuntimeLocked")
        assertFalse(stopEntry.contains("lifecycleMutex.withLock"))
        assertTrue(source.contains("phase = RootRuntimePhase.FAILED_VERIFICATION"))
        val aidl = File("src/main/aidl/com/kunk/singbox/aidl/IRootSingBoxService.aidl")
            .readText(Charsets.UTF_8)
        val rootService = File("src/main/java/com/kunk/singbox/service/root/KunBoxRootService.kt")
            .readText(Charsets.UTF_8)
        assertTrue(aidl.contains("oneway void requestStop"))
        assertTrue(rootService.contains("rootCommandExecutor.cancelActiveCommands()"))
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

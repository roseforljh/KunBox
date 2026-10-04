package com.kunk.singbox.service.root

import com.kunk.singbox.model.TrafficCaptureMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootRuntimeStateMachineTest {
    @Test
    fun rootPrewarmRunsOnlyWhileRootModeIsIdle() {
        assertTrue(
            shouldPrewarmRootService(
                TrafficCaptureMode.ROOT_TRANSPARENT,
                active = false,
                pending = "",
                running = false,
                starting = false
            )
        )
        assertFalse(
            shouldPrewarmRootService(
                TrafficCaptureMode.ROOT_TRANSPARENT,
                active = false,
                pending = "starting",
                running = false,
                starting = true
            )
        )
        assertFalse(
            shouldPrewarmRootService(
                TrafficCaptureMode.VPN,
                active = false,
                pending = "",
                running = false,
                starting = false
            )
        )
    }

    @Test
    fun verifiedForegroundStopRecyclesConnectedRootService() {
        assertTrue(
            shouldRecycleRootServiceAfterStop(
                cleanupConfirmed = true,
                appForeground = true,
                serviceConnected = true
            )
        )
        assertFalse(
            shouldRecycleRootServiceAfterStop(
                cleanupConfirmed = true,
                appForeground = false,
                serviceConnected = true
            )
        )
        assertFalse(
            shouldRecycleRootServiceAfterStop(
                cleanupConfirmed = false,
                appForeground = true,
                serviceConnected = true
            )
        )
    }

    @Test
    fun coldRootStartDispatchesBindBeforeRuntimeWork() {
        val source = File("src/main/java/com/kunk/singbox/service/root/RootTransparentForegroundService.kt")
            .readText(Charsets.UTF_8)
        val startCommand = source.substringAfter("override fun onStartCommand")
            .substringBefore("override fun onBind")
        val coldStart = startCommand.substringAfter("else -> {")

        assertTrue(coldStart.indexOf("rootConnection.beginBind()") in 0 until coldStart.indexOf("startRuntime("))
    }

    @Test
    fun failedPrewarmDoesNotUnbindConnectionAfterForegroundAcquiresIt() {
        val source = File("src/main/java/com/kunk/singbox/service/root/RootServiceConnection.kt")
            .readText(Charsets.UTF_8)
        val failure = source.substringAfter("}.onFailure { error ->")
            .substringBefore("fun acquire")

        assertTrue(failure.contains("if (connection === next)"))
        assertTrue(failure.indexOf("if (connection === next)") < failure.indexOf("next.unbind()"))
    }

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
    fun missingOrUnverifiedRootCleanupRemainsOwnedByRootUntilVerified() {
        assertFalse(rootFailureRequiresCleanup(RootRuntimeSnapshot(phase = RootRuntimePhase.STOPPED)))
        assertFalse(rootFailureRequiresCleanup(RootRuntimeSnapshot(phase = RootRuntimePhase.FAILED_UNPROTECTED)))
        assertTrue(rootFailureRequiresCleanup(RootRuntimeSnapshot(phase = RootRuntimePhase.FAILED_VERIFICATION)))
        assertTrue(rootFailureRequiresCleanup(RootRuntimeSnapshot(phase = RootRuntimePhase.FAILED_BLOCKED)))
        assertTrue(
            rootFailureRequiresCleanup(
                RootRuntimeSnapshot(phase = RootRuntimePhase.FAILED_UNPROTECTED, rulesInstalled = true)
            )
        )
    }

    @Test
    fun missingRootSnapshotIsAverificationFailure() {
        val snapshot = RootRuntimeSnapshot.fromBundle(null)

        assertEquals(RootRuntimePhase.FAILED_VERIFICATION, snapshot.phase)
        assertTrue(snapshot.error.isNotBlank())
        assertTrue(rootFailureRequiresCleanup(snapshot))
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
    fun forcedRootExitOnlyAppliesToTheRequestedLiveSession() {
        assertTrue(
            shouldForceRootProcessExit(
                stopRequestedSession = "session-1",
                runtimeSessionId = "session-1",
                phase = RootRuntimePhase.CORE_STARTING
            )
        )
        assertFalse(
            shouldForceRootProcessExit(
                stopRequestedSession = "session-1",
                runtimeSessionId = "session-2",
                phase = RootRuntimePhase.CORE_STARTING
            )
        )
        assertFalse(
            shouldForceRootProcessExit(
                stopRequestedSession = "session-1",
                runtimeSessionId = "session-1",
                phase = RootRuntimePhase.STOPPED
            )
        )
    }

    @Test
    fun hotSwitchChecksCommandGenerationBeforeSelectionAndBeforePublishingResult() {
        val service = File("src/main/java/com/kunk/singbox/service/root/RootTransparentForegroundService.kt")
            .readText(Charsets.UTF_8)
        val command = service.substringAfter("ACTION_SWITCH_NODE ->")
            .substringBefore("ACTION_RESET_CONNECTIONS ->")
        val runtime = File(
            "src/main/java/com/kunk/singbox/service/root/runtime/RootTransparentForegroundRuntime.kt"
        ).readText(Charsets.UTF_8)
        val switch = runtime.substringAfter("internal suspend fun RootTransparentForegroundService.switchNode(")
            .substringBefore("internal fun RootTransparentForegroundService.rootReadiness(")
        val selectionIndex = switch.indexOf("SelectorManager.switchNode(outboundTag)")
        val firstCheck = switch.indexOf("ensureRunningRequest(token)")
        val finalCheck = switch.indexOf("ensureRunningRequest(token)", selectionIndex)

        assertTrue(command.contains("token = lifecycleState.generation"))
        assertTrue(firstCheck in 0 until selectionIndex)
        assertTrue(finalCheck > selectionIndex)
        assertTrue(finalCheck < switch.indexOf("when (result)"))
    }

    @Test
    fun rootRecoverySerializesWithSwitchAndReplacesSelectorClientAfterGenerationCheck() {
        val runtime = File(
            "src/main/java/com/kunk/singbox/service/root/runtime/RootTransparentForegroundRuntime.kt"
        ).readText(Charsets.UTF_8)
        val recovery = runtime.substringAfter(
            "internal fun RootTransparentForegroundService.scheduleControlChannelRecovery("
        ).substringBefore("internal fun RootTransparentForegroundService.recordSelector(")
        val reconnectIndex = recovery.indexOf("commandManager.reconnectControlClientsWithFd")
        val updateIndex = recovery.indexOf("SelectorManager.updateCommandClient(commandManager.getCommandClient())")

        assertTrue(recovery.contains("val token = lifecycle.snapshot().generation"))
        assertTrue(recovery.indexOf("lifecycleMutex.withLock") in 0 until reconnectIndex)
        assertTrue(updateIndex > reconnectIndex)
        assertTrue(recovery.substring(reconnectIndex, updateIndex).contains("ensureRunningRequest(token)"))
        assertFalse(recovery.contains("it.copy(selectorReady = true)"))
    }

    @Test
    fun rollbackAndUidRefreshRestoreSelectorBeforePublishingRunning() {
        val runtime = File(
            "src/main/java/com/kunk/singbox/service/root/runtime/RootTransparentForegroundRuntime.kt"
        ).readText(Charsets.UTF_8)
        val rollback = runtime.substringAfter(
            "internal suspend fun RootTransparentForegroundService.restoreReloadedPreviousRuntime("
        ).substringBefore("internal fun RootTransparentForegroundService.publishReloadFailure(")
        val refresh = runtime.substringAfter(
            "internal suspend fun RootTransparentForegroundService.refreshUidRoutingLocked("
        ).substringBefore("internal fun RootTransparentForegroundService.publishUidRefreshBlocked(")
        val rollbackRecord = rollback.indexOf("recordSelector(previousConfigPath)")
        val refreshRecord = refresh.indexOf(
            "recordSelector(RootGenerationStore.configFile(filesDir, marker).absolutePath)"
        )

        assertTrue(rollbackRecord > rollback.indexOf("SelectorManager.updateCommandClient"))
        assertTrue(rollbackRecord < rollback.indexOf("transitionLifecycle(token, RootLifecycleState.RUNNING"))
        assertTrue(refreshRecord > refresh.indexOf("SelectorManager.updateCommandClient"))
        assertTrue(refreshRecord < refresh.indexOf("transitionLifecycle(token, RootLifecycleState.RUNNING"))
    }

    @Test
    fun selectionAckFromBeforeReloadOrStopIsNotCurrent() {
        val lifecycle = RootLifecycleCoordinator()
        val start = lifecycle.requestRunning(reload = false) ?: error("start rejected")
        assertTrue(lifecycle.transition(start, RootLifecycleState.RUNNING))
        val switchToken = lifecycle.snapshot().generation
        val reload = lifecycle.requestRunning(reload = true) ?: error("reload rejected")

        assertFalse(lifecycle.isCurrentRunningRequest(switchToken))
        assertTrue(lifecycle.transition(reload, RootLifecycleState.RUNNING))
        lifecycle.requestStopped()
        assertFalse(lifecycle.isCurrentRunningRequest(reload))
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
    fun destroyAfterVerifiedStopDoesNotReopenStopping() {
        val lifecycle = RootLifecycleCoordinator()
        val start = lifecycle.requestRunning(reload = false) ?: error("start request rejected")
        assertTrue(lifecycle.transition(start, RootLifecycleState.RUNNING))
        val stop = lifecycle.requestStopped()
        assertTrue(lifecycle.transition(stop, RootLifecycleState.STOPPED))

        val destroy = lifecycle.requestStopped()

        assertTrue(destroy > stop)
        assertEquals(RootLifecycleState.STOPPED, lifecycle.snapshot().state)
        assertEquals(RootDesiredState.STOPPED, lifecycle.snapshot().desiredState)
        assertFalse(lifecycle.isCurrentRunningRequest(start))
        assertFalse(lifecycle.transition(start, RootLifecycleState.RUNNING))
        assertTrue(lifecycle.requestRunning(reload = false) != null)
    }

    @Test
    fun destroyingActiveRuntimeStillRequiresCleanup() {
        val lifecycle = RootLifecycleCoordinator()
        val start = lifecycle.requestRunning(reload = false) ?: error("start request rejected")
        assertTrue(lifecycle.transition(start, RootLifecycleState.RUNNING))

        lifecycle.requestStopped()

        assertEquals(RootLifecycleState.STOPPING, lifecycle.snapshot().state)
        assertNull(lifecycle.requestRunning(reload = false))
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
    fun stopCanBeRetriedWhileCleanupIsAlreadyStopping() {
        val lifecycle = RootLifecycleCoordinator()
        lifecycle.requestRunning(reload = false)
        val firstStop = lifecycle.requestStopped()
        val retryStop = lifecycle.requestStopped()

        assertTrue(retryStop > firstStop)
        assertEquals(RootLifecycleState.STOPPING, lifecycle.snapshot().state)
        assertEquals(RootDesiredState.STOPPED, lifecycle.snapshot().desiredState)
        assertTrue(lifecycle.transition(retryStop, RootLifecycleState.STOPPED))
    }

    @Test
    fun blockedRootNotificationRequestsCleanupRetryWhenRuntimeIsNotRunning() {
        val source = File(
            "src/main/java/com/kunk/singbox/service/root/runtime/RootTransparentForegroundRuntime.kt"
        ).readText(Charsets.UTF_8)
        val reset = source.substringAfter(
            "internal fun RootTransparentForegroundService.resetConnectionsFromNotification()"
        )
        assertTrue(reset.contains("notification_reset_cleanup_retry"))
        assertTrue(reset.contains("lastRootSnapshot.rulesInstalled"))
        assertTrue(
            source.substringAfter("internal fun RootTransparentForegroundService.publishUidRefreshBlocked")
                .substringBefore("@Suppress(\"DEPRECATION\")")
                .contains("root_uid_refresh_blocked")
        )
    }

    @Test
    fun disconnectedRootServiceStillAttemptsEmergencyCleanup() {
        val source = File(
            "src/main/java/com/kunk/singbox/service/root/runtime/RootStopRuntime.kt"
        ).readText(Charsets.UTF_8)
        assertTrue(source.contains("service_unavailable_using_emergency_cleanup"))
        assertTrue(source.contains("runEmergencyRootCleanup(sessionId, rootPid, rootStartTime)"))
        assertFalse(
            source.contains(
                "return failedRootStop(\"Root service disconnected before cleanup could be verified\")"
            )
        )
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
            "src/main/java/com/kunk/singbox/service/root/runtime/RootTransparentForegroundRuntime.kt",
            "src/main/java/com/kunk/singbox/service/root/runtime/RootStopRuntime.kt"
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
        assertTrue(source.contains("ROOT_STOP_CALL_TIMEOUT_MS"))
        assertTrue(source.contains("rootConnection.stopRootService()"))
        assertTrue(source.contains("rootConnection.bind()"))
        assertTrue(source.contains("stopRemoteRuntime()"))
        assertTrue(source.contains("val rootService = rootConnection.service"))
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
        assertTrue(rootService.contains("rootCommandExecutor.cancelActiveCommands {"))
        assertTrue(rootService.contains("stopRequestedSession.compareAndSet(\"\", sessionId)"))
    }

    @Test
    fun rootStopBinderCallHasAHardDeadline() {
        val blocker = CountDownLatch(1)
        val startedAt = System.nanoTime()

        val result = runRootStopCall(50L) {
            blocker.await()
            true
        }
        blocker.countDown()

        val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertNull(result)
        assertTrue("Root stop deadline took ${durationMs}ms", durationMs < 1_000L)
    }

    @Test
    fun emergencyRootCleanupForceKillsOnlyTheVerifiedRootProcess() {
        val sessionId = "0d833321-aaf8-4b3f-b91c-295b1d8b3133"

        val command = buildEmergencyRootCleanupCommand(sessionId, 31536, "123456")

        assertTrue(command.contains("/proc/31536/stat"))
        assertTrue(command.contains("kill -TERM 31536"))
        assertTrue(command.contains("kill -KILL 31536"))
        assertTrue(command.contains("/data/adb/kunbox/watchdog.sh"))
        assertTrue(command.contains("cleanup '$sessionId'"))
        assertThrows(IllegalArgumentException::class.java) {
            buildEmergencyRootCleanupCommand("bad-session", 31536, "123456")
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildEmergencyRootCleanupCommand(sessionId, 31536, "not-a-start-time")
        }
    }

    @Test
    fun controlHeartbeatRecoveryKeepsRootDataPlaneAndRetriesControlClients() {
        val source = File(
            "src/main/java/com/kunk/singbox/service/root/runtime/RootTransparentForegroundRuntime.kt"
        ).readText(Charsets.UTF_8)
        val recovery = source
            .substringAfter("internal fun RootTransparentForegroundService.scheduleControlChannelRecovery")
            .substringBefore("internal fun RootTransparentForegroundService.recordSelector")

        assertTrue(recovery.contains("reconnectControlClientsWithFd"))
        assertTrue(recovery.contains("repeat(3)"))
        assertFalse(recovery.contains("restartRuntime(configPathOverride"))
        assertFalse(recovery.contains("requestStopRuntime"))
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
    fun rootAppliedPolicyUsesRuntimeStateGeneration() {
        val startSource = File(
            "src/main/java/com/kunk/singbox/service/root/RootTransparentForegroundService.kt"
        ).readText(Charsets.UTF_8)
        val refreshSource = File(
            "src/main/java/com/kunk/singbox/service/root/runtime/RootTransparentForegroundRuntime.kt"
        ).readText(Charsets.UTF_8)

        assertTrue(startSource.contains("val runtimeState = VpnStateStore.getRuntimeStateSnapshot()"))
        assertTrue(startSource.contains("runtimeGeneration = policyRuntimeGeneration"))
        assertTrue(refreshSource.contains("VpnStateStore.getRuntimeStateSnapshot().generation"))
        assertTrue(refreshSource.contains("runtimeGeneration = maxOf("))
        assertFalse(startSource.contains("runtimeGeneration = rootSnapshot.routingGeneration"))
        assertFalse(refreshSource.contains("runtimeGeneration = refreshed.routingGeneration"))
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

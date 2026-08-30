package com.kunk.singbox.service.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StartupManagerTest {

    @Test
    fun resolveRuntimeLogLevelOnlyEnablesVerboseLogsInDebugMode() {
        assertEquals("error", StartupManager.resolveRuntimeLogLevel(debugLoggingEnabled = false))
        assertEquals("debug", StartupManager.resolveRuntimeLogLevel(debugLoggingEnabled = true))
    }

    @Test
    fun startupManagerBlocksLocalNetworkSettingsAndRestrictsWildcardListen() {
        val source = File("src/main/java/com/kunk/singbox/service/manager/StartupManager.kt")
            .readText(Charsets.UTF_8)

        assertTrue(source.contains("ensureLocalNetworkPermission(settings)"))
        assertTrue(source.contains("if (!LocalNetworkPermission.canApplySettings(context, settings))"))
        assertTrue(source.contains("throw IllegalStateException(LocalNetworkPermission.MISSING_PERMISSION_ERROR)"))
        assertTrue(source.contains("restrictLanListen -> LocalNetworkPermission.restrictInboundListen(inbound)"))
        assertTrue(source.contains("Start failed: \${LocalNetworkPermission.MISSING_PERMISSION_ERROR}"))
    }

    @Test
    fun successfulCoreStartPublishesStateBehindExactLeaseGate() {
        val managerSource = File("src/main/java/com/kunk/singbox/service/manager/StartupManager.kt")
            .readText(Charsets.UTF_8)
        val serviceSource = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt")
            .readText(Charsets.UTF_8)

        assertTrue(
            managerSource.contains("callbacks.completeRecoveryIntentOnSuccess(recoveryIntentLease, configContent)")
        )
        assertFalse(managerSource.contains("callbacks.setIsRunning(true)"))
        val completionBody = serviceSource
            .substringAfter("override fun completeRecoveryIntentOnSuccess(")
            .substringBefore("override fun persistVpnState")
        val exactLeaseGate = completionBody.indexOf("completeRecoveryIntentOnSuccess(lease)")
        val markRunning = completionBody.indexOf("SingBoxService.isRunning = true")
        val launchPostStart = completionBody.indexOf("launchPostStartTasks(configContent)")
        assertTrue(exactLeaseGate >= 0)
        assertTrue(markRunning > exactLeaseGate)
        assertTrue(launchPostStart > markRunning)
    }

    @Test
    fun postStartAppliesConfigSelectionAfterCommandClientsWithoutStaleActiveNode() {
        val source = File("src/main/java/com/kunk/singbox/service/vpn/SingBoxCommandRuntime.kt")
            .readText(Charsets.UTF_8)
        val body = source
            .substringAfter("internal fun SingBoxService.launchPostStartTasks(configContent: String) {")
            .substringBefore("internal fun SingBoxService.isPostStartTaskActive(generation: Long): Boolean {")

        val startClients = body.indexOf("commandManager.startClients()")
        val applyPreferred = body.indexOf("applyPreferredProxySelection(initSelectorManager(configContent))")
        assertTrue(startClients >= 0)
        assertTrue(applyPreferred > startClients)
        val preferredBody = source
            .substringAfter("internal fun SingBoxService.resolvePreferredProxyTag(")
            .substringBefore("internal suspend fun SingBoxService.applyPreferredProxySelection(preferredTag: String?)")
        assertFalse(preferredBody.contains("VpnStateStore.getSelectedNodeLabel()"))
        assertFalse(preferredBody.contains("activeNodeId"))
    }

    @Test
    fun confirmedStartupSelectionIsNotDiscardedByColdVpnRepository() {
        val source = File("src/main/java/com/kunk/singbox/service/vpn/SingBoxCommandRuntime.kt")
            .readText(Charsets.UTF_8)
        val body = source
            .substringAfter("internal suspend fun SingBoxService.applyPreferredProxySelection(preferredTag: String?)")
            .substringBefore("internal fun SingBoxService.launchPostStartTasks(configContent: String)")

        assertTrue(body.contains("resolveConfirmedProxyRuntimeLabel("))
        assertFalse(body.contains("resolveNodeNameFromOutboundTag"))
        assertFalse(body.contains("VpnStateStore.setActiveLabel(null)"))
    }

    @Test
    fun startupDoesNotSelectAlreadySelectedProxyAgain() {
        val source = File("src/main/java/com/kunk/singbox/service/vpn/SingBoxCommandRuntime.kt")
            .readText(Charsets.UTF_8)
        val body = source
            .substringAfter("internal suspend fun SingBoxService.applyPreferredProxySelection(preferredTag: String?)")
            .substringBefore("internal fun SingBoxService.launchPostStartTasks(configContent: String)")

        assertTrue(body.contains("commandManager.getSelectedOutbound(\"PROXY\")"))
        assertTrue(body.contains("equals(preferredTag, ignoreCase = true)"))
    }

    private fun assertOrdered(
        source: String,
        scopeStart: String,
        first: String,
        second: String
    ) {
        val scope = source.substringAfter(scopeStart)
        assertTrue(scope.indexOf(first) in 0 until scope.indexOf(second))
    }

    @Test
    fun startupLabelIsInitializedBeforeStartingStateIsPublished() {
        val vpnSource = File("src/main/java/com/kunk/singbox/service/vpn/SingBoxLifecycleRuntime.kt")
            .readText(Charsets.UTF_8)
        assertOrdered(vpnSource, "SingBoxService.ACTION_START -> {", "initializeStartupNodeLabel", "updateServiceState")
        assertOrdered(
            vpnSource,
            "internal fun SingBoxService.handleStickyRestartIntent() {",
            "initializeStartupNodeLabel",
            "updateServiceState"
        )

        val proxySource = File("src/main/java/com/kunk/singbox/service/proxy/ProxyCoreRuntime.kt")
            .readText(Charsets.UTF_8)
        assertOrdered(
            proxySource,
            "internal fun ProxyOnlyService.startCore(configPath: String, recoveryIntentLease: RecoveryIntentLease) {",
            "initializeStartupNodeLabel",
            "notifyRemoteState"
        )
    }

    @Test
    fun shutdownCancelsPostStartTasksWithoutWaitingForStartupCompletion() {
        val serviceSource = File("src/main/java/com/kunk/singbox/service/vpn/SingBoxManagerCallbacks.kt")
            .readText(Charsets.UTF_8)
        val shutdownSource = File("src/main/java/com/kunk/singbox/service/manager/ShutdownManager.kt")
            .readText(Charsets.UTF_8)

        assertTrue(serviceSource.contains("override fun cancelPostStartJob(): Job?"))
        assertTrue(shutdownSource.contains("callbacks.cancelPostStartJob()"))
        assertFalse(shutdownSource.contains("jobsToJoin.forEach { it.join() }"))
    }

    @Test
    fun stopCompletionClearsStartingBeforeQueuedRestart() {
        val source = File("src/main/java/com/kunk/singbox/service/vpn/SingBoxManagerCallbacks.kt")
            .readText(Charsets.UTF_8)
        val completionBody = source
            .substringAfter("override fun completeStop(")
            .substringBefore("override fun startVpn(configPath: String, recoveryIntentLease: RecoveryIntentLease?)")

        val clearStartingIndex = completionBody.indexOf("SingBoxService.isStarting = false")
        val clearStoppingIndex = completionBody.indexOf("isStopping = false")
        assertTrue(clearStartingIndex >= 0)
        assertTrue(clearStartingIndex < clearStoppingIndex)
    }

    @Test
    fun serviceCapturesLifecycleTokenBeforeSchedulingAndTracksFullRestart() {
        val source = File("src/main/java/com/kunk/singbox/service/vpn/SingBoxControlRuntime.kt")
            .readText(Charsets.UTF_8)
        val startBody = source
            .substringAfter("internal fun SingBoxService.startVpn(")
            .substringBefore("internal fun SingBoxService.continueStartVpnAfterForeground")
        val tokenIndex = startBody.indexOf("coreManager.captureStartToken()")
        val scheduleIndex = startBody.indexOf("continueStartVpnAfterForeground(configPath, startToken, recoveryLease)")
        assertTrue(tokenIndex >= 0)
        assertTrue(scheduleIndex > tokenIndex)

        val continueBody = source
            .substringAfter("internal fun SingBoxService.continueStartVpnAfterForeground(")
            .substringBefore("internal fun SingBoxService.stopVpn(")
        assertTrue(continueBody.contains("startToken = startToken"))

        val stopBody = source
            .substringAfter("internal fun SingBoxService.stopVpn(")
            .substringBefore("internal fun SingBoxService.updateTileState()")
        val beginStopIndex = stopBody.indexOf("coreManager.beginStop()")
        val alreadyStoppingIndex = stopBody.indexOf("if (isStopping)")
        assertTrue(beginStopIndex >= 0)
        assertTrue(alreadyStoppingIndex > beginStopIndex)

        val restartBody = source
            .substringAfter("internal fun SingBoxService.performFullRestart(configPath: String) {")
            .substringBefore("internal fun SingBoxService.performHotReloadSyncRuntime")
        assertTrue(restartBody.contains("pendingStartConfigPath = configPath"))
        assertTrue(restartBody.contains("stopVpn(stopService = false, recoveryIntentLease = recoveryIntentLease)"))
        assertFalse(restartBody.contains("?: synchronized(this) { pendingRecoveryIntentLease }"))
        assertFalse(restartBody.contains("serviceScope.launch"))
    }

    @Test
    fun resourceRestartCarriesExactLeaseIntoRestartAndStop() {
        val startupSource = File("src/main/java/com/kunk/singbox/service/vpn/SingBoxStartupRuntime.kt")
            .readText(Charsets.UTF_8)
        val controlSource = File("src/main/java/com/kunk/singbox/service/vpn/SingBoxControlRuntime.kt")
            .readText(Charsets.UTF_8)
        val resourceRestartBody = startupSource
            .substringAfter("override fun restartCore(reason: String, attemptId: Long): Boolean {")
            .substringBefore("override fun recycleProcess")
        val fullRestartBody = controlSource
            .substringAfter("internal fun SingBoxService.performFullRestart(configPath: String) {")
            .substringAfter("internal fun SingBoxService.performFullRestart(")
            .substringBefore("internal fun SingBoxService.performHotReloadSyncRuntime")
        val stopBody = controlSource
            .substringAfter("internal fun SingBoxService.stopVpn(")
            .substringBefore("internal fun SingBoxService.updateTileState()")

        assertTrue(
            startupSource.contains(
                "internal fun SingBoxService.claimResourceRecoveryIntent(attemptId: Long): RecoveryIntentLease?"
            )
        )
        assertTrue(resourceRestartBody.contains("val recoveryIntentLease = claimResourceRecoveryIntent(attemptId)"))
        assertTrue(resourceRestartBody.contains("performFullRestart(configPath, recoveryIntentLease)"))
        assertTrue(fullRestartBody.contains("pendingRecoveryIntentLease !== recoveryIntentLease"))
        assertTrue(fullRestartBody.contains("recoveryIntentLease.ownerId !== resourceGuardOwnerId"))
        assertTrue(fullRestartBody.contains("val resourceRecoveryAttemptId = recoveryIntentLease.attemptId"))
        assertTrue(stopBody.contains("val cleanupRecoveryAttemptId = cleanupRecoveryLease.attemptId"))
        assertFalse(stopBody.contains("resourceRecoveryAttemptId: Long?"))
    }

    @Test
    fun commandServerIsPublishedOnlyAfterExactLifecycleGate() {
        val managerSource = File("src/main/java/com/kunk/singbox/service/manager/StartupManager.kt")
            .readText(Charsets.UTF_8)
        val serviceSource = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt")
            .readText(Charsets.UTF_8)
        val commandSource = File("src/main/java/com/kunk/singbox/service/manager/CommandManager.kt")
            .readText(Charsets.UTF_8)
        val runtimeSource = File("src/main/java/com/kunk/singbox/service/vpn/SingBoxCommandRuntime.kt")
            .readText(Charsets.UTF_8)
        val callbackBody = serviceSource
            .substringAfter("override fun createAndStartCommandServer(")
            .substringBefore("override fun launchPostStartTasks")
        val createIndex = callbackBody.indexOf("commandManager.createServer")
        val startIndex = callbackBody.indexOf("commandManager.startServer")
        val adoptCallIndex = callbackBody.indexOf("adoptCommandServerIfCurrent")
        val adoptionBody = runtimeSource
            .substringAfter("internal fun SingBoxService.adoptCommandServerIfCurrent(")
            .substringBefore("internal fun SingBoxService.isCommandServerStartupCurrentLocked(")

        assertTrue(
            managerSource.contains(
                "callbacks.createAndStartCommandServer(startToken, recoveryIntentLease).getOrThrow()"
            )
        )
        assertTrue(managerSource.contains("return@withContext StartResult.Superseded"))
        val startupPreparationIndex = callbackBody.indexOf("prepareCommandServerStartup")
        assertTrue(startupPreparationIndex in 0 until createIndex)
        assertTrue(createIndex in 0 until startIndex)
        assertTrue(adoptCallIndex > startIndex)
        val publishGateIndex = adoptionBody.indexOf("isCommandServerStartupCurrentLocked")
        val adoptIndex = adoptionBody.indexOf("commandManager.adoptServer")
        val corePublishIndex = adoptionBody.indexOf("coreManager.setCommandServer")
        assertTrue(publishGateIndex in 0 until adoptIndex)
        assertTrue(adoptIndex in 0 until corePublishIndex)
        assertTrue(callbackBody.contains("if (!adopted)"))
        assertTrue(callbackBody.contains("server?.close()"))

        val createBody = commandSource
            .substringAfter("fun createServer(platformInterface: PlatformInterface)")
            .substringBefore("fun startServer(server: CommandServer)")
        val startBody = commandSource
            .substringAfter("fun startServer(server: CommandServer)")
            .substringBefore("fun adoptServer(server: CommandServer)")
        val adoptBody = commandSource
            .substringAfter("fun adoptServer(server: CommandServer)")
            .substringBefore("fun startService(")
        assertFalse(createBody.contains("commandServer = server"))
        assertTrue(startBody.contains("server.start()"))
        assertTrue(adoptBody.contains("commandServer = server"))
        assertTrue(commandSource.contains("check(runtimeHandle == null)"))
        assertTrue(commandSource.contains("Cleared stale CommandServer before startup"))
        assertTrue(runtimeSource.contains("clearStaleServerForStartup().getOrThrow()"))
    }

    @Test
    fun defaultNetworkListenerStartIsOrderedBeforeServiceDestroyStop() {
        val source = File("src/main/java/com/kunk/singbox/service/vpn/SingBoxLifecycleRuntime.kt")
            .readText(Charsets.UTF_8)
        val onCreateBody = source
            .substringAfter("internal fun SingBoxService.onCreateRuntime()")
            .substringBefore("internal fun SingBoxService.onStartCommandRuntime")

        assertTrue(onCreateBody.contains("DefaultNetworkListener.start(manager, defaultNetworkListenerKey)"))
        assertFalse(
            onCreateBody.contains(
                "serviceScope.launch {\n                DefaultNetworkListener.start"
            )
        )
    }
}

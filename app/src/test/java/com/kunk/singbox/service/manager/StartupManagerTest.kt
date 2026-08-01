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
        val source = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt")
            .readText(Charsets.UTF_8)
        val body = source
            .substringAfter("protected fun launchPostStartTasks(configContent: String) {")
            .substringBefore("private fun isPostStartTaskActive(generation: Long): Boolean {")

        val startClients = body.indexOf("commandManager.startClients()")
        val applyPreferred = body.indexOf("applyPreferredProxySelection(initSelectorManager(configContent))")
        assertTrue(startClients >= 0)
        assertTrue(applyPreferred > startClients)
        val preferredBody = source
            .substringAfter("protected fun resolvePreferredProxyTag(")
            .substringBefore("protected fun applyPreferredProxySelection(preferredTag: String?)")
        assertFalse(preferredBody.contains("VpnStateStore.getSelectedNodeLabel()"))
        assertFalse(preferredBody.contains("activeNodeId"))
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
        val vpnSource = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt").readText(Charsets.UTF_8)
        assertOrdered(vpnSource, "SingBoxService.ACTION_START -> {", "initializeStartupNodeLabel", "updateServiceState")
        assertOrdered(
            vpnSource,
            "protected fun handleStickyRestartIntent() {",
            "initializeStartupNodeLabel",
            "updateServiceState"
        )

        val proxySource = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt").readText(Charsets.UTF_8)
        assertOrdered(
            proxySource,
            "private fun startCore(configPath: String, recoveryIntentLease: RecoveryIntentLease) {",
            "initializeStartupNodeLabel",
            "notifyRemoteState"
        )
    }

    @Test
    fun shutdownCancelsAndWaitsForPostStartTasks() {
        val serviceSource = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt")
            .readText(Charsets.UTF_8)
        val shutdownSource = File("src/main/java/com/kunk/singbox/service/manager/ShutdownManager.kt")
            .readText(Charsets.UTF_8)

        assertTrue(serviceSource.contains("override fun cancelPostStartJob(): Job?"))
        assertTrue(shutdownSource.contains("callbacks.cancelPostStartJob()"))
        assertTrue(shutdownSource.contains("jobsToJoin.forEach { it.join() }"))
    }

    @Test
    fun stopCompletionClearsStartingBeforeQueuedRestart() {
        val source = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt")
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
        val source = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt")
            .readText(Charsets.UTF_8)
        val startBody = source
            .substringAfter("protected fun startVpn(")
            .substringBefore("protected fun continueStartVpnAfterForeground")
        val tokenIndex = startBody.indexOf("coreManager.captureStartToken()")
        val scheduleIndex = startBody.indexOf("continueStartVpnAfterForeground(configPath, startToken, recoveryLease)")
        assertTrue(tokenIndex >= 0)
        assertTrue(scheduleIndex > tokenIndex)

        val continueBody = source
            .substringAfter("protected fun continueStartVpnAfterForeground(")
            .substringBefore("protected fun stopVpn(")
        assertTrue(continueBody.contains("startToken = startToken"))

        val stopBody = source
            .substringAfter("protected fun stopVpn(")
            .substringBefore("protected fun updateTileState()")
        val beginStopIndex = stopBody.indexOf("coreManager.beginStop()")
        val alreadyStoppingIndex = stopBody.indexOf("if (isStopping)")
        assertTrue(beginStopIndex >= 0)
        assertTrue(alreadyStoppingIndex > beginStopIndex)

        val restartBody = source
            .substringAfter("protected fun performFullRestart(configPath: String) {")
            .substringBefore("fun performHotReloadSync")
        assertTrue(restartBody.contains("pendingStartConfigPath = configPath"))
        assertTrue(restartBody.contains("stopVpn(stopService = false, recoveryIntentLease = recoveryIntentLease)"))
        assertFalse(restartBody.contains("?: synchronized(this) { pendingRecoveryIntentLease }"))
        assertFalse(restartBody.contains("serviceScope.launch"))
    }

    @Test
    fun resourceRestartCarriesExactLeaseIntoRestartAndStop() {
        val source = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt")
            .readText(Charsets.UTF_8)
        val resourceRestartBody = source
            .substringAfter("override fun restartCore(reason: String, attemptId: Long): Boolean {")
            .substringBefore("override fun recycleProcess")
        val fullRestartBody = source
            .substringAfter("private fun performFullRestart(")
            .substringBefore("fun performHotReloadSync")
        val stopBody = source
            .substringAfter("protected fun stopVpn(")
            .substringBefore("protected fun updateTileState()")

        assertTrue(source.contains("private fun claimResourceRecoveryIntent(attemptId: Long): RecoveryIntentLease?"))
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
        val callbackBody = serviceSource
            .substringAfter("override fun createAndStartCommandServer(")
            .substringBefore("override fun launchPostStartTasks")
        val createIndex = callbackBody.indexOf("commandManager.createServer")
        val startIndex = callbackBody.indexOf("commandManager.startServer")
        val publishGateIndex = callbackBody.indexOf("isCommandServerStartupCurrentLocked")
        val adoptIndex = callbackBody.indexOf("commandManager.adoptServer")
        val corePublishIndex = callbackBody.indexOf("coreManager.setCommandServer")

        assertTrue(
            managerSource.contains(
                "callbacks.createAndStartCommandServer(startToken, recoveryIntentLease).getOrThrow()"
            )
        )
        assertTrue(managerSource.contains("return@withContext StartResult.Superseded"))
        val initialLifecycleGate = callbackBody.indexOf(
            "isCommandServerStartupCurrent(startToken, recoveryIntentLease)"
        )
        assertTrue(initialLifecycleGate in 0 until createIndex)
        assertTrue(createIndex in 0 until startIndex)
        assertTrue(publishGateIndex in (startIndex + 1) until adoptIndex)
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
    }

    @Test
    fun defaultNetworkListenerStartIsOrderedBeforeServiceDestroyStop() {
        val source = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt")
            .readText(Charsets.UTF_8)
        val onCreateBody = source
            .substringAfter("override fun onCreate() {")
            .substringBefore("override fun onStartCommand")

        assertTrue(onCreateBody.contains("DefaultNetworkListener.start(manager, defaultNetworkListenerKey)"))
        assertFalse(
            onCreateBody.contains(
                "serviceScope.launch {\n                DefaultNetworkListener.start"
            )
        )
    }
}

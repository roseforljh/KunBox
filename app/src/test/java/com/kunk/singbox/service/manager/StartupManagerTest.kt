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
    fun successfulCoreStartDoesNotWaitForOptionalPostStartTasks() {
        val source = File("src/main/java/com/kunk/singbox/service/manager/StartupManager.kt")
            .readText(Charsets.UTF_8)

        val markRunning = source.indexOf("callbacks.setIsRunning(true)")
        val launchPostStart = source.indexOf("callbacks.launchPostStartTasks(configContent)")
        assertTrue(markRunning >= 0)
        assertTrue(launchPostStart > markRunning)
        assertTrue(!source.contains("callbacks.startCommandClients()"))
        assertTrue(!source.contains("callbacks.startHealthMonitor()"))
        assertTrue(!source.contains("callbacks.scheduleKeepaliveWorker()"))
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
            "private fun startCore(configPath: String) {",
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
    fun serviceCapturesLifecycleTokenBeforeSchedulingAndTracksFullRestart() {
        val source = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt")
            .readText(Charsets.UTF_8)
        val startBody = source
            .substringAfter("protected fun startVpn(configPath: String) {")
            .substringBefore("protected fun continueStartVpnAfterForeground")
        val tokenIndex = startBody.indexOf("coreManager.captureStartToken()")
        val scheduleIndex = startBody.indexOf("continueStartVpnAfterForeground(configPath, startToken)")
        assertTrue(tokenIndex >= 0)
        assertTrue(scheduleIndex > tokenIndex)

        val continueBody = source
            .substringAfter("protected fun continueStartVpnAfterForeground(configPath: String, startToken: Long)")
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
        assertTrue(restartBody.contains("stopVpn(stopService = false)"))
        assertFalse(restartBody.contains("serviceScope.launch"))
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

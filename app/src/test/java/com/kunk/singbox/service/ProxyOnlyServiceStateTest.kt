package com.kunk.singbox.service

import com.kunk.singbox.ipc.VpnStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProxyOnlyServiceStateTest {

    @Test
    fun runningCoreReloadsExplicitUserConfigButIgnoresRecoveryDuplicate() {
        assertTrue(
            ProxyOnlyService.shouldReloadRuntimeConfig(
                isRecoveryStart = false,
                isRunning = true,
                isStarting = false,
                configPath = "/data/user/0/app/files/running_config.json"
            )
        )
        assertFalse(
            ProxyOnlyService.shouldReloadRuntimeConfig(
                isRecoveryStart = true,
                isRunning = true,
                isStarting = false,
                configPath = "/data/user/0/app/files/running_config.json"
            )
        )
    }

    @Test
    fun explicitStopUsesItsInitiatorForProxyManualStopSemantics() {
        val source = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt")
            .readText(Charsets.UTF_8)
        val stopBranch = source
            .substringAfter("ACTION_STOP ->")
            .substringBefore("ACTION_SWITCH_NODE ->")

        assertTrue(stopBranch.contains("VpnStopInitiator.fromWireValue"))
        assertTrue(stopBranch.contains("VpnStateStore.setManuallyStopped(stopInitiator.isManualStop)"))
        assertTrue(stopBranch.contains("lastStopInitiator = stopInitiator"))
        assertTrue(stopBranch.contains("notifyRemoteState(state = ServiceState.STOPPING)"))
        assertTrue(stopBranch.contains("stopCore(stopService = true, recoveryIntentLease = recoveryIntentLease)"))
    }

    @Test
    fun duplicateStopActionIsIgnoredBeforeReplacingRecoveryLease() {
        val source = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt")
            .readText(Charsets.UTF_8)
        val stopBranch = source
            .substringAfter("ACTION_STOP ->")
            .substringBefore("ACTION_FORCE_STOP ->")

        val guardIndex = stopBranch.indexOf("shouldIgnoreDuplicateHardStop")
        val leaseIndex = stopBranch.indexOf("setNonResourceRecoveryIntent(false)")
        assertTrue(guardIndex >= 0)
        assertTrue(leaseIndex > guardIndex)
    }

    @Test
    fun successfulProxyStartClearsManualStopMarker() {
        val source = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt")
            .readText(Charsets.UTF_8)
        val startBody = source
            .substringAfter("private fun startCore(")
            .substringBefore("private fun restrictLocalNetworkListenIfNeeded")

        assertTrue(startBody.contains("VpnStateStore.setManuallyStopped(false)"))
    }

    @Test
    fun `destroy keeps recovery markers when proxy mode is still active`() {
        // 意外销毁不得清 mode：shouldClear=false，onDestroy 走保留意图分支
        assertFalse(
            ProxyOnlyService.shouldClearRuntimeStateOnDestroy(
                isRunning = false,
                isStarting = false,
                isStopping = false,
                pending = "",
                mode = VpnStateStore.CoreMode.PROXY
            )
        )
        assertFalse(
            ProxyOnlyService.shouldClearRuntimeStateOnDestroy(
                isRunning = true,
                isStarting = false,
                isStopping = false,
                pending = "",
                mode = VpnStateStore.CoreMode.PROXY
            )
        )
    }

    @Test
    fun unexpectedDestroySourcePreservesModeIntent() {
        val source = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt")
            .readText(Charsets.UTF_8)
        assertTrue(source.contains("preserveRecoveryIntentOnUnexpectedDestroy"))
        assertTrue(source.contains("mode == VpnStateStore.CoreMode.PROXY"))
        // 意外路径不得 setMode(NONE)
        val preserveBody = source
            .substringAfter("private fun preserveRecoveryIntentOnUnexpectedDestroy()")
            .substringBefore("override fun onTaskRemoved")
        assertFalse(preserveBody.contains("setMode(VpnStateStore.CoreMode.NONE)"))
        assertFalse(preserveBody.contains("clearRuntimeState()"))
    }

    @Test
    fun `destroy clears runtime markers during final stop`() {
        assertTrue(
            ProxyOnlyService.shouldClearRuntimeStateOnDestroy(
                isRunning = false,
                isStarting = false,
                isStopping = true,
                pending = "stopping",
                mode = VpnStateStore.CoreMode.PROXY
            )
        )
    }

    @Test
    fun `non final stop keeps recovery markers for switch node`() {
        assertFalse(ProxyOnlyService.shouldClearRuntimeStateAfterStop(stopService = false))
        assertTrue(ProxyOnlyService.shouldClearRuntimeStateAfterStop(stopService = true))
    }

    @Test
    fun `destroy clears pending starting even before core runs`() {
        assertTrue(
            ProxyOnlyService.shouldClearRuntimeStateOnDestroy(
                isRunning = false,
                isStarting = false,
                isStopping = false,
                pending = "starting",
                mode = VpnStateStore.CoreMode.NONE
            )
        )
    }

    @Test
    fun `destroy skips cleanup when no runtime marker remains`() {
        assertFalse(
            ProxyOnlyService.shouldClearRuntimeStateOnDestroy(
                isRunning = false,
                isStarting = false,
                isStopping = false,
                pending = "",
                mode = VpnStateStore.CoreMode.NONE
            )
        )
    }

    @Test
    fun `core start continues only after foreground start succeeds`() {
        assertTrue(ProxyOnlyService.shouldContinueCoreStartAfterForegroundResult(true))
        assertFalse(ProxyOnlyService.shouldContinueCoreStartAfterForegroundResult(false))
    }

    @Test
    fun `start without config path must enter foreground before generation`() {
        assertTrue(
            ProxyOnlyService.shouldStartForegroundBeforeConfigGeneration(
                action = ProxyOnlyService.ACTION_START,
                configPath = null
            )
        )
        assertFalse(
            ProxyOnlyService.shouldStartForegroundBeforeConfigGeneration(
                action = ProxyOnlyService.ACTION_STOP,
                configPath = null
            )
        )
        assertFalse(
            ProxyOnlyService.shouldStartForegroundBeforeConfigGeneration(
                action = ProxyOnlyService.ACTION_START,
                configPath = "/data/user/0/com.kunk.singbox/files/config.json"
            )
        )
    }

    @Test
    fun `proxy only service does not kill process when port remains unavailable`() {
        val source = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt").readText()
        val stopCore = source
            .substringAfter("private fun stopCore(")
            .substringBefore("private fun startRuntimeCommandClient")

        assertFalse(stopCore.contains("killProcess("))
        assertTrue(source.contains("private fun forceStopProcess(reason: String)"))
    }

    @Test
    fun `proxy only service blocks local network settings before core start`() {
        val source = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt").readText(Charsets.UTF_8)

        assertTrue(source.contains("if (!LocalNetworkPermission.canApplySettings(this@ProxyOnlyService, settings))"))
        assertTrue(source.contains("val reason = LocalNetworkPermission.MISSING_PERMISSION_ERROR"))
        assertTrue(source.contains("setLastErrorIfCurrent(recoveryIntentLease, reason)"))
        assertTrue(source.contains("return@launch"))
    }

    @Test
    fun `proxy only service restricts wildcard listeners when local network permission is missing`() {
        val source = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt").readText(Charsets.UTF_8)

        assertTrue(source.contains("private fun restrictLocalNetworkListenIfNeeded(configContent: String): String"))
        assertTrue(source.contains("if (!LocalNetworkPermission.shouldRestrictLanListen(this)) return configContent"))
        assertTrue(source.contains("LocalNetworkPermission.restrictInboundListen(inbound)"))
    }

    @Test
    fun `proxy only foreground service uses special use runtime type`() {
        val source = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt").readText(Charsets.UTF_8)

        assertTrue(source.contains("ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE"))
        assertTrue(
            source.contains(
                "startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)"
            )
        )
    }

    @Test
    fun `proxy only notification uses core status speed and shared setting`() {
        val serviceSource = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt")
            .readText(Charsets.UTF_8)
        val notificationSource = File("src/main/java/com/kunk/singbox/service/ProxyOnlyNotification.kt")
            .readText(Charsets.UTF_8)

        assertTrue(serviceSource.contains("addCommand(Libbox.CommandStatus)"))
        assertTrue(serviceSource.contains("trafficMonitor.updateTotals("))
        assertTrue(serviceSource.contains("it.showNotificationSpeed"))
        assertTrue(notificationSource.contains("R.string.notification_speed_format"))
    }

    @Test
    fun `lazy start job is registered before execution`() {
        val source = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt").readText(Charsets.UTF_8)
        val startBody = source.substringAfter("private fun startCore(")
            .substringBefore("private fun restrictLocalNetworkListenIfNeeded")
        val declaration = startBody.indexOf("serviceScope.launch(start = CoroutineStart.LAZY)")
        val registration = startBody.indexOf("startJob = nextStartJob")
        val execution = startBody.indexOf("nextStartJob.start()")

        assertTrue(declaration >= 0)
        assertTrue(registration > declaration)
        assertTrue(execution > registration)
    }

    @Test
    fun `command server and running state share exact lease gates`() {
        val source = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt").readText(Charsets.UTF_8)
        val startBody = source.substringAfter("private fun startCore(")
            .substringBefore("private fun restrictLocalNetworkListenIfNeeded")
        val serverGate = startBody.indexOf("val server = synchronized(this@ProxyOnlyService)")
        val serverCreation = startBody.indexOf("Libbox.newCommandServer", serverGate)
        val serverStart = startBody.indexOf("createdServer.start()", serverCreation)
        val serviceStart = startBody.indexOf("createdServer.startOrReloadService", serverStart)
        val runningGate = startBody.indexOf("val baselineLease = synchronized(this@ProxyOnlyService)")
        val runningPublish = startBody.indexOf("isRunning = true", runningGate)

        assertTrue(serverGate >= 0)
        assertTrue(serverCreation > serverGate)
        assertTrue(serverStart > serverCreation)
        assertTrue(serviceStart > serverStart)
        assertTrue(runningGate > serviceStart)
        assertTrue(runningPublish > runningGate)
        assertTrue(startBody.contains("activeStartRecoveryIntentLease !== recoveryIntentLease"))
        assertTrue(startBody.contains("commandServer !== server"))
    }

    @Test
    fun `hard stop consumes its queued exact lease after startup exits`() {
        val source = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt").readText(Charsets.UTF_8)
        val stopBody = source.substringAfter("private fun stopCore(")
            .substringBefore("private fun startRuntimeCommandClient")
        val hardStopBody = stopBody.substringAfter("hardStopLease != null ->")
            .substringBefore("ServiceStateHolder.isRecoveryIntentCurrent(recoveryIntentLease) ->")
        val join = stopBody.indexOf("jobToJoin?.join()")
        val release = stopBody.indexOf("BoxWrapperManager.release()")
        val close = stopBody.indexOf("serverToClose.closeService()")

        assertTrue(stopBody.contains("pendingStopRecoveryIntentLease = recoveryIntentLease"))
        assertTrue(stopBody.contains("val exactHardStopLease = pendingStopRecoveryIntentLease?.takeIf"))
        assertTrue(hardStopBody.contains("consumeRecoveryIntentOnFailure("))
        assertTrue(hardStopBody.contains("hardStopLease"))
        assertFalse(hardStopBody.contains("consumeRecoveryIntentOnFailure(recoveryIntentLease)"))
        assertTrue(join >= 0)
        assertTrue(release > join)
        assertTrue(close > release)
    }

    @Test
    fun `destroy invalidates startup before closing native resources`() {
        val source = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt").readText(Charsets.UTF_8)
        val destroyBody = source.substringAfter("override fun onDestroy()")
        val invalidation = destroyBody.indexOf("activeStartRecoveryIntentLease = null")
        val cancellation = destroyBody.indexOf("startJobToCancel?.cancel()")
        val close = destroyBody.indexOf("serverToClose?.closeService()")
        val release = destroyBody.indexOf("BoxWrapperManager.release()")

        assertTrue(invalidation >= 0)
        assertTrue(cancellation > invalidation)
        assertTrue(close > cancellation)
        assertTrue(release > close)
        assertTrue(source.contains("if (!cleanupSupervisorJob.isActive)"))
        assertTrue(source.contains("cleanupScope.launch(start = CoroutineStart.ATOMIC)"))
        assertTrue(source.contains("withContext(NonCancellable)"))
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun `atomic cleanup enters non cancellable section after parent cancellation`() = runBlocking {
        val parent = SupervisorJob().apply { cancel() }
        var cleanupRan = false

        CoroutineScope(Dispatchers.Default + parent).launch(start = CoroutineStart.ATOMIC) {
            withContext(NonCancellable) {
                cleanupRan = true
            }
        }.join()

        assertTrue(cleanupRan)
    }

    @Test
    fun `runtime command client is owned before connect can fail`() {
        val source = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt").readText(Charsets.UTF_8)
        val clientBody = source.substringAfter("private fun startRuntimeCommandClient()")
            .substringBefore("private fun createRuntimeCommandOptions")
        val creation = clientBody.indexOf("val client = Libbox.newCommandClient")
        val ownership = clientBody.indexOf("runtimeCommandClient = client")
        val connect = clientBody.indexOf("client.connect()")

        assertTrue(creation >= 0)
        assertTrue(ownership > creation)
        assertTrue(connect > ownership)
    }
}

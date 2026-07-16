package com.kunk.singbox.service

import com.kunk.singbox.ipc.VpnStateStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProxyOnlyServiceStateTest {

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

        assertFalse(source.contains("killProcess("))
    }

    @Test
    fun `proxy only service blocks local network settings before core start`() {
        val source = File("src/main/java/com/kunk/singbox/service/ProxyOnlyService.kt").readText(Charsets.UTF_8)

        assertTrue(source.contains("if (!LocalNetworkPermission.canApplySettings(this@ProxyOnlyService, settings))"))
        assertTrue(source.contains("val reason = LocalNetworkPermission.MISSING_PERMISSION_ERROR"))
        assertTrue(source.contains("setLastError(reason)"))
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
}

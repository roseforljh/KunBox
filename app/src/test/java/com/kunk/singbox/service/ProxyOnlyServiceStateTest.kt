package com.kunk.singbox.service

import com.kunk.singbox.ipc.VpnStateStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProxyOnlyServiceStateTest {

    @Test
    fun `destroy keeps recovery markers when proxy mode is still active`() {
        assertFalse(
            ProxyOnlyService.shouldClearRuntimeStateOnDestroyForTest(
                isRunning = false,
                isStarting = false,
                isStopping = false,
                pending = "",
                mode = VpnStateStore.CoreMode.PROXY
            )
        )
    }

    @Test
    fun `destroy clears runtime markers during final stop`() {
        assertTrue(
            ProxyOnlyService.shouldClearRuntimeStateOnDestroyForTest(
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
        assertFalse(ProxyOnlyService.shouldClearRuntimeStateAfterStopForTest(stopService = false))
        assertTrue(ProxyOnlyService.shouldClearRuntimeStateAfterStopForTest(stopService = true))
    }

    @Test
    fun `destroy clears pending starting even before core runs`() {
        assertTrue(
            ProxyOnlyService.shouldClearRuntimeStateOnDestroyForTest(
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
            ProxyOnlyService.shouldClearRuntimeStateOnDestroyForTest(
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
        assertTrue(ProxyOnlyService.shouldContinueCoreStartAfterForegroundResultForTest(true))
        assertFalse(ProxyOnlyService.shouldContinueCoreStartAfterForegroundResultForTest(false))
    }

    @Test
    fun `start without config path must enter foreground before generation`() {
        assertTrue(
            ProxyOnlyService.shouldStartForegroundBeforeConfigGenerationForTest(
                action = ProxyOnlyService.ACTION_START,
                configPath = null
            )
        )
        assertFalse(
            ProxyOnlyService.shouldStartForegroundBeforeConfigGenerationForTest(
                action = ProxyOnlyService.ACTION_STOP,
                configPath = null
            )
        )
        assertFalse(
            ProxyOnlyService.shouldStartForegroundBeforeConfigGenerationForTest(
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
}

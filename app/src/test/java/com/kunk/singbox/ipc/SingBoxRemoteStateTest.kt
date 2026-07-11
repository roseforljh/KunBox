package com.kunk.singbox.ipc

import com.kunk.singbox.service.ServiceState
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SingBoxRemoteStateTest {

    @Test
    fun `pending starting returns STARTING`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "starting",
            isActive = false,
            mode = VpnStateStore.CoreMode.NONE,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.STARTING, result)
    }

    @Test
    fun `pending stopping returns STOPPING`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "stopping",
            isActive = false,
            mode = VpnStateStore.CoreMode.NONE,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.STOPPING, result)
    }

    @Test
    fun `isActive true with hasVpnTransport false returns STOPPED`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = true,
            mode = VpnStateStore.CoreMode.VPN,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.STOPPED, result)
    }

    @Test
    fun `isActive true with hasVpnTransport true returns RUNNING`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = true,
            mode = VpnStateStore.CoreMode.VPN,
            hasVpnTransport = true
        )
        assertEquals(ServiceState.RUNNING, result)
    }

    @Test
    fun `mode PROXY with hasVpnTransport true returns RUNNING`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = false,
            mode = VpnStateStore.CoreMode.PROXY,
            hasVpnTransport = true
        )
        assertEquals(ServiceState.RUNNING, result)
    }

    @Test
    fun `mode PROXY with hasVpnTransport false returns RUNNING`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = false,
            mode = VpnStateStore.CoreMode.PROXY,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.RUNNING, result)
    }

    @Test
    fun `stale vpn mode without pending or transport returns STOPPED`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = false,
            mode = VpnStateStore.CoreMode.VPN,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.STOPPED, result)
    }

    @Test
    fun `all false returns STOPPED`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = false,
            mode = VpnStateStore.CoreMode.NONE,
            hasVpnTransport = false
        )
        assertEquals(ServiceState.STOPPED, result)
    }

    @Test
    fun `pending takes precedence over isActive`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "stopping",
            isActive = true,
            mode = VpnStateStore.CoreMode.VPN,
            hasVpnTransport = true
        )
        assertEquals(ServiceState.STOPPING, result)
    }

    @Test
    fun `isActive takes precedence over mode PROXY`() {
        val result = SingBoxRemote.resolvePersistedStateFromValues(
            pending = "",
            isActive = true,
            mode = VpnStateStore.CoreMode.PROXY,
            hasVpnTransport = true
        )
        assertEquals(ServiceState.RUNNING, result)
    }

    @Test
    fun `disconnected stop state preserves revoke terminal error`() {
        val result = SingBoxRemote.resolveDisconnectedStopState(
            storedLastError = "VPN revoked by system (another VPN may have started)",
            storedManuallyStopped = true
        )

        assertTrue(result.preserveLastError)
        assertEquals("VPN revoked by system (another VPN may have started)", result.lastError)
        assertTrue(result.manuallyStopped)
    }

    @Test
    fun `disconnected stop state clears transient error for non terminal stop`() {
        val result = SingBoxRemote.resolveDisconnectedStopState(
            storedLastError = "temporary failure",
            storedManuallyStopped = false
        )

        assertFalse(result.preserveLastError)
        assertEquals("", result.lastError)
        assertFalse(result.manuallyStopped)
    }

    @Test
    fun `service loss reconnects when vpn still exists or proxy mode is active`() {
        assertTrue(
            SingBoxRemote.shouldReconnectAfterServiceLoss(
                systemVpn = true,
                storedManuallyStopped = false,
                storedMode = VpnStateStore.CoreMode.VPN
            )
        )
        assertFalse(
            SingBoxRemote.shouldReconnectAfterServiceLoss(
                systemVpn = true,
                storedManuallyStopped = true,
                storedMode = VpnStateStore.CoreMode.VPN
            )
        )
        assertTrue(
            SingBoxRemote.shouldReconnectAfterServiceLoss(
                systemVpn = false,
                storedManuallyStopped = false,
                storedMode = VpnStateStore.CoreMode.PROXY
            )
        )
        assertFalse(
            SingBoxRemote.shouldReconnectAfterServiceLoss(
                systemVpn = false,
                storedManuallyStopped = false,
                storedMode = VpnStateStore.CoreMode.VPN
            )
        )
        assertFalse(
            SingBoxRemote.shouldReconnectAfterServiceLoss(
                systemVpn = false,
                storedManuallyStopped = false,
                storedMode = VpnStateStore.CoreMode.NONE
            )
        )
    }

    @Test
    fun `ensure bound rebinds stale live reference`() {
        val result = resolveSingBoxEnsureBoundAction(
            connectionActive = true,
            bound = true,
            servicePresent = true,
            serviceAlive = false,
            bindingInProgress = false
        )

        assertEquals(EnsureBoundAction.REBIND, result)
    }

    @Test
    fun `ensure bound skips healthy connection and waits for active bind`() {
        assertEquals(
            EnsureBoundAction.NONE,
            resolveSingBoxEnsureBoundAction(
                connectionActive = true,
                bound = true,
                servicePresent = true,
                serviceAlive = true,
                bindingInProgress = false
            )
        )
        assertEquals(
            EnsureBoundAction.WAIT_FOR_BIND,
            resolveSingBoxEnsureBoundAction(
                connectionActive = true,
                bound = false,
                servicePresent = false,
                serviceAlive = false,
                bindingInProgress = true
            )
        )
    }

    @Test
    fun `ensure bound syncs live service state before returning healthy connection`() {
        val source = File("src/main/java/com/kunk/singbox/ipc/SingBoxRemote.kt").readText()
        val start = source.indexOf("fun ensureBound(context: Context)")
        val body = source.substring(
            start,
            source.indexOf("fun queryAndSyncState(context: Context)", start)
        )

        assertTrue(body.contains("EnsureBoundAction.NONE -> {"))
        assertTrue(body.contains("if (!syncStateFromService(currentService))"))
        assertTrue(body.contains("rebind(context)"))
    }

    @Test
    fun `service state sync failure is observable and triggers rebind`() {
        val source = File("src/main/java/com/kunk/singbox/ipc/SingBoxRemote.kt").readText()
        val syncStart = source.indexOf("private fun syncStateFromService")
        val syncBody = source.substring(syncStart, source.indexOf("private fun hasSystemVpn", syncStart))
        val queryStart = source.indexOf("fun queryAndSyncState(context: Context)")
        val queryBody = source.substring(queryStart, source.indexOf("fun rebind(context: Context)", queryStart))
        val recoveryStart = source.indexOf("fun instantRecovery(")
        val recoveryBody = source.substring(
            recoveryStart,
            source.indexOf("@Deprecated", recoveryStart)
        )

        assertTrue(syncBody.contains("ISingBoxService?): Boolean"))
        assertTrue(syncBody.contains("}.isSuccess"))
        assertTrue(queryBody.contains("val synced = syncStateFromService(s)"))
        assertTrue(queryBody.contains("rebind(context)"))
        assertTrue(recoveryBody.contains("val ok = syncStateFromService(s)"))
        assertTrue(recoveryBody.contains("return recovering"))
        assertFalse(recoveryBody.contains("callback?.invoke(RecoveryResult.AlreadyConnected)"))
    }

    @Test
    fun `concurrent recovery requests keep every final callback`() {
        val source = File("src/main/java/com/kunk/singbox/ipc/SingBoxRemote.kt").readText(Charsets.UTF_8)
        val start = source.indexOf("private val pendingRecoveryCallbacks")
        val body = source.substring(
            start,
            source.indexOf("private fun clearPendingUrlTestRequests", start)
        )

        assertTrue(body.contains("ConcurrentLinkedQueue<(RecoveryResult) -> Unit>()"))
        assertTrue(body.contains("callback?.let(pendingRecoveryCallbacks::add)"))
        assertTrue(body.contains("pendingRecoveryCallbacks.poll()"))
        assertFalse(body.contains("pendingRecoveryCallback = callback"))
    }
}

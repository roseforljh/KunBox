package com.kunk.singbox.ipc

import com.kunk.singbox.service.ServiceState
import org.junit.Assert.*
import org.junit.Test

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
}

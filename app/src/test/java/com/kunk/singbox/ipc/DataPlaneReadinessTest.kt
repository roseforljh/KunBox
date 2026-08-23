package com.kunk.singbox.ipc

import com.kunk.singbox.service.ServiceState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataPlaneReadinessTest {
    @Test
    fun `api 30 vpn is ready only for matching owner`() {
        val snapshot = readyVpnSnapshot()
        assertTrue(snapshot.isReady(ServiceState.RUNNING, VpnStateStore.CoreMode.VPN, true, 30, NOW_MS))
        assertFalse(
            snapshot.copy(systemVpnOwnerStatus = VpnOwnerStatus.UNKNOWN)
                .isReady(ServiceState.RUNNING, VpnStateStore.CoreMode.VPN, true, 30, NOW_MS)
        )
        assertFalse(
            snapshot.copy(systemVpnOwnerStatus = VpnOwnerStatus.MISMATCH)
                .isReady(ServiceState.RUNNING, VpnStateStore.CoreMode.VPN, true, 30, NOW_MS)
        )
    }

    @Test
    fun `legacy vpn requires matching nonzero network handle`() {
        val snapshot = readyVpnSnapshot().copy(
            systemVpnOwnerStatus = VpnOwnerStatus.UNKNOWN,
            ownedVpnNetworkHandle = 41L,
            observedVpnNetworkHandle = 41L
        )
        assertTrue(snapshot.isReady(ServiceState.RUNNING, VpnStateStore.CoreMode.VPN, true, 29, NOW_MS))
        assertFalse(
            snapshot.copy(observedVpnNetworkHandle = 42L)
                .isReady(ServiceState.RUNNING, VpnStateStore.CoreMode.VPN, true, 29, NOW_MS)
        )
        assertFalse(
            snapshot.copy(foreignVpnDetected = true)
                .isReady(ServiceState.RUNNING, VpnStateStore.CoreMode.VPN, true, 29, NOW_MS)
        )
        assertFalse(
            snapshot.copy(ownedVpnNetworkLost = true)
                .isReady(ServiceState.RUNNING, VpnStateStore.CoreMode.VPN, true, 29, NOW_MS)
        )
    }

    @Test
    fun `freshness boundary and time rollback fail closed`() {
        val snapshot = readyVpnSnapshot().copy(updatedAtElapsedMs = NOW_MS)
        assertTrue(snapshot.isFresh(NOW_MS + 9_999L))
        assertFalse(snapshot.isFresh(NOW_MS + 10_000L))
        assertFalse(snapshot.isFresh(NOW_MS - 1L))
    }

    @Test
    fun `proxy readiness does not require tun`() {
        val snapshot = readyVpnSnapshot().copy(
            tunEstablished = false,
            systemVpnTransport = false,
            systemVpnOwnerStatus = VpnOwnerStatus.UNKNOWN,
            ownedVpnNetworkHandle = 0L,
            observedVpnNetworkHandle = 0L
        )
        assertTrue(snapshot.isReady(ServiceState.RUNNING, VpnStateStore.CoreMode.PROXY, true, 36, NOW_MS))
    }

    private fun readyVpnSnapshot() = DataPlaneReadinessSnapshot(
        status = DataPlaneStatus.READY,
        tunEstablished = true,
        systemVpnTransport = true,
        systemVpnOwnerStatus = VpnOwnerStatus.MATCH,
        coreReady = true,
        selectorReady = true,
        recoveryActive = false,
        updatedAtElapsedMs = NOW_MS,
        serviceInstanceId = "instance",
        ownedVpnNetworkHandle = 41L,
        observedVpnNetworkHandle = 41L
    )

    private companion object {
        const val NOW_MS = 50_000L
    }
}

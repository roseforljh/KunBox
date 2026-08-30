package com.kunk.singbox.ipc

import com.kunk.singbox.service.ServiceState
import org.junit.Assert.assertEquals
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

    @Test
    fun `root readiness requires watchdog and installed rules`() {
        val snapshot = readyVpnSnapshot().copy(
            tunEstablished = false,
            systemVpnTransport = false,
            rootPid = 321,
            rootRuntimeSessionId = "root-session",
            rootRoutingGeneration = 7L,
            rootConfigSha256 = DIGEST,
            rootSidecarSha256 = DIGEST,
            rootStaticPlanSha256 = DIGEST,
            rootAppRoutingSha256 = DIGEST,
            rootResolvedPlanSha256 = DIGEST,
            rootWatchdogReady = true,
            rootRulesInstalled = true
        )

        assertTrue(snapshot.isReady(ServiceState.RUNNING, VpnStateStore.CoreMode.ROOT, true, 36, NOW_MS))
        assertFalse(
            snapshot.copy(rootWatchdogReady = false)
                .isReady(ServiceState.RUNNING, VpnStateStore.CoreMode.ROOT, true, 36, NOW_MS)
        )
        assertFalse(
            snapshot.copy(rootRulesInstalled = false)
                .isReady(ServiceState.RUNNING, VpnStateStore.CoreMode.ROOT, true, 36, NOW_MS)
        )
    }

    @Test
    fun `unknown owner blocks verification without declaring unprotected`() {
        val starting = readyVpnSnapshot()
            .beginVpnSession(serviceInstanceId = "instance", sessionId = 7L)
            .copy(coreReady = true, selectorReady = true, tunEstablished = true)
        val verifying = starting.observeVpnNetwork(
            ownership = VpnNetworkOwnership.UNKNOWN,
            networkHandle = 41L,
            serviceState = ServiceState.RUNNING
        )

        assertEquals(DataPlaneStatus.BLOCKING, verifying.status)
        assertEquals(VpnOwnerStatus.UNKNOWN, verifying.systemVpnOwnerStatus)
        assertFalse(verifying.foreignVpnDetected)
        assertFalse(verifying.isReady(ServiceState.RUNNING, VpnStateStore.CoreMode.VPN, true, 36, NOW_MS))
    }

    @Test
    fun `matching owner restores readiness for the current session`() {
        val snapshot = readyVpnSnapshot()
            .beginVpnSession(serviceInstanceId = "instance", sessionId = 8L)
            .copy(
                coreReady = true,
                selectorReady = true,
                tunEstablished = true,
                updatedAtElapsedMs = NOW_MS
            )
            .observeVpnNetwork(VpnNetworkOwnership.UNKNOWN, 41L, ServiceState.RUNNING)
            .observeVpnNetwork(VpnNetworkOwnership.OWNED, 41L, ServiceState.RUNNING)

        assertEquals(DataPlaneStatus.READY, snapshot.status)
        assertEquals(VpnOwnerStatus.MATCH, snapshot.systemVpnOwnerStatus)
        assertEquals(8L, snapshot.vpnSessionId)
        assertTrue(snapshot.isReady(ServiceState.RUNNING, VpnStateStore.CoreMode.VPN, true, 36, NOW_MS))
    }

    @Test
    fun `matching owner clears an earlier ownership failure for the same session`() {
        val snapshot = readyVpnSnapshot().copy(
            status = DataPlaneStatus.FAILED_UNPROTECTED,
            systemVpnOwnerStatus = VpnOwnerStatus.MISMATCH,
            foreignVpnDetected = true,
            vpnSessionId = 8L
        ).observeVpnNetwork(VpnNetworkOwnership.OWNED, 41L, ServiceState.RUNNING)

        assertEquals(DataPlaneStatus.READY, snapshot.status)
        assertEquals(VpnOwnerStatus.MATCH, snapshot.systemVpnOwnerStatus)
        assertFalse(snapshot.foreignVpnDetected)
    }

    @Test
    fun `control plane updates cannot hide an explicit protection failure`() {
        val foreign = readyVpnSnapshot().copy(
            status = DataPlaneStatus.FAILED_UNPROTECTED,
            foreignVpnDetected = true
        )
        val blocked = readyVpnSnapshot().copy(status = DataPlaneStatus.FAILED_BLOCKED)

        assertEquals(DataPlaneStatus.FAILED_UNPROTECTED, foreign.resolveVpnStatus(canBeReady = true))
        assertEquals(DataPlaneStatus.FAILED_BLOCKED, blocked.resolveVpnStatus(canBeReady = true))
    }

    @Test
    fun `new session clears previous ownership failure`() {
        val failed = readyVpnSnapshot().copy(
            foreignVpnDetected = true,
            status = DataPlaneStatus.FAILED_UNPROTECTED,
            vpnSessionId = 3L
        )
        val next = failed.beginVpnSession(serviceInstanceId = "instance", sessionId = 4L)

        assertEquals(DataPlaneStatus.STARTING, next.status)
        assertFalse(next.foreignVpnDetected)
        assertFalse(next.ownedVpnNetworkLost)
        assertEquals(4L, next.vpnSessionId)
    }

    @Test
    fun `lost old network handle cannot fail the current tunnel`() {
        val snapshot = readyVpnSnapshot().copy(ownedVpnNetworkHandle = 41L, vpnSessionId = 9L)
        val unchanged = snapshot.observeOwnedVpnNetworkLost(42L, ServiceState.RUNNING)

        assertEquals(snapshot, unchanged)
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
        val DIGEST = "a".repeat(64)
    }
}

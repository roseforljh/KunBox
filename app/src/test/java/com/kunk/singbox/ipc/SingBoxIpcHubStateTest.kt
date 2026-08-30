package com.kunk.singbox.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.kunk.singbox.service.ServiceState
import java.io.File

class SingBoxIpcHubStateTest {

    @Test
    fun `starting a new runtime clears the previous terminal error`() {
        assertEquals(
            "",
            resolveUpdatedRuntimeLastError(
                state = ServiceState.STARTING,
                explicitError = null,
                previousError = "VPN 启动失败：分应用 VPN 策略状态提交失败"
            )
        )
        assertEquals(
            "current failure",
            resolveUpdatedRuntimeLastError(
                state = ServiceState.STARTING,
                explicitError = "current failure",
                previousError = "old failure"
            )
        )
    }

    @Test
    fun `binder death preserves terminal error only for manually stopped state`() {
        assertTrue(
            SingBoxIpcHub.shouldPreserveLastErrorOnBinderDied(
                lastError = "VPN revoked by system (another VPN may have started)",
                manuallyStopped = true
            )
        )
        assertFalse(
            SingBoxIpcHub.shouldPreserveLastErrorOnBinderDied(
                lastError = "",
                manuallyStopped = true
            )
        )
        assertFalse(
            SingBoxIpcHub.shouldPreserveLastErrorOnBinderDied(
                lastError = "temporary failure",
                manuallyStopped = false
            )
        )
    }

    @Test
    fun `resolve realtime delay keeps positive match only`() {
        val delay = SingBoxIpcHub.resolveRealtimeUrlTestNodeDelay(
            nodeTag = "node-a",
            progressResults = listOf(
                mapOf("node-b" to 80),
                mapOf("node-a" to 135)
            )
        )

        assertEquals(135, delay)
    }

    @Test
    fun `resolve realtime delay ignores cached results from other tags`() {
        val delay = SingBoxIpcHub.resolveRealtimeUrlTestNodeDelay(
            nodeTag = "node-a",
            progressResults = listOf(
                mapOf("node-b" to 80),
                mapOf("node-c" to 95)
            )
        )

        assertEquals(-1, delay)
    }

    @Test
    fun `stale running cache becomes stopped when core service is gone`() {
        assertEquals(
            ServiceState.STOPPED.ordinal,
            SingBoxIpcHub.resolveVisibleStateOrdinal(
                cachedStateOrdinal = ServiceState.RUNNING.ordinal,
                liveCoreState = null
            )
        )
    }

    @Test
    fun `stale starting cache becomes stopped when core service is gone`() {
        assertEquals(
            ServiceState.STOPPED.ordinal,
            SingBoxIpcHub.resolveVisibleStateOrdinal(
                cachedStateOrdinal = ServiceState.STARTING.ordinal,
                liveCoreState = null
            )
        )
    }

    @Test
    fun `live vpn and proxy states stay visible`() {
        assertEquals(
            ServiceState.RUNNING.ordinal,
            SingBoxIpcHub.resolveVisibleStateOrdinal(
                cachedStateOrdinal = ServiceState.RUNNING.ordinal,
                liveCoreState = ServiceState.RUNNING
            )
        )
        assertEquals(
            ServiceState.STARTING.ordinal,
            SingBoxIpcHub.resolveVisibleStateOrdinal(
                cachedStateOrdinal = ServiceState.STARTING.ordinal,
                liveCoreState = ServiceState.STARTING
            )
        )
    }

    @Test
    fun `stopped cache cannot hide a live running core`() {
        assertEquals(
            ServiceState.RUNNING.ordinal,
            SingBoxIpcHub.resolveVisibleStateOrdinal(
                cachedStateOrdinal = ServiceState.STOPPED.ordinal,
                liveCoreState = ServiceState.RUNNING
            )
        )
    }

    @Test
    fun `finishing vpn service exposes its tracked stopped state`() {
        assertEquals(
            ServiceState.STOPPED.ordinal,
            SingBoxIpcHub.resolveVisibleStateOrdinal(
                cachedStateOrdinal = ServiceState.STOPPING.ordinal,
                liveCoreState = ServiceState.STOPPED
            )
        )

        val source = File("src/main/java/com/kunk/singbox/ipc/SingBoxIpcHub.kt").readText(Charsets.UTF_8)
        val body = source
            .substringAfter("private fun currentLiveCoreState()")
            .substringBefore("internal fun resolveVisibleStateOrdinal")

        assertTrue(body.contains("vpnService?.currentServiceState()"))
        assertFalse(body.contains("ServiceStateHolder.instance != null -> ServiceState.STOPPING"))
    }

    @Test
    fun `missing live core does not manufacture an unprotected failure`() {
        val source = File("src/main/java/com/kunk/singbox/ipc/SingBoxIpcHub.kt")
            .readText(Charsets.UTF_8)
        val body = source
            .substringAfter("private fun currentStateSnapshot()")
            .substringBefore("private fun VpnStateStore.RuntimeStateSnapshot.toBundle()")

        assertTrue(body.contains("DataPlaneReadinessSnapshot.stopped(serviceInstanceId)"))
        assertFalse(body.contains("status = DataPlaneStatus.FAILED_UNPROTECTED"))
    }

    @Test
    fun readinessBundleCarriesVpnSessionId() {
        val source = File("src/main/java/com/kunk/singbox/ipc/SingBoxIpcHub.kt")
            .readText(Charsets.UTF_8)
        assertTrue(source.contains("READINESS_VPN_SESSION"))
        assertTrue(source.contains("vpnSessionId = getLong(SingBoxIpcHub.READINESS_VPN_SESSION, 0L)"))
        assertTrue(source.contains("putLong(READINESS_VPN_SESSION, vpnSessionId)"))
    }

    @Test
    fun `ipc service registration preserves live readiness`() {
        val source = File("src/main/java/com/kunk/singbox/ipc/SingBoxIpcHub.kt").readText(Charsets.UTF_8)
        val body = source
            .substringAfter("fun registerService(service: SingBoxIpcService)")
            .substringBefore("fun serviceInstanceId()")

        assertFalse(body.contains("readiness = DataPlaneReadinessSnapshot.stopped"))
        assertTrue(body.contains("restoreSnapshotOnServiceRegistration"))
    }

    @Test
    fun `live registration keeps in memory ready data plane`() {
        val current = VpnStateStore.RuntimeStateSnapshot(
            generation = 10L,
            stateOrdinal = ServiceState.RUNNING.ordinal,
            readiness = DataPlaneReadinessSnapshot(
                status = DataPlaneStatus.READY,
                tunEstablished = true,
                systemVpnTransport = true,
                coreReady = true,
                selectorReady = true,
                generation = 10L
            )
        )
        val persisted = VpnStateStore.RuntimeStateSnapshot(
            generation = 11L,
            stateOrdinal = ServiceState.STOPPED.ordinal,
            readiness = DataPlaneReadinessSnapshot.stopped()
        )

        val restored = SingBoxIpcHub.restoreSnapshotOnServiceRegistration(
            current = current,
            persisted = persisted,
            liveCoreState = ServiceState.RUNNING,
            serviceInstanceId = "ipc-new",
            nowElapsedMs = 12_345L
        )

        assertEquals(ServiceState.RUNNING.ordinal, restored.stateOrdinal)
        assertEquals(DataPlaneStatus.READY, restored.readiness.status)
        assertTrue(restored.readiness.tunEstablished)
        assertEquals("ipc-new", restored.readiness.serviceInstanceId)
        assertEquals(12_345L, restored.readiness.updatedAtElapsedMs)
    }
}

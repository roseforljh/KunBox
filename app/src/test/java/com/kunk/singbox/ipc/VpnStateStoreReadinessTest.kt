package com.kunk.singbox.ipc

import com.kunk.singbox.service.ServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnStateStoreReadinessTest {
    @Test
    fun `runtime snapshot json round trips readiness`() {
        val snapshot = VpnStateStore.RuntimeStateSnapshot(
            generation = 9L,
            stateOrdinal = ServiceState.RUNNING.ordinal,
            readiness = DataPlaneReadinessSnapshot(
                status = DataPlaneStatus.RECOVERING,
                tunEstablished = true,
                recoveryActive = true,
                serviceInstanceId = "instance",
                generation = 9L
            )
        )
        val decoded = requireNotNull(VpnStateStore.decodeRuntimeStateSnapshot(
            VpnStateStore.encodeRuntimeStateSnapshot(snapshot)
        ))
        assertEquals(DataPlaneStatus.RECOVERING, decoded.readiness.status)
        assertTrue(decoded.readiness.tunEstablished)
        assertEquals(9L, decoded.readiness.generation)
    }

    @Test
    fun `next snapshot advances service and readiness generation together`() {
        val current = VpnStateStore.RuntimeStateSnapshot(generation = 5L)
        val next = VpnStateStore.buildNextRuntimeStateSnapshot(current, monotonicCandidate = 7L) {
            it.copy(readiness = it.readiness.copy(status = DataPlaneStatus.FAILED_BLOCKED))
        }
        assertEquals(7L, next.generation)
        assertEquals(next.generation, next.readiness.generation)
        assertEquals(DataPlaneStatus.FAILED_BLOCKED, next.readiness.status)
    }
}

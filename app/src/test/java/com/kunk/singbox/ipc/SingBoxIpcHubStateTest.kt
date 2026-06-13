package com.kunk.singbox.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.kunk.singbox.service.ServiceState

class SingBoxIpcHubStateTest {

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
            SingBoxIpcHub.resolveVisibleStateOrdinalForTest(
                cachedStateOrdinal = ServiceState.RUNNING.ordinal,
                liveCoreState = null
            )
        )
    }

    @Test
    fun `stale starting cache becomes stopped when core service is gone`() {
        assertEquals(
            ServiceState.STOPPED.ordinal,
            SingBoxIpcHub.resolveVisibleStateOrdinalForTest(
                cachedStateOrdinal = ServiceState.STARTING.ordinal,
                liveCoreState = null
            )
        )
    }

    @Test
    fun `live vpn and proxy states stay visible`() {
        assertEquals(
            ServiceState.RUNNING.ordinal,
            SingBoxIpcHub.resolveVisibleStateOrdinalForTest(
                cachedStateOrdinal = ServiceState.RUNNING.ordinal,
                liveCoreState = ServiceState.RUNNING
            )
        )
        assertEquals(
            ServiceState.STARTING.ordinal,
            SingBoxIpcHub.resolveVisibleStateOrdinalForTest(
                cachedStateOrdinal = ServiceState.STARTING.ordinal,
                liveCoreState = ServiceState.STARTING
            )
        )
    }
}

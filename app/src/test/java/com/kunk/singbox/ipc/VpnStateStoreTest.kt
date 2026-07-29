package com.kunk.singbox.ipc

import android.system.OsConstants
import com.google.gson.JsonParser
import com.kunk.singbox.service.ServiceState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnStateStoreTest {

    @Test
    fun testCoreModeEnumValues() {
        assertEquals(3, VpnStateStore.CoreMode.values().size)
        assertEquals("NONE", VpnStateStore.CoreMode.NONE.name)
        assertEquals("VPN", VpnStateStore.CoreMode.VPN.name)
        assertEquals("PROXY", VpnStateStore.CoreMode.PROXY.name)
    }

    @Test
    fun testCoreModeValueOf() {
        assertEquals(VpnStateStore.CoreMode.NONE, VpnStateStore.CoreMode.valueOf("NONE"))
        assertEquals(VpnStateStore.CoreMode.VPN, VpnStateStore.CoreMode.valueOf("VPN"))
        assertEquals(VpnStateStore.CoreMode.PROXY, VpnStateStore.CoreMode.valueOf("PROXY"))
    }

    @Test
    fun testCoreModeOrdinal() {
        assertEquals(0, VpnStateStore.CoreMode.NONE.ordinal)
        assertEquals(1, VpnStateStore.CoreMode.VPN.ordinal)
        assertEquals(2, VpnStateStore.CoreMode.PROXY.ordinal)
    }

    @Test
    fun fileDescriptorExhaustionRequiresEmfileInCauseChain() {
        val emfile = IllegalStateException("emfile")
        val unrelated = IllegalStateException("other")

        assertTrue(
            isFileDescriptorExhaustion(IllegalStateException("lock failed", emfile)) { cause ->
                if (cause === emfile) OsConstants.EMFILE else null
            }
        )
        assertFalse(
            isFileDescriptorExhaustion(IllegalStateException("lock failed", unrelated)) { cause ->
                if (cause === unrelated) OsConstants.EMFILE + 1 else null
            }
        )
    }

    @Test
    fun runtimeStateSnapshotJsonRoundTripKeepsAllFields() {
        val snapshot = VpnStateStore.RuntimeStateSnapshot(
            generation = 42L,
            stateOrdinal = ServiceState.RUNNING.ordinal,
            activeLabel = "节点 A",
            lastError = "",
            manuallyStopped = false
        )

        assertEquals(
            snapshot,
            VpnStateStore.decodeRuntimeStateSnapshot(
                VpnStateStore.encodeRuntimeStateSnapshot(snapshot)
            )
        )
        assertNull(VpnStateStore.decodeRuntimeStateSnapshot("{"))
    }

    @Test
    fun runtimeStateSnapshotJsonUsesStableSchemaAndRejectsUnknownLayouts() {
        val encoded = VpnStateStore.encodeRuntimeStateSnapshot(
            VpnStateStore.RuntimeStateSnapshot(
                generation = 7L,
                stateOrdinal = ServiceState.STARTING.ordinal,
                activeLabel = "节点 B",
                lastError = "",
                manuallyStopped = true
            )
        )
        val keys = JsonParser.parseString(encoded).asJsonObject.keySet()

        assertEquals(
            setOf("generation", "stateOrdinal", "activeLabel", "lastError", "manuallyStopped"),
            keys
        )
        assertNull(VpnStateStore.decodeRuntimeStateSnapshot("{}"))
        assertNull(
            VpnStateStore.decodeRuntimeStateSnapshot(
                """{"a":7,"b":1,"c":"节点 B","d":"","e":true}"""
            )
        )
    }

    @Test
    fun runtimeStateSnapshotJsonNormalizesUnsafeValues() {
        val decoded = VpnStateStore.decodeRuntimeStateSnapshot(
            """
                {
                  "generation": -5,
                  "stateOrdinal": 999,
                  "activeLabel": "节点 C",
                  "lastError": "error",
                  "manuallyStopped": false
                }
            """.trimIndent()
        )

        assertEquals(0L, decoded?.generation)
        assertEquals(ServiceState.STOPPED.ordinal, decoded?.stateOrdinal)
        assertEquals("节点 C", decoded?.activeLabel)
        assertEquals("error", decoded?.lastError)
        assertEquals(false, decoded?.manuallyStopped)
    }

    @Test
    fun runtimeStateGenerationNeverMovesBackward() {
        assertEquals(11L, VpnStateStore.nextRuntimeGeneration(10L, 5L))
        assertEquals(20L, VpnStateStore.nextRuntimeGeneration(10L, 20L))
        assertEquals(Long.MAX_VALUE, VpnStateStore.nextRuntimeGeneration(Long.MAX_VALUE, 20L))
    }

    @Test
    fun resourceRecoveryBudgetLimitsActionsAndResetsAfterOneHour() {
        var state = VpnStateStore.ResourceRecoveryBudgetState()
        repeat(2) {
            val result = VpnStateStore.consumeResourceRecoveryBudget(
                state,
                VpnStateStore.ResourceRecoveryAction.CORE_RESTART,
                nowMs = 1_000L
            )
            assertTrue(result.consumed)
            state = result.state
        }
        assertFalse(
            VpnStateStore.consumeResourceRecoveryBudget(
                state,
                VpnStateStore.ResourceRecoveryAction.CORE_RESTART,
                nowMs = 2_000L
            ).consumed
        )

        val reclaim = VpnStateStore.consumeResourceRecoveryBudget(
            state,
            VpnStateStore.ResourceRecoveryAction.PROCESS_RECLAIM,
            nowMs = 2_000L
        )
        assertTrue(reclaim.consumed)
        assertFalse(
            VpnStateStore.consumeResourceRecoveryBudget(
                reclaim.state,
                VpnStateStore.ResourceRecoveryAction.PROCESS_RECLAIM,
                nowMs = 3_000L
            ).consumed
        )

        val reset = VpnStateStore.consumeResourceRecoveryBudget(
            reclaim.state,
            VpnStateStore.ResourceRecoveryAction.CORE_RESTART,
            nowMs = 1_000L + VpnStateStore.RESOURCE_RECOVERY_WINDOW_MS
        )
        assertTrue(reset.consumed)
        assertEquals(1, reset.state.coreRestartCount)
        assertEquals(0, reset.state.processReclaimCount)
    }

    @Test
    fun runtimeStateFileLockSerializesReadModifyWriteTransactions() {
        val lockFile = File.createTempFile("kunbox-runtime-state", ".lock")
        val lock = CrossProcessRuntimeStateLock(lockFile)
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAttempted = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit {
                lock.withLock {
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

            val second = executor.submit {
                secondAttempted.countDown()
                lock.withLock { secondEntered.countDown() }
            }
            assertTrue(secondAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))

            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            lockFile.delete()
        }
    }
}

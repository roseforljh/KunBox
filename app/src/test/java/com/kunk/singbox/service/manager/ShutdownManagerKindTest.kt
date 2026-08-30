package com.kunk.singbox.service.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutdownManagerKindTest {
    @Test
    fun `core recovery and tun rebuild retain vpn protection`() {
        assertTrue(ShutdownManager.shouldRetainTun(ShutdownManager.ShutdownKind.CORE_RECOVERY))
        assertTrue(ShutdownManager.shouldRetainTun(ShutdownManager.ShutdownKind.TUN_REBUILD))
        assertFalse(ShutdownManager.shouldRetainTun(ShutdownManager.ShutdownKind.FINAL_STOP))
    }

    @Test
    fun `recovery timeout never kills vpn process`() {
        assertFalse(
            ShutdownManager.shouldForceStopProcessOnTimeout(ShutdownManager.ShutdownKind.CORE_RECOVERY)
        )
        assertFalse(
            ShutdownManager.shouldForceStopProcessOnTimeout(ShutdownManager.ShutdownKind.TUN_REBUILD)
        )
        assertTrue(
            ShutdownManager.shouldForceStopProcessOnTimeout(ShutdownManager.ShutdownKind.FINAL_STOP)
        )
    }

    @Test
    fun `user stop during recovery escalates to full resource cleanup`() {
        assertTrue(
            ShutdownManager.requiresEscalatedFinalCleanup(
                ShutdownManager.ShutdownKind.CORE_RECOVERY,
                completionStop = true
            )
        )
        assertTrue(
            ShutdownManager.requiresEscalatedFinalCleanup(
                ShutdownManager.ShutdownKind.TUN_REBUILD,
                completionStop = true
            )
        )
        assertFalse(
            ShutdownManager.requiresEscalatedFinalCleanup(
                ShutdownManager.ShutdownKind.FINAL_STOP,
                completionStop = true
            )
        )
    }
}

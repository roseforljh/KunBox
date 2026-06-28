package com.kunk.singbox.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryNetworkTypeChangedProbeTest {

    @Test
    fun networkTypeChangedStrongSignalRequiresPhysicalTunnelAndNoPendingKernelRecovery() {
        assertTrue(
            SingBoxService.hasStrongNetworkTypeChangedRecoverySignal(
                probeSucceeded = true,
                tunnelProbeSucceeded = true,
                networkRecoveryNeeded = false
            )
        )
        assertFalse(
            SingBoxService.hasStrongNetworkTypeChangedRecoverySignal(
                probeSucceeded = true,
                tunnelProbeSucceeded = false,
                networkRecoveryNeeded = false
            )
        )
        assertFalse(
            SingBoxService.hasStrongNetworkTypeChangedRecoverySignal(
                probeSucceeded = false,
                tunnelProbeSucceeded = true,
                networkRecoveryNeeded = false
            )
        )
        assertFalse(
            SingBoxService.hasStrongNetworkTypeChangedRecoverySignal(
                probeSucceeded = true,
                tunnelProbeSucceeded = true,
                networkRecoveryNeeded = true
            )
        )
    }
}

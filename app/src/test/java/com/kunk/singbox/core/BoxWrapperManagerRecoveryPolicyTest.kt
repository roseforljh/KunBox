package com.kunk.singbox.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxWrapperManagerRecoveryPolicyTest {

    @Test
    fun powerAndNetworkOperationsUseOfficialCommandServer() {
        val source = File("src/main/java/com/kunk/singbox/core/BoxWrapperManager.kt").readText()

        assertTrue(source.contains("server.pause()"))
        assertTrue(source.contains("server.wake()"))
        assertTrue(source.contains("server.resetNetwork()"))
    }

    @Test
    fun wrapperContainsNoPrivateRecoveryOrExternalProbeLogic() {
        val source = File("src/main/java/com/kunk/singbox/core/BoxWrapperManager.kt").readText()

        assertFalse(source.contains("smartRecover"))
        assertFalse(source.contains("RecoveryLevel"))
        assertFalse(source.contains("RecoveryMode"))
        assertFalse(source.contains("generate_204"))
        assertFalse(source.contains("resetAllConnections"))
        assertFalse(source.contains("closeAllTrackedConnections"))
        assertFalse(source.contains("closeIdleConnections"))
        assertFalse(source.contains("checkNetworkRecoveryNeeded"))
    }
}

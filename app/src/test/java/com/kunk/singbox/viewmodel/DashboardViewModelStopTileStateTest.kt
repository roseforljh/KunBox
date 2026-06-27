package com.kunk.singbox.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DashboardViewModelStopTileStateTest {

    @Test
    fun stopVpnPersistsTileOffStateBeforeSendingStopIntent() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/DashboardViewModel.kt").readText()
        val body = source.substring(
            source.indexOf("private fun stopVpn()"),
            source.indexOf("private fun startPingTest()")
        )

        val pendingIndex = body.indexOf("VpnTileService.persistVpnPending(context, \"stopping\")")
        val inactiveIndex = body.indexOf("VpnTileService.persistVpnState(context, false)")
        val firstStopIntentIndex = body.indexOf("context.startService(Intent(context,")
        val refreshIndex = body.indexOf("action = VpnTileService.ACTION_REFRESH_TILE")

        assertTrue(pendingIndex >= 0)
        assertTrue(inactiveIndex >= 0)
        assertTrue(firstStopIntentIndex >= 0)
        assertTrue(pendingIndex < firstStopIntentIndex)
        assertTrue(inactiveIndex < firstStopIntentIndex)
        assertTrue(refreshIndex >= 0)
    }
}

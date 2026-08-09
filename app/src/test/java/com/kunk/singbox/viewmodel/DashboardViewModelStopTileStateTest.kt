package com.kunk.singbox.viewmodel

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DashboardViewModelStopTileStateTest {

    @Test
    fun stopVpnKeepsDisconnectingUntilServiceStopIsConfirmed() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/DashboardViewModel.kt").readText()
        val body = source.substring(
            source.indexOf("private fun stopVpn()"),
            source.indexOf("private fun startPingTest()")
        )

        val pendingIndex = body.indexOf("VpnTileService.persistVpnPending(\"stopping\")")
        val stopIndex = body.indexOf("VpnServiceManager.stopVpn(context, VpnStopInitiator.USER_UI)")
        val refreshIndex = body.indexOf("action = VpnTileService.ACTION_REFRESH_TILE")

        assertTrue(pendingIndex >= 0)
        assertTrue(stopIndex >= 0)
        assertTrue(pendingIndex < stopIndex)
        assertTrue(refreshIndex >= 0)
        assertTrue(body.contains("ConnectionState.Disconnecting"))
        assertTrue(body.contains("ServiceState.STOPPED"))
        assertTrue(body.contains("performDisconnect()"))
        assertTrue(!body.contains("VpnTileService.persistVpnState(false)"))
    }
}

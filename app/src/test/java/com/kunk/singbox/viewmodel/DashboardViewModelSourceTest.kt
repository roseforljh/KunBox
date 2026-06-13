package com.kunk.singbox.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DashboardViewModelSourceTest {

    @Test
    fun dashboardTestAllNodesLatencyUsesUnfilteredRepositoryNodes() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/DashboardViewModel.kt").readText()
        val body = source.substring(
            source.indexOf("fun testAllNodesLatency()"),
            source.indexOf("private fun startTrafficMonitor()")
        )

        assertTrue(body.contains("val targetNodeIds = configRepository.nodes.value.map { it.id }"))
        assertFalse(body.contains("val targetNodeIds = nodes.value.map { it.id }"))
        assertTrue(body.contains("configRepository.testAllNodesLatency(targetNodeIds = targetNodeIds)"))
        assertFalse(body.contains("configRepository.testNodeLatency(targetNodeId)"))
    }

    @Test
    fun vpnPermissionDenialResetsConnectingState() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/DashboardViewModel.kt").readText()
        val body = source.substring(
            source.indexOf("fun onVpnPermissionResult(granted: Boolean)"),
            source.indexOf("fun updateAllSubscriptions()")
        )

        assertTrue(body.contains("if (!granted)"))
        assertTrue(body.contains("_connectionState.value = ConnectionState.Idle"))
        assertTrue(body.contains("startGraceUntilElapsedMs = null"))
        assertTrue(body.contains("return"))
    }

    @Test
    fun stopVpnStopsBothServicesWhenCoreModeIsUnknown() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/DashboardViewModel.kt").readText()
        val body = source.substring(
            source.indexOf("private fun stopVpn()"),
            source.indexOf("private fun startPingTest()")
        )

        assertTrue(body.contains("VpnStateStore.CoreMode.NONE -> {"))
        assertTrue(body.contains("context.startService(Intent(context, ProxyOnlyService::class.java).apply"))
        assertTrue(body.contains("context.startService(Intent(context, SingBoxService::class.java).apply"))
    }

    @Test
    fun refreshStateDoesNotTrustPersistedActiveAsConnectedUiState() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/DashboardViewModel.kt").readText()
        val body = source.substring(
            source.indexOf("fun refreshState()"),
            source.indexOf("fun toggleConnection()")
        )

        assertTrue(body.contains("resolveTrustedDashboardConnectionState("))
        assertFalse(body.contains("isActive -> ConnectionState.Connected"))
        assertFalse(body.contains("keeping Connected"))
    }

    @Test
    fun refreshStateDoesNotImportPersistedRecoveryStateIntoHomeUi() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/DashboardViewModel.kt").readText()
        val body = source.substring(
            source.indexOf("fun refreshState()"),
            source.indexOf("fun toggleConnection()")
        )

        assertTrue(body.contains("SingBoxRemote.ensureBound(context)"))
        assertFalse(body.contains("SingBoxRemote.instantRecovery(context)"))
    }
}

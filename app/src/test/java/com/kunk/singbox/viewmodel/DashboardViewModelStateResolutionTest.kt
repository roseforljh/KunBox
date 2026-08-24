package com.kunk.singbox.viewmodel

import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.ipc.DataPlaneReadinessSnapshot
import com.kunk.singbox.ipc.DataPlaneStatus
import com.kunk.singbox.ipc.VpnOwnerStatus
import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.service.ServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DashboardViewModelStateResolutionTest {

    @Test
    fun persistedActiveDoesNotCreateConnectedUiStateWithoutRunningService() {
        assertEquals(
            ConnectionState.Idle,
            resolveTrustedDashboardConnectionState(
                serviceState = ServiceState.STOPPED,
                ipcBound = false
            )
        )
        assertEquals(
            ConnectionState.Idle,
            resolveTrustedDashboardConnectionState(
                serviceState = ServiceState.STOPPED,
                ipcBound = true
            )
        )
    }

    @Test
    fun onlyTrustedServiceStateCanShowRunningUi() {
        assertEquals(
            ConnectionState.Idle,
            resolveTrustedDashboardConnectionState(
                serviceState = ServiceState.STARTING,
                ipcBound = false
            )
        )
        assertEquals(
            ConnectionState.Connecting,
            resolveTrustedDashboardConnectionState(
                serviceState = ServiceState.STARTING,
                ipcBound = true
            )
        )
        assertEquals(
            ConnectionState.Connected,
            resolveTrustedDashboardConnectionState(
                serviceState = ServiceState.RUNNING,
                ipcBound = true,
                readiness = DataPlaneReadinessSnapshot(
                    status = DataPlaneStatus.READY,
                    tunEstablished = true,
                    systemVpnTransport = true,
                    systemVpnOwnerStatus = VpnOwnerStatus.MATCH,
                    coreReady = true,
                    selectorReady = true,
                    updatedAtElapsedMs = 1_000L
                ),
                mode = VpnStateStore.CoreMode.VPN,
                apiLevel = 30,
                nowElapsedMs = 1_000L
            )
        )
    }

    @Test
    fun connectedUiPrefersRuntimeLabelOverUserSelection() {
        assertEquals(
            "麒麟",
            resolveDashboardDisplayedNodeName(
                connectionState = ConnectionState.Connected,
                runtimeLabel = "麒麟",
                selectedNodeDisplayName = "...24"
            )
        )
        assertEquals(
            "...24",
            resolveDashboardDisplayedNodeName(
                connectionState = ConnectionState.Idle,
                runtimeLabel = "麒麟",
                selectedNodeDisplayName = "...24"
            )
        )
        assertEquals(
            "...24",
            resolveDashboardDisplayedNodeName(
                connectionState = ConnectionState.Connecting,
                runtimeLabel = "旧节点",
                selectedNodeDisplayName = "...24"
            )
        )
        assertEquals(
            "...24",
            resolveDashboardDisplayedNodeName(
                connectionState = ConnectionState.Connecting,
                runtimeLabel = "",
                selectedNodeDisplayName = "...24"
            )
        )
    }

    @Test
    fun newStartReportsAnyErrorAfterTheOldErrorWasCleared() {
        assertFalse(
            DashboardViewModel.shouldPresentServiceError(
                connectionState = ConnectionState.Connecting,
                error = "Failed to restart VPN: null"
            )
        )
        assertTrue(
            DashboardViewModel.shouldReportStartError(
                currentError = "Failed to restart VPN: null"
            )
        )
        assertTrue(
            DashboardViewModel.shouldPresentServiceError(
                connectionState = ConnectionState.Error,
                error = "Libbox start failed"
            )
        )
        assertTrue(
            DashboardViewModel.shouldReportStartError(
                currentError = "Libbox start failed"
            )
        )
    }

    @Test
    fun boundStoppedServiceEndsConnectingAfterStartWasObservedOrGraceElapsed() {
        assertTrue(
            DashboardViewModel.shouldFinishStartAsStopped(
                serviceState = ServiceState.STOPPED,
                ipcBound = true,
                observedActiveState = true,
                elapsedMs = 0L
            )
        )
        assertTrue(
            DashboardViewModel.shouldFinishStartAsStopped(
                serviceState = ServiceState.STOPPED,
                ipcBound = true,
                observedActiveState = false,
                elapsedMs = DashboardViewModel.START_STOPPED_CONFIRM_MS
            )
        )
        assertFalse(
            DashboardViewModel.shouldFinishStartAsStopped(
                serviceState = ServiceState.STOPPED,
                ipcBound = false,
                observedActiveState = true,
                elapsedMs = DashboardViewModel.START_STOPPED_CONFIRM_MS
            )
        )
    }

    @Test
    fun startMonitorHasFiniteOverallTimeout() {
        assertFalse(
            DashboardViewModel.hasStartMonitorTimedOut(
                DashboardViewModel.START_MONITOR_TIMEOUT_MS - 1L
            )
        )
        assertTrue(
            DashboardViewModel.hasStartMonitorTimedOut(
                DashboardViewModel.START_MONITOR_TIMEOUT_MS
            )
        )
    }

    @Test
    fun runtimeChangesRequireFullRestart() {
        assertTrue(
            DashboardViewModel.requiresFullRestart(
                perAppSettingsChanged = true,
                tunSettingsChanged = false,
                routingModeChanged = false
            )
        )
        assertTrue(
            DashboardViewModel.requiresFullRestart(
                perAppSettingsChanged = false,
                tunSettingsChanged = true,
                routingModeChanged = false
            )
        )
        assertTrue(
            DashboardViewModel.requiresFullRestart(
                perAppSettingsChanged = false,
                tunSettingsChanged = false,
                routingModeChanged = true
            )
        )
        assertFalse(
            DashboardViewModel.requiresFullRestart(
                perAppSettingsChanged = false,
                tunSettingsChanged = false,
                routingModeChanged = false
            )
        )
    }

    @Test
    fun startMonitorClearsOldErrorsAndStopsTimedOutService() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/DashboardViewModel.kt")
            .readText(Charsets.UTF_8)

        assertTrue(source.contains("SingBoxRemote.clearLastErrorForNewStart()"))
        assertTrue(source.contains("VpnTileService.persistVpnPending(\"\")"))
        assertTrue(
            source.contains("VpnServiceManager.stopVpn(context, VpnStopInitiator.START_TIMEOUT)")
        )
        assertEquals(
            ConnectionState.Connecting,
            resolveTrustedDashboardConnectionState(
                serviceState = ServiceState.RUNNING,
                ipcBound = true
            )
        )
    }

    @Test
    fun onlyAnActuallyActiveOppositeCoreIsStoppedBeforeStartup() {
        assertFalse(
            DashboardViewModel.shouldStopOppositeService(
                VpnStateStore.CoreMode.VPN,
                VpnStateStore.CoreMode.NONE
            )
        )
        assertFalse(
            DashboardViewModel.shouldStopOppositeService(
                VpnStateStore.CoreMode.VPN,
                VpnStateStore.CoreMode.VPN
            )
        )
        assertTrue(
            DashboardViewModel.shouldStopOppositeService(
                VpnStateStore.CoreMode.VPN,
                VpnStateStore.CoreMode.PROXY
            )
        )
    }

    @Test
    fun currentNodePingUsesTheManualSingleNodeLatencyPath() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/DashboardViewModel.kt")
            .readText(Charsets.UTF_8)
        val body = source
            .substringAfter("private fun startPingTest()")
            .substringBefore("private fun stopPingTest()")

        assertTrue(body.contains("configRepository.testNodeLatency(targetNodeId)"))
        assertFalse(body.contains("configRepository.testAllNodesLatency"))
    }

    @Test
    fun mainActivityGatesAutoConnectOnRecoveryPolicy() {
        val source = File("src/main/java/com/kunk/singbox/MainActivity.kt")
            .readText(Charsets.UTF_8)

        assertTrue(source.contains("SingBoxRemote.ensureBound(context)"))
        assertTrue(source.contains("SingBoxRemote.queryAndSyncState(context)"))
        assertTrue(source.contains("if (!SingBoxRemote.isBound()) return@LaunchedEffect"))
        assertTrue(source.contains("SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value"))
        assertTrue(source.contains("autoConnectAttempted = true"))
        // mode / 持久化手动停不再参与 autoConnect；进入界面 once 尝试即可
        assertFalse(
            source.contains("hasRecoverableIntent = RecoveryPolicy.hasRecoverableIntent")
        )
        assertFalse(source.contains("VpnStateStore.isManuallyStopped()"))
        assertFalse(source.contains("shouldBlockAutoConnectForPersistedRuntime"))
    }

    @Test
    fun persistedActiveIsClearedOnBootOnlyWhenIpcConfirmsStopped() {
        // IPC 未绑定：无权清，避免"假停"窗口
        assertFalse(
            shouldClearPersistedActiveOnBoot(
                hasSystemVpn = false,
                persistedActive = true,
                mode = VpnStateStore.CoreMode.VPN,
                ipcBound = false,
                serviceState = ServiceState.STOPPED
            )
        )
        // IPC 绑定但服务仍在跑：不清
        assertFalse(
            shouldClearPersistedActiveOnBoot(
                hasSystemVpn = false,
                persistedActive = true,
                mode = VpnStateStore.CoreMode.VPN,
                ipcBound = true,
                serviceState = ServiceState.RUNNING
            )
        )
        // 系统 VPN 仍在：不清
        assertFalse(
            shouldClearPersistedActiveOnBoot(
                hasSystemVpn = true,
                persistedActive = true,
                mode = VpnStateStore.CoreMode.VPN,
                ipcBound = true,
                serviceState = ServiceState.STOPPED
            )
        )
        // IPC 确认 STOPPED + 无系统 VPN + persisted active：允许清
        assertTrue(
            shouldClearPersistedActiveOnBoot(
                hasSystemVpn = false,
                persistedActive = true,
                mode = VpnStateStore.CoreMode.VPN,
                ipcBound = true,
                serviceState = ServiceState.STOPPED
            )
        )
    }
}

package com.kunk.singbox.viewmodel

import com.kunk.singbox.shouldBlockAutoConnectForPersistedRuntime
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
                ipcBound = true
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
        assertTrue(source.contains("VpnServiceManager.stopVpn(context)"))
    }

    @Test
    fun persistedRuntimeBlocksAutoConnectUntilIpcResolves() {
        assertTrue(
            shouldBlockAutoConnectForPersistedRuntime(
                persistedActive = true,
                ipcBound = false,
                serviceState = ServiceState.STOPPED
            )
        )
        assertTrue(
            shouldBlockAutoConnectForPersistedRuntime(
                persistedActive = true,
                ipcBound = true,
                serviceState = ServiceState.RUNNING
            )
        )
        assertFalse(
            shouldBlockAutoConnectForPersistedRuntime(
                persistedActive = true,
                ipcBound = true,
                serviceState = ServiceState.STOPPED
            )
        )
        assertFalse(
            shouldBlockAutoConnectForPersistedRuntime(
                persistedActive = false,
                ipcBound = false,
                serviceState = ServiceState.STOPPED
            )
        )
    }

    @Test
    fun autoConnectWaitsForIpcBeforeTrustingPersistedActiveState() {
        val source = File("src/main/java/com/kunk/singbox/MainActivity.kt")
            .readText(Charsets.UTF_8)
        val conditionBody = source
            .substringAfter("fun shouldAutoConnect(persistedManuallyStopped: Boolean): Boolean")
            .substringBefore("val persistedManuallyStopped")
        val effectBody = source
            .substringAfter("LaunchedEffect(settings.autoConnect")
            .substringBefore("LaunchedEffect(settings.excludeFromRecent)")

        assertFalse(conditionBody.contains("VpnStateStore.getActive()"))
        assertTrue(effectBody.contains("while (VpnStateStore.getActive() && !SingBoxRemote.isBound()"))
        assertTrue(effectBody.contains("persistedRuntimeBlocksStart = shouldBlockAutoConnectForPersistedRuntime("))
    }
}

@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.kunk.singbox.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.kunk.singbox.manager.VpnServiceManager
import com.kunk.singbox.R
import com.kunk.singbox.repository.*
import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.model.ConnectionStats
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.VpnTileService
import com.kunk.singbox.service.manager.VpnStopInitiator
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

internal fun DashboardViewModel.stopVpnRuntime() {
    val context = getApplication<Application>()
    startCoreJob?.cancel()
    startCoreJob = null
    startMonitorJob?.cancel()
    startMonitorJob = null
    stopTrafficMonitorRuntime()
    stopPingTestRuntime()
    _connectionState.value = ConnectionState.Disconnecting
    _connectedAtElapsedMs.value = null
    _statsBase.value = ConnectionStats(0, 0, 0, 0, 0)
    VpnTileService.persistVpnPending("stopping")

    val stopResult = VpnServiceManager.stopVpn(context, VpnStopInitiator.USER_UI)
    if (stopResult.isFailure) {
        _connectionState.value = ConnectionState.Error
        return
    }

    context.startService(Intent(context, VpnTileService::class.java).apply {
        action = VpnTileService.ACTION_REFRESH_TILE
    })
    waitForStopConfirmationRuntime(context)
}

private fun DashboardViewModel.waitForStopConfirmationRuntime(context: Context) {
    stopConfirmJob?.cancel()
    stopConfirmJob = viewModelScope.launch {
        try {
            if (SingBoxRemote.state.value != ServiceState.STOPPED) {
                withTimeout(DashboardViewModel.STOP_CONFIRM_TIMEOUT_MS) {
                    SingBoxRemote.state.first { it == ServiceState.STOPPED }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(DashboardViewModel.TAG, "Stop confirmation timed out, forcing service process stop", e)
            VpnServiceManager.forceStop(context).onFailure { error ->
                Log.e(DashboardViewModel.TAG, "Failed to dispatch force stop", error)
            }
            SingBoxRemote.ensureBound(context)
            withTimeoutOrNull(DashboardViewModel.FORCE_STOP_CONFIRM_TIMEOUT_MS) {
                SingBoxRemote.state.first { it == ServiceState.STOPPED }
            }
            SingBoxRemote.queryAndSyncState(context)
        }

        val persistedStopped = !VpnStateStore.getActive() &&
            VpnStateStore.getPending().isBlank() &&
            VpnStateStore.getMode() == VpnStateStore.CoreMode.NONE
        if (SingBoxRemote.state.value == ServiceState.STOPPED || persistedStopped) {
            VpnTileService.persistVpnPending("")
            performDisconnect()
        } else {
            Log.e(DashboardViewModel.TAG, "Service did not confirm stop after force stop")
            setConnectionState(ConnectionState.Error)
        }
        stopConfirmJob = null
    }
}

internal fun DashboardViewModel.startPingTestRuntime() {
    if (_connectionState.value != ConnectionState.Connected || _isPingTesting.value) return

    val targetNodeId = activeNodeId.value
    if (targetNodeId.isNullOrBlank()) {
        Log.w(DashboardViewModel.TAG, "No active node to test ping")
        return
    }

    stopPingTestRuntime()
    pingTestJob = viewModelScope.launch {
        _isPingTesting.value = true
        try {
            configRepository.testNodeLatency(targetNodeId)
        } catch (e: Exception) {
            Log.e(DashboardViewModel.TAG, "Error during ping test", e)
        } finally {
            _isPingTesting.value = false
        }
    }
}

internal fun DashboardViewModel.stopPingTestRuntime() {
    pingTestJob?.cancel()
    pingTestJob = null
    _isPingTesting.value = false
}

internal fun DashboardViewModel.onVpnPermissionResultRuntime(granted: Boolean, startCore: () -> Unit) {
    _vpnPermissionNeeded.value = false
    if (!granted) {
        startGraceUntilElapsedMs = null
        startMonitorJob?.cancel()
        startMonitorJob = null
        _connectionState.value = ConnectionState.Idle
        return
    }
    startCore()
}

internal fun DashboardViewModel.updateAllSubscriptionsRuntime() {
    viewModelScope.launch {
        emitToast(getApplication<Application>().getString(R.string.common_loading))
        val result = configRepository.updateAllProfiles()
        emitToast(result.toDisplayMessage(getApplication()))
    }
}

internal fun DashboardViewModel.testAllNodesLatencyRuntime() {
    viewModelScope.launch {
        val targetNodeIds = configRepository.nodes.value.map { it.id }
        if (targetNodeIds.isEmpty()) return@launch
        emitToast(getApplication<Application>().getString(R.string.common_loading))
        configRepository.testAllNodesLatency(targetNodeIds = targetNodeIds)
        emitToast(getApplication<Application>().getString(R.string.dashboard_test_complete))
    }
}

internal fun DashboardViewModel.getActiveProfileNameRuntime(): String? =
    activeProfileId.value?.let { activeId -> profiles.value.find { it.id == activeId }?.name }

internal fun DashboardViewModel.getActiveNodeNameRuntime(): String? {
    val selectedName = activeNodeId.value?.let { activeId ->
        configRepository.getNodeById(activeId)?.displayName
    }
    return resolveDashboardDisplayedNodeName(
        connectionState = _connectionState.value,
        runtimeLabel = SingBoxRemote.activeLabel.value,
        selectedNodeDisplayName = selectedName
    )
}

private fun DashboardViewModel.stopTrafficMonitorRuntime() {
    trafficMonitor.stop()
}

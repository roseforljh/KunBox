package com.kunk.singbox.viewmodel

import com.kunk.singbox.model.ConnectionState
import com.kunk.singbox.service.ServiceState
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardViewModelStateResolutionTest {

    @Test
    fun persistedActiveDoesNotCreateConnectedUiStateWithoutRunningService() {
        assertEquals(
            ConnectionState.Idle,
            DashboardViewModel.resolveTrustedConnectionStateForTest(
                serviceState = ServiceState.STOPPED,
                ipcBound = false
            )
        )
        assertEquals(
            ConnectionState.Idle,
            DashboardViewModel.resolveTrustedConnectionStateForTest(
                serviceState = ServiceState.STOPPED,
                ipcBound = true
            )
        )
    }

    @Test
    fun onlyTrustedServiceStateCanShowRunningUi() {
        assertEquals(
            ConnectionState.Idle,
            DashboardViewModel.resolveTrustedConnectionStateForTest(
                serviceState = ServiceState.STARTING,
                ipcBound = false
            )
        )
        assertEquals(
            ConnectionState.Connecting,
            DashboardViewModel.resolveTrustedConnectionStateForTest(
                serviceState = ServiceState.STARTING,
                ipcBound = true
            )
        )
        assertEquals(
            ConnectionState.Connected,
            DashboardViewModel.resolveTrustedConnectionStateForTest(
                serviceState = ServiceState.RUNNING,
                ipcBound = true
            )
        )
    }
}

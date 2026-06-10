package com.kunk.singbox.ui.screens

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DashboardRoutingModeSourceTest {

    @Test
    fun dashboardWaitsForRoutingModePersistenceBeforeRestartingVpn() {
        val source = File(
            "src/main/java/com/kunk/singbox/ui/screens/DashboardScreen.kt"
        ).readText()

        assertTrue(source.contains("rememberCoroutineScope()"))
        val saveIndex = source.indexOf("settingsViewModel.setRoutingModeAndWait(")
        val restartIndex = source.indexOf("viewModel.restartVpn()", saveIndex)

        assertTrue(saveIndex >= 0)
        assertTrue(restartIndex > saveIndex)
    }

    @Test
    fun settingsViewModelExposesSuspendRoutingModeSetter() {
        val source = File(
            "src/main/java/com/kunk/singbox/viewmodel/SettingsViewModel.kt"
        ).readText()

        assertTrue(source.contains("suspend fun setRoutingModeAndWait("))
        assertTrue(source.contains("repository.setRoutingMode(value, notifyRestartRequired)"))
    }
}

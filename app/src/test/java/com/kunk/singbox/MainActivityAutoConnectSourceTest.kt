package com.kunk.singbox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MainActivityAutoConnectSourceTest {

    @Test
    fun autoConnectEffectRestartsWhenRuntimeStateChanges() {
        val source = File("src/main/java/com/kunk/singbox/MainActivity.kt").readText()

        assertTrue(
            source.contains(
                "LaunchedEffect(settings?.autoConnect, connectionState, isRunning, isStarting, manuallyStopped)"
            )
        )
    }

    @Test
    fun dashboardRouteUsesRootDashboardViewModelInstance() {
        val main = File("src/main/java/com/kunk/singbox/MainActivity.kt").readText()
        val navigation = File("src/main/java/com/kunk/singbox/ui/navigation/AppNavigation.kt").readText()

        assertTrue(main.contains("AppNavigation(navController, dashboardViewModel)"))
        assertTrue(navigation.contains("fun AppNavigation("))
        assertTrue(navigation.contains("dashboardViewModel: DashboardViewModel"))
        assertTrue(navigation.contains("DashboardScreen(navController, viewModel = dashboardViewModel)"))
    }
}

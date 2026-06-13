package com.kunk.singbox.service.manager

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NetworkAutoSwitchSourceTest {

    @Test
    fun applicationStartsNetworkAutoSwitchMonitorInMainProcess() {
        val source = File("src/main/java/com/kunk/singbox/SingBoxApplication.kt").readText()

        assertTrue(source.contains("NetworkAutoSwitchManager.start(this@SingBoxApplication)"))
    }

    @Test
    fun connectionSettingsExposeNetworkAutoSwitchControls() {
        val source = File("src/main/java/com/kunk/singbox/ui/screens/ConnectionSettingsScreen.kt").readText()

        assertTrue(source.contains("connection_settings_network_auto_switch"))
        assertTrue(source.contains("settings.networkAutoSwitchEnabled"))
        assertTrue(source.contains("settings.trustedWifiSsids"))
        assertTrue(source.contains("setNetworkAutoSwitchEnabled"))
        assertTrue(source.contains("setTrustedWifiSsids"))
    }

    @Test
    fun manifestDeclaresWifiSsidPermissions() {
        val source = File("src/main/AndroidManifest.xml").readText()

        assertTrue(source.contains("android.permission.ACCESS_WIFI_STATE"))
        assertTrue(source.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(source.contains("android.permission.NEARBY_WIFI_DEVICES"))
    }
}

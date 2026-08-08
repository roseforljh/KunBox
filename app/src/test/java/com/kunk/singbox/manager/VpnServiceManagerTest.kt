package com.kunk.singbox.manager

import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.SingBoxService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class VpnServiceManagerTest {

    @Test
    fun buildStartCommandForVpnIncludesProvidedConfigPath() {
        val command = VpnServiceManager.buildStartCommand(
            tunMode = true,
            configPath = "/data/user/0/com.kunk.singbox/files/running_config.json",
            cleanCache = true
        )

        assertEquals(SingBoxService::class.java, command.serviceClass)
        assertEquals(SingBoxService.ACTION_START, command.action)
        assertEquals(
            "/data/user/0/com.kunk.singbox/files/running_config.json",
            command.configPath
        )
        assertEquals(true, command.cleanCache)
    }

    @Test
    fun buildStartCommandForProxyKeepsConfigPathOptional() {
        val command = VpnServiceManager.buildStartCommand(tunMode = false)

        assertEquals(ProxyOnlyService::class.java, command.serviceClass)
        assertEquals(ProxyOnlyService.ACTION_START, command.action)
        assertNull(command.configPath)
        assertEquals(false, command.cleanCache)
    }

    @Test
    fun tunModeReadsSettingsRepositoryInsteadOfLegacyPreferences() {
        val source = File("src/main/java/com/kunk/singbox/manager/VpnServiceManager.kt").readText()

        assertTrue(source.contains("SettingsRepository"))
        assertFalse(source.contains("com.kunk.singbox_preferences"))
        assertFalse(source.contains("tun_enabled"))
    }

    @Test
    fun runtimeStateDoesNotReadLegacyVpnPreferences() {
        val source = File("src/main/java/com/kunk/singbox/manager/VpnServiceManager.kt").readText()

        assertTrue(source.contains("VpnStateStore.getActive()"))
        assertTrue(source.contains("VpnStateStore.getPending()"))
        assertFalse(source.contains("PREFS_VPN_STATE"))
        assertFalse(source.contains("getSharedPreferences"))
    }

    @Test
    fun knownRuntimeModeStopsOnlyMatchingService() {
        assertTrue(
            VpnServiceManager.shouldDispatchStopToService(
                activeMode = VpnStateStore.CoreMode.VPN,
                serviceMode = VpnStateStore.CoreMode.VPN
            )
        )
        assertFalse(
            VpnServiceManager.shouldDispatchStopToService(
                activeMode = VpnStateStore.CoreMode.VPN,
                serviceMode = VpnStateStore.CoreMode.PROXY
            )
        )
        assertTrue(
            VpnServiceManager.shouldDispatchStopToService(
                activeMode = VpnStateStore.CoreMode.PROXY,
                serviceMode = VpnStateStore.CoreMode.PROXY
            )
        )
        assertFalse(
            VpnServiceManager.shouldDispatchStopToService(
                activeMode = VpnStateStore.CoreMode.PROXY,
                serviceMode = VpnStateStore.CoreMode.VPN
            )
        )
    }

    @Test
    fun unknownRuntimeModeKeepsBothStopFallbacks() {
        assertTrue(
            VpnServiceManager.shouldDispatchStopToService(
                activeMode = VpnStateStore.CoreMode.NONE,
                serviceMode = VpnStateStore.CoreMode.VPN
            )
        )
        assertTrue(
            VpnServiceManager.shouldDispatchStopToService(
                activeMode = VpnStateStore.CoreMode.NONE,
                serviceMode = VpnStateStore.CoreMode.PROXY
            )
        )
    }

    @Test
    fun stopVpnUsesPersistedModeBeforeDispatching() {
        val source = File("src/main/java/com/kunk/singbox/manager/VpnServiceManager.kt").readText()
        val start = source.indexOf("fun stopVpn(context: Context)")
        val body = source.substring(
            start,
            source.indexOf("fun restartVpn(context: Context)", start)
        )

        assertTrue(body.contains("SingBoxService::class.java"))
        assertTrue(body.contains("ProxyOnlyService::class.java"))
        assertTrue(body.contains("val activeMode = VpnStateStore.getMode()"))
        assertTrue(body.contains("shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.VPN)"))
        assertTrue(body.contains("shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.PROXY)"))
    }
}

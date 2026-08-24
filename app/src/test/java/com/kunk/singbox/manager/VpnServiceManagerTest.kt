package com.kunk.singbox.manager

import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.root.RootTransparentForegroundService
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.service.manager.VpnStopInitiator
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
    fun buildStartCommandForRootUsesRootForegroundService() {
        val command = VpnServiceManager.buildStartCommand(
            mode = TrafficCaptureMode.ROOT_TRANSPARENT,
            configPath = "/data/user/0/com.kunk.singbox/files/root.json",
            cleanCache = true
        )

        assertEquals(RootTransparentForegroundService::class.java, command.serviceClass)
        assertEquals(RootTransparentForegroundService.ACTION_START, command.action)
        assertEquals("/data/user/0/com.kunk.singbox/files/root.json", command.configPath)
        assertFalse(command.cleanCache)
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
                activeMode = VpnStateStore.CoreMode.ROOT,
                serviceMode = VpnStateStore.CoreMode.ROOT
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
        val start = source.indexOf("fun stopVpn(context: Context, initiator: VpnStopInitiator)")
        val body = source.substring(
            start,
            source.indexOf("fun restartVpn(context: Context)", start)
        )

        assertTrue(body.contains("SingBoxService::class.java"))
        assertTrue(body.contains("ProxyOnlyService::class.java"))
        assertTrue(body.contains("RootTransparentForegroundService::class.java"))
        assertTrue(body.contains("val activeMode = VpnStateStore.getMode()"))
        assertTrue(body.contains("shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.VPN)"))
        assertTrue(body.contains("shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.PROXY)"))
        assertTrue(body.contains("shouldDispatchStopToService(activeMode, VpnStateStore.CoreMode.ROOT)"))
        assertTrue(body.contains("putExtra(SingBoxService.EXTRA_STOP_INITIATOR, initiator.wireValue)"))
    }

    @Test
    fun forceStopUsesEmergencyActionForBothServiceModes() {
        val source = File("src/main/java/com/kunk/singbox/manager/VpnServiceManager.kt")
            .readText(Charsets.UTF_8)
        val body = source.substringAfter("fun forceStop(context: Context)")
            .substringBefore("fun restartVpn(context: Context)")

        assertTrue(body.contains("SingBoxService.ACTION_FORCE_STOP"))
        assertTrue(body.contains("ProxyOnlyService.ACTION_FORCE_STOP"))
        assertTrue(body.contains("RootTransparentForegroundService.ACTION_STOP"))
    }

    @Test
    fun onlyExplicitUserActionsHaveManualStopSemantics() {
        assertTrue(VpnStopInitiator.USER_UI.isManualStop)
        assertTrue(VpnStopInitiator.QUICK_SETTINGS.isManualStop)
        assertTrue(VpnStopInitiator.NOTIFICATION.isManualStop)
        assertFalse(VpnStopInitiator.TRUSTED_WIFI.isManualStop)
        assertFalse(VpnStopInitiator.METERED_PROTECTION.isManualStop)
        assertFalse(VpnStopInitiator.MODE_SWITCH.isManualStop)
        assertFalse(VpnStopInitiator.START_TIMEOUT.isManualStop)
        assertFalse(VpnStopInitiator.RESTART.isManualStop)
        assertFalse(VpnStopInitiator.SYSTEM_REVOKE.isManualStop)
        assertFalse(VpnStopInitiator.UNKNOWN.isManualStop)
        assertEquals(VpnStopInitiator.UNKNOWN, VpnStopInitiator.fromWireValue("invalid"))
    }

    @Test
    fun systemRevokeIsRecordedAsAutomaticStop() {
        val source = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt")
            .readText(Charsets.UTF_8)
        val body = source.substringAfter("override fun onRevoke()")
            .substringBefore("protected suspend fun ensureNetworkCallbackReady")

        assertTrue(body.contains("lastStopInitiator = VpnStopInitiator.SYSTEM_REVOKE"))
        assertTrue(body.contains("VpnStateStore.setManuallyStopped(false)"))
        assertFalse(body.contains("VpnStateStore.setManuallyStopped(true)"))
    }
}

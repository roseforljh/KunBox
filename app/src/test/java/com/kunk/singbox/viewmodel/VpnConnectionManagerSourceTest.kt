package com.kunk.singbox.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VpnConnectionManagerSourceTest {

    @Test
    fun toggleConnectionChecksSystemVpnOnlyForTunMode() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/VpnConnectionManager.kt").readText()
        val body = source.substring(
            source.indexOf("suspend fun toggleConnection(): Boolean"),
            source.indexOf("suspend fun restartVpn()")
        )

        assertTrue(body.contains("settingsRepository.settings.first()"))
        assertTrue(body.contains("val tunEnabled = settings?.tunEnabled == true"))
        assertTrue(body.contains("tunEnabled && checkSystemVpn()"))
        assertFalse(body.contains("checkSystemVpn() ->"))
    }

    @Test
    fun stopVpnStopsBothServicesWhenCoreModeIsUnknown() {
        val source = File("src/main/java/com/kunk/singbox/viewmodel/VpnConnectionManager.kt").readText()
        val body = source.substring(
            source.indexOf("private fun stopVpnInternal()"),
            source.indexOf("private suspend fun startCore()")
        )

        assertTrue(body.contains("VpnStateStore.CoreMode.NONE -> {"))
        assertTrue(body.contains("context.startService(Intent(context, ProxyOnlyService::class.java).apply"))
        assertTrue(body.contains("context.startService(Intent(context, SingBoxService::class.java).apply"))
    }
}

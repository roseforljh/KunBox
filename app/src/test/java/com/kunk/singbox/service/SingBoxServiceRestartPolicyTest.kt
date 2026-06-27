package com.kunk.singbox.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SingBoxServiceRestartPolicyTest {

    @Test
    fun recoveryRestartQueuesStartBeforeStopping() {
        val body = restartVpnServiceBody()
        val queueIndex = body.indexOf("pendingStartConfigPath = configPath")
        val stopIndex = body.indexOf("stopVpn(stopService = false)")

        assertTrue(queueIndex >= 0)
        assertTrue(stopIndex > queueIndex)
        assertFalse(body.contains("startVpn(configPath)"))
    }

    private fun restartVpnServiceBody(): String {
        val source = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt")
            .readText(Charsets.UTF_8)
        val start = source.indexOf("protected suspend fun restartVpnService")
        val end = source.indexOf("// 屏幕/前台状态", start)

        assertTrue(start >= 0)
        assertTrue(end > start)
        return source.substring(start, end)
    }
}

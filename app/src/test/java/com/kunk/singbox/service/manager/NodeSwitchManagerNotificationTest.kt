package com.kunk.singbox.service.manager

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeSwitchManagerNotificationTest {

    @Test
    fun notificationUpdateAfterExplicitHotSwitch_isForced() {
        assertTrue(FORCE_NOTIFICATION_AFTER_EXPLICIT_HOT_SWITCH)
    }

    @Test
    fun explicitHotSwitchFailureDoesNotSilentlyRestartVpn() {
        val source = File("src/main/java/com/kunk/singbox/service/manager/NodeSwitchManager.kt")
            .readText(Charsets.UTF_8)
        val body = source
            .substringAfter("val success = callbacks?.hotSwitchNode(nodeTag) == true")
            .substringBefore("@Suppress(\"LongMethod\", \"CognitiveComplexMethod\")")

        assertFalse(body.contains("startServiceIntent"))
        assertFalse(body.contains("restart_fallback"))
    }

    @Test
    fun proxyOnlyHotSwitchFailureAlsoKeepsCurrentRuntime() {
        val source = File("src/main/java/com/kunk/singbox/service/proxy/ProxyCoreRuntime.kt")
            .readText(Charsets.UTF_8)
        val failureBody = source
            .substringAfter("if (failure == null) return")
            .substringBefore("internal fun ProxyOnlyService.initializeRuntimeSelector(configContent: String)")

        assertFalse(failureBody.contains("queueCoreRestart"))
    }
}

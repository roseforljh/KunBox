package com.kunk.singbox.service.notification

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnNotificationManagerPolicyTest {

    @Test
    fun suppressingUpdatesCancelsPendingNotificationJob() {
        val source = File(
            "src/main/java/com/kunk/singbox/service/notification/VpnNotificationManager.kt"
        ).readText(Charsets.UTF_8)
        val body = source.functionBody("fun setSuppressUpdates(")

        assertTrue(body.contains("if (suppress)"))
        assertTrue(body.contains("updateJob?.cancel()"))
        assertTrue(body.contains("updateJob = null"))
    }

    @Test
    fun delayedUpdateRechecksSuppressionBeforePublishing() {
        val source = File(
            "src/main/java/com/kunk/singbox/service/notification/VpnNotificationManager.kt"
        ).readText(Charsets.UTF_8)
        val body = source.functionBody("fun requestNotificationUpdate(")
        val delayedUpdate = body.substringAfter("updateJob = serviceScope.launch")

        assertTrue(delayedUpdate.contains("delay(delayMs)"))
        assertTrue(delayedUpdate.contains("!suppressUpdates && !latest.state.isStopping"))
        assertTrue(
            delayedUpdate.indexOf("!suppressUpdates && !latest.state.isStopping") <
                delayedUpdate.indexOf("updateNotification(latest.state, latest.service)")
        )
    }

    @Test
    fun delayedUpdatePublishesLatestPendingState() {
        val source = File(
            "src/main/java/com/kunk/singbox/service/notification/VpnNotificationManager.kt"
        ).readText(Charsets.UTF_8)
        val body = source.functionBody("fun requestNotificationUpdate(")
        val storeIndex = body.indexOf("pendingUpdate = PendingUpdate(state, service)")
        val activeJobIndex = body.indexOf("if (updateJob?.isActive == true) return")
        val delayedUpdate = body.substringAfter("updateJob = serviceScope.launch")

        assertTrue(storeIndex >= 0)
        assertTrue(storeIndex < activeJobIndex)
        assertTrue(delayedUpdate.contains("pendingUpdate.also { pendingUpdate = null }"))
        assertTrue(delayedUpdate.contains("updateNotification(latest.state, latest.service)"))
    }

    private fun String.functionBody(startToken: String): String {
        val start = indexOf(startToken)
        require(start >= 0) { "未找到 $startToken" }
        val openingBrace = indexOf('{', start)
        require(openingBrace >= 0) { "$startToken 缺少函数体" }
        var depth = 0
        for (index in openingBrace until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return substring(start, index + 1)
            }
        }
        error("$startToken 函数体未闭合")
    }
}

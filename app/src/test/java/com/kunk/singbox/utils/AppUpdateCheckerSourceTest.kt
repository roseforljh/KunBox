package com.kunk.singbox.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppUpdateCheckerSourceTest {
    @Test
    fun updateCheckResponsesAreClosedForProxyAndDirectRequests() {
        val source = File("src/main/java/com/kunk/singbox/utils/AppUpdateChecker.kt").readText()

        assertTrue(source.contains("proxyClient.newCall(request).execute().use { response ->"))
        assertTrue(source.contains("getDirectClient().newCall(request).execute().use { response ->"))
        assertTrue(!source.contains("if (result == null) response.close()"))
    }

    @Test
    fun proxyFailureFallsBackToDirectUpdateCheck() {
        val source = File("src/main/java/com/kunk/singbox/utils/AppUpdateChecker.kt").readText()

        assertTrue(source.contains("return proxyResult ?: tryDirectRequest(request)"))
        assertTrue(!source.contains("if (proxyResult != null || proxyClientAvailable) return proxyResult"))
    }

    @Test
    fun notificationVersionIsStoredOnlyWhenNotificationWasShown() {
        val source = File("src/main/java/com/kunk/singbox/utils/AppUpdateChecker.kt").readText()

        assertTrue(source.contains("if (showUpdateNotification(context, release))"))
        assertTrue(
            !source.contains(
                "showUpdateNotification(context, release)\n                    setLastNotifiedVersion"
            )
        )
    }
}

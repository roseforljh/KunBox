package com.kunk.singbox.service.root

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RootUidRefreshWakeLockTest {
    @Test
    fun uidRefreshHoldsAndReleasesTimedWakeLock() {
        val service = File("src/main/java/com/kunk/singbox/service/root/RootTransparentForegroundService.kt")
            .readText(Charsets.UTF_8)
        val runtime = File(
            "src/main/java/com/kunk/singbox/service/root/runtime/RootTransparentForegroundRuntime.kt"
        ).readText(Charsets.UTF_8)
        val schedule = runtime.substringAfter("internal fun RootTransparentForegroundService.scheduleUidRefresh(")
            .substringBefore("@Suppress(\"LongMethod\")")

        assertTrue(service.contains("PowerManager.PARTIAL_WAKE_LOCK"))
        assertTrue(service.contains("acquire(UID_REFRESH_WAKE_LOCK_TIMEOUT_MS)"))
        assertTrue(service.contains("releaseUidRefreshWakeLock"))
        assertTrue(schedule.contains("val wakeLock = acquireUidRefreshWakeLock()"))
        assertTrue(schedule.contains("releaseUidRefreshWakeLock(wakeLock)"))
    }
}

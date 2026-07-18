package com.kunk.singbox.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VpnKeepaliveWorkerTest {

    @Test
    fun applicationCancelsLegacyKeepaliveAndNeverSchedulesRecovery() {
        val applicationSource = File("src/main/java/com/kunk/singbox/SingBoxApplication.kt")
            .readText(Charsets.UTF_8)
        val serviceSource = File("src/main/java/com/kunk/singbox/service/SingBoxService.kt")
            .readText(Charsets.UTF_8)
        val workerSource = File("src/main/java/com/kunk/singbox/service/VpnKeepaliveWorker.kt")
            .readText(Charsets.UTF_8)

        assertTrue(applicationSource.contains("VpnKeepaliveWorker.cancel(this@SingBoxApplication)"))
        assertFalse(applicationSource.contains("VpnKeepaliveWorker.schedule("))
        assertFalse(applicationSource.contains("app_cold_start"))
        assertFalse(serviceSource.contains("VpnKeepaliveWorker.schedule("))
        assertFalse(workerSource.contains("attemptOnce"))
        assertTrue(workerSource.contains("override suspend fun doWork(): Result = Result.success()"))
    }
}

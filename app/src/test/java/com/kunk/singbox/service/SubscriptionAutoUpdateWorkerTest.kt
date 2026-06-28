package com.kunk.singbox.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SubscriptionAutoUpdateWorkerTest {

    @Test
    fun normalizeIntervalMinutesKeepsDisabledValue() {
        assertEquals(0, SubscriptionAutoUpdateWorker.normalizeIntervalMinutes(0))
        assertEquals(0, SubscriptionAutoUpdateWorker.normalizeIntervalMinutes(-1))
    }

    @Test
    fun normalizeIntervalMinutesRaisesPositiveValuesBelowWorkManagerMinimum() {
        assertEquals(15, SubscriptionAutoUpdateWorker.normalizeIntervalMinutes(1))
        assertEquals(15, SubscriptionAutoUpdateWorker.normalizeIntervalMinutes(14))
    }

    @Test
    fun normalizeIntervalMinutesKeepsLegalValues() {
        assertEquals(15, SubscriptionAutoUpdateWorker.normalizeIntervalMinutes(15))
        assertEquals(60, SubscriptionAutoUpdateWorker.normalizeIntervalMinutes(60))
    }

    @Test
    fun disabledSubscriptionCancelsExistingPeriodicWork() {
        val source = File("src/main/java/com/kunk/singbox/service/SubscriptionAutoUpdateWorker.kt").readText()

        assertTrue(source.contains("if (!profile.enabled)"))
        assertTrue(source.contains("cancel(applicationContext, profileId)"))
    }

    @Test
    fun subscriptionScheduleRequiresConnectedNetwork() {
        val source = File("src/main/java/com/kunk/singbox/service/SubscriptionAutoUpdateWorker.kt")
            .readText(Charsets.UTF_8)

        assertTrue(source.contains("val constraints = Constraints.Builder()"))
        assertTrue(source.contains(".setRequiredNetworkType(NetworkType.CONNECTED)"))
        assertTrue(source.contains(".setConstraints(constraints)"))
    }
}

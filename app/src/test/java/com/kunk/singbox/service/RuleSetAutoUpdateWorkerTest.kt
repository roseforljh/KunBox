package com.kunk.singbox.service

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleSetAutoUpdateWorkerTest {

    @Test
    fun normalizeIntervalMinutesKeepsDisabledValue() {
        assertEquals(0, RuleSetAutoUpdateWorker.normalizeIntervalMinutes(0))
        assertEquals(0, RuleSetAutoUpdateWorker.normalizeIntervalMinutes(-1))
    }

    @Test
    fun normalizeIntervalMinutesRaisesPositiveValuesBelowWorkManagerMinimum() {
        assertEquals(15, RuleSetAutoUpdateWorker.normalizeIntervalMinutes(1))
        assertEquals(15, RuleSetAutoUpdateWorker.normalizeIntervalMinutes(14))
    }

    @Test
    fun normalizeIntervalMinutesKeepsLegalValues() {
        assertEquals(15, RuleSetAutoUpdateWorker.normalizeIntervalMinutes(15))
        assertEquals(60, RuleSetAutoUpdateWorker.normalizeIntervalMinutes(60))
    }
}

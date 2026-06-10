package com.kunk.singbox.service.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStateManagerTest {

    @Test
    fun activityStartedCountNeverGoesBelowZero() {
        assertEquals(0, ScreenStateManager.nextStartedActivityCount(0, started = false))
        assertEquals(0, ScreenStateManager.nextStartedActivityCount(1, started = false))
        assertEquals(1, ScreenStateManager.nextStartedActivityCount(0, started = true))
    }

    @Test
    fun foregroundStateDependsOnAnyStartedActivity() {
        assertFalse(ScreenStateManager.isForegroundFromStartedActivityCount(0))
        assertTrue(ScreenStateManager.isForegroundFromStartedActivityCount(1))
        assertTrue(ScreenStateManager.isForegroundFromStartedActivityCount(2))
    }
}

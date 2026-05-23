package com.kunk.singbox.service.manager

import org.junit.Assert.assertTrue
import org.junit.Test

class NodeSwitchManagerNotificationTest {

    @Test
    fun notificationUpdateAfterExplicitHotSwitch_isForced() {
        assertTrue(FORCE_NOTIFICATION_AFTER_EXPLICIT_HOT_SWITCH)
    }
}

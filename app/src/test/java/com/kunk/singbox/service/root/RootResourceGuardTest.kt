package com.kunk.singbox.service.root

import org.junit.Assert.assertEquals
import org.junit.Test

class RootResourceGuardTest {
    @Test
    fun fdThresholdsFailOpenBeforePlatformLimit() {
        assertEquals(RootResourceAction.NONE, rootResourceAction(8_191))
        assertEquals(RootResourceAction.WARN, rootResourceAction(8_192))
        assertEquals(RootResourceAction.WARN, rootResourceAction(29_999))
        assertEquals(RootResourceAction.STOP, rootResourceAction(30_000))
    }
}

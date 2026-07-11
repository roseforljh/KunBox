package com.kunk.singbox.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SingBoxServiceRestartErrorTest {

    @Test
    fun restartFailureWithoutMessageUsesExceptionTypeInsteadOfNull() {
        val message = SingBoxService.formatRestartFailure(IllegalStateException())

        assertEquals("Failed to restart VPN: IllegalStateException", message)
        assertFalse(message.contains("null"))
    }
}

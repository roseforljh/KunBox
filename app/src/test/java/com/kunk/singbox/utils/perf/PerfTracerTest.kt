package com.kunk.singbox.utils.perf

import com.kunk.singbox.repository.LogRepository
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerfTracerTest {

    private val logRepository = LogRepository.getInstance()

    @After
    fun tearDown() {
        logRepository.setEnabled(false)
        logRepository.clearLogs()
    }

    @Test
    fun metricEventIsPersistedWhenDebugLoggingIsDisabled() {
        val canary = "canary_metric_default_logging_off"
        logRepository.setEnabled(false)
        logRepository.clearLogs()
        assertFalse(logRepository.isEnabled())

        PerfTracer.recordEvent(canary, "success")

        assertTrue(logRepository.getLogsAsText().contains("name=$canary"))
    }
}

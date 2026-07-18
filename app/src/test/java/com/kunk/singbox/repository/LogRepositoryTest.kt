package com.kunk.singbox.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRepositoryTest {

    private val repository = LogRepository.getInstance()

    @After
    fun tearDown() {
        repository.setEnabled(false)
        repository.clearLogs()
    }

    @Test
    fun addLogIgnoresMessagesWhenDisabled() {
        repository.setEnabled(false)
        repository.clearLogs()

        repository.addLog("INFO test log")

        assertFalse(repository.getLogsAsText().contains("INFO test log"))
    }

    @Test
    fun addLogRecordsMessagesWhenEnabled() {
        repository.setEnabled(true)
        repository.clearLogs()

        repository.addLog("INFO test log")

        val logs = repository.getLogsAsText()
        assertEquals(1, logs.lineSequence().count { it.contains("INFO test log") })
        assertTrue(logs.contains("INFO test log"))
    }

    @Test
    fun userClearRemovesRecoveryDiagnostics() {
        repository.setEnabled(false)
        repository.clearLogs()

        repository.addAlwaysLog("INFO [Recovery] cold start probe")
        assertTrue(repository.getLogsAsText().contains("INFO [Recovery] cold start probe"))

        repository.clearLogs()
        assertFalse(repository.getLogsAsText().contains("INFO [Recovery] cold start probe"))
    }

    @Test
    fun internalClearPreservesRecoveryDiagnostics() {
        repository.setEnabled(false)
        repository.clearLogs()

        repository.addAlwaysLog("INFO [Recovery] cold start probe")
        repository.clearLogs(preserveRecoveryDiagnostics = true)

        assertTrue(repository.getLogsAsText().contains("INFO [Recovery] cold start probe"))
    }

    @Test
    fun preservedDiagnosticMarkerMatchesRecoveryLinesOnly() {
        assertTrue(LogRepository.isPreservedDiagnosticLine("INFO [Recovery] sticky"))
        assertFalse(LogRepository.isPreservedDiagnosticLine("INFO [IPC] state update"))
    }
}

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
        assertTrue(LogRepository.isPreservedDiagnosticLine("INFO [Lifecycle] service=vpn event=create"))
        assertTrue(LogRepository.isPreservedDiagnosticLine("METRIC resource_fd process=bg count=32700"))
        assertTrue(LogRepository.isPreservedDiagnosticLine("WARN recovery resource_exhausted stage=restart_core"))
        assertTrue(LogRepository.isPreservedDiagnosticLine("INFO recovery same_node stage=RESET_NETWORK"))
        assertTrue(LogRepository.isPreservedDiagnosticLine("ERROR [METERED_GUARD] closed=true"))
        assertTrue(LogRepository.isPreservedDiagnosticLine("ERROR [CONNECTION_STORM] closed=true"))
        assertTrue(LogRepository.isPreservedDiagnosticLine("INFO [HOT_SWITCH] outcome=success"))
        assertTrue(LogRepository.isPreservedDiagnosticLine("WARN [COMMAND_LOG] disconnected"))
        assertTrue(LogRepository.isPreservedDiagnosticLine("WARN diagnosis=remote_dns_timeout"))
        assertFalse(LogRepository.isPreservedDiagnosticLine("INFO [IPC] state update"))
    }

    @Test
    fun persistedLogTimestampContainsDateAndMilliseconds() {
        assertEquals("yyyy-MM-dd HH:mm:ss.SSS", LOG_TIMESTAMP_PATTERN)
    }

    @Test
    fun staleCrossProcessBatchRebasesOnlyRecoveryDiagnostics() {
        val stale = LogPersistenceBatch(
            lines = listOf("INFO ordinary", "WARN [COMMAND_LOG] disconnected"),
            rewriteAll = true,
            generation = 4L,
            queueGeneration = 9L
        )

        val rebased = rebaseStaleDiagnosticBatch(stale, currentGeneration = 5L)

        assertEquals(listOf("WARN [COMMAND_LOG] disconnected"), rebased?.lines)
        assertEquals(false, rebased?.rewriteAll)
        assertEquals(5L, rebased?.generation)
        assertEquals(9L, rebased?.queueGeneration)
        assertEquals(
            null,
            rebaseStaleDiagnosticBatch(stale.copy(lines = listOf("INFO ordinary")), currentGeneration = 5L)
        )
    }

    @Test
    fun lifecycleDiagnosticContainsProcessSessionAndReason() {
        val line = buildServiceLifecycleDiagnostic(
            service = "vpn",
            event = "destroy",
            reason = "unexpected_destroy",
            pid = 12186,
            details = "process_started_at_epoch_ms=1700000000000 app_version_code=6913 " +
                "mode=VPN manually_stopped=false recovery=true action=START"
        )

        assertTrue(line.contains("service=vpn event=destroy reason=unexpected_destroy pid=12186"))
        assertTrue(line.contains("process_started_at_epoch_ms=1700000000000 app_version_code=6913"))
        assertTrue(line.contains("mode=VPN manually_stopped=false recovery=true action=START"))
    }

    @Test
    fun bufferOverflowKeepsResourceDiagnostics() {
        repository.setEnabled(false)
        repository.clearLogs()
        repository.addAlwaysLog("METRIC resource_fd process=bg count=32700")

        repeat(2_100) { index ->
            repository.addAlwaysLog("INFO connection completed index=$index")
        }

        assertTrue(repository.getLogsAsText().contains("resource_fd process=bg count=32700"))
    }
}

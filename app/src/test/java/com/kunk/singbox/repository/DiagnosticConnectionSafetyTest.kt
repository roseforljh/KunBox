package com.kunk.singbox.repository

import com.google.gson.JsonParser
import com.kunk.singbox.utils.perf.DiagnosticResourceSample
import com.kunk.singbox.utils.perf.FdPressureLevel
import com.kunk.singbox.utils.perf.ResourceFdTracker
import com.kunk.singbox.utils.perf.evaluateFdPressure
import com.kunk.singbox.utils.perf.readProcSocketTableRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticConnectionSafetyTest {
    @Test
    fun diagnosticArchiveEntriesIncludeRedactedConnectionIncidents() {
        val canaryPackage = "com.private.connectionstorm"
        val canarySource = "203.0.113.42:54321"
        val canaryInbound = "private-inbound"
        val entries = buildDiagnosticArchiveEntries(
            manifest = "{\"format_version\":3}",
            logs = "",
            runningConfig = null,
            resourcesCsv = "",
            connectionIncidentsJsonl = "{\"package_names\":[\"$canaryPackage\"]," +
                "\"source\":\"$canarySource\",\"inbound\":\"$canaryInbound\"}\n" +
                "{\"chain\":\"front-node>private-node\",\"protocol\":\"tcp/tls\"}\n",
            redactor = DiagnosticRedactor("test-salt".toByteArray())
        )

        val incidents = requireNotNull(entries["connection_incidents.jsonl"])
        assertFalse(incidents.contains(canaryPackage))
        assertFalse(incidents.contains(canarySource))
        assertFalse(incidents.contains(canaryInbound))
        assertFalse(incidents.contains("front-node>private-node"))
        val lines = incidents.lineSequence().filter(String::isNotBlank).toList()
        assertEquals(2, lines.size)
        assertTrue(lines.all { JsonParser.parseString(it).isJsonObject })
    }

    @Test
    fun fdTrackerImmediatelyRecoversFromAOneSecondSocketStorm() {
        val tracker = ResourceFdTracker()
        tracker.observe(resourceSample(elapsedRealtimeMs = 1_000L, fdCount = 150))

        val decision = tracker.observe(resourceSample(elapsedRealtimeMs = 2_000L, fdCount = 1_200))

        assertEquals(FdPressureLevel.RECOVERY, decision.level)
        assertTrue(decision.shouldRecover)
    }

    @Test
    fun fdGuardPollsFrequentlyEnoughToSeeASecondsScaleStorm() {
        val decision = evaluateFdPressure(
            fdCount = 150,
            fdSoftLimit = 32_768L,
            growthOverFiveMinutes = 0,
            consecutiveHighSamples = 0
        )

        assertTrue(decision.sampleIntervalMs <= 1_000L)
    }

    @Test
    fun fdGuardKeepsOneSecondPollingAtObserveAndWarningLevels() {
        val observe = evaluateFdPressure(
            fdCount = 16_500,
            fdSoftLimit = 32_768L,
            growthOverFiveMinutes = 0,
            consecutiveHighSamples = 0
        )
        val warning = evaluateFdPressure(
            fdCount = 23_500,
            fdSoftLimit = 32_768L,
            growthOverFiveMinutes = 0,
            consecutiveHighSamples = 0
        )

        assertTrue(observe.sampleIntervalMs <= 1_000L)
        assertTrue(warning.sampleIntervalMs <= 1_000L)
    }

    @Test
    fun fdRecoveryStartsBeforeAnyFullSocketBreakdownScan() {
        val source = java.io.File(
            "src/main/java/com/kunk/singbox/utils/perf/DiagnosticResourceSampler.kt"
        ).readText()
        val monitorBody = source.substringAfter("private suspend fun monitor(")
            .substringBefore("private fun requestRecovery(")
        val immediateBranch = monitorBody.substringAfter("if (decision.shouldRecover) {")
            .substringBefore("val breakdownDue")
        val signalBody = source.substringAfter("fun signalResourceExhaustion(")
            .substringBefore("suspend fun failSuccessorAndAwait(")

        assertTrue(immediateBranch.contains("startImmediateFdRecovery("))
        assertFalse(immediateBranch.contains("captureCurrentProcess"))
        assertTrue(signalBody.contains("captureCurrentFdPressure()"))
        assertFalse(signalBody.contains("includeFdBreakdown = true"))
    }

    @Test
    fun procSocketTableReaderFallsBackFromSelfToGlobalBeforePidPath() {
        val attemptedPaths = mutableListOf<String>()
        val result = readProcSocketTableRows(
            pid = 42,
            fileName = "tcp",
            inodeColumn = 9,
            stateColumn = 3
        ) { path ->
            attemptedPaths += path
            if (path == "/proc/net/tcp") {
                listOf(
                    "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode",
                    "0: 0100007F:1F90 00000000:0000 0A 0:0 00:00000000 00000000 1000 0 12345"
                )
            } else {
                throw java.io.FileNotFoundException(path)
            }
        }

        assertEquals(listOf("/proc/self/net/tcp", "/proc/net/tcp"), attemptedPaths)
        assertEquals("0A", result.rows?.get("12345"))
        assertTrue(result.failures.isEmpty())
    }

    private fun resourceSample(elapsedRealtimeMs: Long, fdCount: Int) = DiagnosticResourceSample(
        timestampEpochMs = elapsedRealtimeMs,
        elapsedRealtimeMs = elapsedRealtimeMs,
        processName = "com.kunk.singbox:bg",
        pid = 42,
        pssKb = null,
        cpuTimeMs = null,
        cpuPercent = null,
        fdCount = fdCount,
        fdSoftLimit = 32_768L
    )
}

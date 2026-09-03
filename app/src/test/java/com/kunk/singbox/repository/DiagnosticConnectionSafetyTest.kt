package com.kunk.singbox.repository

import com.google.gson.JsonParser
import com.kunk.singbox.service.manager.ConnectionStormGuard
import com.kunk.singbox.service.manager.ConnectionStormReason
import com.kunk.singbox.service.manager.ConnectionTrafficAttributor
import com.kunk.singbox.service.manager.ConnectionTrafficEventData
import com.kunk.singbox.utils.perf.DiagnosticResourceSample
import com.kunk.singbox.utils.perf.FdPressureLevel
import com.kunk.singbox.utils.perf.ResourceRecoveryBudgetHealthTracker
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
    fun fdTrackerClassifiesLowStartupBurstBeforeRecoveringSustainedHighGrowth() {
        val tracker = ResourceFdTracker()
        tracker.observe(resourceSample(elapsedRealtimeMs = 1_000L, fdCount = 150))

        val startupBurst = listOf(
            tracker.observe(resourceSample(elapsedRealtimeMs = 2_000L, fdCount = 2_709)),
            tracker.observe(resourceSample(elapsedRealtimeMs = 3_000L, fdCount = 6_338)),
            tracker.observe(resourceSample(elapsedRealtimeMs = 4_000L, fdCount = 8_780))
        )
        val sustainedHighGrowth = tracker.observe(resourceSample(elapsedRealtimeMs = 5_000L, fdCount = 17_000))

        assertTrue(startupBurst.all { !it.shouldRecover })
        assertEquals(FdPressureLevel.WARNING, startupBurst.last().level)
        assertEquals(FdPressureLevel.RECOVERY, sustainedHighGrowth.level)
        assertTrue(sustainedHighGrowth.shouldRecover)
    }

    @Test
    fun fdTrackerCoolsDownAfterRecoveryButNeverSuppressesEmergency() {
        val tracker = ResourceFdTracker()
        tracker.observe(resourceSample(elapsedRealtimeMs = 3_000L, fdCount = 17_000))
        tracker.markRecoveryStarted(elapsedRealtimeMs = 4_000L)

        tracker.observe(resourceSample(elapsedRealtimeMs = 5_000L, fdCount = 20_000))
        tracker.observe(resourceSample(elapsedRealtimeMs = 6_000L, fdCount = 28_000))
        val suppressed = tracker.observe(resourceSample(elapsedRealtimeMs = 7_000L, fdCount = 29_000))
        val emergency = tracker.observe(resourceSample(elapsedRealtimeMs = 8_000L, fdCount = 32_000))

        assertFalse(suppressed.shouldRecover)
        assertEquals(FdPressureLevel.WARNING, suppressed.level)
        assertTrue(emergency.shouldRecover)
        assertEquals(FdPressureLevel.EMERGENCY, emergency.level)
    }

    @Test
    fun healthyFdWindowReturnsRecoveryBudgetOnlyOncePerHealthyPeriod() {
        val tracker = ResourceRecoveryBudgetHealthTracker(healthyWindowMs = 5_000L)

        assertFalse(tracker.observe(resourceSample(1_000L, 150), FdPressureLevel.NORMAL))
        assertFalse(tracker.observe(resourceSample(5_999L, 150), FdPressureLevel.NORMAL))
        assertTrue(tracker.observe(resourceSample(6_000L, 150), FdPressureLevel.NORMAL))
        assertFalse(tracker.observe(resourceSample(7_000L, 150), FdPressureLevel.NORMAL))
        assertFalse(tracker.observe(resourceSample(8_000L, 24_000), FdPressureLevel.WARNING))
        assertFalse(tracker.observe(resourceSample(9_000L, 150), FdPressureLevel.NORMAL))
        assertTrue(tracker.observe(resourceSample(14_000L, 150), FdPressureLevel.NORMAL))
    }

    @Test
    fun repeatedResetSnapshotsStillCountNewConnections() {
        val guard = ConnectionStormGuard(
            sourceCreationLimit = 2,
            globalCreationLimit = 4,
            sourceActiveLimit = 8,
            globalActiveLimit = 16,
            windowMs = 5_000L
        )
        fun event(id: String) = ConnectionTrafficEventData(
            type = ConnectionTrafficAttributor.EVENT_NEW,
            id = id,
            uid = 10_123,
            packageNames = listOf("com.example.storm")
        )

        assertEquals(null, guard.observe(reset = true, events = listOf(event("1")), nowMs = 1_000L))
        val decision = guard.observe(
            reset = true,
            events = listOf(event("1"), event("2"), event("3")),
            nowMs = 2_000L
        )

        assertEquals(ConnectionStormReason.SOURCE_CREATION_RATE, decision?.reason)
        assertEquals(2, decision?.newConnectionsInWindow)
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
    fun unknownFdLimitStillRecoversAtTheConservativeAbsoluteFallback() {
        val decision = evaluateFdPressure(
            fdCount = 16_384,
            fdSoftLimit = null,
            growthOverFiveMinutes = 0,
            consecutiveHighSamples = 0
        )

        assertTrue(decision.shouldRecover)
        assertEquals(FdPressureLevel.RECOVERY, decision.level)
    }

    @Test
    fun fdRecoveryStartsBeforeAnyFullSocketBreakdownScan() {
        val source = java.io.File(
            "src/main/java/com/kunk/singbox/utils/perf/DiagnosticResourceGuard.kt"
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
    fun resourceExhaustionFallbackRegistersTheGuardWithoutClosingGlobalConnections() {
        val vpnSource = java.io.File(
            "src/main/java/com/kunk/singbox/service/vpn/SingBoxStartupRuntime.kt"
        ).readText()
        val vpnBody = vpnSource.substringAfter("internal fun SingBoxService.handleResourceExhaustionSignal(")
            .substringBefore("internal fun SingBoxService.submitSameNodeRecovery(")
        val proxySource = java.io.File(
            "src/main/java/com/kunk/singbox/service/proxy/ProxyHealthRuntime.kt"
        ).readText()
        val proxyBody = proxySource.substringAfter(
            "internal fun ProxyOnlyService.handleKernelLogForSameNodeRecovery("
        ).substringBefore("internal fun ProxyOnlyService.submitSameNodeRecovery(")

        listOf(vpnBody, proxyBody).forEach { body ->
            assertTrue(body.contains("startResourceGuard()"))
            assertFalse(body.contains("closeConnections()"))
            assertFalse(body.contains("closeRuntimeConnections()"))
            assertFalse(body.contains("BoxWrapperManager.resetNetwork()"))
        }
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

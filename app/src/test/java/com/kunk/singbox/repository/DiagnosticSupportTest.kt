package com.kunk.singbox.repository

import com.google.gson.JsonParser
import com.kunk.singbox.utils.perf.DIAGNOSTIC_RESOURCE_CSV_HEADER
import com.kunk.singbox.utils.perf.DiagnosticResourceSample
import com.kunk.singbox.utils.perf.DiagnosticResourceHistory
import com.kunk.singbox.service.manager.ConnectionAttributionSnapshot
import com.kunk.singbox.utils.perf.FdBreakdown
import com.kunk.singbox.utils.perf.FdPressureLevel
import com.kunk.singbox.utils.perf.FdTargetType
import com.kunk.singbox.utils.perf.ProcessCpuBaseline
import com.kunk.singbox.utils.perf.ProcessResourcePoint
import com.kunk.singbox.utils.perf.ProcessStartEpochClock
import com.kunk.singbox.utils.perf.calculateProcessCpuPercent
import com.kunk.singbox.utils.perf.calculateProcessStartedAtEpochMs
import com.kunk.singbox.utils.perf.buildSocketAttributionDiagnosticLines
import com.kunk.singbox.utils.perf.classifyFdTarget
import com.kunk.singbox.utils.perf.evaluateFdPressure
import com.kunk.singbox.utils.perf.formatDiagnosticResourceSamplesCsv
import com.kunk.singbox.utils.perf.isFdRecoverySufficient
import com.kunk.singbox.utils.perf.parseDiagnosticResourceSamplesCsv
import com.kunk.singbox.utils.perf.parseProcCpuTimeMs
import com.kunk.singbox.utils.perf.parseProcProcessStartElapsedRealtimeMs
import com.kunk.singbox.utils.perf.parseProcSocketRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

class DiagnosticSupportTest {

    @Test
    fun redactorRemovesSecretsAndKeepsIdentifierReferencesConsistent() {
        val source = """
            {
              "outbounds": [{
                "tag": "private-node",
                "server": "secret.example.com",
                "password": "CANARY_PASSWORD",
                "uuid": "123e4567-e89b-12d3-a456-426614174000"
              }],
              "route": { "final": "private-node" }
            }
        """.trimIndent()

        val redacted = DiagnosticRedactor("test-salt".toByteArray()).redactJson(source)
        val json = JsonParser.parseString(redacted).asJsonObject
        val outbound = json.getAsJsonArray("outbounds")[0].asJsonObject

        assertFalse(redacted.contains("CANARY_PASSWORD"))
        assertFalse(redacted.contains("secret.example.com"))
        assertFalse(redacted.contains("123e4567-e89b-12d3-a456-426614174000"))
        assertFalse(redacted.contains("private-node"))
        assertEquals(outbound.get("tag").asString, json.getAsJsonObject("route").get("final").asString)
    }

    @Test
    fun redactorRemovesSensitiveValuesFromLogs() {
        val source = """
            password=CANARY_PASSWORD
            Authorization: Bearer CANARY_BEARER
            node=trojan://user:CANARY_URI@secret.example.com:443?token=CANARY_TOKEN
            remote=203.0.113.42 package=com.private.bank
            peer=[2001:db8::1234]
            uuid=123e4567-e89b-12d3-a456-426614174000
            path=/data/user/0/com.kunk.singbox/files/running_config.json
        """.trimIndent()

        val redacted = DiagnosticRedactor("test-salt".toByteArray()).redactText(source)

        listOf(
            "CANARY_PASSWORD",
            "CANARY_BEARER",
            "CANARY_URI",
            "CANARY_TOKEN",
            "secret.example.com",
            "203.0.113.42",
            "2001:db8::1234",
            "com.private.bank",
            "123e4567-e89b-12d3-a456-426614174000",
            "/data/user/0/com.kunk.singbox/files/running_config.json"
        ).forEach { secret -> assertFalse("未脱敏: $secret", redacted.contains(secret)) }
    }

    @Test
    fun redactorPreservesLogTimestampsWhileRedactingBracketedIpv6() {
        val source = "[14:40:27] connected to [2001:db8::1]:443"

        val redacted = DiagnosticRedactor("test-salt".toByteArray()).redactText(source)

        assertTrue(redacted.startsWith("[14:40:27] connected to "))
        assertFalse(redacted.contains("2001:db8::1"))
    }

    @Test
    fun redactorKeepsLineBreakAndTimestampAfterEmptyValues() {
        val source = """
            source=
            [2026-08-09 23:45:05.266] INFO first event
            password=
            [2026-08-09 23:45:06.001] WARN second event
            Authorization: Bearer
            [2026-08-09 23:45:07.418] ERROR third event
        """.trimIndent()

        val redacted = DiagnosticRedactor("test-salt".toByteArray()).redactText(source)

        assertTrue(redacted.contains("source=\n[2026-08-09 23:45:05.266]"))
        assertTrue(redacted.contains("password=\n[2026-08-09 23:45:06.001]"))
        assertTrue(redacted.contains("\n[2026-08-09 23:45:07.418] ERROR third event"))
        assertEquals(source.lines().size, redacted.lines().size)
    }

    @Test
    fun redactorRemovesQuotedCredentialKeysFromTextAndInvalidJsonFallback() {
        val source = """
            event={"password":"CANARY_JSON_PASSWORD","token": "CANARY_JSON_TOKEN"}
            {"private_key_passphrase":"CANARY_FALLBACK_PASSPHRASE"
        """.trimIndent()
        val redactor = DiagnosticRedactor("test-salt".toByteArray())

        val redactedText = redactor.redactText(source)
        val redactedFallback = redactor.redactJson(source)

        listOf(
            "CANARY_JSON_PASSWORD",
            "CANARY_JSON_TOKEN",
            "CANARY_FALLBACK_PASSPHRASE"
        ).forEach { secret ->
            assertFalse("文本未脱敏: $secret", redactedText.contains(secret))
            assertFalse("JSON fallback 未脱敏: $secret", redactedFallback.contains(secret))
        }
    }

    @Test
    fun invalidJsonFallbackOmitsTheEntireSource() {
        val source = """
            {"client_key":["-----BEGIN PRIVATE KEY-----","CANARY_MULTILINE_KEY"]
        """.trimIndent()

        val redacted = DiagnosticRedactor("test-salt".toByteArray()).redactJson(source)

        assertFalse(redacted.contains("BEGIN PRIVATE KEY"))
        assertFalse(redacted.contains("CANARY_MULTILINE_KEY"))
        assertTrue(redacted.contains("invalid_json_omitted"))
    }

    @Test
    fun redactorRemovesMultiTokenAuthorizationAndCredentialArrays() {
        val redactor = DiagnosticRedactor("test-salt".toByteArray())
        val logSource = """
            Authorization: Basic CANARY_BASIC_TOKEN
            Proxy-Authorization: Digest username="CANARY_DIGEST_USER", response="CANARY_DIGEST_RESPONSE"
        """.trimIndent()
        val configSource = """
            {"client_key":["CANARY_KEY_LINE_1","CANARY_KEY_LINE_2"]}
        """.trimIndent()

        val redactedLogs = redactor.redactText(logSource)
        val redactedConfig = redactor.redactJson(configSource)

        listOf(
            "CANARY_BASIC_TOKEN",
            "CANARY_DIGEST_USER",
            "CANARY_DIGEST_RESPONSE"
        ).forEach { secret -> assertFalse("授权信息未脱敏: $secret", redactedLogs.contains(secret)) }
        listOf("CANARY_KEY_LINE_1", "CANARY_KEY_LINE_2").forEach { secret ->
            assertFalse("数组凭据未脱敏: $secret", redactedConfig.contains(secret))
        }
    }

    @Test
    fun redactorRemovesMultilinePrivateKeyBlocksFromLogs() {
        val source = """
            client_key=[
            -----BEGIN OPENSSH PRIVATE KEY-----
            CANARY_MULTILINE_PRIVATE_KEY
            -----END OPENSSH PRIVATE KEY-----
            ]
            private_key=[
            -----BEGIN PRIVATE KEY-----
            CANARY_TRUNCATED_PRIVATE_KEY
        """.trimIndent()

        val redacted = DiagnosticRedactor("test-salt".toByteArray()).redactText(source)

        assertFalse(redacted.contains("BEGIN OPENSSH PRIVATE KEY"))
        assertFalse(redacted.contains("CANARY_MULTILINE_PRIVATE_KEY"))
        assertFalse(redacted.contains("END OPENSSH PRIVATE KEY"))
        assertFalse(redacted.contains("CANARY_TRUNCATED_PRIVATE_KEY"))
    }

    @Test
    fun redactorRemovesProtocolSpecificCredentialFields() {
        val source = """
            {
              "auth": "CANARY_AUTH",
              "client-key": "CANARY_CLIENT_KEY",
              "short_id": "CANARY_SHORT_ID",
              "headers": {
                "Proxy-Authorization": "Basic CANARY_PROXY_AUTH",
                "Set-Cookie": "CANARY_SET_COOKIE"
              }
            }
        """.trimIndent()

        val redacted = DiagnosticRedactor("test-salt".toByteArray()).redactJson(source)

        listOf(
            "CANARY_AUTH",
            "CANARY_CLIENT_KEY",
            "CANARY_SHORT_ID",
            "CANARY_PROXY_AUTH",
            "CANARY_SET_COOKIE"
        ).forEach { secret -> assertFalse("未脱敏: $secret", redacted.contains(secret)) }
    }

    @Test
    fun redactorRemovesPassphraseCredentialsFromRunningConfig() {
        val source = """
            {
              "passphrase": "CANARY_PASSPHRASE",
              "private_key_passphrase": "CANARY_PRIVATE_KEY_PASSPHRASE",
              "backup_passphrase": "CANARY_BACKUP_PASSPHRASE"
            }
        """.trimIndent()

        val redacted = DiagnosticRedactor("test-salt".toByteArray()).redactJson(source)

        assertFalse(redacted.contains("CANARY_PASSPHRASE"))
        assertFalse(redacted.contains("CANARY_PRIVATE_KEY_PASSPHRASE"))
        assertFalse(redacted.contains("CANARY_BACKUP_PASSPHRASE"))
    }

    @Test
    fun redactorPseudonymizesAccountIdentifiersConsistently() {
        val source = """
            {
              "outbounds": [{"user": "CANARY_ACCOUNT"}],
              "inbounds": [{"users": [{"username": "CANARY_ACCOUNT"}]}],
              "route": {"rules": [{"auth_user": ["CANARY_ACCOUNT"]}]}
            }
        """.trimIndent()

        val redacted = DiagnosticRedactor("test-salt".toByteArray()).redactJson(source)
        val json = JsonParser.parseString(redacted).asJsonObject
        val user = json.getAsJsonArray("outbounds")[0].asJsonObject.get("user").asString
        val username = json.getAsJsonArray("inbounds")[0].asJsonObject
            .getAsJsonArray("users")[0].asJsonObject.get("username").asString
        val authUser = json.getAsJsonObject("route").getAsJsonArray("rules")[0].asJsonObject
            .getAsJsonArray("auth_user")[0].asString

        assertFalse(redacted.contains("CANARY_ACCOUNT"))
        assertEquals(user, username)
        assertEquals(user, authUser)
    }

    @Test
    fun redactorPseudonymizesSelectorReferencesAndOutboundLogTagsConsistently() {
        val selectedNode = "CANARY_PRIVATE_NODE"
        val trafficNode = "CANARY_TRAFFIC_TAG"
        val configSource = """
            {
              "outbounds": [
                {
                  "type": "selector",
                  "tag": "PROXY",
                  "outbounds": ["$selectedNode", "$trafficNode"],
                  "default": "$selectedNode"
                },
                {"type": "hysteria2", "tag": "$selectedNode"},
                {"type": "vless", "tag": "$trafficNode"}
              ]
            }
        """.trimIndent()
        val logSource = """
            outbound/hysteria2[$selectedNode]: connection opened
            router: using outbound/vless[$trafficNode]
        """.trimIndent()
        val redactor = DiagnosticRedactor("test-salt".toByteArray())

        val redactedConfig = redactor.redactJson(configSource)
        val redactedLogs = redactor.redactText(logSource)
        val outbounds = JsonParser.parseString(redactedConfig).asJsonObject.getAsJsonArray("outbounds")
        val selector = outbounds[0].asJsonObject
        val selectedTag = outbounds[1].asJsonObject.get("tag").asString
        val trafficTag = outbounds[2].asJsonObject.get("tag").asString

        listOf(selectedNode, trafficNode).forEach { node ->
            assertFalse("配置未脱敏: $node", redactedConfig.contains(node))
            assertFalse("日志未脱敏: $node", redactedLogs.contains(node))
        }
        assertEquals(selectedTag, selector.get("default").asString)
        assertEquals(selectedTag, selector.getAsJsonArray("outbounds")[0].asString)
        assertEquals(trafficTag, selector.getAsJsonArray("outbounds")[1].asString)
        assertTrue(redactedLogs.contains("outbound/hysteria2[$selectedTag]"))
        assertTrue(redactedLogs.contains("using outbound/vless[$trafficTag]"))
    }

    @Test
    fun diagnosticArchiveContainsExpectedSanitizedEntries() {
        val directory = Files.createTempDirectory("kunbox-diagnostic-test").toFile()
        val archive = directory.resolve("diagnostics.zip")
        val redactor = DiagnosticRedactor("test-salt".toByteArray())
        val canary = "CANARY_ARCHIVE_SECRET"

        try {
            writeDiagnosticArchive(
                archive,
                linkedMapOf(
                    "manifest.json" to "{\"format\":1}",
                    "logs.txt" to redactor.redactText("password=$canary"),
                    "running_config.json" to redactor.redactJson("{\"password\":\"$canary\"}")
                )
            )

            ZipFile(archive).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toSet()
                val content = zip.entries().asSequence().joinToString("\n") { entry ->
                    zip.getInputStream(entry).bufferedReader().use { it.readText() }
                }

                assertEquals(setOf("manifest.json", "logs.txt", "running_config.json"), names)
                assertFalse(content.contains(canary))
                assertTrue(archive.length() > 0L)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun resourceSamplerParsesCpuTimeAndCalculatesIntervalUsage() {
        val stat = "42 (com.kunk.singbox:bg worker) S 1 1 1 0 0 0 0 0 0 0 250 150 0 0 0 0 0 0 0"
        val cpuTimeMs = parseProcCpuTimeMs(stat, ticksPerSecond = 100L)
        val previous = ProcessResourcePoint(pid = 42, elapsedRealtimeMs = 1_000L, cpuTimeMs = 4_000L)
        val current = ProcessResourcePoint(pid = 42, elapsedRealtimeMs = 3_000L, cpuTimeMs = 5_000L)

        assertEquals(4_000L, cpuTimeMs)
        assertEquals(50.0, calculateProcessCpuPercent(previous, current) ?: -1.0, 0.001)
    }

    @Test
    fun resourceSamplerCalculatesProcessStartEpochFromProcStat() {
        val stat = "42 (com.kunk.singbox:bg worker) S 1 1 1 0 0 0 0 0 0 0 250 150 0 0 0 0 0 0 12345"
        val startElapsedRealtimeMs = parseProcProcessStartElapsedRealtimeMs(stat, ticksPerSecond = 100L)

        assertEquals(123_450L, startElapsedRealtimeMs)
        assertEquals(
            1_699_999_923_450L,
            calculateProcessStartedAtEpochMs(
                timestampEpochMs = 1_700_000_000_000L,
                elapsedRealtimeMs = 200_000L,
                processStartElapsedRealtimeMs = checkNotNull(startElapsedRealtimeMs)
            )
        )
    }

    @Test
    fun processStartEpochClockKeepsOneSessionStableAcrossSamplingJitter() {
        val clock = ProcessStartEpochClock(bootEpochMs = 1_699_999_800_000L)

        assertEquals(1_699_999_923_450L, clock.calculate(200_000L, 123_450L))
        assertEquals(1_699_999_923_450L, clock.calculate(200_001L, 123_450L))
    }

    @Test
    fun processCpuBaselineResetStartsANewSamplingSession() {
        val baseline = ProcessCpuBaseline()

        assertNull(baseline.update(ProcessResourcePoint(pid = 42, elapsedRealtimeMs = 1_000L, cpuTimeMs = 4_000L)))
        assertEquals(
            50.0,
            baseline.update(ProcessResourcePoint(pid = 42, elapsedRealtimeMs = 3_000L, cpuTimeMs = 5_000L)) ?: -1.0,
            0.001
        )

        baseline.reset()

        assertNull(baseline.update(ProcessResourcePoint(pid = 42, elapsedRealtimeMs = 10_000L, cpuTimeMs = 8_000L)))
    }

    @Test
    fun resourceSamplesExportAsStableCsv() {
        val csv = formatDiagnosticResourceSamplesCsv(
            listOf(
                DiagnosticResourceSample(
                    timestampEpochMs = 1_700_000_000_000L,
                    elapsedRealtimeMs = 10_000L,
                    processName = "com.kunk.singbox:bg,worker",
                    pid = 42,
                    pssKb = 12_345,
                    cpuTimeMs = 6_789L,
                    cpuPercent = 12.345,
                    fdCount = 88,
                    appVersion = "v2.21.0",
                    appVersionCode = 6913L,
                    processStartedAtEpochMs = 1_699_999_990_000L
                )
            )
        )

        assertTrue(csv.startsWith("timestamp_epoch_ms,elapsed_realtime_ms,process_name,pid,pss_kb,cpu_time_ms"))
        assertTrue(csv.contains("\"com.kunk.singbox:bg,worker\""))
        assertTrue(csv.contains(",12.35,88"))
        val decoded = parseDiagnosticResourceSamplesCsv(csv).single()
        assertEquals("v2.21.0", decoded.appVersion)
        assertEquals(6913L, decoded.appVersionCode)
        assertEquals(1_699_999_990_000L, decoded.processStartedAtEpochMs)
    }

    @Test
    fun fdTargetsAreClassifiedWithoutExportingPaths() {
        assertEquals(FdTargetType.SOCKET, classifyFdTarget("socket:[123]"))
        assertEquals(FdTargetType.ANON_INODE, classifyFdTarget("anon_inode:[eventfd]"))
        assertEquals(FdTargetType.PIPE, classifyFdTarget("pipe:[456]"))
        assertEquals(FdTargetType.ORDINARY_FILE, classifyFdTarget("/data/user/0/private/file"))
        assertEquals(FdTargetType.DEVICE, classifyFdTarget("/dev/null"))
        assertEquals(FdTargetType.UNKNOWN, classifyFdTarget(null))
    }

    @Test
    fun fdPressureBoundariesMatchRecoveryPolicy() {
        assertEquals(FdPressureLevel.NORMAL, evaluateFdPressure(499, 1_000, 0, 0).level)
        assertEquals(FdPressureLevel.OBSERVE, evaluateFdPressure(500, 1_000, 0, 0).level)
        assertEquals(FdPressureLevel.WARNING, evaluateFdPressure(700, 1_000, 0, 0).level)
        assertEquals(FdPressureLevel.WARNING, evaluateFdPressure(850, 1_000, 0, 1).level)
        assertEquals(FdPressureLevel.RECOVERY, evaluateFdPressure(850, 1_000, 0, 2).level)
        assertEquals(FdPressureLevel.EMERGENCY, evaluateFdPressure(950, 1_000, 0, 1).level)
        val unknownLimitGrowth = evaluateFdPressure(2_000, null, 1_024, 0)
        assertEquals(FdPressureLevel.WARNING, unknownLimitGrowth.level)
        assertFalse(unknownLimitGrowth.shouldRecover)
    }

    @Test
    fun fdRecoveryRequiresLowWatermarkAndMeaningfulDrop() {
        assertTrue(isFdRecoverySufficient(beforeCount = 900, afterCount = 400, softLimit = 1_000))
        assertFalse(isFdRecoverySufficient(beforeCount = 900, afterCount = 650, softLimit = 1_000))
        assertFalse(isFdRecoverySufficient(beforeCount = 900, afterCount = 400, softLimit = null))
    }

    @Test
    fun resourceCsvParserAcceptsLegacyRows() {
        val legacy = "timestamp_epoch_ms,elapsed_realtime_ms,process_name,pid,pss_kb,cpu_time_ms," +
            "cpu_percent,fd_count\n1700000000000,10000,com.kunk.singbox:bg,42,12345,6789,12.35,88\n"

        val sample = parseDiagnosticResourceSamplesCsv(legacy).single()

        assertEquals(88, sample.fdCount)
        assertNull(sample.fdSoftLimit)
        assertNull(sample.fdBreakdown)
        assertNull(sample.appVersion)
        assertNull(sample.appVersionCode)
        assertNull(sample.processStartedAtEpochMs)
    }

    @Test
    fun resourceSummarySeparatesCurrentVersionAndLatestProcessSession() {
        val samples = listOf(
            DiagnosticResourceSample(1L, 1L, "bg", 10, null, null, null, 100),
            DiagnosticResourceSample(
                2L,
                2L,
                "bg",
                11,
                null,
                null,
                null,
                101,
                appVersion = "v2.21.0",
                appVersionCode = 6913L,
                processStartedAtEpochMs = 20L
            ),
            DiagnosticResourceSample(
                3L,
                3L,
                "bg",
                11,
                null,
                null,
                null,
                102,
                appVersion = "v2.21.0",
                appVersionCode = 6913L,
                processStartedAtEpochMs = 21L
            )
        )

        val summary = summarizeDiagnosticResources(samples, currentVersionCode = 6913L)

        assertEquals(2, summary.currentVersionSampleCount)
        assertEquals(1, summary.historicalSampleCount)
        assertEquals(2, summary.processSessionCount)
        assertEquals(2, summary.latestSessionSampleCount)
        assertEquals(11, summary.latestPid)
    }

    @Test
    fun procSocketTablesUseProtocolSpecificInodeColumns() {
        val tcp = parseProcSocketRows(
            sequenceOf(
                "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode",
                "0: 0100007F:1F90 00000000:0000 0A 0:0 00:00000000 00000000 1000 0 12345"
            ),
            inodeColumn = 9,
            stateColumn = 3
        )
        val unix = parseProcSocketRows(
            sequenceOf(
                "Num RefCount Protocol Flags Type St Inode Path",
                "00000000: 00000002 00000000 00010000 0001 01 23456 /dev/socket/test"
            ),
            inodeColumn = 6,
            stateColumn = 5
        )
        val netlink = parseProcSocketRows(
            sequenceOf(
                "sk Eth Pid Groups Rmem Wmem Dump Locks Drops Inode",
                "00000000 0 42 00000000 0 0 0 2 0 33456"
            ),
            inodeColumn = 9,
            stateColumn = 1
        )
        val packet = parseProcSocketRows(
            sequenceOf(
                "sk RefCnt Type Proto Iface R Rmem User Inode",
                "00000000 3 3 0003 2 1 0 1000 34567"
            ),
            inodeColumn = 8,
            stateColumn = 3
        )

        assertEquals("0A", tcp["12345"])
        assertEquals("01", unix["23456"])
        assertEquals("0", netlink["33456"])
        assertEquals("0003", packet["34567"])
    }

    @Test
    fun resourceCsvPreservesExtendedSocketBreakdownAndReadFailures() {
        val sample = DiagnosticResourceSample(
            timestampEpochMs = 1L,
            elapsedRealtimeMs = 2L,
            processName = "com.kunk.singbox:bg",
            pid = 42,
            pssKb = null,
            cpuTimeMs = null,
            cpuPercent = null,
            fdCount = 100,
            fdBreakdown = FdBreakdown(
                socketUniqueCount = 11,
                unixCount = 7,
                netlinkCount = 3,
                packetCount = 2,
                socketUnknownCount = 5,
                socketTableFailures = "packet:FileNotFoundException"
            )
        )

        val decoded = parseDiagnosticResourceSamplesCsv(formatDiagnosticResourceSamplesCsv(listOf(sample))).single()

        assertEquals(11, decoded.fdBreakdown?.socketUniqueCount)
        assertEquals(7, decoded.fdBreakdown?.unixCount)
        assertEquals(3, decoded.fdBreakdown?.netlinkCount)
        assertEquals(2, decoded.fdBreakdown?.packetCount)
        assertEquals(5, decoded.fdBreakdown?.socketUnknownCount)
        assertEquals("packet:FileNotFoundException", decoded.fdBreakdown?.socketTableFailures)
    }

    @Test
    fun resourceCsvPreservesNativeLibboxGapAndProcReadFailureStage() {
        val sample = DiagnosticResourceSample(
            timestampEpochMs = 1L,
            elapsedRealtimeMs = 2L,
            processName = "com.kunk.singbox:bg",
            pid = 42,
            pssKb = null,
            cpuTimeMs = null,
            cpuPercent = null,
            fdCount = 1_024,
            libboxActiveConnections = 24,
            nativeLibboxSocketDelta = 876,
            nativePreConnectGap = 876,
            socketAttributionStatus = "native_preconnect_gap",
            fdReadFailureStage = "fd_readlink;socket_tables"
        )

        val decoded = parseDiagnosticResourceSamplesCsv(formatDiagnosticResourceSamplesCsv(listOf(sample))).single()

        assertEquals(24, decoded.libboxActiveConnections)
        assertEquals(876, decoded.nativeLibboxSocketDelta)
        assertEquals(876, decoded.nativePreConnectGap)
        assertEquals("native_preconnect_gap", decoded.socketAttributionStatus)
        assertEquals("fd_readlink;socket_tables", decoded.fdReadFailureStage)
    }

    @Test
    fun socketAttributionLogsKeepCountsAndRedactRuntimeIdentities() {
        val sample = DiagnosticResourceSample(
            timestampEpochMs = 1L,
            elapsedRealtimeMs = 2L,
            processName = "com.kunk.singbox:bg",
            pid = 42,
            pssKb = null,
            cpuTimeMs = null,
            cpuPercent = null,
            fdCount = 1_024,
            fdBreakdown = FdBreakdown(socketCount = 910, socketUniqueCount = 900),
            libboxActiveConnections = 24,
            nativeLibboxSocketDelta = 876,
            nativePreConnectGap = 876,
            socketAttributionStatus = "native_preconnect_gap",
            connectionAttribution = ConnectionAttributionSnapshot(
                activeConnections = 24,
                outboundCounts = mapOf("paid residential node" to 20),
                chainCounts = mapOf("front-a>paid residential node" to 20),
                protocolCounts = mapOf("tcp/tls" to 20),
                applicationCounts = mapOf("com.private.app" to 20)
            )
        )

        val lines = buildSocketAttributionDiagnosticLines(sample)
        val redacted = DiagnosticRedactor("test-salt".toByteArray()).redactText(lines.joinToString("\n"))

        assertTrue(lines.first().contains("native_preconnect_gap=876"))
        assertTrue(lines.any { it.contains("dimension=outbound") && it.contains("count=20") })
        assertTrue(lines.any { it.contains("dimension=application") && it.contains("count=20") })
        assertFalse(redacted.contains("paid residential node"))
        assertFalse(redacted.contains("com.private.app"))
        assertTrue(redacted.contains("count=20"))
    }

    @Test
    fun backgroundResourceHistoryKeepsOnlyCompleteBoundedRows() {
        val directory = Files.createTempDirectory("kunbox-resource-history").toFile()
        val file = directory.resolve("resources.csv")
        try {
            val history = DiagnosticResourceHistory(file, maxSamples = 3)
            repeat(5) { index ->
                history.append(
                    DiagnosticResourceSample(
                        timestampEpochMs = index.toLong(),
                        elapsedRealtimeMs = index.toLong(),
                        processName = "com.kunk.singbox:bg",
                        pid = 42,
                        pssKb = null,
                        cpuTimeMs = null,
                        cpuPercent = null,
                        fdCount = 100 + index
                    )
                )
            }

            val retained = history.read()
            assertEquals(listOf(2L, 3L, 4L), retained.map { it.timestampEpochMs })
            assertEquals(4, file.readLines(Charsets.UTF_8).size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun backgroundResourceHistoryMigratesLegacyHeaderBeforeAppending() {
        val directory = Files.createTempDirectory("kunbox-resource-history-migration").toFile()
        val file = directory.resolve("resources.csv")
        try {
            file.writeText(
                "timestamp_epoch_ms,elapsed_realtime_ms,process_name,pid,pss_kb,cpu_time_ms," +
                    "cpu_percent,fd_count,fd_soft_limit,fd_ratio,fd_socket,fd_anon_inode,fd_eventfd," +
                    "fd_eventpoll,fd_timerfd,fd_pipe,fd_file,fd_device,fd_unknown,socket_tcp,socket_tcp6," +
                    "socket_udp,socket_udp6,socket_raw,socket_raw6,socket_unknown,socket_table_failures," +
                    "socket_states\n" +
                    "1,2,com.kunk.singbox:bg,42,,,,32700,32768,0.9979,32600,20,5,5,0,2,70,7,1," +
                    "0,0,0,0,0,0,32600,permission,unknown=32600\n",
                Charsets.UTF_8
            )
            val history = DiagnosticResourceHistory(file, maxSamples = 3)

            history.append(
                DiagnosticResourceSample(
                    timestampEpochMs = 3L,
                    elapsedRealtimeMs = 4L,
                    processName = "com.kunk.singbox:bg",
                    pid = 43,
                    pssKb = null,
                    cpuTimeMs = null,
                    cpuPercent = null,
                    fdCount = 150,
                    fdBreakdown = FdBreakdown(socketCount = 10, socketUniqueCount = 2)
                )
            )

            val lines = file.readLines(Charsets.UTF_8)
            val retained = history.read()
            assertEquals(DIAGNOSTIC_RESOURCE_CSV_HEADER, lines.first())
            assertEquals(listOf(1L, 3L), retained.map { it.timestampEpochMs })
            assertEquals(32600, retained.first().fdBreakdown?.socketUnknownCount)
            assertEquals(2, retained.last().fdBreakdown?.socketUniqueCount)
        } finally {
            directory.deleteRecursively()
        }
    }
}

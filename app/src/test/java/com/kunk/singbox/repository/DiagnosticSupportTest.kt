package com.kunk.singbox.repository

import com.google.gson.JsonParser
import com.kunk.singbox.utils.perf.DiagnosticResourceSample
import com.kunk.singbox.utils.perf.ProcessCpuBaseline
import com.kunk.singbox.utils.perf.ProcessResourcePoint
import com.kunk.singbox.utils.perf.calculateProcessCpuPercent
import com.kunk.singbox.utils.perf.formatDiagnosticResourceSamplesCsv
import com.kunk.singbox.utils.perf.parseProcCpuTimeMs
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
                    fdCount = 88
                )
            )
        )

        assertTrue(csv.startsWith("timestamp_epoch_ms,elapsed_realtime_ms,process_name,pid,pss_kb,cpu_time_ms"))
        assertTrue(csv.contains("\"com.kunk.singbox:bg,worker\""))
        assertTrue(csv.contains(",12.35,88"))
    }
}

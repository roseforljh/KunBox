package com.kunk.singbox.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuleSetRepositoryTest {

    @Test
    fun ruleSetCacheFileNameKeepsSafeTagsCompatible() {
        assertEquals(
            "geosite-geolocation-!cn.srs",
            RuleSetRepository.ruleSetCacheFileName("geosite-geolocation-!cn")
        )
    }

    @Test
    fun ruleSetCacheFileNameEscapesPathTraversalTags() {
        val fileName = RuleSetRepository.ruleSetCacheFileName("../outside")

        assertFalse(fileName.contains(".."))
        assertFalse(fileName.contains("/"))
        assertFalse(fileName.contains("\\"))
        assertTrue(fileName.endsWith(".srs"))
        assertTrue(fileName.startsWith("outside-"))
    }

    @Test
    fun downloadedSourceJsonRuleSetIsValid() {
        val content = """
            {
              "version": 3,
              "rules": [
                { "domain_suffix": ["example.com"] }
              ]
            }
        """.trimIndent()

        assertTrue(
            RuleSetRepository.isDownloadedRuleSetValidForTest(
                header = content,
                fileLength = content.toByteArray().size.toLong(),
                format = "source"
            )
        )
    }

    @Test
    fun downloadedSourceJsonRuleSetIsValidWhenRulesKeyIsAfterLongMetadata() {
        val content = """
            {
              "version": 3,
              "metadata": "${"x".repeat(256)}",
              "rules": [
                { "domain_suffix": ["example.com"] }
              ]
            }
        """.trimIndent()

        assertTrue(
            RuleSetRepository.isDownloadedRuleSetValidForTest(
                header = content,
                fileLength = content.toByteArray().size.toLong(),
                format = "source"
            )
        )
    }

    @Test
    fun downloadedBinaryJsonErrorIsInvalid() {
        val content = """{"message":"not found"}"""

        assertFalse(
            RuleSetRepository.isDownloadedRuleSetValidForTest(
                header = content,
                fileLength = content.toByteArray().size.toLong(),
                format = "binary"
            )
        )
    }

    @Test
    fun downloadedBinaryTextErrorIsInvalid() {
        val content = "429 Too Many Requests\nrate limit exceeded"

        assertFalse(
            RuleSetRepository.isDownloadedRuleSetValidForTest(
                header = content,
                fileLength = content.toByteArray().size.toLong(),
                format = "binary"
            )
        )
    }

    @Test
    fun downloadedBinaryRuleSetWithSrsMagicIsValid() {
        val content = "SRS\u0001binary-payload"

        assertTrue(
            RuleSetRepository.isDownloadedRuleSetValidForTest(
                header = content,
                fileLength = content.toByteArray().size.toLong(),
                format = "binary"
            )
        )
    }

    @Test
    fun downloadedHtmlRuleSetIsInvalid() {
        val content = "<!DOCTYPE html><html></html>"

        assertFalse(
            RuleSetRepository.isDownloadedRuleSetValidForTest(
                header = content,
                fileLength = content.toByteArray().size.toLong(),
                format = "source"
            )
        )
    }

    @Test
    fun forceUpdateDownloadsEvenWhenCacheIsFresh() {
        assertTrue(
            RuleSetRepository.shouldDownloadRemoteRuleSetForTest(
                fileExists = true,
                allowNetwork = true,
                forceUpdate = true,
                isExpired = false
            )
        )
    }

    @Test
    fun nonForcedUpdateSkipsFreshCache() {
        assertFalse(
            RuleSetRepository.shouldDownloadRemoteRuleSetForTest(
                fileExists = true,
                allowNetwork = true,
                forceUpdate = false,
                isExpired = false
            )
        )
    }

    @Test
    fun missingCacheDownloadsWhenNetworkAllowed() {
        assertTrue(
            RuleSetRepository.shouldDownloadRemoteRuleSetForTest(
                fileExists = false,
                allowNetwork = true,
                forceUpdate = false,
                isExpired = false
            )
        )
    }

    @Test
    fun forcedUpdateFailureIsNotReadyJustBecauseOldCacheExists() {
        assertFalse(
            RuleSetRepository.isRemoteRuleSetReadyAfterDownloadFailureForTest(
                fileExists = true,
                forceUpdate = true
            )
        )
        assertTrue(
            RuleSetRepository.isRemoteRuleSetReadyAfterDownloadFailureForTest(
                fileExists = true,
                forceUpdate = false
            )
        )
    }

    @Test
    fun downloadTempFileNamesAreUniquePerAttempt() {
        val tempDir = java.nio.file.Files.createTempDirectory("ruleset_download_").toFile()
        val target = java.io.File(tempDir, "geosite-cn.srs")

        val first = RuleSetRepository.createDownloadTempFileForTest(target)
        val second = RuleSetRepository.createDownloadTempFileForTest(target)

        assertTrue(first.name.startsWith("geosite-cn.srs."))
        assertTrue(second.name.startsWith("geosite-cn.srs."))
        assertTrue(first.name.endsWith(".tmp"))
        assertTrue(second.name.endsWith(".tmp"))
        assertFalse(first.name == second.name)
    }

    @Test
    fun ruleSetDownloadsUseCancellableOkHttpCall() {
        val source = File("src/main/java/com/kunk/singbox/repository/RuleSetRepository.kt").readText(Charsets.UTF_8)

        assertTrue(source.contains("NetworkClient.executeCancellable(client, request) { response ->"))
        assertFalse(source.contains("client.newCall(request).execute().use { response ->"))
    }

    @Test
    fun ruleSetDownloadFallbackDoesNotSwallowCancellation() {
        val source = File("src/main/java/com/kunk/singbox/repository/RuleSetRepository.kt").readText(Charsets.UTF_8)

        assertTrue(source.contains("import kotlinx.coroutines.CancellationException"))
        assertTrue(source.countOccurrences("if (e is CancellationException) throw e") >= 2)
    }

    private fun String.countOccurrences(pattern: String): Int {
        return split(pattern).size - 1
    }
}

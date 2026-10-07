package com.kunk.singbox.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetType
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
            RuleSetRepository.isDownloadedRuleSetContentValid(
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
            RuleSetRepository.isDownloadedRuleSetContentValid(
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
            RuleSetRepository.isDownloadedRuleSetContentValid(
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
            RuleSetRepository.isDownloadedRuleSetContentValid(
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
            RuleSetRepository.isDownloadedRuleSetContentValid(
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
            RuleSetRepository.isDownloadedRuleSetContentValid(
                header = content,
                fileLength = content.toByteArray().size.toLong(),
                format = "source"
            )
        )
    }

    @Test
    fun defaultDisabledRuleSetsAreStillPrefetched() {
        val source = File("src/main/java/com/kunk/singbox/repository/RuleSetRepository.kt")
            .readText(Charsets.UTF_8)
        val prefetchBody = source.substringAfter("suspend fun prefetchRuleSet(")
            .substringBefore("fun getRuleSetPath(")

        assertFalse(prefetchBody.contains("if (!ruleSet.enabled) return@withContext true"))
    }

    @Test
    fun startupReadinessDoesNotInstallBundledRuleSetAsRemoteCache() {
        val source = File("src/main/java/com/kunk/singbox/repository/RuleSetRepository.kt")
            .readText(Charsets.UTF_8)

        assertFalse(source.contains("installBaselineRuleSet"))
        assertTrue(source.contains("isDownloadedRuleSetFileValid(file, ruleSet.format)"))
    }

    @Test
    fun cacheSourceKeyChangesWithUrlAndFormat() {
        val ruleSet = RuleSet(tag = "same-tag", type = RuleSetType.REMOTE, url = "https://a.example/a.srs")
        val firstKey = RuleSetRepository.ruleSetSourceKey(ruleSet)

        assertEquals(64, firstKey.length)
        assertFalse(firstKey == RuleSetRepository.ruleSetSourceKey(ruleSet.copy(url = "https://b.example/b.srs")))
        assertFalse(firstKey == RuleSetRepository.ruleSetSourceKey(ruleSet.copy(format = "source")))
    }

    @Test
    fun cacheSourceKeyIgnoresGithubMirror() {
        val raw = RuleSet(
            tag = "geosite-google",
            type = RuleSetType.REMOTE,
            url = "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-google.srs"
        )
        val cdn = raw.copy(url = "https://cdn.jsdelivr.net/gh/SagerNet/sing-geosite@rule-set/geosite-google.srs")

        assertEquals(RuleSetRepository.ruleSetSourceKey(raw), RuleSetRepository.ruleSetSourceKey(cdn))
        assertTrue(RuleSetRepository.canUseLegacyRuleSetCache(cdn, raw))
    }

    @Test
    fun missingOrInvalidCacheCannotBeEnabled() {
        val dir = java.nio.file.Files.createTempDirectory("ruleset_cache_").toFile()
        try {
            val file = File(dir, "geosite-cn.srs")
            val ruleSet = RuleSet(tag = "geosite-cn", type = RuleSetType.REMOTE, url = "https://example.org/cn.srs")
            assertFalse(RuleSetRepository.isRemoteRuleSetCacheReady(ruleSet, file, requireSource = true))
            file.writeText("not a valid rule set")
            File(dir, "${file.name}.source").writeText(RuleSetRepository.ruleSetSourceKey(ruleSet))
            assertFalse(RuleSetRepository.isRemoteRuleSetCacheReady(ruleSet, file, requireSource = true))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun enablingRemoteRuleSetRequiresVerifiedMatchingSource() {
        val dir = java.nio.file.Files.createTempDirectory("ruleset_cache_").toFile()
        try {
            val file = File(dir, "geosite-cn.srs")
            val marker = File(dir, "${file.name}.source")
            val ruleSet = RuleSet(tag = "geosite-cn", type = RuleSetType.REMOTE, url = "https://example.org/cn.srs")
            file.writeText("SRS\u0001binary-payload")
            assertFalse(RuleSetRepository.isRemoteRuleSetCacheReady(ruleSet, file, requireSource = true))
            assertTrue(RuleSetRepository.isRemoteRuleSetCacheReady(ruleSet, file)) // 兼容同来源的旧版已启用缓存
            marker.writeText(RuleSetRepository.ruleSetSourceKey(ruleSet))
            assertTrue(RuleSetRepository.isRemoteRuleSetCacheReady(ruleSet, file, requireSource = true))
            assertFalse(
                RuleSetRepository.isRemoteRuleSetCacheReady(ruleSet.copy(url = "https://elsewhere.org/cn.srs"), file)
            )
            assertFalse(RuleSetRepository.isRemoteRuleSetCacheReady(ruleSet.copy(format = "source"), file))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun legacyCacheOnlyAllowedForSamePreviouslyEnabledSource() {
        val previous = RuleSet(tag = "geosite-cn", type = RuleSetType.REMOTE, url = "https://example.org/cn.srs")
        assertTrue(RuleSetRepository.canUseLegacyRuleSetCache(previous, previous))
        assertFalse(RuleSetRepository.canUseLegacyRuleSetCache(previous, null))
        assertFalse(RuleSetRepository.canUseLegacyRuleSetCache(previous, previous.copy(enabled = false)))
        assertFalse(RuleSetRepository.canUseLegacyRuleSetCache(previous.copy(url = "https://new.org/cn.srs"), previous))
        assertFalse(RuleSetRepository.canUseLegacyRuleSetCache(previous.copy(tag = "geoip-cn"), previous))
    }

    @Test
    fun allRuleSetWritesGuardEnabledStateEvenOutsideTheEditor() {
        val source = File("src/main/java/com/kunk/singbox/repository/SettingsRepository.kt").readText(Charsets.UTF_8)
        val body = source.substringAfter("suspend fun setRuleSets(").substringBefore("suspend fun getRuleSets(")
        assertTrue(body.contains("ruleSetRepo.prefetchRuleSet("))
        assertTrue(body.contains("allowNetwork = false"))
        assertTrue(body.contains("ruleSet.copy(enabled = false)"))
    }

    @Test
    fun forceUpdateDownloadsEvenWhenCacheIsFresh() {
        assertTrue(
            RuleSetRepository.shouldDownloadRemoteRuleSet(
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
            RuleSetRepository.shouldDownloadRemoteRuleSet(
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
            RuleSetRepository.shouldDownloadRemoteRuleSet(
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
            RuleSetRepository.isRemoteRuleSetReadyAfterDownloadFailure(
                fileExists = true,
                forceUpdate = true
            )
        )
        assertTrue(
            RuleSetRepository.isRemoteRuleSetReadyAfterDownloadFailure(
                fileExists = true,
                forceUpdate = false
            )
        )
    }

    @Test
    fun downloadTempFileNamesAreUniquePerAttempt() {
        val tempDir = java.nio.file.Files.createTempDirectory("ruleset_download_").toFile()
        val target = java.io.File(tempDir, "geosite-cn.srs")

        val first = RuleSetRepository.createDownloadTempFile(target)
        val second = RuleSetRepository.createDownloadTempFile(target)

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

package com.kunk.singbox.repository

import com.google.gson.Gson
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.DnsStrategy
import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.EchConfig
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.OutboundTag
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetConfig
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.RuleType
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.BatchUpdateResult
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.model.DefaultRule
import com.kunk.singbox.model.SubscriptionUpdateStage
import com.kunk.singbox.model.SubscriptionUpdateResult
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.UpdateStatus
import com.kunk.singbox.utils.parser.Base64Parser
import com.kunk.singbox.utils.parser.ClashYamlParser
import com.kunk.singbox.utils.parser.NodeLinkParser
import com.kunk.singbox.utils.parser.SingBoxParser
import com.kunk.singbox.utils.parser.SubscriptionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

@Suppress("LargeClass")
class ConfigRepositoryTest {

    private val gson = Gson()
    private val nodeLinkParser = NodeLinkParser(gson)
    private val subscriptionManager = SubscriptionManager(
        listOf(
            SingBoxParser(gson),
            ClashYamlParser(),
            Base64Parser { nodeLinkParser.parse(it) }
        )
    )

    private fun invokeAppliedRemoteRuleSetFilter(
        ruleSets: List<RuleSet>,
        validRuleSets: List<RuleSetConfig>
    ): List<RuleSet> {
        val validTags = validRuleSets.mapNotNull { it.tag }.toSet()
        return ConfigRepository.filterAppliedRemoteRuleSetsForTest(ruleSets, validTags)
    }

    private fun createUpdatingProfile(profileId: String): ProfileUi {
        return ProfileUi(
            id = profileId,
            name = "Test Profile",
            type = ProfileType.Subscription,
            url = "https://example.com/sub",
            lastUpdated = 0,
            enabled = true,
            updateStatus = UpdateStatus.Updating,
            updateStage = SubscriptionUpdateStage.Requesting
        )
    }

    private fun applyStageForRun(
        profiles: MutableStateFlow<List<ProfileUi>>,
        activeRuns: Map<String, Long>,
        profileId: String,
        runId: Long,
        stage: SubscriptionUpdateStage?
    ) {
        ConfigRepository.setProfileUpdateStageIfCurrent(
            profilesState = profiles,
            activeUpdateRuns = activeRuns,
            profileId = profileId,
            runId = runId,
            stage = stage
        )
    }

    @Test
    fun testStableNodeIdConsistency() {
        val profileId = "profile-123"
        val outboundTag = "node-abc"

        val id1 = ConfigRepository.stableNodeId(profileId, outboundTag)
        val id2 = ConfigRepository.stableNodeId(profileId, outboundTag)

        assertEquals(id1, id2)
        assertTrue(id1.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun testStableNodeIdDifferentInputs() {
        val id1 = ConfigRepository.stableNodeId("profile-1", "node-a")
        val id2 = ConfigRepository.stableNodeId("profile-1", "node-b")
        val id3 = ConfigRepository.stableNodeId("profile-2", "node-a")

        assertNotEquals(id1, id2)
        assertNotEquals(id1, id3)
        assertNotEquals(id2, id3)
    }

    @Test
    fun testStableNodeIdSpecialCharacters() {
        val id = ConfigRepository.stableNodeId("profile/with/slashes", "node#with#hash")

        assertNotNull(id)
        assertTrue(id.isNotBlank())
    }

    @Test
    fun testStableNodeIdEmptyInputs() {
        val id1 = ConfigRepository.stableNodeId("", "node")
        val id2 = ConfigRepository.stableNodeId("profile", "")
        val id3 = ConfigRepository.stableNodeId("", "")

        assertNotNull(id1)
        assertNotNull(id2)
        assertNotNull(id3)
        assertNotEquals(id1, id2)
    }

    @Test
    fun testStableNodeIdUnicodeCharacters() {
        val id = ConfigRepository.stableNodeId("日本配置", "香港节点-01")

        assertNotNull(id)
        assertTrue(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))

        val id2 = ConfigRepository.stableNodeId("日本配置", "香港节点-01")
        assertEquals(id, id2)
    }

    @Test
    fun testStableNodeIdCacheEfficiency() {
        val profileId = "cache-test-profile"
        val outboundTag = "cache-test-node"

        val startTime = System.nanoTime()
        repeat(10000) {
            ConfigRepository.stableNodeId(profileId, outboundTag)
        }
        val duration = System.nanoTime() - startTime

        assertTrue(duration < 100_000_000L)
    }

    @Test
    fun testExtractSubscriptionUrlFromHtml() {
        val html = """
            <html>
            <body>
              <input
                type="text"
                value="https://conf1.example.com/token-123"
                readonly
                id="sub_url"
                class="link-input">
            </body>
            </html>
        """.trimIndent()

        val actual = ConfigRepository.extractSubscriptionUrlFromHtml(html)

        assertEquals("https://conf1.example.com/token-123", actual)
    }

    @Test
    fun testLooksLikeHtmlSubscriptionPageDoesNotTrustContentTypeAloneForYamlBody() {
        val result = ConfigRepository.looksLikeHtmlSubscriptionPage(
            contentType = "text/html; charset=utf-8",
            body = "mixed-port: 7890"
        )

        assertFalse(result)
    }

    @Test
    fun testLooksLikeHtmlSubscriptionPageDoesNotTrustContentTypeAloneForBase64Body() {
        val result = ConfigRepository.looksLikeHtmlSubscriptionPage(
            contentType = "text/html; charset=utf-8",
            body = "c3M6Ly9ZV1Z6TFRFeU9DMW5ZMjA9QDEyNy4wLjAuMTo0NDMjVEVTVA=="
        )

        assertFalse(result)
    }

    @Test
    fun testLooksLikeHtmlSubscriptionPageByBodyPrefix() {
        val result = ConfigRepository.looksLikeHtmlSubscriptionPage(
            contentType = null,
            body = "<!DOCTYPE html><html><body>订阅信息</body></html>"
        )

        assertTrue(result)
    }

    @Test
    fun testLooksLikeHtmlSubscriptionPageByHtmlTagPrefixEvenWhenContentTypeIsTextHtml() {
        val result = ConfigRepository.looksLikeHtmlSubscriptionPage(
            contentType = "text/html; charset=utf-8",
            body = "<html><body>订阅信息</body></html>"
        )

        assertTrue(result)
    }

    @Test
    fun testExtractSubscriptionHost() {
        val host = ConfigRepository.extractSubscriptionHost(
            "https://1.811200.xyz/api/v1/client/subscribe?token=abc"
        )

        assertEquals("1.811200.xyz", host)
    }

    @Test
    fun testLooksLikeSubscriptionUrlForImportAcceptsSubscriptionApiUrl() {
        assertTrue(
            ConfigRepository.looksLikeSubscriptionUrlForImport(
                "https://sub.example.com/api/v1/client/subscribe?token=abc123"
            )
        )
    }

    @Test
    fun testLooksLikeSubscriptionUrlForImportAcceptsPortedSubscriptionApiUrl() {
        assertTrue(
            ConfigRepository.looksLikeSubscriptionUrlForImport(
                "https://sub.example.com:8443/api/v1/client/subscribe?token=abc123"
            )
        )
    }

    @Test
    fun testLooksLikeSubscriptionUrlForImportRejectsHttpProxyLink() {
        assertFalse(
            ConfigRepository.looksLikeSubscriptionUrlForImport(
                "http://proxy.example.com:3128#NoAuthProxy"
            )
        )
    }

    @Test
    fun testPrioritizeUserAgentsWithPreferredValue() {
        val prioritized = ConfigRepository.prioritizeUserAgents("sing-box/1.13.1")

        assertEquals("sing-box/1.13.1", prioritized.first())
        assertEquals(prioritized.size, prioritized.distinct().size)
        assertTrue(prioritized.contains("ClashMeta/1.18.0"))
    }

    @Test
    fun testPrioritizeUserAgentsWithoutPreferredValue() {
        val prioritized = ConfigRepository.prioritizeUserAgents(null)

        assertEquals("ClashMeta/1.18.0", prioritized.first())
        assertTrue(prioritized.contains("sing-box/1.13.1"))
    }

    @Test
    fun testFilterCircuitBrokenUserAgents() {
        val result = ConfigRepository.filterCircuitBrokenUserAgents(
            userAgents = listOf("ClashMeta/1.18.0", "Clash/1.18.0", "sing-box/1.13.1"),
            circuitBrokenUserAgents = setOf("ClashMeta/1.18.0", "Clash/1.18.0")
        )

        assertEquals(listOf("sing-box/1.13.1"), result)
    }

    @Test
    fun testFilterCircuitBrokenUserAgentsFallsBackWhenAllBlocked() {
        val original = listOf("ClashMeta/1.18.0", "Clash/1.18.0")
        val result = ConfigRepository.filterCircuitBrokenUserAgents(
            userAgents = original,
            circuitBrokenUserAgents = original.toSet()
        )

        assertEquals(original, result)
    }

    @Test
    fun testBuildSubscriptionAttemptUserAgentsKeepsRememberedUserAgentFirst() {
        val userAgents = ConfigRepository.buildSubscriptionAttemptUserAgents(
            preferredUserAgent = "sing-box/1.13.1",
            circuitBrokenUserAgents = setOf("Clash/1.18.0")
        )

        assertEquals("sing-box/1.13.1", userAgents.first())
        assertTrue(!userAgents.contains("Clash/1.18.0"))
    }

    @Test
    fun testResolveSubscriptionUpdateBudgetSecondsFallsBackToDefaultWhenNonPositive() {
        val budgetSeconds = ConfigRepository.resolveSubscriptionUpdateBudgetSeconds(0)

        assertEquals(AppSettings().subscriptionUpdateTimeout.toLong(), budgetSeconds)
    }

    @Test
    fun testResolveSubscriptionAttemptTimeoutBudgetUsesFullRemainingBudget() {
        val budget = ConfigRepository.resolveSubscriptionAttemptTimeoutBudget(
            totalBudgetSeconds = 30,
            elapsedMs = 0
        )

        assertNotNull(budget)
        assertEquals(30L, budget?.connectTimeoutSeconds)
        assertEquals(30L, budget?.readTimeoutSeconds)
        assertEquals(30L, budget?.writeTimeoutSeconds)
        assertEquals(30L, budget?.callTimeoutSeconds)
    }

    @Test
    fun testResolveSubscriptionAttemptTimeoutBudgetRoundsUpRemainingBudget() {
        val budget = ConfigRepository.resolveSubscriptionAttemptTimeoutBudget(
            totalBudgetSeconds = 30,
            elapsedMs = 29_100
        )

        assertNotNull(budget)
        assertEquals(1L, budget?.connectTimeoutSeconds)
        assertEquals(1L, budget?.readTimeoutSeconds)
        assertEquals(1L, budget?.writeTimeoutSeconds)
        assertEquals(1L, budget?.callTimeoutSeconds)
    }

    @Test
    fun testResolveSubscriptionAttemptTimeoutBudgetReturnsNullWhenBudgetExhausted() {
        val budget = ConfigRepository.resolveSubscriptionAttemptTimeoutBudget(
            totalBudgetSeconds = 30,
            elapsedMs = 30_000
        )

        assertNull(budget)
    }

    @Test
    fun testResolveSubscriptionUpdateStageMapsKnownStages() {
        assertEquals(
            SubscriptionUpdateStage.Requesting,
            ConfigRepository.resolveSubscriptionUpdateStage("requesting")
        )
        assertEquals(
            SubscriptionUpdateStage.Parsing,
            ConfigRepository.resolveSubscriptionUpdateStage("parsing")
        )
        assertEquals(
            SubscriptionUpdateStage.Saving,
            ConfigRepository.resolveSubscriptionUpdateStage("saving")
        )
        assertEquals(
            SubscriptionUpdateStage.DnsBackground,
            ConfigRepository.resolveSubscriptionUpdateStage("dns_background")
        )
        assertNull(ConfigRepository.resolveSubscriptionUpdateStage("unknown"))
    }

    @Test
    fun testLaunchSubscriptionDnsPreResolveReturnsWithoutWaitingForResolution() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        try {
            val startNs = System.nanoTime()
            val job = ConfigRepository.launchSubscriptionDnsPreResolve(
                scope = scope,
                profileId = "profile-1",
                enabled = true
            ) {
                started.complete(Unit)
                release.await()
                true
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

            runBlocking { started.await() }

            assertNotNull(job)
            assertTrue(elapsedMs < 200)
            assertTrue(job?.isActive == true)

            release.complete(Unit)
            runBlocking { job?.join() }

            assertTrue(job?.isCompleted == true)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testLaunchSubscriptionDnsPreResolveSwallowsBackgroundFailure() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val job = ConfigRepository.launchSubscriptionDnsPreResolve(
                scope = scope,
                profileId = "profile-2",
                enabled = true
            ) {
                throw SocketTimeoutException("dns timeout")
            }

            runBlocking { job?.join() }

            assertNotNull(job)
            assertTrue(job?.isCompleted == true)
            assertFalse(job?.isCancelled == true)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testLaunchSubscriptionDnsPreResolveStaleRunCannotClearNewUpdateStage() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val profileId = "profile-3"
        val profiles = MutableStateFlow(listOf(createUpdatingProfile(profileId)))
        val activeRuns = mutableMapOf(profileId to 1L)
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()

        try {
            val oldJob = ConfigRepository.launchSubscriptionDnsPreResolve(
                scope = scope,
                profileId = profileId,
                enabled = true,
                updateRunId = 1L,
                onStarted = {
                    applyStageForRun(profiles, activeRuns, profileId, 1L, SubscriptionUpdateStage.DnsBackground)
                    oldStarted.complete(Unit)
                },
                onFinished = {
                    applyStageForRun(profiles, activeRuns, profileId, 1L, null)
                }
            ) {
                releaseOld.await()
                true
            }

            runBlocking { oldStarted.await() }
            assertEquals(SubscriptionUpdateStage.DnsBackground, profiles.value.single().updateStage)

            activeRuns[profileId] = 2L
            applyStageForRun(profiles, activeRuns, profileId, 2L, SubscriptionUpdateStage.Requesting)

            releaseOld.complete(Unit)
            runBlocking { oldJob?.join() }

            assertEquals(SubscriptionUpdateStage.Requesting, profiles.value.single().updateStage)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testBatchUpdateResultAggregatesMixedSubscriptionResults() {
        val result = BatchUpdateResult(
            successWithChanges = 1,
            successNoChanges = 2,
            failed = 1,
            details = listOf(
                SubscriptionUpdateResult.SuccessWithChanges("A", 1, 0, 3),
                SubscriptionUpdateResult.SuccessNoChanges("B", 2),
                SubscriptionUpdateResult.SuccessNoChanges("C", 2),
                SubscriptionUpdateResult.Failed("D", "timeout")
            )
        )

        assertEquals(4, result.totalCount)
        assertEquals(3, result.successCount)
        assertEquals(4, result.details.size)
    }

    @Test
    fun testResolveAppRuleOutboundModeDefaultsToProxy() {
        val resolved = ConfigRepository.resolveAppRuleOutboundMode(null)

        assertEquals(RuleSetOutboundMode.PROXY, resolved)
    }

    @Test
    fun testResolveAppRuleOutboundModeKeepsExplicitMode() {
        val resolved = ConfigRepository.resolveAppRuleOutboundMode(RuleSetOutboundMode.DIRECT)

        assertEquals(RuleSetOutboundMode.DIRECT, resolved)
    }

    @Test
    fun testShouldRecordSubscriptionNetworkFailureForConnectException() {
        assertTrue(
            ConfigRepository.shouldRecordSubscriptionNetworkFailure(
                ConnectException("failed to connect")
            )
        )
    }

    @Test
    fun testShouldRecordSubscriptionNetworkFailureForTimeoutException() {
        assertTrue(
            ConfigRepository.shouldRecordSubscriptionNetworkFailure(
                SocketTimeoutException("timeout")
            )
        )
    }

    @Test
    fun testShouldRecordSubscriptionNetworkFailureForParseError() {
        val result = ConfigRepository.shouldRecordSubscriptionNetworkFailure(
            IllegalArgumentException("parse failed")
        )

        assertTrue(!result)
    }

    @Test
    fun testShouldStopSubscriptionFallbackForHtmlInfoPage() {
        val result = ConfigRepository.shouldStopSubscriptionFallback(
            looksLikeHtmlInfoPage = true
        )

        assertTrue(result)
    }

    @Test
    fun testShouldStopSubscriptionFallbackForHttp429() {
        val result = ConfigRepository.shouldStopSubscriptionFallback(
            httpStatusCode = 429
        )

        assertTrue(result)
    }

    @Test
    fun testShouldNotStopSubscriptionFallbackForOrdinaryParseFailureOrOtherHttpErrors() {
        val parseFailureResult = ConfigRepository.shouldStopSubscriptionFallback()
        val serverErrorResult = ConfigRepository.shouldStopSubscriptionFallback(httpStatusCode = 503)

        assertFalse(parseFailureResult)
        assertFalse(serverErrorResult)
    }

    @Test
    fun testBuildBootstrapDnsRulesOnlyTargetsResolverDomains() {
        val rules = ConfigRepository.buildBootstrapDnsRules(
            serverAddresses = listOf(
                "https://dns.google/dns-query",
                "https://dns.alidns.com/dns-query",
                "https://1.1.1.1/dns-query",
                "119.29.29.29",
                "local"
            ),
            bootstrapV4Tag = "dns-bootstrap-v4",
            bootstrapV6Tag = "dns-bootstrap-v6",
            bootstrapTag = "dns-bootstrap"
        )

        assertEquals(3, rules.size)
        assertEquals(listOf("dns.google", "dns.alidns.com"), rules[0].domain)
        assertEquals(listOf("A"), rules[0].queryType)
        assertEquals("dns-bootstrap-v4", rules[0].server)
        assertNull(rules[0].outboundRaw)

        assertEquals(listOf("dns.google", "dns.alidns.com"), rules[1].domain)
        assertEquals(listOf("AAAA"), rules[1].queryType)
        assertEquals("dns-bootstrap-v6", rules[1].server)
        assertNull(rules[1].outboundRaw)

        assertEquals(listOf("dns.google", "dns.alidns.com"), rules[2].domain)
        assertEquals("dns-bootstrap", rules[2].server)
        assertNull(rules[2].outboundRaw)
    }

    @Test
    fun testBuildBootstrapDnsRulesSkipsIpAndLocalAddresses() {
        val rules = ConfigRepository.buildBootstrapDnsRules(
            serverAddresses = listOf(
                "local",
                "223.5.5.5",
                "https://1.1.1.1/dns-query",
                "https://[2606:4700:4700::1111]/dns-query"
            ),
            bootstrapV4Tag = "dns-bootstrap-v4",
            bootstrapV6Tag = "dns-bootstrap-v6",
            bootstrapTag = "dns-bootstrap"
        )

        assertTrue(rules.isEmpty())
    }

    @Test
    fun testBuildBootstrapDnsRulesStripsPortFromBareHostAddress() {
        val rules = ConfigRepository.buildBootstrapDnsRules(
            serverAddresses = listOf(
                "dns.google:853",
                "dns.alidns.com:443"
            ),
            bootstrapV4Tag = "dns-bootstrap-v4",
            bootstrapV6Tag = "dns-bootstrap-v6",
            bootstrapTag = "dns-bootstrap"
        )

        assertEquals(3, rules.size)
        assertEquals(listOf("dns.google", "dns.alidns.com"), rules[0].domain)
        assertEquals(listOf("dns.google", "dns.alidns.com"), rules[1].domain)
        assertEquals(listOf("dns.google", "dns.alidns.com"), rules[2].domain)
    }

    @Test
    fun testNormalizeLocalDnsReplacesLegacyLocalValue() {
        val normalized = ConfigRepository.normalizeLocalDns(AppSettings.LEGACY_LOCAL_DNS)

        assertEquals(AppSettings.DEFAULT_LOCAL_DNS, normalized)
    }

    @Test
    fun testNormalizeLocalDnsReplacesBlankValue() {
        val normalized = ConfigRepository.normalizeLocalDns("   ")

        assertEquals(AppSettings.DEFAULT_LOCAL_DNS, normalized)
    }

    @Test
    fun testNormalizeLocalDnsKeepsNumericAddress() {
        val normalized = ConfigRepository.normalizeLocalDns(" 223.5.5.5 ")

        assertEquals("223.5.5.5", normalized)
    }

    @Test
    fun testNormalizeLocalDnsRejectsBareDomainAddress() {
        val normalized = ConfigRepository.normalizeLocalDns("dns.example.com")

        assertEquals(AppSettings.DEFAULT_LOCAL_DNS, normalized)
    }

    @Test
    fun testNormalizeRemoteDnsReplacesBlankValue() {
        val normalized = ConfigRepository.normalizeRemoteDns("   ")

        assertEquals("https://dns.google/dns-query", normalized)
    }

    @Test
    fun testNormalizeRemoteDnsRewritesCloudflareIpDohToDomain() {
        val normalized = ConfigRepository.normalizeRemoteDns("https://1.1.1.1/dns-query")

        assertEquals("https://cloudflare-dns.com/dns-query", normalized)
    }

    @Test
    fun testNormalizeRemoteDnsRewritesCloudflareIpv6DohToDomain() {
        val normalized = ConfigRepository.normalizeRemoteDns("https://[2606:4700:4700::1111]/dns-query")

        assertEquals("https://cloudflare-dns.com/dns-query", normalized)
    }

    @Test
    fun testNormalizeRemoteDnsKeepsNonCloudflareIpDoh() {
        val normalized = ConfigRepository.normalizeRemoteDns("https://8.8.8.8/dns-query")

        assertEquals("https://8.8.8.8/dns-query", normalized)
    }

    @Test
    fun testBuildDnsResolverForDomainUrlReturnsBootstrapResolver() {
        val resolver = ConfigRepository.buildDnsResolverForAddress("https://dns.alidns.com/dns-query")

        assertNotNull(resolver)
        assertEquals("dns-bootstrap", resolver?.server)
    }

    @Test
    fun testBuildDnsResolverForIpUrlReturnsNull() {
        val resolver = ConfigRepository.buildDnsResolverForAddress("https://1.1.1.1/dns-query")

        assertNull(resolver)
    }

    @Test
    fun testBuildDnsResolverForLocalValueReturnsNull() {
        val resolver = ConfigRepository.buildDnsResolverForAddress("local")

        assertNull(resolver)
    }

    @Test
    fun testSubscriptionManagerPreservesTlsCertificateFromYamlImport() {
        val certificatePem = "-----BEGIN CERTIFICATE-----\nMIIBYAMLTEST\n-----END CERTIFICATE-----"
        val yaml = """
            proxies:
              - name: "yaml-anytls-cert"
                type: anytls
                server: anytls.example.com
                port: 443
                password: test-pass
                cert: |
                  -----BEGIN CERTIFICATE-----
                  MIIBYAMLTEST
                  -----END CERTIFICATE-----
        """.trimIndent()

        val config = subscriptionManager.parse(yaml)
        val anytls = config?.outbounds?.find { it.tag == "yaml-anytls-cert" }
        assertNotNull(anytls)
        assertEquals(certificatePem, anytls?.tls?.certificate?.trim())
    }

    @Test
    fun testSubscriptionManagerDoesNotTreatTlsCertificateAsNodeLink() {
        val yaml = """
            proxies:
              - name: "user-info-cert"
                type: anytls
                server: anytls.example.com
                port: 443
                password: test-pass
                cert: |
                  -----BEGIN CERTIFICATE-----
                  MIIBNOTUSERINFO
                  -----END CERTIFICATE-----
        """.trimIndent()

        val config = subscriptionManager.parse(yaml)

        assertNotNull(config?.outbounds?.find { it.tag == "user-info-cert" }?.tls?.certificate)
        assertEquals(1, config?.outbounds?.size)
    }

    @Test
    fun testSubscriptionManagerPreservesJsonTlsCertificateFields() {
        val certificatePem = "-----BEGIN CERTIFICATE-----\nMIIBJSONCERT\n-----END CERTIFICATE-----"
        val caPem = "-----BEGIN CERTIFICATE-----\nMIIBJSONCA\n-----END CERTIFICATE-----"
        val keyPem = "-----BEGIN PRIVATE KEY-----\nMIIBJSONKEY\n-----END PRIVATE KEY-----"
        val json = """
            {
              "outbounds": [
                {
                  "type": "anytls",
                  "tag": "json-anytls-cert",
                  "server": "json.example.com",
                  "server_port": 443,
                  "password": "test-pass",
                  "tls": {
                    "enabled": true,
                    "server_name": "edge.example.com",
                    "certificate": "-----BEGIN CERTIFICATE-----\nMIIBJSONCERT\n-----END CERTIFICATE-----",
                    "ca": "-----BEGIN CERTIFICATE-----\nMIIBJSONCA\n-----END CERTIFICATE-----",
                    "key": "-----BEGIN PRIVATE KEY-----\nMIIBJSONKEY\n-----END PRIVATE KEY-----"
                  }
                }
              ]
            }
        """.trimIndent()

        val config = subscriptionManager.parse(json)

        val anytls = config?.outbounds?.find { it.tag == "json-anytls-cert" }
        assertNotNull(anytls)
        assertEquals(certificatePem, anytls?.tls?.certificate)
        assertEquals(caPem, anytls?.tls?.ca)
        assertEquals(keyPem, anytls?.tls?.key)
    }

    @Test
    fun testSubscriptionManagerParsesYamlImportWithMultipleNodes() {
        val yaml = """
            proxies:
              - name: "hk-regression"
                type: ss
                server: hk.example.com
                port: 443
                cipher: aes-128-gcm
                password: pass-a
              - name: "us-regression"
                type: trojan
                server: us.example.com
                port: 443
                password: pass-b
        """.trimIndent()

        val config = subscriptionManager.parse(yaml)

        assertNotNull(config)
        assertEquals(2, config?.outbounds?.size)
        assertEquals(listOf("hk-regression", "us-regression"), config?.outbounds?.map { it.tag })
    }

    @Test
    fun testBuildUdpDnsServerFromNumericAddressUsesPort53() {
        val server = ConfigRepository.buildDnsServer(
            address = "223.5.5.5",
            tag = "local"
        )

        assertEquals("local", server.tag)
        assertEquals("udp", server.type)
        assertEquals("223.5.5.5", server.server)
        assertEquals(53, server.serverPort)
        assertNull(server.domainResolver)
    }

    @Test
    fun testBuildDnsServerPreservesDomainResolverInJson() {
        val server = ConfigRepository.buildDnsServer(
            address = "https://dns.alidns.com/dns-query",
            tag = "local",
            domainStrategy = "prefer_ipv4",
            domainResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals("local", server.tag)
        assertEquals("https", server.type)
        assertEquals("dns.alidns.com", server.server)
        assertEquals("/dns-query", server.path)
        assertNotNull(server.domainResolver)
        assertEquals("dns-bootstrap", server.domainResolver?.server)

        val json = Gson().toJson(server)
        assertTrue(json.contains("\"domain_resolver\""))
        assertTrue(json.contains("\"server\":\"dns-bootstrap\""))
    }

    @Test
    fun testBuildDynamicDnsServersDeduplicatesSameDetour() {
        val servers = ConfigRepository.buildDynamicDnsServersForTest(
            semantics = listOf(
                ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
                ConfigRepository.OutboundSemantic.RouteTag("P:HK")
            ),
            remoteDnsAddr = "https://dns.google/dns-query",
            remoteStrategy = "prefer_ipv4",
            remoteResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals(1, servers.size)
        assertEquals("P:HK", servers.first().detour)
    }

    @Test
    fun testBuildDynamicDnsServersIncludesDifferentDetours() {
        val servers = ConfigRepository.buildDynamicDnsServersForTest(
            semantics = listOf(
                ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
                ConfigRepository.OutboundSemantic.RouteTag("node-tag-1")
            ),
            remoteDnsAddr = "https://dns.google/dns-query",
            remoteStrategy = "prefer_ipv4",
            remoteResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals(2, servers.size)
        assertTrue(servers.any { it.detour == "P:HK" })
        assertTrue(servers.any { it.detour == "node-tag-1" })
    }

    @Test
    fun testBuildDynamicDnsServerTagIsStableForSameDetour() {
        val tag1 = ConfigRepository.buildDynamicDnsServerTag("P:HK")
        val tag2 = ConfigRepository.buildDynamicDnsServerTag("P:HK")

        assertEquals(tag1, tag2)
        assertTrue(tag1.startsWith("dns-remote-"))
    }

    @Test
    fun testBuildDynamicDnsServerTagDiffersForDifferentDetours() {
        val tag1 = ConfigRepository.buildDynamicDnsServerTag("P:HK")
        val tag2 = ConfigRepository.buildDynamicDnsServerTag("P/HK")

        assertNotEquals(tag1, tag2)
    }

    @Test
    fun testBuildDynamicDnsServerUsesGivenDetour() {
        val server = ConfigRepository.buildDynamicRemoteDnsServerForTest(
            detourTag = "P:HK",
            remoteDnsAddr = "https://dns.google/dns-query",
            remoteStrategy = "prefer_ipv4",
            remoteResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals("P:HK", server.detour)
        assertEquals("https", server.type)
        assertEquals("dns.google", server.server)
        assertEquals("dns-bootstrap", server.domainResolver?.server)
    }

    @Test
    fun testBuildDynamicDnsServersUsesRemoteDnsWithDetourForEchRouteTag() {
        val servers = ConfigRepository.buildDynamicDnsServersForTest(
            semantics = listOf(ConfigRepository.OutboundSemantic.RouteTag("ECH Node")),
            remoteDnsAddr = "https://1.1.1.1/dns-query",
            remoteStrategy = "prefer_ipv4",
            remoteResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals(1, servers.size)
        assertEquals("1.1.1.1", servers.first().server)
        assertEquals("dns-bootstrap", servers.first().domainResolver?.server)
        assertEquals("ECH Node", servers.first().detour)
    }

    @Test
    fun testBuildDynamicRemoteDnsServerForProxyDetourCarriesDetour() {
        val server = ConfigRepository.buildDynamicRemoteDnsServerForTest(
            detourTag = "PROXY",
            remoteDnsAddr = "https://1.1.1.1/dns-query",
            remoteStrategy = "prefer_ipv4",
            remoteResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals("PROXY", server.detour)
        assertEquals("https", server.type)
        assertEquals("1.1.1.1", server.server)
        assertEquals("/dns-query", server.path)
    }

    @Test
    fun testBuildDynamicRemoteDnsServerKeepsRemoteDnsForEchDetour() {
        val server = ConfigRepository.buildDynamicRemoteDnsServerForTest(
            detourTag = "ECH Node",
            remoteDnsAddr = "https://1.1.1.1/dns-query",
            remoteStrategy = "prefer_ipv4",
            remoteResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("ECH Node"), server.tag)
        assertEquals("https", server.type)
        assertEquals("1.1.1.1", server.server)
        assertEquals("/dns-query", server.path)
        assertEquals("dns-bootstrap", server.domainResolver?.server)
        assertEquals("ECH Node", server.detour)
    }

    @Test
    fun testBuildFakeIpDnsServerForTestIncludesRangesForFakeIpTransport() {
        val server = ConfigRepository.buildFakeIpDnsServerForTest("198.18.0.0/15")

        assertEquals("fakeip-dns", server.tag)
        assertEquals("fakeip", server.type)
        assertEquals("198.18.0.0/15", server.inet4Range)
        assertEquals("fc00::/18", server.inet6Range)
    }

    @Test
    fun testBuildFakeIpDnsServerForTestPreservesCustomIpv4AndIpv6Ranges() {
        val server = ConfigRepository.buildFakeIpDnsServerForTest("198.18.0.0/15,fd00::/16")

        assertEquals("198.18.0.0/15", server.inet4Range)
        assertEquals("fd00::/16", server.inet6Range)
    }

    @Test
    fun testDnsServerTagForRouteTagUsesDynamicServerWhenFakeDnsDisabled() {
        val serverTag = ConfigRepository.dnsServerTagForSemanticForTest(
            semantic = ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
            fakeDnsEnabled = false
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("P:HK"), serverTag)
    }

    @Test
    fun testDnsServerTagForRouteTagUsesDynamicServerWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.dnsServerTagForSemanticForTest(
            semantic = ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
            fakeDnsEnabled = true
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("P:HK"), serverTag)
    }

    @Test
    fun testDnsServerTagForProxyUsesProxyServerWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.resolveDnsServerTagForRuleSemanticForTest(
            semantic = ConfigRepository.OutboundSemantic.Proxy,
            fakeDnsEnabled = true,
            proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("PROXY")
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("PROXY"), serverTag)
    }

    @Test
    fun testDnsRouteToProxyUsesProxyDnsForIpQueriesWhenFakeDnsEnabled() {
        val proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("PROXY")
        val rules = ConfigRepository.buildDnsRouteToProxyForTest(
            fakeDnsEnabled = true,
            proxyServerTag = proxyServerTag,
            rule = com.kunk.singbox.model.DnsRule(ruleSet = listOf("geosite-google"))
        )

        assertEquals(1, rules.size)
        assertEquals(proxyServerTag, rules[0].server)
        assertEquals(listOf("A", "AAAA"), rules[0].queryType)
    }

    @Test
    fun testDnsRouteToProxyReturnsProxyDnsRuleWhenFakeDnsEnabled() {
        val proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("PROXY")
        val rules = ConfigRepository.buildDnsRouteToProxyForTest(
            fakeDnsEnabled = true,
            proxyServerTag = proxyServerTag,
            rule = com.kunk.singbox.model.DnsRule(ruleSet = listOf("geosite-geolocation-!cn"))
        )

        assertEquals(1, rules.size)
        assertEquals(proxyServerTag, rules[0].server)
        assertEquals(listOf("A", "AAAA"), rules[0].queryType)
    }

    @Test
    fun testDnsRouteToNonDirectReturnsSpecificDnsRuleWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.buildDynamicDnsServerTag("SG|官方优选|94ms_2")
        val rules = ConfigRepository.buildDnsRouteToNonDirectForTest(
            fakeDnsEnabled = true,
            serverTag = serverTag,
            rule = com.kunk.singbox.model.DnsRule(ruleSet = listOf("geosite-geolocation-!cn"))
        )

        assertEquals(1, rules.size)
        assertEquals(serverTag, rules[0].server)
        assertEquals(listOf("A", "AAAA"), rules[0].queryType)
    }

    @Test
    fun testNonIpDnsFallbackRoutesHttpsAndSvcbToProxyDns() {
        val rule = ConfigRepository.buildNonIpDnsFallbackRuleForTest(
            ConfigRepository.buildDynamicDnsServerTag("PROXY")
        )

        assertEquals("route", rule.action)
        assertEquals(listOf("HTTPS", "SVCB"), rule.queryType)
        assertEquals(ConfigRepository.buildDynamicDnsServerTag("PROXY"), rule.server)
    }

    @Test
    fun testDnsRouteToDirectOnlyRoutesIpQueriesToLocalDns() {
        val rule = ConfigRepository.buildDnsRouteToDirectForTest(
            com.kunk.singbox.model.DnsRule(ruleSet = listOf("geosite-cn"))
        )

        assertEquals("route", rule.action)
        assertEquals("local", rule.server)
        assertEquals(listOf("A", "AAAA"), rule.queryType)
    }

    @Test
    fun testNormalizeRuleSetUrlAddsRawPrefixForGithubPathOnlyUrl() {
        val normalized = RuleSetRepository.normalizeRuleSetUrl(
            url = "SagerNet/sing-geosite/rule-set/geosite-google.srs",
            mirrorUrl = "https://raw.githubusercontent.com/"
        )

        assertEquals(
            "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-google.srs",
            normalized
        )
    }

    @Test
    fun testNormalizeRuleSetUrlAddsRawPrefixForLeadingSlashGithubPathOnlyUrl() {
        val normalized = RuleSetRepository.normalizeRuleSetUrl(
            url = "/SagerNet/sing-geosite/rule-set/geosite-google.srs",
            mirrorUrl = "https://raw.githubusercontent.com/"
        )

        assertEquals(
            "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-google.srs",
            normalized
        )
    }

    @Test
    fun testNormalizeRuleSetForSaveNormalizesRemoteRuleSetUrl() {
        val normalized = RuleSetRepository.normalizeRuleSetForSave(
            ruleSet = RuleSet(
                tag = "geosite-google",
                type = RuleSetType.REMOTE,
                url = "SagerNet/sing-geosite/rule-set/geosite-google.srs"
            ),
            mirrorUrl = "https://raw.githubusercontent.com/"
        )

        assertEquals(
            "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-google.srs",
            normalized.url
        )
    }

    @Test
    fun testRuleSetDnsPriorityKeepsProxySpecificRulesBeforeDirectCountryRules() {
        val sortedRuleSets = ConfigRepository.sortRuleSetsForDnsAndRoutePriorityForTest(
            listOf(
                RuleSet(
                    tag = "geosite-cn",
                    type = RuleSetType.REMOTE,
                    outboundMode = RuleSetOutboundMode.DIRECT
                ),
                RuleSet(
                    tag = "geosite-google",
                    type = RuleSetType.REMOTE,
                    outboundMode = RuleSetOutboundMode.PROXY
                ),
                RuleSet(
                    tag = "geosite-geolocation-!cn",
                    type = RuleSetType.REMOTE,
                    outboundMode = RuleSetOutboundMode.PROXY
                )
            )
        )

        assertEquals(
            listOf("geosite-google", "geosite-cn", "geosite-geolocation-!cn"),
            sortedRuleSets.map { it.tag }
        )
    }

    @Test
    fun testDnsServerTagForFallbackProxyUsesProxyServer() {
        val serverTag = ConfigRepository.dnsServerTagForSemanticForTest(
            semantic = ConfigRepository.OutboundSemantic.FallbackProxy("PROXY"),
            fakeDnsEnabled = false
        )

        assertEquals("remote", serverTag)
    }

    @Test
    fun testDnsServerTagForFallbackProxyUsesDynamicServerWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.dnsServerTagForSemanticForTest(
            semantic = ConfigRepository.OutboundSemantic.FallbackProxy("PROXY"),
            fakeDnsEnabled = true,
            proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("PROXY")
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("PROXY"), serverTag)
    }

    @Test
    fun testDnsServerTagForFakeIpExcludeDomainUsesDynamicServerWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.dnsServerTagForSemanticForTest(
            semantic = ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
            fakeDnsEnabled = true
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("P:HK"), serverTag)
    }

    @Test
    fun testResolveRouteModeForRuleSetUsesProxyDefault() {
        val resolved = ConfigRepository.resolveRouteModeForRuleSetForTest(
            RuleSet(
                tag = "geo-test",
                type = RuleSetType.LOCAL,
                path = "/tmp/geo.srs",
                outboundMode = null
            )
        )

        assertEquals(RuleSetOutboundMode.PROXY, resolved)
    }

    @Test
    fun testResolveRouteModeForAppGroupUsesDirectDefault() {
        val resolved = ConfigRepository.resolveRouteModeForAppGroupForTest(
            AppGroup(name = "group", outboundMode = null)
        )

        assertEquals(RuleSetOutboundMode.DIRECT, resolved)
    }

    @Test
    fun testResolveRouteModeForCustomRuleUsesLegacyOutboundDefault() {
        val resolved = ConfigRepository.resolveRouteModeForCustomRuleForTest(
            CustomRule(
                name = "rule",
                type = RuleType.DOMAIN,
                value = "example.com",
                outbound = OutboundTag.BLOCK,
                outboundMode = null
            )
        )

        assertEquals(RuleSetOutboundMode.BLOCK, resolved)
    }

    @Test
    fun testResolveOutboundSemanticDirect() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepository.Companion.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.DIRECT,
                value = null,
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.Direct, semantic)
    }

    @Test
    fun testResolveOutboundSemanticBlock() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepository.Companion.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.BLOCK,
                value = null,
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.Block, semantic)
    }

    @Test
    fun testResolveOutboundSemanticProxy() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepository.Companion.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.PROXY,
                value = null,
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.Proxy, semantic)
    }

    @Test
    fun testResolveOutboundSemanticNodeValid() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepository.Companion.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.NODE,
                value = "node-id-1",
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { id -> if (id == "node-id-1") "node-tag-1" else null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.RouteTag("node-tag-1"), semantic)
    }

    @Test
    fun testResolveOutboundSemanticNodeInvalid() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepository.Companion.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.NODE,
                value = "missing-node",
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.FallbackProxy("PROXY"), semantic)
    }

    @Test
    fun testResolveOutboundSemanticProfileValid() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepository.Companion.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.PROFILE,
                value = "profile-1",
                selectorTag = "PROXY",
                outbounds = listOf(com.kunk.singbox.model.Outbound(tag = "P:HK", type = "selector")),
                profiles = listOf(
                    com.kunk.singbox.database.entity.ProfileEntity(
                        id = "profile-1",
                        name = "HK",
                        type = com.kunk.singbox.model.ProfileType.Subscription,
                        url = "",
                        lastUpdated = 0L,
                        enabled = true
                    )
                ),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.RouteTag("P:HK"), semantic)
    }

    @Test
    fun testResolveOutboundSemanticProfileInvalid() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepository.Companion.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.PROFILE,
                value = "missing-profile",
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.FallbackProxy("PROXY"), semantic)
    }

    @Test
    fun testResolveProfileSelectorDefaultPrefersLowestLatencyOverRememberedNode() {
        val defaultTag = ConfigRepository.resolveProfileSelectorDefault(
            nodeIds = listOf("node-1", "node-2", "node-3"),
            nodeTagMap = mapOf(
                "node-1" to "tag-a",
                "node-2" to "tag-b",
                "node-3" to "tag-c"
            ),
            rememberedNodeId = "node-2",
            savedNodeLatencies = mapOf(
                "node-1" to 20L,
                "node-2" to 10L,
                "node-3" to 5L
            )
        )

        assertEquals("tag-c", defaultTag)
    }

    @Test
    fun testResolveProfileSelectorDefaultIgnoresRememberedNodeOutsideCurrentProfile() {
        val defaultTag = ConfigRepository.resolveProfileSelectorDefault(
            nodeIds = listOf("node-1", "node-2"),
            nodeTagMap = mapOf(
                "node-1" to "tag-a",
                "node-2" to "tag-b",
                "node-3" to "tag-c"
            ),
            rememberedNodeId = "node-3",
            savedNodeLatencies = mapOf(
                "node-1" to 80L,
                "node-2" to 30L,
                "node-3" to 5L
            )
        )

        assertEquals("tag-b", defaultTag)
    }

    @Test
    fun testResolveProfileSelectorDefaultFallsBackToRememberedNodeWhenLatencyUnavailable() {
        val defaultTag = ConfigRepository.resolveProfileSelectorDefault(
            nodeIds = listOf("node-1", "node-2", "node-3"),
            nodeTagMap = mapOf(
                "node-1" to "tag-a",
                "node-2" to "tag-b",
                "node-3" to "tag-c"
            ),
            rememberedNodeId = "node-2",
            savedNodeLatencies = mapOf(
                "node-1" to 0L,
                "node-3" to -1L
            )
        )

        assertEquals("tag-b", defaultTag)
    }

    @Test
    fun testResolveProfileSelectorDefaultUsesLowestPositiveLatency() {
        val defaultTag = ConfigRepository.resolveProfileSelectorDefault(
            nodeIds = listOf("node-1", "node-2", "node-3"),
            nodeTagMap = mapOf(
                "node-1" to "tag-a",
                "node-2" to "tag-b",
                "node-3" to "tag-c"
            ),
            rememberedNodeId = null,
            savedNodeLatencies = mapOf(
                "node-1" to 120L,
                "node-2" to 45L,
                "node-3" to 60L
            )
        )

        assertEquals("tag-b", defaultTag)
    }

    @Test
    fun testResolveProfileSelectorDefaultFallsBackToFirstTag() {
        val defaultTag = ConfigRepository.resolveProfileSelectorDefault(
            nodeIds = listOf("node-1", "node-2"),
            nodeTagMap = mapOf(
                "node-1" to "tag-a",
                "node-2" to "tag-b"
            ),
            rememberedNodeId = null,
            savedNodeLatencies = mapOf(
                "node-1" to 0L,
                "node-2" to -1L
            )
        )

        assertEquals("tag-a", defaultTag)
    }

    @Test
    fun testBuildProfileRouteGroupOutboundsCreatesNestedAutoStructure() {
        val outbounds = ConfigRepository.buildProfileRouteGroupOutboundsForTest(
            groupTag = "P:HK",
            nodeTags = listOf("node-a", "node-b")
        )

        assertEquals(2, outbounds.size)

        val autoGroup = outbounds[0]
        assertEquals("urltest", autoGroup.type)
        assertEquals("P:HK#AUTO", autoGroup.tag)
        assertEquals(listOf("node-a", "node-b"), autoGroup.outbounds)
        assertNull(autoGroup.default)
        assertEquals("https://www.gstatic.com/generate_204", autoGroup.url)
        assertEquals("10m", autoGroup.interval)
        assertEquals(50, autoGroup.tolerance)

        val outerGroup = outbounds[1]
        assertEquals("selector", outerGroup.type)
        assertEquals("P:HK", outerGroup.tag)
        assertEquals(listOf("P:HK#AUTO", "PROXY"), outerGroup.outbounds)
        assertEquals("P:HK#AUTO", outerGroup.default)
    }

    @Test
    fun testApplySelectorSafeOutboundsKeepsUrlTestDefaultNull() {
        val safeOutbounds = ConfigRepository.applySelectorSafeOutboundsForTest(
            listOf(
                Outbound(
                    type = "urltest",
                    tag = "P:HK#AUTO",
                    outbounds = listOf("node-a", "missing-node"),
                    default = "node-a"
                ),
                Outbound(type = "direct", tag = "direct"),
                Outbound(type = "shadowsocks", tag = "node-a"),
                Outbound(
                    type = "selector",
                    tag = "P:HK",
                    outbounds = listOf("P:HK#AUTO", "missing-selector-ref"),
                    default = "missing-selector-ref"
                )
            )
        )

        val autoGroup = safeOutbounds.first { it.tag == "P:HK#AUTO" }
        assertEquals(listOf("node-a"), autoGroup.outbounds)
        assertNull(autoGroup.default)

        val selectorGroup = safeOutbounds.first { it.tag == "P:HK" }
        assertEquals(listOf("P:HK#AUTO"), selectorGroup.outbounds)
        assertEquals("P:HK#AUTO", selectorGroup.default)
    }

    @Test
    fun testBuildAppRoutingRulesUsesSemanticRejectForBlockRule() {
        val routeRule = ConfigRepository.toRouteRuleForTest(
            ConfigRepository.OutboundSemantic.Block,
            "PROXY"
        )

        assertEquals("reject", routeRule.action)
        assertNull(routeRule.outbound)
    }

    @Test
    fun testResolveDnsStrategyClampsIpv4OnlyMode() {
        assertEquals(
            "ipv4_only",
            ConfigRepository.resolveDnsStrategyForTest(DnsStrategy.AUTO, IpVersionMode.IPV4_ONLY)
        )
        assertEquals(
            "ipv4_only",
            ConfigRepository.resolveDnsStrategyForTest(DnsStrategy.PREFER_IPV6, IpVersionMode.IPV4_ONLY)
        )
        assertEquals(
            "ipv4_only",
            ConfigRepository.resolveDnsStrategyForTest(DnsStrategy.ONLY_IPV6, IpVersionMode.IPV4_ONLY)
        )
    }

    @Test
    fun testResolveDnsStrategyClampsIpv6OnlyMode() {
        assertEquals(
            "ipv6_only",
            ConfigRepository.resolveDnsStrategyForTest(DnsStrategy.AUTO, IpVersionMode.IPV6_ONLY)
        )
        assertEquals(
            "ipv6_only",
            ConfigRepository.resolveDnsStrategyForTest(DnsStrategy.PREFER_IPV4, IpVersionMode.IPV6_ONLY)
        )
        assertEquals(
            "ipv6_only",
            ConfigRepository.resolveDnsStrategyForTest(DnsStrategy.ONLY_IPV4, IpVersionMode.IPV6_ONLY)
        )
    }

    @Test
    fun testResolveDnsStrategyPrefersIpv6InPreferMode() {
        assertEquals(
            "prefer_ipv6",
            ConfigRepository.resolveDnsStrategyForTest(DnsStrategy.AUTO, IpVersionMode.PREFER_IPV6)
        )
        assertEquals(
            "prefer_ipv4",
            ConfigRepository.resolveDnsStrategyForTest(DnsStrategy.PREFER_IPV4, IpVersionMode.PREFER_IPV6)
        )
    }

    @Test
    fun testBuildQuicBlockRuleReturnsEmptyWhenBlockQuicDisabled() {
        val rules = ConfigRepository.buildQuicBlockRuleForTest(AppSettings(blockQuic = false))

        assertTrue(rules.isEmpty())
    }

    @Test
    fun testBuildQuicBlockRuleOnlyRejectsSniffedQuicWhenBlockQuicEnabled() {
        val rules = ConfigRepository.buildQuicBlockRuleForTest(AppSettings(blockQuic = true))

        assertEquals(1, rules.size)
        assertTrue(rules.any { it.protocol?.contains("quic") == true })
        assertFalse(rules.any { it.network?.contains("udp") == true && it.port == listOf(443) })
    }

    @Test
    fun testBuildTunFakeIpDnsRuleReturnsEmptyWhenFakeDnsDisabled() {
        val rules = ConfigRepository.buildTunFakeIpDnsRulesForTest(false)

        assertTrue(rules.isEmpty())
    }

    @Test
    fun testBuildTunFakeIpDnsRuleRoutesTunAaaaAndAWhenFakeDnsEnabled() {
        val rules = ConfigRepository.buildTunFakeIpDnsRulesForTest(true)

        assertEquals(1, rules.size)
        assertEquals(listOf("A", "AAAA"), rules.first().queryType)
        assertEquals(listOf("tun-in"), rules.first().inbound)
        assertEquals("route", rules.first().action)
        assertEquals("fakeip-dns", rules.first().server)
    }

    @Test
    fun testBuildEchDnsRulesRoutesHttpsQueryServerNameToGivenDnsServer() {
        val rules = ConfigRepository.buildEchDnsRulesForTest(
            outbounds = listOf(
                Outbound(
                    type = "vless",
                    tag = "cf-node-a",
                    tls = TlsConfig(ech = EchConfig(enabled = true, queryServerName = "cloudflare-ech.com"))
                ),
                Outbound(
                    type = "vless",
                    tag = "cf-node-b",
                    tls = TlsConfig(ech = EchConfig(enabled = true, queryServerName = "cloudflare-ech.com"))
                )
            ),
            serverTag = ConfigRepository.buildDynamicDnsServerTag("cf-node-a")
        )

        assertEquals(1, rules.size)
        assertEquals("route", rules.first().action)
        assertEquals(listOf("cloudflare-ech.com"), rules.first().domain)
        assertEquals(listOf("HTTPS", "SVCB"), rules.first().queryType)
        assertEquals(ConfigRepository.buildDynamicDnsServerTag("cf-node-a"), rules.first().server)
    }

    @Test
    fun testResolveActiveEchDnsServerRequiresActiveNode() {
        val outbounds = listOf(
            Outbound(
                type = "vless",
                tag = "plain-node"
            ),
            Outbound(
                type = "vless",
                tag = "ech-node",
                tls = TlsConfig(
                    ech = EchConfig(
                        enabled = true,
                        queryServerName = "cloudflare-ech.com",
                        dnsServer = "https://dns.alidns.com/dns-query"
                    )
                )
            )
        )

        assertNull(ConfigRepository.resolveActiveEchDnsServerForTest("plain-node", outbounds))
        assertEquals(
            "https://dns.alidns.com/dns-query",
            ConfigRepository.resolveActiveEchDnsServerForTest("ech-node", outbounds)
        )
    }

    @Test
    fun testResolveActiveEchDnsServerFallsBackToUniqueEchResolver() {
        val outbounds = listOf(
            Outbound(
                type = "vless",
                tag = "active-ech-node",
                tls = TlsConfig(
                    ech = EchConfig(
                        enabled = true,
                        queryServerName = "cloudflare-ech.com"
                    )
                )
            ),
            Outbound(
                type = "vless",
                tag = "sibling-ech-node",
                tls = TlsConfig(
                    ech = EchConfig(
                        enabled = true,
                        queryServerName = "cloudflare-ech.com",
                        dnsServer = "https://dns.alidns.com/dns-query"
                    )
                )
            )
        )

        assertEquals(
            "https://dns.alidns.com/dns-query",
            ConfigRepository.resolveActiveEchDnsServerForTest("active-ech-node", outbounds)
        )
    }

    @Test
    fun testNeedsLegacyEchDnsRepairWhenResolverMetadataMissing() {
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(
                    type = "vless",
                    tag = "legacy-ech-node",
                    tls = TlsConfig(
                        ech = EchConfig(
                            enabled = true,
                            queryServerName = "cloudflare-ech.com"
                        )
                    )
                )
            )
        )

        assertTrue(ConfigRepository.needsLegacyEchDnsRepairForTest(config))
    }

    @Test
    fun testResolveDefaultRouteDomainResolverUsesBootstrapAlways() {
        assertEquals("dns-bootstrap", ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG)
    }

    @Test
    fun testResolveRunDnsFinalServerUsesStableRemoteWhenGlobalProxyAndFakeDnsEnabled() {
        val proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("node-b")
        val finalServer = ConfigRepository.resolveRunDnsFinalServerForTest(
            routingMode = RoutingMode.GLOBAL_PROXY,
            defaultRule = DefaultRule.PROXY,
            fakeDnsEnabled = true,
            proxyServerTag = proxyServerTag
        )

        assertEquals("remote", finalServer)
    }

    @Test
    fun testResolveRunDnsFinalServerUsesProxyDetourWhenRuleProxyAndFakeDnsEnabled() {
        val proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("node-b")
        val finalServer = ConfigRepository.resolveRunDnsFinalServerForTest(
            routingMode = RoutingMode.RULE,
            defaultRule = DefaultRule.PROXY,
            fakeDnsEnabled = true,
            proxyServerTag = proxyServerTag
        )

        assertEquals(proxyServerTag, finalServer)
    }

    @Test
    fun testResolveProxyDnsDetourTagUsesSelectorDefaultConcreteNode() {
        val detourTag = ConfigRepository.resolveProxyDnsDetourTagForTest(
            selectorTag = "PROXY",
            outbounds = listOf(
                Outbound(
                    type = "selector",
                    tag = "PROXY",
                    outbounds = listOf("node-a", "node-b"),
                    default = "node-b"
                ),
                Outbound(type = "vless", tag = "node-a"),
                Outbound(type = "vless", tag = "node-b")
            )
        )

        assertEquals("node-b", detourTag)
    }

    @Test
    fun testResolveProxyDnsDetourTagUnwrapsUrlTestDefault() {
        val detourTag = ConfigRepository.resolveProxyDnsDetourTagForTest(
            selectorTag = "PROXY",
            outbounds = listOf(
                Outbound(
                    type = "selector",
                    tag = "PROXY",
                    outbounds = listOf("P:HK#AUTO"),
                    default = "P:HK#AUTO"
                ),
                Outbound(
                    type = "urltest",
                    tag = "P:HK#AUTO",
                    outbounds = listOf("node-a", "node-b")
                ),
                Outbound(type = "vless", tag = "node-a"),
                Outbound(type = "vless", tag = "node-b")
            )
        )

        assertEquals("node-a", detourTag)
    }

    @Test
    fun testBypassLanRulesUseIpIsPrivate() {
        val rules = ConfigRepository.buildBypassLanRulesForTest(AppSettings(bypassLan = true))

        assertEquals(1, rules.size)
        assertEquals(true, rules.first().ipIsPrivate)
        assertEquals("direct", rules.first().outbound)
    }

    @Test
    fun testHijackDnsRulesCatchTunDnsPortBeforeProtocolSniffing() {
        val rules = ConfigRepository.buildHijackDnsRulesForTest()

        assertEquals(3, rules.size)
        assertEquals(listOf("tun-in"), rules[0].inbound)
        assertEquals(listOf(53), rules[0].port)
        assertEquals("hijack-dns", rules[0].action)
        assertEquals(listOf("dns"), rules[1].protocol)
        assertEquals("hijack-dns", rules[1].action)
        assertEquals(listOf(853), rules[2].port)
        assertEquals("reject", rules[2].action)
    }

    @Test
    fun testRoutingModeGlobalProxyStillBuildsProfileRuleSetRouteRules() {
        val rules = ConfigRepository.buildRunRouteRulesForTest(
            settings = AppSettings(
                routingMode = RoutingMode.GLOBAL_PROXY,
                ruleSets = listOf(
                    RuleSet(
                        tag = "geosite-google",
                        type = RuleSetType.LOCAL,
                        path = "/tmp/geosite-google.srs",
                        outboundMode = RuleSetOutboundMode.PROFILE,
                        outboundValue = "profile-1",
                        enabled = true
                    )
                )
            ),
            selectorTag = "PROXY",
            outbounds = listOf(
                Outbound(type = "selector", tag = "PROXY"),
                Outbound(type = "selector", tag = "P:鹰")
            ),
            profiles = listOf(
                com.kunk.singbox.database.entity.ProfileEntity(
                    id = "profile-1",
                    name = "鹰",
                    type = ProfileType.Subscription,
                    url = "",
                    lastUpdated = 0L,
                    enabled = true
                )
            ),
            validRuleSets = listOf(RuleSetConfig(tag = "geosite-google"))
        )

        val googleRule = rules.firstOrNull { it.ruleSet == listOf("geosite-google") }
        assertNotNull(googleRule)
        assertEquals("P:鹰", googleRule?.outbound)
    }

    @Test
    fun testGlobalProxyDnsFinalUsesRemoteServerWhenFakeDnsEnabled() {
        val finalServer = ConfigRepository.resolveRunDnsFinalServerForTest(
            routingMode = RoutingMode.GLOBAL_PROXY,
            defaultRule = DefaultRule.PROXY,
            fakeDnsEnabled = true,
            proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("selected-node")
        )

        assertEquals("remote", finalServer)
    }

    @Test
    fun testBypassLanRulesDisabledWhenSettingOff() {
        val rules = ConfigRepository.buildBypassLanRulesForTest(AppSettings(bypassLan = false))

        assertTrue(rules.isEmpty())
    }

    @Test
    fun testMulticastRejectRulesCoverIpv4AndIpv6WhenDualStack() {
        val rules = ConfigRepository.buildMulticastRejectRulesForTest(
            AppSettings(ipVersionMode = IpVersionMode.DUAL_STACK)
        )

        assertEquals(listOf("224.0.0.0/3", "ff00::/8"), rules.first().ipCidr)
        assertEquals("reject", rules.first().action)
    }

    @Test
    fun testMulticastRejectRulesFollowIpVersionMode() {
        val ipv4Only = ConfigRepository.buildMulticastRejectRulesForTest(
            AppSettings(ipVersionMode = IpVersionMode.IPV4_ONLY)
        )
        val ipv6Only = ConfigRepository.buildMulticastRejectRulesForTest(
            AppSettings(ipVersionMode = IpVersionMode.IPV6_ONLY)
        )

        assertEquals(listOf("224.0.0.0/3"), ipv4Only.first().ipCidr)
        assertEquals(listOf("ff00::/8"), ipv6Only.first().ipCidr)
    }

    @Test
    fun testAppliedRemoteRuleSetFilterIncludesEnabledRemoteRuleSet() {
        val ruleSet = RuleSet(tag = "remote-enabled", type = RuleSetType.REMOTE, enabled = true)

        val filtered = invokeAppliedRemoteRuleSetFilter(
            ruleSets = listOf(ruleSet),
            validRuleSets = listOf(RuleSetConfig(tag = "remote-enabled"))
        )

        assertEquals(listOf("remote-enabled"), filtered.map { it.tag })
    }

    @Test
    fun testAppliedRemoteRuleSetFilterExcludesDisabledRemoteRuleSet() {
        val ruleSet = RuleSet(tag = "remote-disabled", type = RuleSetType.REMOTE, enabled = false)

        val filtered = invokeAppliedRemoteRuleSetFilter(
            ruleSets = listOf(ruleSet),
            validRuleSets = listOf(RuleSetConfig(tag = "remote-disabled"))
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun testAppliedRemoteRuleSetFilterExcludesRemoteRuleSetOutsideValidTags() {
        val ruleSet = RuleSet(tag = "remote-missing", type = RuleSetType.REMOTE, enabled = true)

        val filtered = invokeAppliedRemoteRuleSetFilter(
            ruleSets = listOf(ruleSet),
            validRuleSets = listOf(RuleSetConfig(tag = "another-tag"))
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun testAppliedRemoteRuleSetFilterExcludesLocalRuleSet() {
        val ruleSet = RuleSet(
            tag = "local-enabled",
            type = RuleSetType.LOCAL,
            path = "/tmp/local-enabled.srs",
            enabled = true
        )

        val filtered = invokeAppliedRemoteRuleSetFilter(
            ruleSets = listOf(ruleSet),
            validRuleSets = listOf(RuleSetConfig(tag = "local-enabled"))
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun testDetectRuleSetRuleTypeIpRules() {
        val tempFile =
            createTempRuleSetFile("""
            1.0.1.0/24
            1.0.2.0/23
            192.168.0.0/16
            10.0.0.0/8
            """.trimIndent())

        val ruleType = ConfigRepository.detectRuleSetRuleTypeForTest(tempFile)
        assertEquals(ConfigRepository.RuleSetRuleType.IP, ruleType)
    }

    @Test
    fun testDetectRuleSetRuleTypeDomainRules() {
        val tempFile =
            createTempRuleSetFile("""
            domain:google.com
            domain:facebook.com
            geosite:youtube
            domain:twitter.com
            """.trimIndent())

        val ruleType = ConfigRepository.detectRuleSetRuleTypeForTest(tempFile)
        assertEquals(ConfigRepository.RuleSetRuleType.DOMAIN, ruleType)
    }

    @Test
    fun testDetectRuleSetRuleTypeMixedRules() {
        val tempFile =
            createTempRuleSetFile("""
            1.0.1.0/24
            domain:google.com
            192.168.0.0/16
            geosite:youtube
            """.trimIndent())

        val ruleType = ConfigRepository.detectRuleSetRuleTypeForTest(tempFile)
        assertEquals(ConfigRepository.RuleSetRuleType.MIXED, ruleType)
    }

    @Test
    fun testDetectRuleSetRuleTypeWithIpCidrPrefix() {
        val tempFile =
            createTempRuleSetFile("""
            ip-cidr:1.0.1.0/24
            ip-cidr:1.0.2.0/23
            geoip:cn
            """.trimIndent())

        val ruleType = ConfigRepository.detectRuleSetRuleTypeForTest(tempFile)
        assertEquals(ConfigRepository.RuleSetRuleType.IP, ruleType)
    }

    @Test
    fun testDetectRuleSetRuleTypeWithDomainPrefix() {
        val tempFile =
            createTempRuleSetFile("""
            domain:google.com
            domain-suffix:facebook.com
            domain-keyword:twitter
            """.trimIndent())

        val ruleType = ConfigRepository.detectRuleSetRuleTypeForTest(tempFile)
        assertEquals(ConfigRepository.RuleSetRuleType.DOMAIN, ruleType)
    }

    @Test
    fun testDetectRuleSetRuleTypeIpv6Rules() {
        val tempFile =
            createTempRuleSetFile("""
            2001:db8::/32
            fe80::/10
            ::1/128
            """.trimIndent())

        val ruleType = ConfigRepository.detectRuleSetRuleTypeForTest(tempFile)
        assertEquals(ConfigRepository.RuleSetRuleType.IP, ruleType)
    }

    @Test
    fun testDetectRuleSetRuleTypeEmptyFile() {
        val tempFile = createTempRuleSetFile("")

        val ruleType = ConfigRepository.detectRuleSetRuleTypeForTest(tempFile)
        assertEquals(ConfigRepository.RuleSetRuleType.UNKNOWN, ruleType)
    }

    @Test
    fun testDetectRuleSetRuleTypeOnlyComments() {
        val tempFile =
            createTempRuleSetFile("""
            # This is a comment
            // Another comment
            ! Yet another
            """.trimIndent())

        val ruleType = ConfigRepository.detectRuleSetRuleTypeForTest(tempFile)
        assertEquals(ConfigRepository.RuleSetRuleType.UNKNOWN, ruleType)
    }

    @Test
    fun testDetectRuleSetRuleTypeUsesGeositeTagForBinaryRuleSet() {
        val tempFile = createTempRuleSetBytes(byteArrayOf(0, 1, 2, 3))

        val ruleType = ConfigRepository.detectRuleSetRuleTypeForTest(tempFile, "geosite-cn")

        assertEquals(ConfigRepository.RuleSetRuleType.DOMAIN, ruleType)
    }

    private fun createTempRuleSetBytes(content: ByteArray): java.io.File {
        val tempFile = java.io.File.createTempFile("ruleset_test_", ".srs")
        tempFile.writeBytes(content)
        tempFile.deleteOnExit()
        return tempFile
    }

    private fun createTempRuleSetFile(content: String): java.io.File {
        val tempFile = java.io.File.createTempFile("ruleset_test_", ".srs")
        tempFile.writeText(content)
        tempFile.deleteOnExit()
        return tempFile
    }

    @Test
    fun testSanitizeInjectedDnsServerForcesDetourOnUdpWithoutDetour() {
        val server = com.kunk.singbox.model.DnsServer(
            tag = "ad-block", type = "udp", server = "8.8.8.8"
        )
        val result = ConfigRepository.sanitizeInjectedDnsServerForTest(
            server = server,
            routingMode = RoutingMode.GLOBAL_PROXY,
            proxyDetourTag = "node-hk"
        )
        assertEquals("node-hk", result.detour)
    }

    @Test
    fun testSanitizeInjectedDnsServerPreservesExistingDetour() {
        val server = com.kunk.singbox.model.DnsServer(
            tag = "custom", type = "https", server = "dns.google", detour = "my-proxy"
        )
        val result = ConfigRepository.sanitizeInjectedDnsServerForTest(
            server = server,
            routingMode = RoutingMode.GLOBAL_PROXY,
            proxyDetourTag = "node-hk"
        )
        assertEquals("my-proxy", result.detour)
    }

    @Test
    fun testSanitizeInjectedDnsServerSkipsFakeip() {
        val server = com.kunk.singbox.model.DnsServer(tag = "fakeip-dns", type = "fakeip")
        val result = ConfigRepository.sanitizeInjectedDnsServerForTest(
            server = server,
            routingMode = RoutingMode.GLOBAL_PROXY,
            proxyDetourTag = "node-hk"
        )
        assertNull(result.detour)
    }

    @Test
    fun testSanitizeInjectedDnsServerSkipsInGlobalDirectMode() {
        val server = com.kunk.singbox.model.DnsServer(
            tag = "leak", type = "udp", server = "1.1.1.1"
        )
        val result = ConfigRepository.sanitizeInjectedDnsServerForTest(
            server = server,
            routingMode = RoutingMode.GLOBAL_DIRECT,
            proxyDetourTag = "node-hk"
        )
        assertNull(result.detour)
    }
}

package com.kunk.singbox.repository

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.CacheFileConfig
import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.ExperimentalConfig
import com.kunk.singbox.model.Inbound
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RouteConfig
import com.kunk.singbox.model.RouteRule
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetConfig
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.BatchUpdateResult
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.model.SubscriptionUpdateStage
import com.kunk.singbox.model.SubscriptionUpdateResult
import com.kunk.singbox.model.UpdateStatus
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

@Suppress("TooManyFunctions")
abstract class ConfigRepositoryTestPart5 : ConfigRepositoryTestPart4() {
    override fun testSanitizeInjectedDnsServerForcesDetourOnUdpWithoutDetour() {
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
    override fun testSanitizeInjectedDnsServerPreservesExistingDetour() {
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
    override fun testSanitizeInjectedDnsServerSkipsFakeip() {
        val server = com.kunk.singbox.model.DnsServer(tag = "fakeip-dns", type = "fakeip")
        val result = ConfigRepository.sanitizeInjectedDnsServerForTest(
            server = server,
            routingMode = RoutingMode.GLOBAL_PROXY,
            proxyDetourTag = "node-hk"
        )
        assertNull(result.detour)
    }

    @Test
    override fun testSanitizeInjectedDnsServerSkipsInGlobalDirectMode() {
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

    protected override fun invokeAppliedRemoteRuleSetFilter(
        ruleSets: List<RuleSet>,
        validRuleSets: List<RuleSetConfig>
    ): List<RuleSet> {
        val validTags = validRuleSets.mapNotNull { it.tag }.toSet()
        return ConfigRepository.filterAppliedRemoteRuleSetsForTest(ruleSets, validTags)
    }

    protected override fun createUpdatingProfile(profileId: String): ProfileUi {
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

    protected override fun bestvmrDnsOverrideJson(): String =
        """
        {
          "dns": {
            "servers": [
              { "tag": "bestvmr-dns", "address": "udp://47.110.75.65:8053" }
            ],
            "rules": [
              {
                "domain_suffix": [".bestvmr.com"],
                "server": "bestvmr-dns",
                "disable_cache": true
              },
              {
                "domain": ["bestvmr.com"],
                "server": "bestvmr-dns",
                "disable_cache": true
              }
            ]
          }
        }
        """.trimIndent()

    protected override fun bestvmrNodeOutbound(): Outbound =
        Outbound(
            type = "vless",
            tag = "airport-node",
            server = "fly-nnca.bestvmr.com"
        )

    protected override fun applyStageForRun(
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
    override fun testStableNodeIdConsistency() {
        val profileId = "profile-123"
        val outboundTag = "node-abc"

        val id1 = ConfigRepository.stableNodeId(profileId, outboundTag)
        val id2 = ConfigRepository.stableNodeId(profileId, outboundTag)

        assertEquals(id1, id2)
        assertTrue(id1.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    override fun testStableNodeIdDifferentInputs() {
        val id1 = ConfigRepository.stableNodeId("profile-1", "node-a")
        val id2 = ConfigRepository.stableNodeId("profile-1", "node-b")
        val id3 = ConfigRepository.stableNodeId("profile-2", "node-a")

        assertNotEquals(id1, id2)
        assertNotEquals(id1, id3)
        assertNotEquals(id2, id3)
    }

    @Test
    override fun testStableNodeIdSpecialCharacters() {
        val id = ConfigRepository.stableNodeId("profile/with/slashes", "node#with#hash")

        assertNotNull(id)
        assertTrue(id.isNotBlank())
    }

    @Test
    override fun testBuildConfigWithOutboundsPreservesExistingProfileSettings() {
        val existingConfig = SingBoxConfig(
            dns = DnsConfig(
                servers = listOf(DnsServer(tag = "remote", type = "https", server = "dns.example.com")),
                finalServer = "remote"
            ),
            inbounds = listOf(Inbound(type = "tun", tag = "tun-in")),
            outbounds = listOf(Outbound(type = "vless", tag = "old")),
            route = RouteConfig(
                rules = listOf(RouteRule(domainSuffix = listOf("example.com"), outbound = "PROXY")),
                finalOutbound = "PROXY"
            ),
            experimental = ExperimentalConfig(cacheFile = CacheFileConfig(enabled = true))
        )
        val newOutbounds = listOf(
            Outbound(type = "vless", tag = "old"),
            Outbound(type = "trojan", tag = "new"),
            Outbound(type = "direct", tag = "direct")
        )

        val updatedConfig = ConfigRepository.buildConfigWithOutboundsPreservingProfileSettings(
            existingConfig = existingConfig,
            outbounds = newOutbounds
        )

        assertEquals(existingConfig.dns, updatedConfig.dns)
        assertEquals(existingConfig.inbounds, updatedConfig.inbounds)
        assertEquals(existingConfig.route, updatedConfig.route)
        assertEquals(existingConfig.experimental, updatedConfig.experimental)
        assertEquals(newOutbounds, updatedConfig.outbounds)
    }

    @Test
    override fun testStableNodeIdEmptyInputs() {
        val id1 = ConfigRepository.stableNodeId("", "node")
        val id2 = ConfigRepository.stableNodeId("profile", "")
        val id3 = ConfigRepository.stableNodeId("", "")

        assertNotNull(id1)
        assertNotNull(id2)
        assertNotNull(id3)
        assertNotEquals(id1, id2)
    }

    @Test
    override fun testStableNodeIdUnicodeCharacters() {
        val id = ConfigRepository.stableNodeId("日本配置", "香港节点-01")

        assertNotNull(id)
        assertTrue(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))

        val id2 = ConfigRepository.stableNodeId("日本配置", "香港节点-01")
        assertEquals(id, id2)
    }

    @Test
    override fun testStableNodeIdCacheEfficiency() {
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
    override fun testExtractSubscriptionUrlFromHtml() {
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
    override fun testLooksLikeHtmlSubscriptionPageDoesNotTrustContentTypeAloneForYamlBody() {
        val result = ConfigRepository.looksLikeHtmlSubscriptionPage(
            contentType = "text/html; charset=utf-8",
            body = "mixed-port: 7890"
        )

        assertFalse(result)
    }

    @Test
    override fun testLooksLikeHtmlSubscriptionPageDoesNotTrustContentTypeAloneForBase64Body() {
        val result = ConfigRepository.looksLikeHtmlSubscriptionPage(
            contentType = "text/html; charset=utf-8",
            body = "c3M6Ly9ZV1Z6TFRFeU9DMW5ZMjA9QDEyNy4wLjAuMTo0NDMjVEVTVA=="
        )

        assertFalse(result)
    }

    @Test
    override fun testLooksLikeHtmlSubscriptionPageByBodyPrefix() {
        val result = ConfigRepository.looksLikeHtmlSubscriptionPage(
            contentType = null,
            body = "<!DOCTYPE html><html><body>订阅信息</body></html>"
        )

        assertTrue(result)
    }

    @Test
    override fun testLooksLikeHtmlSubscriptionPageByHtmlTagPrefixEvenWhenContentTypeIsTextHtml() {
        val result = ConfigRepository.looksLikeHtmlSubscriptionPage(
            contentType = "text/html; charset=utf-8",
            body = "<html><body>订阅信息</body></html>"
        )

        assertTrue(result)
    }

    @Test
    override fun testExtractSubscriptionHost() {
        val host = ConfigRepository.extractSubscriptionHost(
            "https://1.811200.xyz/api/v1/client/subscribe?token=abc"
        )

        assertEquals("1.811200.xyz", host)
    }

    @Test
    override fun testLooksLikeSubscriptionUrlForImportAcceptsSubscriptionApiUrl() {
        assertTrue(
            ConfigRepository.looksLikeSubscriptionUrlForImport(
                "https://sub.example.com/api/v1/client/subscribe?token=abc123"
            )
        )
    }

    @Test
    override fun testLooksLikeSubscriptionUrlForImportAcceptsPortedSubscriptionApiUrl() {
        assertTrue(
            ConfigRepository.looksLikeSubscriptionUrlForImport(
                "https://sub.example.com:8443/api/v1/client/subscribe?token=abc123"
            )
        )
    }

    @Test
    override fun testLooksLikeSubscriptionUrlForImportRejectsHttpProxyLink() {
        assertFalse(
            ConfigRepository.looksLikeSubscriptionUrlForImport(
                "http://proxy.example.com:3128#NoAuthProxy"
            )
        )
    }

    @Test
    override fun testPrioritizeUserAgentsWithPreferredValue() {
        val prioritized = ConfigRepository.prioritizeUserAgents("sing-box/1.13.1")

        assertEquals("sing-box/1.13.1", prioritized.first())
        assertEquals(prioritized.size, prioritized.distinct().size)
        assertTrue(prioritized.contains("ClashMeta/1.18.0"))
    }

    @Test
    override fun testPrioritizeUserAgentsWithoutPreferredValue() {
        val prioritized = ConfigRepository.prioritizeUserAgents(null)

        assertEquals("ClashMeta/1.18.0", prioritized.first())
        assertTrue(prioritized.contains("sing-box/1.13.1"))
    }

    @Test
    override fun testFilterCircuitBrokenUserAgents() {
        val result = ConfigRepository.filterCircuitBrokenUserAgents(
            userAgents = listOf("ClashMeta/1.18.0", "Clash/1.18.0", "sing-box/1.13.1"),
            circuitBrokenUserAgents = setOf("ClashMeta/1.18.0", "Clash/1.18.0")
        )

        assertEquals(listOf("sing-box/1.13.1"), result)
    }

    @Test
    override fun testFilterCircuitBrokenUserAgentsFallsBackWhenAllBlocked() {
        val original = listOf("ClashMeta/1.18.0", "Clash/1.18.0")
        val result = ConfigRepository.filterCircuitBrokenUserAgents(
            userAgents = original,
            circuitBrokenUserAgents = original.toSet()
        )

        assertEquals(original, result)
    }

    @Test
    override fun testBuildSubscriptionAttemptUserAgentsKeepsRememberedUserAgentFirst() {
        val userAgents = ConfigRepository.buildSubscriptionAttemptUserAgents(
            preferredUserAgent = "sing-box/1.13.1",
            circuitBrokenUserAgents = setOf("Clash/1.18.0")
        )

        assertEquals("sing-box/1.13.1", userAgents.first())
        assertTrue(!userAgents.contains("Clash/1.18.0"))
    }

    @Test
    override fun testResolveSubscriptionUpdateBudgetSecondsFallsBackToDefaultWhenNonPositive() {
        val budgetSeconds = ConfigRepository.resolveSubscriptionUpdateBudgetSeconds(0)

        assertEquals(AppSettings().subscriptionUpdateTimeout.toLong(), budgetSeconds)
    }

    @Test
    override fun testResolveSubscriptionAttemptTimeoutBudgetUsesFullRemainingBudget() {
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
    override fun testResolveSubscriptionAttemptTimeoutBudgetRoundsUpRemainingBudget() {
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
    override fun testResolveSubscriptionAttemptTimeoutBudgetReturnsNullWhenBudgetExhausted() {
        val budget = ConfigRepository.resolveSubscriptionAttemptTimeoutBudget(
            totalBudgetSeconds = 30,
            elapsedMs = 30_000
        )

        assertNull(budget)
    }

    @Test
    override fun testSubscriptionContentLengthLimitAllowsUnknownOrBoundedLength() {
        assertFalse(ConfigRepository.isSubscriptionContentLengthTooLargeForTest(-1))
        assertFalse(ConfigRepository.isSubscriptionContentLengthTooLargeForTest(1024))
        assertFalse(
            ConfigRepository.isSubscriptionContentLengthTooLargeForTest(
                ConfigRepository.subscriptionResponseMaxBytesForTest()
            )
        )
    }

    @Test
    override fun testSubscriptionContentLengthLimitRejectsOversizedLength() {
        assertTrue(
            ConfigRepository.isSubscriptionContentLengthTooLargeForTest(
                ConfigRepository.subscriptionResponseMaxBytesForTest() + 1
            )
        )
    }

    @Test
    override fun testResolveSubscriptionUpdateStageMapsKnownStages() {
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
    override fun testLaunchSubscriptionDnsPreResolveReturnsWithoutWaitingForResolution() {
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
    override fun testLaunchSubscriptionDnsPreResolveSwallowsBackgroundFailure() {
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
    override fun testLaunchSubscriptionDnsPreResolveStaleRunCannotClearNewUpdateStage() {
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
    override fun testBatchUpdateResultAggregatesMixedSubscriptionResults() {
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
    override fun testResolveAppRuleOutboundModeDefaultsToProxy() {
        val resolved = ConfigRepository.resolveAppRuleOutboundMode(null)

        assertEquals(RuleSetOutboundMode.PROXY, resolved)
    }

    @Test
    override fun testResolveAppRuleOutboundModeKeepsExplicitMode() {
        val resolved = ConfigRepository.resolveAppRuleOutboundMode(RuleSetOutboundMode.DIRECT)

        assertEquals(RuleSetOutboundMode.DIRECT, resolved)
    }

    @Test
    override fun testShouldRecordSubscriptionNetworkFailureForConnectException() {
        assertTrue(
            ConfigRepository.shouldRecordSubscriptionNetworkFailure(
                ConnectException("failed to connect")
            )
        )
    }

    @Test
    override fun testShouldRecordSubscriptionNetworkFailureForTimeoutException() {
        assertTrue(
            ConfigRepository.shouldRecordSubscriptionNetworkFailure(
                SocketTimeoutException("timeout")
            )
        )
    }

    @Test
    override fun testShouldRecordSubscriptionNetworkFailureForParseError() {
        val result = ConfigRepository.shouldRecordSubscriptionNetworkFailure(
            IllegalArgumentException("parse failed")
        )

        assertTrue(!result)
    }

    @Test
    override fun testShouldStopSubscriptionFallbackForHtmlInfoPage() {
        val result = ConfigRepository.shouldStopSubscriptionFallback(
            looksLikeHtmlInfoPage = true
        )

        assertTrue(result)
    }

    @Test
    override fun testShouldStopSubscriptionFallbackForHttp429() {
        val result = ConfigRepository.shouldStopSubscriptionFallback(
            httpStatusCode = 429
        )

        assertTrue(result)
    }

    @Test
    override fun testShouldNotStopSubscriptionFallbackForOrdinaryParseFailureOrOtherHttpErrors() {
        val parseFailureResult = ConfigRepository.shouldStopSubscriptionFallback()
        val serverErrorResult = ConfigRepository.shouldStopSubscriptionFallback(httpStatusCode = 503)

        assertFalse(parseFailureResult)
        assertFalse(serverErrorResult)
    }

    @Test
    override fun testBuildBootstrapDnsRulesOnlyTargetsResolverDomains() {
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
    override fun testBuildBootstrapDnsRulesSkipsIpAndLocalAddresses() {
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
    override fun testBuildBootstrapDnsRulesStripsPortFromBareHostAddress() {
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
}

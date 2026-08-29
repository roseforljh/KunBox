package com.kunk.singbox.repository

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.DefaultRule
import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsRule
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.Endpoint
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RootAppRoutingAssignment
import com.kunk.singbox.model.RootAppRoutingPlanCompiler
import com.kunk.singbox.model.RouteRule
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.model.RuleType
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.model.TunStack
import com.kunk.singbox.model.WireGuardPeer
import com.kunk.singbox.repository.config.InboundBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigRepositoryRoutingDnsPolicyTest {

    @Test
    @Suppress("LongMethod")
    fun rootLaneValidationRequiresEveryTcpUdpAndDnsInbound() {
        val settings = AppSettings(
            trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT,
            routingMode = RoutingMode.RULE
        )
        val plan = RootAppRoutingPlanCompiler.compile(
            settings,
            listOf(
                RootAppRoutingAssignment(
                    packageNames = listOf("org.telegram.messenger"),
                    targetKind = "OUTBOUND",
                    outboundTag = "🇺🇸 VL | 美国 \"1.1\" \\ residential",
                    sourceLabel = "Telegram"
                )
            ),
            generation = 1L
        )
        val lane = plan.lanes.single()
        val laneInbounds = lane.inboundTags(plan.proxyIpv4, plan.proxyIpv6)
        val dnsTag = ConfigRepository.buildDynamicDnsServerTag(lane.outboundTag)
        val valid = SingBoxConfig(
            inbounds = InboundBuilder.build(settings, TunStack.SYSTEM, plan),
            outbounds = listOf(
                Outbound(type = "socks", tag = lane.outboundTag),
                Outbound(type = "direct", tag = "direct")
            ),
            route = com.kunk.singbox.model.RouteConfig(
                rules = listOf(RouteRule(inbound = laneInbounds, action = "route", outbound = lane.outboundTag))
            ),
            dns = DnsConfig(
                servers = listOf(DnsServer(type = "udp", tag = dnsTag, server = "1.1.1.1")),
                rules = listOf(DnsRule(inbound = laneInbounds, action = "route", server = dnsTag))
            )
        )

        ConfigRepository.requireValidRootApplicationRoutes(valid, plan)

        assertTrue(runCatching {
            ConfigRepository.requireValidRootApplicationRoutes(
                valid.copy(
                    route = valid.route?.copy(
                        rules = listOf(
                            RouteRule(
                                inbound = laneInbounds.dropLast(1),
                                action = "route",
                                outbound = lane.outboundTag
                            )
                        )
                    )
                ),
                plan
            )
        }.isFailure)
        assertTrue(runCatching {
            ConfigRepository.requireValidRootApplicationRoutes(
                valid.copy(
                    dns = valid.dns?.copy(
                        rules = listOf(DnsRule(inbound = laneInbounds.drop(1), action = "route", server = dnsTag))
                    )
                ),
                plan
            )
        }.isFailure)
        assertTrue(runCatching {
            ConfigRepository.requireValidRootApplicationRoutes(
                valid.copy(
                    route = valid.route?.copy(
                        rules = listOf(
                            RouteRule(inbound = laneInbounds, action = "resolve", outbound = lane.outboundTag)
                        )
                    )
                ),
                plan
            )
        }.isFailure)
        assertTrue(runCatching {
            ConfigRepository.requireValidRootApplicationRoutes(
                valid.copy(
                    dns = valid.dns?.copy(
                        rules = listOf(
                            DnsRule(
                                inbound = laneInbounds,
                                action = "route",
                                server = dnsTag,
                                sourcePort = listOf(53)
                            )
                        )
                    )
                ),
                plan
            )
        }.isFailure)
    }

    @Test
    fun rootLaneDnsRuleAddsAddressQueriesWhenFakeDnsIsEnabled() {
        val laneInbounds = listOf("root-lane-000-tcp-v4", "root-lane-000-udp-v4")
        val rules = ConfigRepository.buildOrderedDnsRules(
            entries = listOf(
                DnsRule(inbound = laneInbounds) to
                    ConfigRepository.OutboundSemantic.RouteTag("PROXY")
            ),
            fakeDnsEnabled = true,
            directServerTag = "local",
            proxyServerTag = ConfigRepository.buildDynamicDnsServerTag("PROXY")
        )

        assertEquals(listOf("A", "AAAA"), rules.single().queryType)
    }

    @Test
    fun sharedUidExpansionKeepsCompletePackageSet() {
        val packages = ConfigRepository.expandSharedUidPackageNames(
            packageNames = listOf("com.example.one"),
            resolveUid = { 10_123 },
            resolvePackages = { listOf("com.example.one", "com.example.two") }
        )

        assertEquals(listOf("com.example.one", "com.example.two"), packages)
    }

    @Test
    fun capturedPackageFilteringFollowsActualCaptureMode() {
        assertTrue(
            ConfigRepository.shouldFilterCapturedPackages(
                AppSettings(tunEnabled = false, trafficCaptureMode = TrafficCaptureMode.ROOT_TRANSPARENT)
            )
        )
        assertFalse(
            ConfigRepository.shouldFilterCapturedPackages(
                AppSettings(tunEnabled = false, trafficCaptureMode = TrafficCaptureMode.PROXY_ONLY)
            )
        )
    }

    @Test
    fun bareDnsDomainBuildsUdpServerAndUnknownSchemeFails() {
        val server = ConfigRepository.buildDnsServer("dns.example.com", "local")

        assertEquals("udp", server.type)
        assertEquals("dns.example.com", server.server)
        assertEquals(53, server.serverPort)
        val failure = runCatching { ConfigRepository.buildDnsServer("ftp://dns.example.com", "invalid") }
        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun bootstrapUsesConfiguredNumericDnsOrSystemResolverWithoutHardcodedFallback() {
        val numeric = ConfigRepository.buildBootstrapDnsServer(
            localDnsAddress = "udp://9.9.9.9",
            tag = "dns-bootstrap",
            domainStrategy = "prefer_ipv4"
        )
        val system = ConfigRepository.buildBootstrapDnsServer(
            localDnsAddress = "https://dns.local.example/dns-query",
            tag = "dns-bootstrap",
            domainStrategy = "prefer_ipv4"
        )

        assertEquals("9.9.9.9", numeric.server)
        assertEquals("local", system.type)
        assertNull(system.server)
    }

    @Test
    fun defaultLocalDnsIsNumericEncryptedBootstrap() {
        val localDns = ConfigRepository.normalizeLocalDns(null)
        val bootstrap = ConfigRepository.buildBootstrapDnsServer(
            localDnsAddress = localDns,
            tag = "dns-bootstrap",
            domainStrategy = "prefer_ipv4"
        )

        assertEquals(AppSettings.DEFAULT_LOCAL_DNS, localDns)
        assertEquals("https", bootstrap.type)
        assertEquals("223.5.5.5", bootstrap.server)
        assertNull(bootstrap.detour)
        assertNull(bootstrap.domainResolver)
    }

    @Test
    fun fakeIpExclusionsDistinguishExactDomainsFromSuffixes() {
        val rules = ConfigRepository.buildFakeIpExcludeDnsRules(
            values = listOf("accounts.google.com", "local", "*.lan.example"),
            serverTag = "remote"
        )

        assertEquals(listOf("accounts.google.com"), rules.first().domain)
        assertEquals(listOf("local", "lan.example"), rules.last().domainSuffix)
    }

    @Test
    fun defaultBlockAddsCatchAllDnsResponseBeforeFallbackRules() {
        val rules = ConfigRepository.buildDefaultDnsBlockRules(RoutingMode.RULE, DefaultRule.BLOCK)

        assertEquals(1, rules.size)
        assertEquals("predefined", rules.first().action)
        assertEquals("NOERROR", rules.first().rcode?.asString)
    }

    @Test
    fun ruleSetInboundAliasesAndUserRuleOrderAreShared() {
        assertEquals(
            listOf("tun-in", "mixed-in"),
            ConfigRepository.normalizeRuleSetInboundTags(listOf("tun", "mixed", "tun"))
        )
        val domain = DnsRule(domain = listOf("example.com"))
        val app = DnsRule(packageName = listOf("com.example"))
        val ruleSet = DnsRule(ruleSet = listOf("geosite-cn"))
        assertEquals(
            listOf(app, domain, ruleSet),
            ConfigRepository.mergeUserDnsRules(listOf(domain), listOf(app), listOf(ruleSet))
        )
    }

    @Test
    fun rootRuleSetTunAliasExpandsToTransparentCaptureInbounds() {
        assertEquals(
            listOf("redirect-in-v4", "tproxy-in-v4", "redirect-in-v6", "tproxy-in-v6"),
            ConfigRepository.normalizeRuleSetInboundTags(
                listOf("tun-in"),
                AppSettings(trafficCaptureMode = com.kunk.singbox.model.TrafficCaptureMode.ROOT_TRANSPARENT)
            )
        )
    }

    @Test
    fun rootRuntimeKeepsDebugRoutingSignalsWhileOtherModesStayInfo() {
        assertEquals("debug", ConfigRepository.resolveRunLogLevel(TrafficCaptureMode.ROOT_TRANSPARENT))
        assertEquals("info", ConfigRepository.resolveRunLogLevel(TrafficCaptureMode.VPN))
        assertEquals("info", ConfigRepository.resolveRunLogLevel(TrafficCaptureMode.PROXY_ONLY))
    }

    @Test
    fun dnsCompatibilityAcceptsLogicalRulesAndCurrentAddressMatchers() {
        val warning = ConfigRepository.buildDnsOverrideCompatibilityWarning(
            """
            {
              "servers": [{"tag":"local","type":"local"}],
              "rules": [{
                "type":"logical",
                "mode":"and",
                "rules":[
                  {"domain_suffix":"example.com"},
                  {"ip_cidr":"192.0.2.0/24","rule_set_ip_cidr_accept_empty":true}
                ],
                "action":"route",
                "server":"local"
              }],
              "independent_cache":true
            }
            """.trimIndent()
        )

        assertNull(warning)
    }

    @Test
    fun dnsCompatibilityOnlyFlagsDeprecatedRuleSetIpCidrSpelling() {
        val warning = ConfigRepository.buildDnsOverrideCompatibilityWarning(
            """
            {
              "servers": [{"tag":"local","type":"local"}],
              "rules": [{
                "domain":"example.com",
                "rule_set_ipcidr_match_source":true,
                "action":"route",
                "server":"local"
              }]
            }
            """.trimIndent()
        )

        assertTrue(warning?.contains("rule_set_ipcidr_match_source") == true)
    }

    @Test
    fun logicalDnsRuleIsNotPrecomputedAsOutboundDomainResolver() {
        val resolver = ConfigRepository.buildDomainResolverForMatchedDnsOverrideRule(
            domain = "node.example.com",
            outboundTag = "node",
            rule = DnsRule(
                type = "logical",
                mode = "and",
                rules = listOf(DnsRule(domainSuffix = listOf("example.com"))),
                action = "route",
                server = "local"
            )
        )

        assertNull(resolver)
    }

    @Test
    fun routingModesShareTheSameUserRuleScopesForRouteAndDns() {
        assertFalse(ConfigRepository.shouldApplyCustomAndAppRules(RoutingMode.GLOBAL_DIRECT))
        assertFalse(ConfigRepository.shouldApplyRuleSetRules(RoutingMode.GLOBAL_DIRECT))

        assertFalse(ConfigRepository.shouldApplyCustomAndAppRules(RoutingMode.GLOBAL_PROXY))
        assertFalse(ConfigRepository.shouldApplyRuleSetRules(RoutingMode.GLOBAL_PROXY))

        assertTrue(ConfigRepository.shouldApplyCustomAndAppRules(RoutingMode.RULE))
        assertTrue(ConfigRepository.shouldApplyRuleSetRules(RoutingMode.RULE))
    }

    @Test
    fun customPortRuleProducesExactPortsAndValidatedRanges() {
        val rule = ConfigRepository.applyCustomRuleMatcher(
            baseRule = RouteRule(outbound = "PROXY"),
            type = RuleType.PORT,
            values = listOf("53", "1000-2000", "invalid")
        )

        assertEquals(listOf(53), rule?.port)
        assertEquals(listOf("1000:2000"), rule?.portRange)
    }

    @Test
    fun clashSecretRejectsWeakStoredValueAndKeepsStrongStoredValue() {
        val generated = "a".repeat(64)

        assertEquals(generated, ConfigRepository.resolveClashApiSecret("short") { generated })
        assertEquals(generated, ConfigRepository.resolveClashApiSecret("  $generated  ") { "unused" })
    }

    @Test
    fun wireGuardEndpointRoundTripPreservesEverySupportedField() {
        val endpoint = Endpoint(
            type = "wireguard",
            tag = "wg-main",
            system = true,
            name = "wg0",
            mtu = 1380,
            address = listOf("10.0.0.2/32", "fd00::2/128"),
            privateKey = "private",
            listenPort = 51820,
            peers = listOf(
                WireGuardPeer(
                    server = "wg.example.com",
                    serverPort = 443,
                    publicKey = "public",
                    preSharedKey = "psk",
                    allowedIps = listOf("0.0.0.0/0", "::/0"),
                    persistentKeepaliveInterval = 25,
                    reserved = listOf(1, 2, 3)
                )
            ),
            udpTimeout = "5m",
            workers = 2,
            detour = "direct",
            bindInterface = "wlan0",
            inet4BindAddress = "192.0.2.20",
            inet6BindAddress = "2001:db8::20",
            bindAddressNoPort = true,
            protectPath = "/tmp/protect.sock",
            routingMark = com.google.gson.JsonPrimitive("0xff"),
            reuseAddr = true,
            netns = "test-ns",
            connectTimeout = "10s",
            tcpFastOpen = true,
            tcpMultiPath = true,
            disableTcpKeepAlive = true,
            tcpKeepAlive = "30s",
            tcpKeepAliveInterval = "10s",
            udpFragment = false,
            networkStrategy = "hybrid",
            networkType = listOf("wifi", "cellular"),
            fallbackNetworkType = listOf("ethernet"),
            fallbackDelay = "250ms",
            domainStrategy = "prefer_ipv6",
            domainResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        val outbound = ConfigRepository.convertWireGuardEndpointToOutbound(endpoint)
        val roundTrip = outbound?.let(ConfigRepository::convertWireGuardOutboundToEndpoint)

        assertEquals(endpoint, roundTrip)
    }

    @Test
    fun wireGuardRuntimeFillsDefaultAllowedIpsWhenMissing() {
        val outbound = Outbound(
            type = "wireguard",
            tag = "wg-manual",
            localAddress = listOf("10.2.0.2/32"),
            privateKey = listOf("private"),
            peers = listOf(
                WireGuardPeer(
                    server = "2a02:6ea0:d802:5519::10",
                    serverPort = 51820,
                    publicKey = "public"
                )
            )
        )

        val endpoint = ConfigRepository.convertWireGuardOutboundToEndpoint(outbound)

        assertEquals(
            listOf("0.0.0.0/0", "::/0"),
            endpoint?.peers?.single()?.allowedIps
        )
        assertEquals("2a02:6ea0:d802:5519::10", endpoint?.peers?.single()?.server)
    }

    @Test
    fun endpointOnlyWireGuardNormalizesForUiAndRuntimeProducesSingleEndpoint() {
        val endpoint = Endpoint(
            type = "wireguard",
            tag = "wg-only",
            address = listOf("10.0.0.2/32"),
            privateKey = "private"
        )
        val normalized = ConfigRepository.normalizeWireGuardEndpointsForInternalUse(
            SingBoxConfig(endpoints = listOf(endpoint))
        )
        val outbound = normalized.outbounds?.single()
        val runtimeEndpoint = outbound?.let(ConfigRepository::convertWireGuardOutboundToEndpoint)
        val merged = ConfigRepository.mergeRuntimeEndpoints(
            convertedEndpoints = listOfNotNull(runtimeEndpoint),
            existingEndpoints = emptyList()
        )

        assertEquals("wg-only", outbound?.tag)
        assertNull(normalized.endpoints)
        assertEquals(listOf(endpoint), merged)
    }

    @Test
    fun selectorValidationKeepsWireGuardEndpointReference() {
        val selector = Outbound(type = "selector", tag = "PROXY", outbounds = listOf("wg-main"))
        val sanitized = ConfigRepository.sanitizeSelectorSafeOutbounds(
            outbounds = listOf(selector, Outbound(type = "direct", tag = "direct")),
            additionalTags = setOf("wg-main")
        )

        assertEquals(listOf("wg-main"), sanitized.first().outbounds)
    }

    @Test
    fun latencyRuntimeOutboundsKeepsWireGuardWithoutOutboundFixer() {
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(
                    type = "wireguard",
                    tag = "wg-manual",
                    localAddress = listOf("10.2.0.2/32"),
                    privateKey = listOf("private"),
                    peers = listOf(
                        WireGuardPeer(
                            server = "149.22.88.129",
                            serverPort = 51820,
                            publicKey = "public"
                        )
                    )
                ),
                Outbound(type = "direct", tag = "direct")
            )
        )

        val runtime = ConfigRepository.buildLatencyRuntimeOutbounds(config) {
            // 模拟 OutboundFixer：运行时拒绝 WireGuard outbound
            if (it.type.equals("wireguard", ignoreCase = true)) null else it
        }

        val wg = runtime.firstOrNull { it.tag == "wg-manual" }
        assertEquals("wireguard", wg?.type)
        assertEquals(
            listOf("0.0.0.0/0", "::/0"),
            wg?.peers?.single()?.allowedIps
        )
        assertTrue(runtime.any { it.tag == "direct" })
    }

    @Test
    fun latencyRuntimeOutboundsNormalizesEndpointOnlyWireGuard() {
        val config = SingBoxConfig(
            endpoints = listOf(
                Endpoint(
                    type = "wireguard",
                    tag = "wg-only",
                    address = listOf("10.0.0.2/32"),
                    privateKey = "private",
                    peers = listOf(
                        WireGuardPeer(
                            server = "1.1.1.1",
                            serverPort = 51820,
                            publicKey = "public",
                            allowedIps = listOf("0.0.0.0/0")
                        )
                    )
                )
            )
        )

        val runtime = ConfigRepository.buildLatencyRuntimeOutbounds(config) {
            if (it.type.equals("wireguard", ignoreCase = true)) null else it
        }

        assertEquals(listOf("wg-only"), runtime.map { it.tag })
        assertEquals("wireguard", runtime.single().type)
    }

    @Test
    fun selectorValidationDoesNotTurnInvalidGroupsIntoDirect() {
        val sanitized = ConfigRepository.sanitizeSelectorSafeOutbounds(
            outbounds = listOf(
                Outbound(
                    type = "selector",
                    tag = "invalid-selector",
                    outbounds = listOf("missing-node"),
                    default = "missing-node"
                ),
                Outbound(
                    type = "urltest",
                    tag = "invalid-urltest",
                    outbounds = listOf("missing-node")
                ),
                Outbound(type = "direct", tag = "direct")
            )
        )

        assertTrue(sanitized[0].outbounds.isNullOrEmpty())
        assertNull(sanitized[0].default)
        assertTrue(sanitized[1].outbounds.isNullOrEmpty())
        assertNull(sanitized[1].default)
    }

    @Test
    fun unavailableAndroidCapabilitiesFailClosedBeforeImportOrRuntime() {
        val tailscaleConfigs = listOf(
            SingBoxConfig(endpoints = listOf(Endpoint(type = "tailscale", tag = "ts-endpoint"))),
            SingBoxConfig(outbounds = listOf(Outbound(type = "tailscale", tag = "ts-outbound"))),
            SingBoxConfig(proxies = listOf(Outbound(type = "tailscale", tag = "ts-proxy"))),
            SingBoxConfig(dns = DnsConfig(servers = listOf(DnsServer(type = "tailscale", tag = "ts-dns"))))
        )
        tailscaleConfigs.forEach { config ->
            val message = ConfigRepository.findUnsupportedAndroidCapability(config)
            assertTrue(message?.contains("Tailscale") == true)
            assertTrue(message?.contains("APK 体积") == true)
        }

        val torConfigs = listOf(
            SingBoxConfig(endpoints = listOf(Endpoint(type = "tor", tag = "tor-endpoint"))),
            SingBoxConfig(outbounds = listOf(Outbound(type = "tor", tag = "tor-outbound"))),
            SingBoxConfig(proxies = listOf(Outbound(type = "tor", tag = "tor-proxy"))),
            SingBoxConfig(dns = DnsConfig(servers = listOf(DnsServer(type = "tor", tag = "tor-dns"))))
        )
        torConfigs.forEach { config ->
            assertTrue(ConfigRepository.findUnsupportedAndroidCapability(config)?.contains("Tor") == true)
        }

        listOf(
            """{"type":"tailscale","tag":"ts"}""",
            """{"endpoints":[{"type":"tailscale","tag":"ts"}]}""",
            """{"proxies":[{"type":"tailscale","tag":"ts"}]}""",
            """{"dns":{"servers":[{"type":"tailscale","tag":"ts"}]}}""",
            """{"servers":[{"type":"tailscale","tag":"ts"}]}"""
        ).forEach { json ->
            assertTrue(
                ConfigRepository.findUnsupportedAndroidCapabilityInJson(json)
                    ?.contains("Tailscale") == true
            )
        }
        assertTrue(
            ConfigRepository.findUnsupportedAndroidCapabilityInJson(
                """{"type":"tor","tag":"tor"}"""
            )?.contains("Tor") == true
        )

        val dnsOverrideError = runCatching {
            ConfigRepository.parseDnsOverrideConfig(
                """{"servers":[{"type":"tailscale","tag":"ts"}]}"""
            )
        }.exceptionOrNull()
        assertTrue(dnsOverrideError is IllegalArgumentException)
        assertTrue(dnsOverrideError?.message?.contains("Tailscale") == true)

        assertNull(
            ConfigRepository.findUnsupportedAndroidCapability(
                SingBoxConfig(outbounds = listOf(Outbound(type = "vmess", tag = "node")))
            )
        )
    }
}

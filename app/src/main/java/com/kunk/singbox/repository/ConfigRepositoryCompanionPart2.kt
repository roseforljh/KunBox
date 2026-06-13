package com.kunk.singbox.repository

import android.util.Log
import com.google.gson.JsonObject
import com.kunk.singbox.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Suppress("TooManyFunctions")
abstract class ConfigRepositoryCompanionPart2 : ConfigRepositoryCompanionPart1() {
    internal override fun buildCustomRuleSetRulesStatic(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        profiles: List<ProfileUi>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>
    ): List<RouteRule> {
        val rules = mutableListOf<RouteRule>()
        val validTags = validRuleSets.mapNotNull { it.tag }.toSet()
        val sortedRuleSets = sortRuleSetsForDnsAndRoutePriority(
            settings.ruleSets.filter { it.enabled && it.tag in validTags }
        )

        sortedRuleSets.forEach { ruleSet ->
            val semantic = resolveOutboundSemantic(
                mode = resolveRuleSetOutboundMode(ruleSet.outboundMode),
                value = ruleSet.outboundValue,
                context = ConfigRepositoryOutboundSemanticContext(
                    selectorTag = defaultProxyTag,
                    outbounds = outbounds,
                    profiles = profiles,
                    nodeTagResolver = nodeTagResolver
                )
            )
            val baseRule = toRouteRule(semantic, defaultProxyTag)
            val inboundTags = ruleSet.inbounds?.takeIf { it.isNotEmpty() }?.map {
                when (it) {
                    "tun" -> "tun-in"
                    "mixed" -> "mixed-in"
                    else -> it
                }
            }

            rules.add(
                baseRule.copy(
                    ruleSet = listOf(ruleSet.tag),
                    inbound = inboundTags
                )
            )
        }

        return rules
    }

    internal override fun buildBypassLanRulesStatic(settings: AppSettings): List<RouteRule> {
        return if (settings.bypassLan) {
            listOf(RouteRule(ipIsPrivate = true, outbound = "direct"))
        } else {
            emptyList()
        }
    }

    internal override fun buildMulticastRejectRulesStatic(settings: AppSettings): List<RouteRule> {
        val cidrs = mutableListOf<String>()
        if (settings.ipVersionMode.includesIpv4) cidrs.add("224.0.0.0/3")
        if (settings.ipVersionMode.includesIpv6) cidrs.add("ff00::/8")
        return if (cidrs.isEmpty()) {
            emptyList()
        } else {
            listOf(RouteRule(ipCidr = cidrs, action = "reject"))
        }
    }

    internal override fun resolveOutboundSemantic(
        mode: RuleSetOutboundMode?,
        value: String?,
        context: ConfigRepositoryOutboundSemanticContext
    ): ConfigRepository.OutboundSemantic {
        val selectorTag = context.selectorTag
        val outbounds = context.outbounds
        val profiles = context.profiles
        val nodeTagResolver = context.nodeTagResolver
        return when (mode ?: RuleSetOutboundMode.PROXY) {
            RuleSetOutboundMode.DIRECT -> ConfigRepository.OutboundSemantic.Direct
            RuleSetOutboundMode.BLOCK -> ConfigRepository.OutboundSemantic.Block
            RuleSetOutboundMode.PROXY -> ConfigRepository.OutboundSemantic.Proxy
            RuleSetOutboundMode.NODE -> {
                val resolvedTag = nodeTagResolver(value)
                if (resolvedTag != null) {
                    ConfigRepository.OutboundSemantic.RouteTag(resolvedTag)
                } else {
                    Log.w(TAG, "Node ID '$value' not resolved to any tag, falling back to $selectorTag")
                    ConfigRepository.OutboundSemantic.FallbackProxy(selectorTag)
                }
            }
            RuleSetOutboundMode.PROFILE -> {
                val profileId = value
                if (profileId.isNullOrBlank()) {
                    Log.w(TAG, "Profile ID is null or blank, falling back to $selectorTag")
                    return ConfigRepository.OutboundSemantic.FallbackProxy(selectorTag)
                }
                val profileName = profiles.find { it.id == profileId }?.name
                if (profileName == null) {
                    Log.w(TAG, "Profile with ID '$profileId' not found, falling back to $selectorTag")
                    return ConfigRepository.OutboundSemantic.FallbackProxy(selectorTag)
                }
                val tag = "P:$profileName"
                if (outbounds.any { it.tag == tag }) {
                    ConfigRepository.OutboundSemantic.RouteTag(tag)
                } else {
                    Log.w(TAG, "Profile selector tag '$tag' not found in outbounds, falling back to $selectorTag")
                    ConfigRepository.OutboundSemantic.FallbackProxy(selectorTag)
                }
            }
        }
    }

    internal override fun toRouteRuleForTest(semantic: ConfigRepository.OutboundSemantic, selectorTag: String): RouteRule {
        return toRouteRule(semantic, selectorTag)
    }

    internal override fun resolveOutboundSemanticForTest(input: OutboundSemanticTestInput): ConfigRepository.OutboundSemantic {
        val profileUis = input.profiles.map { it.toUiModel() }
        return resolveOutboundSemantic(
            input.mode,
            input.value,
            ConfigRepositoryOutboundSemanticContext(
                input.selectorTag,
                input.outbounds,
                profileUis,
                input.nodeTagResolver
            )
        )
    }

    internal override fun resolveProfileSelectorDefault(
        nodeIds: List<String>,
        nodeTagMap: Map<String, String>,
        rememberedNodeId: String?,
        savedNodeLatencies: Map<String, Long>
    ): String? {
        val candidateTags = nodeIds.mapNotNull { nodeTagMap[it] }.distinct()
        val bestLatencyTag = nodeIds.asSequence()
            .mapNotNull { nodeId ->
                val tag = nodeTagMap[nodeId] ?: return@mapNotNull null
                val latency = savedNodeLatencies[nodeId] ?: return@mapNotNull null
                if (latency <= 0) {
                    return@mapNotNull null
                }
                tag to latency
            }
            .minByOrNull { it.second }
            ?.first

        val rememberedTag = rememberedNodeId
            ?.let { nodeTagMap[it] }
            ?.takeIf { it in candidateTags }

        return when {
            candidateTags.isEmpty() -> null
            bestLatencyTag != null -> bestLatencyTag
            rememberedTag != null -> rememberedTag
            else -> candidateTags.firstOrNull()
        }
    }

    internal override fun buildDynamicDnsServerTag(detourTag: String): String {
        val normalized = detourTag
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "tag" }
            .take(24)
        val hash = detourTag.toByteArray(Charsets.UTF_8)
            .fold(0x811c9dc5.toInt()) { acc, byte ->
                (acc xor byte.toInt()) * 0x01000193
            }
            .toUInt()
            .toString(16)
            .padStart(8, '0')
        return "dns-remote-$normalized-$hash"
    }

    internal override fun ensureDynamicRemoteDnsServers(
        dnsServers: MutableList<DnsServer>,
        semantics: List<ConfigRepository.OutboundSemantic>,
        remoteDnsAddr: String,
        remoteStrategy: String?,
        remoteResolver: DomainResolveConfig?
    ) {
        semantics.asSequence()
            .filterIsInstance<ConfigRepository.OutboundSemantic.RouteTag>()
            .map { it.tag }
            .distinct()
            .forEach { detourTag ->
                val serverTag = buildDynamicDnsServerTag(detourTag)
                if (dnsServers.none { it.tag == serverTag }) {
                    dnsServers.add(
                        buildDynamicRemoteDnsServer(
                            detourTag = detourTag,
                            remoteDnsAddr = remoteDnsAddr,
                            remoteStrategy = remoteStrategy,
                            remoteResolver = remoteResolver
                        )
                    )
                }
            }
    }

    internal override fun buildDynamicDnsServersForTest(
        semantics: List<ConfigRepository.OutboundSemantic>,
        remoteDnsAddr: String,
        remoteStrategy: String?,
        remoteResolver: DomainResolveConfig?
    ): List<DnsServer> {
        val servers = mutableListOf<DnsServer>()
        ensureDynamicRemoteDnsServers(servers, semantics, remoteDnsAddr, remoteStrategy, remoteResolver)
        return servers
    }

    internal override fun buildDynamicRemoteDnsServerForTest(
        detourTag: String,
        remoteDnsAddr: String,
        remoteStrategy: String?,
        remoteResolver: DomainResolveConfig?
    ): DnsServer {
        return buildDynamicRemoteDnsServer(
            detourTag = detourTag,
            remoteDnsAddr = remoteDnsAddr,
            remoteStrategy = remoteStrategy,
            remoteResolver = remoteResolver
        )
    }

    internal override fun buildDynamicRemoteDnsServer(
        detourTag: String,
        remoteDnsAddr: String,
        remoteStrategy: String?,
        remoteResolver: DomainResolveConfig?
    ): DnsServer {
        return buildDnsServer(
            address = remoteDnsAddr,
            tag = buildDynamicDnsServerTag(detourTag),
            detour = detourTag,
            domainStrategy = remoteStrategy,
            domainResolver = remoteResolver
        )
    }

    internal override fun resolveActiveEchDnsServerForTest(activeTag: String, outbounds: List<Outbound>): String? {
        return resolveActiveEchDnsServer(activeTag, outbounds)
    }

    internal override fun resolveActiveEchDnsServer(activeTag: String, outbounds: List<Outbound>): String? {
        val activeOutbound = outbounds.firstOrNull { it.tag == activeTag }
        val activeDnsServer = activeOutbound
            ?.tls
            ?.ech
            ?.dnsServer
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (activeDnsServer != null) return activeDnsServer

        if (activeOutbound?.tls?.ech?.enabled != true) return null

        val candidates = outbounds
            .mapNotNull { it.tls?.ech?.dnsServer?.trim()?.takeIf(String::isNotBlank) }
            .distinct()
        return candidates.singleOrNull()
    }

    internal override fun needsLegacyEchDnsRepairForTest(config: SingBoxConfig): Boolean {
        return needsLegacyEchDnsRepair(config)
    }

    internal override fun needsLegacyEchDnsRepair(config: SingBoxConfig): Boolean {
        return config.outbounds.orEmpty().any { outbound ->
            val ech = outbound.tls?.ech
            val hasEch = ech?.enabled == true ||
                !ech?.queryServerName.isNullOrBlank() ||
                !ech?.config.isNullOrEmpty()
            hasEch && ech?.dnsServer.isNullOrBlank() && ech?.config.isNullOrEmpty()
        }
    }

    internal override fun resolveFakeIpRanges(fakeIpRange: String?): ConfigRepositoryFakeIpRanges {
        val ranges = fakeIpRange.orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val inet4Range = ranges.firstOrNull { it.contains(".") } ?: "198.18.0.0/15"
        val inet6Range = ranges.firstOrNull { it.contains(":") } ?: "fc00::/18"
        return ConfigRepositoryFakeIpRanges(inet4Range = inet4Range, inet6Range = inet6Range)
    }

    internal override fun buildFakeIpDnsServer(fakeIpRange: String?): DnsServer {
        val ranges = resolveFakeIpRanges(fakeIpRange)
        return DnsServer(
            tag = "fakeip-dns",
            type = "fakeip",
            inet4Range = ranges.inet4Range,
            inet6Range = ranges.inet6Range
        )
    }

    internal override fun buildFakeIpDnsServerForTest(fakeIpRange: String?): DnsServer {
        return buildFakeIpDnsServer(fakeIpRange)
    }

    internal override fun buildFakeIpConfig(fakeIpRange: String?): DnsFakeIpConfig {
        val ranges = resolveFakeIpRanges(fakeIpRange)
        return DnsFakeIpConfig(
            enabled = true,
            inet4Range = ranges.inet4Range,
            inet6Range = ranges.inet6Range
        )
    }

    internal override fun dnsServerTagForSemantic(
        semantic: ConfigRepository.OutboundSemantic,
        fakeDnsEnabled: Boolean,
        directServerTag: String,
        proxyServerTag: String): String? {
        return when (semantic) {
            ConfigRepository.OutboundSemantic.Direct -> directServerTag
            ConfigRepository.OutboundSemantic.Block -> {
                Log.d(TAG, "DNS rule for Block semantic, skipping DNS server assignment")
                null
            }
            ConfigRepository.OutboundSemantic.Proxy -> proxyServerTag
            is ConfigRepository.OutboundSemantic.FallbackProxy -> proxyServerTag
            is ConfigRepository.OutboundSemantic.RouteTag -> buildDynamicDnsServerTag(semantic.tag)
        }
    }

    internal override fun dnsServerTagForSemanticForTest(
        semantic: ConfigRepository.OutboundSemantic,
        fakeDnsEnabled: Boolean,
        directServerTag: String,
        proxyServerTag: String): String? {
        return dnsServerTagForSemantic(semantic, fakeDnsEnabled, directServerTag, proxyServerTag)
    }

    internal override fun resolveDnsServerTagForRuleSemanticForTest(
        semantic: ConfigRepository.OutboundSemantic,
        fakeDnsEnabled: Boolean,
        directServerTag: String,
        proxyServerTag: String): String? {
        return when (semantic) {
            ConfigRepository.OutboundSemantic.Direct -> directServerTag
            ConfigRepository.OutboundSemantic.Block -> null
            ConfigRepository.OutboundSemantic.Proxy -> proxyServerTag
            is ConfigRepository.OutboundSemantic.FallbackProxy -> proxyServerTag
            is ConfigRepository.OutboundSemantic.RouteTag -> buildDynamicDnsServerTag(semantic.tag)
        }
    }

    internal override fun buildDnsRouteToProxyForTest(
        fakeDnsEnabled: Boolean,
        proxyServerTag: String,
        rule: DnsRule
    ): List<DnsRule> {
        fun dnsRouteTo(server: String, currentRule: DnsRule): DnsRule =
            currentRule.copy(action = "route", server = server)

        if (!fakeDnsEnabled) {
            return listOf(dnsRouteTo(proxyServerTag, rule))
        }
        return listOf(dnsRouteTo(proxyServerTag, rule.copy(queryType = IP_DNS_QUERY_TYPES)))
    }

    internal override fun buildDnsRouteToNonDirectForTest(
        fakeDnsEnabled: Boolean,
        serverTag: String,
        rule: DnsRule
    ): List<DnsRule> {
        return buildDnsRouteToNonDirect(fakeDnsEnabled, serverTag, rule)
    }

    internal override fun buildNonIpDnsFallbackRuleForTest(serverTag: String): DnsRule {
        return buildNonIpDnsFallbackRule(serverTag)
    }

    internal override fun buildDnsRouteToDirectForTest(rule: DnsRule): DnsRule {
        return buildDnsRouteToDirect("local", "local", rule)
    }

    internal override fun sortRuleSetsForDnsAndRoutePriorityForTest(ruleSets: List<RuleSet>): List<RuleSet> {
        return sortRuleSetsForDnsAndRoutePriority(ruleSets)
    }

    internal override fun buildQuicBlockRuleForTest(settings: AppSettings): List<RouteRule> {
        return if (settings.blockQuic) {
            listOf(
                RouteRule(protocolRaw = listOf("quic"), action = "reject")
            )
        } else {
            emptyList()
        }
    }

    internal override fun buildTunFakeIpDnsRulesForTest(fakeDnsEnabled: Boolean): List<DnsRule> {
        return buildTunFakeIpDnsRulesStatic(fakeDnsEnabled)
    }

    internal override fun buildOutboundDomainResolverDnsRulesForTest(outbounds: List<Outbound>): List<DnsRule> {
        return buildOutboundDomainResolverDnsRulesForRuntime(outbounds)
    }

    internal override fun buildOutboundDomainResolverDnsRulesForRuntime(outbounds: List<Outbound>): List<DnsRule> {
        return buildOutboundDomainResolverDnsRules(outbounds)
    }

    internal override fun applyDefaultOutboundDomainResolverForTest(
        outbounds: List<Outbound>,
        defaultResolverTag: String,
        defaultResolverStrategy: String?): List<Outbound> {
        return applyDefaultOutboundDomainResolver(outbounds, defaultResolverTag, defaultResolverStrategy)
    }

    internal override fun buildEchDnsRulesForTest(outbounds: List<Outbound>, serverTag: String): List<DnsRule> {
        return buildEchDnsRules(outbounds, serverTag)
    }

    internal override fun buildEchAwareHttpsSvcbDnsRulesForTest(
        blockQuic: Boolean,
        outbounds: List<Outbound>,
        echQueryServerTag: String
    ): List<DnsRule> {
        return buildEchAwareHttpsSvcbDnsRules(blockQuic, outbounds, echQueryServerTag)
    }

    internal override fun buildTunFakeIpDnsRulesStatic(fakeDnsEnabled: Boolean): List<DnsRule> {
        if (!fakeDnsEnabled) return emptyList()
        return listOf(
            DnsRule(
                queryType = listOf("A", "AAAA"),
                inbound = listOf("tun-in"),
                action = "route",
                server = "fakeip-dns"
            )
        )
    }

    internal override fun buildOutboundDomainResolverDnsRules(outbounds: List<Outbound>): List<DnsRule> {
        val domainToResolver = linkedMapOf<String, DomainResolveConfig>()
        outbounds.forEach { outbound ->
            val domain = outbound.server
                ?.trim()
                ?.takeIf { it.isNotBlank() && !isIpAddressValue(it) }
                ?.let { normalizeDnsRuleDomain(it) }
                ?: return@forEach
            val resolver = outbound.domainResolver ?: return@forEach
            val resolverServer = resolver
                ?.server
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "fakeip-dns" }
                ?: return@forEach
            domainToResolver.putIfAbsent(domain, resolver.copy(server = resolverServer))
        }
        return domainToResolver.map { (domain, resolver) ->
            DnsRule(
                domain = listOf(domain),
                queryType = IP_DNS_QUERY_TYPES,
                action = "route",
                server = resolver.server,
                strategy = resolver.strategy,
                disableCache = resolver.disableCache,
                rewriteTtl = resolver.rewriteTtl,
                clientSubnet = resolver.clientSubnet
            )
        }
    }

    internal override fun applyDefaultOutboundDomainResolver(
        outbounds: List<Outbound>,
        defaultResolverTag: String,
        defaultResolverStrategy: String?): List<Outbound> {
        return outbounds.map { outbound ->
            val server = outbound.server?.trim().orEmpty()
            if (server.isBlank() || isIpAddressValue(server)) return@map outbound

            val existing = outbound.domainResolver
            if (!existing?.server.isNullOrBlank() && existing?.server != DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG) {
                return@map outbound
            }

            outbound.copy(
                domainResolver = (existing ?: DomainResolveConfig()).copy(
                    server = defaultResolverTag,
                    strategy = existing?.strategy ?: defaultResolverStrategy
                )
            )
        }
    }

    internal override fun buildEchAwareHttpsSvcbDnsRules(
        blockQuic: Boolean,
        outbounds: List<Outbound>,
        echQueryServerTag: String
    ): List<DnsRule> {
        val rules = buildEchDnsRules(outbounds, echQueryServerTag).toMutableList()
        val hasEchOutbound = outbounds.any { it.tls?.ech?.enabled == true }
        if (blockQuic && hasEchOutbound) {
            rules.add(
                DnsRule(
                    queryType = listOf("HTTPS", "SVCB"),
                    action = "predefined",
                    rcode = "NOERROR"
                )
            )
        }
        return rules
    }

    internal override fun buildEchDnsRules(outbounds: List<Outbound>, serverTag: String): List<DnsRule> {
        val queryServerNames = outbounds
            .mapNotNull { it.tls?.ech?.queryServerName?.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (queryServerNames.isEmpty()) {
            return emptyList()
        }
        return listOf(
            DnsRule(
                action = "route",
                domain = queryServerNames,
                queryType = listOf("HTTPS", "SVCB"),
                server = serverTag
            )
        )
    }

    internal override fun buildNonIpDnsFallbackRule(serverTag: String): DnsRule {
        return DnsRule(
            action = "route",
            queryType = NON_IP_DNS_QUERY_TYPES,
            server = serverTag
        )
    }

    internal override fun buildDnsRouteToDirect(
        serverTag: String,
        directServerTag: String,
        rule: DnsRule
    ): DnsRule {
        val routeRule = if (serverTag == directServerTag && rule.queryType == null) {
            rule.copy(queryType = IP_DNS_QUERY_TYPES)
        } else {
            rule
        }
        return routeRule.copy(action = "route", server = serverTag)
    }

    internal override fun buildDnsRouteToNonDirect(
        fakeDnsEnabled: Boolean,
        serverTag: String,
        rule: DnsRule
    ): List<DnsRule> {
        fun dnsRouteTo(server: String, currentRule: DnsRule): DnsRule =
            currentRule.copy(action = "route", server = server)

        if (!fakeDnsEnabled) {
            return listOf(dnsRouteTo(serverTag, rule))
        }
        return listOf(dnsRouteTo(serverTag, rule.copy(queryType = IP_DNS_QUERY_TYPES)))
    }

    internal override fun sortRuleSetsForDnsAndRoutePriority(ruleSets: List<RuleSet>): List<RuleSet> {
        return ruleSets.sortedWith(
            compareBy(
                { ruleSet ->
                    when {
                        ruleSet.tag == "geolocation-!cn" -> 200
                        ruleSet.tag == "geolocation-cn" -> 199
                        ruleSet.tag == "!cn" || ruleSet.tag.endsWith("-!cn") -> 198
                        ruleSet.tag.matches(Regex("^geo(site|ip)-[a-z]{2}$")) -> 100
                        else -> 0
                    }
                },
                { ruleSet ->
                    when (resolveRuleSetOutboundMode(ruleSet.outboundMode)) {
                        RuleSetOutboundMode.NODE -> 0
                        RuleSetOutboundMode.PROXY -> 1
                        RuleSetOutboundMode.DIRECT -> 2
                        RuleSetOutboundMode.BLOCK -> 3
                        RuleSetOutboundMode.PROFILE -> 1
                    }
                }
            )
        )
    }

    internal override fun resolveProxyDnsDetourTagForTest(
        selectorTag: String,
        outbounds: List<Outbound>
    ): String {
        fun resolveCurrent(tag: String): String {
            val outbound = outbounds.firstOrNull { it.tag == tag } ?: return tag
            return when (outbound.type) {
                "selector" -> resolveCurrent(outbound.default ?: outbound.outbounds?.firstOrNull() ?: tag)
                "urltest", "url-test" -> resolveCurrent(outbound.outbounds?.firstOrNull() ?: tag)
                else -> tag
            }
        }
        return resolveCurrent(selectorTag)
    }

    internal override fun resolveRunDnsFinalServerForTest(
        routingMode: RoutingMode,
        defaultRule: DefaultRule,
        fakeDnsEnabled: Boolean,
        proxyServerTag: String,
        stableRemoteServerTag: String,
        directServerTag: String): String {
        return when (routingMode) {
            RoutingMode.GLOBAL_PROXY -> stableRemoteServerTag
            RoutingMode.GLOBAL_DIRECT -> directServerTag
            RoutingMode.RULE -> when (defaultRule) {
                DefaultRule.PROXY -> proxyServerTag
                DefaultRule.DIRECT -> directServerTag
                DefaultRule.BLOCK -> if (fakeDnsEnabled) stableRemoteServerTag else proxyServerTag
            }
        }
    }

    internal override fun sanitizeInjectedDnsServerForTest(
        server: DnsServer,
        routingMode: RoutingMode,
        proxyDetourTag: String,
        directDnsServerTags: Set<String>): DnsServer {
        return sanitizeInjectedDnsServerForRuntime(server, routingMode, proxyDetourTag, directDnsServerTags)
    }

    internal override fun sanitizeInjectedDnsServerForRuntime(
        server: DnsServer,
        routingMode: RoutingMode,
        proxyDetourTag: String,
        directDnsServerTags: Set<String>): DnsServer {
        val normalizedServer = normalizeInjectedDnsServer(server)
        val serverTag = normalizedServer.tag?.trim().orEmpty()
        val t = normalizedServer.type?.lowercase().orEmpty()
        val shouldKeepDirect = routingMode == RoutingMode.GLOBAL_DIRECT ||
            (serverTag.isNotBlank() && serverTag in directDnsServerTags)
        val shouldPreserve = shouldKeepDirect ||
            !normalizedServer.detour.isNullOrBlank() ||
            t == "fakeip" ||
            t == "local" ||
            t == "dhcp"
        return if (shouldPreserve) normalizedServer else normalizedServer.copy(detour = proxyDetourTag)
    }

    internal override fun normalizeInjectedDnsServer(server: DnsServer): DnsServer {
        val tag = server.tag?.trim().orEmpty()
        val address = server.address?.trim().orEmpty()
        val hasNewEndpoint = !server.type.isNullOrBlank() || !server.server.isNullOrBlank()
        if (tag.isBlank() || address.isBlank() || hasNewEndpoint) {
            return server
        }

        val domainResolver = server.domainResolver ?: server.addressResolver
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { DomainResolveConfig(server = it) }
        return buildDnsServer(
            address = address,
            tag = tag,
            detour = server.detour,
            domainStrategy = server.domainStrategy ?: server.strategy,
            domainResolver = domainResolver
        ).copy(
            udpFragment = server.udpFragment,
            networkStrategy = server.networkStrategy,
            networkType = server.networkType,
            fallbackNetworkType = server.fallbackNetworkType,
            fallbackDelay = server.fallbackDelay,
            inet4Range = server.inet4Range,
            inet6Range = server.inet6Range,
            headers = server.headers,
            tls = server.tls
        )
    }

    internal override fun applyDnsOverrideForTest(
        baseConfig: DnsConfig,
        overrideConfig: DnsConfig,
        sanitizeServer: (DnsServer) -> DnsServer): DnsConfig {
        return applyDnsOverride(baseConfig, overrideConfig, sanitizeServer)
    }

    internal override fun parseDnsOverrideForTest(dnsOverride: String?): DnsConfig? {
        return parseDnsOverrideConfig(dnsOverride)
    }

    internal override fun buildDnsOverrideCompatibilityWarningForTest(dnsOverride: String?): String? {
        return buildDnsOverrideCompatibilityWarning(dnsOverride)
    }

    override fun buildDnsOverrideCompatibilityWarning(dnsOverride: String?): String? {
        val trimmed = dnsOverride?.trim().orEmpty()
        if (trimmed.isBlank()) return null

        val dnsObject = parseDnsOverrideObjectForWarning(trimmed)
        return when {
            dnsObject == null -> "DNS 覆写无法解析，请检查 JSON 格式；KunBox 无法保证兼容。"
            !hasDnsOverrideShape(dnsObject) -> "DNS 覆写无法解析，请使用包含 dns/servers/rules 的 JSON 对象。"
            else -> formatDnsOverrideCompatibilityWarning(collectDnsOverrideCompatibilityIssues(dnsObject))
        }
    }

    internal override fun parseDnsOverrideObjectForWarning(dnsOverride: String): JsonObject? {
        return try {
            extractDnsOverrideJsonObject(dnsOverride)
        } catch (_: Exception) {
            null
        }
    }

    internal override fun hasDnsOverrideShape(dnsObject: JsonObject): Boolean {
        return dnsOverrideKeys().any { dnsObject.has(it) }
    }

    internal override fun collectDnsOverrideCompatibilityIssues(dnsObject: JsonObject): Set<String> {
        val issues = linkedSetOf<String>()
        val definedServerTags = knownDnsServerTags().toMutableSet()
        collectDnsServerCompatibilityIssues(dnsObject, definedServerTags, issues)
        collectDnsRuleCompatibilityIssues(dnsObject, definedServerTags, issues)
        collectDnsTopLevelCompatibilityIssues(dnsObject, issues)
        return issues
    }

    internal override fun collectDnsServerCompatibilityIssues(
        dnsObject: JsonObject,
        definedServerTags: MutableSet<String>,
        issues: MutableSet<String>
    ) {
        val servers = dnsObject.get("servers") ?: return
        if (!servers.isJsonArray) {
            issues.add("dns.servers 不是数组")
            return
        }
        val overrideTags = linkedSetOf<String>()
        servers.asJsonArray.forEach { element ->
            collectSingleDnsServerCompatibilityIssues(
                asJsonObjectOrNull(element),
                definedServerTags,
                overrideTags,
                issues
            )
        }
    }
}

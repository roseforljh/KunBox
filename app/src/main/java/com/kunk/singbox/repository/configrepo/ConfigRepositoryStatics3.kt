@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.repository

import android.util.Log
import com.google.gson.JsonPrimitive
import com.kunk.singbox.database.entity.ProfileEntity
import com.kunk.singbox.model.*
import com.kunk.singbox.repository.config.InboundBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun ConfigRepository.Companion.requireValidRootApplicationRoutes(
    config: SingBoxConfig,
    plan: RootAppRoutingPlan
) {
    val inboundTags = config.inbounds.orEmpty().mapNotNullTo(mutableSetOf(), Inbound::tag)
    val outboundTags = config.outbounds.orEmpty().mapTo(mutableSetOf(), Outbound::tag) +
        config.endpoints.orEmpty().map(Endpoint::tag)
    val dnsServerTags = config.dns?.servers.orEmpty().mapNotNullTo(mutableSetOf(), DnsServer::tag)
    val routeRules = config.route?.rules.orEmpty()
    val dnsRules = config.dns?.rules.orEmpty()
    require(routeRules.none { !it.packageName.isNullOrEmpty() }) {
        "Root 运行配置仍含 package_name 路由，已阻止不确定应用分流"
    }
    require(dnsRules.none { !it.packageName.isNullOrEmpty() }) {
        "Root 运行配置仍含 package_name DNS 规则，已阻止不确定应用分流"
    }
    plan.lanes.forEach { lane ->
        val laneInbounds = lane.inboundTags(plan.proxyIpv4, plan.proxyIpv6)
        requireValidRootLaneRoute(lane, laneInbounds, inboundTags, outboundTags, routeRules)
        requireValidRootLaneDns(
            lane,
            laneInbounds,
            dnsServerTags,
            dnsRules,
            config.dns?.fakeip != null
        )
    }
    require(plan.staticPlanSha256 == RootAppRoutingCanonical.staticPlanSha256(plan)) {
        "Root static plan digest mismatch"
    }
    require(plan.appRoutingSha256 == RootAppRoutingCanonical.appRoutingSha256(plan)) {
        "Root app routing digest mismatch"
    }
}

internal fun ConfigRepository.Companion.requireValidRootLaneRoute(
    lane: RootAppRouteLane,
    laneInbounds: List<String>,
    inboundTags: Set<String>,
    outboundTags: Set<String>,
    routeRules: List<RouteRule>
) {
    require(laneInbounds.isNotEmpty() && laneInbounds.all(inboundTags::contains)) {
        "Root lane ${lane.laneId} 缺少 inbound"
    }
    val expected = if (lane.targetKind == "BLOCK") {
        RouteRule(inbound = laneInbounds, action = "reject")
    } else {
        RouteRule(inbound = laneInbounds, action = "route", outbound = lane.outboundTag)
    }
    require(routeRules.count(expected::equals) == 1) {
        "Root lane ${lane.laneId} 缺少唯一且完整的 TCP/UDP 路由规则"
    }
    if (lane.targetKind != "BLOCK") {
        require(lane.outboundTag == "direct" || lane.outboundTag in outboundTags) {
            "Root lane ${lane.laneId} 目标 ${lane.outboundTag} 不存在"
        }
    }
}

internal fun ConfigRepository.Companion.requireValidRootLaneDns(
    lane: RootAppRouteLane,
    laneInbounds: List<String>,
    dnsServerTags: Set<String>,
    dnsRules: List<DnsRule>,
    fakeDnsEnabled: Boolean
) {
    val expectedDnsServer = when (lane.targetKind) {
        "DIRECT" -> "local"
        "OUTBOUND" -> buildDynamicDnsServerTag(lane.outboundTag)
        else -> null
    }
    val expected = if (lane.targetKind == "BLOCK") {
        DnsRule(inbound = laneInbounds, action = "predefined", rcode = JsonPrimitive("NOERROR"))
    } else {
        DnsRule(
            inbound = laneInbounds,
            action = "route",
            server = expectedDnsServer,
            queryType = IP_DNS_QUERY_TYPES.takeIf { fakeDnsEnabled && lane.targetKind == "OUTBOUND" }
        )
    }
    require(dnsRules.count(expected::equals) == 1) {
        "Root lane ${lane.laneId} 缺少唯一且完整的 DNS 规则"
    }
    if (expectedDnsServer != null) {
        require(expectedDnsServer in dnsServerTags) {
            "Root lane ${lane.laneId} DNS server 不存在"
        }
    }
}

internal fun ConfigRepository.Companion.toRootAppRoutingAssignment(
    packageNames: List<String>,
    semantic: ConfigRepository.OutboundSemantic,
    selectorTag: String,
    sourceLabel: String
): RootAppRoutingAssignment = when (semantic) {
    ConfigRepository.OutboundSemantic.Direct -> RootAppRoutingAssignment(
        packageNames = packageNames,
        targetKind = "DIRECT",
        outboundTag = "direct",
        sourceLabel = sourceLabel
    )
    ConfigRepository.OutboundSemantic.Block -> RootAppRoutingAssignment(
        packageNames = packageNames,
        targetKind = "BLOCK",
        routeAction = "reject",
        sourceLabel = sourceLabel
    )
    ConfigRepository.OutboundSemantic.Proxy -> RootAppRoutingAssignment(
        packageNames = packageNames,
        targetKind = "OUTBOUND",
        outboundTag = selectorTag,
        sourceLabel = sourceLabel
    )
    is ConfigRepository.OutboundSemantic.RouteTag -> RootAppRoutingAssignment(
        packageNames = packageNames,
        targetKind = "OUTBOUND",
        outboundTag = semantic.tag,
        sourceLabel = sourceLabel
    )
    is ConfigRepository.OutboundSemantic.FallbackProxy -> error(
        "Root app route cannot use fallback outbound: ${semantic.tag}"
    )
}

internal fun ConfigRepository.Companion.rootLaneSemantic(lane: RootAppRouteLane): ConfigRepository.OutboundSemantic = when {
    lane.targetKind == "DIRECT" -> ConfigRepository.OutboundSemantic.Direct
    lane.targetKind == "BLOCK" -> ConfigRepository.OutboundSemantic.Block
    lane.targetKind == "OUTBOUND" && lane.outboundTag.isNotBlank() ->
        ConfigRepository.OutboundSemantic.RouteTag(lane.outboundTag)
    else -> error("Invalid Root lane target: ${lane.laneId}")
}

internal fun ConfigRepository.Companion.buildRunRouteRulesForTest(
    settings: AppSettings,
    selectorTag: String,
    outbounds: List<Outbound>,
    profiles: List<ProfileEntity>,
    validRuleSets: List<RuleSetConfig>,
    nodeTagResolver: (String?) -> String? = { null }): List<RouteRule> {
    val profileUis = profiles.map { it.toUiModel() }
    return buildRunRouteRules(
        settings = settings,
        selectorTag = selectorTag,
        outbounds = outbounds,
        profiles = profileUis,
        nodeTagResolver = nodeTagResolver,
        validRuleSets = validRuleSets
    )
}

internal fun ConfigRepository.Companion.captureInboundTags(
    settings: AppSettings,
    rootRoutingPlan: RootAppRoutingPlan? = null
): List<String> = when (settings.resolvedTrafficCaptureMode()) {
    TrafficCaptureMode.VPN -> listOf("tun-in")
    TrafficCaptureMode.ROOT_TRANSPARENT -> buildList {
        if (settings.ipVersionMode != IpVersionMode.IPV6_ONLY) {
            add(InboundBuilder.ROOT_REDIRECT_TAG_IPV4)
            add(InboundBuilder.ROOT_TPROXY_TAG_IPV4)
        }
        if (settings.ipVersionMode != IpVersionMode.IPV4_ONLY) {
            add(InboundBuilder.ROOT_REDIRECT_TAG_IPV6)
            add(InboundBuilder.ROOT_TPROXY_TAG_IPV6)
        }
        rootRoutingPlan?.let { plan ->
            plan.lanes.forEach { lane ->
                addAll(lane.inboundTags(plan.proxyIpv4, plan.proxyIpv6))
            }
        }
    }
    TrafficCaptureMode.PROXY_ONLY -> emptyList()
}

internal fun ConfigRepository.Companion.buildHijackDnsRulesStatic(
    settings: AppSettings = AppSettings(),
    rootRoutingPlan: RootAppRoutingPlan? = null
): List<RouteRule> {
    // sing-box 1.13 的 sniff 是非终止动作，协议规则必须位于其后；TUN 53 端口保留前置劫持。
    val captureTags = captureInboundTags(settings, rootRoutingPlan)
    val sniffInboundTags = if (settings.resolvedTrafficCaptureMode() == TrafficCaptureMode.ROOT_TRANSPARENT) {
        captureTags
    } else {
        (captureTags + "mixed-in").distinct()
    }
    return listOfNotNull(
        captureTags.takeIf(List<String>::isNotEmpty)?.let {
            RouteRule(inbound = it, port = listOf(53), action = "hijack-dns")
        },
        RouteRule(inbound = sniffInboundTags, action = "sniff"),
        RouteRule(protocolRaw = listOf("dns"), action = "hijack-dns"),
        RouteRule(port = listOf(853), action = "reject")
    )
}

@Suppress("LongParameterList")
internal fun ConfigRepository.Companion.buildRunRouteRules(
    settings: AppSettings,
    selectorTag: String,
    outbounds: List<Outbound>,
    profiles: List<ProfileUi>,
    nodeTagResolver: (String?) -> String?,
    validRuleSets: List<RuleSetConfig>
): List<RouteRule> {
    val customRuleSetRules = buildCustomRuleSetRulesStatic(
        settings = settings,
        defaultProxyTag = selectorTag,
        outbounds = outbounds,
        profiles = profiles,
        nodeTagResolver = nodeTagResolver,
        validRuleSets = validRuleSets
    )
    val quicRule = buildQuicBlockRuleStatic(settings)
    val webRtcLeakProtectionRules = buildWebRtcLeakProtectionRulesStatic()
    val multicastRejectRules = buildMulticastRejectRulesStatic(settings)
    val bypassLanRules = buildBypassLanRulesStatic(settings)
    val icmpEchoRules = buildIcmpEchoRulesStatic(settings)
    val defaultRuleCatchAll = buildDefaultRulesStatic(settings, selectorTag)
    val hijackDnsRule = buildHijackDnsRulesStatic(settings)
    return selectRunRouteRulesStatic(
        settings = settings,
        baseRules = multicastRejectRules + hijackDnsRule + webRtcLeakProtectionRules + quicRule +
            icmpEchoRules,
        bypassLanRules = bypassLanRules,
        customDomainRules = emptyList(),
        appRoutingRules = emptyList(),
        customRuleSetRules = customRuleSetRules,
        defaultRuleCatchAll = defaultRuleCatchAll
    )
}

internal fun ConfigRepository.Companion.buildQuicBlockRuleStatic(settings: AppSettings): List<RouteRule> {
    return if (settings.blockQuic) {
        listOf(RouteRule(protocolRaw = listOf("quic"), action = "reject"))
    } else {
        emptyList()
    }
}

internal fun ConfigRepository.Companion.buildWebRtcLeakProtectionRulesStatic(): List<RouteRule> {
    return listOf(RouteRule(protocolRaw = listOf("stun"), action = "reject"))
}

internal fun ConfigRepository.Companion.buildIcmpEchoRulesStatic(settings: AppSettings): List<RouteRule> {
    if (!settings.icmpEchoRoutingEnabled) return emptyList()

    return when (settings.routingMode) {
        RoutingMode.GLOBAL_DIRECT -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
        RoutingMode.GLOBAL_PROXY -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
        RoutingMode.RULE -> when (settings.defaultRule) {
            DefaultRule.DIRECT -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
            DefaultRule.BLOCK -> listOf(RouteRule(networkRaw = listOf("icmp"), action = "reject"))
            DefaultRule.PROXY -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
        }
    }
}

internal fun ConfigRepository.Companion.buildDefaultRulesStatic(settings: AppSettings, selectorTag: String): List<RouteRule> {
    return when (settings.defaultRule) {
        DefaultRule.DIRECT -> listOf(RouteRule(outbound = "direct"))
        DefaultRule.BLOCK -> listOf(RouteRule(action = "reject"))
        DefaultRule.PROXY -> listOf(RouteRule(outbound = selectorTag))
    }
}

/**
 * 规则集匹配顺序：特定服务 > 通用集 > 国家/地区泛化 > geolocation 泛化。
 * 同级保持用户拖拽顺序（stable sort），避免 geosite-geolocation-!cn 抢先吞掉 openai/google 等专项规则。
 */
internal fun ConfigRepository.Companion.sortRuleSetsForRouting(ruleSets: List<RuleSet>): List<RuleSet> {
    return ruleSets.sortedBy { ruleSet ->
        val tag = ruleSet.tag.trim().lowercase()
        when {
            tag.contains("geolocation-!cn") || tag.contains("geolocation_!cn") -> 200
            tag.contains("geolocation-cn") || tag.contains("geolocation_cn") -> 199
            tag.contains("!cn") -> 198
            tag.matches(Regex("^geo(site|ip)-[a-z]{2}$")) -> 100
            tag.contains("private") || tag.contains("category-ads") -> 50
            else -> 0
        }
    }
}

internal fun ConfigRepository.Companion.buildCustomRuleSetRulesStatic(
    settings: AppSettings,
    defaultProxyTag: String,
    outbounds: List<Outbound>,
    profiles: List<ProfileUi>,
    nodeTagResolver: (String?) -> String? = { null },
    validRuleSets: List<RuleSetConfig>
): List<RouteRule> {
    val rules = mutableListOf<RouteRule>()
    val validTags = validRuleSets.mapNotNull { it.tag }.toSet()
    val orderedRuleSets = sortRuleSetsForRouting(
        settings.ruleSets.filter { it.enabled && it.tag in validTags }
    )

    orderedRuleSets.forEach { ruleSet ->
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
        val inboundTags = normalizeRuleSetInboundTags(ruleSet.inbounds, settings)

        rules.add(
            baseRule.copy(
                ruleSet = listOf(ruleSet.tag),
                inbound = inboundTags
            )
        )
    }

    return rules
}

internal fun ConfigRepository.Companion.buildBypassLanRulesStatic(settings: AppSettings): List<RouteRule> {
    return if (settings.bypassLan) {
        listOf(RouteRule(ipIsPrivate = true, outbound = "direct"))
    } else {
        emptyList()
    }
}

internal fun ConfigRepository.Companion.buildMulticastRejectRulesStatic(settings: AppSettings): List<RouteRule> {
    val cidrs = mutableListOf<String>()
    if (settings.ipVersionMode.includesIpv4) cidrs.add("224.0.0.0/3")
    if (settings.ipVersionMode.includesIpv6) cidrs.add("ff00::/8")
    return if (cidrs.isEmpty()) {
        emptyList()
    } else {
        listOf(RouteRule(ipCidr = cidrs, action = "reject"))
    }
}

internal fun ConfigRepository.Companion.resolveOutboundSemantic(
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
            val tag = buildProfileRouteTag(profileId, profileName)
            if (outbounds.any { it.tag == tag }) {
                ConfigRepository.OutboundSemantic.RouteTag(tag)
            } else {
                Log.w(TAG, "Profile selector tag '$tag' not found in outbounds, falling back to $selectorTag")
                ConfigRepository.OutboundSemantic.FallbackProxy(selectorTag)
            }
        }
    }
}

internal fun ConfigRepository.Companion.resolveOutboundSemanticForTest(
    input: ConfigRepository.OutboundSemanticTestInput
): ConfigRepository.OutboundSemantic {
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

internal fun ConfigRepository.Companion.buildDynamicDnsServerTag(detourTag: String): String {
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

internal fun ConfigRepository.Companion.ensureDynamicRemoteDnsServers(
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

internal fun ConfigRepository.Companion.buildDynamicDnsServersForTest(
    semantics: List<ConfigRepository.OutboundSemantic>,
    remoteDnsAddr: String,
    remoteStrategy: String?,
    remoteResolver: DomainResolveConfig?
): List<DnsServer> {
    val servers = mutableListOf<DnsServer>()
    ensureDynamicRemoteDnsServers(servers, semantics, remoteDnsAddr, remoteStrategy, remoteResolver)
    return servers
}

internal fun ConfigRepository.Companion.buildDynamicRemoteDnsServer(
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

internal fun ConfigRepository.Companion.resolveActiveEchDnsServer(activeTag: String, outbounds: List<Outbound>): String? {
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

internal fun ConfigRepository.Companion.needsLegacyEchDnsRepair(config: SingBoxConfig): Boolean {
    return config.outbounds.orEmpty().any { outbound ->
        val ech = outbound.tls?.ech ?: return@any false
        val hasEch = ech.enabled == true ||
            !ech.queryServerName.isNullOrBlank() ||
            !ech.config.isNullOrEmpty()
        hasEch && ech.dnsServer.isNullOrBlank() && ech.config.isNullOrEmpty()
    }
}

internal fun ConfigRepository.Companion.resolveFakeIpRanges(fakeIpRange: String?): ConfigRepositoryFakeIpRanges {
    val ranges = fakeIpRange.orEmpty()
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val inet4Range = ranges.firstOrNull { it.contains(".") } ?: "198.18.0.0/15"
    val inet6Range = ranges.firstOrNull { it.contains(":") } ?: "fc00::/18"
    return ConfigRepositoryFakeIpRanges(inet4Range = inet4Range, inet6Range = inet6Range)
}

internal fun ConfigRepository.Companion.buildFakeIpDnsServer(fakeIpRange: String?): DnsServer {
    val ranges = resolveFakeIpRanges(fakeIpRange)
    return DnsServer(
        tag = "fakeip-dns",
        type = "fakeip",
        inet4Range = ranges.inet4Range,
        inet6Range = ranges.inet6Range
    )
}

internal fun ConfigRepository.Companion.buildFakeIpConfig(fakeIpRange: String?): DnsFakeIpConfig {
    val ranges = resolveFakeIpRanges(fakeIpRange)
    return DnsFakeIpConfig(
        enabled = true,
        inet4Range = ranges.inet4Range,
        inet6Range = ranges.inet6Range
    )
}

internal fun ConfigRepository.Companion.dnsServerTagForSemantic(
    semantic: ConfigRepository.OutboundSemantic,
    fakeDnsEnabled: Boolean,
    directServerTag: String = "local",
    proxyServerTag: String = if (fakeDnsEnabled) "fakeip-dns" else "remote"): String? {
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

internal fun ConfigRepository.Companion.buildTunFakeIpDnsRulesStatic(
    fakeDnsEnabled: Boolean,
    settings: AppSettings = AppSettings()
): List<DnsRule> {
    if (!fakeDnsEnabled) return emptyList()
    val captureTags = captureInboundTags(settings).takeIf(List<String>::isNotEmpty) ?: return emptyList()
    return listOf(
        DnsRule(
            queryType = listOf("A", "AAAA"),
            inbound = captureTags,
            action = "route",
            server = "fakeip-dns"
        )
    )
}

internal fun ConfigRepository.Companion.buildOutboundDomainResolverDnsRules(outbounds: List<Outbound>): List<DnsRule> {
    val domainToResolver = linkedMapOf<String, DomainResolveConfig>()
    outbounds.forEach { outbound ->
        val domain = outbound.server
            ?.trim()
            ?.takeIf { it.isNotBlank() && !isIpAddressValue(it) }
            ?.let { normalizeDnsRuleDomain(it) }
            ?: return@forEach
        val resolver = outbound.domainResolver ?: return@forEach
        val resolverServer = resolver
            .server
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

internal fun ConfigRepository.Companion.applyDefaultOutboundDomainResolver(
    outbounds: List<Outbound>,
    defaultResolverTag: String,
    defaultResolverStrategy: String? = null): List<Outbound> {
    return outbounds.map { outbound ->
        val server = outbound.server?.trim().orEmpty()
        if (server.isBlank() || isIpAddressValue(server)) return@map outbound

        val existing = outbound.domainResolver
        val existingServer = existing?.server
        if (!existingServer.isNullOrBlank() && existingServer != DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG) {
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

internal fun ConfigRepository.Companion.buildEchAwareHttpsSvcbDnsRules(
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
                rcode = JsonPrimitive("NOERROR")
            )
        )
    }
    return rules
}

internal fun ConfigRepository.Companion.buildEchDnsRules(outbounds: List<Outbound>, serverTag: String): List<DnsRule> {
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

internal fun ConfigRepository.Companion.buildDnsRouteToDirect(
    serverTag: String,
    rule: DnsRule
): DnsRule {
    return rule.copy(action = "route", server = serverTag)
}

internal fun ConfigRepository.Companion.buildDnsRouteToNonDirect(
    fakeDnsEnabled: Boolean,
    serverTag: String,
    rule: DnsRule
): List<DnsRule> {
    fun dnsRouteTo(server: String, currentRule: DnsRule): DnsRule =
        currentRule.copy(action = "route", server = server)

    val routedRule = if (fakeDnsEnabled) {
        rule.copy(queryType = IP_DNS_QUERY_TYPES)
    } else {
        rule
    }
    return listOf(dnsRouteTo(serverTag, routedRule))
}

internal fun ConfigRepository.Companion.buildOrderedDnsRules(
    entries: List<Pair<DnsRule, ConfigRepository.OutboundSemantic>>,
    fakeDnsEnabled: Boolean,
    directServerTag: String,
    proxyServerTag: String
): List<DnsRule> = buildList {
    entries.forEach { (rule, semantic) ->
        if (semantic == ConfigRepository.OutboundSemantic.Block) {
            add(rule.copy(action = "predefined", rcode = JsonPrimitive("NOERROR")))
            return@forEach
        }
        val serverTag = dnsServerTagForSemantic(
            semantic = semantic,
            fakeDnsEnabled = fakeDnsEnabled,
            directServerTag = directServerTag,
            proxyServerTag = proxyServerTag
        ) ?: return@forEach
        if (serverTag == directServerTag) {
            add(buildDnsRouteToDirect(serverTag, rule))
        } else {
            addAll(buildDnsRouteToNonDirect(fakeDnsEnabled, serverTag, rule))
        }
    }
}

internal fun ConfigRepository.Companion.buildCustomDnsRuleMatcher(type: RuleType, values: List<String>): DnsRule? {
    return when (type) {
        RuleType.DOMAIN -> DnsRule(domain = values)
        RuleType.DOMAIN_SUFFIX -> DnsRule(domainSuffix = values)
        RuleType.DOMAIN_KEYWORD -> DnsRule(domainKeyword = values)
        RuleType.GEOSITE -> DnsRule(geosite = values)
        else -> null
    }
}

internal fun ConfigRepository.Companion.resolveProxyDnsDetourTagForTest(
    selectorTag: String,
    outbounds: List<Outbound> = emptyList()
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

internal fun ConfigRepository.Companion.resolveRunDnsFinalServerForTest(
    routingMode: RoutingMode,
    defaultRule: DefaultRule,
    fakeDnsEnabled: Boolean,
    proxyServerTag: String,
    stableRemoteServerTag: String = "remote",
    directServerTag: String = "local"): String {
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

internal fun ConfigRepository.Companion.sanitizeInjectedDnsServerForRuntime(
    server: DnsServer,
    routingMode: RoutingMode,
    proxyDetourTag: String,
    directDnsServerTags: Set<String> = emptySet()): DnsServer {
    val normalizedServer = normalizeInjectedDnsServer(server)
    val serverTag = normalizedServer.tag?.trim().orEmpty()
    val t = normalizedServer.type?.lowercase().orEmpty()
    val shouldKeepDirect = routingMode == RoutingMode.GLOBAL_DIRECT ||
        (serverTag.isNotBlank() && serverTag in directDnsServerTags)
    val shouldPreserve = shouldKeepDirect ||
        !normalizedServer.detour.isNullOrBlank() ||
        t in setOf("fakeip", "local", "hosts", "dhcp", "resolved")
    return if (shouldPreserve) normalizedServer else normalizedServer.copy(detour = proxyDetourTag)
}

internal fun ConfigRepository.Companion.normalizeInjectedDnsServer(server: DnsServer): DnsServer {
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

@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.repository

import android.util.Log
import com.google.gson.JsonPrimitive
import com.kunk.singbox.model.*
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal val DEFAULT_WIREGUARD_ALLOWED_IPS = listOf("0.0.0.0/0", "::/0")

internal fun ConfigRepository.Companion.normalizeWireGuardPeersForRuntime(peers: List<WireGuardPeer>?): List<WireGuardPeer>? {
    if (peers.isNullOrEmpty()) return peers
    return peers.map { peer ->
        if (!peer.allowedIps.isNullOrEmpty()) {
            peer
        } else {
            peer.copy(allowedIps = DEFAULT_WIREGUARD_ALLOWED_IPS)
        }
    }
}

internal fun ConfigRepository.Companion.convertWireGuardOutboundToEndpoint(
    outbound: Outbound,
    runtimeTag: String = outbound.tag
): Endpoint? {
    if (!outbound.type.equals("wireguard", ignoreCase = true)) return null
    return Endpoint(
        type = "wireguard",
        tag = runtimeTag,
        system = outbound.system,
        name = outbound.endpointName,
        mtu = outbound.mtu,
        address = outbound.localAddress,
        privateKey = outbound.privateKey?.firstOrNull(),
        listenPort = outbound.listenPort,
        peers = normalizeWireGuardPeersForRuntime(outbound.peers),
        udpTimeout = outbound.udpTimeout,
        workers = outbound.workers,
        detour = outbound.detour,
        bindInterface = outbound.bindInterface,
        inet4BindAddress = outbound.inet4BindAddress,
        inet6BindAddress = outbound.inet6BindAddress,
        bindAddressNoPort = outbound.bindAddressNoPort,
        protectPath = outbound.protectPath,
        routingMark = outbound.routingMark,
        reuseAddr = outbound.reuseAddr,
        netns = outbound.netns,
        connectTimeout = outbound.connectTimeout,
        tcpFastOpen = outbound.tcpFastOpen,
        tcpMultiPath = outbound.tcpMultiPath,
        disableTcpKeepAlive = outbound.disableTcpKeepAlive,
        tcpKeepAlive = outbound.tcpKeepAlive,
        tcpKeepAliveInterval = outbound.tcpKeepAliveInterval,
        udpFragment = outbound.udpFragment,
        networkStrategy = outbound.networkStrategy,
        networkType = outbound.networkType,
        fallbackNetworkType = outbound.fallbackNetworkType,
        fallbackDelay = outbound.fallbackDelay,
        domainStrategy = outbound.domainStrategy,
        domainResolver = outbound.domainResolver
    )
}

internal fun ConfigRepository.Companion.convertWireGuardEndpointToOutbound(endpoint: Endpoint): Outbound? {
    if (!endpoint.type.equals("wireguard", ignoreCase = true)) return null
    return Outbound(
        type = "wireguard",
        tag = endpoint.tag,
        system = endpoint.system,
        endpointName = endpoint.name,
        mtu = endpoint.mtu,
        localAddress = endpoint.address,
        privateKey = endpoint.privateKey?.let(::listOf),
        listenPort = endpoint.listenPort,
        peers = normalizeWireGuardPeersForRuntime(endpoint.peers),
        udpTimeout = endpoint.udpTimeout,
        workers = endpoint.workers,
        detour = endpoint.detour,
        bindInterface = endpoint.bindInterface,
        inet4BindAddress = endpoint.inet4BindAddress,
        inet6BindAddress = endpoint.inet6BindAddress,
        bindAddressNoPort = endpoint.bindAddressNoPort,
        protectPath = endpoint.protectPath,
        routingMark = endpoint.routingMark,
        reuseAddr = endpoint.reuseAddr,
        netns = endpoint.netns,
        connectTimeout = endpoint.connectTimeout,
        tcpFastOpen = endpoint.tcpFastOpen,
        tcpMultiPath = endpoint.tcpMultiPath,
        disableTcpKeepAlive = endpoint.disableTcpKeepAlive,
        tcpKeepAlive = endpoint.tcpKeepAlive,
        tcpKeepAliveInterval = endpoint.tcpKeepAliveInterval,
        udpFragment = endpoint.udpFragment,
        networkStrategy = endpoint.networkStrategy,
        networkType = endpoint.networkType,
        fallbackNetworkType = endpoint.fallbackNetworkType,
        fallbackDelay = endpoint.fallbackDelay,
        domainStrategy = endpoint.domainStrategy,
        domainResolver = endpoint.domainResolver
    )
}

internal fun ConfigRepository.Companion.normalizeWireGuardEndpointsForInternalUse(config: SingBoxConfig): SingBoxConfig {
    val wireGuardEndpoints = config.endpoints.orEmpty()
        .mapNotNull(::convertWireGuardEndpointToOutbound)
    if (wireGuardEndpoints.isEmpty()) return config

    val outboundsByTag = linkedMapOf<String, Outbound>()
    config.outbounds.orEmpty().forEach { outboundsByTag[it.tag] = it }
    wireGuardEndpoints.forEach { endpointOutbound ->
        val existing = outboundsByTag[endpointOutbound.tag]
        if (existing == null || existing.type.equals("wireguard", ignoreCase = true)) {
            outboundsByTag[endpointOutbound.tag] = endpointOutbound
        }
    }
    val remainingEndpoints = config.endpoints.orEmpty()
        .filterNot { it.type.equals("wireguard", ignoreCase = true) }
        .takeIf(List<Endpoint>::isNotEmpty)
    return config.copy(
        endpoints = remainingEndpoints,
        outbounds = outboundsByTag.values.toList()
    )
}

internal fun ConfigRepository.Companion.mergeRuntimeEndpoints(
    convertedEndpoints: List<Endpoint>,
    existingEndpoints: List<Endpoint>
): List<Endpoint> {
    val byTag = linkedMapOf<String, Endpoint>()
    convertedEndpoints.filter { it.tag.isNotBlank() }.forEach { byTag[it.tag] = it }
    existingEndpoints.filter { it.tag.isNotBlank() }.forEach { byTag[it.tag] = it }
    return byTag.values.toList()
}

internal fun ConfigRepository.Companion.normalizeRuleSetInboundTags(
    inbounds: List<String>?,
    settings: AppSettings? = null
): List<String>? {
    return inbounds.orEmpty()
        .map(String::trim)
        .filter(String::isNotBlank)
        .flatMap {
            when (it) {
                "tun", "tun-in" -> settings?.let(::captureInboundTags).orEmpty().ifEmpty {
                    listOf("tun-in")
                }
                "mixed" -> listOf("mixed-in")
                else -> listOf(it)
            }
        }
        .distinct()
        .takeIf(List<String>::isNotEmpty)
}

internal fun ConfigRepository.Companion.mergeUserDnsRules(
    domainRules: List<DnsRule>,
    appRules: List<DnsRule>,
    ruleSetRules: List<DnsRule>
): List<DnsRule> = appRules + domainRules + ruleSetRules

internal fun ConfigRepository.Companion.shouldApplyCustomAndAppRules(routingMode: RoutingMode): Boolean {
    return routingMode == RoutingMode.RULE
}

internal fun ConfigRepository.Companion.shouldFilterCapturedPackages(settings: AppSettings): Boolean {
    return settings.resolvedTrafficCaptureMode() != TrafficCaptureMode.PROXY_ONLY
}

internal fun ConfigRepository.Companion.shouldApplyRuleSetRules(routingMode: RoutingMode): Boolean {
    return routingMode == RoutingMode.RULE
}

@Suppress("LongParameterList")
internal fun ConfigRepository.Companion.selectRunRouteRulesStatic(
    settings: AppSettings,
    baseRules: List<RouteRule>,
    bypassLanRules: List<RouteRule>,
    customDomainRules: List<RouteRule>,
    appRoutingRules: List<RouteRule>,
    customRuleSetRules: List<RouteRule>,
    defaultRuleCatchAll: List<RouteRule>
): List<RouteRule> {
    return when (settings.routingMode) {
        RoutingMode.GLOBAL_PROXY -> baseRules
        RoutingMode.GLOBAL_DIRECT -> baseRules + listOf(RouteRule(outbound = "direct"))
        RoutingMode.RULE -> baseRules + bypassLanRules + appRoutingRules + customDomainRules +
            customRuleSetRules + defaultRuleCatchAll
    }
}

internal fun ConfigRepository.Companion.routeTargetKey(rule: RouteRule): String = when {
    rule.action == "reject" -> "BLOCK"
    !rule.outbound.isNullOrBlank() -> "OUT:${rule.outbound}"
    else -> "ACTION:${rule.action.orEmpty()}"
}

internal fun ConfigRepository.Companion.sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

internal fun ConfigRepository.Companion.appRoutingDigest(settings: AppSettings): String {
    val canonical = buildString {
        append(settings.routingMode.name).append('\n')
        append(PerAppVpnPolicy.from(settings).digest()).append('\n')
        settings.appRules.sortedBy { it.id }.forEach { rule ->
            append("R:").append(rule.id).append(':').append(rule.enabled).append(':')
            append(rule.packageName.trim()).append(':').append(rule.outboundMode?.name.orEmpty()).append(':')
            append(rule.outboundValue.orEmpty()).append('\n')
        }
        settings.appGroups.sortedBy { it.id }.forEach { group ->
            append("G:").append(group.id).append(':').append(group.enabled).append(':')
            append(group.outboundMode?.name.orEmpty()).append(':')
            append(group.outboundValue.orEmpty())
            val packageNames = group.apps
                .map { it.packageName.trim() }
                .filter(String::isNotBlank)
                .sorted()
            packageNames.forEach { packageName -> append(':').append(packageName) }
            append('\n')
        }
    }
    return sha256(canonical)
}

internal fun ConfigRepository.Companion.resolveAppOutboundSemanticStrict(
    mode: RuleSetOutboundMode?,
    value: String?,
    context: ConfigRepositoryOutboundSemanticContext,
    label: String
): ConfigRepository.OutboundSemantic {
    val resolvedMode = mode ?: RuleSetOutboundMode.PROXY
    val semantic = resolveOutboundSemantic(resolvedMode, value, context)
    if (semantic is ConfigRepository.OutboundSemantic.FallbackProxy) {
        val target = value?.takeIf(String::isNotBlank) ?: "未选择"
        val targetType = when (resolvedMode) {
            RuleSetOutboundMode.NODE -> "单节点"
            RuleSetOutboundMode.PROFILE -> "配置"
            else -> resolvedMode.name
        }
        throw IllegalArgumentException(
            "$label 的${targetType}目标「$target」已失效，已阻止回退到全局代理。" +
                "请重新选择目标后再启动。"
        )
    }
    return semantic
}

internal fun ConfigRepository.Companion.requireValidApplicationRoutes(
    route: RouteConfig?,
    availableTags: Set<String>
) {
    val targetByPackage = mutableMapOf<String, String>()
    route?.rules.orEmpty().filter { !it.packageName.isNullOrEmpty() }.forEach { rule ->
        val target = routeTargetKey(rule)
        if (!rule.outbound.isNullOrBlank()) {
            require(rule.outbound == "direct" || rule.outbound in availableTags) {
                "应用分流目标 ${rule.outbound} 未进入最终运行配置，已阻止启动"
            }
        }
        rule.packageName.orEmpty().forEach { packageName ->
            val previous = targetByPackage.putIfAbsent(packageName, target)
            require(previous == null || previous == target) {
                "应用分流冲突：$packageName 同时指向 $previous 和 $target，已阻止启动"
            }
        }
    }
}

internal fun ConfigRepository.Companion.applyCustomRuleMatcher(
    baseRule: RouteRule,
    type: RuleType,
    values: List<String>
): RouteRule? {
    return when (type) {
        RuleType.DOMAIN -> baseRule.copy(domain = values)
        RuleType.DOMAIN_SUFFIX -> baseRule.copy(domainSuffix = values)
        RuleType.DOMAIN_KEYWORD -> baseRule.copy(domainKeyword = values)
        RuleType.IP_CIDR -> baseRule.copy(ipCidr = values)
        RuleType.GEOIP -> baseRule.copy(geoip = values)
        RuleType.GEOSITE -> baseRule.copy(geosite = values)
        RuleType.PROCESS_NAME -> baseRule.copy(processName = values)
        RuleType.PORT -> {
            val ports = values.mapNotNull { it.toIntOrNull()?.takeIf { port -> port in 1..65535 } }
            val ranges = values.mapNotNull(::normalizePortRange)
            if (ports.isEmpty() && ranges.isEmpty()) {
                null
            } else {
                baseRule.copy(
                    port = ports.distinct().takeIf(List<Int>::isNotEmpty),
                    portRange = ranges.distinct().takeIf(List<String>::isNotEmpty)
                )
            }
        }
    }
}

internal fun ConfigRepository.Companion.normalizePortRange(value: String): String? {
    val parts = value.trim().split(Regex("\\s*[:-]\\s*"), limit = 2)
    val start = parts.getOrNull(0)?.toIntOrNull()
    val end = parts.getOrNull(1)?.toIntOrNull()
    return when {
        parts.size != 2 || start == null || end == null -> null
        start !in 1..65535 || end !in start..65535 -> null
        else -> "$start:$end"
    }
}

internal fun ConfigRepository.Companion.buildFakeIpExcludeDnsRules(values: List<String>, serverTag: String): List<DnsRule> {
    val suffixLabels = setOf("arpa", "lan", "local", "localdomain")
    val exactDomains = mutableListOf<String>()
    val suffixDomains = mutableListOf<String>()
    values.forEach { rawValue ->
        val value = normalizeDnsRuleDomain(rawValue).removePrefix("*.").removePrefix(".")
        if (value.isBlank()) return@forEach
        val rawDomain = rawValue.trim()
        val explicitSuffix = rawDomain.startsWith("*.") || rawDomain.startsWith(".")
        if (explicitSuffix || value in suffixLabels) {
            suffixDomains.add(value)
        } else {
            exactDomains.add(value)
        }
    }
    return buildList {
        exactDomains.distinct().takeIf(List<String>::isNotEmpty)?.let {
            add(DnsRule(domain = it, action = "route", server = serverTag))
        }
        suffixDomains.distinct().takeIf(List<String>::isNotEmpty)?.let {
            add(DnsRule(domainSuffix = it, action = "route", server = serverTag))
        }
    }
}

internal fun ConfigRepository.Companion.buildDefaultDnsBlockRules(
    routingMode: RoutingMode,
    defaultRule: DefaultRule
): List<DnsRule> {
    return if (routingMode == RoutingMode.RULE && defaultRule == DefaultRule.BLOCK) {
        listOf(DnsRule(action = "predefined", rcode = JsonPrimitive("NOERROR")))
    } else {
        emptyList()
    }
}

internal fun ConfigRepository.Companion.resolveClashApiSecret(existing: String?, generator: () -> String): String {
    fun isValid(value: String): Boolean {
        return value.length >= 32 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    existing?.trim()?.takeIf(::isValid)?.let { return it }
    return generator().trim().also {
        require(isValid(it)) { "Generated Clash API secret is invalid" }
    }
}

// bootstrap 只认 localDns 的 IP；否则系统 DNS，避免用 remote 1.1.1.1 解域名
internal fun ConfigRepository.Companion.buildBootstrapDnsServer(
    localDnsAddress: String,
    tag: String,
    domainStrategy: String?
): DnsServer {
    val numericLocalAddress = localDnsAddress.takeIf { address ->
        extractHostFromAddress(address)?.let(::isIpAddressValue) == true
    }
    return if (numericLocalAddress != null) {
        buildDnsServer(
            address = numericLocalAddress,
            tag = tag,
            domainStrategy = domainStrategy
        ).copy(detour = null, domainResolver = null)
    } else {
        DnsServer(tag = tag, type = "local", domainStrategy = domainStrategy)
    }
}

internal fun ConfigRepository.Companion.buildProfileRouteGroupOutbounds(
    groupTag: String,
    nodeTags: List<String>,
    eligibleNodeTags: List<String> = nodeTags,
    testUrl: String,
    autoSelectionEnabled: Boolean = false,
    preferredNodeTag: String? = null
): List<Outbound> {
    val distinctNodeTags = nodeTags
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
    val distinctEligibleNodeTags = eligibleNodeTags
        .map { it.trim() }
        .filter { it.isNotBlank() && it in distinctNodeTags }
        .distinct()
    if (distinctNodeTags.isEmpty() || distinctEligibleNodeTags.isEmpty()) {
        return emptyList()
    }

    val autoTag = buildRouteGroupAutoTag(groupTag)
    val preferred = preferredNodeTag?.takeIf { it in distinctEligibleNodeTags }
        ?: distinctEligibleNodeTags.first()
    val automaticSelectionAvailable = autoSelectionEnabled
    val selector = Outbound(
        type = "selector",
        tag = groupTag,
        outbounds = if (automaticSelectionAvailable) {
            listOf(autoTag) + distinctEligibleNodeTags
        } else {
            distinctEligibleNodeTags
        },
        default = if (automaticSelectionAvailable) autoTag else preferred,
        interruptExistConnections = false
    )
    if (!automaticSelectionAvailable) {
        return listOf(selector)
    }
    return listOf(
        Outbound(
            type = "urltest",
            tag = autoTag,
            outbounds = distinctEligibleNodeTags,
            url = AppSettings.requireLatencyTestUrl(testUrl),
            interval = ROUTE_GROUP_AUTO_TEST_INTERVAL,
            tolerance = ROUTE_GROUP_AUTO_TEST_TOLERANCE,
            interruptExistConnections = false
        ),
        selector
    )
}

internal fun ConfigRepository.Companion.buildBootstrapDnsRules(
    serverAddresses: List<String>,
    bootstrapV4Tag: String,
    bootstrapV6Tag: String,
    bootstrapTag: String
): List<DnsRule> {
    val bootstrapDomains = serverAddresses
        .mapNotNull { extractHostFromAddress(it) }
        .map { it.trim() }
        .filter { it.isNotEmpty() && !isIpAddressValue(it) && !it.equals("local", ignoreCase = true) }
        .distinct()

    if (bootstrapDomains.isEmpty()) {
        return emptyList()
    }

    if (setOf(bootstrapV4Tag, bootstrapV6Tag, bootstrapTag).size == 1) {
        return listOf(
            DnsRule(
                domain = bootstrapDomains,
                action = "route",
                server = bootstrapTag
            )
        )
    }

    return listOf(
        DnsRule(
            domain = bootstrapDomains,
            queryType = listOf("A"),
            action = "route",
            server = bootstrapV4Tag
        ),
        DnsRule(
            domain = bootstrapDomains,
            queryType = listOf("AAAA"),
            action = "route",
            server = bootstrapV6Tag
        ),
        DnsRule(
            domain = bootstrapDomains,
            action = "route",
            server = bootstrapTag
        )
    )
}

internal fun ConfigRepository.Companion.isIpAddressValue(address: String?): Boolean {
    if (address.isNullOrBlank()) return false
    return (address.count { it == '.' } == 3 &&
        address.all { it.isDigit() || it == '.' }) ||
        address.contains(":")
}

@Suppress("ReturnCount")
internal fun ConfigRepository.Companion.extractHostFromAddress(address: String): String? {
    val trimmed = address.trim()
    if (trimmed.isEmpty()) return null

    extractHostByUri(trimmed)?.let { return it }
    extractHostByUri("dns://$trimmed")?.let { return it }

    if (trimmed.startsWith("[") && trimmed.contains("]")) {
        return trimmed.substringAfter('[').substringBefore(']')
    }

    val colonCount = trimmed.count { it == ':' }
    if (colonCount == 1 && !trimmed.contains('/')) {
        return trimmed.substringBefore(':').takeIf { it.isNotBlank() }
    }

    return trimmed
}

internal fun ConfigRepository.Companion.extractHostByUri(address: String): String? {
    return try {
        val uri = URI(address)
        uri.host
    } catch (_: Exception) {
        null
    }
}

internal fun ConfigRepository.Companion.extractSubscriptionUrlFromHtml(html: String): String? {
    return REGEX_HTML_SUBSCRIPTION_INPUT.find(html)
        ?.value
        ?.let { inputTag -> REGEX_HTML_INPUT_VALUE.find(inputTag)?.groupValues?.getOrNull(1) }
        ?.trim()
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
}

@Suppress("UnusedParameter")
internal fun ConfigRepository.Companion.looksLikeHtmlSubscriptionPage(contentType: String?, body: String): Boolean {
    val trimmed = body.trimStart()
    if (!trimmed.startsWith("<")) {
        return false
    }

    return trimmed.startsWith("<!DOCTYPE html>", ignoreCase = true) ||
        trimmed.startsWith("<html", ignoreCase = true) ||
        trimmed.startsWith("<head", ignoreCase = true) ||
        trimmed.startsWith("<body", ignoreCase = true) ||
        trimmed.startsWith("<meta", ignoreCase = true) ||
        trimmed.startsWith("<title", ignoreCase = true)
}

internal fun ConfigRepository.Companion.extractSubscriptionHost(url: String): String? {
    return runCatching { URI(url).host?.lowercase() }.getOrNull()
}

internal fun ConfigRepository.Companion.looksLikeSubscriptionUrlForImport(content: String): Boolean {
    val trimmed = content.trim()
    if (!trimmed.startsWith("http://", ignoreCase = true) &&
        !trimmed.startsWith("https://", ignoreCase = true)
    ) {
        return false
    }

    return runCatching {
        val uri = URI(trimmed)
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery.orEmpty()
        val hasUserInfo = !uri.userInfo.isNullOrBlank()
        val hasName = !uri.fragment.isNullOrBlank()
        val hasProxyOnlyPort = uri.port > 0 && path.isBlank() && query.isBlank()
        !hasUserInfo && !hasName && !hasProxyOnlyPort
    }.getOrDefault(false)
}

internal fun ConfigRepository.Companion.prioritizeUserAgents(preferredUserAgent: String?): List<String> {
    if (preferredUserAgent.isNullOrBlank()) return USER_AGENTS
    return buildList {
        add(preferredUserAgent)
        USER_AGENTS.forEach { userAgent ->
            if (!userAgent.equals(preferredUserAgent, ignoreCase = true)) {
                add(userAgent)
            }
        }
    }
}

internal fun ConfigRepository.Companion.buildSubscriptionAttemptUserAgents(
    preferredUserAgent: String?,
    circuitBrokenUserAgents: Set<String>
): List<String> {
    return filterCircuitBrokenUserAgents(
        userAgents = prioritizeUserAgents(preferredUserAgent),
        circuitBrokenUserAgents = circuitBrokenUserAgents
    )
}

internal fun ConfigRepository.Companion.filterCircuitBrokenUserAgents(
    userAgents: List<String>,
    circuitBrokenUserAgents: Set<String>
): List<String> {
    if (circuitBrokenUserAgents.isEmpty()) return userAgents
    val available = userAgents.filterNot { userAgent ->
        circuitBrokenUserAgents.any { blocked ->
            blocked.equals(userAgent, ignoreCase = true)
        }
    }
    return if (available.isNotEmpty()) available else userAgents
}

internal fun ConfigRepository.Companion.shouldRecordSubscriptionNetworkFailure(exception: Exception): Boolean {
    if (exception is ConnectException || exception is SocketTimeoutException) {
        return true
    }
    val message = exception.message.orEmpty().lowercase()
    return "failed to connect" in message || "timeout" in message
}

internal fun ConfigRepository.Companion.shouldStopSubscriptionFallback(
    httpStatusCode: Int? = null,
    looksLikeHtmlInfoPage: Boolean = false): Boolean {
    return looksLikeHtmlInfoPage || httpStatusCode == 429
}

internal fun ConfigRepository.Companion.resolveSubscriptionUpdateBudgetSeconds(configuredTimeoutSeconds: Int): Long {
    return configuredTimeoutSeconds.takeIf { it > 0 }?.toLong()
        ?: AppSettings().subscriptionUpdateTimeout.toLong()
}

internal fun ConfigRepository.Companion.resolveSubscriptionAttemptTimeoutBudget(
    totalBudgetSeconds: Long,
    elapsedMs: Long
): SubscriptionAttemptTimeoutBudget? {
    val safeTotalBudgetSeconds = totalBudgetSeconds.coerceAtLeast(1L)
    val remainingMs = (safeTotalBudgetSeconds * 1000L) - elapsedMs
    if (remainingMs <= 0L) return null

    val remainingSeconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
    return SubscriptionAttemptTimeoutBudget(
        connectTimeoutSeconds = remainingSeconds,
        readTimeoutSeconds = remainingSeconds,
        writeTimeoutSeconds = remainingSeconds,
        callTimeoutSeconds = remainingSeconds
    )
}

internal fun ConfigRepository.Companion.resolveAppRuleOutboundMode(mode: RuleSetOutboundMode?): RuleSetOutboundMode {
    return mode ?: RuleSetOutboundMode.PROXY
    // 有意设计: 自定义规则通常是代理规则，直连为例外
    // 符合"代理优先"的用户心智模型
}

internal fun ConfigRepository.Companion.resolveAppGroupOutboundMode(mode: RuleSetOutboundMode?): RuleSetOutboundMode {
    return mode ?: RuleSetOutboundMode.DIRECT
    // 有意设计: AppGroup 主要用于需要直连的本地应用（游戏、支付等）
    // 如需代理，用户应显式配置
}

internal fun ConfigRepository.Companion.resolveRuleSetOutboundMode(mode: RuleSetOutboundMode?): RuleSetOutboundMode {
    return mode ?: RuleSetOutboundMode.PROXY
}

internal fun ConfigRepository.Companion.resolveCustomRuleOutboundMode(
    mode: RuleSetOutboundMode?,
    oldOutbound: OutboundTag
): RuleSetOutboundMode {
    if (mode != null) return mode
    return when (oldOutbound) {
        OutboundTag.DIRECT -> RuleSetOutboundMode.DIRECT
        OutboundTag.PROXY -> RuleSetOutboundMode.PROXY
        OutboundTag.BLOCK -> RuleSetOutboundMode.BLOCK
    }
}

internal fun ConfigRepository.Companion.filterAppliedRemoteRuleSets(
    ruleSets: List<RuleSet>,
    validTags: Set<String>
): List<RuleSet> {
    return ruleSets.filter { ruleSet ->
        ruleSet.enabled && ruleSet.type == RuleSetType.REMOTE && ruleSet.tag in validTags
    }
}

internal fun ConfigRepository.Companion.detectRuleSetRuleTypeStatic(
    file: java.io.File,
    tag: String = ""
): ConfigRepository.RuleSetRuleType {
    return detectRuleSetRuleTypeFromFile(file, tag)
}

internal fun ConfigRepository.Companion.detectRuleSetRuleTypeFromFile(
    file: java.io.File,
    tag: String = ""
): ConfigRepository.RuleSetRuleType {
    val tagRuleType = detectRuleSetRuleTypeFromTag(tag)
    if (tagRuleType != ConfigRepository.RuleSetRuleType.UNKNOWN) return tagRuleType
    if (!file.exists() || file.length() < RULE_SET_MIN_SIZE_BYTES) {
        return ConfigRepository.RuleSetRuleType.UNKNOWN
    }
    return try {
        val sample = readRuleSetSampleFromFile(file)
        detectRuleSetRuleTypeFromSample(sample)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to detect rule set type: ${file.name}", e)
        ConfigRepository.RuleSetRuleType.UNKNOWN
    }
}

internal fun ConfigRepository.Companion.detectRuleSetRuleTypeFromTag(tag: String): ConfigRepository.RuleSetRuleType {
    val normalizedTag = tag.trim().lowercase()
    return when {
        normalizedTag.startsWith("geosite-") || normalizedTag.contains("geosite") -> ConfigRepository.RuleSetRuleType.DOMAIN
        normalizedTag.startsWith("geoip-") || normalizedTag.contains("geoip") -> ConfigRepository.RuleSetRuleType.IP
        else -> ConfigRepository.RuleSetRuleType.UNKNOWN
    }
}

internal fun ConfigRepository.Companion.detectRuleSetRuleTypeFromSample(sample: ByteArray): ConfigRepository.RuleSetRuleType {
    if (sample.isEmpty()) return ConfigRepository.RuleSetRuleType.UNKNOWN
    if (!isLikelyTextRuleSetFromBytes(sample)) return ConfigRepository.RuleSetRuleType.UNKNOWN
    return detectRuleTypeFromTextContent(sample.toString(Charsets.UTF_8))
}

internal fun ConfigRepository.Companion.detectRuleTypeFromTextContent(text: String): ConfigRepository.RuleSetRuleType {
    val lines = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
        .take(100)
        .toList()

    if (lines.isEmpty()) return ConfigRepository.RuleSetRuleType.UNKNOWN

    var ipLineCount = 0
    var domainLineCount = 0

    for (line in lines) {
        when {
            isIpRuleLineContent(line) -> ipLineCount++
            isDomainRuleLineContent(line) -> domainLineCount++
        }
    }

    val total = ipLineCount + domainLineCount
    if (total == 0) return ConfigRepository.RuleSetRuleType.UNKNOWN

    val ipRatio = ipLineCount.toFloat() / total
    val domainRatio = domainLineCount.toFloat() / total

    return when {
        ipRatio >= RULE_SET_IP_THRESHOLD -> ConfigRepository.RuleSetRuleType.IP
        domainRatio >= RULE_SET_IP_THRESHOLD -> ConfigRepository.RuleSetRuleType.DOMAIN
        ipRatio > 0 && domainRatio > 0 -> ConfigRepository.RuleSetRuleType.MIXED
        else -> ConfigRepository.RuleSetRuleType.UNKNOWN
    }
}

internal fun ConfigRepository.Companion.isIpRuleLineContent(line: String): Boolean {
    if (REGEX_IP_CIDR.matches(line)) return true
    if (isLikelyIpv6Cidr(line)) return true
    return isIpRuleWithPrefix(line)
}

internal fun ConfigRepository.Companion.isLikelyIpv6Cidr(line: String): Boolean {
    if (!line.contains("/") || !line.contains(":") || line.contains(".")) return false
    val ipPart = line.substringBefore("/")
    return !ipPart.contains(" ") && ipPart.length <= 45 && ipPart.count { it == ':' } >= 1
}

internal fun ConfigRepository.Companion.isIpRuleWithPrefix(line: String): Boolean {
    val prefixes = listOf("ip-cidr:", "ip:", "geoip:")
    for (prefix in prefixes) {
        if (line.startsWith(prefix, ignoreCase = true)) {
            val content = line.removePrefix(prefix).trim()
            if (REGEX_IP_CIDR.matches(content)) return true
            if (content.contains(":") && content.contains("/") && !content.contains(".")) return true
        }
    }
    return false
}

internal fun ConfigRepository.Companion.isDomainRuleLineContent(line: String): Boolean {
    if (REGEX_DOMAIN_LINE.matches(line)) {
        return true
    }
    val domainPrefixes = listOf("domain:", "geosite:", "domain-keyword:", "domain-suffix:", "domain-regex:")
    for (prefix in domainPrefixes) {
        if (line.startsWith(prefix, ignoreCase = true)) {
            return true
        }
    }
    return false
}

internal fun ConfigRepository.Companion.readRuleSetSampleFromFile(file: java.io.File): ByteArray {
    return file.inputStream().use { input ->
        val buffer = ByteArray(RULE_SET_SNIFF_BYTES)
        val read = input.read(buffer)
        if (read > 0) buffer.copyOf(read) else ByteArray(0)
    }
}

internal fun ConfigRepository.Companion.isLikelyTextRuleSetFromBytes(sample: ByteArray): Boolean {
    if (sample.any { it == 0.toByte() }) return false
    val printableBytes = sample.count { byte ->
        val code = byte.toInt() and 0xff
        code == 9 || code == 10 || code == 13 || code in 32..126
    }
    return printableBytes >= sample.size * 3 / 4
}

internal fun ConfigRepository.Companion.setProfileUpdateStageIfCurrent(
    profilesState: MutableStateFlow<List<ProfileUi>>,
    activeUpdateRuns: Map<String, Long>,
    profileId: String,
    runId: Long,
    stage: SubscriptionUpdateStage?
) {
    profilesState.update { profiles ->
        if (activeUpdateRuns[profileId] != runId) {
            return@update profiles
        }
        profiles.map { profile ->
            if (profile.id == profileId) profile.copy(updateStage = stage) else profile
        }
    }
}

internal fun ConfigRepository.Companion.resolveSubscriptionUpdateStage(
    stageName: String?
): SubscriptionUpdateStage? {
    return when (stageName) {
        "requesting" -> SubscriptionUpdateStage.Requesting
        "parsing" -> SubscriptionUpdateStage.Parsing
        "saving" -> SubscriptionUpdateStage.Saving
        "dns_background" -> SubscriptionUpdateStage.DnsBackground
        else -> null
    }
}

internal fun ConfigRepository.Companion.toRouteRule(semantic: ConfigRepository.OutboundSemantic, selectorTag: String): RouteRule {
    return when (semantic) {
        ConfigRepository.OutboundSemantic.Direct -> RouteRule(outbound = "direct")
        ConfigRepository.OutboundSemantic.Block -> RouteRule(action = "reject")
        ConfigRepository.OutboundSemantic.Proxy -> RouteRule(outbound = selectorTag)
        is ConfigRepository.OutboundSemantic.RouteTag -> RouteRule(outbound = semantic.tag)
        is ConfigRepository.OutboundSemantic.FallbackProxy -> RouteRule(outbound = semantic.tag)
    }
}

internal val ConfigRepository.Companion.DEFAULT_WIREGUARD_ALLOWED_IPS get() = com.kunk.singbox.repository.DEFAULT_WIREGUARD_ALLOWED_IPS

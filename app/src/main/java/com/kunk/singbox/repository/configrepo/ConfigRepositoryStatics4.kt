@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.repository

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kunk.singbox.model.*
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun ConfigRepository.Companion.buildDnsOverrideCompatibilityWarning(dnsOverride: String?): String? {
    val trimmed = dnsOverride?.trim().orEmpty()
    if (trimmed.isBlank()) return null

    val dnsObject = parseDnsOverrideObjectForWarning(trimmed)
    return when {
        dnsObject == null -> "DNS 覆写无法解析，请检查 JSON 格式；KunBox 无法保证兼容。"
        !hasDnsOverrideShape(dnsObject) -> "DNS 覆写无法解析，请使用包含 dns/servers/rules 的 JSON 对象。"
        else -> formatDnsOverrideCompatibilityWarning(collectDnsOverrideCompatibilityIssues(dnsObject))
    }
}

internal fun ConfigRepository.Companion.parseDnsOverrideObjectForWarning(dnsOverride: String): JsonObject? {
    return try {
        extractDnsOverrideJsonObject(dnsOverride)
    } catch (_: Exception) {
        null
    }
}

internal fun ConfigRepository.Companion.hasDnsOverrideShape(dnsObject: JsonObject): Boolean {
    return dnsOverrideKeys().any { dnsObject.has(it) }
}

internal fun ConfigRepository.Companion.collectDnsOverrideCompatibilityIssues(dnsObject: JsonObject): Set<String> {
    val issues = linkedSetOf<String>()
    val definedServerTags = knownDnsServerTags().toMutableSet()
    collectDnsServerCompatibilityIssues(dnsObject, definedServerTags, issues)
    collectDnsRuleCompatibilityIssues(dnsObject, definedServerTags, issues)
    return issues
}

internal fun ConfigRepository.Companion.collectDnsServerCompatibilityIssues(
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

internal fun ConfigRepository.Companion.collectSingleDnsServerCompatibilityIssues(
    server: JsonObject?,
    definedServerTags: MutableSet<String>,
    overrideTags: MutableSet<String>,
    issues: MutableSet<String>
) {
    if (server == null) {
        issues.add("servers 中存在非对象项")
        return
    }
    collectDnsServerTagIssues(server, definedServerTags, overrideTags, issues)
    collectDnsServerLegacyFieldIssues(server, issues)
    collectDnsServerEndpointIssues(server, issues)
}

internal fun ConfigRepository.Companion.collectDnsServerTagIssues(
    server: JsonObject,
    definedServerTags: MutableSet<String>,
    overrideTags: MutableSet<String>,
    issues: MutableSet<String>
) {
    val tag = jsonString(server, "tag")
    if (tag.isNullOrBlank()) {
        issues.add("DNS server 缺少 tag")
        return
    }
    if (!overrideTags.add(tag)) {
        issues.add("DNS server tag 重复: $tag")
    }
    definedServerTags.add(tag)
}

internal fun ConfigRepository.Companion.collectDnsServerLegacyFieldIssues(server: JsonObject, issues: MutableSet<String>) {
    if (server.has("address")) issues.add("DNS server 使用旧字段 address")
    if (server.has("address_resolver")) issues.add("DNS server 使用旧字段 address_resolver")
    if (server.has("address_strategy")) issues.add("DNS server 使用旧字段 address_strategy")
}

internal fun ConfigRepository.Companion.collectDnsServerEndpointIssues(server: JsonObject, issues: MutableSet<String>) {
    val tag = jsonString(server, "tag") ?: return
    val type = jsonString(server, "type")?.lowercase()
    val usesLatestEndpoint = when (type) {
        null, "" -> false
        "local", "fakeip" -> true
        "dhcp" -> true
        "hosts" -> server.has("path") || server.has("predefined")
        "resolved" -> hasNonBlankString(server, "service")
        else -> hasNonBlankString(server, "server")
    }
    if (!usesLatestEndpoint) {
        issues.add("DNS server 缺少最新格式 type/server: $tag")
    }
}

internal fun ConfigRepository.Companion.collectDnsRuleCompatibilityIssues(
    dnsObject: JsonObject,
    definedServerTags: Set<String>,
    issues: MutableSet<String>
) {
    val rules = dnsObject.get("rules") ?: return
    if (!rules.isJsonArray) {
        issues.add("dns.rules 不是数组")
        return
    }
    rules.asJsonArray.forEach { element ->
        collectSingleDnsRuleCompatibilityIssues(asJsonObjectOrNull(element), definedServerTags, issues)
    }
}

internal fun ConfigRepository.Companion.collectSingleDnsRuleCompatibilityIssues(
    rule: JsonObject?,
    definedServerTags: Set<String>,
    issues: MutableSet<String>
) {
    if (rule == null) {
        issues.add("rules 中存在非对象项")
        return
    }
    collectDnsRuleActionIssues(rule, definedServerTags, issues)
    collectDnsRuleLegacyFieldIssues(rule, issues)
    if (jsonString(rule, "type").equals("logical", ignoreCase = true)) {
        val nestedRules = rule.get("rules")
        if (nestedRules == null || !nestedRules.isJsonArray || nestedRules.asJsonArray.size() == 0) {
            issues.add("DNS logical rule 缺少 rules")
        } else {
            nestedRules.asJsonArray.forEach { nested ->
                collectSingleDnsRuleCompatibilityIssues(
                    asJsonObjectOrNull(nested),
                    definedServerTags,
                    issues
                )
            }
        }
    }
    if (!hasDnsRuleMatcher(rule)) {
        issues.add("DNS rule 存在没有匹配条件的全局规则")
    }
}

internal fun ConfigRepository.Companion.collectDnsRuleActionIssues(
    rule: JsonObject,
    definedServerTags: Set<String>,
    issues: MutableSet<String>
) {
    val server = jsonString(rule, "server")
    val action = jsonString(rule, "action")
    if (!server.isNullOrBlank() && action.isNullOrBlank()) {
        issues.add("DNS rule 缺少最新格式 action")
    }
    if (action.equals("route", ignoreCase = true) && server.isNullOrBlank()) {
        issues.add("DNS route 规则缺少 server")
    }
    if (!server.isNullOrBlank() && server !in definedServerTags) {
        issues.add("DNS rule 引用了未定义 server: $server")
    }
}

internal fun ConfigRepository.Companion.collectDnsRuleLegacyFieldIssues(rule: JsonObject, issues: MutableSet<String>) {
    if (rule.has("rule_set_ipcidr_match_source")) {
        issues.add("DNS rule 使用旧拼写 rule_set_ipcidr_match_source")
    }
}

internal fun ConfigRepository.Companion.formatDnsOverrideCompatibilityWarning(issues: Set<String>): String? {
    if (issues.isEmpty()) return null
    return "DNS 覆写使用了旧版 sing-box 格式或存在兼容风险：" +
        issues.take(5).joinToString("；") +
        "。KunBox 会尝试兼容，但建议改为最新 sing-box DNS 格式。"
}

internal fun ConfigRepository.Companion.extractDnsOverrideJsonObject(dnsOverride: String): JsonObject? {
    val root = JsonParser.parseString(dnsOverride)
    if (!root.isJsonObject) return null
    val rootObject = root.asJsonObject
    return rootObject
        .get("dns")
        ?.takeIf { it.isJsonObject }
        ?.asJsonObject
        ?: rootObject
}

internal fun ConfigRepository.Companion.jsonString(obj: JsonObject, key: String): String? {
    val element = obj.get(key) ?: return null
    if (!element.isJsonPrimitive) return null
    return element.asJsonPrimitive.takeIf { it.isString }?.asString?.trim()?.takeIf { it.isNotBlank() }
}

internal fun ConfigRepository.Companion.hasNonBlankString(obj: JsonObject, key: String): Boolean {
    return !jsonString(obj, key).isNullOrBlank()
}

internal fun ConfigRepository.Companion.hasDnsRuleMatcher(rule: JsonObject): Boolean {
    if (jsonString(rule, "type").equals("logical", ignoreCase = true)) {
        val nestedRules = rule.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
        return nestedRules.size() > 0 && nestedRules.all { nested ->
            asJsonObjectOrNull(nested)?.let(::hasDnsRuleMatcher) == true
        }
    }
    return dnsRuleMatcherKeys().any { key -> rule.has(key) && !rule.get(key).isJsonNull }
}

internal fun ConfigRepository.Companion.asJsonObjectOrNull(element: com.google.gson.JsonElement): JsonObject? {
    return element.takeIf { it.isJsonObject }?.asJsonObject
}

internal fun ConfigRepository.Companion.dnsOverrideKeys(): Set<String> {
    return setOf(
        "servers",
        "rules",
        "final",
        "strategy",
        "disable_cache",
        "disable_expire",
        "independent_cache",
        "reverse_mapping",
        "cache_capacity",
        "client_subnet",
        "fakeip"
    )
}

internal fun ConfigRepository.Companion.knownDnsServerTags(): Set<String> {
    return setOf(
        "local",
        "remote",
        "fakeip-dns",
        "dns-bootstrap",
        "dns-bootstrap-v4",
        "dns-bootstrap-v6",
        "dns-backup"
    )
}

internal fun ConfigRepository.Companion.dnsRuleMatcherKeys(): Set<String> {
    return setOf(
        "domain",
        "domain_suffix",
        "domain_keyword",
        "domain_regex",
        "geosite",
        "rule_set",
        "query_type",
        "inbound",
        "package_name",
        "user_id",
        "outbound",
        "ip_version",
        "network",
        "auth_user",
        "protocol",
        "client",
        "source_geoip",
        "geoip",
        "ip_cidr",
        "ip_is_private",
        "ip_accept_any",
        "interface_address",
        "network_interface_address",
        "default_interface_address",
        "source_ip_cidr",
        "source_ip_is_private",
        "source_port",
        "source_port_range",
        "port",
        "port_range",
        "process_name",
        "process_path",
        "process_path_regex",
        "user",
        "clash_mode",
        "network_type",
        "network_is_expensive",
        "network_is_constrained",
        "wifi_ssid",
        "wifi_bssid",
        "rule_set_ip_cidr_match_source",
        "rule_set_ip_cidr_accept_empty"
    )
}

internal fun ConfigRepository.Companion.parseDnsOverrideConfig(dnsOverride: String?): DnsConfig? {
    val trimmed = dnsOverride?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    findUnsupportedAndroidCapabilityInJson(trimmed)?.let { message ->
        throw IllegalArgumentException(message)
    }
    val config = Gson().fromJson(extractDnsOverrideJsonObject(trimmed), DnsConfig::class.java)
        ?: return null
    findUnsupportedAndroidCapability(SingBoxConfig(dns = config))?.let { message ->
        throw IllegalArgumentException(message)
    }
    return config
}

internal fun ConfigRepository.Companion.prepareDnsOverrideForRuntime(config: DnsConfig): DnsConfig? {
    if (config.fakeip != null) return null
    if (config.servers.orEmpty().any { server ->
            !server.strategy.isNullOrBlank() || !server.clientSubnet.isNullOrBlank()
        }
    ) {
        return null
    }
    val servers = runCatching {
        config.servers.orEmpty().map(::normalizeInjectedDnsServer)
    }.getOrNull() ?: return null
    if (servers.any { !isDnsServerValidForRuntime(it, requireTag = true) }) return null

    val availableServerTags = knownDnsServerTags() + servers.mapNotNull { it.tag }
    val rules = config.rules.orEmpty().map(::normalizeDnsOverrideRule)
    if (rules.any { rule ->
            if (dnsRuleOutboundValues(rule).isNotEmpty()) {
                rule.server.isNullOrBlank() || rule.server !in availableServerTags
            } else {
                !isDnsRuleValidForModernRuntime(rule, availableServerTags)
            }
        }
    ) {
        return null
    }
    val finalServer = config.finalServer?.trim()?.takeIf(String::isNotBlank)
    if (finalServer != null && finalServer !in availableServerTags) return null
    return config.copy(
        servers = servers,
        rules = rules,
        finalServer = finalServer,
        independentCache = null,
        fakeip = null
    )
}

internal fun ConfigRepository.Companion.isDnsServerValidForRuntime(
    server: DnsServer,
    requireTag: Boolean = false
): Boolean {
    if (requireTag && server.tag.isNullOrBlank()) return false
    return when (server.type?.trim()?.lowercase()) {
        "udp", "tcp", "tls", "quic", "https", "h3" -> !server.server.isNullOrBlank()
        "hosts" -> true
        "resolved" -> !server.service.isNullOrBlank()
        "local", "fakeip", "dhcp", "mdns" -> true
        else -> false
    }
}

internal fun ConfigRepository.Companion.isDnsRuleValidForModernRuntime(
    rule: DnsRule,
    availableServerTags: Set<String>,
    ipOnlyRuleSetTags: Set<String> = emptySet(),
    nested: Boolean = false
): Boolean {
    if (dnsRuleOutboundValues(rule).isNotEmpty()) return false
    if (!rule.strategy.isNullOrBlank() ||
        !rule.geosite.isNullOrEmpty() ||
        !rule.sourceGeoip.isNullOrEmpty() ||
        !rule.geoip.isNullOrEmpty() ||
        !rule.client.isNullOrEmpty() ||
        !rule.ipCidr.isNullOrEmpty() ||
        rule.ipIsPrivate != null ||
        rule.ipAcceptAny != null ||
        rule.ruleSetIpCidrAcceptEmpty != null ||
        rule.ruleSet.orEmpty().any(ipOnlyRuleSetTags::contains)
    ) {
        return false
    }
    if (rule.type.equals("logical", ignoreCase = true)) {
        val nestedRules = rule.rules.orEmpty()
        return !nested && nestedRules.isNotEmpty() && nestedRules.all {
            isDnsRuleValidForModernRuntime(it, availableServerTags, ipOnlyRuleSetTags, nested = true)
        } && isDnsRuleActionValidForRuntime(rule, availableServerTags)
    }
    if (nested) {
        return rule.action.isNullOrBlank() && rule.server.isNullOrBlank()
    }
    return isDnsRuleActionValidForRuntime(rule, availableServerTags)
}

internal fun ConfigRepository.Companion.isDnsRuleActionValidForRuntime(
    rule: DnsRule,
    availableServerTags: Set<String>
): Boolean {
    return when (rule.action?.trim()?.lowercase()) {
        "route" -> !rule.server.isNullOrBlank() && rule.server in availableServerTags
        "reject", "predefined" -> rule.server.isNullOrBlank()
        else -> false
    }
}

internal fun ConfigRepository.Companion.sanitizeDnsRulesForRuntime(
    rules: List<DnsRule>,
    availableServerTags: Set<String>,
    ipOnlyRuleSetTags: Set<String> = emptySet()
): List<DnsRule> {
    return rules.map(::normalizeDnsOverrideRule).filter { rule ->
        isDnsRuleValidForModernRuntime(rule, availableServerTags, ipOnlyRuleSetTags)
    }
}

internal fun ConfigRepository.Companion.applyDnsOverride(
    baseConfig: DnsConfig,
    overrideConfig: DnsConfig,
    sanitizeServer: (DnsServer) -> DnsServer = { it }
): DnsConfig {
    val servers = baseConfig.servers.orEmpty().toMutableList()
    overrideConfig.servers.orEmpty().forEach { server ->
        val tag = server.tag
        if (!tag.isNullOrBlank()) {
            val sanitizedServer = sanitizeServer(server)
            val existingIndex = servers.indexOfFirst { it.tag == tag }
            if (existingIndex >= 0) {
                servers[existingIndex] = sanitizedServer
            } else {
                servers.add(sanitizedServer)
            }
        }
    }

    val rules = baseConfig.rules.orEmpty().toMutableList()
    val availableServerTags = servers.mapNotNullTo(mutableSetOf()) { it.tag }
    val overrideRules = sanitizeDnsRulesForRuntime(
        overrideConfig.rules.orEmpty(),
        availableServerTags
    )
    if (overrideRules.isNotEmpty()) {
        rules.addAll(0, overrideRules)
    }

    return baseConfig.copy(
        servers = servers,
        rules = rules,
        finalServer = overrideConfig.finalServer?.takeIf { it.isNotBlank() } ?: baseConfig.finalServer,
        strategy = overrideConfig.strategy?.takeIf { it.isNotBlank() } ?: baseConfig.strategy,
        disableCache = overrideConfig.disableCache ?: baseConfig.disableCache,
        disableExpire = overrideConfig.disableExpire ?: baseConfig.disableExpire,
        independentCache = overrideConfig.independentCache ?: baseConfig.independentCache,
        reverseMapping = overrideConfig.reverseMapping ?: baseConfig.reverseMapping,
        cacheCapacity = overrideConfig.cacheCapacity ?: baseConfig.cacheCapacity,
        clientSubnet = overrideConfig.clientSubnet ?: baseConfig.clientSubnet,
        fakeip = overrideConfig.fakeip ?: baseConfig.fakeip
    )
}

internal fun ConfigRepository.Companion.normalizeDnsOverrideRule(rule: DnsRule): DnsRule {
    if (!rule.action.isNullOrBlank() || rule.server.isNullOrBlank()) {
        return rule
    }
    return rule.copy(action = "route")
}

internal fun ConfigRepository.Companion.shouldApplyDnsPreResolveToDomain(
    domain: String,
    dnsOverride: DnsConfig?,
    outboundTag: String? = null
): Boolean {
    val normalizedDomain = domain.trim()
    if (normalizedDomain.isBlank() || isIpAddressValue(normalizedDomain) || dnsOverride == null) {
        return true
    }
    return dnsOverride.rules.orEmpty()
        .map { normalizeDnsOverrideRule(it) }
        .none { rule ->
            buildDomainResolverForMatchedDnsOverrideRule(
                domain = normalizedDomain,
                outboundTag = outboundTag,
                rule = rule
            ) != null
        }
}

internal fun ConfigRepository.Companion.applyDnsOverrideDomainResolvers(
    outbounds: List<Outbound>,
    overrideConfig: DnsConfig
): List<Outbound> {
    val rules = overrideConfig.rules.orEmpty().map { normalizeDnsOverrideRule(it) }
    if (rules.isEmpty()) return outbounds

    return outbounds.map { outbound ->
        val server = outbound.server?.trim().orEmpty()
        if (server.isBlank() || isIpAddressValue(server)) {
            return@map outbound
        }
        val resolver = rules.firstNotNullOfOrNull { rule ->
            buildDomainResolverForMatchedDnsOverrideRule(
                domain = server,
                outboundTag = outbound.tag,
                rule = rule
            )
        } ?: return@map outbound
        outbound.copy(
            domainResolver = resolver.copy(
                strategy = resolver.strategy ?: outbound.domainResolver?.strategy
            )
        )
    }
}

internal fun ConfigRepository.Companion.resolveDnsOverrideDirectDnsServerTags(
    outbounds: List<Outbound>,
    overrideConfig: DnsConfig?
): Set<String> {
    val rules = overrideConfig?.rules.orEmpty().map { normalizeDnsOverrideRule(it) }
    if (rules.isEmpty()) return emptySet()

    val directTags = linkedSetOf<String>()
    outbounds.forEach { outbound ->
        val server = outbound.server?.trim().orEmpty()
        if (server.isBlank() || isIpAddressValue(server)) return@forEach
        rules.forEach { rule ->
            val resolver = buildDomainResolverForMatchedDnsOverrideRule(
                domain = server,
                outboundTag = outbound.tag,
                rule = rule
            )
            val resolverTag = resolver?.server?.trim().orEmpty()
            if (resolverTag.isNotBlank()) {
                directTags.add(resolverTag)
            }
        }
    }
    return directTags
}

internal fun ConfigRepository.Companion.buildDomainResolverForMatchedDnsOverrideRule(
    domain: String,
    outboundTag: String?,
    rule: DnsRule
): DomainResolveConfig? {
    val server = rule.server?.trim()?.takeIf { it.isNotBlank() }
    val matches = server != null &&
        rule.action.equals("route", ignoreCase = true) &&
        dnsRuleAppliesToAddressQuery(rule) &&
        dnsRuleCanResolveOutboundDomain(domain, outboundTag, rule)
    return if (matches) {
        DomainResolveConfig(
            server = server,
            strategy = rule.strategy,
            disableCache = rule.disableCache,
            rewriteTtl = rule.rewriteTtl,
            clientSubnet = rule.clientSubnet
        )
    } else {
        null
    }
}

internal fun ConfigRepository.Companion.dnsRuleCanResolveOutboundDomain(
    domain: String,
    outboundTag: String?,
    rule: DnsRule
): Boolean {
    if (!dnsRuleMatchesOutbound(outboundTag, rule)) {
        return false
    }
    if (dnsRuleHasDomainMatcher(rule)) {
        return dnsRuleMatchesDomain(domain, rule)
    }
    return dnsRuleHasNoUnsupportedOutboundDomainMatcher(rule)
}

internal fun ConfigRepository.Companion.dnsRuleMatchesOutbound(outboundTag: String?, rule: DnsRule): Boolean {
    val outbounds = dnsRuleOutboundValues(rule)
    if (outbounds.isEmpty()) return true
    if (outbounds.any { it.equals("any", ignoreCase = true) }) return true
    return outboundTag?.let { tag -> outbounds.any { it == tag } } == true
}

internal fun ConfigRepository.Companion.dnsRuleOutboundValues(rule: DnsRule): List<String> {
    return when (val raw = rule.outboundRaw) {
        is String -> listOf(raw)
        is List<*> -> raw.mapNotNull { it?.toString() }
        else -> emptyList()
    }.map { it.trim() }.filter { it.isNotBlank() }
}

internal fun ConfigRepository.Companion.dnsRuleHasDomainMatcher(rule: DnsRule): Boolean {
    return rule.domain.orEmpty().any { it.isNotBlank() } ||
        rule.domainSuffix.orEmpty().any { it.isNotBlank() } ||
        rule.domainKeyword.orEmpty().any { it.isNotBlank() } ||
        rule.domainRegex.orEmpty().any { it.isNotBlank() }
}

internal fun ConfigRepository.Companion.dnsRuleHasNoUnsupportedOutboundDomainMatcher(rule: DnsRule): Boolean {
    val type = rule.type?.trim().orEmpty()
    if (type.isNotBlank() && !type.equals("default", ignoreCase = true)) return false
    if (!rule.mode.isNullOrBlank() || !rule.rules.isNullOrEmpty() || rule.invert == true) return false

    return !dnsRuleHasUnsupportedListMatcher(rule) && !dnsRuleHasUnsupportedScalarMatcher(rule)
}

internal fun ConfigRepository.Companion.dnsRuleHasUnsupportedListMatcher(rule: DnsRule): Boolean {
    return listOf(
        rule.geosite,
        rule.ruleSet,
        rule.inbound,
        rule.packageName,
        rule.network,
        rule.authUser,
        rule.protocol,
        rule.client,
        rule.sourceGeoip,
        rule.geoip,
        rule.ipCidr,
        rule.defaultInterfaceAddress,
        rule.sourceIpCidr,
        rule.sourcePort,
        rule.sourcePortRange,
        rule.port,
        rule.portRange,
        rule.processName,
        rule.processPath,
        rule.processPathRegex,
        rule.user,
        rule.networkType,
        rule.wifiSsid,
        rule.wifiBssid
    ).any { !it.isNullOrEmpty() }
}

internal fun ConfigRepository.Companion.dnsRuleHasUnsupportedScalarMatcher(rule: DnsRule): Boolean {
    return listOf(
        !rule.userId.isNullOrEmpty(),
        !rule.interfaceAddress.isNullOrEmpty(),
        !rule.networkInterfaceAddress.isNullOrEmpty(),
        rule.ipVersion != null,
        rule.ipIsPrivate == true,
        rule.ipAcceptAny == true,
        rule.sourceIpIsPrivate == true,
        !rule.clashMode.isNullOrBlank(),
        rule.networkIsExpensive == true,
        rule.networkIsConstrained == true,
        rule.ruleSetIpCidrMatchSource == true,
        rule.ruleSetIpCidrAcceptEmpty == true
    ).any { it }
}

internal fun ConfigRepository.Companion.dnsRuleAppliesToAddressQuery(rule: DnsRule): Boolean {
    val queryTypes = rule.queryType.orEmpty()
        .map { it.trim().uppercase() }
        .filter { it.isNotBlank() }
    return queryTypes.isEmpty() || queryTypes.any {
        it == "A" || it == "AAAA" || it == "1" || it == "28"
    }
}

internal fun ConfigRepository.Companion.dnsRuleMatchesDomain(domain: String, rule: DnsRule): Boolean {
    val normalizedDomain = domain.trim().trimEnd('.').lowercase()
    val exactMatch = rule.domain.orEmpty().any { normalizeDnsRuleDomain(it) == normalizedDomain }
    val suffixMatch = rule.domainSuffix.orEmpty().any { suffix ->
        val normalizedSuffix = normalizeDnsRuleDomain(suffix).removePrefix(".")
        normalizedDomain == normalizedSuffix || normalizedDomain.endsWith(".$normalizedSuffix")
    }
    val keywordMatch = rule.domainKeyword.orEmpty().any { keyword ->
        keyword.trim().lowercase().takeIf { it.isNotBlank() }?.let { normalizedDomain.contains(it) } == true
    }
    val regexMatch = rule.domainRegex.orEmpty().any { pattern ->
        runCatching { Regex(pattern).containsMatchIn(domain) }.getOrDefault(false)
    }

    return normalizedDomain.isNotBlank() && (exactMatch || suffixMatch || keywordMatch || regexMatch)
}

internal fun ConfigRepository.Companion.normalizeDnsRuleDomain(value: String): String {
    return value.trim().trimEnd('.').lowercase()
}

internal fun ConfigRepository.Companion.normalizeLocalDns(value: String?): String {
    val trimmed = value?.trim().orEmpty()
    return if (
        trimmed.isBlank() ||
        trimmed.equals(AppSettings.LEGACY_LOCAL_DNS, ignoreCase = true) ||
        trimmed.equals(AppSettings.LEGACY_DOMAIN_LOCAL_DNS, ignoreCase = true)
    ) {
        AppSettings.DEFAULT_LOCAL_DNS
    } else {
        trimmed
    }
}

internal fun ConfigRepository.Companion.isBareDnsDomain(value: String): Boolean {
    if (value.contains("://") || value.contains("/")) return false
    if (isIpAddressValue(value)) return false
    return value.contains('.')
}

internal fun ConfigRepository.Companion.normalizeRemoteDns(value: String?): String {
    val trimmed = value?.trim().orEmpty()
    return trimmed.ifBlank { AppSettings.DEFAULT_REMOTE_DNS }
}

internal fun ConfigRepository.Companion.buildDnsResolverForAddress(address: String): DomainResolveConfig? {
    val trimmed = address.trim()
    if (trimmed.equals("local", ignoreCase = true)) {
        return null
    }
    val host = extractHostFromAddress(trimmed)?.trim().orEmpty()
    if (host.isEmpty() || isIpAddressValue(host)) {
        return null
    }
    return DomainResolveConfig(server = "dns-bootstrap")
}

internal fun ConfigRepository.Companion.buildSpecialDnsServerOrNull(
    trimmed: String,
    tag: String,
    detour: String?,
    domainStrategy: String?,
    domainResolver: DomainResolveConfig?
): DnsServer? {
    val type = when {
        trimmed.equals("local", ignoreCase = true) -> "local"
        trimmed.equals("fakeip", ignoreCase = true) -> "fakeip"
        else -> null
    }
    return type?.let {
        val resolver = domainResolver
            ?.takeIf { resolverConfig -> !resolverConfig.server.isNullOrBlank() }
            ?.let { resolverConfig ->
                resolverConfig.copy(strategy = resolverConfig.strategy ?: domainStrategy)
            }
        DnsServer(
            tag = tag,
            type = it,
            domainResolver = resolver,
            detour = detour
        )
    }
}

internal fun ConfigRepository.Companion.dnsServerTypeFromScheme(scheme: String?): String {
    return when (scheme) {
        "https" -> "https"
        "h3" -> "h3"
        "tls" -> "tls"
        "quic" -> "quic"
        "tcp" -> "tcp"
        "udp" -> "udp"
        "dhcp" -> "dhcp"
        null -> "udp"
        else -> throw IllegalArgumentException("Unsupported DNS server scheme: $scheme")
    }
}

internal fun ConfigRepository.Companion.shouldUseParsedDnsHost(scheme: String?): Boolean {
    return scheme == null || scheme in setOf("https", "h3", "tls", "quic", "tcp", "udp", "dhcp")
}

internal fun ConfigRepository.Companion.buildDnsServer(
    address: String,
    tag: String,
    detour: String? = null,
    domainStrategy: String? = null,
    domainResolver: DomainResolveConfig? = null): DnsServer {
    val trimmed = address.trim()
    buildSpecialDnsServerOrNull(trimmed, tag, detour, domainStrategy, domainResolver)?.let {
        return it
    }

    if (trimmed.startsWith("dhcp://", ignoreCase = true)) {
        val interfaceName = trimmed.substringAfter("://").trim()
            .takeIf { it.isNotBlank() && !it.equals("auto", ignoreCase = true) }
        return DnsServer(
            tag = tag,
            type = "dhcp",
            interfaceName = interfaceName,
            detour = detour
        )
    }

    fun runtimeDomainResolver(host: String): DomainResolveConfig? {
        val existing = domainResolver?.takeIf { !it.server.isNullOrBlank() }
        val resolverServer = existing?.server?.trim()?.takeIf(String::isNotBlank)
            ?: DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG.takeIf {
                !isIpAddressValue(host) && tag != DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG
            }
        return resolverServer?.let {
            (existing ?: DomainResolveConfig()).copy(
                server = it,
                strategy = existing?.strategy ?: domainStrategy
            )
        }
    }

    parseBareDnsEndpoint(trimmed)?.let { (host, port) ->
        return DnsServer(
            tag = tag,
            type = "udp",
            server = host,
            serverPort = port,
            domainResolver = runtimeDomainResolver(host),
            detour = detour
        )
    }

    val uri = try {
        URI(trimmed)
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid DNS server address: $trimmed", e)
    }

    val scheme = uri.scheme?.lowercase()
    val type = dnsServerTypeFromScheme(scheme)
    val host = uri.host?.removePrefix("[")?.removeSuffix("]")
        ?: throw IllegalArgumentException("DNS server address has no host: $trimmed")
    val port = if (uri.port > 0) uri.port else if (scheme == null || scheme == "udp") 53 else null
    val path = uri.path?.takeIf { it.isNotBlank() && it != "/" }
    val server = if (shouldUseParsedDnsHost(scheme)) host else trimmed

    return DnsServer(
        tag = tag,
        type = type,
        server = server,
        serverPort = port,
        pathRaw = path,
        domainResolver = runtimeDomainResolver(host),
        detour = detour
    )
}

internal fun ConfigRepository.Companion.parseBareDnsEndpoint(address: String): Pair<String, Int>? {
    if (address.isBlank() || address.contains("://") || address.contains('/')) return null
    if (address.startsWith("[") && address.contains(']')) {
        val host = address.substringAfter('[').substringBefore(']').trim()
        val port = address.substringAfter(']', "").removePrefix(":").toIntOrNull() ?: 53
        return host.takeIf(String::isNotBlank)?.let { it to port }
    }
    if (address.count { it == ':' } > 1) {
        return address to 53
    }
    val host = address.substringBeforeLast(':').takeIf { address.contains(':') } ?: address
    val port = address.substringAfterLast(':').takeIf { address.contains(':') }?.toIntOrNull() ?: 53
    require(port in 1..65535) { "Invalid DNS server port: $address" }
    return host.trim().takeIf(String::isNotBlank)?.let { it to port }
}

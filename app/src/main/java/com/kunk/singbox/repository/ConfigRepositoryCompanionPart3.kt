package com.kunk.singbox.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kunk.singbox.model.*
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Suppress("TooManyFunctions")
open class ConfigRepositoryCompanionPart3 : ConfigRepositoryCompanionPart2() {
    internal override fun collectSingleDnsServerCompatibilityIssues(
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

    internal override fun collectDnsServerTagIssues(
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

    internal override fun collectDnsServerLegacyFieldIssues(server: JsonObject, issues: MutableSet<String>) {
        if (server.has("address")) issues.add("DNS server 使用旧字段 address")
        if (server.has("address_resolver")) issues.add("DNS server 使用旧字段 address_resolver")
        if (server.has("address_strategy")) issues.add("DNS server 使用旧字段 address_strategy")
    }

    internal override fun collectDnsServerEndpointIssues(server: JsonObject, issues: MutableSet<String>) {
        val tag = jsonString(server, "tag") ?: return
        val type = jsonString(server, "type")?.lowercase()
        val endpointOptional = type in dnsServerTypesWithoutEndpoint()
        val usesLatestEndpoint = !type.isNullOrBlank() && (endpointOptional || hasNonBlankString(server, "server"))
        if (!usesLatestEndpoint) {
            issues.add("DNS server 缺少最新格式 type/server: $tag")
        }
    }

    internal override fun collectDnsRuleCompatibilityIssues(
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

    internal override fun collectSingleDnsRuleCompatibilityIssues(
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
        if (!hasDnsRuleMatcher(rule)) {
            issues.add("DNS rule 存在没有匹配条件的全局规则")
        }
    }

    internal override fun collectDnsRuleActionIssues(
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

    internal override fun collectDnsRuleLegacyFieldIssues(rule: JsonObject, issues: MutableSet<String>) {
        if (rule.has("outbound")) {
            issues.add("DNS rule 使用已废弃 outbound 匹配")
        }
        if (dnsRuleAddressFilterKeys().any { rule.has(it) }) {
            issues.add("DNS rule 使用旧地址过滤字段")
        }
    }

    internal override fun collectDnsTopLevelCompatibilityIssues(dnsObject: JsonObject, issues: MutableSet<String>) {
        if (dnsObject.has("independent_cache")) {
            issues.add("dns.independent_cache 已不再推荐")
        }
    }

    internal override fun formatDnsOverrideCompatibilityWarning(issues: Set<String>): String? {
        if (issues.isEmpty()) return null
        return "DNS 覆写使用了旧版 sing-box 格式或存在兼容风险：" +
            issues.take(5).joinToString("；") +
            "。KunBox 会尝试兼容，但建议改为最新 sing-box DNS 格式。"
    }

    internal override fun extractDnsOverrideJsonObject(dnsOverride: String): JsonObject? {
        val root = JsonParser.parseString(dnsOverride)
        if (!root.isJsonObject) return null
        val rootObject = root.asJsonObject
        return rootObject
            .get("dns")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: rootObject
    }

    internal override fun jsonString(obj: JsonObject, key: String): String? {
        val element = obj.get(key) ?: return null
        if (!element.isJsonPrimitive) return null
        return element.asJsonPrimitive.takeIf { it.isString }?.asString?.trim()?.takeIf { it.isNotBlank() }
    }

    internal override fun hasNonBlankString(obj: JsonObject, key: String): Boolean {
        return !jsonString(obj, key).isNullOrBlank()
    }

    internal override fun hasDnsRuleMatcher(rule: JsonObject): Boolean {
        return dnsRuleMatcherKeys().any { key -> rule.has(key) && !rule.get(key).isJsonNull }
    }

    internal override fun asJsonObjectOrNull(element: com.google.gson.JsonElement): JsonObject? {
        return element.takeIf { it.isJsonObject }?.asJsonObject
    }

    internal override fun dnsOverrideKeys(): Set<String> {
        return setOf(
            "servers",
            "rules",
            "final",
            "strategy",
            "disable_cache",
            "disable_expire",
            "independent_cache",
            "fakeip"
        )
    }

    internal override fun knownDnsServerTags(): Set<String> {
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

    internal override fun dnsServerTypesWithoutEndpoint(): Set<String> {
        return setOf("local", "fakeip", "dhcp")
    }

    internal override fun dnsRuleMatcherKeys(): Set<String> {
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
            "outbound"
        )
    }

    internal override fun dnsRuleAddressFilterKeys(): Set<String> {
        return setOf(
            "ip_cidr",
            "source_ip_cidr",
            "source_geoip",
            "rule_set_ip_cidr_match_source",
            "rule_set_ip_cidr_accept_empty"
        )
    }

    internal override fun parseDnsOverrideConfig(dnsOverride: String?): DnsConfig? {
        val trimmed = dnsOverride?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        return Gson().fromJson(extractDnsOverrideJsonObject(trimmed), DnsConfig::class.java)
    }

    internal override fun applyDnsOverride(
        baseConfig: DnsConfig,
        overrideConfig: DnsConfig,
        sanitizeServer: (DnsServer) -> DnsServer
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
        val overrideRules = overrideConfig.rules.orEmpty().map { normalizeDnsOverrideRule(it) }
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
            fakeip = overrideConfig.fakeip ?: baseConfig.fakeip
        )
    }

    internal override fun normalizeDnsOverrideRule(rule: DnsRule): DnsRule {
        if (!rule.action.isNullOrBlank() || rule.server.isNullOrBlank()) {
            return rule
        }
        return rule.copy(action = "route")
    }

    internal override fun applyDnsOverrideDomainResolversForTest(
        outbounds: List<Outbound>,
        overrideConfig: DnsConfig
    ): List<Outbound> {
        return applyDnsOverrideDomainResolvers(outbounds, overrideConfig)
    }

    internal override fun resolveDnsOverrideDirectDnsServerTagsForTest(
        outbounds: List<Outbound>,
        overrideConfig: DnsConfig?
    ): Set<String> {
        return resolveDnsOverrideDirectDnsServerTags(outbounds, overrideConfig)
    }

    internal override fun shouldApplyDnsPreResolveToDomainForTest(
        domain: String,
        dnsOverride: DnsConfig?,
        outboundTag: String?): Boolean {
        return shouldApplyDnsPreResolveToDomain(domain, dnsOverride, outboundTag)
    }

    internal override fun shouldApplyDnsPreResolveToDomain(
        domain: String,
        dnsOverride: DnsConfig?,
        outboundTag: String?): Boolean {
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

    internal override fun applyDnsOverrideDomainResolvers(
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

    internal override fun resolveDnsOverrideDirectDnsServerTags(
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

    internal override fun buildDomainResolverForMatchedDnsOverrideRule(
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

    internal override fun dnsRuleCanResolveOutboundDomain(
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

    internal override fun dnsRuleMatchesOutbound(outboundTag: String?, rule: DnsRule): Boolean {
        val outbounds = dnsRuleOutboundValues(rule)
        if (outbounds.isEmpty()) return true
        if (outbounds.any { it.equals("any", ignoreCase = true) }) return true
        return outboundTag?.let { tag -> outbounds.any { it == tag } } == true
    }

    internal override fun dnsRuleOutboundValues(rule: DnsRule): List<String> {
        return when (val raw = rule.outboundRaw) {
            is String -> listOf(raw)
            is List<*> -> raw.mapNotNull { it?.toString() }
            else -> emptyList()
        }.map { it.trim() }.filter { it.isNotBlank() }
    }

    internal override fun dnsRuleHasDomainMatcher(rule: DnsRule): Boolean {
        return rule.domain.orEmpty().any { it.isNotBlank() } ||
            rule.domainSuffix.orEmpty().any { it.isNotBlank() } ||
            rule.domainKeyword.orEmpty().any { it.isNotBlank() } ||
            rule.domainRegex.orEmpty().any { it.isNotBlank() }
    }

    internal override fun dnsRuleHasNoUnsupportedOutboundDomainMatcher(rule: DnsRule): Boolean {
        return rule.geosite.orEmpty().none { it.isNotBlank() } &&
            rule.ruleSet.orEmpty().none { it.isNotBlank() } &&
            rule.inbound.orEmpty().none { it.isNotBlank() } &&
            rule.packageName.orEmpty().none { it.isNotBlank() } &&
            rule.userId.orEmpty().isEmpty()
    }

    internal override fun dnsRuleAppliesToAddressQuery(rule: DnsRule): Boolean {
        val queryTypes = rule.queryType.orEmpty()
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
        return queryTypes.isEmpty() || queryTypes.any { it == "A" || it == "AAAA" }
    }

    internal override fun dnsRuleMatchesDomain(domain: String, rule: DnsRule): Boolean {
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

    internal override fun normalizeDnsRuleDomain(value: String): String {
        return value.trim().trimEnd('.').lowercase()
    }

    internal override fun normalizeLocalDns(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return when {
            trimmed.isBlank() -> AppSettings.DEFAULT_LOCAL_DNS
            trimmed.equals(AppSettings.LEGACY_LOCAL_DNS, ignoreCase = true) -> AppSettings.DEFAULT_LOCAL_DNS
            isBareDnsDomain(trimmed) -> AppSettings.DEFAULT_LOCAL_DNS
            else -> trimmed
        }
    }

    internal override fun isBareDnsDomain(value: String): Boolean {
        if (value.contains("://") || value.contains("/")) return false
        if (isIpAddressValue(value)) return false
        return value.contains('.')
    }

    internal override fun normalizeRemoteDns(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        val remoteDns = trimmed.ifBlank { "https://dns.google/dns-query" }
        return normalizeCloudflareIpDohAddress(remoteDns)
    }

    internal override fun normalizeCloudflareIpDohAddress(address: String): String {
        val uri = runCatching { URI(address.trim()) }.getOrNull() ?: return address
        val scheme = uri.scheme?.lowercase() ?: return address
        val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return address
        if (scheme !in setOf("https", "h3") || host !in CLOUDFLARE_DOH_IPS) return address
        val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/dns-query"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://cloudflare-dns.com$path$query"
    }

    internal override fun buildDnsResolverForAddress(address: String): DomainResolveConfig? {
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

    internal override fun buildSpecialDnsServerOrNull(
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
            DnsServer(
                tag = tag,
                type = it,
                domainResolver = domainResolver,
                domainStrategy = domainStrategy,
                detour = detour
            )
        }
    }

    internal override fun dnsServerTypeFromScheme(scheme: String?): String {
        return when (scheme) {
            "https" -> "https"
            "h3" -> "h3"
            "tls" -> "tls"
            "quic" -> "quic"
            "tcp" -> "tcp"
            "udp" -> "udp"
            "dhcp" -> "dhcp"
            null -> "udp"
            else -> "udp"
        }
    }

    internal override fun shouldUseParsedDnsHost(scheme: String?): Boolean {
        return scheme == null || scheme in setOf("https", "h3", "tls", "quic", "tcp", "udp", "dhcp")
    }

    internal override fun buildDnsServer(
        address: String,
        tag: String,
        detour: String?,
        domainStrategy: String?,
        domainResolver: DomainResolveConfig?): DnsServer {
        val trimmed = address.trim()
        buildSpecialDnsServerOrNull(trimmed, tag, detour, domainStrategy, domainResolver)?.let {
            return it
        }

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return DnsServer(
                tag = tag,
                type = "udp",
                server = trimmed,
                serverPort = 53,
                domainResolver = domainResolver,
                domainStrategy = domainStrategy,
                detour = detour
            )
        }

        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: trimmed
        val port = if (uri.port > 0) uri.port else if (scheme == null || scheme == "udp") 53 else null
        val path = uri.path?.takeIf { it.isNotBlank() && it != "/" }

        val type = dnsServerTypeFromScheme(scheme)
        val server = if (shouldUseParsedDnsHost(scheme)) host else trimmed

        return DnsServer(
            tag = tag,
            type = type,
            server = server,
            serverPort = port,
            path = path,
            domainResolver = domainResolver,
            domainStrategy = domainStrategy,
            detour = detour
        )
    }

    override fun getInstance(context: Context): ConfigRepository {
        return instance ?: synchronized(this) {
            instance ?: ConfigRepository(context.applicationContext).also { instance = it }
        }
    }
}

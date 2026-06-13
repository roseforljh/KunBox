package com.kunk.singbox.repository

import android.content.Context
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.model.*
import com.kunk.singbox.database.entity.ProfileEntity
import java.io.File
import java.util.Collections
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.ResponseBody

@Suppress("TooManyFunctions")
abstract class ConfigRepositoryCompanionBase {
    internal val TAG = "ConfigRepository"

    internal val ROUTE_GROUP_AUTO_TAG_SUFFIX = "#AUTO"

    internal val ROUTE_GROUP_AUTO_TEST_URL = "https://www.gstatic.com/generate_204"

    internal val ROUTE_GROUP_AUTO_TEST_INTERVAL = "10m"

    internal val ROUTE_GROUP_AUTO_TEST_TOLERANCE = 50

    internal val PARALLEL_CONCURRENCY = 8

    internal val SUBSCRIPTION_FAILURE_THRESHOLD = 1

    internal val SUBSCRIPTION_CIRCUIT_BREAKER_WINDOW_MS = 10 * 60 * 1000L

    internal val SUBSCRIPTION_RESPONSE_MAX_BYTES = 1024 * 1024L

    internal val CONFIG_CACHE_EXPIRY_MS = 30 * 60 * 1000L

    internal val CONFIG_CACHE_CLEANUP_INTERVAL_MINUTES = 30L

    internal val REGEX_TRAFFIC = Regex("([\\d.]+)\\s*([KMGTPE]?)B?")

    internal val REGEX_KV_PAIRS =
        Regex("(?i)\\b(upload|download|total|expire)\\b\\s*[:=]\\s*\"?([^,;\\s\\n\\r}]+)\"?")

    internal val REGEX_SUBSCRIPTION_USERINFO = Regex("(?i)subscription[-_]userinfo\\s*[:=]\\s*\"?([^\"\\n\\r]+)\"?")

    internal val REGEX_TOTAL = Regex("TOT:([\\d.]+[KMGTPE]?)B?")

    internal val REGEX_EXPIRE_DATE = Regex("Expires:(\\d{4}-\\d{2}-\\d{2})")

    internal val REGEX_TRAFFIC_VALUE = Regex("([\\d.]+[KMGTPE]?)B?")

    internal val REGEX_REMAINING =
        Regex("(?i)(remaining|balance)\\s*[:=]?\\s*([\\d.]+\\s*[KMGTPE]?)\\s*B?")

    internal val REGEX_EXPIRE = Regex("(?i)(expiry|expires?|expire)\\s*[:=]?\\s*([^\\s,;]+)")

    internal val REGEX_SANITIZE_UUID = Regex("(?i)uuid\\s*[:=]\\s*[^\\\\n]+")

    internal val REGEX_SANITIZE_PASSWORD = Regex("(?i)password\\s*[:=]\\s*[^\\\\n]+")

    internal val REGEX_SANITIZE_TOKEN = Regex("(?i)token\\s*[:=]\\s*[^\\\\n]+")

    internal val REGEX_WHITESPACE_DASH = Regex("[\\s\\-_]")

    internal val REGEX_RULE_SET_JSON_KEYS = Regex("\"(version|rules|rule_set|type|tag|path|url|payload)\"\\s*:")

    internal val REGEX_RULE_SET_TEXT_LINE = Regex(
        "^(payload:|rules:|type:|version:|mode:|tag:|-\\s+|" +
            "[a-z0-9*._-]+\\.[a-z]{2,}|[a-z0-9*._-]+/[a-z0-9*._/-]+|[0-9a-f:.]+/[0-9]{1,3})",
        RegexOption.IGNORE_CASE
    )

    internal val REGEX_RULE_SET_ERROR_TEXT = Regex(
        "^(error|forbidden|not found|404|403|401|429|500|access denied|" +
            "invalid request|too many requests|rate limit|rate limited)\\b",
        RegexOption.IGNORE_CASE
    )

    internal val RULE_SET_BINARY_MAGIC = "SRS"

    internal val RULE_SET_MIN_SIZE_BYTES = 10L

    internal val RULE_SET_SNIFF_BYTES = 512

    internal val RULE_SET_TEXT_PARSE_LIMIT_BYTES = 256 * 1024L

    internal val RULE_SET_IP_THRESHOLD = 0.6

    internal val IP_DNS_QUERY_TYPES = listOf("A", "AAAA")

    internal val NON_IP_DNS_QUERY_TYPES = listOf("HTTPS", "SVCB")

    internal val REGEX_IP_CIDR = Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}" +
        "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)/([0-9]|[1-2][0-9]|3[0-2])\$")

    internal val REGEX_DOMAIN_LINE = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(\\." +
        "[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*\\.[a-zA-Z]{2,}\$")

    internal val TYPE_SAVED_PROFILES_DATA = object : TypeToken<SavedProfilesData>() {}.type

    internal val TYPE_OUTBOUND_LIST = object : TypeToken<List<Outbound>>() {}.type

    internal val MAX_NODE_ID_CACHE_SIZE = 2000

    internal val nodeIdCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(MAX_NODE_ID_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > MAX_NODE_ID_CACHE_SIZE
            }
        }
    )

    internal val REGEX_HTML_SUBSCRIPTION_INPUT = Regex(
        """<input[^>]+id=["']sub_url["'][^>]*>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    internal val REGEX_HTML_INPUT_VALUE = Regex(
        """value=["']([^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    internal val USER_AGENTS = listOf(
        "ClashMeta/1.18.0",
        "Clash.Meta/1.18.0",
        "Clash/1.18.0",
        "sing-box/1.13.1",
        "sing-box/1.13.0",
        "SFA/1.13.0",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    internal data class OutboundSemanticTestInput(
        val mode: RuleSetOutboundMode?,
        val value: String?,
        val selectorTag: String,
        val outbounds: List<Outbound>,
        val profiles: List<ProfileEntity>,
        val nodeTagResolver: (String?) -> String?
    )

    internal val DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG = "dns-bootstrap"

    internal val CLOUDFLARE_DOH_IPS = setOf(
        "1.1.1.1",
        "1.0.0.1",
        "2606:4700:4700::1111",
        "2606:4700:4700::1001"
    )

    internal var instance: ConfigRepository? = null

    // Virtual declarations keep split companion helpers callable across files.
    abstract fun stableNodeId(profileId: String, outboundTag: String): String

    internal abstract fun buildRouteGroupAutoTag(groupTag: String): String

    internal abstract fun buildProfileRouteGroupOutboundsForTest(
        groupTag: String,
        nodeTags: List<String>
    ): List<Outbound>

    internal abstract fun applySelectorSafeOutboundsForTest(outbounds: List<Outbound>): List<Outbound>

    internal abstract fun buildConfigWithOutboundsPreservingProfileSettings(
        existingConfig: SingBoxConfig?,
        outbounds: List<Outbound>
    ): SingBoxConfig

    internal abstract fun writeTextFileAtomicallyForTest(targetFile: File, content: String)

    internal abstract fun subscriptionResponseMaxBytesForTest(): Long

    internal abstract fun isSubscriptionContentLengthTooLargeForTest(contentLength: Long): Boolean

    internal abstract fun isSubscriptionContentLengthTooLarge(contentLength: Long): Boolean

    internal abstract fun readSubscriptionResponseBody(responseBody: ResponseBody): String

    internal abstract fun writeTextFileAtomically(targetFile: File, content: String)

    internal abstract fun createSiblingTempFile(targetFile: File): File

    internal abstract fun moveTempFileIntoPlace(tempFile: File, targetFile: File)

    internal abstract fun sanitizeSelectorSafeOutbounds(outbounds: List<Outbound>): List<Outbound>

    internal abstract fun sanitizeSelectorLikeOutbound(outbound: Outbound, allOutboundTags: Set<String>): Outbound

    internal abstract fun buildProfileRouteGroupOutbounds(
        groupTag: String,
        nodeTags: List<String>
    ): List<Outbound>

    internal abstract fun buildBootstrapDnsRules(
        serverAddresses: List<String>,
        bootstrapV4Tag: String,
        bootstrapV6Tag: String,
        bootstrapTag: String
    ): List<DnsRule>

    internal abstract fun isIpAddressValue(address: String?): Boolean

    internal abstract fun extractHostFromAddress(address: String): String?

    internal abstract fun extractHostByUri(address: String): String?

    internal abstract fun extractSubscriptionUrlFromHtml(html: String): String?

    internal abstract fun looksLikeHtmlSubscriptionPage(contentType: String?, body: String): Boolean

    internal abstract fun extractSubscriptionHost(url: String): String?

    internal abstract fun looksLikeSubscriptionUrlForImport(content: String): Boolean

    internal abstract fun prioritizeUserAgents(preferredUserAgent: String?): List<String>

    internal abstract fun buildSubscriptionAttemptUserAgents(
        preferredUserAgent: String?,
        circuitBrokenUserAgents: Set<String>
    ): List<String>

    internal abstract fun filterCircuitBrokenUserAgents(
        userAgents: List<String>,
        circuitBrokenUserAgents: Set<String>
    ): List<String>

    internal abstract fun shouldRecordSubscriptionNetworkFailure(exception: Exception): Boolean

    internal abstract fun shouldStopSubscriptionFallback(
        httpStatusCode: Int? = null,
        looksLikeHtmlInfoPage: Boolean = false
    ): Boolean

    internal abstract fun resolveSubscriptionUpdateBudgetSeconds(configuredTimeoutSeconds: Int): Long

    internal abstract fun resolveSubscriptionAttemptTimeoutBudget(
        totalBudgetSeconds: Long,
        elapsedMs: Long
    ): SubscriptionAttemptTimeoutBudget?

    internal abstract fun resolveAppRuleOutboundMode(mode: RuleSetOutboundMode?): RuleSetOutboundMode

    internal abstract fun resolveAppGroupOutboundMode(mode: RuleSetOutboundMode?): RuleSetOutboundMode

    internal abstract fun resolveRuleSetOutboundMode(mode: RuleSetOutboundMode?): RuleSetOutboundMode

    internal abstract fun resolveCustomRuleOutboundMode(
        mode: RuleSetOutboundMode?,
        oldOutbound: OutboundTag
    ): RuleSetOutboundMode

    internal abstract fun resolveRouteModeForRuleSetForTest(ruleSet: RuleSet): RuleSetOutboundMode

    internal abstract fun resolveRouteModeForAppGroupForTest(group: AppGroup): RuleSetOutboundMode

    internal abstract fun resolveRouteModeForCustomRuleForTest(rule: CustomRule): RuleSetOutboundMode

    internal abstract fun filterAppliedRemoteRuleSets(
        ruleSets: List<RuleSet>,
        validTags: Set<String>
    ): List<RuleSet>

    internal abstract fun filterAppliedRemoteRuleSetsForTest(
        ruleSets: List<RuleSet>,
        validTags: Set<String>
    ): List<RuleSet>

    internal abstract fun detectRuleSetRuleTypeForTest(file: java.io.File, tag: String = ""): ConfigRepository.RuleSetRuleType

    abstract fun detectRuleSetRuleTypeStatic(file: java.io.File, tag: String = ""): ConfigRepository.RuleSetRuleType

    internal abstract fun detectRuleSetRuleTypeFromFile(file: java.io.File, tag: String = ""): ConfigRepository.RuleSetRuleType

    internal abstract fun detectRuleSetRuleTypeFromTag(tag: String): ConfigRepository.RuleSetRuleType

    internal abstract fun detectRuleSetRuleTypeFromSample(sample: ByteArray): ConfigRepository.RuleSetRuleType

    internal abstract fun detectRuleTypeFromTextContent(text: String): ConfigRepository.RuleSetRuleType

    internal abstract fun isIpRuleLineContent(line: String): Boolean

    internal abstract fun isLikelyIpv6Cidr(line: String): Boolean

    internal abstract fun isIpRuleWithPrefix(line: String): Boolean

    internal abstract fun isDomainRuleLineContent(line: String): Boolean

    internal abstract fun readRuleSetSampleFromFile(file: java.io.File): ByteArray

    internal abstract fun isLikelyTextRuleSetFromBytes(sample: ByteArray): Boolean

    internal abstract fun launchSubscriptionDnsPreResolve(
        scope: CoroutineScope,
        profileId: String,
        enabled: Boolean,
        updateRunId: Long? = null,
        onStarted: () -> Unit = {},
        onFinished: () -> Unit = {},
        preResolve: suspend () -> Boolean
    ): Job?

    internal abstract fun setProfileUpdateStageIfCurrent(
        profilesState: MutableStateFlow<List<ProfileUi>>,
        activeUpdateRuns: Map<String, Long>,
        profileId: String,
        runId: Long,
        stage: SubscriptionUpdateStage?
    )

    internal abstract fun resolveSubscriptionUpdateStage(
        stageName: String?
    ): SubscriptionUpdateStage?

    internal abstract fun toRouteRule(semantic: ConfigRepository.OutboundSemantic, selectorTag: String): RouteRule

    internal abstract fun buildRunRouteRulesForTest(
        settings: AppSettings,
        selectorTag: String,
        outbounds: List<Outbound>,
        profiles: List<ProfileEntity>,
        validRuleSets: List<RuleSetConfig>,
        nodeTagResolver: (String?) -> String? = { null }
    ): List<RouteRule>

    internal abstract fun resolveDnsStrategyForTest(strategy: DnsStrategy, mode: IpVersionMode): String

    internal abstract fun buildBypassLanRulesForTest(settings: AppSettings): List<RouteRule>

    internal abstract fun buildMulticastRejectRulesForTest(settings: AppSettings): List<RouteRule>

    internal abstract fun buildHijackDnsRulesForTest(): List<RouteRule>

    internal abstract fun buildHijackDnsRulesStatic(): List<RouteRule>

    internal abstract fun buildRunRouteRules(
        settings: AppSettings,
        selectorTag: String,
        outbounds: List<Outbound>,
        profiles: List<ProfileUi>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>
    ): List<RouteRule>

    internal abstract fun buildQuicBlockRuleStatic(settings: AppSettings): List<RouteRule>

    internal abstract fun buildIcmpEchoRulesStatic(settings: AppSettings): List<RouteRule>

    internal abstract fun buildDefaultRulesStatic(settings: AppSettings, selectorTag: String): List<RouteRule>

    internal abstract fun buildCustomRuleSetRulesStatic(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        profiles: List<ProfileUi>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>
    ): List<RouteRule>

    internal abstract fun buildBypassLanRulesStatic(settings: AppSettings): List<RouteRule>

    internal abstract fun buildMulticastRejectRulesStatic(settings: AppSettings): List<RouteRule>

    internal abstract fun resolveOutboundSemantic(
        mode: RuleSetOutboundMode?,
        value: String?,
        context: ConfigRepositoryOutboundSemanticContext
    ): ConfigRepository.OutboundSemantic

    internal abstract fun toRouteRuleForTest(semantic: ConfigRepository.OutboundSemantic, selectorTag: String): RouteRule

    internal abstract fun resolveOutboundSemanticForTest(input: OutboundSemanticTestInput): ConfigRepository.OutboundSemantic

    internal abstract fun resolveProfileSelectorDefault(
        nodeIds: List<String>,
        nodeTagMap: Map<String, String>,
        rememberedNodeId: String?,
        savedNodeLatencies: Map<String, Long>
    ): String?

    internal abstract fun buildDynamicDnsServerTag(detourTag: String): String

    internal abstract fun ensureDynamicRemoteDnsServers(
        dnsServers: MutableList<DnsServer>,
        semantics: List<ConfigRepository.OutboundSemantic>,
        remoteDnsAddr: String,
        remoteStrategy: String?,
        remoteResolver: DomainResolveConfig?
    )

    internal abstract fun buildDynamicDnsServersForTest(
        semantics: List<ConfigRepository.OutboundSemantic>,
        remoteDnsAddr: String,
        remoteStrategy: String?,
        remoteResolver: DomainResolveConfig?
    ): List<DnsServer>

    internal abstract fun buildDynamicRemoteDnsServerForTest(
        detourTag: String,
        remoteDnsAddr: String,
        remoteStrategy: String?,
        remoteResolver: DomainResolveConfig?
    ): DnsServer

    internal abstract fun buildDynamicRemoteDnsServer(
        detourTag: String,
        remoteDnsAddr: String,
        remoteStrategy: String?,
        remoteResolver: DomainResolveConfig?
    ): DnsServer

    internal abstract fun resolveActiveEchDnsServerForTest(activeTag: String, outbounds: List<Outbound>): String?

    internal abstract fun resolveActiveEchDnsServer(activeTag: String, outbounds: List<Outbound>): String?

    internal abstract fun needsLegacyEchDnsRepairForTest(config: SingBoxConfig): Boolean

    internal abstract fun needsLegacyEchDnsRepair(config: SingBoxConfig): Boolean

    internal abstract fun resolveFakeIpRanges(fakeIpRange: String?): ConfigRepositoryFakeIpRanges

    internal abstract fun buildFakeIpDnsServer(fakeIpRange: String?): DnsServer

    internal abstract fun buildFakeIpDnsServerForTest(fakeIpRange: String?): DnsServer

    internal abstract fun buildFakeIpConfig(fakeIpRange: String?): DnsFakeIpConfig

    internal abstract fun dnsServerTagForSemantic(
        semantic: ConfigRepository.OutboundSemantic,
        fakeDnsEnabled: Boolean,
        directServerTag: String = "local",
        proxyServerTag: String = if (fakeDnsEnabled) "fakeip-dns" else "remote"
    ): String?

    internal abstract fun dnsServerTagForSemanticForTest(
        semantic: ConfigRepository.OutboundSemantic,
        fakeDnsEnabled: Boolean,
        directServerTag: String = "local",
        proxyServerTag: String = if (fakeDnsEnabled) "fakeip-dns" else "remote"
    ): String?

    internal abstract fun resolveDnsServerTagForRuleSemanticForTest(
        semantic: ConfigRepository.OutboundSemantic,
        fakeDnsEnabled: Boolean,
        directServerTag: String = "local",
        proxyServerTag: String = if (fakeDnsEnabled) "fakeip-dns" else "remote"
    ): String?

    internal abstract fun buildDnsRouteToProxyForTest(
        fakeDnsEnabled: Boolean,
        proxyServerTag: String,
        rule: DnsRule
    ): List<DnsRule>

    internal abstract fun buildDnsRouteToNonDirectForTest(
        fakeDnsEnabled: Boolean,
        serverTag: String,
        rule: DnsRule
    ): List<DnsRule>

    internal abstract fun buildNonIpDnsFallbackRuleForTest(serverTag: String): DnsRule

    internal abstract fun buildDnsRouteToDirectForTest(rule: DnsRule): DnsRule

    internal abstract fun sortRuleSetsForDnsAndRoutePriorityForTest(ruleSets: List<RuleSet>): List<RuleSet>

    internal abstract fun buildQuicBlockRuleForTest(settings: AppSettings): List<RouteRule>

    internal abstract fun buildTunFakeIpDnsRulesForTest(fakeDnsEnabled: Boolean): List<DnsRule>

    internal abstract fun buildOutboundDomainResolverDnsRulesForTest(outbounds: List<Outbound>): List<DnsRule>

    internal abstract fun buildOutboundDomainResolverDnsRulesForRuntime(outbounds: List<Outbound>): List<DnsRule>

    internal abstract fun applyDefaultOutboundDomainResolverForTest(
        outbounds: List<Outbound>,
        defaultResolverTag: String,
        defaultResolverStrategy: String? = null
    ): List<Outbound>

    internal abstract fun buildEchDnsRulesForTest(outbounds: List<Outbound>, serverTag: String): List<DnsRule>

    internal abstract fun buildEchAwareHttpsSvcbDnsRulesForTest(
        blockQuic: Boolean,
        outbounds: List<Outbound>,
        echQueryServerTag: String
    ): List<DnsRule>

    internal abstract fun buildTunFakeIpDnsRulesStatic(fakeDnsEnabled: Boolean): List<DnsRule>

    internal abstract fun buildOutboundDomainResolverDnsRules(outbounds: List<Outbound>): List<DnsRule>

    internal abstract fun applyDefaultOutboundDomainResolver(
        outbounds: List<Outbound>,
        defaultResolverTag: String,
        defaultResolverStrategy: String? = null
    ): List<Outbound>

    internal abstract fun buildEchAwareHttpsSvcbDnsRules(
        blockQuic: Boolean,
        outbounds: List<Outbound>,
        echQueryServerTag: String
    ): List<DnsRule>

    internal abstract fun buildEchDnsRules(outbounds: List<Outbound>, serverTag: String): List<DnsRule>

    internal abstract fun buildNonIpDnsFallbackRule(serverTag: String): DnsRule

    internal abstract fun buildDnsRouteToDirect(
        serverTag: String,
        directServerTag: String,
        rule: DnsRule
    ): DnsRule

    internal abstract fun buildDnsRouteToNonDirect(
        fakeDnsEnabled: Boolean,
        serverTag: String,
        rule: DnsRule
    ): List<DnsRule>

    internal abstract fun sortRuleSetsForDnsAndRoutePriority(ruleSets: List<RuleSet>): List<RuleSet>

    internal abstract fun resolveProxyDnsDetourTagForTest(
        selectorTag: String,
        outbounds: List<Outbound>
    ): String

    internal abstract fun resolveRunDnsFinalServerForTest(
        routingMode: RoutingMode,
        defaultRule: DefaultRule,
        fakeDnsEnabled: Boolean,
        proxyServerTag: String,
        stableRemoteServerTag: String = "remote",
        directServerTag: String = "local"
    ): String

    internal abstract fun sanitizeInjectedDnsServerForTest(
        server: DnsServer,
        routingMode: RoutingMode,
        proxyDetourTag: String,
        directDnsServerTags: Set<String> = emptySet()
    ): DnsServer

    internal abstract fun sanitizeInjectedDnsServerForRuntime(
        server: DnsServer,
        routingMode: RoutingMode,
        proxyDetourTag: String,
        directDnsServerTags: Set<String> = emptySet()
    ): DnsServer

    internal abstract fun normalizeInjectedDnsServer(server: DnsServer): DnsServer

    internal abstract fun applyDnsOverrideForTest(
        baseConfig: DnsConfig,
        overrideConfig: DnsConfig,
        sanitizeServer: (DnsServer) -> DnsServer = { it }
    ): DnsConfig

    internal abstract fun parseDnsOverrideForTest(dnsOverride: String?): DnsConfig?

    internal abstract fun buildDnsOverrideCompatibilityWarningForTest(dnsOverride: String?): String?

    abstract fun buildDnsOverrideCompatibilityWarning(dnsOverride: String?): String?

    internal abstract fun parseDnsOverrideObjectForWarning(dnsOverride: String): JsonObject?

    internal abstract fun hasDnsOverrideShape(dnsObject: JsonObject): Boolean

    internal abstract fun collectDnsOverrideCompatibilityIssues(dnsObject: JsonObject): Set<String>

    internal abstract fun collectDnsServerCompatibilityIssues(
        dnsObject: JsonObject,
        definedServerTags: MutableSet<String>,
        issues: MutableSet<String>
    )

    internal abstract fun collectSingleDnsServerCompatibilityIssues(
        server: JsonObject?,
        definedServerTags: MutableSet<String>,
        overrideTags: MutableSet<String>,
        issues: MutableSet<String>
    )

    internal abstract fun collectDnsServerTagIssues(
        server: JsonObject,
        definedServerTags: MutableSet<String>,
        overrideTags: MutableSet<String>,
        issues: MutableSet<String>
    )

    internal abstract fun collectDnsServerLegacyFieldIssues(server: JsonObject, issues: MutableSet<String>)

    internal abstract fun collectDnsServerEndpointIssues(server: JsonObject, issues: MutableSet<String>)

    internal abstract fun collectDnsRuleCompatibilityIssues(
        dnsObject: JsonObject,
        definedServerTags: Set<String>,
        issues: MutableSet<String>
    )

    internal abstract fun collectSingleDnsRuleCompatibilityIssues(
        rule: JsonObject?,
        definedServerTags: Set<String>,
        issues: MutableSet<String>
    )

    internal abstract fun collectDnsRuleActionIssues(
        rule: JsonObject,
        definedServerTags: Set<String>,
        issues: MutableSet<String>
    )

    internal abstract fun collectDnsRuleLegacyFieldIssues(rule: JsonObject, issues: MutableSet<String>)

    internal abstract fun collectDnsTopLevelCompatibilityIssues(dnsObject: JsonObject, issues: MutableSet<String>)

    internal abstract fun formatDnsOverrideCompatibilityWarning(issues: Set<String>): String?

    internal abstract fun extractDnsOverrideJsonObject(dnsOverride: String): JsonObject?

    internal abstract fun jsonString(obj: JsonObject, key: String): String?

    internal abstract fun hasNonBlankString(obj: JsonObject, key: String): Boolean

    internal abstract fun hasDnsRuleMatcher(rule: JsonObject): Boolean

    internal abstract fun asJsonObjectOrNull(element: com.google.gson.JsonElement): JsonObject?

    internal abstract fun dnsOverrideKeys(): Set<String>

    internal abstract fun knownDnsServerTags(): Set<String>

    internal abstract fun dnsServerTypesWithoutEndpoint(): Set<String>

    internal abstract fun dnsRuleMatcherKeys(): Set<String>

    internal abstract fun dnsRuleAddressFilterKeys(): Set<String>

    internal abstract fun parseDnsOverrideConfig(dnsOverride: String?): DnsConfig?

    internal abstract fun applyDnsOverride(
        baseConfig: DnsConfig,
        overrideConfig: DnsConfig,
        sanitizeServer: (DnsServer) -> DnsServer
    ): DnsConfig

    internal abstract fun normalizeDnsOverrideRule(rule: DnsRule): DnsRule

    internal abstract fun applyDnsOverrideDomainResolversForTest(
        outbounds: List<Outbound>,
        overrideConfig: DnsConfig
    ): List<Outbound>

    internal abstract fun resolveDnsOverrideDirectDnsServerTagsForTest(
        outbounds: List<Outbound>,
        overrideConfig: DnsConfig?
    ): Set<String>

    internal abstract fun shouldApplyDnsPreResolveToDomainForTest(
        domain: String,
        dnsOverride: DnsConfig?,
        outboundTag: String? = null
    ): Boolean

    internal abstract fun shouldApplyDnsPreResolveToDomain(
        domain: String,
        dnsOverride: DnsConfig?,
        outboundTag: String? = null
    ): Boolean

    internal abstract fun applyDnsOverrideDomainResolvers(
        outbounds: List<Outbound>,
        overrideConfig: DnsConfig
    ): List<Outbound>

    internal abstract fun resolveDnsOverrideDirectDnsServerTags(
        outbounds: List<Outbound>,
        overrideConfig: DnsConfig?
    ): Set<String>

    internal abstract fun buildDomainResolverForMatchedDnsOverrideRule(
        domain: String,
        outboundTag: String?,
        rule: DnsRule
    ): DomainResolveConfig?

    internal abstract fun dnsRuleCanResolveOutboundDomain(
        domain: String,
        outboundTag: String?,
        rule: DnsRule
    ): Boolean

    internal abstract fun dnsRuleMatchesOutbound(outboundTag: String?, rule: DnsRule): Boolean

    internal abstract fun dnsRuleOutboundValues(rule: DnsRule): List<String>

    internal abstract fun dnsRuleHasDomainMatcher(rule: DnsRule): Boolean

    internal abstract fun dnsRuleHasNoUnsupportedOutboundDomainMatcher(rule: DnsRule): Boolean

    internal abstract fun dnsRuleAppliesToAddressQuery(rule: DnsRule): Boolean

    internal abstract fun dnsRuleMatchesDomain(domain: String, rule: DnsRule): Boolean

    internal abstract fun normalizeDnsRuleDomain(value: String): String

    internal abstract fun normalizeLocalDns(value: String?): String

    internal abstract fun isBareDnsDomain(value: String): Boolean

    internal abstract fun normalizeRemoteDns(value: String?): String

    internal abstract fun normalizeCloudflareIpDohAddress(address: String): String

    internal abstract fun buildDnsResolverForAddress(address: String): DomainResolveConfig?

    internal abstract fun buildSpecialDnsServerOrNull(
        trimmed: String,
        tag: String,
        detour: String?,
        domainStrategy: String?,
        domainResolver: DomainResolveConfig?
    ): DnsServer?

    internal abstract fun dnsServerTypeFromScheme(scheme: String?): String

    internal abstract fun shouldUseParsedDnsHost(scheme: String?): Boolean

    internal abstract fun buildDnsServer(
        address: String,
        tag: String,
        detour: String? = null,
        domainStrategy: String? = null,
        domainResolver: DomainResolveConfig? = null
    ): DnsServer

    abstract fun getInstance(context: Context): ConfigRepository
}

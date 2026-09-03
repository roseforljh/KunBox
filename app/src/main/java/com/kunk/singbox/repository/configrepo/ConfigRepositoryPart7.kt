@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.repository

import android.util.Log
import com.kunk.singbox.model.*
import com.kunk.singbox.repository.config.InboundBuilder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun ConfigRepository.buildRunLogConfig(settings: AppSettings): LogConfig {
    return LogConfig(
        level = ConfigRepository.resolveRunLogLevel(settings.resolvedTrafficCaptureMode()),
        timestamp = true
    )
}

internal fun ConfigRepository.getOrCreateClashApiSecret(): String {
    val secretFile = File(context.noBackupFilesDir, "clash_api.secret")
    val existing = runCatching {
        secretFile.takeIf(File::isFile)?.readText(Charsets.UTF_8)
    }.getOrNull()
    val secret = ConfigRepository.resolveClashApiSecret(existing) {
        buildString {
            repeat(2) { append(UUID.randomUUID().toString().replace("-", "")) }
        }
    }
    if (secret != existing?.trim()) {
        ConfigRepository.writeTextFileAtomically(secretFile, secret)
    }
    return secret
}

internal fun ConfigRepository.buildRunExperimentalConfig(settings: AppSettings): ExperimentalConfig {
    val singboxDataDir = File(context.filesDir, "singbox_data").also { it.mkdirs() }

    val clashApiPort = findAvailablePort(9090)
    val clashApi = ClashApiConfig(
        externalController = "127.0.0.1:$clashApiPort",
        secret = getOrCreateClashApiSecret(),
        defaultMode = "rule"
    )

    return ExperimentalConfig(
        cacheFile = CacheFileConfig(
            enabled = true,
            path = File(singboxDataDir, "cache.db").absolutePath,
            storeFakeip = settings.fakeDnsEnabled
        ),
        clashApi = clashApi
    )
}

internal fun ConfigRepository.buildRunInbounds(
    settings: AppSettings,
    rootRoutingPlan: RootAppRoutingPlan? = null
): List<Inbound> =
    InboundBuilder.build(
        settings.copy(tunMtu = getEffectiveTunMtu(settings)),
        getEffectiveTunStack(settings.tunStack),
        rootRoutingPlan
    )

internal fun ConfigRepository.resolveRunDnsFinalServer(
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

@Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
    "CognitiveComplexMethod",
    "NestedBlockDepth"
)
internal fun ConfigRepository.buildRunDns(
    settings: AppSettings,
    validRuleSets: List<RuleSetConfig>,
    outboundsContext: ConfigRepositoryRunOutboundsContext,
    dnsOverride: DnsConfig? = null,
    originalDns: DnsConfig? = null,
    rootRoutingPlan: RootAppRoutingPlan? = null
): DnsConfig {
    val dnsServers = mutableListOf<DnsServer>()
    val dnsRules = mutableListOf<DnsRule>()
    val customDomainDnsRules = mutableListOf<DnsRule>()
    val appDnsRules = mutableListOf<DnsRule>()
    val ruleSetDnsRules = mutableListOf<DnsRule>()

    val profiles = _profiles.value
    val proxyDetourTag = outboundsContext.selectorTag
    val proxyServerTag = ConfigRepository.buildDynamicDnsServerTag(proxyDetourTag)
    val directServerTag = "local"

    fun parseDomainList(input: String): List<String> {
        return input
            .split("\n", "\r", ";")
            .flatMap { rawEntry ->
                val entry = rawEntry.trim()
                when {
                    entry.isEmpty() -> emptyList()
                    entry.contains(",") && !entry.contains(".") -> {
                        entry.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    }

                    else -> listOf(entry)
                }
            }
            .distinct()
    }

    val localDnsAddr = ConfigRepository.normalizeLocalDns(settings.localDns)
    val remoteDnsAddr = ConfigRepository.normalizeRemoteDns(settings.remoteDns)
    val bootstrapStrategy = resolveDnsStrategy(settings.serverAddressStrategy, settings.ipVersionMode)
    val bootstrapTag = "dns-bootstrap"
    dnsServers.add(
        ConfigRepository.buildBootstrapDnsServer(
            localDnsAddress = localDnsAddr,
            tag = bootstrapTag,
            domainStrategy = bootstrapStrategy
        )
    )

    val echQueryServerTag = bootstrapTag
    dnsRules.addAll(
        ConfigRepository.buildEchAwareHttpsSvcbDnsRules(
            blockQuic = settings.blockQuic,
            outbounds = outboundsContext.outbounds,
            echQueryServerTag = echQueryServerTag
        )
    )
    val localResolver = ConfigRepository.buildDnsResolverForAddress(localDnsAddr)
    val localServer = ConfigRepository.buildDnsServer(
        address = localDnsAddr,
        tag = "local",
        domainStrategy = resolveDirectDnsStrategy(settings.directDnsStrategy, settings.ipVersionMode),
        domainResolver = localResolver
    )
    dnsServers.add(localServer)
    val remoteResolver = ConfigRepository.buildDnsResolverForAddress(remoteDnsAddr)
    val remoteDetour = if (settings.routingMode != RoutingMode.GLOBAL_DIRECT) proxyDetourTag else null
    val remoteServer = ConfigRepository.buildDnsServer(
        address = remoteDnsAddr,
        tag = "remote",
        detour = remoteDetour,
        domainStrategy = resolveDnsStrategy(settings.remoteDnsStrategy, settings.ipVersionMode),
        domainResolver = remoteResolver
    )
    dnsServers.add(remoteServer)
    val remoteStrategy = resolveDnsStrategy(settings.remoteDnsStrategy, settings.ipVersionMode)
    ConfigRepository.ensureDynamicRemoteDnsServers(
        dnsServers = dnsServers,
        semantics = listOf(ConfigRepository.OutboundSemantic.RouteTag(proxyDetourTag)),
        remoteDnsAddr = remoteDnsAddr,
        remoteStrategy = remoteStrategy,
        remoteResolver = remoteResolver
    )
    val bootstrapDnsAddresses = listOf(localDnsAddr, remoteDnsAddr)

    dnsRules.addAll(
        ConfigRepository.buildBootstrapDnsRules(
            serverAddresses = bootstrapDnsAddresses,
            bootstrapV4Tag = bootstrapTag,
            bootstrapV6Tag = bootstrapTag,
            bootstrapTag = bootstrapTag
        )
    )

    if (settings.fakeDnsEnabled) {
        dnsServers.add(ConfigRepository.buildFakeIpDnsServer(settings.fakeIpRange))
    }
    val customDomainRulesForDns = settings.customRules
        .filter { ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode) && it.enabled }
        .filter {
            it.type == RuleType.DOMAIN ||
                it.type == RuleType.DOMAIN_SUFFIX ||
                it.type == RuleType.DOMAIN_KEYWORD ||
                it.type == RuleType.GEOSITE
        }
    val domainSemantics = mutableListOf<ConfigRepository.OutboundSemantic>()

    if (customDomainRulesForDns.isNotEmpty()) {
        val orderedRules = mutableListOf<Pair<DnsRule, ConfigRepository.OutboundSemantic>>()

        fun addDnsRuleForSemantic(rule: DnsRule, semantic: ConfigRepository.OutboundSemantic) {
            domainSemantics.add(semantic)
            orderedRules.add(rule to semantic)
        }

        customDomainRulesForDns.forEach { rule ->
            val values = rule.value
                .split("\n", "\r", ",", ";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (values.isEmpty()) return@forEach

            val semantic = ConfigRepository.resolveAppOutboundSemanticStrict(
                mode = ConfigRepository.resolveCustomRuleOutboundMode(rule.outboundMode, rule.outbound),
                value = rule.outboundValue,
                context = ConfigRepositoryOutboundSemanticContext(
                    selectorTag = outboundsContext.selectorTag,
                    outbounds = outboundsContext.outbounds,
                    profiles = profiles,
                    nodeTagResolver = outboundsContext.ruleNodeTagResolver
                ),
                label = "DNS 自定义规则「${rule.name}」"
            )

            ConfigRepository.buildCustomDnsRuleMatcher(rule.type, values.distinct())
                ?.let { addDnsRuleForSemantic(it, semantic) }
        }

        customDomainDnsRules.addAll(
            ConfigRepository.buildOrderedDnsRules(
                entries = orderedRules,
                fakeDnsEnabled = settings.fakeDnsEnabled,
                directServerTag = directServerTag,
                proxyServerTag = proxyServerTag
            )
        )
    }
    val validRuleSetTags = validRuleSets.mapNotNull { it.tag }.toSet()
    val ipOnlyRuleSetTags = validRuleSets.mapNotNull { ruleSet ->
        val tag = ruleSet.tag ?: return@mapNotNull null
        val path = ruleSet.path ?: return@mapNotNull null
        tag.takeIf {
            ConfigRepository.detectRuleSetRuleTypeStatic(File(path), tag) ==
                ConfigRepository.RuleSetRuleType.IP
        }
    }.toSet()
    val orderedRuleSetRules = mutableListOf<Pair<DnsRule, ConfigRepository.OutboundSemantic>>()
    val ruleSetSemantics = mutableListOf<ConfigRepository.OutboundSemantic>()

    fun addRuleSetDnsRule(rule: DnsRule, semantic: ConfigRepository.OutboundSemantic) {
        ruleSetSemantics.add(semantic)
        orderedRuleSetRules.add(rule to semantic)
    }

    ConfigRepository.sortRuleSetsForRouting(
        settings.ruleSets.filter {
            ConfigRepository.shouldApplyRuleSetRules(settings.routingMode) && it.enabled
        }
    ).forEach { ruleSet ->
        val tag = ruleSet.tag
        if (tag.isBlank() || tag !in validRuleSetTags) return@forEach

        val ruleSetConfig = validRuleSets.find { it.tag == tag }
        val ruleSetPath = ruleSetConfig?.path ?: return@forEach
        val ruleSetFile = File(ruleSetPath)
        val ruleType = ConfigRepository.detectRuleSetRuleTypeStatic(ruleSetFile, tag)

        // Only add domain-based or mixed rulesets to DNS rules.
        // Pure IP rulesets (like GeoIP) should only be used in Route rules.
        if (ruleType == ConfigRepository.RuleSetRuleType.IP) {
            Log.d(ConfigRepository.TAG, "Skipping IP-only ruleset in DNS rules: $tag")
            return@forEach
        }

        val semantic = ConfigRepository.resolveAppOutboundSemanticStrict(
            mode = ConfigRepository.resolveRuleSetOutboundMode(ruleSet.outboundMode),
            value = ruleSet.outboundValue,
            context = ConfigRepositoryOutboundSemanticContext(
                selectorTag = outboundsContext.selectorTag,
                outbounds = outboundsContext.outbounds,
                profiles = profiles,
                nodeTagResolver = outboundsContext.ruleNodeTagResolver
            ),
            label = "DNS 规则集「${ruleSet.tag}」"
        )
        addRuleSetDnsRule(
            DnsRule(
                ruleSet = listOf(tag),
                inbound = ConfigRepository.normalizeRuleSetInboundTags(ruleSet.inbounds, settings)
            ),
            semantic
        )
    }

    ruleSetDnsRules.addAll(
        ConfigRepository.buildOrderedDnsRules(
            entries = orderedRuleSetRules,
            fakeDnsEnabled = settings.fakeDnsEnabled,
            directServerTag = directServerTag,
            proxyServerTag = proxyServerTag
        )
    )
    val orderedPackageRules = mutableListOf<Pair<DnsRule, ConfigRepository.OutboundSemantic>>()
    val packageSemantics = mutableListOf<ConfigRepository.OutboundSemantic>()

    fun addPackageDnsRule(rule: DnsRule, semantic: ConfigRepository.OutboundSemantic) {
        packageSemantics.add(semantic)
        orderedPackageRules.add(rule to semantic)
    }

    if (rootRoutingPlan != null) {
        rootRoutingPlan.lanes.sortedBy(RootAppRouteLane::slot).forEach { lane ->
            addPackageDnsRule(
                DnsRule(inbound = lane.inboundTags(rootRoutingPlan.proxyIpv4, rootRoutingPlan.proxyIpv6)),
                ConfigRepository.rootLaneSemantic(lane)
            )
        }
    } else {
        settings.appRules
            .filter { ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode) && it.enabled }
            .forEach { rule ->
                val packageNames = resolvePackagesSharingUid(
                    filterVpnCapturedPackages(settings, listOf(rule.packageName))
                )
                if (packageNames.isEmpty()) return@forEach
                val semantic = ConfigRepository.resolveAppOutboundSemanticStrict(
                    mode = ConfigRepository.resolveAppRuleOutboundMode(rule.outboundMode),
                    value = rule.outboundValue,
                    context = ConfigRepositoryOutboundSemanticContext(
                        selectorTag = outboundsContext.selectorTag,
                        outbounds = outboundsContext.outbounds,
                        profiles = profiles,
                        nodeTagResolver = outboundsContext.ruleNodeTagResolver
                    ),
                    label = "应用「${rule.appName.ifBlank { rule.packageName }}」"
                )
                addPackageDnsRule(DnsRule(packageName = packageNames), semantic)
            }
        settings.appGroups
            .filter { ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode) && it.enabled }
            .forEach { group ->
                val packageNames = resolvePackagesSharingUid(
                    filterVpnCapturedPackages(settings, group.apps.map { it.packageName })
                )
                if (packageNames.isEmpty()) return@forEach
                val semantic = ConfigRepository.resolveAppOutboundSemanticStrict(
                    mode = ConfigRepository.resolveAppGroupOutboundMode(group.outboundMode),
                    value = group.outboundValue,
                    context = ConfigRepositoryOutboundSemanticContext(
                        selectorTag = outboundsContext.selectorTag,
                        outbounds = outboundsContext.outbounds,
                        profiles = profiles,
                        nodeTagResolver = outboundsContext.ruleNodeTagResolver
                    ),
                    label = "应用分组「${group.name}」"
                )
                addPackageDnsRule(DnsRule(packageName = packageNames), semantic)
            }
    }

    appDnsRules.addAll(
        ConfigRepository.buildOrderedDnsRules(
            entries = orderedPackageRules,
            fakeDnsEnabled = settings.fakeDnsEnabled,
            directServerTag = directServerTag,
            proxyServerTag = proxyServerTag
        )
    )
    dnsRules.addAll(
        ConfigRepository.mergeUserDnsRules(
            domainRules = customDomainDnsRules,
            appRules = appDnsRules,
            ruleSetRules = ruleSetDnsRules
        )
    )
    ConfigRepository.ensureDynamicRemoteDnsServers(
        dnsServers = dnsServers,
        semantics = domainSemantics + ruleSetSemantics + packageSemantics,
        remoteDnsAddr = remoteDnsAddr,
        remoteStrategy = remoteStrategy,
        remoteResolver = remoteResolver
    )

    dnsRules.addAll(ConfigRepository.buildOutboundDomainResolverDnsRules(outboundsContext.outbounds))

    val finalServer = resolveRunDnsFinalServer(
        routingMode = settings.routingMode,
        defaultRule = settings.defaultRule,
        fakeDnsEnabled = settings.fakeDnsEnabled,
        proxyServerTag = proxyServerTag
    )

    if (settings.fakeDnsEnabled) {
        val fakeIpExcludeDomains = buildList {
            parseDomainList(settings.fakeIpExcludeDomains).forEach { add(it) }
            val defaultExcludes = settings.fakeDnsExcludedDomains
                .takeIf { it.isNotBlank() }
                ?.split("\n", "\r")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: AppSettings.DEFAULT_FAKE_DNS_EXCLUDED_DOMAINS
                    .split("\n", "\r")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            defaultExcludes.filter { it !in this }.forEach { add(it) }
        }.distinct()

        if (fakeIpExcludeDomains.isNotEmpty()) {
            dnsRules.addAll(
                ConfigRepository.buildFakeIpExcludeDnsRules(
                    values = fakeIpExcludeDomains,
                    serverTag = finalServer
                )
            )
        }
    }

    val fakeIpConfig = if (settings.fakeDnsEnabled) {
        ConfigRepository.buildFakeIpConfig(settings.fakeIpRange)
    } else {
        null
    }

    val directOverrideDnsServerTags =
        ConfigRepository.resolveDnsOverrideDirectDnsServerTags(outboundsContext.outbounds, originalDns) +
            ConfigRepository.resolveDnsOverrideDirectDnsServerTags(outboundsContext.outbounds, dnsOverride)

    fun sanitizeDnsServer(server: DnsServer): DnsServer {
        return ConfigRepository.sanitizeInjectedDnsServerForRuntime(
            server,
            settings.routingMode,
            proxyDetourTag,
            directOverrideDnsServerTags
        )
    }

    // 追加订阅原始配置中的 DNS servers 和 rules
    if (originalDns != null) {
        originalDns.servers?.forEach { server ->
            if (server.tag != null && dnsServers.none { it.tag == server.tag }) {
                runCatching { sanitizeDnsServer(server) }
                    .getOrNull()
                    ?.takeIf(ConfigRepository::isDnsServerValidForRuntime)
                    ?.let(dnsServers::add)
            }
        }
        originalDns.rules?.forEach { rule ->
            dnsRules.add(
                rule.copy(inbound = ConfigRepository.normalizeRuleSetInboundTags(rule.inbound, settings))
            )
        }
    }

    dnsRules.addAll(
        ConfigRepository.buildDefaultDnsBlockRules(
            routingMode = settings.routingMode,
            defaultRule = settings.defaultRule
        )
    )
    dnsRules.addAll(ConfigRepository.buildTunFakeIpDnsRulesStatic(settings.fakeDnsEnabled, settings))

    val baseDnsConfig = DnsConfig(
        servers = dnsServers,
        rules = dnsRules,
        finalServer = finalServer,
        strategy = resolveDnsStrategy(settings.dnsStrategy, settings.ipVersionMode),
        disableCache = !settings.dnsCacheEnabled,
        fakeip = fakeIpConfig
    )

    val runtimeDns = if (dnsOverride != null) {
        ConfigRepository.applyDnsOverride(
            baseDnsConfig,
            dnsOverride.copy(
                rules = dnsOverride.rules?.map { rule ->
                    rule.copy(inbound = ConfigRepository.normalizeRuleSetInboundTags(rule.inbound, settings))
                }
            ),
            ::sanitizeDnsServer
        )
    } else {
        baseDnsConfig
    }
    val runtimeServerTags = runtimeDns.servers.orEmpty().mapNotNullTo(mutableSetOf()) { it.tag }
    return runtimeDns.copy(
        // sing-box 1.14 removed these legacy top-level DNS options. Keep them in
        // the import model, but never emit them into the runtime configuration.
        rules = ConfigRepository.sanitizeDnsRulesForRuntime(
            runtimeDns.rules.orEmpty(),
            runtimeServerTags,
            ipOnlyRuleSetTags
        ),
        independentCache = null,
        fakeip = null
    )
}

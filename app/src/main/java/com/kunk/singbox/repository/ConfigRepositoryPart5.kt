package com.kunk.singbox.repository

import android.content.Context
import android.util.Log
import com.kunk.singbox.model.*
import com.kunk.singbox.repository.config.InboundBuilder
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Suppress("TooManyFunctions")
abstract class ConfigRepositoryPart5(context: Context) : ConfigRepositoryPart4(context) {
    protected override fun buildCustomDomainRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        profiles: List<ProfileUi>,
        nodeTagResolver: (String?) -> String?
    ): List<RouteRule> {
        fun splitValues(raw: String): List<String> {
            return raw
                .split("\n", "\r", ",", ";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        val rules = settings.customRules
            .filter { it.enabled }
            .filter {
                it.type == RuleType.DOMAIN ||
                    it.type == RuleType.DOMAIN_SUFFIX ||
                    it.type == RuleType.DOMAIN_KEYWORD
            }
            .mapNotNull { rule ->
                val values = splitValues(rule.value)
                if (values.isEmpty()) return@mapNotNull null

                val semantic = ConfigRepository.resolveOutboundSemantic(
                    mode = ConfigRepository.resolveCustomRuleOutboundMode(rule.outboundMode, rule.outbound),
                    value = rule.outboundValue,
                    context = ConfigRepositoryOutboundSemanticContext(
                        selectorTag = defaultProxyTag,
                        outbounds = outbounds,
                        profiles = profiles,
                        nodeTagResolver = nodeTagResolver
                    )
                )
                val baseRule = ConfigRepository.toRouteRule(semantic, defaultProxyTag)
                Log.d(
                    ConfigRepository.TAG,
                    "CustomDomainRule: type=${rule.type}, value=${rule.value}, mode=${rule.outboundMode}, " +
                        "outboundValue=${rule.outboundValue}, resolved=${baseRule.outbound}"
                )
                when (rule.type) {
                    RuleType.DOMAIN -> baseRule.copy(domain = values)
                    RuleType.DOMAIN_SUFFIX -> baseRule.copy(domainSuffix = values)
                    RuleType.DOMAIN_KEYWORD -> baseRule.copy(domainKeyword = values)
                    else -> null
                }
            }
        return rules
    }

    @Suppress("LongParameterList")
    protected override fun buildCustomRuleSetRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        profiles: List<ProfileUi>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>
    ): List<RouteRule> {
        val rules = mutableListOf<RouteRule>()

        val validTags = validRuleSets.mapNotNull { it.tag }.toSet()
        val sortedRuleSets = ConfigRepository.sortRuleSetsForDnsAndRoutePriority(
            settings.ruleSets.filter { it.enabled && it.tag in validTags }
        )

        sortedRuleSets.forEach { ruleSet ->
            val semantic = ConfigRepository.resolveOutboundSemantic(
                mode = ConfigRepository.resolveRuleSetOutboundMode(ruleSet.outboundMode),
                value = ruleSet.outboundValue,
                context = ConfigRepositoryOutboundSemanticContext(
                    selectorTag = defaultProxyTag,
                    outbounds = outbounds,
                    profiles = profiles,
                    nodeTagResolver = nodeTagResolver
                )
            )
            val baseRule = ConfigRepository.toRouteRule(semantic, defaultProxyTag)
            val inboundTags: List<String>? = when {
                ruleSet.inbounds.isNullOrEmpty() -> null
                else -> ruleSet.inbounds.map {
                    when (it) {
                        "tun" -> "tun-in"
                        "mixed" -> "mixed-in"
                        else -> it
                    }
                }
            }

            rules.add(baseRule.copy(
                ruleSet = listOf(ruleSet.tag),
                inbound = inboundTags
            ))
        }

        return rules
    }

    @Suppress("LongMethod")
    protected override fun buildAppRoutingRules(
        settings: AppSettings,
        defaultProxyTag: String,
        outbounds: List<Outbound>,
        profiles: List<ProfileUi>,
        nodeTagResolver: (String?) -> String?
    ): List<RouteRule> {
        val rules = mutableListOf<RouteRule>()

        fun resolveUidByPackageName(pkg: String): Int? {
            return try {
                context.packageManager.getApplicationInfo(pkg, 0).uid
            } catch (_: Exception) {
                null
            }
        }

        settings.appRules.filter { it.enabled }.forEach { rule ->
            val semantic = ConfigRepository.resolveOutboundSemantic(
                mode = ConfigRepository.resolveAppRuleOutboundMode(rule.outboundMode),
                value = rule.outboundValue,
                context = ConfigRepositoryOutboundSemanticContext(
                    selectorTag = defaultProxyTag,
                    outbounds = outbounds,
                    profiles = profiles,
                    nodeTagResolver = nodeTagResolver
                )
            )
            val baseRule = ConfigRepository.toRouteRule(semantic, defaultProxyTag)

            val uid = resolveUidByPackageName(rule.packageName)
            if (uid != null && uid > 0) {
                rules.add(
                    baseRule.copy(
                        userId = listOf(uid)
                    )
                )
            }

            rules.add(
                baseRule.copy(
                    packageName = listOf(rule.packageName)
                )
            )
        }
        settings.appGroups.filter { it.enabled }.forEach { group ->
            val semantic = ConfigRepository.resolveOutboundSemantic(
                mode = ConfigRepository.resolveAppGroupOutboundMode(group.outboundMode),
                value = group.outboundValue,
                context = ConfigRepositoryOutboundSemanticContext(
                    selectorTag = defaultProxyTag,
                    outbounds = outbounds,
                    profiles = profiles,
                    nodeTagResolver = nodeTagResolver
                )
            )
            val baseRule = ConfigRepository.toRouteRule(semantic, defaultProxyTag)
            val packageNames = group.apps.map { it.packageName }
            if (packageNames.isNotEmpty()) {
                val uids = packageNames.mapNotNull { resolveUidByPackageName(it) }.filter { it > 0 }.distinct()
                if (uids.isNotEmpty()) {
                    rules.add(
                        baseRule.copy(
                            userId = uids
                        )
                    )
                }

                rules.add(
                    baseRule.copy(
                        packageName = packageNames
                    )
                )
            }
        }

        return rules
    }

    protected override fun buildRunLogConfig(): LogConfig {
        return LogConfig(
            level = "info",
            timestamp = true
        )
    }

    protected override fun buildRunExperimentalConfig(settings: AppSettings): ExperimentalConfig {
        val singboxDataDir = File(context.filesDir, "singbox_data").also { it.mkdirs() }

        val clashApiPort = findAvailablePort(9090)
        val clashApi = ClashApiConfig(
            externalController = "127.0.0.1:$clashApiPort",
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

    protected override fun buildRunInbounds(settings: AppSettings): List<Inbound> =
        InboundBuilder.build(
            settings.copy(tunMtu = getEffectiveTunMtu(settings)),
            getEffectiveTunStack(settings.tunStack)
        )

    protected override fun resolveRunDnsFinalServer(
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

    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
        "CognitiveComplexMethod",
        "NestedBlockDepth"
    )
    protected override fun buildRunDns(
        settings: AppSettings,
        validRuleSets: List<RuleSetConfig>,
        outboundsContext: ConfigRepositoryRunOutboundsContext,
        dnsOverride: DnsConfig?,
        originalDns: DnsConfig?): DnsConfig {
        val dnsServers = mutableListOf<DnsServer>()
        val dnsRules = mutableListOf<DnsRule>()

        val profiles = _profiles.value
        val proxyDetourTag = resolveCurrentProxyDnsDetourTag(outboundsContext.selectorTag, outboundsContext.outbounds)
        val proxyServerTag = ConfigRepository.buildDynamicDnsServerTag(proxyDetourTag)
        val proxyFinalServerTag = proxyServerTag
        val directServerTag = "local"

        fun dnsRouteTo(server: String, rule: DnsRule): DnsRule =
            rule.copy(action = "route", server = server)

        fun dnsRouteToDirect(server: String, rule: DnsRule): DnsRule =
            ConfigRepository.buildDnsRouteToDirect(server, directServerTag, rule)

        fun dnsReject(rule: DnsRule): DnsRule = rule.copy(action = "predefined", rcode = "NOERROR")

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

        fun dnsRouteToProxy(rule: DnsRule): List<DnsRule> {
            return ConfigRepository.buildDnsRouteToNonDirect(settings.fakeDnsEnabled, proxyServerTag, rule)
        }

        fun dnsRouteToNonDirect(server: String, rule: DnsRule): List<DnsRule> {
            return ConfigRepository.buildDnsRouteToNonDirect(settings.fakeDnsEnabled, server, rule)
        }

        fun outboundModeOf(
            ruleOutboundMode: RuleSetOutboundMode?,
            fallbackOutbound: OutboundTag?
        ): RuleSetOutboundMode {
            return ruleOutboundMode
                ?: when (fallbackOutbound) {
                    OutboundTag.DIRECT -> RuleSetOutboundMode.DIRECT
                    OutboundTag.BLOCK -> RuleSetOutboundMode.BLOCK
                    OutboundTag.PROXY -> RuleSetOutboundMode.PROXY
                    null -> RuleSetOutboundMode.PROXY
                }
        }
        val echQueryServerTag = "dns-bootstrap"
        dnsRules.addAll(
            ConfigRepository.buildEchAwareHttpsSvcbDnsRules(
                blockQuic = settings.blockQuic,
                outbounds = outboundsContext.outbounds,
                echQueryServerTag = echQueryServerTag
            )
        )
        val bootstrapStrategy = resolveDnsStrategy(settings.serverAddressStrategy, settings.ipVersionMode)
        val bootstrapV4Tag = "dns-bootstrap-v4"
        val bootstrapV6Tag = "dns-bootstrap-v6"

        // sing-box 1.13+: 不设 detour 即为直连，显式设 detour="direct" 会报
        // "detour to an empty direct outbound makes no sense"
        dnsServers.add(
            DnsServer(
                tag = bootstrapV4Tag,
                type = "https",
                server = "223.5.5.5",
                domainStrategy = bootstrapStrategy
            )
        )
        dnsServers.add(
            DnsServer(
                tag = bootstrapV6Tag,
                type = "https",
                server = "2606:4700:4700::1111",
                domainStrategy = "prefer_ipv6"
            )
        )
        dnsServers.add(
            DnsServer(
                tag = "dns-bootstrap",
                type = "https",
                server = "1.12.12.12",
                domainStrategy = bootstrapStrategy
            )
        )

        val localDnsAddr = ConfigRepository.normalizeLocalDns(settings.localDns)
        val localResolver = ConfigRepository.buildDnsResolverForAddress(localDnsAddr)
        val localServer = ConfigRepository.buildDnsServer(
            address = localDnsAddr,
            tag = "local",
            domainStrategy = resolveDnsStrategy(settings.directDnsStrategy, settings.ipVersionMode),
            domainResolver = localResolver
        )
        dnsServers.add(localServer)
        val remoteDnsAddr = ConfigRepository.normalizeRemoteDns(settings.remoteDns)
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
                bootstrapV4Tag = bootstrapV4Tag,
                bootstrapV6Tag = bootstrapV6Tag,
                bootstrapTag = "dns-bootstrap"
            )
        )

        if (settings.fakeDnsEnabled) {
            dnsServers.add(ConfigRepository.buildFakeIpDnsServer(settings.fakeIpRange))
        }
        val customDomainRulesForDns = settings.customRules
            .filter { it.enabled }
            .filter {
                it.type == RuleType.DOMAIN ||
                    it.type == RuleType.DOMAIN_SUFFIX ||
                    it.type == RuleType.DOMAIN_KEYWORD
            }
        val domainSemantics = mutableListOf<ConfigRepository.OutboundSemantic>()

        if (customDomainRulesForDns.isNotEmpty()) {
            val dnsRulesByServer = linkedMapOf<String, MutableList<DnsRule>>()

            fun addDnsRuleForSemantic(rule: DnsRule, semantic: ConfigRepository.OutboundSemantic) {
                domainSemantics.add(semantic)
                when (semantic) {
                    ConfigRepository.OutboundSemantic.Block -> dnsRules.add(dnsReject(rule))
                    else -> {
                        val serverTag = ConfigRepository.dnsServerTagForSemantic(
                            semantic,
                            settings.fakeDnsEnabled,
                            directServerTag,
                            proxyServerTag
                        ) ?: return
                        dnsRulesByServer.getOrPut(serverTag) { mutableListOf() }.add(rule)
                    }
                }
            }

            customDomainRulesForDns.forEach { rule ->
                val values = rule.value
                    .split("\n", "\r", ",", ";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                if (values.isEmpty()) return@forEach

                val semantic = ConfigRepository.resolveOutboundSemantic(
                    mode = ConfigRepository.resolveCustomRuleOutboundMode(rule.outboundMode, rule.outbound),
                    value = rule.outboundValue,
                    context = ConfigRepositoryOutboundSemanticContext(
                        selectorTag = outboundsContext.selectorTag,
                        outbounds = outboundsContext.outbounds,
                        profiles = profiles,
                        nodeTagResolver = outboundsContext.nodeTagResolver
                    )
                )

                when (rule.type) {
                    RuleType.DOMAIN -> {
                        addDnsRuleForSemantic(DnsRule(domain = values.distinct()), semantic)
                    }
                    RuleType.DOMAIN_SUFFIX -> {
                        addDnsRuleForSemantic(DnsRule(domainSuffix = values.distinct()), semantic)
                    }
                    RuleType.DOMAIN_KEYWORD -> {
                        addDnsRuleForSemantic(DnsRule(domainKeyword = values.distinct()), semantic)
                    }
                    else -> {}
                }
            }

            dnsRulesByServer.forEach { (serverTag, rulesForServer) ->
                rulesForServer.forEach { rule ->
                    if (serverTag == "fakeip-dns") {
                        dnsRules.addAll(dnsRouteToProxy(rule))
                    } else if (serverTag == directServerTag) {
                        dnsRules.add(dnsRouteToDirect(serverTag, rule))
                    } else {
                        dnsRules.addAll(dnsRouteToNonDirect(serverTag, rule))
                    }
                }
            }
        }
        val validRuleSetTags = validRuleSets.mapNotNull { it.tag }.toSet()
        val dnsRuleSetRulesByServer = linkedMapOf<String, MutableList<DnsRule>>()
        val ruleSetSemantics = mutableListOf<ConfigRepository.OutboundSemantic>()

        fun addRuleSetDnsRule(rule: DnsRule, semantic: ConfigRepository.OutboundSemantic) {
            ruleSetSemantics.add(semantic)
            when (semantic) {
                ConfigRepository.OutboundSemantic.Block -> dnsRules.add(dnsReject(rule))
                else -> {
                    val serverTag = ConfigRepository.dnsServerTagForSemantic(
                        semantic,
                        settings.fakeDnsEnabled,
                        directServerTag,
                        proxyServerTag
                    ) ?: return
                    dnsRuleSetRulesByServer.getOrPut(serverTag) {
                        mutableListOf()
                    }.add(rule)
                }
            }
        }

        ConfigRepository.sortRuleSetsForDnsAndRoutePriority(settings.ruleSets.filter { it.enabled })
            .forEach { ruleSet ->
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

                val semantic = ConfigRepository.resolveOutboundSemantic(
                    mode = ConfigRepository.resolveRuleSetOutboundMode(ruleSet.outboundMode),
                    value = ruleSet.outboundValue,
                    context = ConfigRepositoryOutboundSemanticContext(
                        selectorTag = outboundsContext.selectorTag,
                        outbounds = outboundsContext.outbounds,
                        profiles = profiles,
                        nodeTagResolver = outboundsContext.nodeTagResolver
                    )
                )
                addRuleSetDnsRule(DnsRule(ruleSet = listOf(tag)), semantic)
            }

        dnsRuleSetRulesByServer.forEach { (serverTag, rulesForServer) ->
            rulesForServer.forEach { rule ->
                if (serverTag == "fakeip-dns") {
                    dnsRules.addAll(dnsRouteToProxy(rule))
                } else if (serverTag == directServerTag) {
                    dnsRules.add(dnsRouteToDirect(serverTag, rule))
                } else {
                    dnsRules.addAll(dnsRouteToNonDirect(serverTag, rule))
                }
            }
        }
        val packageRulesByServer = linkedMapOf<String, MutableList<DnsRule>>()
        val packageSemantics = mutableListOf<ConfigRepository.OutboundSemantic>()
        val packageBlockRules = mutableListOf<DnsRule>()

        fun addPackageDnsRule(rule: DnsRule, semantic: ConfigRepository.OutboundSemantic) {
            packageSemantics.add(semantic)
            when (semantic) {
                ConfigRepository.OutboundSemantic.Block -> packageBlockRules.add(rule)
                else -> {
                    val serverTag = ConfigRepository.dnsServerTagForSemantic(
                        semantic,
                        settings.fakeDnsEnabled,
                        directServerTag,
                        proxyServerTag
                    ) ?: return
                    packageRulesByServer.getOrPut(serverTag) { mutableListOf() }.add(rule)
                }
            }
        }

        settings.appRules.filter { it.enabled }.forEach { rule ->
            val semantic = ConfigRepository.resolveOutboundSemantic(
                mode = ConfigRepository.resolveAppRuleOutboundMode(rule.outboundMode),
                value = rule.outboundValue,
                context = ConfigRepositoryOutboundSemanticContext(
                    selectorTag = outboundsContext.selectorTag,
                    outbounds = outboundsContext.outbounds,
                    profiles = profiles,
                    nodeTagResolver = outboundsContext.nodeTagResolver
                )
            )
            addPackageDnsRule(DnsRule(packageName = listOf(rule.packageName)), semantic)
        }
        settings.appGroups.filter { it.enabled }.forEach { group ->
            val semantic = ConfigRepository.resolveOutboundSemantic(
                mode = ConfigRepository.resolveAppGroupOutboundMode(group.outboundMode),
                value = group.outboundValue,
                context = ConfigRepositoryOutboundSemanticContext(
                    selectorTag = outboundsContext.selectorTag,
                    outbounds = outboundsContext.outbounds,
                    profiles = profiles,
                    nodeTagResolver = outboundsContext.nodeTagResolver
                )
            )
            group.apps.forEach { addPackageDnsRule(DnsRule(packageName = listOf(it.packageName)), semantic) }
        }

        // We keep both package_name and user_id matching for robustness.
        // (sing-box docs mark user_id as Linux-only, but some Android clients still accept it via platform integration)
        fun resolveUids(pkgs: List<String>): List<Int> {
            return pkgs.mapNotNull {
                try {
                    context.packageManager.getApplicationInfo(it, 0).uid
                } catch (_: Exception) {
                    null
                }
            }.distinct()
        }

        val directPkgs = packageRulesByServer[directServerTag]
            .orEmpty()
            .flatMap { it.packageName.orEmpty() }
            .distinct()
            .filter { it.isNotBlank() }
        val proxyPkgs = packageRulesByServer[proxyServerTag]
            .orEmpty()
            .flatMap { it.packageName.orEmpty() }
            .distinct()
            .filter { it.isNotBlank() }
        val blockPkgs = packageBlockRules
            .flatMap { it.packageName.orEmpty() }
            .distinct()
            .filter { it.isNotBlank() }

        packageRulesByServer.forEach { (serverTag, rulesForServer) ->
            if (serverTag == directServerTag || serverTag == proxyServerTag) {
                return@forEach
            }
            rulesForServer.forEach { rule ->
                dnsRules.addAll(dnsRouteToNonDirect(serverTag, rule))
            }
        }

        if (blockPkgs.isNotEmpty()) {
            dnsRules.add(
                dnsReject(DnsRule(packageName = blockPkgs, userId = resolveUids(blockPkgs)))
            )
        }
        if (proxyPkgs.isNotEmpty()) {
            dnsRules.addAll(
                dnsRouteToProxy(DnsRule(packageName = proxyPkgs, userId = resolveUids(proxyPkgs)))
            )
        }
        if (directPkgs.isNotEmpty()) {
            dnsRules.add(
                dnsRouteToDirect(
                    directServerTag,
                    DnsRule(packageName = directPkgs, userId = resolveUids(directPkgs))
                )
            )
        }
        ConfigRepository.ensureDynamicRemoteDnsServers(
            dnsServers = dnsServers,
            semantics = domainSemantics + ruleSetSemantics + packageSemantics,
            remoteDnsAddr = remoteDnsAddr,
            remoteStrategy = remoteStrategy,
            remoteResolver = remoteResolver
        )

        dnsRules.addAll(ConfigRepository.buildOutboundDomainResolverDnsRules(outboundsContext.outbounds))

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
                dnsRules.add(dnsRouteTo(proxyFinalServerTag, DnsRule(domain = fakeIpExcludeDomains)))
            }
        }
        dnsRules.add(ConfigRepository.buildNonIpDnsFallbackRule(proxyServerTag))
        dnsRules.addAll(ConfigRepository.buildTunFakeIpDnsRulesStatic(settings.fakeDnsEnabled))

        val fakeIpConfig = if (settings.fakeDnsEnabled) {
            ConfigRepository.buildFakeIpConfig(settings.fakeIpRange)
        } else {
            null
        }

        val finalServer = resolveRunDnsFinalServer(
            routingMode = settings.routingMode,
            defaultRule = settings.defaultRule,
            fakeDnsEnabled = settings.fakeDnsEnabled,
            proxyServerTag = proxyServerTag
        )
        val directOverrideDnsServerTags = ConfigRepository.resolveDnsOverrideDirectDnsServerTags(outboundsContext.outbounds, dnsOverride)

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
                    dnsServers.add(sanitizeDnsServer(server))
                }
            }
            originalDns.rules?.forEach { rule ->
                dnsRules.add(rule)
            }
        }

        val baseDnsConfig = DnsConfig(
            servers = dnsServers,
            rules = dnsRules,
            finalServer = finalServer,
            strategy = resolveDnsStrategy(settings.dnsStrategy, settings.ipVersionMode),
            disableCache = !settings.dnsCacheEnabled,
            independentCache = false,
            fakeip = fakeIpConfig
        )

        return if (dnsOverride != null) {
            ConfigRepository.applyDnsOverride(baseDnsConfig, dnsOverride, ::sanitizeDnsServer)
        } else {
            baseDnsConfig
        }
    }

    protected override fun resolveCurrentProxyDnsDetourTag(selectorTag: String, outbounds: List<Outbound>): String {
        fun resolve(tag: String): String {
            val outbound = outbounds.firstOrNull { it.tag == tag } ?: return tag
            return when (outbound.type) {
                "selector" -> resolve(outbound.default ?: outbound.outbounds?.firstOrNull() ?: tag)
                "urltest", "url-test" -> resolve(outbound.outbounds?.firstOrNull() ?: tag)
                else -> tag
            }
        }
        return resolve(selectorTag)
    }
}

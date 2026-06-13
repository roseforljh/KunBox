package com.kunk.singbox.repository

import com.kunk.singbox.R
import android.content.Context
import android.util.Log
import com.kunk.singbox.model.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Suppress("TooManyFunctions")
abstract class ConfigRepositoryPart6(context: Context) : ConfigRepositoryPart5(context) {
    protected override fun buildRunOutbounds(
        baseConfig: SingBoxConfig,
        activeNode: NodeUi?,
        settings: AppSettings,
        allNodes: List<NodeUi>,
        dnsPreResolve: Boolean,
        profileId: String?,
        dnsOverrideConfig: DnsConfig?): ConfigRepositoryRunOutboundsContext {
        val rawOutbounds = baseConfig.outbounds
        if (rawOutbounds.isNullOrEmpty()) {
            Log.w(ConfigRepository.TAG, "No outbounds found in base config, adding defaults")
        }

        val fixedOutbounds = rawOutbounds?.mapNotNull { outbound ->
            var processed = buildOutboundForRuntime(outbound) ?: return@mapNotNull null
            if (dnsPreResolve && profileId != null) {
                val server = processed.server?.trim().orEmpty()
                if (ConfigRepository.shouldApplyDnsPreResolveToDomain(server, dnsOverrideConfig, processed.tag)) {
                    processed = applyDnsResolveToOutbound(profileId, processed)
                } else {
                    Log.d(ConfigRepository.TAG, "Skip DNS pre-resolve for DNS override matched node domain: $server")
                }
            }
            if (singBoxCore.validateOutbound(stripInternalMetadata(processed))) {
                processed
            } else {
                Log.w(ConfigRepository.TAG, "Skipping invalid outbound: ${outbound.tag} (type=${outbound.type})")
                null
            }
        }?.toMutableList() ?: mutableListOf()

        if (fixedOutbounds.none { it.tag == "direct" }) {
            fixedOutbounds.add(Outbound(type = "direct", tag = "direct"))
        }
        val activeProfileId = _activeProfileId.value
        val requiredNodeIds = mutableSetOf<String>()
        val requiredProfileIds = mutableSetOf<String>()

        fun resolveNodeRefToId(value: String?): String? {
            if (value.isNullOrBlank()) return null
            val parts = value.split("::", limit = 2)
            if (parts.size == 2) {
                val refProfileId = parts[0]
                val nodeName = parts[1]
                return allNodes.firstOrNull { it.sourceProfileId == refProfileId && it.name == nodeName }?.id
            }
            if (allNodes.any { it.id == value }) return value
            val node = if (activeProfileId != null) {
                allNodes.firstOrNull { it.sourceProfileId == activeProfileId && it.name == value }
                    ?: allNodes.firstOrNull { it.name == value }
            } else {
                allNodes.firstOrNull { it.name == value }
            }
            return node?.id
        }
        settings.appRules.filter { it.enabled }.forEach { rule ->
            when (rule.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(rule.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> rule.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
        settings.appGroups.filter { it.enabled }.forEach { group ->
            when (group.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(group.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> group.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
        settings.ruleSets.filter { it.enabled }.forEach { ruleSet ->
            when (ruleSet.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(ruleSet.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> ruleSet.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
        settings.customRules.filter { it.enabled }.forEach { rule ->
            when (rule.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(rule.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> rule.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
        fixedOutbounds.mapNotNull { it.detour }.forEach { detourValue ->
            resolveNodeRefToId(detourValue)?.let { requiredNodeIds.add(it) }
        }
        activeNode?.let { requiredNodeIds.add(it.id) }
        requiredProfileIds.forEach { requiredProfileId ->
            allNodes.filter { it.sourceProfileId == requiredProfileId }.forEach { node ->
                requiredNodeIds.add(node.id)
            }
        }
        val nodeTagMap = mutableMapOf<String, String>()
        val existingTags = fixedOutbounds.map { it.tag }.toMutableSet()
        Log.d(ConfigRepository.TAG, "buildRunOutbounds: activeProfileId=$activeProfileId, existingTags count=${existingTags.size}")
        Log.d(ConfigRepository.TAG, "  existingTags (first 10): ${existingTags.take(10)}")
        if (activeProfileId != null) {
            val profileNodes = allNodes.filter { it.sourceProfileId == activeProfileId }
            Log.d(ConfigRepository.TAG, "  profileNodes count=${profileNodes.size}")
            profileNodes.forEach { node ->
                if (existingTags.contains(node.name)) {
                    nodeTagMap[node.id] = node.name
                } else {
                    val fuzzyMatch = existingTags.find { it.equals(node.name, ignoreCase = true) }
                    if (fuzzyMatch != null) {
                        nodeTagMap[node.id] = fuzzyMatch
                        Log.w(ConfigRepository.TAG, "  Fuzzy matched node '${node.name}' to tag '$fuzzyMatch'")
                    } else {
                        Log.w(ConfigRepository.TAG, "  WARNING: Node '${node.name}' (id=${node.id.take(8)}) not found in existingTags!")
                    }
                }
            }
        }
        requiredNodeIds.forEach { nodeId ->
            if (nodeTagMap.containsKey(nodeId)) return@forEach

            val node = allNodes.find { it.id == nodeId }
            if (node == null) {
                Log.w(ConfigRepository.TAG, "Cross-profile node not found in allNodes: nodeId=$nodeId")
                return@forEach
            }
            val sourceProfileId = node.sourceProfileId
            if (sourceProfileId == activeProfileId) {
                Log.w(ConfigRepository.TAG, "Cross-profile node belongs to activeProfile but not in outbounds: ${node.name}")
                return@forEach
            }
            val sourceConfig = loadConfig(sourceProfileId)
            if (sourceConfig == null) {
                Log.e(ConfigRepository.TAG, "Failed to load source config for cross-profile node: profileId=$sourceProfileId, nodeName=${node.name}")
                return@forEach
            }
            val sourceOutbound = sourceConfig.outbounds?.find { it.tag == node.name }
                ?: sourceConfig.outbounds?.find { it.tag.equals(node.name, ignoreCase = true) }
                ?: sourceConfig.outbounds?.find {
                    it.tag.replace(ConfigRepository.REGEX_WHITESPACE_DASH, "").equals(
                        node.name.replace(ConfigRepository.REGEX_WHITESPACE_DASH, ""),
                        ignoreCase = true
                    )
                }

            if (sourceOutbound == null) {
                Log.e(ConfigRepository.TAG, "Cross-profile outbound not found: nodeName=${node.name}, profileId=$sourceProfileId, available tags: ${sourceConfig.outbounds?.map { it.tag }?.take(10)}")
                return@forEach
            }
            var fixedSourceOutbound = buildOutboundForRuntime(sourceOutbound)
            if (fixedSourceOutbound == null) {
                Log.w(ConfigRepository.TAG, "Skipping removed outbound type: ${sourceOutbound.type} (${sourceOutbound.tag})")
                return@forEach
            }
            var finalTag = fixedSourceOutbound.tag
            if (existingTags.contains(finalTag)) {
                val suffix = sourceProfileId.take(4)
                finalTag = "${finalTag}_$suffix"
                if (existingTags.contains(finalTag)) {
                    finalTag = "${finalTag}_${java.util.UUID.randomUUID().toString().take(4)}"
                }
                fixedSourceOutbound = fixedSourceOutbound.copy(tag = finalTag)
            }
            if (!singBoxCore.validateOutbound(stripInternalMetadata(fixedSourceOutbound))) {
                Log.w(ConfigRepository.TAG, "Skipping invalid cross-profile outbound: ${node.name} (type=${sourceOutbound.type})")
                return@forEach
            }
            fixedOutbounds.add(fixedSourceOutbound)
            existingTags.add(finalTag)
            nodeTagMap[nodeId] = finalTag
        }
        requiredProfileIds.forEach { requiredProfileId ->
            val profileNodes = allNodes.filter { it.sourceProfileId == requiredProfileId }
            val nodeIds = profileNodes.map { it.id }
            val nodeTags = nodeIds.mapNotNull { nodeTagMap[it] }.distinct()
            val profileName = _profiles.value.find { it.id == requiredProfileId }?.name ?: "Profile_$requiredProfileId"
            val tag = "P:$profileName"
            val selectorDefault = ConfigRepository.resolveProfileSelectorDefault(
                nodeIds = nodeIds,
                nodeTagMap = nodeTagMap,
                rememberedNodeId = getProfileLastSelectedNode(requiredProfileId),
                savedNodeLatencies = savedNodeLatencies
            )

            if (nodeTags.isNotEmpty()) {
                val routeGroupOutbounds = ConfigRepository.buildProfileRouteGroupOutbounds(
                    groupTag = tag,
                    nodeTags = nodeTags
                )
                if (routeGroupOutbounds.isNotEmpty()) {
                    val generatedTags = routeGroupOutbounds.map { it.tag }.toSet()
                    fixedOutbounds.removeAll { it.tag in generatedTags }
                    fixedOutbounds.addAll(0, routeGroupOutbounds)
                }
            }
        }
        val proxyTags = fixedOutbounds.filter {
            it.type in listOf(
                "vless", "vmess", "trojan", "shadowsocks",
                "hysteria2", "hysteria", "anytls", "tuic",
                "wireguard", "ssh", "shadowtls", "http", "socks", "naive"
            )
        }.map { it.tag }.toMutableList()
        val selectorTag = "PROXY"
        if (proxyTags.isEmpty()) {
            proxyTags.add("direct")
        }

        val selectorDefault = activeNode
            ?.let { nodeTagMap[it.id] ?: it.name }
            ?.takeIf { it in proxyTags }
            ?: proxyTags.firstOrNull()
        if (activeNode != null) {
            val mappedTag = nodeTagMap[activeNode.id]
            Log.d(ConfigRepository.TAG, "Selector default: activeNode=${activeNode.name}, id=${activeNode.id}, mappedTag=$mappedTag, selectorDefault=$selectorDefault, inProxyTags=${selectorDefault in proxyTags}")
            if (mappedTag == null && activeNode.name !in proxyTags) {
                Log.w(ConfigRepository.TAG, "WARNING: Active node not in nodeTagMap and name not in proxyTags! Node may not be selected correctly.")
                Log.w(ConfigRepository.TAG, "  Available proxyTags (first 10): ${proxyTags.take(10)}")
                Log.w(ConfigRepository.TAG, "  nodeTagMap keys (first 10): ${nodeTagMap.keys.take(10)}")
            }
        }

        val selectorOutbound = Outbound(
            type = "selector",
            tag = selectorTag,
            outbounds = proxyTags,
            default = selectorDefault,
            interruptExistConnections = true
        )
        val existingProxyIndexes = fixedOutbounds.withIndex()
            .filter { it.value.tag == selectorTag }
            .map { it.index }
        if (existingProxyIndexes.isNotEmpty()) {
            existingProxyIndexes.asReversed().forEach { idx ->
                fixedOutbounds.removeAt(idx)
            }
        }
        fixedOutbounds.add(0, selectorOutbound)
        val nodeTagResolver: (String?) -> String? = { value ->
            if (value.isNullOrBlank()) {
                null
            } else {
                nodeTagMap[value]
                    ?: resolveNodeRefToId(value)?.let { nodeTagMap[it] }
                    ?: if (fixedOutbounds.any { it.tag == value }) value else null
            }
        }

        // Final safety check:
        // 1) Normalize detour node refs to runtime tag
        // 2) Filter out non-existent references in Selector/URLTest
        // 3) Validate detour target exists (or clear detour)
        val detourNormalizedOutbounds = fixedOutbounds.map { outbound ->
            val detourValue = outbound.detour
            if (detourValue.isNullOrBlank()) return@map outbound
            val mappedDetourTag = nodeTagResolver(detourValue)
            if (mappedDetourTag != null && mappedDetourTag != detourValue) {
                outbound.copy(detour = mappedDetourTag)
            } else {
                outbound
            }
        }

        val selectorSafeOutbounds = applySelectorSafeOutbounds(detourNormalizedOutbounds)

        val finalTags = selectorSafeOutbounds.map { it.tag }.toSet()
        val safeOutbounds = selectorSafeOutbounds.map { outbound ->
            val detourTag = outbound.detour
            if (detourTag.isNullOrBlank()) return@map outbound

            val isInvalidDetour = detourTag == outbound.tag || detourTag !in finalTags
            if (isInvalidDetour) {
                Log.w(ConfigRepository.TAG, "Cleared invalid detour for ${outbound.tag}: detour=$detourTag")
                outbound.copy(detour = null)
            } else {
                outbound
            }
        }

        return ConfigRepositoryRunOutboundsContext(
            outbounds = safeOutbounds,
            selectorTag = selectorTag,
            nodeTagResolver = nodeTagResolver,
            nodeTagMap = nodeTagMap
        )
    }

    protected override fun applySelectorSafeOutbounds(outbounds: List<Outbound>): List<Outbound> {
        return ConfigRepository.sanitizeSelectorSafeOutbounds(outbounds)
    }

    protected override fun buildQuicBlockRule(settings: AppSettings): List<RouteRule> {
        return if (settings.blockQuic) {
            listOf(
                RouteRule(protocolRaw = listOf("quic"), action = "reject")
            )
        } else {
            emptyList()
        }
    }

    protected override fun buildBypassLanRules(settings: AppSettings): List<RouteRule> {
        return ConfigRepository.buildBypassLanRulesStatic(settings)
    }

    protected override fun buildMulticastRejectRules(settings: AppSettings): List<RouteRule> {
        return ConfigRepository.buildMulticastRejectRulesStatic(settings)
    }

    protected override fun buildIcmpEchoRules(settings: AppSettings): List<RouteRule> {
        if (!settings.icmpEchoRoutingEnabled) return emptyList()

        return when (settings.routingMode) {
            RoutingMode.GLOBAL_DIRECT -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
            RoutingMode.GLOBAL_PROXY -> {
                Log.w(ConfigRepository.TAG, "ICMP echo proxy outbound is limited; fallback to direct routing")
                listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
            }
            RoutingMode.RULE -> when (settings.defaultRule) {
                DefaultRule.DIRECT -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
                DefaultRule.BLOCK -> listOf(RouteRule(networkRaw = listOf("icmp"), action = "reject"))
                DefaultRule.PROXY -> {
                    Log.w(ConfigRepository.TAG, "ICMP echo with PROXY default rule falls back to direct routing")
                    listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
                }
            }
        }
    }

    protected override fun buildDefaultRules(settings: AppSettings, selectorTag: String): List<RouteRule> {
        return when (settings.defaultRule) {
            DefaultRule.DIRECT -> listOf(RouteRule(outbound = "direct"))
            DefaultRule.BLOCK -> listOf(RouteRule(action = "reject"))
            DefaultRule.PROXY -> listOf(RouteRule(outbound = selectorTag))
        }
    }

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod", "LongParameterList")
    protected override fun selectRunRouteRules(
        settings: AppSettings,
        baseRules: List<RouteRule>,
        bypassLanRules: List<RouteRule>,
        customDomainRules: List<RouteRule>,
        appRoutingRules: List<RouteRule>,
        customRuleSetRules: List<RouteRule>,
        defaultRuleCatchAll: List<RouteRule>
    ): List<RouteRule> {
        return when (settings.routingMode) {
            RoutingMode.GLOBAL_PROXY -> baseRules + customRuleSetRules
            RoutingMode.GLOBAL_DIRECT -> baseRules + listOf(RouteRule(outbound = "direct"))
            RoutingMode.RULE -> baseRules + bypassLanRules + customDomainRules + appRoutingRules +
                customRuleSetRules + defaultRuleCatchAll
        }
    }

    protected override fun normalizeRunRouteRules(allRules: List<RouteRule>): List<RouteRule> {
        return allRules.map { rule ->
            if (rule.outbound == "block") {
                // sing-box 1.13.0+: "block" outbound removed, use "reject" action
                rule.copy(outbound = null, action = "reject")
            } else if (rule.action == "reject" && !rule.outbound.isNullOrBlank()) {
                rule.copy(outbound = null)
            } else if (!rule.outbound.isNullOrBlank() && rule.action.isNullOrBlank()) {
                rule.copy(action = "route")
            } else {
                rule
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod")
    protected override fun buildRunRoute(
        settings: AppSettings,
        selectorTag: String,
        outbounds: List<Outbound>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>
    ): RouteConfig {
        val hasAppRouting = settings.appRules.any { it.enabled } || settings.appGroups.any { it.enabled }

        val profileUis = _profiles.value
        val appRoutingRules = buildAppRoutingRules(
            settings = settings,
            defaultProxyTag = selectorTag,
            outbounds = outbounds,
            profiles = profileUis,
            nodeTagResolver = nodeTagResolver
        )
        val customRuleSetRules = buildCustomRuleSetRules(
            settings = settings,
            defaultProxyTag = selectorTag,
            outbounds = outbounds,
            profiles = profileUis,
            nodeTagResolver = nodeTagResolver,
            validRuleSets = validRuleSets
        )

        val quicRule = buildQuicBlockRule(settings)
        val multicastRejectRules = buildMulticastRejectRules(settings)
        val bypassLanRules = buildBypassLanRules(settings)
        val icmpEchoRules = buildIcmpEchoRules(settings)
        val customDomainRules = buildCustomDomainRules(
            settings = settings,
            defaultProxyTag = selectorTag,
            outbounds = outbounds,
            profiles = profileUis,
            nodeTagResolver = nodeTagResolver
        )
        val defaultRuleCatchAll = buildDefaultRules(settings, selectorTag)
        val hijackDnsRule = ConfigRepository.buildHijackDnsRulesStatic()
        val sniffRule = listOf(RouteRule(inbound = listOf("tun-in", "mixed-in"), action = "sniff"))

        val baseRules = hijackDnsRule + sniffRule + quicRule + multicastRejectRules + icmpEchoRules
        val allRules = selectRunRouteRules(
            settings = settings,
            baseRules = baseRules,
            bypassLanRules = bypassLanRules,
            customDomainRules = customDomainRules,
            appRoutingRules = appRoutingRules,
            customRuleSetRules = customRuleSetRules,
            defaultRuleCatchAll = defaultRuleCatchAll
        )

        val bootstrapStrategy = resolveDnsStrategy(settings.serverAddressStrategy, settings.ipVersionMode)
        val defaultResolverTag = "dns-bootstrap"

        val normalizedRules = normalizeRunRouteRules(allRules)

        return RouteConfig(
            ruleSet = validRuleSets,
            rules = normalizedRules,
            finalOutbound = selectorTag,
            findProcess = hasAppRouting,
            autoDetectInterface = true,
            defaultDomainResolver = DomainResolveConfig(
                server = defaultResolverTag,
                strategy = bootstrapStrategy
            )
        )
    }

    override suspend fun getActiveConfig(): SingBoxConfig? = withContext(Dispatchers.IO) {
        val id = _activeProfileId.value ?: return@withContext null
        loadConfig(id)
    }

    override fun getConfig(profileId: String): SingBoxConfig? {
        return loadConfig(profileId)
    }

    override suspend fun readProfileConfigContent(profileId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(_profiles.value.any { it.id == profileId }) { "Profile not found" }

            val configFile = File(configDir, "$profileId.json")
            if (configFile.exists()) {
                return@runCatching configFile.readText(Charsets.UTF_8)
            }

            val config = loadConfig(profileId) ?: throw IllegalStateException("Config not found")
            gson.toJson(config)
        }
    }

    override suspend fun updateProfileConfigContent(profileId: String, content: String): Result<ProfileUi> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(content.isNotBlank()) { context.getString(R.string.profiles_content_empty) }

                val existingProfile = _profiles.value.find { it.id == profileId }
                    ?: throw IllegalArgumentException("Profile not found")
                val parsedConfig = gson.fromJson(content, SingBoxConfig::class.java)
                    ?: throw IllegalArgumentException(context.getString(R.string.profiles_parse_failed))
                val deduplicatedConfig = deduplicateTags(parsedConfig)
                val nodes = extractNodesFromConfig(deduplicatedConfig, profileId, {})
                require(nodes.isNotEmpty()) { context.getString(R.string.profiles_parse_failed) }

                writeConfigFileOrThrow(profileId, deduplicatedConfig)
                cacheConfig(profileId, deduplicatedConfig)
                profileNodes[profileId] = nodes
                updateAllNodesAndGroups()
                if (_activeProfileId.value == profileId) {
                    applyActiveProfileNodes(profileId, nodes)
                }

                val updatedProfile = existingProfile.copy(lastUpdated = System.currentTimeMillis())
                _profiles.update { list ->
                    list.map { profile ->
                        if (profile.id == profileId) updatedProfile else profile
                    }
                }
                saveProfiles()

                updatedProfile
            }
        }

    protected override fun resolveDnsStrategy(strategy: DnsStrategy, mode: IpVersionMode): String {
        return mode.resolveDnsStrategy(strategy)
    }

    override suspend fun getOutboundByNodeId(nodeId: String): Outbound? = withContext(Dispatchers.IO) {
        val node = _nodes.value.find { it.id == nodeId } ?: return@withContext null
        val config = loadConfig(node.sourceProfileId) ?: return@withContext null
        config.outbounds?.find { it.tag == node.name }
    }

    override fun getNodeById(nodeId: String): NodeUi? {
        _nodes.value.find { it.id == nodeId }?.let { return it }
        for ((_, nodes) in profileNodes) {
            nodes.find { it.id == nodeId }?.let { return it }
        }
        _allNodes.value.find { it.id == nodeId }?.let { return it }

        return null
    }

    @Suppress("ReturnCount")
    override fun getNodeByName(nodeName: String): NodeUi? {
        _nodes.value.find { it.name == nodeName }?.let { return it }
        for ((_, nodes) in profileNodes) {
            nodes.find { it.name == nodeName }?.let { return it }
        }
        _allNodes.value.find { it.name == nodeName }?.let { return it }

        return null
    }

    override fun createNode(
        outbound: Outbound,
        targetProfileId: String?,
        newProfileName: String?) {
        var createdProfileId: String? = null
        try {
            val profileId: String
            val existingConfig: SingBoxConfig?
            var targetProfile: ProfileUi? = null
            val finalProfileName: String

            when {
                targetProfileId != null -> {
                    targetProfile = _profiles.value.find { it.id == targetProfileId }
                    if (targetProfile != null) {
                        profileId = targetProfileId
                        existingConfig = loadConfig(profileId)
                        finalProfileName = targetProfile.name
                    } else {
                        profileId = UUID.randomUUID().toString()
                        existingConfig = null
                        finalProfileName = "Manual"
                        createdProfileId = profileId
                    }
                }
                newProfileName != null -> {
                    profileId = UUID.randomUUID().toString()
                    existingConfig = null
                    finalProfileName = newProfileName
                    createdProfileId = profileId
                }
                else -> {
                    val manualProfileName = "Manual"
                    targetProfile = _profiles.value.find { it.name == manualProfileName && it.type == ProfileType.Imported }
                    if (targetProfile != null) {
                        profileId = targetProfile.id
                        existingConfig = loadConfig(profileId)
                    } else {
                        profileId = UUID.randomUUID().toString()
                        existingConfig = null
                        createdProfileId = profileId
                    }
                    finalProfileName = manualProfileName
                }
            }
            val newOutbounds = mutableListOf<Outbound>()
            existingConfig?.outbounds?.let { existing ->
                newOutbounds.addAll(existing.filter { it.type !in listOf("direct", "block", "dns") })
            }
            var finalTag = outbound.tag
            var counter = 1
            while (newOutbounds.any { it.tag == finalTag }) {
                finalTag = "${outbound.tag}_$counter"
                counter++
            }
            val finalOutbound = if (finalTag != outbound.tag) outbound.copy(tag = finalTag) else outbound
            newOutbounds.add(finalOutbound)
            if (newOutbounds.none { it.tag == "direct" }) {
                newOutbounds.add(Outbound(type = "direct", tag = "direct"))
            }
            val newConfig = deduplicateTags(
                ConfigRepository.buildConfigWithOutboundsPreservingProfileSettings(existingConfig, newOutbounds)
            )

            writeConfigFileOrThrow(profileId, newConfig)

            cacheConfig(profileId, newConfig)

            if (targetProfile == null) {
                targetProfile = ProfileUi(
                    id = profileId,
                    name = finalProfileName,
                    type = ProfileType.Imported,
                    url = null,
                    lastUpdated = System.currentTimeMillis(),
                    enabled = true,
                    updateStatus = UpdateStatus.Idle
                )
                _profiles.update { it + targetProfile }
            } else {
                _profiles.update { list ->
                    list.map { if (it.id == profileId) it.copy(lastUpdated = System.currentTimeMillis()) else it }
                }
            }

            setActiveProfile(profileId)
            scope.launch {
                val nodes = extractNodesFromConfig(newConfig, profileId)
                profileNodes[profileId] = nodes
                if (_activeProfileId.value == profileId) {
                    _nodes.value = nodes
                }
                updateAllNodesAndGroups()
                val addedNode = nodes.find { it.name == finalTag }
                if (addedNode != null) {
                    _activeNodeId.value = addedNode.id
                }

                saveProfiles()
                Log.i(ConfigRepository.TAG, "Created node: $finalTag in profile $profileId")
            }
        } catch (e: Exception) {
            createdProfileId?.let { rollbackTransientProfileFile(it) }
            Log.e(ConfigRepository.TAG, "Failed to create node", e)
        }
    }

    protected override fun removeOutboundFromConfig(config: SingBoxConfig, removedTag: String): SingBoxConfig {
        val outbounds = config.outbounds ?: return config
        val filteredOutbounds = outbounds
            .filter { it.tag != removedTag }
            .map { outbound ->
                when {
                    outbound.outbounds?.contains(removedTag) == true -> {
                        val filteredRefs = outbound.outbounds.filter { it != removedTag }
                        outbound.copy(
                            outbounds = if (filteredRefs.isEmpty()) listOf("direct") else filteredRefs,
                            default = outbound.default?.takeIf { it != removedTag }
                        )
                    }
                    outbound.detour == removedTag -> {
                        outbound.copy(detour = null)
                    }
                    else -> outbound
                }
            }
        return config.copy(outbounds = filteredOutbounds)
    }

    override suspend fun deleteNode(nodeId: String) = withContext(Dispatchers.IO) {
        val node = getNodeById(nodeId) ?: return@withContext
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return@withContext
        val newConfig = removeOutboundFromConfig(config, node.name)
        cacheConfig(profileId, newConfig)
        writeConfigFileOrThrow(profileId, newConfig)

        val immediateNodes = (profileNodes[profileId] ?: _nodes.value)
            .filter { it.id != nodeId && it.name != node.name }
        applyDeletedNodeSnapshot(profileId, nodeId, immediateNodes)

        scope.launch {
            val newNodes = extractNodesFromConfig(newConfig, profileId)
            applyDeletedNodeSnapshot(profileId, nodeId, newNodes)
            saveProfiles()
        }
    }

    protected override fun applyDeletedNodeSnapshot(profileId: String, deletedNodeId: String, nodes: List<NodeUi>) {
        profileNodes[profileId] = nodes
        updateAllNodesAndGroups()
        if (_activeProfileId.value != profileId) return

        _nodes.value = nodes
        if (_activeNodeId.value == deletedNodeId) {
            _activeNodeId.value = nodes.firstOrNull()?.id
        }
    }
}

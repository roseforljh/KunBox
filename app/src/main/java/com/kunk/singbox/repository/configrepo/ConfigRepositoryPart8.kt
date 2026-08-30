@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.repository

import android.util.Log
import com.kunk.singbox.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun ConfigRepository.buildRunEndpoints(
    baseConfig: SingBoxConfig,
    activeProfileId: String,
    allNodes: List<NodeUi>,
    nodeTagMap: Map<String, String>,
    excludedOutboundTags: Set<String> = emptySet()
): List<Endpoint>? {
    val convertedEndpoints = mutableListOf<Endpoint>()
    baseConfig.outbounds.orEmpty()
        .filterNot { it.tag in excludedOutboundTags }
        .mapNotNullTo(convertedEndpoints) {
            ConfigRepository.convertWireGuardOutboundToEndpoint(it)
        }

    val sourceConfigs = mutableMapOf<String, SingBoxConfig?>()
    nodeTagMap.forEach { (nodeId, runtimeTag) ->
        val node = allNodes.firstOrNull { it.id == nodeId } ?: return@forEach
        val sourceConfig = if (node.sourceProfileId == activeProfileId) {
            baseConfig
        } else {
            sourceConfigs.getOrPut(node.sourceProfileId) { loadConfig(node.sourceProfileId) }
        } ?: return@forEach
        val sourceOutbound = sourceConfig.outbounds.orEmpty().firstOrNull { it.tag == node.name }
            ?: sourceConfig.outbounds.orEmpty().firstOrNull { it.tag.equals(node.name, ignoreCase = true) }
            ?: return@forEach
        ConfigRepository.convertWireGuardOutboundToEndpoint(sourceOutbound, runtimeTag)
            ?.let(convertedEndpoints::add)
    }

    return ConfigRepository.mergeRuntimeEndpoints(
        convertedEndpoints = convertedEndpoints,
        existingEndpoints = baseConfig.endpoints.orEmpty().filterNot { it.tag in excludedOutboundTags }
    ).takeIf(List<Endpoint>::isNotEmpty)
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "NestedBlockDepth")
internal fun ConfigRepository.buildRunOutbounds(
    baseConfig: SingBoxConfig,
    activeProfileId: String,
    activeNode: NodeUi?,
    settings: AppSettings,
    allNodes: List<NodeUi>,
    dnsPreResolve: Boolean = false,
    dnsOverrideConfig: DnsConfig? = null,
    activeProfileAutoSelectionEnabled: Boolean = isProfileAutoSelectionEnabled(activeProfileId)
): ConfigRepositoryRunOutboundsContext {
    fun profileAutoSelectionEnabled(profileId: String): Boolean {
        return if (profileId == activeProfileId) {
            activeProfileAutoSelectionEnabled
        } else {
            isProfileAutoSelectionEnabled(profileId)
        }
    }

    fun resolveNodeRefToId(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val parts = value.split("::", limit = 2)
        if (parts.size == 2) {
            val refProfileId = parts[0]
            val nodeName = parts[1]
            return allNodes.firstOrNull {
                it.sourceProfileId == refProfileId && it.name == nodeName
            }?.id
        }
        if (allNodes.any { it.id == value }) return value
        return allNodes.firstOrNull { it.sourceProfileId == activeProfileId && it.name == value }?.id
            ?: allNodes.firstOrNull { it.name == value }?.id
    }

    val protectedNodes = allNodes.filter(NodeUi::meteredProtected)
    val protectedNodeIds = NodeProtectionStore.protectedNodeIds()
    val explicitNodeReferences = buildList {
        if (ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode)) {
            settings.appRules
                .filter {
                    it.enabled && it.outboundMode == RuleSetOutboundMode.NODE &&
                        filterVpnCapturedPackages(settings, listOf(it.packageName)).isNotEmpty()
                }
                .mapNotNullTo(this) { it.outboundValue }
            settings.appGroups
                .filter {
                    it.enabled && it.outboundMode == RuleSetOutboundMode.NODE &&
                        filterVpnCapturedPackages(settings, it.apps.map(AppInfo::packageName)).isNotEmpty()
                }
                .mapNotNullTo(this) { it.outboundValue }
            settings.customRules
                .filter { it.enabled && it.outboundMode == RuleSetOutboundMode.NODE }
                .mapNotNullTo(this) { it.outboundValue }
        }
        if (ConfigRepository.shouldApplyRuleSetRules(settings.routingMode)) {
            settings.ruleSets
                .filter { it.enabled && it.outboundMode == RuleSetOutboundMode.NODE }
                .mapNotNullTo(this) { it.outboundValue }
        }
    }
    val explicitNodeIds = explicitNodeReferences.mapNotNull(::resolveNodeRefToId).toSet()
    val explicitlyRoutedProtectedNodeIds = explicitNodeIds
        .filterTo(mutableSetOf(), protectedNodeIds::contains)
    val allowedProtectedNodeId = activeNode
        ?.takeIf { node ->
            node.meteredProtected && NodeProtectionStore.isUseAuthorized(
                nodeId = node.id,
                activeNodeId = activeNode.id,
                autoSelectionEnabled = activeProfileAutoSelectionEnabled
            )
        }
        ?.id
    MeteredNodeConfigGuard.requireNoViolations(
        MeteredNodeConfigGuard.findSettingsViolations(
            settings = settings,
            nodes = allNodes,
            allowedProtectedNodeId = allowedProtectedNodeId,
            isPackageCaptured = { packageName ->
                filterVpnCapturedPackages(settings, listOf(packageName)).isNotEmpty()
            }
        )
    )
    MeteredNodeConfigGuard.requireNoViolations(
        MeteredNodeConfigGuard.findSourceConfigViolations(
            config = baseConfig,
            sourceProfileId = activeProfileId,
            protectedNodeIds = protectedNodeIds,
            includeGroupReferences = false
        )
    )
    dnsOverrideConfig?.let { overrideConfig ->
        MeteredNodeConfigGuard.requireNoViolations(
            MeteredNodeConfigGuard.findSourceConfigViolations(
                config = SingBoxConfig(dns = overrideConfig),
                sourceProfileId = activeProfileId,
                protectedNodeIds = protectedNodeIds,
                includeGroupReferences = false
            ).map { violation -> "DNS 覆盖：$violation" }
        )
    }
    val routeOnlyProtectedNodeIds = explicitlyRoutedProtectedNodeIds - setOfNotNull(allowedProtectedNodeId)
    val disallowedProtectedNodes = protectedNodes.filter {
        it.id != allowedProtectedNodeId && it.id !in explicitlyRoutedProtectedNodeIds
    }
    val disallowedProtectedNodeIds = disallowedProtectedNodes.mapTo(mutableSetOf(), NodeUi::id)
    val disallowedProtectedTags = disallowedProtectedNodes
        .filter { it.sourceProfileId == activeProfileId }
        .mapTo(mutableSetOf(), NodeUi::name)
    val routeOnlyProtectedTags = protectedNodes
        .filter { it.sourceProfileId == activeProfileId && it.id in routeOnlyProtectedNodeIds }
        .mapTo(mutableSetOf(), NodeUi::name)
    val excludedAutomaticTags = allNodes.asSequence()
        .filter {
            it.sourceProfileId == activeProfileId &&
                (!isNodeAutoSelectionEligible(it.id) || it.meteredProtected)
        }
        .map { it.name }
        .toSet()
    val rawOutbounds = baseConfig.outbounds
        ?.let { ConfigRepository.filterAutomaticGroupCandidates(it, excludedAutomaticTags) }
        ?.let { MeteredNodeConfigGuard.removeDisallowedNodes(it, disallowedProtectedTags) }
        ?.let { MeteredNodeConfigGuard.removeGroupReferences(it, routeOnlyProtectedTags) }
    rawOutbounds?.let { sanitizedOutbounds ->
        MeteredNodeConfigGuard.requireNoViolations(
            MeteredNodeConfigGuard.findSourceConfigViolations(
                config = SingBoxConfig(outbounds = sanitizedOutbounds),
                sourceProfileId = activeProfileId,
                protectedNodeIds = protectedNodeIds,
                includeGroupReferences = true,
                includeDeclaredNodes = false,
                allowedProtectedNodeId = allowedProtectedNodeId
            )
        )
    }
    val runtimeEndpointTags = buildSet {
        baseConfig.endpoints.orEmpty().mapTo(this) { it.tag }
        rawOutbounds.orEmpty()
            .filter { it.type.equals("wireguard", ignoreCase = true) }
            .mapTo(this) { it.tag }
    }.filter(String::isNotBlank).toMutableSet()
    if (rawOutbounds.isNullOrEmpty()) {
        Log.w(ConfigRepository.TAG, "No outbounds found in base config, adding defaults")
    }

    val fixedOutbounds = rawOutbounds?.mapNotNull { outbound ->
        var processed = buildOutboundForRuntime(outbound) ?: return@mapNotNull null
        val server = processed.server?.trim().orEmpty()
        if (dnsPreResolve && ConfigRepository.shouldApplyDnsPreResolveToDomain(
                server,
                dnsOverrideConfig,
                processed.tag
            )
        ) {
            processed = applyDnsResolveToOutbound(activeProfileId, processed)
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
    val requiredNodeIds = explicitlyRoutedProtectedNodeIds.toMutableSet()
    val requiredProfileIds = mutableSetOf<String>()
    settings.appRules
        .filter {
            ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode) && it.enabled &&
                filterVpnCapturedPackages(settings, listOf(it.packageName)).isNotEmpty()
        }
        .forEach { rule ->
            when (rule.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(rule.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> rule.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
    settings.appGroups
        .filter {
            ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode) && it.enabled &&
                filterVpnCapturedPackages(settings, it.apps.map(AppInfo::packageName)).isNotEmpty()
        }
        .forEach { group ->
            when (group.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(group.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> group.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
    settings.ruleSets
        .filter { ConfigRepository.shouldApplyRuleSetRules(settings.routingMode) && it.enabled }
        .forEach { ruleSet ->
            when (ruleSet.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(ruleSet.outboundValue)
                    ?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> ruleSet.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
    settings.customRules
        .filter { ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode) && it.enabled }
        .forEach { rule ->
            when (rule.outboundMode) {
                RuleSetOutboundMode.NODE -> resolveNodeRefToId(rule.outboundValue)?.let { requiredNodeIds.add(it) }
                RuleSetOutboundMode.PROFILE -> rule.outboundValue?.let { requiredProfileIds.add(it) }
                else -> {}
            }
        }
    fixedOutbounds.mapNotNull { it.detour }.forEach { detourValue ->
        resolveNodeRefToId(detourValue)?.let { requiredNodeIds.add(it) }
    }
    if (settings.resolvedTrafficCaptureMode() == TrafficCaptureMode.ROOT_TRANSPARENT) {
        explicitNodeIds.mapNotNullTo(requiredProfileIds) { nodeId ->
            allNodes.firstOrNull { it.id == nodeId }?.sourceProfileId
        }
    }
    if (activeProfileAutoSelectionEnabled) {
        requiredProfileIds.add(activeProfileId)
    }
    activeNode?.takeIf { it.id !in disallowedProtectedNodeIds }
        ?.let { requiredNodeIds.add(it.id) }
    val strictlyRequiredNodeIds = requiredNodeIds.toSet()
    requiredProfileIds.forEach { requiredProfileId ->
        allNodes.filter {
            it.sourceProfileId == requiredProfileId && it.id !in disallowedProtectedNodeIds
        }.forEach { node ->
            requiredNodeIds.add(node.id)
        }
    }
    val nodeTagMap = mutableMapOf<String, String>()
    val existingTags = (fixedOutbounds.map { it.tag } + runtimeEndpointTags).toMutableSet()
    Log.d(ConfigRepository.TAG, "buildRunOutbounds: activeProfileId=$activeProfileId, existingTags count=${existingTags.size}")
    Log.d(ConfigRepository.TAG, "  existingTags (first 10): ${existingTags.take(10)}")
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
    val sourceConfigCache = mutableMapOf<String, SingBoxConfig?>()
    val crossProfileRoots = linkedMapOf<String, Pair<String, String>>()
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
        val sourceConfig = sourceConfigCache.getOrPut(sourceProfileId) { loadConfig(sourceProfileId) }
        if (sourceConfig == null) {
            Log.e(
                ConfigRepository.TAG,
                "Failed to load source config for cross-profile node: " +
                    "profileId=$sourceProfileId, nodeName=${node.name}"
            )
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
            Log.e(
                ConfigRepository.TAG,
                "Cross-profile outbound not found: nodeName=${node.name}, profileId=$sourceProfileId, " +
                    "available tags: ${sourceConfig.outbounds?.map { it.tag }?.take(10)}"
            )
            return@forEach
        }
        MeteredNodeConfigGuard.requireNoViolations(
            MeteredNodeConfigGuard.findSourceConfigViolations(
                config = SingBoxConfig(outbounds = listOf(sourceOutbound)),
                sourceProfileId = sourceProfileId,
                protectedNodeIds = protectedNodeIds,
                includeGroupReferences = false
            ).map { violation -> "跨配置节点「${node.name}」：$violation" }
        )
        crossProfileRoots[nodeId] = sourceProfileId to sourceOutbound.tag
    }
    if (crossProfileRoots.isNotEmpty()) {
        val runtimeOutboundsCache = mutableMapOf<String, List<Outbound>?>()
        val resolution = ConfigRepository.resolveRuntimeOutboundDependencies(
            rootReferences = crossProfileRoots.values.toList(),
            reservedTags = existingTags,
            isProtectedReference = { sourceProfileId, reference ->
                MeteredNodeConfigGuard.isProtectedNodeReference(
                    sourceProfileId = sourceProfileId,
                    reference = reference,
                    protectedNodeIds = protectedNodeIds,
                    allowedProtectedNodeId = allowedProtectedNodeId
                )
            }
        ) { sourceProfileId ->
            runtimeOutboundsCache.getOrPut(sourceProfileId) {
                val sourceConfig = sourceConfigCache.getOrPut(sourceProfileId) {
                    loadConfig(sourceProfileId)
                } ?: return@getOrPut null
                ConfigRepository.buildLatencyRuntimeOutbounds(sourceConfig) { outbound ->
                    buildOutboundForRuntime(outbound)
                }.filter { outbound ->
                    outbound.type.equals("wireguard", ignoreCase = true) ||
                        singBoxCore.validateOutbound(stripInternalMetadata(outbound))
                }
            }
        }
        val missingStrictNodes = crossProfileRoots.filter { (nodeId, rootReference) ->
            nodeId in strictlyRequiredNodeIds && rootReference !in resolution.runtimeTags
        }.keys
        if (missingStrictNodes.isNotEmpty()) {
            val names = missingStrictNodes.mapNotNull { nodeId -> allNodes.find { it.id == nodeId }?.name }
            throw IllegalStateException("跨配置节点依赖不完整：${names.joinToString()}")
        }
        resolution.outbounds.forEach { outbound ->
            if (outbound.type.equals("wireguard", ignoreCase = true)) {
                runtimeEndpointTags.add(outbound.tag)
            } else {
                fixedOutbounds.add(outbound)
            }
            existingTags.add(outbound.tag)
        }
        resolution.runtimeTags.forEach { (sourceReference, runtimeTag) ->
            val (sourceProfileId, sourceTag) = sourceReference
            allNodes.firstOrNull {
                it.sourceProfileId == sourceProfileId && it.name == sourceTag
            }?.let { node -> nodeTagMap[node.id] = runtimeTag }
        }
        crossProfileRoots.forEach { (nodeId, rootReference) ->
            resolution.runtimeTags[rootReference]?.let { runtimeTag -> nodeTagMap[nodeId] = runtimeTag }
        }
    }
    requiredProfileIds.forEach { requiredProfileId ->
        val availableProfileNodes = allNodes
            .filter { it.sourceProfileId == requiredProfileId }
        val isProfileAutoSelectionEnabled = profileAutoSelectionEnabled(requiredProfileId)
        val explicitlySelectedNodeId = activeNode
            ?.takeIf {
                requiredProfileId == activeProfileId &&
                    !isProfileAutoSelectionEnabled &&
                    (!it.meteredProtected || it.id == allowedProtectedNodeId)
            }
            ?.id
        val storedNodeId = getProfileLastSelectedNode(requiredProfileId)
        val rememberedNodeId = storedNodeId?.takeIf { rememberedId ->
            availableProfileNodes.any { it.id == rememberedId }
        } ?: availableProfileNodes.minByOrNull { it.id }?.id?.also { fallbackNodeId ->
            saveProfileNodeMemory(requiredProfileId, fallbackNodeId)
            LogRepository.getInstance().addAlwaysLog(
                "INFO [CFG] profile_selection_fallback profile=$requiredProfileId node=$fallbackNodeId"
            )
        }
        val profileNodes = availableProfileNodes
            .sortedWith(compareBy<NodeUi> { if (it.id == rememberedNodeId) 0 else 1 }.thenBy { it.id })
        val nodeIds = profileNodes.map { it.id }
        val nodeTags = nodeIds.mapNotNull { nodeTagMap[it] }.distinct()
        val eligibleNodeTags = profileNodes
            .filter {
                (isNodeAutoSelectionEligible(it.id) && !it.meteredProtected) ||
                    it.id == explicitlySelectedNodeId
            }
            .mapNotNull { nodeTagMap[it.id] }
            .distinct()
        val profileName = _profiles.value.find { it.id == requiredProfileId }?.name ?: "Profile"
        val tag = ConfigRepository.buildProfileRouteTag(requiredProfileId, profileName)
        if (nodeTags.isNotEmpty()) {
            val routeGroupOutbounds = ConfigRepository.buildProfileRouteGroupOutbounds(
                groupTag = tag,
                nodeTags = nodeTags,
                eligibleNodeTags = eligibleNodeTags,
                testUrl = settings.latencyTestUrl,
                autoSelectionEnabled = isProfileAutoSelectionEnabled,
                preferredNodeTag = rememberedNodeId?.let { nodeTagMap[it] }
            )
            if (routeGroupOutbounds.isNotEmpty()) {
                val generatedTags = routeGroupOutbounds.map { it.tag }.toSet()
                fixedOutbounds.removeAll { it.tag in generatedTags }
                fixedOutbounds.addAll(0, routeGroupOutbounds)
            }
        }
    }
    val routeOnlyRuntimeTags = routeOnlyProtectedNodeIds.mapNotNullTo(mutableSetOf()) { nodeTagMap[it] }
    val proxyTags = fixedOutbounds.filter {
        it.tag !in routeOnlyRuntimeTags && it.type in listOf(
            "vless", "vmess", "trojan", "shadowsocks",
            "hysteria2", "hysteria", "anytls", "tuic",
            "ssh", "shadowtls", "http", "socks", "naive"
        )
    }.map { it.tag }
        .plus(runtimeEndpointTags.filterNot(routeOnlyRuntimeTags::contains))
        .distinct()
        .toMutableList()
    val selectorTag = "PROXY"
    val activeProfileName = _profiles.value.find { it.id == activeProfileId }?.name ?: "Profile"
    val activeAutoTag = ConfigRepository.buildRouteGroupAutoTag(
        ConfigRepository.buildProfileRouteTag(activeProfileId, activeProfileName)
    ).takeIf { autoTag ->
        activeProfileAutoSelectionEnabled && fixedOutbounds.any { it.tag == autoTag }
    }
    if (activeAutoTag != null) {
        proxyTags.add(0, activeAutoTag)
    }
    if (proxyTags.isEmpty()) {
        proxyTags.add("direct")
    }

    val selectorDefault = activeAutoTag ?: activeNode
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
        // 手动切换只影响新连接，避免全量中断触发应用和核心同时重连。
        interruptExistConnections = false
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
                ?: if (fixedOutbounds.any { it.tag == value } || value in runtimeEndpointTags) value else null
        }
    }
    // 所有规则都使用同一套解析结果。显式 NODE 不能悄悄变成 selector，
    // 否则首页切换或自动探测会改变应用已经指定的节点。
    val ruleNodeTagResolver: (String?) -> String? = nodeTagResolver

    // Final safety check:
    // 1) Normalize detour node refs to runtime tag
    // 2) Filter out non-existent references in Selector/URLTest
    // 3) Validate detour target exists
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

    val selectorSafeOutbounds = applySelectorSafeOutbounds(detourNormalizedOutbounds, runtimeEndpointTags)

    val finalTags = selectorSafeOutbounds.map { it.tag }.toSet() + runtimeEndpointTags
    selectorSafeOutbounds.forEach { outbound ->
        val detourTag = outbound.detour
        if (detourTag.isNullOrBlank()) return@forEach

        val isInvalidDetour = detourTag == outbound.tag || detourTag !in finalTags
        if (isInvalidDetour) {
            throw IllegalStateException(
                "运行出站「${outbound.tag}」的前置代理「$detourTag」不存在或形成自引用"
            )
        }
    }

    return ConfigRepositoryRunOutboundsContext(
        outbounds = selectorSafeOutbounds,
        selectorTag = selectorTag,
        nodeTagResolver = nodeTagResolver,
        ruleNodeTagResolver = ruleNodeTagResolver,
        nodeTagMap = nodeTagMap,
        disallowedProtectedTags = disallowedProtectedTags,
        explicitlyRoutedProtectedNodeIds = explicitlyRoutedProtectedNodeIds,
        routeOnlyProtectedNodeIds = routeOnlyProtectedNodeIds
    )
}

internal fun ConfigRepository.applySelectorSafeOutbounds(
    outbounds: List<Outbound>,
    additionalTags: Set<String> = emptySet()
): List<Outbound> {
    return ConfigRepository.sanitizeSelectorSafeOutbounds(outbounds, additionalTags)
}

internal fun ConfigRepository.buildQuicBlockRule(settings: AppSettings): List<RouteRule> {
    return if (settings.blockQuic) {
        listOf(
            RouteRule(protocolRaw = listOf("quic"), action = "reject")
        )
    } else {
        emptyList()
    }
}

internal fun ConfigRepository.buildWebRtcLeakProtectionRules(): List<RouteRule> {
    return ConfigRepository.buildWebRtcLeakProtectionRulesStatic()
}

internal fun ConfigRepository.buildBypassLanRules(settings: AppSettings): List<RouteRule> {
    return ConfigRepository.buildBypassLanRulesStatic(settings)
}

internal fun ConfigRepository.buildMulticastRejectRules(settings: AppSettings): List<RouteRule> {
    return ConfigRepository.buildMulticastRejectRulesStatic(settings)
}

internal fun ConfigRepository.buildIcmpEchoRules(settings: AppSettings): List<RouteRule> {
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

internal fun ConfigRepository.buildDefaultRules(settings: AppSettings, selectorTag: String): List<RouteRule> {
    return when (settings.defaultRule) {
        DefaultRule.DIRECT -> listOf(RouteRule(outbound = "direct"))
        DefaultRule.BLOCK -> listOf(RouteRule(action = "reject"))
        DefaultRule.PROXY -> listOf(RouteRule(outbound = selectorTag))
    }
}

internal fun ConfigRepository.normalizeRunRouteRules(allRules: List<RouteRule>): List<RouteRule> {
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

@Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod", "LongMethod")
internal fun ConfigRepository.buildRunRoute(
    settings: AppSettings,
    selectorTag: String,
    outbounds: List<Outbound>,
    nodeTagResolver: (String?) -> String?,
    validRuleSets: List<RuleSetConfig>,
    rootRoutingPlan: RootAppRoutingPlan? = null
): RouteConfig {
    val profileUis = _profiles.value
    val appRoutingRules = if (ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode)) {
        if (rootRoutingPlan != null) {
            rootRoutingPlan.lanes.map { lane ->
                val baseRule = ConfigRepository.toRouteRule(
                    ConfigRepository.rootLaneSemantic(lane),
                    selectorTag
                )
                baseRule.copy(
                    inbound = lane.inboundTags(rootRoutingPlan.proxyIpv4, rootRoutingPlan.proxyIpv6)
                )
            }
        } else {
            buildAppRoutingRules(
                settings = settings,
                defaultProxyTag = selectorTag,
                outbounds = outbounds,
                profiles = profileUis,
                nodeTagResolver = nodeTagResolver
            )
        }
    } else {
        emptyList()
    }
    val customRuleSetRules = if (ConfigRepository.shouldApplyRuleSetRules(settings.routingMode)) {
        buildCustomRuleSetRules(
            settings = settings,
            defaultProxyTag = selectorTag,
            outbounds = outbounds,
            profiles = profileUis,
            nodeTagResolver = nodeTagResolver,
            validRuleSets = validRuleSets
        )
    } else {
        emptyList()
    }

    val quicRule = buildQuicBlockRule(settings)
    val webRtcLeakProtectionRules = buildWebRtcLeakProtectionRules()
    val multicastRejectRules = buildMulticastRejectRules(settings)
    val bypassLanRules = buildBypassLanRules(settings)
    val icmpEchoRules = buildIcmpEchoRules(settings)
    val customDomainRules = if (ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode)) {
        buildCustomDomainRules(
            settings = settings,
            defaultProxyTag = selectorTag,
            outbounds = outbounds,
            profiles = profileUis,
            nodeTagResolver = nodeTagResolver
        )
    } else {
        emptyList()
    }
    val defaultRuleCatchAll = buildDefaultRules(settings, selectorTag)
    val hijackDnsRule = ConfigRepository.buildHijackDnsRulesStatic(settings, rootRoutingPlan)
    val baseRules = multicastRejectRules + hijackDnsRule + webRtcLeakProtectionRules + quicRule + icmpEchoRules
    val allRules = ConfigRepository.selectRunRouteRulesStatic(
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
    normalizedRules.withIndex()
        .filter { (_, rule) -> "org.telegram.messenger" in rule.packageName.orEmpty() }
        .forEach { (index, rule) ->
            val trace = "[APP_ROUTE_TRACE] config ruleIndex=$index " +
                "packages=${rule.packageName.orEmpty().joinToString("|")} " +
                "outbound=${rule.outbound.orEmpty()} action=${rule.action.orEmpty()} " +
                "selector=$selectorTag available=${outbounds.joinToString("|") { it.tag }}"
            Log.i(ConfigRepository.TAG, trace)
            LogRepository.getInstance().addAlwaysLog("INFO $trace")
        }

    return RouteConfig(
        ruleSet = validRuleSets,
        rules = normalizedRules,
        finalOutbound = selectorTag,
        autoDetectInterface = true,
        defaultDomainResolver = DomainResolveConfig(
            server = defaultResolverTag,
            strategy = bootstrapStrategy
        )
    )
}

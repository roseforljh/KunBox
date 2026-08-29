@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.repository

import android.util.Log
import com.google.gson.JsonParser
import com.kunk.singbox.model.*
import com.kunk.singbox.repository.config.OutboundFixer
import com.kunk.singbox.utils.dns.DnsResolver
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal fun ConfigRepository.buildOutboundForRuntime(outbound: Outbound): Outbound? =
    OutboundFixer.buildForRuntime(context, outbound)

internal fun ConfigRepository.loadConfigWithLegacyEchRepair(profile: ProfileUi?, profileId: String): SingBoxConfig? {
    val config = loadConfig(profileId) ?: return null
    val subscriptionUrl = profile?.url?.takeIf { it.isNotBlank() } ?: return config
    if (!ConfigRepository.needsLegacyEchDnsRepair(config)) return config

    val repairedConfig = fetchAndParseSubscription(subscriptionUrl)?.config ?: return config
    val deduplicatedConfig = deduplicateTags(repairedConfig)
    if (ConfigRepository.needsLegacyEchDnsRepair(deduplicatedConfig)) return config

    runCatching {
        writeConfigFileOrThrow(profileId, deduplicatedConfig)
        cacheConfig(profileId, deduplicatedConfig)
        val repairedNodes = extractNodesFromConfigSync(deduplicatedConfig, profileId)
        profileNodes[profileId] = repairedNodes
        updateAllNodesAndGroups()
        if (_activeProfileId.value == profileId) {
            _nodes.value = repairedNodes
        }
        Log.i(ConfigRepository.TAG, "Repaired legacy ECH subscription config for profile: ${profile.name}")
    }.onFailure { e ->
        Log.w(ConfigRepository.TAG, "Failed to persist repaired ECH subscription config for profile: $profileId", e)
    }
    return deduplicatedConfig
}

internal fun ConfigRepository.stripInternalMetadata(config: SingBoxConfig): SingBoxConfig {
    return config.copy(
        outbounds = config.outbounds?.map { stripInternalMetadata(it) },
        proxies = config.proxies?.map { stripInternalMetadata(it) }
    )
}

internal fun ConfigRepository.stripInternalMetadata(outbound: Outbound): Outbound {
    val tls = outbound.tls ?: return outbound
    val ech = tls.ech ?: return outbound
    return outbound.copy(tls = tls.copy(ech = ech.copy(dnsServer = null)))
}

internal suspend fun ConfigRepository.preResolveDomainsForProfile(
    profileId: String,
    config: SingBoxConfig,
    dnsServer: String?
) {
    val domains = config.outbounds.orEmpty()
        .mapNotNull { it.server }
        .filterNot(DnsResolver::isIpAddress)
        .distinct()
    if (domains.isEmpty()) return

    val results = dnsResolver.resolveBatch(
        domains = domains,
        dohServer = dnsServer ?: DnsResolver.DOH_CLOUDFLARE
    )
    dnsResolveStore.saveBatch(profileId, results)
}

internal fun ConfigRepository.applyDnsResolveToOutbound(profileId: String, outbound: Outbound): Outbound {
    val server = outbound.server ?: return outbound
    if (DnsResolver.isIpAddress(server)) return outbound
    return dnsResolveStore.getIp(profileId, server)?.let { outbound.copy(server = it) } ?: outbound
}

internal fun ConfigRepository.detectValidRuleSetFileFormat(file: File, tag: String): String? {
    if (!file.exists() || file.length() == 0L) {
        Log.w(ConfigRepository.TAG, "Rule set file not found or empty: $tag (${file.absolutePath})")
        return null
    }

    return try {
        val sample = readRuleSetSample(file)
        if (sample.isEmpty()) {
            Log.w(ConfigRepository.TAG, "Rule set file header is empty, ignoring: $tag")
            return null
        }

        if (!isLikelyTextRuleSet(sample)) {
            if (validateBinaryRuleSet(file, tag)) "binary" else null
        } else {
            if (validateTextRuleSet(file, tag, readRuleSetInspectionText(file, sample))) "source" else null
        }
    } catch (e: Exception) {
        Log.w(ConfigRepository.TAG, "Failed to validate rule set file: $tag", e)
        null
    }
}

internal fun ConfigRepository.readRuleSetSample(file: File): ByteArray {
    return file.inputStream().use { input ->
        val buffer = ByteArray(ConfigRepository.RULE_SET_SNIFF_BYTES)
        val read = input.read(buffer)
        if (read > 0) buffer.copyOf(read) else ByteArray(0)
    }
}

internal fun ConfigRepository.isLikelyTextRuleSet(sample: ByteArray): Boolean {
    if (sample.any { it == 0.toByte() }) return false
    val printableBytes = sample.count { byte ->
        val code = byte.toInt() and 0xff
        code == 9 || code == 10 || code == 13 || code in 32..126
    }
    return printableBytes >= sample.size * 3 / 4
}

internal fun ConfigRepository.readRuleSetInspectionText(file: File, sample: ByteArray): String {
    return if (file.length() <= ConfigRepository.RULE_SET_TEXT_PARSE_LIMIT_BYTES) {
        file.readText()
    } else {
        sample.toString(Charsets.UTF_8)
    }
}

internal fun ConfigRepository.validateBinaryRuleSet(file: File, tag: String): Boolean {
    val sample = readRuleSetSample(file)
    if (file.length() >= ConfigRepository.RULE_SET_MIN_SIZE_BYTES && hasRuleSetBinaryMagic(sample)) {
        return true
    }
    Log.w(ConfigRepository.TAG, "Rule set binary file is not a valid .srs file, ignoring: $tag (${file.length()} bytes)")
    return false
}

internal fun ConfigRepository.hasRuleSetBinaryMagic(sample: ByteArray): Boolean {
    if (sample.size < ConfigRepository.RULE_SET_BINARY_MAGIC.length) return false
    return sample[0] == 'S'.code.toByte() &&
        sample[1] == 'R'.code.toByte() &&
        sample[2] == 'S'.code.toByte()
}

internal fun ConfigRepository.validateTextRuleSet(file: File, tag: String, inspectionText: String): Boolean {
    val trimmed = inspectionText.trim()
    val validTextRuleSet = when {
        trimmed.startsWith("{") || trimmed.startsWith("[") -> isValidRuleSetJson(trimmed)
        else -> isValidRuleSetStructuredText(trimmed)
    }
    return when {
        trimmed.isEmpty() -> {
            Log.w(ConfigRepository.TAG, "Rule set text content is blank, ignoring: $tag")
            false
        }

        ConfigRepository.looksLikeHtmlSubscriptionPage(contentType = null, body = trimmed) -> {
            Log.e(ConfigRepository.TAG, "Rule set file appears to be HTML, ignoring: $tag")
            false
        }

        validTextRuleSet -> true
        else -> rejectUnrecognizedRuleSetText(file, tag, trimmed)
    }
}

internal fun ConfigRepository.rejectUnrecognizedRuleSetText(file: File, tag: String, trimmed: String): Boolean {
    if (file.length() < ConfigRepository.RULE_SET_MIN_SIZE_BYTES) {
        Log.w(ConfigRepository.TAG, "Rule set text file too small, ignoring: $tag (${file.length()} bytes)")
        return false
    }
    if (ConfigRepository.REGEX_RULE_SET_ERROR_TEXT.containsMatchIn(trimmed.lineSequence().firstOrNull().orEmpty())) {
        Log.e(ConfigRepository.TAG, "Rule set file looks like an error response, ignoring: $tag")
        return false
    }

    Log.w(ConfigRepository.TAG, "Rule set file content not recognized, ignoring: $tag (${file.length()} bytes)")
    return false
}

internal fun ConfigRepository.isValidRuleSetJson(content: String): Boolean {
    return runCatching {
        val element = JsonParser.parseString(content)
        when {
            element.isJsonArray -> element.asJsonArray.size() > 0
            !element.isJsonObject -> false
            else -> {
                val obj = element.asJsonObject
                obj.has("rules") ||
                    obj.has("rule_set") ||
                    obj.has("payload") ||
                    obj.has("type") ||
                    obj.has("version") ||
                    ConfigRepository.REGEX_RULE_SET_JSON_KEYS.containsMatchIn(content)
            }
        }
    }.getOrDefault(false)
}

internal fun ConfigRepository.isValidRuleSetStructuredText(content: String): Boolean {
    val lines = content.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
        .take(8)
        .toList()
    if (lines.isEmpty()) return false
    if (ConfigRepository.REGEX_RULE_SET_ERROR_TEXT.containsMatchIn(lines.first())) {
        return false
    }
    return lines.any { ConfigRepository.REGEX_RULE_SET_TEXT_LINE.containsMatchIn(it) }
}

internal fun ConfigRepository.buildCustomRuleSets(settings: AppSettings): List<RuleSetConfig> {
    val ruleSetRepo = RuleSetRepository.getInstance(context)

    val rules = settings.ruleSets.filter { it.enabled }.map { ruleSet ->
        if (ruleSet.type == RuleSetType.REMOTE) {
            val localPath = ruleSetRepo.getRuleSetPath(ruleSet.tag)
            val file = File(localPath)
            val detectedFormat = detectValidRuleSetFileFormat(file, ruleSet.tag)
            if (detectedFormat != null) {
                RuleSetConfig(
                    tag = ruleSet.tag,
                    type = "local",
                    format = detectedFormat,
                    path = localPath
                )
            } else null
        } else {
            val file = File(ruleSet.path)
            val detectedFormat = detectValidRuleSetFileFormat(file, ruleSet.tag)
            if (detectedFormat != null) {
                RuleSetConfig(
                    tag = ruleSet.tag,
                    type = "local",
                    format = detectedFormat,
                    path = ruleSet.path
                )
            } else {
                Log.w(ConfigRepository.TAG, "Local rule set file not found: ${ruleSet.tag} (${ruleSet.path})")
                null
            }
        }
    }.filterNotNull().toMutableList()

    return rules
}

internal fun ConfigRepository.buildCustomDomainRules(
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
            ConfigRepository.applyCustomRuleMatcher(baseRule, rule.type, values)
        }
    return rules
}

@Suppress("LongParameterList")
internal fun ConfigRepository.buildCustomRuleSetRules(
    settings: AppSettings,
    defaultProxyTag: String,
    outbounds: List<Outbound>,
    profiles: List<ProfileUi>,
    nodeTagResolver: (String?) -> String?,
    validRuleSets: List<RuleSetConfig>
): List<RouteRule> {
    val rules = mutableListOf<RouteRule>()

    val validTags = validRuleSets.mapNotNull { it.tag }.toSet()
    // 特定服务规则集必须排在国家/地区泛化规则前，避免 geolocation-!cn 抢先吃掉 openai/google 等规则
    val orderedRuleSets = ConfigRepository.sortRuleSetsForRouting(
        settings.ruleSets.filter { it.enabled && it.tag in validTags }
    )

    orderedRuleSets.forEach { ruleSet ->
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
        val inboundTags = ConfigRepository.normalizeRuleSetInboundTags(ruleSet.inbounds, settings)

        rules.add(baseRule.copy(
            ruleSet = listOf(ruleSet.tag),
            inbound = inboundTags
        ))
    }

    return rules
}

internal fun ConfigRepository.resolvePackagesSharingUid(packageNames: List<String>): List<String> {
    return ConfigRepository.expandSharedUidPackageNames(
        packageNames = packageNames,
        resolveUid = { packageName ->
            runCatching { context.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
        },
        resolvePackages = { uid ->
            context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        }
    )
}

@Suppress("LongMethod")
internal fun ConfigRepository.buildAppRoutingRules(
    settings: AppSettings,
    defaultProxyTag: String,
    outbounds: List<Outbound>,
    profiles: List<ProfileUi>,
    nodeTagResolver: (String?) -> String?
): List<RouteRule> {
    val rules = mutableListOf<RouteRule>()
    val targetByPackage = mutableMapOf<String, String>()

    fun addAppRule(label: String, baseRule: RouteRule, packageNames: List<String>) {
        if (packageNames.isEmpty()) return
        val target = ConfigRepository.routeTargetKey(baseRule)
        packageNames.forEach { packageName ->
            val previous = targetByPackage.putIfAbsent(packageName, target)
            require(previous == null || previous == target) {
                "应用分流冲突：$label 与其他规则展开到同一 UID 应用 $packageName，" +
                    "但目标分别为 $previous 和 $target。请把共享 UID 应用放入同一分组并使用同一目标。"
            }
        }
        rules.add(baseRule.copy(packageName = packageNames))
    }

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
                    selectorTag = defaultProxyTag,
                    outbounds = outbounds,
                    profiles = profiles,
                    nodeTagResolver = nodeTagResolver
                ),
                label = "应用「${rule.appName.ifBlank { rule.packageName }}」"
            )
            val baseRule = ConfigRepository.toRouteRule(semantic, defaultProxyTag)
            addAppRule("应用「${rule.appName.ifBlank { rule.packageName }}」", baseRule, packageNames)
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
                    selectorTag = defaultProxyTag,
                    outbounds = outbounds,
                    profiles = profiles,
                    nodeTagResolver = nodeTagResolver
                ),
                label = "应用分组「${group.name}」"
            )
            val baseRule = ConfigRepository.toRouteRule(semantic, defaultProxyTag)
            addAppRule("应用分组「${group.name}」", baseRule, packageNames)
        }

    return rules
}

internal fun ConfigRepository.buildRootAppRoutingPlan(
    settings: AppSettings,
    outboundsContext: ConfigRepositoryRunOutboundsContext,
    generation: Long
): RootAppRoutingPlan {
    val semanticContext = ConfigRepositoryOutboundSemanticContext(
        selectorTag = outboundsContext.selectorTag,
        outbounds = outboundsContext.outbounds,
        profiles = _profiles.value,
        // Root 的 NODE 必须绑定物理 outbound。selector 只能表示 PROXY 或 PROFILE，
        // 不能作为明确节点的隐藏故障切换入口。
        nodeTagResolver = outboundsContext.nodeTagResolver
    )
    val assignments = buildList {
        if (!ConfigRepository.shouldApplyCustomAndAppRules(settings.routingMode)) return@buildList
        settings.appRules.filter(AppRule::enabled).forEach { rule ->
            val packages = rootCapturedPackages(settings, listOf(rule.packageName))
            if (packages.isEmpty()) return@forEach
            val label = "应用「${rule.appName.ifBlank { rule.packageName }}」"
            add(
                ConfigRepository.toRootAppRoutingAssignment(
                    packageNames = packages,
                    semantic = ConfigRepository.resolveAppOutboundSemanticStrict(
                        mode = ConfigRepository.resolveAppRuleOutboundMode(rule.outboundMode),
                        value = rule.outboundValue,
                        context = semanticContext,
                        label = label
                    ),
                    selectorTag = outboundsContext.selectorTag,
                    sourceLabel = label
                )
            )
        }
        settings.appGroups.filter(AppGroup::enabled).forEach { group ->
            val packages = rootCapturedPackages(settings, group.apps.map(AppInfo::packageName))
            if (packages.isEmpty()) return@forEach
            val label = "应用分组「${group.name}」"
            add(
                ConfigRepository.toRootAppRoutingAssignment(
                    packageNames = packages,
                    semantic = ConfigRepository.resolveAppOutboundSemanticStrict(
                        mode = ConfigRepository.resolveAppGroupOutboundMode(group.outboundMode),
                        value = group.outboundValue,
                        context = semanticContext,
                        label = label
                    ),
                    selectorTag = outboundsContext.selectorTag,
                    sourceLabel = label
                )
            )
        }
    }
    return RootAppRoutingPlanCompiler.compile(settings, assignments, generation)
}

internal fun ConfigRepository.rootCapturedPackages(settings: AppSettings, packageNames: List<String>): List<String> {
    val policy = PerAppVpnPolicy.from(settings)
    return packageNames.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .filter { policy.captures(it, context.packageName) }
        .distinct()
        .toList()
}

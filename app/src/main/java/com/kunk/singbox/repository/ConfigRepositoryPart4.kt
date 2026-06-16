package com.kunk.singbox.repository

import com.kunk.singbox.R
import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import com.kunk.singbox.model.*
import com.kunk.singbox.repository.config.OutboundFixer
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.kunk.singbox.utils.dns.DnsResolver

@Suppress("TooManyFunctions")
abstract class ConfigRepositoryPart4(context: Context) : ConfigRepositoryPart3(context) {
    override suspend fun testNodeLatency(nodeId: String): Long {
        val existing = inFlightLatencyTests[nodeId]
        if (existing != null) {
            return existing.await()
        }

        val deferred = CompletableDeferred<Long>()
        val prev = inFlightLatencyTests.putIfAbsent(nodeId, deferred)
        if (prev != null) {
            return prev.await()
        }

        try {
            val result = withContext(Dispatchers.IO) {
                run {
                    try {
                        val node = _nodes.value.find { it.id == nodeId }
                            ?: _allNodes.value.find { it.id == nodeId }
                        if (node == null) {
                            Log.e(ConfigRepository.TAG, "Node not found: $nodeId")
                            return@withContext -1L
                        }

                        val config = loadConfig(node.sourceProfileId)
                        if (config == null) {
                            Log.e(ConfigRepository.TAG, "Config not found for profile: ${node.sourceProfileId}")
                            return@withContext -1L
                        }

                        val rawOutbound = config.outbounds?.find { it.tag == node.name }
                        if (rawOutbound == null) {
                            Log.e(ConfigRepository.TAG, "Outbound not found: ${node.name}")
                            return@withContext -1L
                        }

                        val settings = settingsRepository.settings.first()
                        val runtimeContext = buildLatencyRuntimeContext(node.sourceProfileId, config, settings)
                        val fixedOutbound = runtimeContext.outbounds.find { it.tag == rawOutbound.tag }
                        if (fixedOutbound == null) {
                            Log.e(ConfigRepository.TAG, "Outbound type removed: ${rawOutbound.type}")
                            return@withContext -1L
                        }
                        val allOutbounds = runtimeContext.outbounds
                        val probeOutbound = prepareOfflineProbeOutbound(fixedOutbound)
                        val latency = singBoxCore.testOutboundLatency(
                            probeOutbound,
                            allOutbounds,
                            runtimeContext.dnsConfig
                        )
                        val finalLatency = if (latency > 0) {
                            latency
                        } else {
                            val fallback = ipv6TcpLatencyFallback(probeOutbound)
                            if (fallback > 0) {
                                fallback
                            } else {
                                resolveIpv6OnlyStatus(probeOutbound, latency)
                            }
                        }

                        _nodes.update { list ->
                            list.map {
                                if (it.id == nodeId) {
                                    it.copy(latencyMs = normalizeLatencyValue(finalLatency))
                                } else {
                                    it
                                }
                            }
                        }

                        profileNodes[node.sourceProfileId] = profileNodes[node.sourceProfileId]?.map {
                            if (it.id == nodeId) {
                                it.copy(latencyMs = normalizeLatencyValue(finalLatency))
                            } else {
                                it
                            }
                        } ?: emptyList()
                        updateLatencyInAllNodes(nodeId, finalLatency)
                        saveProfiles()

                        finalLatency
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) {
                            throw e
                        }
                        Log.e(ConfigRepository.TAG, "Latency test error for $nodeId", e)
                        val nodeName = _nodes.value.find { it.id == nodeId }?.name
                            ?: _allNodes.value.find { it.id == nodeId }?.name
                        LogRepository.getInstance().addLog(context.getString(R.string.nodes_test_failed, nodeName ?: nodeId) + ": ${e.message}")
                        -1L
                    }
                }
            }
            deferred.complete(result)
            return result
        } catch (e: Exception) {
            deferred.complete(-1L)
            return -1L
        } finally {
            inFlightLatencyTests.remove(nodeId, deferred)
        }
    }

    override suspend fun clearAllNodesLatency() = withContext(Dispatchers.IO) {
        savedNodeLatencies.clear()

        _nodes.update { list ->
            list.map { it.copy(latencyMs = null) }
        }

        // Update profileNodes map
        profileNodes.keys.forEach { profileId ->
            profileNodes[profileId] = profileNodes[profileId]?.map {
                it.copy(latencyMs = null)
            } ?: emptyList()
        }
        _allNodes.update { list ->
            list.map { it.copy(latencyMs = null) }
        }
    }

    override suspend fun testAllNodesLatency(
        targetNodeIds: List<String>?,
        useAllNodes: Boolean,
        onNodeComplete: ((nodeId: String, latencyMs: Long) -> Unit)?) = withContext(Dispatchers.IO) {
        val sourceNodes = if (useAllNodes) _allNodes.value else _nodes.value
        val nodes = if (targetNodeIds != null) {
            sourceNodes.filter { it.id in targetNodeIds }
        } else {
            sourceNodes
        }

        val settings = settingsRepository.settings.first()
        val testInfoList = buildNodeTestInfos(nodes, settings)

        if (testInfoList.isEmpty()) {
            Log.w(ConfigRepository.TAG, "No valid nodes to test")
            return@withContext
        }

        val (tcpFallbackInfos, regularInfos) = testInfoList.partition {
            LatencyProbePolicy.shouldUseTcpFallback(it.outbound)
        }

        val concurrency = settings.latencyTestConcurrency.coerceIn(1, 20)

        coroutineScope {
            val regularJob = async {
                testRegularOutboundsLatency(regularInfos, concurrency, onNodeComplete)
            }
            val tcpFallbackJob = async {
                testTcpFallbackOutboundsLatency(tcpFallbackInfos, concurrency, onNodeComplete)
            }

            regularJob.await()
            tcpFallbackJob.await()
        }

        saveProfiles()
    }

    override suspend fun updateAllProfiles(): BatchUpdateResult = withContext(Dispatchers.IO) {
        val enabledProfiles = _profiles.value.filter { it.enabled && it.type == ProfileType.Subscription }

        if (enabledProfiles.isEmpty()) {
            return@withContext BatchUpdateResult()
        }
        val semaphore = Semaphore(3)
        val results = enabledProfiles.map { profile ->
            async {
                semaphore.withPermit {
                    updateProfile(profile.id)
                }
            }
        }.awaitAll()

        BatchUpdateResult(
            successWithChanges = results.count { it is SubscriptionUpdateResult.SuccessWithChanges },
            successNoChanges = results.count { it is SubscriptionUpdateResult.SuccessNoChanges },
            failed = results.count { it is SubscriptionUpdateResult.Failed },
            details = results
        )
    }

    @Suppress("LongMethod", "CognitiveComplexMethod")
    override suspend fun updateProfile(profileId: String): SubscriptionUpdateResult {
        val profile = _profiles.value.find { it.id == profileId }
            ?: return SubscriptionUpdateResult.Failed("Unknown Profile", "Profile not found")

        if (profile.url.isNullOrBlank()) {
            return SubscriptionUpdateResult.Failed(profile.name, "Subscription URL is empty")
        }

        val updateRunId = beginProfileUpdateRun(profileId)
        profileResetJobs.remove(profileId)?.cancel()
        updateProfileForCurrentRun(profileId, updateRunId) {
            it.copy(
                updateStatus = UpdateStatus.Updating,
                updateStage = SubscriptionUpdateStage.Requesting
            )
        }

        val result = try {
            importFromSubscriptionUpdate(profile, updateRunId)
        } catch (e: Exception) {
            SubscriptionUpdateResult.Failed(profile.name, e.message ?: "Subscription update failed")
        }
        updateProfileForCurrentRun(profileId, updateRunId) {
            it.copy(
                updateStatus = if (result is SubscriptionUpdateResult.Failed) {
                    UpdateStatus.Failed
                } else {
                    UpdateStatus.Success
                },
                lastUpdated = if (result is SubscriptionUpdateResult.Failed) {
                    it.lastUpdated
                } else {
                    System.currentTimeMillis()
                },
                updateStage = when {
                    result is SubscriptionUpdateResult.Failed -> null
                    it.updateStage?.isBackground == true -> it.updateStage
                    else -> null
                }
            )
        }
        val resetJob = scope.launch {
            kotlinx.coroutines.delay(2000)
            updateProfileForCurrentRun(profileId, updateRunId) {
                if (it.updateStatus == UpdateStatus.Updating) {
                    it
                } else {
                    it.copy(
                        updateStatus = UpdateStatus.Idle,
                        updateStage = it.updateStage?.takeIf(SubscriptionUpdateStage::isBackground)
                    )
                }
            }
        }
        resetJob.invokeOnCompletion {
            profileResetJobs.remove(profileId, resetJob)
        }
        profileResetJobs[profileId] = resetJob

        return result
    }

    @Suppress("LongMethod", "CognitiveComplexMethod")
    protected override suspend fun importFromSubscriptionUpdate(
        profile: ProfileUi,
        updateRunId: Long
    ): SubscriptionUpdateResult = withContext(Dispatchers.IO) {
        var previousConfigText: String? = null
        try {
            val oldNodes = profileNodes[profile.id] ?: emptyList()
            val oldNodeNames = oldNodes.map { it.name }.toSet()
            val profileUrl = profile.url
            if (profileUrl.isNullOrBlank()) {
                return@withContext SubscriptionUpdateResult.Failed(profile.name, "Subscription URL is empty")
            }

            val fetchResult = fetchAndParseSubscription(
                url = profileUrl,
                onProgress = {},
                onStageChanged = { stage -> setProfileUpdateStage(profile.id, updateRunId, stage) }
            )
                ?: return@withContext SubscriptionUpdateResult.Failed(profile.name, "Failed to fetch subscription")

            val config = fetchResult.config
            val userInfo = fetchResult.userInfo

            val deduplicatedConfig = deduplicateTags(config)
            val newNodes = extractNodesFromConfig(deduplicatedConfig, profile.id)
            val newNodeNames = newNodes.map { it.name }.toSet()
            val addedNodes = newNodeNames - oldNodeNames
            val removedNodes = oldNodeNames - newNodeNames
            setProfileUpdateStage(profile.id, updateRunId, SubscriptionUpdateStage.Saving)
            previousConfigText = File(configDir, "${profile.id}.json")
                .takeIf { it.exists() }
                ?.readText()
            writeConfigFileOrThrow(profile.id, deduplicatedConfig)

            cacheConfig(profile.id, deduplicatedConfig)
            profileNodes[profile.id] = newNodes
            updateAllNodesAndGroups()
            if (_activeProfileId.value == profile.id) {
                _nodes.value = newNodes
            }
            val defaultQrName = context.getString(R.string.profiles_qrcode_subscription)
            val finalName = if ((profile.name == defaultQrName || profile.name.isBlank() || profile.name == "扫码订阅" || profile.name == "QR Code Subscription") &&
                !fetchResult.subscriptionName.isNullOrBlank()) {
                fetchResult.subscriptionName
            } else {
                profile.name
            }

            _profiles.update { list ->
                list.map {
                    if (it.id == profile.id) {
                        it.copy(
                            name = finalName,
                            expireDate = userInfo?.expire ?: it.expireDate,
                            totalTraffic = userInfo?.total ?: it.totalTraffic,
                            usedTraffic = if (userInfo != null) (userInfo.upload + userInfo.download) else it.usedTraffic
                        )
                    } else {
                        it
                    }
                }
            }

            saveProfiles()
            val updateResult = buildSubscriptionUpdateSuccessResult(
                profileName = profile.name,
                addedNodes = addedNodes,
                removedNodes = removedNodes,
                totalCount = newNodes.size,
                dnsMovedToBackground = ConfigRepository.launchSubscriptionDnsPreResolve(
                    scope = scope,
                    profileId = profile.id,
                    enabled = profile.dnsPreResolve,
                    updateRunId = updateRunId,
                    onStarted = {
                        setProfileUpdateStage(profile.id, updateRunId, SubscriptionUpdateStage.DnsBackground)
                    },
                    onFinished = {
                        setProfileUpdateStage(profile.id, updateRunId, null)
                    }
                ) {
                    preResolveDomainsForProfileBestEffort(profile.id, deduplicatedConfig, profile.dnsServer)
                } != null
            )

            updateResult
        } catch (e: Exception) {
            previousConfigText?.let { oldText ->
                runCatching {
                    val oldConfig = gson.fromJson(oldText, SingBoxConfig::class.java)
                    ConfigRepository.writeTextFileAtomically(File(configDir, "${profile.id}.json"), oldText)
                    cacheConfig(profile.id, oldConfig)
                    profileNodes[profile.id] = extractNodesFromConfigSync(oldConfig, profile.id)
                    updateAllNodesAndGroups()
                }.onFailure { restoreError ->
                    Log.e(ConfigRepository.TAG, "Failed to restore previous config after subscription update failure", restoreError)
                }
            }
            SubscriptionUpdateResult.Failed(profile.name, e.message ?: "Subscription update failed")
        }
    }

    protected override fun buildSubscriptionUpdateSuccessResult(
        profileName: String,
        addedNodes: Set<String>,
        removedNodes: Set<String>,
        totalCount: Int,
        dnsMovedToBackground: Boolean
    ): SubscriptionUpdateResult {
        return if (addedNodes.isNotEmpty() || removedNodes.isNotEmpty()) {
            SubscriptionUpdateResult.SuccessWithChanges(
                profileName = profileName,
                addedCount = addedNodes.size,
                removedCount = removedNodes.size,
                totalCount = totalCount,
                dnsMovedToBackground = dnsMovedToBackground
            )
        } else {
            SubscriptionUpdateResult.SuccessNoChanges(
                profileName = profileName,
                totalCount = totalCount,
                dnsMovedToBackground = dnsMovedToBackground
            )
        }
    }

    override suspend fun generateConfigFile(): ConfigRepository.ConfigGenerationResult? = withContext(Dispatchers.IO) {
        try {
            awaitInitialProfilesLoaded()
            val activeId = _activeProfileId.value
                ?: activeStateDao.get()?.activeProfileId
                ?: return@withContext null
            val activeProfile = _profiles.value.find { it.id == activeId }
            val config = loadConfigWithLegacyEchRepair(activeProfile, activeId) ?: return@withContext null
            val activeNodeId = _activeNodeId.value
                ?: activeStateDao.get()?.activeNodeId

            val allNodesSnapshot = _allNodes.value.takeIf { it.isNotEmpty() } ?: loadAllNodesSnapshot()
            val activeNode = _nodes.value.find { it.id == activeNodeId }
                ?: allNodesSnapshot.find { it.id == activeNodeId }
            val sanitizedSettings = settingsRepository.settings.first()
            val log = buildRunLogConfig()
            val experimental = buildRunExperimentalConfig(sanitizedSettings)
            val inbounds = buildRunInbounds(sanitizedSettings)
            val customRuleSets = buildCustomRuleSets(sanitizedSettings)

            val dnsOverrideConfig = parseDnsOverride(activeProfile?.dnsOverride)
            val rawOutboundsContext = buildRunOutbounds(
                config, activeNode, sanitizedSettings, allNodesSnapshot,
                activeProfile?.dnsPreResolve ?: false, activeId, dnsOverrideConfig
            )
            val serverAddressStrategy = resolveDnsStrategy(
                sanitizedSettings.serverAddressStrategy,
                sanitizedSettings.ipVersionMode
            )
            val defaultResolverOutbounds = ConfigRepository.applyDefaultOutboundDomainResolver(
                rawOutboundsContext.outbounds,
                "local",
                serverAddressStrategy
            )
            val outboundsContext = rawOutboundsContext.copy(
                outbounds = if (dnsOverrideConfig != null) {
                    ConfigRepository.applyDnsOverrideDomainResolvers(defaultResolverOutbounds, dnsOverrideConfig)
                } else {
                    defaultResolverOutbounds
                }
            )
            val dns = buildRunDns(
                sanitizedSettings,
                customRuleSets,
                outboundsContext,
                dnsOverrideConfig,
                config.dns
            )
            val route = buildRunRoute(
                sanitizedSettings,
                outboundsContext.selectorTag,
                outboundsContext.outbounds,
                outboundsContext.nodeTagResolver,
                customRuleSets
            )

            lastTagToNodeName = outboundsContext.nodeTagMap.mapNotNull { (nodeId, tag) ->
                val name = allNodesSnapshot.firstOrNull { it.id == nodeId }?.name
                if (name.isNullOrBlank() || tag.isBlank()) null else (tag to name)
            }.toMap()

            val runConfig = config.copy(
                log = log,
                experimental = experimental,
                inbounds = inbounds,
                dns = dns,
                route = route,
                outbounds = outboundsContext.outbounds
            )

            val validation = singBoxCore.validateConfig(stripInternalMetadata(runConfig))
            validation.exceptionOrNull()?.let { e ->
                val msg = e.cause?.message ?: e.message ?: "unknown error"
                Log.e(ConfigRepository.TAG, "Config pre-validation failed: $msg", e)
                throw Exception("Config validation failed: $msg", e)
            }
            val allTags = runConfig.outbounds?.map { it.tag }?.toSet() ?: emptySet()
            val candidateTag = activeNodeId?.let { outboundsContext.nodeTagMap[it] }
                ?: activeNode?.name

            val resolvedTag = when {
                candidateTag == null -> {
                    val proxySelector = runConfig.outbounds?.find { it.tag == "PROXY" }
                    proxySelector?.default ?: proxySelector?.outbounds?.firstOrNull()
                }
                allTags.contains(candidateTag) -> candidateTag
                else -> {
                    Log.e(ConfigRepository.TAG, "Selected node tag '$candidateTag' not found in runtime outbounds, aborting switch")
                    throw IllegalStateException("Selected node is not available in runtime outbounds: $candidateTag")
                }
            }
            val configFile = File(context.filesDir, "running_config.json")
            ConfigRepository.writeTextFileAtomically(configFile, gson.toJson(stripInternalMetadata(runConfig)))

            ConfigRepository.ConfigGenerationResult(configFile.absolutePath, resolvedTag, allTags)
        } catch (e: Exception) {
            Log.e(ConfigRepository.TAG, "Failed to generate config file", e)
            null
        }
    }

    protected override fun buildOutboundForRuntime(outbound: Outbound): Outbound? =
        OutboundFixer.buildForRuntime(context, outbound)

    protected override fun loadConfigWithLegacyEchRepair(profile: ProfileUi?, profileId: String): SingBoxConfig? {
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
            Log.i(ConfigRepository.TAG, "Repaired legacy ECH subscription config for profile: ${profile?.name ?: profileId}")
        }.onFailure { e ->
            Log.w(ConfigRepository.TAG, "Failed to persist repaired ECH subscription config for profile: $profileId", e)
        }
        return deduplicatedConfig
    }

    protected override fun stripInternalMetadata(config: SingBoxConfig): SingBoxConfig {
        return config.copy(
            outbounds = config.outbounds?.map { stripInternalMetadata(it) },
            proxies = config.proxies?.map { stripInternalMetadata(it) }
        )
    }

    protected override fun stripInternalMetadata(outbound: Outbound): Outbound {
        val tls = outbound.tls ?: return outbound
        val ech = tls.ech ?: return outbound
        return outbound.copy(tls = tls.copy(ech = ech.copy(dnsServer = null)))
    }

    protected override suspend fun preResolveDomainsForProfile(
        profileId: String,
        config: SingBoxConfig,
        dnsServer: String?
    ) {
        val outbounds = config.outbounds ?: return
        val domains = outbounds.mapNotNull { outbound ->
            val server = outbound.server ?: return@mapNotNull null
            if (DnsResolver.isIpAddress(server)) return@mapNotNull null
            server
        }.distinct()

        if (domains.isEmpty()) {
            Log.d(ConfigRepository.TAG, "No domains to pre-resolve for profile $profileId")
            return
        }

        Log.d(ConfigRepository.TAG, "Pre-resolving ${domains.size} domains for profile $profileId")

        val results = dnsResolver.resolveBatch(
            domains = domains,
            dohServer = dnsServer ?: DnsResolver.DOH_CLOUDFLARE
        )

        val savedCount = dnsResolveStore.saveBatch(profileId, results)
        Log.d(ConfigRepository.TAG, "Pre-resolved and saved $savedCount domains for profile $profileId")
    }

    protected override fun applyDnsResolveToOutbound(profileId: String, outbound: Outbound): Outbound {
        val server = outbound.server ?: return outbound
        if (DnsResolver.isIpAddress(server)) return outbound

        val resolvedIp = dnsResolveStore.getIp(profileId, server)
        return if (resolvedIp != null) {
            Log.d(ConfigRepository.TAG, "Applying DNS resolve: $server -> $resolvedIp")
            outbound.copy(server = resolvedIp)
        } else {
            outbound
        }
    }

    protected override fun detectValidRuleSetFileFormat(file: File, tag: String): String? {
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

    protected override fun readRuleSetSample(file: File): ByteArray {
        return file.inputStream().use { input ->
            val buffer = ByteArray(ConfigRepository.RULE_SET_SNIFF_BYTES)
            val read = input.read(buffer)
            if (read > 0) buffer.copyOf(read) else ByteArray(0)
        }
    }

    protected override fun isLikelyTextRuleSet(sample: ByteArray): Boolean {
        if (sample.any { it == 0.toByte() }) return false
        val printableBytes = sample.count { byte ->
            val code = byte.toInt() and 0xff
            code == 9 || code == 10 || code == 13 || code in 32..126
        }
        return printableBytes >= sample.size * 3 / 4
    }

    protected override fun readRuleSetInspectionText(file: File, sample: ByteArray): String {
        return if (file.length() <= ConfigRepository.RULE_SET_TEXT_PARSE_LIMIT_BYTES) {
            file.readText()
        } else {
            sample.toString(Charsets.UTF_8)
        }
    }

    protected override fun validateBinaryRuleSet(file: File, tag: String): Boolean {
        val sample = readRuleSetSample(file)
        if (file.length() >= ConfigRepository.RULE_SET_MIN_SIZE_BYTES && hasRuleSetBinaryMagic(sample)) {
            return true
        }
        Log.w(ConfigRepository.TAG, "Rule set binary file is not a valid .srs file, ignoring: $tag (${file.length()} bytes)")
        return false
    }

    protected override fun hasRuleSetBinaryMagic(sample: ByteArray): Boolean {
        if (sample.size < ConfigRepository.RULE_SET_BINARY_MAGIC.length) return false
        return sample[0] == 'S'.code.toByte() &&
            sample[1] == 'R'.code.toByte() &&
            sample[2] == 'S'.code.toByte()
    }

    protected override fun validateTextRuleSet(file: File, tag: String, inspectionText: String): Boolean {
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

    protected override fun rejectUnrecognizedRuleSetText(file: File, tag: String, trimmed: String): Boolean {
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

    protected override fun isValidRuleSetJson(content: String): Boolean {
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

    protected override fun isValidRuleSetStructuredText(content: String): Boolean {
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

    protected override fun buildCustomRuleSets(settings: AppSettings): List<RuleSetConfig> {
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

    internal override fun getAppliedRemoteRuleSets(settings: AppSettings): List<RuleSet> {
        val validTags = buildCustomRuleSets(settings)
            .mapNotNull { it.tag }
            .toSet()
        return ConfigRepository.filterAppliedRemoteRuleSets(settings.ruleSets, validTags)
    }
}

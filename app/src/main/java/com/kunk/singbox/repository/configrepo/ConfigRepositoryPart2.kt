@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.repository

import android.util.Log
import com.kunk.singbox.R
import com.kunk.singbox.database.entity.ActiveStateEntity
import com.kunk.singbox.database.entity.NodeLatencyEntity
import com.kunk.singbox.database.entity.ProfileEntity
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.*
import com.kunk.singbox.utils.parser.SubscriptionManager
import java.io.File
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request

internal suspend fun ConfigRepository.loadSavedProfiles() {
    try {
        val startTime = System.currentTimeMillis()
        val profileEntities = profileDao.getAll()
        val activeState = activeStateDao.get()
        val latencyEntities = nodeLatencyDao.getAll()

        if (profileEntities.isNotEmpty()) {
            val profiles = profileEntities.map { it.toUiModel().copy(updateStatus = UpdateStatus.Idle) }
            val (restoredProfileId, restoredNodeId) = resolveRestoredProfileSelection(
                availableProfileIds = profiles.mapTo(mutableSetOf()) { it.id },
                databaseProfileId = activeState?.activeProfileId,
                databaseNodeId = activeState?.activeNodeId,
                persistedProfileId = VpnStateStore.getSelectedProfileId(),
                persistedNodeId = VpnStateStore.getSelectedNodeId()
            )
            _profiles.value = profiles
            _activeProfileId.value = restoredProfileId
            savedNodeLatencies.clear()
            latencyEntities.forEach { entity ->
                savedNodeLatencies[entity.nodeId] = SavedNodeLatency(entity.latencyMs, entity.testedAt)
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(ConfigRepository.TAG, "Loaded ${profiles.size} profiles from Room in ${elapsed}ms")
            loadActiveProfileNodes(restoredProfileId, restoredNodeId)
            cleanupLegacyProfileFiles()
            return
        }
        val savedData: SavedProfilesData? = if (profilesFileJson.exists()) {
            Log.i(ConfigRepository.TAG, "Migrating profiles from JSON to Room...")
            val json = profilesFileJson.readText()
            gson.fromJson<SavedProfilesData>(json, ConfigRepository.TYPE_SAVED_PROFILES_DATA)
        } else {
            null
        }

        if (savedData != null) {
            val profiles = savedData.profiles.map { it.copy(updateStatus = UpdateStatus.Idle) }
            val (restoredProfileId, restoredNodeId) = resolveRestoredProfileSelection(
                availableProfileIds = profiles.mapTo(mutableSetOf()) { it.id },
                databaseProfileId = savedData.activeProfileId,
                databaseNodeId = savedData.activeNodeId,
                persistedProfileId = VpnStateStore.getSelectedProfileId(),
                persistedNodeId = VpnStateStore.getSelectedNodeId()
            )
            _profiles.value = profiles
            _activeProfileId.value = restoredProfileId

            savedNodeLatencies.clear()
            savedNodeLatencies.putAll(
                savedData.nodeLatencies.mapValues { (_, latency) -> SavedNodeLatency(latency, testedAt = 0L) }
            )
            val entities = profiles.mapIndexed { index, profile ->
                ProfileEntity.fromUiModel(profile, sortOrder = index)
            }
            profileDao.insertAll(entities)
            if (savedData.activeProfileId != null || savedData.activeNodeId != null) {
                activeStateDao.save(ActiveStateEntity(
                    id = 1,
                    activeProfileId = savedData.activeProfileId,
                    activeNodeId = savedData.activeNodeId
                ))
            }
            val latencies = savedData.nodeLatencies.map { (nodeId, latency) ->
                NodeLatencyEntity(nodeId = nodeId, latencyMs = latency, testedAt = 0L)
            }
            if (latencies.isNotEmpty()) {
                scope.launch { nodeLatencyDao.insertAll(latencies) }
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.i(ConfigRepository.TAG, "Migrated ${profiles.size} profiles to Room in ${elapsed}ms")
            loadActiveProfileNodes(restoredProfileId, restoredNodeId)
            cleanupLegacyProfileFiles()
        }
    } catch (e: Exception) {
        Log.e(ConfigRepository.TAG, "Failed to load saved profiles", e)
    }
}

internal suspend fun ConfigRepository.loadActiveProfileNodes(activeProfileId: String?, activeNodeId: String?) {
    if (activeProfileId == null) return
    val configFile = File(configDir, "$activeProfileId.json")
    if (!configFile.exists()) return

    try {
        val configJson = configFile.readText()
        val config = deduplicateTags(gson.fromJson(configJson, SingBoxConfig::class.java))
        val nodes = extractNodesFromConfig(config, activeProfileId)
        val nodesWithLatency = nodes.map { node ->
            val latency = savedLatencyMs(node.id)
            if (latency != null) node.copy(latencyMs = latency) else node
        }
        profileNodes[activeProfileId] = nodesWithLatency
        cacheConfig(activeProfileId, config)
        if (activeProfileId == _activeProfileId.value) {
            applyActiveProfileNodes(activeProfileId, nodesWithLatency, activeNodeId)
        }
        if (allNodesUiActiveCount.get() > 0) {
            updateAllNodesAndGroups()
        }
    } catch (e: Exception) {
        Log.e(ConfigRepository.TAG, "Failed to load config for profile: $activeProfileId", e)
    }
}

internal fun ConfigRepository.cleanupLegacyProfileFiles() {
    scope.launch {
        try {
            if (profilesFileJson.exists()) {
                profilesFileJson.delete()
                Log.i(ConfigRepository.TAG, "Deleted legacy JSON profiles file")
            }
        } catch (e: Exception) {
            Log.w(ConfigRepository.TAG, "Failed to cleanup legacy profile files", e)
        }
    }
}

internal fun ConfigRepository.parseTrafficString(value: String): Long {
    val trimmed = value.trim().uppercase()
    val match = ConfigRepository.REGEX_TRAFFIC.find(trimmed) ?: return 0L

    val (numStr, unit) = match.destructured
    val num = numStr.toDoubleOrNull() ?: return 0L

    val multiplier = when (unit) {
        "K" -> 1024L
        "M" -> 1024L * 1024
        "G" -> 1024L * 1024 * 1024
        "T" -> 1024L * 1024 * 1024 * 1024
        "P" -> 1024L * 1024 * 1024 * 1024 * 1024
        else -> 1L
    }

    return (num * multiplier).toLong()
}

internal fun ConfigRepository.parseDateString(value: String): Long {
    return try {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        (sdf.parse(value.trim())?.time ?: 0L) / 1000 // Convert to seconds
    } catch (e: Exception) {
        0L
    }
}

internal fun ConfigRepository.parseExpireValue(raw: String): Long {
    val normalized = raw.trim().trim('"', '\'')
    if (normalized.isBlank()) return 0L
    val lower = normalized.lowercase()
    if (lower.contains("never") || lower.contains("permanent") || lower.contains("forever") || lower.contains("unlimited")) {
        return -1L
    }
    return if (normalized.contains("-")) {
        parseDateString(normalized)
    } else {
        normalized.toLongOrNull() ?: 0L
    }
}

internal fun ConfigRepository.parseSubscriptionUserInfo(header: String?, bodyDecoded: String?): ConfigRepository.SubscriptionUserInfo? {
    var upload = 0L
    var download = 0L
    var total = 0L
    var expire = 0L
    var found = false
    var totalSpecified = false

    fun isUnlimitedValue(raw: String): Boolean {
        val normalized = raw.trim().lowercase()
        return normalized == "unlimited" || normalized == "infinite" || normalized == "infinity" || normalized == "inf" || normalized == "INF"
    }

    fun parseTrafficValue(raw: String): Long {
        val normalized = raw.trim().trim('"', '\'')
        return normalized.toLongOrNull() ?: parseTrafficString(normalized)
    }

    fun applyKeyValue(key: String, rawValue: String) {
        when (key.lowercase()) {
            "upload" -> {
                upload = parseTrafficValue(rawValue)
                found = true
            }
            "download" -> {
                download = parseTrafficValue(rawValue)
                found = true
            }
            "total" -> {
                totalSpecified = true
                total = if (isUnlimitedValue(rawValue)) -1L else parseTrafficValue(rawValue)
                found = true
            }
            "expire" -> {
                expire = parseExpireValue(rawValue)
                found = true
            }
        }
    }

    fun parseKeyValuePairs(text: String) {
        ConfigRepository.REGEX_KV_PAIRS.findAll(text).forEach { match ->
            applyKeyValue(match.groupValues[1], match.groupValues[2])
        }
    }

    fun parseHeaderLike(text: String) {
        text.split(",", ";").forEach { part ->
            val kv = part.trim().split("=", ":", limit = 2)
            if (kv.size == 2) {
                applyKeyValue(kv[0].trim(), kv[1].trim())
            }
        }
    }
    if (!header.isNullOrBlank()) {
        try {
            parseHeaderLike(header)
        } catch (e: Exception) {
            Log.w(ConfigRepository.TAG, "Failed to parse Subscription-Userinfo header: $header", e)
        }
    }
    if (bodyDecoded != null && (!found || total == 0L)) {
        try {
            val userInfoIndex = bodyDecoded.indexOf("subscription-userinfo", ignoreCase = true)
            val userInfoAltIndex = if (userInfoIndex >= 0) userInfoIndex else bodyDecoded.indexOf("subscription_userinfo", ignoreCase = true)
            if (userInfoAltIndex >= 0) {
                val endIndex = (userInfoAltIndex + 800).coerceAtMost(bodyDecoded.length)
                val snippet = bodyDecoded.substring(userInfoAltIndex, endIndex)
                val inlineMatch = ConfigRepository.REGEX_SUBSCRIPTION_USERINFO.find(snippet)
                if (inlineMatch != null) {
                    parseHeaderLike(inlineMatch.groupValues[1])
                }
                parseKeyValuePairs(snippet)
            }

            val firstLine = bodyDecoded.lines().firstOrNull()?.trim()
            if (firstLine != null && (firstLine.startsWith("STATUS=") || firstLine.contains("TOT:") || firstLine.contains("Expires:"))) {
                val totalMatch = ConfigRepository.REGEX_TOTAL.find(firstLine)
                if (totalMatch != null) {
                    totalSpecified = true
                    total = parseTrafficString(totalMatch.groupValues[1])
                    found = true
                }
                val expireMatch = ConfigRepository.REGEX_EXPIRE_DATE.find(firstLine)
                if (expireMatch != null) {
                    expire = parseDateString(expireMatch.groupValues[1])
                    found = true
                }
                var usedAccumulator = 0L
                val parts = firstLine.substringAfter("STATUS=").split(",")
                parts.forEach { part ->
                    if (part.contains("TOT:")) return@forEach
                    if (part.contains("Expires:")) return@forEach
                    val match = ConfigRepository.REGEX_TRAFFIC_VALUE.find(part)
                    if (match != null) {
                        usedAccumulator += parseTrafficString(match.groupValues[1])
                        found = true
                    }
                }

                if (usedAccumulator > 0) {
                    download = usedAccumulator
                    upload = 0
                }
            }
        } catch (e: Exception) {
            Log.w(ConfigRepository.TAG, "Failed to parse info from body: ${bodyDecoded.take(100)}", e)
        }
    }

    if (!found) return null
    if (totalSpecified && total <= 0L) {
        total = -1L
    }
    return ConfigRepository.SubscriptionUserInfo(upload, download, total, expire)
}

internal fun ConfigRepository.parseUserInfoFromOutbounds(outbounds: List<Outbound>?): ConfigRepository.SubscriptionUserInfo? {
    if (outbounds.isNullOrEmpty()) return null
    var remainingBytes: Long? = null
    var expireValue: Long? = null

    outbounds.forEach { outbound ->
        val tag = outbound.tag
        if (remainingBytes == null) {
            val match = ConfigRepository.REGEX_REMAINING.find(tag)
            if (match != null) {
                remainingBytes = parseTrafficString(match.groupValues[2])
            }
        }
        if (expireValue == null) {
            val match = ConfigRepository.REGEX_EXPIRE.find(tag)
            if (match != null) {
                expireValue = parseExpireValue(match.groupValues[2])
            }
        }
    }

    if (remainingBytes == null && expireValue == null) return null
    return ConfigRepository.SubscriptionUserInfo(
        upload = 0,
        download = remainingBytes ?: 0,
        total = if (remainingBytes != null) -2L else 0L,
        expire = expireValue ?: 0L
    )
}

internal fun ConfigRepository.mergeUserInfo(primary: ConfigRepository.SubscriptionUserInfo?, fallback: ConfigRepository.SubscriptionUserInfo?): ConfigRepository.SubscriptionUserInfo? {
    if (primary == null) return fallback
    if (fallback == null) return primary
    return ConfigRepository.SubscriptionUserInfo(
        upload = if (primary.upload > 0) primary.upload else fallback.upload,
        download = if (primary.download > 0) primary.download else fallback.download,
        total = if (primary.total != 0L) primary.total else fallback.total,
        expire = if (primary.expire != 0L) primary.expire else fallback.expire
    )
}

internal fun ConfigRepository.logHtmlSubscriptionPage(userAgent: String, responseBody: String) {
    val extractedUrl = ConfigRepository.extractSubscriptionUrlFromHtml(responseBody)
    if (!extractedUrl.isNullOrBlank()) {
        Log.i(
            ConfigRepository.TAG,
            "Subscription endpoint returned HTML info page with UA '$userAgent', " +
                "embedded subscription URL: $extractedUrl"
        )
    } else {
        Log.w(ConfigRepository.TAG, "Subscription endpoint returned HTML info page with UA '$userAgent'")
    }
}

@Suppress("ReturnCount")
internal fun ConfigRepository.parseSubscriptionResponse(
    userAgent: String,
    contentType: String?,
    responseBody: String,
    subscriptionUserInfoHeader: String?
): ConfigRepositorySubscriptionAttemptResult {
    val isHtmlInfoPage = ConfigRepository.looksLikeHtmlSubscriptionPage(contentType, responseBody)
    if (ConfigRepository.shouldStopSubscriptionFallback(looksLikeHtmlInfoPage = isHtmlInfoPage)) {
        logHtmlSubscriptionPage(userAgent, responseBody)
        return ConfigRepositorySubscriptionAttemptResult(shouldStopFallback = true)
    }

    ConfigRepository.findUnsupportedAndroidCapabilityInJson(responseBody)?.let { message ->
        val error = IllegalArgumentException(message)
        Log.w(ConfigRepository.TAG, message)
        return ConfigRepositorySubscriptionAttemptResult(
            shouldStopFallback = true,
            terminalError = error
        )
    }

    val parsedConfig = subscriptionManager.parse(responseBody)
    parsedConfig?.let(ConfigRepository::findUnsupportedAndroidCapability)?.let { message ->
        val error = IllegalArgumentException(message)
        Log.w(ConfigRepository.TAG, message)
        return ConfigRepositorySubscriptionAttemptResult(
            shouldStopFallback = true,
            terminalError = error
        )
    }
    val config = parsedConfig?.let(::deduplicateTags)
    if (config == null || config.outbounds.isNullOrEmpty()) {
        Log.w(ConfigRepository.TAG, "Failed to parse subscription response with UA '$userAgent'")
        return ConfigRepositorySubscriptionAttemptResult()
    }

    val headerUserInfo = parseSubscriptionUserInfo(subscriptionUserInfoHeader, responseBody)
    val outboundUserInfo = parseUserInfoFromOutbounds(config.outbounds)
    val userInfo = mergeUserInfo(headerUserInfo, outboundUserInfo)
    return ConfigRepositorySubscriptionAttemptResult(fetchResult = ConfigRepositoryFetchResult(config, userInfo))
}

internal fun ConfigRepository.buildSubscriptionRequest(url: String, userAgent: String): Request {
    return Request.Builder()
        .url(url)
        .header("User-Agent", userAgent)
        .header("Accept", "application/yaml,text/yaml,text/plain,application/json,*/*")
        .build()
}

internal fun ConfigRepository.logSubscriptionAttempt(
    level: Int,
    message: String,
    context: ConfigRepositorySubscriptionAttemptContext,
    costMs: Long,
    extra: String? = null) {
    val logMessage = buildString {
        append(message)
        append(": host=")
        append(context.host)
        append(", ua='")
        append(context.userAgent)
        append("', cached=")
        append(context.isRemembered)
        append(", cost=")
        append(costMs)
        append("ms")
        if (!extra.isNullOrBlank()) {
            append(", ")
            append(extra)
        }
    }
    when (level) {
        Log.INFO -> Log.i(ConfigRepository.TAG, logMessage)
        Log.WARN -> Log.w(ConfigRepository.TAG, logMessage)
        else -> Log.d(ConfigRepository.TAG, logMessage)
    }
}

internal fun ConfigRepository.logSubscriptionParseResult(
    attemptResult: ConfigRepositorySubscriptionAttemptResult,
    contentType: String?,
    context: ConfigRepositorySubscriptionAttemptContext,
    costMs: Long
) {
    val message = when {
        attemptResult.fetchResult != null -> "Subscription request succeeded"
        attemptResult.shouldStopFallback -> "Subscription response stopped further fallback"
        else -> "Subscription response unusable"
    }
    val level = if (attemptResult.fetchResult != null) Log.INFO else Log.WARN
    logSubscriptionAttempt(
        level = level,
        message = message,
        context = context,
        costMs = costMs,
        extra = "contentType='$contentType'"
    )
}

internal fun ConfigRepository.logSubscriptionFallbackStopped(
    context: ConfigRepositorySubscriptionAttemptContext,
    costMs: Long,
    reason: String
) {
    logSubscriptionAttempt(
        level = Log.WARN,
        message = "Stopping remaining User-Agent fallbacks",
        context = context,
        costMs = costMs,
        extra = reason
    )
}

internal fun ConfigRepository.handleUnsuccessfulSubscriptionResponse(
    responseCode: Int,
    responseMessage: String,
    context: ConfigRepositorySubscriptionAttemptContext,
    costMs: Long
): ConfigRepositorySubscriptionAttemptResult {
    val shouldStopFallback = ConfigRepository.shouldStopSubscriptionFallback(httpStatusCode = responseCode)
    val error = Exception(
        this.context.getString(R.string.subscription_import_http_error, responseCode, responseMessage)
    )
    logSubscriptionAttempt(
        level = Log.WARN,
        message = if (shouldStopFallback) {
            "Subscription request hit terminal response"
        } else {
            "Subscription request failed"
        },
        context = context,
        costMs = costMs,
        extra = "code=$responseCode"
    )
    if (!shouldStopFallback) {
        throw error
    }
    return ConfigRepositorySubscriptionAttemptResult(shouldStopFallback = true, terminalError = error)
}

internal fun ConfigRepository.executeSubscriptionAttempt(
    client: OkHttpClient,
    url: String,
    context: ConfigRepositorySubscriptionAttemptContext,
    onProgress: (String) -> Unit,
    onStageChanged: (SubscriptionUpdateStage) -> Unit
): ConfigRepositorySubscriptionAttemptResult {
    val startedAt = System.currentTimeMillis()
    val request = buildSubscriptionRequest(url, context.userAgent)

    client.newCall(request).execute().use { response ->
        val costMs = System.currentTimeMillis() - startedAt
        if (!response.isSuccessful) {
            return handleUnsuccessfulSubscriptionResponse(
                responseCode = response.code,
                responseMessage = response.message,
                context = context,
                costMs = costMs
            )
        }

        val responseBody = ConfigRepository.readSubscriptionResponseBody(response.body)
        if (responseBody.isNullOrBlank()) {
            logSubscriptionAttempt(
                level = Log.WARN,
                message = "Empty subscription response",
                context = context,
                costMs = costMs
            )
            throw IllegalStateException(
                this@executeSubscriptionAttempt.context.getString(R.string.subscription_import_empty_response)
            )
        }

        onStageChanged(SubscriptionUpdateStage.Parsing)
        onProgress(this@executeSubscriptionAttempt.context.getString(R.string.subscription_import_parsing))

        val contentType = response.header("Content-Type")
        val attemptResult = parseSubscriptionResponse(
            userAgent = context.userAgent,
            contentType = contentType,
            responseBody = responseBody,
            subscriptionUserInfoHeader = response.header("Subscription-Userinfo")
        )
        val profileTitle = response.header("profile-title")
        val contentDisposition = response.header("Content-Disposition")
        val subName = SubscriptionManager.parseSubscriptionNameFromHeader(profileTitle, contentDisposition)

        val finalAttemptResult = if (subName != null && attemptResult.fetchResult != null) {
            attemptResult.copy(
                fetchResult = attemptResult.fetchResult.copy(subscriptionName = subName)
            )
        } else {
            attemptResult
        }
        logSubscriptionParseResult(finalAttemptResult, contentType, context, costMs)
        return finalAttemptResult
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "LoopWithTooManyJumpStatements")
internal fun ConfigRepository.fetchAndParseSubscription(
    url: String,
    onProgress: (String) -> Unit = {},
    onStageChanged: (SubscriptionUpdateStage) -> Unit = {}): ConfigRepositoryFetchResult? {
    var lastError: Exception? = null
    val host = ConfigRepository.extractSubscriptionHost(url) ?: "unknown"
    val rememberedUserAgent = getRememberedSubscriptionUserAgent(url)
    val userAgents = buildSubscriptionUserAgents(url)
    val totalBudgetSeconds = ConfigRepository.resolveSubscriptionUpdateBudgetSeconds(
        (cachedSettings ?: AppSettings()).subscriptionUpdateTimeout
    )
    val startedAt = System.currentTimeMillis()

    for ((index, userAgent) in userAgents.withIndex()) {
        val elapsedMs = System.currentTimeMillis() - startedAt
        val timeoutBudget = ConfigRepository.resolveSubscriptionAttemptTimeoutBudget(totalBudgetSeconds, elapsedMs)
        if (timeoutBudget == null) {
            Log.w(
                ConfigRepository.TAG,
                "Subscription request budget exhausted: host=$host, " +
                    "attempts=$index/${userAgents.size}, budget=${totalBudgetSeconds}s"
            )
            break
        }

        try {
            onStageChanged(SubscriptionUpdateStage.Requesting)
            onProgress(
                context.getString(
                    R.string.subscription_import_requesting,
                    index + 1,
                    userAgents.size
                )
            )
            val attemptContext = ConfigRepositorySubscriptionAttemptContext(
                host = host,
                userAgent = userAgent,
                isRemembered = rememberedUserAgent.equals(userAgent, ignoreCase = true)
            )
            val attemptResult = executeSubscriptionAttempt(
                client = getSubscriptionProxyClient(timeoutBudget) ?: getSubscriptionClient(timeoutBudget),
                url = url,
                context = attemptContext,
                onProgress = onProgress,
                onStageChanged = onStageChanged
            )
            val fetchResult = attemptResult.fetchResult
            if (fetchResult != null) {
                rememberSuccessfulSubscriptionUserAgent(url, userAgent)
                clearSubscriptionUserAgentFailure(host, userAgent)
                return fetchResult
            }
            if (attemptResult.shouldStopFallback) {
                lastError = attemptResult.terminalError
                logSubscriptionFallbackStopped(
                    context = attemptContext,
                    costMs = System.currentTimeMillis() - startedAt,
                    reason = attemptResult.terminalError?.message ?: "html_info_page"
                )
                break
            }
        } catch (e: Exception) {
            lastError = e
            if (ConfigRepository.shouldRecordSubscriptionNetworkFailure(e)) {
                recordSubscriptionUserAgentFailure(host, userAgent)
            }
            Log.w(
                ConfigRepository.TAG,
                "Subscription fetch error: host=$host, ua='$userAgent', " +
                    "cached=${rememberedUserAgent.equals(userAgent, ignoreCase = true)}, error=${e.message}"
            )
            if (index == userAgents.lastIndex) {
                throw e
            }
        }
    }

    lastError?.let {
        Log.e(ConfigRepository.TAG, "All User-Agent attempts failed", it)
        throw it
    }
    return null
}
@Suppress("LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod")
internal suspend fun ConfigRepository.importFromSubscription(
    name: String,
    url: String,
    autoUpdateInterval: Int = 0,
    dnsPreResolve: Boolean = false,
    dnsServer: String? = null,
    dnsOverride: String? = null,
    onProgress: (String) -> Unit = {}): Result<ProfileUi> = withContext(Dispatchers.IO) {
    var profileId: String? = null
    val normalizedAutoUpdateInterval =
        com.kunk.singbox.service.SubscriptionAutoUpdateWorker.normalizeIntervalMinutes(autoUpdateInterval)
    try {
        onProgress(context.getString(R.string.subscription_import_fetching))
        val fetchResult = try {
            fetchAndParseSubscription(url, onProgress)
        } catch (e: Exception) {
            Log.e(ConfigRepository.TAG, "Subscription fetch failed", e)
            return@withContext Result.failure(e)
        }

        if (fetchResult == null) {
            return@withContext Result.failure(Exception(context.getString(R.string.profiles_parse_failed)))
        }

        val config = fetchResult.config
        val userInfo = fetchResult.userInfo

        val defaultQrName = context.getString(R.string.profiles_qrcode_subscription)
        val finalName = resolveSubscriptionProfileName(name, defaultQrName, fetchResult.subscriptionName)

        onProgress(context.getString(R.string.profiles_extracting_nodes, 0, 0))

        profileId = UUID.randomUUID().toString()
        val deduplicatedConfig = deduplicateTags(config)
        val nodes = extractNodesFromConfig(deduplicatedConfig, profileId, onProgress)

        if (nodes.isEmpty()) {
            return@withContext Result.failure(Exception(context.getString(R.string.nodes_no_valid_found)))
        }
        writeConfigFileOrThrow(profileId, deduplicatedConfig)
        val profile = ProfileUi(
            id = profileId,
            name = finalName,
            type = ProfileType.Subscription,
            url = url,
            lastUpdated = System.currentTimeMillis(),
            enabled = true,
            autoUpdateInterval = normalizedAutoUpdateInterval,
            updateStatus = UpdateStatus.Idle,
            expireDate = userInfo?.expire ?: 0,
            totalTraffic = userInfo?.total ?: 0,
            usedTraffic = (userInfo?.upload ?: 0) + (userInfo?.download ?: 0),
            dnsPreResolve = dnsPreResolve,
            dnsServer = dnsServer,
            dnsOverride = dnsOverride
        )
        cacheConfig(profileId, deduplicatedConfig)
        profileNodes[profileId] = nodes
        updateAllNodesAndGroups()
        _profiles.update { it + profile }
        saveProfiles()
        if (_activeProfileId.value == null) {
            setActiveProfile(profileId)
        }
        if (normalizedAutoUpdateInterval > 0) {
            com.kunk.singbox.service.SubscriptionAutoUpdateWorker.schedule(
                context,
                profileId,
                normalizedAutoUpdateInterval
            )
        }
        if (dnsPreResolve) {
            preResolveDomainsForProfileBestEffort(profileId, deduplicatedConfig, dnsServer)
        }
        onProgress(context.getString(R.string.profiles_import_success, nodes.size.toString()))

        Result.success(profile)
    } catch (e: Exception) {
        profileId?.let { rollbackTransientProfileFile(it) }
        Log.e(ConfigRepository.TAG, "Subscription import failed", e)
        val msg = when (e) {
            is java.net.SocketTimeoutException -> context.getString(R.string.subscription_import_timeout)
            is java.net.UnknownHostException -> context.getString(R.string.subscription_import_dns_failed)
            is javax.net.ssl.SSLHandshakeException -> context.getString(R.string.subscription_import_ssl_failed)
            else -> e.message ?: context.getString(R.string.subscription_import_failed_generic)
        }
        Result.failure(Exception(msg))
    }
}

internal suspend fun ConfigRepository.loadSelectedCustomNodes(selectedNodeIds: List<String>): List<NodeUi> {
    val allCurrentNodes = _allNodes.value.takeIf { it.isNotEmpty() } ?: loadAllNodesSnapshot()
    val nodeById = allCurrentNodes.associateBy { it.id }
    return selectedNodeIds.mapNotNull { nodeById[it] }
}

internal fun ConfigRepository.collectCustomOutbounds(targetNodes: List<NodeUi>): List<com.kunk.singbox.model.Outbound> {
    return targetNodes.mapNotNull { node ->
        val sourceConfig = getConfig(node.sourceProfileId) ?: return@mapNotNull null
        sourceConfig.outbounds?.find { it.tag == node.name }
            ?: sourceConfig.outbounds?.find { it.tag.equals(node.name, ignoreCase = true) }
    }
}

internal suspend fun ConfigRepository.resolveCustomProfileOutbounds(
    selectedNodeIds: List<String>,
    additionalOutbounds: List<Outbound>
): Result<List<Outbound>> {
    val targetNodes = if (selectedNodeIds.isEmpty()) {
        emptyList()
    } else {
        loadSelectedCustomNodes(selectedNodeIds)
    }
    val copiedOutbounds = collectCustomOutbounds(targetNodes)
    val outbounds = combineCustomProfileOutbounds(copiedOutbounds, additionalOutbounds)
    return if (outbounds.isEmpty()) {
        val message = if (selectedNodeIds.isEmpty()) {
            context.getString(R.string.custom_profile_nodes_required)
        } else {
            context.getString(R.string.custom_profile_extract_failed)
        }
        Result.failure(Exception(message))
    } else {
        Result.success(outbounds)
    }
}

internal fun ConfigRepository.buildCustomProfile(profileId: String, name: String): ProfileUi {
    return ProfileUi(
        id = profileId,
        name = name,
        type = ProfileType.Custom,
        url = null,
        lastUpdated = System.currentTimeMillis(),
        enabled = true,
        updateStatus = UpdateStatus.Idle
    )
}

internal suspend fun ConfigRepository.createCustomProfile(
    name: String,
    selectedNodeIds: List<String>,
    additionalOutbounds: List<Outbound> = emptyList()
): Result<ProfileUi> = withContext(Dispatchers.IO) {
    var profileId: String? = null
    try {
        val outbounds = resolveCustomProfileOutbounds(selectedNodeIds, additionalOutbounds).getOrElse { error ->
            return@withContext Result.failure(error)
        }

        val newConfig = com.kunk.singbox.model.SingBoxConfig(outbounds = outbounds)
        profileId = UUID.randomUUID().toString()
        val deduplicatedConfig = deduplicateTags(newConfig)
        val nodes = extractNodesFromConfig(deduplicatedConfig, profileId, {})
        if (nodes.isEmpty()) {
            return@withContext Result.failure(
                Exception(context.getString(R.string.nodes_no_valid_found))
            )
        }

        writeConfigFileOrThrow(profileId, deduplicatedConfig)

        val profile = buildCustomProfile(profileId, name)
        cacheConfig(profileId, deduplicatedConfig)
        profileNodes[profileId] = nodes
        updateAllNodesAndGroups()
        _profiles.update { it + profile }
        saveProfiles()

        if (_activeProfileId.value == null) {
            setActiveProfile(profileId)
        }

        Result.success(profile)
    } catch (e: Exception) {
        profileId?.let { rollbackTransientProfileFile(it) }
        Log.e(ConfigRepository.TAG, "Failed to create custom profile", e)
        Result.failure(e)
    }
}

internal fun ConfigRepository.resolveSubscriptionProfileName(
    currentName: String,
    defaultQrName: String,
    subscriptionName: String?
): String {
    if (subscriptionName.isNullOrBlank()) {
        return currentName
    }
    return if (isDefaultSubscriptionProfileName(currentName, defaultQrName)) {
        subscriptionName
    } else {
        currentName
    }
}

internal fun ConfigRepository.isDefaultSubscriptionProfileName(currentName: String, defaultQrName: String): Boolean {
    return currentName.isBlank() ||
        currentName == defaultQrName ||
        currentName == "扫码订阅" ||
        currentName == "QR Code Subscription"
}

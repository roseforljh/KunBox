package com.kunk.singbox.repository

import android.annotation.TargetApi
import android.os.Build
import android.util.Log
import com.kunk.singbox.model.*
import com.kunk.singbox.database.entity.ProfileEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.ResponseBody

@Suppress("TooManyFunctions")
abstract class ConfigRepositoryCompanionPart1 : ConfigRepositoryCompanionBase() {
    override fun stableNodeId(profileId: String, outboundTag: String): String {
        val key = "$profileId|$outboundTag"
        synchronized(nodeIdCache) {
            nodeIdCache[key]?.let { return it }
            val id = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
            nodeIdCache[key] = id
            return id
        }
    }

    internal override fun buildRouteGroupAutoTag(groupTag: String): String {
        return "$groupTag$ROUTE_GROUP_AUTO_TAG_SUFFIX"
    }

    internal override fun buildProfileRouteGroupOutboundsForTest(
        groupTag: String,
        nodeTags: List<String>
    ): List<Outbound> {
        return buildProfileRouteGroupOutbounds(
            groupTag = groupTag,
            nodeTags = nodeTags
        )
    }

    internal override fun applySelectorSafeOutboundsForTest(outbounds: List<Outbound>): List<Outbound> {
        return sanitizeSelectorSafeOutbounds(outbounds)
    }

    internal override fun buildConfigWithOutboundsPreservingProfileSettings(
        existingConfig: SingBoxConfig?,
        outbounds: List<Outbound>
    ): SingBoxConfig {
        return existingConfig?.copy(outbounds = outbounds) ?: SingBoxConfig(outbounds = outbounds)
    }

    internal override fun writeTextFileAtomicallyForTest(targetFile: File, content: String) {
        writeTextFileAtomically(targetFile, content)
    }

    internal override fun subscriptionResponseMaxBytesForTest(): Long {
        return SUBSCRIPTION_RESPONSE_MAX_BYTES
    }

    internal override fun isSubscriptionContentLengthTooLargeForTest(contentLength: Long): Boolean {
        return isSubscriptionContentLengthTooLarge(contentLength)
    }

    internal override fun isSubscriptionContentLengthTooLarge(contentLength: Long): Boolean {
        return contentLength > SUBSCRIPTION_RESPONSE_MAX_BYTES
    }

    internal override fun readSubscriptionResponseBody(responseBody: ResponseBody): String {
        val contentLength = responseBody.contentLength()
        require(!isSubscriptionContentLengthTooLarge(contentLength)) {
            "Subscription response body is too large: $contentLength bytes"
        }

        val charset = responseBody.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        val output = ByteArrayOutputStream(
            contentLength.takeIf { it in 0..SUBSCRIPTION_RESPONSE_MAX_BYTES }?.toInt() ?: 8192
        )
        var totalBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

        responseBody.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                totalBytes += read
                require(totalBytes <= SUBSCRIPTION_RESPONSE_MAX_BYTES) {
                    "Subscription response body exceeds $SUBSCRIPTION_RESPONSE_MAX_BYTES bytes"
                }
                output.write(buffer, 0, read)
            }
        }

        return output.toString(charset.name())
    }

    internal override fun writeTextFileAtomically(targetFile: File, content: String) {
        targetFile.parentFile?.mkdirs()
        val tempFile = createSiblingTempFile(targetFile)

        try {
            tempFile.writeText(content, Charsets.UTF_8)
            moveTempFileIntoPlace(tempFile, targetFile)
        } finally {
            if (tempFile.isFile && !tempFile.delete()) {
                Log.w(TAG, "Failed to delete config temp file: ${tempFile.absolutePath}")
            }
        }
    }

    internal override fun createSiblingTempFile(targetFile: File): File {
        targetFile.parentFile?.mkdirs()
        val prefix = "${targetFile.name.take(64)}.".takeIf { it.length >= 3 } ?: "tmp."
        return File.createTempFile(prefix, ".tmp", targetFile.parentFile)
    }

    internal override fun moveTempFileIntoPlace(tempFile: File, targetFile: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            moveTempFileWithFileApi(tempFile, targetFile)
            return
        }

        try {
            moveTempFileWithNio(tempFile, targetFile, atomic = true)
        } catch (_: IOException) {
            moveTempFileWithNio(tempFile, targetFile, atomic = false)
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private fun moveTempFileWithNio(tempFile: File, targetFile: File, atomic: Boolean) {
        if (atomic) {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } else {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun moveTempFileWithFileApi(tempFile: File, targetFile: File) {
        if (targetFile.exists() && !targetFile.delete()) {
            throw IOException("Failed to delete old config file: ${targetFile.absolutePath}")
        }
        if (tempFile.renameTo(targetFile)) return

        tempFile.copyTo(targetFile, overwrite = true)
        if (!tempFile.delete()) {
            Log.w(TAG, "Failed to delete moved config temp file: ${tempFile.absolutePath}")
        }
    }

    internal override fun sanitizeSelectorSafeOutbounds(outbounds: List<Outbound>): List<Outbound> {
        val allOutboundTags = outbounds.map { it.tag }.toSet()
        return outbounds.map { outbound ->
            if (outbound.type == "selector" || outbound.type == "urltest" || outbound.type == "url-test") {
                sanitizeSelectorLikeOutbound(outbound, allOutboundTags)
            } else {
                outbound
            }
        }
    }

    internal override fun sanitizeSelectorLikeOutbound(outbound: Outbound, allOutboundTags: Set<String>): Outbound {
        val validRefs = outbound.outbounds?.filter { allOutboundTags.contains(it) } ?: emptyList()
        val safeRefs = if (validRefs.isEmpty()) listOf("direct") else validRefs

        if (safeRefs.size != (outbound.outbounds?.size ?: 0)) {
            Log.w(TAG, "Filtered invalid refs in ${outbound.tag}: ${outbound.outbounds} -> $safeRefs")
        }

        return if (outbound.type == "selector") {
            val currentDefault = outbound.default
            val safeDefault = if (currentDefault != null && safeRefs.contains(currentDefault)) {
                currentDefault
            } else {
                safeRefs.firstOrNull()
            }
            outbound.copy(outbounds = safeRefs, default = safeDefault)
        } else {
            outbound.copy(outbounds = safeRefs, default = null)
        }
    }

    internal override fun buildProfileRouteGroupOutbounds(
        groupTag: String,
        nodeTags: List<String>
    ): List<Outbound> {
        val distinctNodeTags = nodeTags
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (distinctNodeTags.isEmpty()) {
            return emptyList()
        }

        val autoTag = buildRouteGroupAutoTag(groupTag)
        return listOf(
            Outbound(
                type = "urltest",
                tag = autoTag,
                outbounds = distinctNodeTags,
                url = ROUTE_GROUP_AUTO_TEST_URL,
                interval = ROUTE_GROUP_AUTO_TEST_INTERVAL,
                tolerance = ROUTE_GROUP_AUTO_TEST_TOLERANCE,
                interruptExistConnections = false
            ),
            Outbound(
                type = "selector",
                tag = groupTag,
                outbounds = listOf(autoTag, "PROXY"),
                default = autoTag,
                interruptExistConnections = false
            )
        )
    }

    internal override fun buildBootstrapDnsRules(
        serverAddresses: List<String>,
        bootstrapV4Tag: String,
        bootstrapV6Tag: String,
        bootstrapTag: String
    ): List<DnsRule> {
        val bootstrapDomains = serverAddresses
            .mapNotNull { extractHostFromAddress(it) }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isIpAddressValue(it) && !it.equals("local", ignoreCase = true) }
            .distinct()

        if (bootstrapDomains.isEmpty()) {
            return emptyList()
        }

        return listOf(
            DnsRule(
                domain = bootstrapDomains,
                queryType = listOf("A"),
                action = "route",
                server = bootstrapV4Tag
            ),
            DnsRule(
                domain = bootstrapDomains,
                queryType = listOf("AAAA"),
                action = "route",
                server = bootstrapV6Tag
            ),
            DnsRule(
                domain = bootstrapDomains,
                action = "route",
                server = bootstrapTag
            )
        )
    }

    internal override fun isIpAddressValue(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        return (address.count { it == '.' } == 3 &&
            address.all { it.isDigit() || it == '.' }) ||
            address.contains(":")
    }

    @Suppress("ReturnCount")
    internal override fun extractHostFromAddress(address: String): String? {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return null

        extractHostByUri(trimmed)?.let { return it }
        extractHostByUri("dns://$trimmed")?.let { return it }

        if (trimmed.startsWith("[") && trimmed.contains("]")) {
            return trimmed.substringAfter('[').substringBefore(']')
        }

        val colonCount = trimmed.count { it == ':' }
        if (colonCount == 1 && !trimmed.contains('/')) {
            return trimmed.substringBefore(':').takeIf { it.isNotBlank() }
        }

        return trimmed
    }

    internal override fun extractHostByUri(address: String): String? {
        return try {
            val uri = URI(address)
            uri.host
        } catch (_: Exception) {
            null
        }
    }

    internal override fun extractSubscriptionUrlFromHtml(html: String): String? {
        return REGEX_HTML_SUBSCRIPTION_INPUT.find(html)
            ?.value
            ?.let { inputTag -> REGEX_HTML_INPUT_VALUE.find(inputTag)?.groupValues?.getOrNull(1) }
            ?.trim()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    @Suppress("UnusedParameter")
    internal override fun looksLikeHtmlSubscriptionPage(contentType: String?, body: String): Boolean {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("<")) {
            return false
        }

        return trimmed.startsWith("<!DOCTYPE html>", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true) ||
            trimmed.startsWith("<head", ignoreCase = true) ||
            trimmed.startsWith("<body", ignoreCase = true) ||
            trimmed.startsWith("<meta", ignoreCase = true) ||
            trimmed.startsWith("<title", ignoreCase = true)
    }

    internal override fun extractSubscriptionHost(url: String): String? {
        return runCatching { URI(url).host?.lowercase() }.getOrNull()
    }

    internal override fun looksLikeSubscriptionUrlForImport(content: String): Boolean {
        val trimmed = content.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return false
        }

        return runCatching {
            val uri = URI(trimmed)
            val path = uri.rawPath.orEmpty()
            val query = uri.rawQuery.orEmpty()
            val hasUserInfo = !uri.userInfo.isNullOrBlank()
            val hasName = !uri.fragment.isNullOrBlank()
            val hasProxyOnlyPort = uri.port > 0 && path.isBlank() && query.isBlank()
            !hasUserInfo && !hasName && !hasProxyOnlyPort
        }.getOrDefault(false)
    }

    internal override fun prioritizeUserAgents(preferredUserAgent: String?): List<String> {
        if (preferredUserAgent.isNullOrBlank()) return USER_AGENTS
        return buildList {
            add(preferredUserAgent)
            USER_AGENTS.forEach { userAgent ->
                if (!userAgent.equals(preferredUserAgent, ignoreCase = true)) {
                    add(userAgent)
                }
            }
        }
    }

    internal override fun buildSubscriptionAttemptUserAgents(
        preferredUserAgent: String?,
        circuitBrokenUserAgents: Set<String>
    ): List<String> {
        return filterCircuitBrokenUserAgents(
            userAgents = prioritizeUserAgents(preferredUserAgent),
            circuitBrokenUserAgents = circuitBrokenUserAgents
        )
    }

    internal override fun filterCircuitBrokenUserAgents(
        userAgents: List<String>,
        circuitBrokenUserAgents: Set<String>
    ): List<String> {
        if (circuitBrokenUserAgents.isEmpty()) return userAgents
        val available = userAgents.filterNot { userAgent ->
            circuitBrokenUserAgents.any { blocked ->
                blocked.equals(userAgent, ignoreCase = true)
            }
        }
        return if (available.isNotEmpty()) available else userAgents
    }

    internal override fun shouldRecordSubscriptionNetworkFailure(exception: Exception): Boolean {
        if (exception is ConnectException || exception is SocketTimeoutException) {
            return true
        }
        val message = exception.message.orEmpty().lowercase()
        return "failed to connect" in message || "timeout" in message
    }

    internal override fun shouldStopSubscriptionFallback(
        httpStatusCode: Int?,
        looksLikeHtmlInfoPage: Boolean): Boolean {
        return looksLikeHtmlInfoPage || httpStatusCode == 429
    }

    internal override fun resolveSubscriptionUpdateBudgetSeconds(configuredTimeoutSeconds: Int): Long {
        return configuredTimeoutSeconds.takeIf { it > 0 }?.toLong()
            ?: AppSettings().subscriptionUpdateTimeout.toLong()
    }

    internal override fun resolveSubscriptionAttemptTimeoutBudget(
        totalBudgetSeconds: Long,
        elapsedMs: Long
    ): SubscriptionAttemptTimeoutBudget? {
        val safeTotalBudgetSeconds = totalBudgetSeconds.coerceAtLeast(1L)
        val remainingMs = (safeTotalBudgetSeconds * 1000L) - elapsedMs
        if (remainingMs <= 0L) return null

        val remainingSeconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
        return SubscriptionAttemptTimeoutBudget(
            connectTimeoutSeconds = remainingSeconds,
            readTimeoutSeconds = remainingSeconds,
            writeTimeoutSeconds = remainingSeconds,
            callTimeoutSeconds = remainingSeconds
        )
    }

    internal override fun resolveAppRuleOutboundMode(mode: RuleSetOutboundMode?): RuleSetOutboundMode {
        return mode ?: RuleSetOutboundMode.PROXY
        // 有意设计: 自定义规则通常是代理规则，直连为例外
        // 符合"代理优先"的用户心智模型
    }

    internal override fun resolveAppGroupOutboundMode(mode: RuleSetOutboundMode?): RuleSetOutboundMode {
        return mode ?: RuleSetOutboundMode.DIRECT
        // 有意设计: AppGroup 主要用于需要直连的本地应用（游戏、支付等）
        // 如需代理，用户应显式配置
    }

    internal override fun resolveRuleSetOutboundMode(mode: RuleSetOutboundMode?): RuleSetOutboundMode {
        return mode ?: RuleSetOutboundMode.PROXY
    }

    internal override fun resolveCustomRuleOutboundMode(
        mode: RuleSetOutboundMode?,
        oldOutbound: OutboundTag
    ): RuleSetOutboundMode {
        if (mode != null) return mode
        return when (oldOutbound) {
            OutboundTag.DIRECT -> RuleSetOutboundMode.DIRECT
            OutboundTag.PROXY -> RuleSetOutboundMode.PROXY
            OutboundTag.BLOCK -> RuleSetOutboundMode.BLOCK
        }
    }

    internal override fun resolveRouteModeForRuleSetForTest(ruleSet: RuleSet): RuleSetOutboundMode {
        return resolveRuleSetOutboundMode(ruleSet.outboundMode)
    }

    internal override fun resolveRouteModeForAppGroupForTest(group: AppGroup): RuleSetOutboundMode {
        return resolveAppGroupOutboundMode(group.outboundMode)
    }

    internal override fun resolveRouteModeForCustomRuleForTest(rule: CustomRule): RuleSetOutboundMode {
        return resolveCustomRuleOutboundMode(rule.outboundMode, rule.outbound)
    }

    internal override fun filterAppliedRemoteRuleSets(
        ruleSets: List<RuleSet>,
        validTags: Set<String>
    ): List<RuleSet> {
        return ruleSets.filter { ruleSet ->
            ruleSet.enabled && ruleSet.type == RuleSetType.REMOTE && ruleSet.tag in validTags
        }
    }

    internal override fun filterAppliedRemoteRuleSetsForTest(
        ruleSets: List<RuleSet>,
        validTags: Set<String>
    ): List<RuleSet> {
        return filterAppliedRemoteRuleSets(ruleSets, validTags)
    }

    internal override fun detectRuleSetRuleTypeForTest(file: java.io.File, tag: String): ConfigRepository.RuleSetRuleType {
        return detectRuleSetRuleTypeFromFile(file, tag)
    }

    override fun detectRuleSetRuleTypeStatic(file: java.io.File, tag: String): ConfigRepository.RuleSetRuleType {
        return detectRuleSetRuleTypeFromFile(file, tag)
    }

    internal override fun detectRuleSetRuleTypeFromFile(file: java.io.File, tag: String): ConfigRepository.RuleSetRuleType {
        val tagRuleType = detectRuleSetRuleTypeFromTag(tag)
        if (tagRuleType != ConfigRepository.RuleSetRuleType.UNKNOWN) return tagRuleType
        if (!file.exists() || file.length() < RULE_SET_MIN_SIZE_BYTES) {
            return ConfigRepository.RuleSetRuleType.UNKNOWN
        }
        return try {
            val sample = readRuleSetSampleFromFile(file)
            detectRuleSetRuleTypeFromSample(sample)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect rule set type: ${file.name}", e)
            ConfigRepository.RuleSetRuleType.UNKNOWN
        }
    }

    internal override fun detectRuleSetRuleTypeFromTag(tag: String): ConfigRepository.RuleSetRuleType {
        val normalizedTag = tag.trim().lowercase()
        return when {
            normalizedTag.startsWith("geosite-") || normalizedTag.contains("geosite") -> ConfigRepository.RuleSetRuleType.DOMAIN
            normalizedTag.startsWith("geoip-") || normalizedTag.contains("geoip") -> ConfigRepository.RuleSetRuleType.IP
            else -> ConfigRepository.RuleSetRuleType.UNKNOWN
        }
    }

    internal override fun detectRuleSetRuleTypeFromSample(sample: ByteArray): ConfigRepository.RuleSetRuleType {
        if (sample.isEmpty()) return ConfigRepository.RuleSetRuleType.UNKNOWN
        if (!isLikelyTextRuleSetFromBytes(sample)) return ConfigRepository.RuleSetRuleType.UNKNOWN
        return detectRuleTypeFromTextContent(sample.toString(Charsets.UTF_8))
    }

    internal override fun detectRuleTypeFromTextContent(text: String): ConfigRepository.RuleSetRuleType {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .take(100)
            .toList()

        if (lines.isEmpty()) return ConfigRepository.RuleSetRuleType.UNKNOWN

        var ipLineCount = 0
        var domainLineCount = 0

        for (line in lines) {
            when {
                isIpRuleLineContent(line) -> ipLineCount++
                isDomainRuleLineContent(line) -> domainLineCount++
            }
        }

        val total = ipLineCount + domainLineCount
        if (total == 0) return ConfigRepository.RuleSetRuleType.UNKNOWN

        val ipRatio = ipLineCount.toFloat() / total
        val domainRatio = domainLineCount.toFloat() / total

        return when {
            ipRatio >= RULE_SET_IP_THRESHOLD -> ConfigRepository.RuleSetRuleType.IP
            domainRatio >= RULE_SET_IP_THRESHOLD -> ConfigRepository.RuleSetRuleType.DOMAIN
            ipRatio > 0 && domainRatio > 0 -> ConfigRepository.RuleSetRuleType.MIXED
            else -> ConfigRepository.RuleSetRuleType.UNKNOWN
        }
    }

    internal override fun isIpRuleLineContent(line: String): Boolean {
        if (REGEX_IP_CIDR.matches(line)) return true
        if (isLikelyIpv6Cidr(line)) return true
        return isIpRuleWithPrefix(line)
    }

    internal override fun isLikelyIpv6Cidr(line: String): Boolean {
        if (!line.contains("/") || !line.contains(":") || line.contains(".")) return false
        val ipPart = line.substringBefore("/")
        return !ipPart.contains(" ") && ipPart.length <= 45 && ipPart.count { it == ':' } >= 1
    }

    internal override fun isIpRuleWithPrefix(line: String): Boolean {
        val prefixes = listOf("ip-cidr:", "ip:", "geoip:")
        for (prefix in prefixes) {
            if (line.startsWith(prefix, ignoreCase = true)) {
                val content = line.removePrefix(prefix).trim()
                if (REGEX_IP_CIDR.matches(content)) return true
                if (content.contains(":") && content.contains("/") && !content.contains(".")) return true
            }
        }
        return false
    }

    internal override fun isDomainRuleLineContent(line: String): Boolean {
        if (REGEX_DOMAIN_LINE.matches(line)) {
            return true
        }
        val domainPrefixes = listOf("domain:", "geosite:", "domain-keyword:", "domain-suffix:", "domain-regex:")
        for (prefix in domainPrefixes) {
            if (line.startsWith(prefix, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    internal override fun readRuleSetSampleFromFile(file: java.io.File): ByteArray {
        return file.inputStream().use { input ->
            val buffer = ByteArray(RULE_SET_SNIFF_BYTES)
            val read = input.read(buffer)
            if (read > 0) buffer.copyOf(read) else ByteArray(0)
        }
    }

    internal override fun isLikelyTextRuleSetFromBytes(sample: ByteArray): Boolean {
        if (sample.any { it == 0.toByte() }) return false
        val printableBytes = sample.count { byte ->
            val code = byte.toInt() and 0xff
            code == 9 || code == 10 || code == 13 || code in 32..126
        }
        return printableBytes >= sample.size * 3 / 4
    }

    internal override fun launchSubscriptionDnsPreResolve(
        scope: CoroutineScope,
        profileId: String,
        enabled: Boolean,
        updateRunId: Long?,
        onStarted: () -> Unit,
        onFinished: () -> Unit,
        preResolve: suspend () -> Boolean
    ): Job? {
        if (!enabled) {
            return null
        }

        val updateRunSuffix = updateRunId?.let { ", run=$it" }.orEmpty()

        Log.d(
            TAG,
            "Subscription update main flow finished for profile $profileId; " +
                "scheduling background DNS pre-resolve$updateRunSuffix"
        )
        return scope.launch {
            onStarted()
            Log.d(TAG, "Background DNS pre-resolve started for profile $profileId$updateRunSuffix")
            val success = runCatching { preResolve() }
                .getOrElse { e ->
                    Log.w(TAG, "Background DNS pre-resolve crashed for profile $profileId$updateRunSuffix", e)
                    false
                }
            if (success) {
                Log.d(TAG, "Background DNS pre-resolve completed for profile $profileId$updateRunSuffix")
            } else {
                Log.d(TAG, "Background DNS pre-resolve failed for profile $profileId$updateRunSuffix")
            }
            onFinished()
        }
    }

    internal override fun setProfileUpdateStageIfCurrent(
        profilesState: MutableStateFlow<List<ProfileUi>>,
        activeUpdateRuns: Map<String, Long>,
        profileId: String,
        runId: Long,
        stage: SubscriptionUpdateStage?
    ) {
        profilesState.update { profiles ->
            if (activeUpdateRuns[profileId] != runId) {
                return@update profiles
            }
            profiles.map { profile ->
                if (profile.id == profileId) profile.copy(updateStage = stage) else profile
            }
        }
    }

    internal override fun resolveSubscriptionUpdateStage(
        stageName: String?
    ): SubscriptionUpdateStage? {
        return when (stageName) {
            "requesting" -> SubscriptionUpdateStage.Requesting
            "parsing" -> SubscriptionUpdateStage.Parsing
            "saving" -> SubscriptionUpdateStage.Saving
            "dns_background" -> SubscriptionUpdateStage.DnsBackground
            else -> null
        }
    }

    internal override fun toRouteRule(semantic: ConfigRepository.OutboundSemantic, selectorTag: String): RouteRule {
        return when (semantic) {
            ConfigRepository.OutboundSemantic.Direct -> RouteRule(outbound = "direct")
            ConfigRepository.OutboundSemantic.Block -> RouteRule(action = "reject")
            ConfigRepository.OutboundSemantic.Proxy -> RouteRule(outbound = selectorTag)
            is ConfigRepository.OutboundSemantic.RouteTag -> RouteRule(outbound = semantic.tag)
            is ConfigRepository.OutboundSemantic.FallbackProxy -> RouteRule(outbound = semantic.tag)
        }
    }

    internal override fun buildRunRouteRulesForTest(
        settings: AppSettings,
        selectorTag: String,
        outbounds: List<Outbound>,
        profiles: List<ProfileEntity>,
        validRuleSets: List<RuleSetConfig>,
        nodeTagResolver: (String?) -> String?): List<RouteRule> {
        val profileUis = profiles.map { it.toUiModel() }
        return buildRunRouteRules(
            settings = settings,
            selectorTag = selectorTag,
            outbounds = outbounds,
            profiles = profileUis,
            nodeTagResolver = nodeTagResolver,
            validRuleSets = validRuleSets
        )
    }

    internal override fun resolveDnsStrategyForTest(strategy: DnsStrategy, mode: IpVersionMode): String {
        return mode.resolveDnsStrategy(strategy)
    }

    internal override fun buildBypassLanRulesForTest(settings: AppSettings): List<RouteRule> {
        return buildBypassLanRulesStatic(settings)
    }

    internal override fun buildMulticastRejectRulesForTest(settings: AppSettings): List<RouteRule> {
        return buildMulticastRejectRulesStatic(settings)
    }

    internal override fun buildHijackDnsRulesForTest(): List<RouteRule> {
        return buildHijackDnsRulesStatic()
    }

    internal override fun buildHijackDnsRulesStatic(): List<RouteRule> {
        return listOf(
            RouteRule(inbound = listOf("tun-in"), port = listOf(53), action = "hijack-dns"),
            RouteRule(protocolRaw = listOf("dns"), action = "hijack-dns"),
            RouteRule(port = listOf(853), action = "reject")
        )
    }

    @Suppress("LongParameterList")
    internal override fun buildRunRouteRules(
        settings: AppSettings,
        selectorTag: String,
        outbounds: List<Outbound>,
        profiles: List<ProfileUi>,
        nodeTagResolver: (String?) -> String?,
        validRuleSets: List<RuleSetConfig>
    ): List<RouteRule> {
        val customRuleSetRules = buildCustomRuleSetRulesStatic(
            settings = settings,
            defaultProxyTag = selectorTag,
            outbounds = outbounds,
            profiles = profiles,
            nodeTagResolver = nodeTagResolver,
            validRuleSets = validRuleSets
        )
        val quicRule = buildQuicBlockRuleStatic(settings)
        val multicastRejectRules = buildMulticastRejectRulesStatic(settings)
        val bypassLanRules = buildBypassLanRulesStatic(settings)
        val icmpEchoRules = buildIcmpEchoRulesStatic(settings)
        val defaultRuleCatchAll = buildDefaultRulesStatic(settings, selectorTag)
        val hijackDnsRule = buildHijackDnsRulesStatic()
        val sniffRule = listOf(RouteRule(inbound = listOf("tun-in", "mixed-in"), action = "sniff"))

        return when (settings.routingMode) {
            RoutingMode.GLOBAL_PROXY ->
                hijackDnsRule + sniffRule + quicRule + multicastRejectRules + icmpEchoRules + customRuleSetRules
            RoutingMode.GLOBAL_DIRECT ->
                hijackDnsRule + sniffRule + quicRule + multicastRejectRules + icmpEchoRules +
                    listOf(RouteRule(outbound = "direct"))
            RoutingMode.RULE -> {
                hijackDnsRule + sniffRule + quicRule + multicastRejectRules + bypassLanRules + icmpEchoRules +
                    customRuleSetRules + defaultRuleCatchAll
            }
        }
    }

    internal override fun buildQuicBlockRuleStatic(settings: AppSettings): List<RouteRule> {
        return if (settings.blockQuic) {
            listOf(RouteRule(protocolRaw = listOf("quic"), action = "reject"))
        } else {
            emptyList()
        }
    }

    internal override fun buildIcmpEchoRulesStatic(settings: AppSettings): List<RouteRule> {
        if (!settings.icmpEchoRoutingEnabled) return emptyList()

        return when (settings.routingMode) {
            RoutingMode.GLOBAL_DIRECT -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
            RoutingMode.GLOBAL_PROXY -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
            RoutingMode.RULE -> when (settings.defaultRule) {
                DefaultRule.DIRECT -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
                DefaultRule.BLOCK -> listOf(RouteRule(networkRaw = listOf("icmp"), action = "reject"))
                DefaultRule.PROXY -> listOf(RouteRule(networkRaw = listOf("icmp"), outbound = "direct"))
            }
        }
    }

    internal override fun buildDefaultRulesStatic(settings: AppSettings, selectorTag: String): List<RouteRule> {
        return when (settings.defaultRule) {
            DefaultRule.DIRECT -> listOf(RouteRule(outbound = "direct"))
            DefaultRule.BLOCK -> listOf(RouteRule(action = "reject"))
            DefaultRule.PROXY -> listOf(RouteRule(outbound = selectorTag))
        }
    }
}

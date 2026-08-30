@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.kunk.singbox.repository

import android.annotation.TargetApi
import android.os.Build
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.ResponseBody

internal val TAG = "ConfigRepository"

internal val PLACEHOLDER_NODE_SERVERS = setOf("127.0.0.1", "0.0.0.0", "::1")

internal val TAILSCALE_UNSUPPORTED_MESSAGE =
    "Tailscale 不受支持：为控制 APK 体积，当前 Android 内核未编译 with_tailscale，" +
        "请移除 Tailscale endpoint 或 DNS server 后重试"
internal val TOR_UNSUPPORTED_MESSAGE =
    "Tor 不受支持：当前 Android 内核未包含嵌入式 Tor，应用也未打包 Tor 可执行文件，" +
        "请移除 Tor outbound 后重试"

internal fun ConfigRepository.Companion.findUnsupportedAndroidCapability(config: SingBoxConfig): String? {
    val configuredTypes = buildList {
        config.endpoints.orEmpty().mapTo(this) { it.type }
        config.outbounds.orEmpty().mapTo(this) { it.type }
        config.proxies.orEmpty().mapTo(this) { it.type }
        config.dns?.servers.orEmpty().mapTo(this) { it.type.orEmpty() }
    }
    return unsupportedAndroidCapabilityMessage(configuredTypes)
}

internal fun ConfigRepository.Companion.findUnsupportedAndroidCapabilityInJson(content: String): String? {
    val root = runCatching { JsonParser.parseString(content) }.getOrNull() ?: return null
    val configuredTypes = jsonConfigObjects(root).flatMap { it.configuredTypes() }
    return unsupportedAndroidCapabilityMessage(configuredTypes)
}

internal fun ConfigRepository.Companion.unsupportedAndroidCapabilityMessage(configuredTypes: Iterable<String?>): String? {
    val normalizedTypes = configuredTypes.mapNotNull { type ->
        type?.trim()?.takeIf { it.isNotEmpty() }
    }
    return when {
        normalizedTypes.any { it.equals("tailscale", ignoreCase = true) } -> TAILSCALE_UNSUPPORTED_MESSAGE
        normalizedTypes.any { it.equals("tor", ignoreCase = true) } -> TOR_UNSUPPORTED_MESSAGE
        else -> null
    }
}

internal fun ConfigRepository.Companion.jsonConfigObjects(root: com.google.gson.JsonElement): List<JsonObject> {
    return when {
        root.isJsonArray -> root.asJsonArray.mapNotNull { value ->
            value.takeIf { it.isJsonObject }?.asJsonObject
        }
        root.isJsonObject -> listOf(root.asJsonObject)
        else -> emptyList()
    }
}

internal fun JsonObject.configuredTypes(): List<String> {
    return buildList {
        configuredType()?.let { add(it) }
        listOf("endpoints", "outbounds", "proxies", "servers").forEach { key ->
            addAll(arrayConfiguredTypes(key))
        }
        get("dns")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.arrayConfiguredTypes("servers")
            ?.let { addAll(it) }
    }
}

internal fun JsonObject.configuredType(): String? {
    return get("type")?.takeIf { it.isJsonPrimitive }?.asString
}

internal fun JsonObject.arrayConfiguredTypes(key: String): List<String> {
    return get(key)
        ?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.mapNotNull { value -> value.takeIf { it.isJsonObject }?.asJsonObject?.configuredType() }
        .orEmpty()
}

internal fun ConfigRepository.Companion.buildLatencyProbeTag(nodeId: String): String {
    return "latency-probe-$nodeId"
}

/** 延迟探测用：WireGuard 不走 OutboundFixer，仅规范化 peers 后保留逻辑 outbound。 */
internal fun ConfigRepository.Companion.prepareLatencyRuntimeOutbound(
    outbound: Outbound,
    buildNonWireGuard: (Outbound) -> Outbound?
): Outbound? {
    if (outbound.type.equals("wireguard", ignoreCase = true)) {
        return outbound.copy(peers = normalizeWireGuardPeersForRuntime(outbound.peers))
    }
    return buildNonWireGuard(outbound)
}

internal fun ConfigRepository.Companion.buildLatencyRuntimeOutbounds(
    config: SingBoxConfig,
    buildNonWireGuard: (Outbound) -> Outbound?
): List<Outbound> {
    val normalized = normalizeWireGuardEndpointsForInternalUse(config)
    return normalized.outbounds.orEmpty().mapNotNull { outbound ->
        prepareLatencyRuntimeOutbound(outbound, buildNonWireGuard)
    }
}

internal fun ConfigRepository.Companion.resolveLatencyRuntimeDetours(
    sourceProfileId: String,
    sourceOutbounds: List<Outbound>,
    isProtectedReference: (sourceProfileId: String, reference: String) -> Boolean = { _, _ -> false },
    loadProfileOutbounds: (String) -> List<Outbound>?
): List<Outbound> {
    val rootReferences = sourceOutbounds.map { sourceProfileId to it.tag }
    return RuntimeOutboundDependencyResolver(
        initialOutboundsByProfile = mapOf(sourceProfileId to sourceOutbounds),
        reservedTags = emptySet(),
        allowProtectedRoots = false,
        isProtectedReference = isProtectedReference,
        loadProfileOutbounds = loadProfileOutbounds
    ).resolve(rootReferences).outbounds
}

internal fun ConfigRepository.Companion.resolveRuntimeOutboundDependencies(
    rootReferences: List<Pair<String, String>>,
    reservedTags: Set<String>,
    isProtectedReference: (sourceProfileId: String, reference: String) -> Boolean = { _, _ -> false },
    loadProfileOutbounds: (String) -> List<Outbound>?
): ConfigRepositoryRuntimeOutboundResolution {
    return RuntimeOutboundDependencyResolver(
        initialOutboundsByProfile = emptyMap(),
        reservedTags = reservedTags,
        allowProtectedRoots = true,
        isProtectedReference = isProtectedReference,
        loadProfileOutbounds = loadProfileOutbounds
    ).resolve(rootReferences)
}

internal class RuntimeOutboundDependencyResolver(
    initialOutboundsByProfile: Map<String, List<Outbound>>,
    reservedTags: Set<String>,
    private val allowProtectedRoots: Boolean,
    private val isProtectedReference: (sourceProfileId: String, reference: String) -> Boolean,
    private val loadProfileOutbounds: (String) -> List<Outbound>?
) {
    private val outboundsByProfile = initialOutboundsByProfile.toMutableMap()
    private val externalRuntimeTags = reservedTags.toSet()
    private val runtimeTags = mutableMapOf<Pair<String, String>, String>()
    private val usedTags = reservedTags.toMutableSet()
    private val resolving = mutableSetOf<Pair<String, String>>()
    private val resolvedOutbounds = linkedMapOf<Pair<String, String>, Outbound>()
    private val blockedOutbounds = mutableSetOf<Pair<String, String>>()
    private val groupTypes = setOf("selector", "urltest", "url-test")
    private var rootKeys = emptySet<Pair<String, String>>()

    fun resolve(rootReferences: List<Pair<String, String>>): ConfigRepositoryRuntimeOutboundResolution {
        val orderedRootKeys = rootReferences.distinct()
        rootKeys = orderedRootKeys.toSet()
        orderedRootKeys.forEach(::allocateRuntimeTag)
        orderedRootKeys.forEach { resolveOutbound(it) }
        val outbounds = buildList {
            orderedRootKeys.mapNotNullTo(this) { resolvedOutbounds[it] }
            resolvedOutbounds.forEach { (key, outbound) ->
                if (key !in rootKeys) add(outbound)
            }
        }
        return ConfigRepositoryRuntimeOutboundResolution(
            outbounds = outbounds,
            runtimeTags = runtimeTags.filterKeys { it in resolvedOutbounds }
        )
    }

    private fun outboundsFor(profileId: String): List<Outbound>? {
        outboundsByProfile[profileId]?.let { return it }
        return loadProfileOutbounds(profileId)?.also { outboundsByProfile[profileId] = it }
    }

    private fun resolveReference(profileId: String, reference: String): Pair<String, String>? {
        val parts = reference.split("::", limit = 2)
        val targetProfileId = if (parts.size == 2) parts[0] else profileId
        val targetTag = if (parts.size == 2) parts[1] else reference
        if (targetProfileId.isBlank() || targetTag.isBlank()) return null
        val targetExists = outboundsFor(targetProfileId)?.any { it.tag == targetTag } == true
        return (targetProfileId to targetTag).takeIf { targetExists }
    }

    private fun allocateRuntimeTag(key: Pair<String, String>): String {
        runtimeTags[key]?.let { return it }
        val (profileId, originalTag) = key
        var candidate = originalTag
        if (candidate in usedTags) {
            val suffix = profileId.take(8).ifBlank { "profile" }
            val base = "$originalTag#$suffix"
            candidate = base
            var index = 2
            while (candidate in usedTags) {
                candidate = "$base-$index"
                index++
            }
        }
        runtimeTags[key] = candidate
        usedTags.add(candidate)
        return candidate
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private fun resolveOutbound(key: Pair<String, String>): String? {
        if (key in blockedOutbounds) return null
        if (key in resolving) return blockOutbound(key)
        val knownTag = resolvedOutbounds[key]?.tag
        if (knownTag != null) return knownTag
        val (profileId, sourceTag) = key
        val source = outboundsFor(profileId)?.firstOrNull { it.tag == sourceTag }
            ?: return blockOutbound(key)
        if ((!allowProtectedRoots || key !in rootKeys) && isProtectedReference(profileId, sourceTag)) {
            return blockOutbound(key)
        }
        val runtimeTag = allocateRuntimeTag(key)
        resolving.add(key)
        val detour = source.detour?.takeIf { it.isNotBlank() }
        if (detour != null && isProtectedReference(profileId, detour)) {
            return blockOutbound(key)
        }
        val detourKey = detour?.let { resolveReference(profileId, it) }
        if (detour != null && detourKey == null && !isExternalRuntimeReference(detour)) {
            return blockOutbound(key)
        }
        val resolvedDetour = detourKey?.let { resolveOutbound(it) }
            ?: detour?.takeIf(::isExternalRuntimeReference)
        if (detourKey in blockedOutbounds) {
            return blockOutbound(key)
        }
        val groupSafeSource = resolveGroupReferences(profileId, source) ?: return blockOutbound(key)
        resolvedOutbounds[key] = groupSafeSource.copy(tag = runtimeTag, detour = resolvedDetour)
        resolving.remove(key)
        return runtimeTag
    }

    private fun resolveGroupReferences(profileId: String, source: Outbound): Outbound? {
        if (source.type.lowercase() !in groupTypes) return source

        fun resolveGroupReference(reference: String): String? {
            if (isProtectedReference(profileId, reference)) return null
            val targetKey = resolveReference(profileId, reference)
                ?: return reference.takeIf(::isExternalRuntimeReference)
            val runtimeTag = resolveOutbound(targetKey)
            return runtimeTag?.takeUnless { targetKey in blockedOutbounds }
        }

        val candidates = source.outbounds.orEmpty().map { reference ->
            resolveGroupReference(reference) ?: return null
        }
        val default = source.default?.let { reference ->
            resolveGroupReference(reference) ?: return null
        }
        return source.copy(outbounds = candidates, default = default)
    }

    private fun isExternalRuntimeReference(reference: String): Boolean {
        return "::" !in reference && reference in externalRuntimeTags
    }

    private fun blockOutbound(key: Pair<String, String>): String? {
        resolving.remove(key)
        resolvedOutbounds.remove(key)
        blockedOutbounds.add(key)
        return null
    }
}

internal fun ConfigRepository.Companion.buildNodeTestInfosFromContexts(
    nodes: List<NodeUi>,
    loadContext: (String) -> ConfigRepositoryLatencyRuntimeContext?
): List<ConfigRepositoryNodeTestInfo> {
    return nodes.groupBy { it.sourceProfileId }.flatMap { (profileId, profileNodes) ->
        val context = loadContext(profileId) ?: return@flatMap emptyList()
        val outboundsByTag = context.outbounds.associateBy { it.tag }
        profileNodes.mapNotNull { node ->
            val outbound = outboundsByTag[node.name] ?: return@mapNotNull null
            ConfigRepositoryNodeTestInfo(
                outbound = outbound,
                nodeId = node.id,
                profileId = profileId,
                dnsConfig = context.dnsConfig,
                allOutbounds = context.outbounds
            )
        }
    }
}

internal fun ConfigRepository.Companion.applyLatencyResultsToNodes(
    nodes: List<NodeUi>,
    results: Map<String, Long>
): List<NodeUi> {
    if (results.isEmpty()) return nodes
    return nodes.map { node ->
        val latency = results[node.id] ?: return@map node
        node.copy(latencyMs = latency)
    }
}

internal val ROUTE_GROUP_AUTO_TAG_SUFFIX = "#AUTO"

internal val RUNTIME_RELOAD_POLL_INTERVAL_MS = 250L
internal val RUNTIME_RELOAD_TIMEOUT_MS = 30_000L
internal val MANUAL_HOT_SWITCH_CONFIRMATION_TIMEOUT_MS = 3_000L
internal val AUTO_GROUP_RESOLUTION_TIMEOUT_MS = 10_000L

internal fun ConfigRepository.Companion.resolveOutboundServerAddressStrategy(
    strategy: DnsStrategy,
    ipVersionMode: IpVersionMode
): String {
    return ipVersionMode.resolveDnsStrategy(strategy)
}

/**
 * 直连 DNS：双栈下 AUTO 映射为 ipv4_only，避免无 IPv6 出口时国内站 AAAA 直连失败。
 * 用户显式选择 PREFER_IPV4/PREFER_IPV6 等时仍按原规则解析。
 */
internal fun ConfigRepository.Companion.resolveDirectDnsStrategy(
    strategy: DnsStrategy,
    ipVersionMode: IpVersionMode
): String {
    if (ipVersionMode == IpVersionMode.DUAL_STACK && strategy == DnsStrategy.AUTO) {
        return "ipv4_only"
    }
    return ipVersionMode.resolveDnsStrategy(strategy)
}

internal fun ConfigRepository.Companion.buildOutboundServerAddressStrategyLog(
    scope: String,
    strategy: DnsStrategy,
    ipVersionMode: IpVersionMode,
    resolvedStrategy: String
): String {
    return "INFO [CFG] outbound_server_domain_resolver scope=$scope " +
        "serverAddressStrategy=${strategy.name} " +
        "ipVersionMode=${ipVersionMode.name} " +
        "strategy=$resolvedStrategy"
}

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

internal val DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG = "dns-bootstrap"

internal fun ConfigRepository.Companion.shouldActivateCreatedNode(activeProfileId: String?): Boolean {
    return activeProfileId == null
}

internal fun ConfigRepository.Companion.resolveManualProfileTarget(
    nodes: List<NodeUi>,
    rememberedNodeId: String?,
    autoSelectionEnabled: Boolean
): NodeUi? {
    val candidates = nodes.filter { node ->
        !node.meteredProtected && (!autoSelectionEnabled || node.autoSelectionEligible)
    }
    return candidates.firstOrNull { it.id == rememberedNodeId }
        ?: candidates.minByOrNull(NodeUi::id)
        ?: nodes.firstOrNull { it.id == rememberedNodeId }
        ?: nodes.minByOrNull(NodeUi::id)
}

internal fun ConfigRepository.Companion.stableNodeId(profileId: String, outboundTag: String): String {
    val key = "$profileId|$outboundTag"
    synchronized(nodeIdCache) {
        nodeIdCache[key]?.let { return it }
        val id = UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
        nodeIdCache[key] = id
        return id
    }
}

internal fun ConfigRepository.Companion.buildRouteGroupAutoTag(groupTag: String): String {
    return "$groupTag$ROUTE_GROUP_AUTO_TAG_SUFFIX"
}

internal fun ConfigRepository.Companion.buildProfileRouteTag(profileId: String, profileName: String): String {
    val readableName = profileName.trim().ifBlank { "Profile" }
    return "P:$readableName#$profileId"
}

internal fun ConfigRepository.Companion.resolveRunLogLevel(captureMode: TrafficCaptureMode): String =
    if (captureMode == TrafficCaptureMode.ROOT_TRANSPARENT) "debug" else "info"

internal fun ConfigRepository.Companion.buildConfigWithOutboundsPreservingProfileSettings(
    existingConfig: SingBoxConfig?,
    outbounds: List<Outbound>
): SingBoxConfig {
    return existingConfig?.copy(outbounds = outbounds) ?: SingBoxConfig(outbounds = outbounds)
}

internal fun ConfigRepository.Companion.isSubscriptionContentLengthTooLarge(contentLength: Long): Boolean {
    return contentLength > SUBSCRIPTION_RESPONSE_MAX_BYTES
}

internal fun ConfigRepository.Companion.readSubscriptionResponseBody(responseBody: ResponseBody): String {
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

internal fun ConfigRepository.Companion.writeTextFileAtomically(targetFile: File, content: String) {
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

internal fun ConfigRepository.Companion.createSiblingTempFile(targetFile: File): File {
    targetFile.parentFile?.mkdirs()
    val prefix = "${targetFile.name.take(64)}.".takeIf { it.length >= 3 } ?: "tmp."
    return File.createTempFile(prefix, ".tmp", targetFile.parentFile)
}

internal fun ConfigRepository.Companion.moveTempFileIntoPlace(tempFile: File, targetFile: File) {
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
internal fun ConfigRepository.Companion.moveTempFileWithNio(tempFile: File, targetFile: File, atomic: Boolean) {
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

internal fun ConfigRepository.Companion.moveTempFileWithFileApi(tempFile: File, targetFile: File) {
    if (targetFile.exists() && !targetFile.delete()) {
        throw IOException("Failed to delete old config file: ${targetFile.absolutePath}")
    }
    if (tempFile.renameTo(targetFile)) return

    tempFile.copyTo(targetFile, overwrite = true)
    if (!tempFile.delete()) {
        Log.w(TAG, "Failed to delete moved config temp file: ${tempFile.absolutePath}")
    }
}

internal fun ConfigRepository.Companion.sanitizeSelectorSafeOutbounds(
    outbounds: List<Outbound>,
    additionalTags: Set<String> = emptySet()
): List<Outbound> {
    val allOutboundTags = outbounds.map { it.tag }.toSet() + additionalTags
    return outbounds.map { outbound ->
        if (outbound.type == "selector" || outbound.type == "urltest" || outbound.type == "url-test") {
            sanitizeSelectorLikeOutbound(outbound, allOutboundTags)
        } else {
            outbound
        }
    }
}

internal fun ConfigRepository.Companion.filterAutomaticGroupCandidates(
    outbounds: List<Outbound>,
    excludedNodeTags: Set<String>
): List<Outbound> {
    if (excludedNodeTags.isEmpty()) return outbounds
    return outbounds.map { outbound ->
        if (outbound.type == "urltest" || outbound.type == "url-test") {
            outbound.copy(outbounds = outbound.outbounds?.filterNot(excludedNodeTags::contains))
        } else {
            outbound
        }
    }
}

@Suppress("CyclomaticComplexMethod")
internal fun ConfigRepository.Companion.pruneUnreachableGroupOutbounds(
    outbounds: List<Outbound>,
    route: RouteConfig,
    dns: DnsConfig,
    endpoints: List<Endpoint> = emptyList()
): List<Outbound> {
    val groupTypes = setOf("selector", "urltest", "url-test")
    val groupsByTag = outbounds
        .filter { it.type in groupTypes }
        .associateBy { it.tag }
    if (groupsByTag.isEmpty()) return outbounds

    val reachableGroups = mutableSetOf<String>()
    val pendingGroups = mutableListOf<String>()
    fun enqueue(tag: String?) {
        if (!tag.isNullOrBlank() && tag in groupsByTag && reachableGroups.add(tag)) {
            pendingGroups.add(tag)
        }
    }

    enqueue(route.finalOutbound)
    route.rules.orEmpty().forEach { enqueue(it.outbound) }
    route.ruleSet.orEmpty().forEach { enqueue(it.downloadDetour) }
    dns.servers.orEmpty().forEach { enqueue(it.detour) }
    outbounds.filter { it.type !in groupTypes }.forEach { enqueue(it.detour) }
    endpoints.forEach { enqueue(it.detour) }

    while (pendingGroups.isNotEmpty()) {
        val group = groupsByTag.getValue(pendingGroups.removeAt(pendingGroups.lastIndex))
        group.outbounds.orEmpty().forEach(::enqueue)
        enqueue(group.detour)
    }

    val pruned = outbounds.filter { it.type !in groupTypes || it.tag in reachableGroups }
    if (pruned.size != outbounds.size) {
        val removedTags = outbounds.asSequence()
            .filter { it.type in groupTypes && it.tag !in reachableGroups }
            .map { it.tag }
            .toList()
        Log.i(TAG, "Pruned unreachable runtime groups: $removedTags")
    }
    return pruned
}

internal fun ConfigRepository.Companion.sanitizeSelectorLikeOutbound(outbound: Outbound, allOutboundTags: Set<String>): Outbound {
    val safeRefs = outbound.outbounds?.filter { allOutboundTags.contains(it) }.orEmpty()

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

internal fun ConfigRepository.Companion.expandSharedUidPackageNames(
    packageNames: List<String>,
    resolveUid: (String) -> Int?,
    resolvePackages: (Int) -> List<String>
): List<String> {
    return packageNames.asSequence()
        .map(String::trim)
        .filter(String::isNotBlank)
        .flatMap { packageName ->
            val uid = runCatching { resolveUid(packageName) }.getOrNull()
            val sharedPackages = uid
                ?.let { runCatching { resolvePackages(it) }.getOrDefault(emptyList()) }
                .orEmpty()
            (sharedPackages + packageName).asSequence()
        }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .toList()
}

internal val ConfigRepository.Companion.TAG get() = com.kunk.singbox.repository.TAG
internal val ConfigRepository.Companion.PLACEHOLDER_NODE_SERVERS get() = com.kunk.singbox.repository.PLACEHOLDER_NODE_SERVERS
internal val ConfigRepository.Companion.TAILSCALE_UNSUPPORTED_MESSAGE get() = com.kunk.singbox.repository.TAILSCALE_UNSUPPORTED_MESSAGE
internal val ConfigRepository.Companion.TOR_UNSUPPORTED_MESSAGE get() = com.kunk.singbox.repository.TOR_UNSUPPORTED_MESSAGE
internal val ConfigRepository.Companion.ROUTE_GROUP_AUTO_TAG_SUFFIX get() = com.kunk.singbox.repository.ROUTE_GROUP_AUTO_TAG_SUFFIX
internal val ConfigRepository.Companion.RUNTIME_RELOAD_POLL_INTERVAL_MS get() = com.kunk.singbox.repository.RUNTIME_RELOAD_POLL_INTERVAL_MS
internal val ConfigRepository.Companion.RUNTIME_RELOAD_TIMEOUT_MS get() = com.kunk.singbox.repository.RUNTIME_RELOAD_TIMEOUT_MS
internal val ConfigRepository.Companion.MANUAL_HOT_SWITCH_CONFIRMATION_TIMEOUT_MS get() = com.kunk.singbox.repository.MANUAL_HOT_SWITCH_CONFIRMATION_TIMEOUT_MS
internal val ConfigRepository.Companion.AUTO_GROUP_RESOLUTION_TIMEOUT_MS get() = com.kunk.singbox.repository.AUTO_GROUP_RESOLUTION_TIMEOUT_MS
internal val ConfigRepository.Companion.ROUTE_GROUP_AUTO_TEST_INTERVAL get() = com.kunk.singbox.repository.ROUTE_GROUP_AUTO_TEST_INTERVAL
internal val ConfigRepository.Companion.ROUTE_GROUP_AUTO_TEST_TOLERANCE get() = com.kunk.singbox.repository.ROUTE_GROUP_AUTO_TEST_TOLERANCE
internal val ConfigRepository.Companion.PARALLEL_CONCURRENCY get() = com.kunk.singbox.repository.PARALLEL_CONCURRENCY
internal val ConfigRepository.Companion.SUBSCRIPTION_FAILURE_THRESHOLD get() = com.kunk.singbox.repository.SUBSCRIPTION_FAILURE_THRESHOLD
internal val ConfigRepository.Companion.SUBSCRIPTION_CIRCUIT_BREAKER_WINDOW_MS get() = com.kunk.singbox.repository.SUBSCRIPTION_CIRCUIT_BREAKER_WINDOW_MS
internal val ConfigRepository.Companion.SUBSCRIPTION_RESPONSE_MAX_BYTES get() = com.kunk.singbox.repository.SUBSCRIPTION_RESPONSE_MAX_BYTES
internal val ConfigRepository.Companion.CONFIG_CACHE_EXPIRY_MS get() = com.kunk.singbox.repository.CONFIG_CACHE_EXPIRY_MS
internal val ConfigRepository.Companion.CONFIG_CACHE_CLEANUP_INTERVAL_MINUTES get() = com.kunk.singbox.repository.CONFIG_CACHE_CLEANUP_INTERVAL_MINUTES
internal val ConfigRepository.Companion.REGEX_TRAFFIC get() = com.kunk.singbox.repository.REGEX_TRAFFIC
internal val ConfigRepository.Companion.REGEX_KV_PAIRS get() = com.kunk.singbox.repository.REGEX_KV_PAIRS
internal val ConfigRepository.Companion.REGEX_SUBSCRIPTION_USERINFO get() = com.kunk.singbox.repository.REGEX_SUBSCRIPTION_USERINFO
internal val ConfigRepository.Companion.REGEX_TOTAL get() = com.kunk.singbox.repository.REGEX_TOTAL
internal val ConfigRepository.Companion.REGEX_EXPIRE_DATE get() = com.kunk.singbox.repository.REGEX_EXPIRE_DATE
internal val ConfigRepository.Companion.REGEX_TRAFFIC_VALUE get() = com.kunk.singbox.repository.REGEX_TRAFFIC_VALUE
internal val ConfigRepository.Companion.REGEX_REMAINING get() = com.kunk.singbox.repository.REGEX_REMAINING
internal val ConfigRepository.Companion.REGEX_EXPIRE get() = com.kunk.singbox.repository.REGEX_EXPIRE
internal val ConfigRepository.Companion.REGEX_SANITIZE_UUID get() = com.kunk.singbox.repository.REGEX_SANITIZE_UUID
internal val ConfigRepository.Companion.REGEX_SANITIZE_PASSWORD get() = com.kunk.singbox.repository.REGEX_SANITIZE_PASSWORD
internal val ConfigRepository.Companion.REGEX_SANITIZE_TOKEN get() = com.kunk.singbox.repository.REGEX_SANITIZE_TOKEN
internal val ConfigRepository.Companion.REGEX_WHITESPACE_DASH get() = com.kunk.singbox.repository.REGEX_WHITESPACE_DASH
internal val ConfigRepository.Companion.REGEX_RULE_SET_JSON_KEYS get() = com.kunk.singbox.repository.REGEX_RULE_SET_JSON_KEYS
internal val ConfigRepository.Companion.REGEX_RULE_SET_TEXT_LINE get() = com.kunk.singbox.repository.REGEX_RULE_SET_TEXT_LINE
internal val ConfigRepository.Companion.REGEX_RULE_SET_ERROR_TEXT get() = com.kunk.singbox.repository.REGEX_RULE_SET_ERROR_TEXT
internal val ConfigRepository.Companion.RULE_SET_BINARY_MAGIC get() = com.kunk.singbox.repository.RULE_SET_BINARY_MAGIC
internal val ConfigRepository.Companion.RULE_SET_MIN_SIZE_BYTES get() = com.kunk.singbox.repository.RULE_SET_MIN_SIZE_BYTES
internal val ConfigRepository.Companion.RULE_SET_SNIFF_BYTES get() = com.kunk.singbox.repository.RULE_SET_SNIFF_BYTES
internal val ConfigRepository.Companion.RULE_SET_TEXT_PARSE_LIMIT_BYTES get() = com.kunk.singbox.repository.RULE_SET_TEXT_PARSE_LIMIT_BYTES
internal val ConfigRepository.Companion.RULE_SET_IP_THRESHOLD get() = com.kunk.singbox.repository.RULE_SET_IP_THRESHOLD
internal val ConfigRepository.Companion.IP_DNS_QUERY_TYPES get() = com.kunk.singbox.repository.IP_DNS_QUERY_TYPES
internal val ConfigRepository.Companion.REGEX_IP_CIDR get() = com.kunk.singbox.repository.REGEX_IP_CIDR
internal val ConfigRepository.Companion.REGEX_DOMAIN_LINE get() = com.kunk.singbox.repository.REGEX_DOMAIN_LINE
internal val ConfigRepository.Companion.TYPE_SAVED_PROFILES_DATA get() = com.kunk.singbox.repository.TYPE_SAVED_PROFILES_DATA
internal val ConfigRepository.Companion.TYPE_OUTBOUND_LIST get() = com.kunk.singbox.repository.TYPE_OUTBOUND_LIST
internal val ConfigRepository.Companion.MAX_NODE_ID_CACHE_SIZE get() = com.kunk.singbox.repository.MAX_NODE_ID_CACHE_SIZE
internal val ConfigRepository.Companion.nodeIdCache get() = com.kunk.singbox.repository.nodeIdCache
internal val ConfigRepository.Companion.REGEX_HTML_SUBSCRIPTION_INPUT get() = com.kunk.singbox.repository.REGEX_HTML_SUBSCRIPTION_INPUT
internal val ConfigRepository.Companion.REGEX_HTML_INPUT_VALUE get() = com.kunk.singbox.repository.REGEX_HTML_INPUT_VALUE
internal val ConfigRepository.Companion.USER_AGENTS get() = com.kunk.singbox.repository.USER_AGENTS
internal val ConfigRepository.Companion.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG get() = com.kunk.singbox.repository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG

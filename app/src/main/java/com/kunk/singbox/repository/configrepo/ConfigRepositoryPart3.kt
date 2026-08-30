@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.repository

import android.util.Base64
import android.util.Log
import com.kunk.singbox.R
import com.kunk.singbox.model.*
import com.kunk.singbox.utils.parser.NodeLinkParser
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal suspend fun ConfigRepository.importFromContent(
    name: String,
    content: String,
    profileType: ProfileType = ProfileType.Imported,
    onProgress: (String) -> Unit = {}): Result<ProfileUi> = withContext(Dispatchers.IO) {
    var profileId: String? = null
    try {
        onProgress(context.getString(R.string.common_loading))

        val normalized = normalizeImportedContent(content)
        ConfigRepository.findUnsupportedAndroidCapabilityInJson(normalized)?.let { message ->
            return@withContext Result.failure(IllegalArgumentException(message))
        }
        if (ConfigRepository.looksLikeSubscriptionUrlForImport(normalized)) {
            return@withContext importFromSubscription(
                name = name,
                url = normalized,
                onProgress = onProgress
            )
        }

        val config = subscriptionManager.parse(normalized)
            ?: return@withContext Result.failure(Exception(context.getString(R.string.profiles_parse_failed)))

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
            name = name,
            type = profileType,
            url = null,
            lastUpdated = System.currentTimeMillis(),
            enabled = true,
            updateStatus = UpdateStatus.Idle
        )

        cacheConfig(profileId, deduplicatedConfig)
        profileNodes[profileId] = nodes
        updateAllNodesAndGroups()

        _profiles.update { it + profile }
        saveProfiles()

        if (_activeProfileId.value == null) {
            setActiveProfile(profileId)
        }

        onProgress(context.getString(R.string.profiles_import_success, nodes.size.toString()))

        Result.success(profile)
    } catch (e: Exception) {
        profileId?.let { rollbackTransientProfileFile(it) }
        Log.e(ConfigRepository.TAG, "Failed to import profile from content", e)
        Result.failure(e)
    }
}

internal fun ConfigRepository.normalizeImportedContent(content: String): String {
    val trimmed = content.trim().trimStart('\uFEFF')
    val lines = trimmed.lines().toMutableList()

    fun isFenceLine(line: String): Boolean {
        val t = line.trim()
        if (t.startsWith("```")) return true
        return t.length >= 2 && t.all { it == '`' }
    }

    if (lines.isNotEmpty() && isFenceLine(lines.first())) {
        lines.removeAt(0)
    }
    if (lines.isNotEmpty() && isFenceLine(lines.last())) {
        lines.removeAt(lines.lastIndex)
    }

    return lines.joinToString("\n").trim()
}

internal fun ConfigRepository.tryDecodeBase64(content: String): String? {
    val s = content.trim().trimStart('\uFEFF')
    if (s.isBlank()) return null
    val candidates = arrayOf(
        Base64.DEFAULT,
        Base64.NO_WRAP,
        Base64.URL_SAFE or Base64.NO_WRAP,
        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    )
    for (flags in candidates) {
        try {
            val decoded = Base64.decode(s, flags)
            val text = String(decoded)
            if (text.isNotBlank()) return text
        } catch (e: Exception) {
            Log.v(ConfigRepository.TAG, "Base64 decode attempt failed with flags=$flags", e)
        }
    }
    return null
}

internal fun ConfigRepository.extractOutboundsOnly(config: SingBoxConfig): SingBoxConfig {
    val normalizedConfig = ConfigRepository.normalizeWireGuardEndpointsForInternalUse(config)
    val outbounds = normalizedConfig.outbounds ?: normalizedConfig.proxies ?: emptyList()
    return SingBoxConfig(outbounds = outbounds)
}

internal fun ConfigRepository.extractOutboundsFromJson(jsonContent: String): List<Outbound>? {
    val trimmed = jsonContent.trim()
    if (!trimmed.startsWith("{")) return null

    return try {
        val parsedConfig = gson.fromJson(trimmed, SingBoxConfig::class.java)
        val normalizedConfig = ConfigRepository.normalizeWireGuardEndpointsForInternalUse(parsedConfig)
        normalizedConfig.outbounds?.takeIf(List<Outbound>::isNotEmpty)
            ?: normalizedConfig.proxies?.takeIf(List<Outbound>::isNotEmpty)
    } catch (e: Exception) {
        Log.w(ConfigRepository.TAG, "extractOutboundsFromJson failed: ${e.message}")
        null
    }
}

internal fun ConfigRepository.sanitizeSubscriptionSnippet(content: String): String {
    val snippet = content.take(200)
    return ConfigRepository.REGEX_SANITIZE_UUID.replace(
        ConfigRepository.REGEX_SANITIZE_PASSWORD.replace(
            ConfigRepository.REGEX_SANITIZE_TOKEN.replace(snippet, "token=***"),
            "password=***"
        ),
        "uuid=***"
    )
}

internal fun ConfigRepository.parseClashYamlConfig(content: String): SingBoxConfig? {
    return if (clashYamlParser.canParse(content)) {
        clashYamlParser.parse(content)
    } else {
        null
    }
}

internal fun ConfigRepository.parseSubscriptionResponse(content: String): SingBoxConfig? {
    val normalizedContent = normalizeImportedContent(content)
    try {
        val outbounds = extractOutboundsFromJson(normalizedContent)
        if (outbounds != null && outbounds.isNotEmpty()) {
            return SingBoxConfig(outbounds = outbounds)
        } else {
            Log.w(ConfigRepository.TAG, "Parsed as JSON but outbounds/proxies is empty/null. content snippet: ${sanitizeSubscriptionSnippet(normalizedContent)}")
        }
    } catch (e: Exception) {
        Log.w(ConfigRepository.TAG, "Failed to extract outbounds from JSON: ${e.message}")
    }
    try {
        val yamlConfig = parseClashYamlConfig(normalizedContent)
        if (yamlConfig?.outbounds != null && yamlConfig.outbounds.isNotEmpty()) {
            return extractOutboundsOnly(yamlConfig)
        }
    } catch (_: Exception) {
    }
    try {
        val decoded = tryDecodeBase64(normalizedContent)
        if (decoded.isNullOrBlank()) {
            throw IllegalStateException("base64 decode failed")
        }
        try {
            val outbounds = extractOutboundsFromJson(decoded)
            if (outbounds != null && outbounds.isNotEmpty()) {
                return SingBoxConfig(outbounds = outbounds)
            } else {
                Log.w(ConfigRepository.TAG, "Parsed decoded Base64 as JSON but outbounds is empty/null")
            }
        } catch (e: Exception) {
            Log.w(ConfigRepository.TAG, "Failed to extract outbounds from decoded Base64 JSON: ${e.message}")
        }

        try {
            val yamlConfig = parseClashYamlConfig(decoded)
            if (yamlConfig?.outbounds != null && yamlConfig.outbounds.isNotEmpty()) {
                return extractOutboundsOnly(yamlConfig)
            }
        } catch (_: Exception) {
        }
    } catch (e: Exception) {
    }
    try {
        val lines = normalizedContent.trim().lines().filter { it.isNotBlank() }
        if (lines.isNotEmpty()) {
            val decoded = tryDecodeBase64(normalizedContent) ?: normalizedContent

            val decodedLines = decoded.trim().lines().filter { it.isNotBlank() }
            val outbounds = mutableListOf<Outbound>()

            for (line in decodedLines) {
                val cleanedLine = line.trim()
                    .removePrefix("- ")
                    .removePrefix("\"")
                    .trim()
                    .trim('`', '"', '\'')
                val outbound = parseNodeLink(cleanedLine)
                if (outbound != null) {
                    outbounds.add(outbound)
                }
            }

            if (outbounds.isNotEmpty()) {
                return SingBoxConfig(
                    outbounds = outbounds
                )
            }
        }
    } catch (e: Exception) {
        Log.w(ConfigRepository.TAG, "Failed to parse subscription response as node links", e)
    }

    return null
}

internal fun ConfigRepository.parseNodeLink(link: String): Outbound? {
    return nodeLinkParser.parse(link)
}

internal fun ConfigRepository.parseNodeLinkForCustomProfile(link: String): Result<Outbound> {
    val normalizedLink = link.trim()
    if (!NodeLinkParser.isSupportedLink(normalizedLink)) {
        return Result.failure(Exception(context.getString(R.string.nodes_unsupported_format)))
    }
    return runCatching {
        parseNodeLink(normalizedLink)
            ?: throw IllegalArgumentException(context.getString(R.string.nodes_add_failed))
    }
}

internal suspend fun ConfigRepository.extractNodesFromConfig(
    config: SingBoxConfig,
    profileId: String,
    onProgress: ((String) -> Unit)? = null): List<NodeUi> {
    val outbounds = ConfigRepository.normalizeWireGuardEndpointsForInternalUse(config).outbounds
        ?: return emptyList()
    val trafficRepo = withContext(Dispatchers.IO) {
        TrafficRepository.getInstance(context)
    }
    return withContext(Dispatchers.Default) {
        val groupOutbounds = outbounds.filter {
            it.type == "selector" || it.type == "urltest"
        }
        val nodeToGroup = mutableMapOf<String, String>()
        groupOutbounds.forEach { group ->
            group.outbounds?.forEach { nodeName ->
                nodeToGroup[nodeName] = group.tag
            }
        }
        val proxyTypes = setOf(
            "shadowsocks", "vmess", "vless", "trojan",
            "hysteria", "hysteria2", "tuic", "wireguard",
            "shadowtls", "ssh", "anytls", "naive", "http", "socks"
        )
        val detourTags = outbounds.mapNotNull { it.detour }.toSet()

        val validOutbounds = outbounds.filter {
            it.type in proxyTypes && it.tag !in detourTags
        }
        if (validOutbounds.isEmpty()) return@withContext emptyList()

        val total = validOutbounds.size
        val completed = AtomicInteger(0)
        val semaphore = Semaphore(ConfigRepository.PARALLEL_CONCURRENCY)

        val deferredNodes = validOutbounds.map { outbound ->
            async {
                semaphore.withPermit {
                    val node = createNodeUi(outbound, profileId, nodeToGroup, trafficRepo)
                    val done = completed.incrementAndGet()
                    if (done % 100 == 0 || done == total) {
                        onProgress?.invoke(context.getString(R.string.profiles_extracting_nodes, done, total))
                    }
                    node
                }
            }
        }

        deferredNodes.awaitAll().filterNotNull()
    }
}

internal fun ConfigRepository.extractNodesFromConfigSync(
    config: SingBoxConfig,
    profileId: String
): List<NodeUi> {
    val outbounds = ConfigRepository.normalizeWireGuardEndpointsForInternalUse(config).outbounds
        ?: return emptyList()
    val trafficRepo = TrafficRepository.getInstance(context)
    val groupOutbounds = outbounds.filter {
        it.type == "selector" || it.type == "urltest"
    }
    val nodeToGroup = mutableMapOf<String, String>()
    groupOutbounds.forEach { group ->
        group.outbounds?.forEach { nodeName ->
            nodeToGroup[nodeName] = group.tag
        }
    }
    val proxyTypes = setOf(
        "shadowsocks", "vmess", "vless", "trojan",
        "hysteria", "hysteria2", "tuic", "wireguard",
        "shadowtls", "ssh", "anytls", "naive", "http", "socks"
    )
    val detourTags = outbounds.mapNotNull { it.detour }.toSet()

    val validOutbounds = outbounds.filter {
        it.type in proxyTypes && it.tag !in detourTags
    }
    if (validOutbounds.isEmpty()) return emptyList()

    return validOutbounds.mapNotNull { outbound ->
        createNodeUi(outbound, profileId, nodeToGroup, trafficRepo)
    }
}

/** 订阅信息位 / 占位地址，不当作可测节点。 */
internal fun ConfigRepository.isPlaceholderNodeServer(server: String): Boolean {
    return when {
        server.equals("localhost", ignoreCase = true) -> true
        server in PLACEHOLDER_NODE_SERVERS -> true
        else -> false
    }
}

internal fun ConfigRepository.createNodeUi(
    outbound: Outbound,
    profileId: String,
    nodeToGroup: Map<String, String>,
    trafficRepo: TrafficRepository
): NodeUi? {
    if (outbound.tag.isBlank()) return null

    // 订阅信息位（剩余流量/套餐到期）常写成 127.0.0.1，不当作可测节点
    val server = outbound.server?.trim().orEmpty()
    if (isPlaceholderNodeServer(server)) {
        return null
    }

    var group = nodeToGroup[outbound.tag] ?: "Default"
    if (group.contains("://") || group.length > 50) {
        group = "Default"
    }

    val id = ConfigRepository.stableNodeId(profileId, outbound.tag)
    val meteredProtected = isNodeMeteredProtected(id)

    return NodeUi(
        id = id,
        name = outbound.tag,
        protocol = outbound.type,
        group = group,
        latencyMs = savedLatencyMs(id),
        isFavorite = false,
        sourceProfileId = profileId,
        trafficUsed = trafficRepo.getMonthlyTotal(id),
        autoSelectionEligible = isNodeAutoSelectionEligible(id) && !meteredProtected,
        meteredProtected = meteredProtected,
        hasDetour = !outbound.detour.isNullOrBlank(),
        tags = buildList {
            outbound.tls?.let {
                if (it.enabled == true) add("TLS")
                it.reality?.let { r -> if (r.enabled == true) add("Reality") }
            }
            outbound.transport?.type?.let { add(it.uppercase()) }
        }
    )
}

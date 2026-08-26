package com.kunk.singbox.repository

import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import java.security.MessageDigest
import java.util.UUID

@Keep
internal data class RuntimeNodeRef(
    val nodeId: String,
    val nodeName: String,
    val meteredProtected: Boolean = false,
    val explicitRouteAuthorized: Boolean = false
)

@Keep
internal data class RuntimeNodeMappingSnapshot(
    val configSha256: String,
    val mappings: Map<String, RuntimeNodeRef>
)

@Keep
internal data class PendingManualNodeSelection(
    val token: String,
    val nodeId: String,
    val expiresAtEpochMs: Long
)

internal fun runtimeConfigFingerprint(content: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal fun buildRuntimeNodeMappingSnapshot(
    previous: RuntimeNodeMappingSnapshot?,
    mapping: Map<String, RuntimeNodeRef>,
    configContent: String
): RuntimeNodeMappingSnapshot {
    return RuntimeNodeMappingSnapshot(
        configSha256 = runtimeConfigFingerprint(configContent),
        // 保留旧 tag，直到旧连接关闭，避免配置切换窗口失去真实归属。
        mappings = previous?.mappings.orEmpty() + mapping
    )
}

internal fun authorizationAfterProtectionUpdate(
    previousAuthorizedNodeId: String?,
    oldNodeId: String,
    updatedNodeId: String,
    wasProtected: Boolean,
    isProtected: Boolean
): String? {
    if (previousAuthorizedNodeId != oldNodeId) return previousAuthorizedNodeId
    return updatedNodeId.takeIf { wasProtected && isProtected }
}

/** 计费节点保护状态及运行时 tag 映射，主进程与 VPN 进程共同读取。 */
@Suppress("TooManyFunctions")
internal object NodeProtectionStore {
    private const val PROTECTION_MMKV_ID = "node_metered_protection"
    private const val RUNTIME_MMKV_ID = "runtime_outbound_nodes"
    private const val MANUAL_AUTHORIZATION_KEY = "manual_authorized_node_id"
    private const val PENDING_MANUAL_SELECTION_KEY = "pending_manual_selection"
    private const val RUNTIME_MAPPING_KEY = "runtime_mapping"
    private const val RUNTIME_CANDIDATE_PREFIX = "runtime_candidate_"
    private const val PENDING_MANUAL_SELECTION_TTL_MS = 60_000L

    private val gson = Gson()
    private val runtimeMapType = object : TypeToken<Map<String, RuntimeNodeRef>>() {}.type
    private val runtimeSnapshotType = object : TypeToken<RuntimeNodeMappingSnapshot>() {}.type

    private val protectionMmkv: MMKV by lazy {
        MMKV.mmkvWithID(PROTECTION_MMKV_ID, MMKV.MULTI_PROCESS_MODE)
    }

    private val runtimeMmkv: MMKV by lazy {
        MMKV.mmkvWithID(RUNTIME_MMKV_ID, MMKV.MULTI_PROCESS_MODE)
    }

    fun isProtected(nodeId: String): Boolean {
        return nodeId.isNotBlank() && protectionMmkv.decodeBool(nodeId, false)
    }

    fun protectedNodeIds(): Set<String> {
        return protectionMmkv.allKeys()
            .orEmpty()
            .asSequence()
            .filter {
                it != MANUAL_AUTHORIZATION_KEY &&
                    it != PENDING_MANUAL_SELECTION_KEY &&
                    protectionMmkv.decodeBool(it, false)
            }
            .toSet()
    }

    fun setProtected(nodeId: String, protected: Boolean): Boolean {
        if (nodeId.isBlank()) return false
        val written = protectionMmkv.encode(nodeId, protected)
        if (!protected && manuallyAuthorizedNodeId() == nodeId) {
            clearManualAuthorization()
        }
        return written
    }

    fun removeNode(nodeId: String) {
        if (nodeId.isBlank()) return
        protectionMmkv.removeValueForKey(nodeId)
        if (manuallyAuthorizedNodeId() == nodeId) {
            clearManualAuthorization()
        }
        if (readPendingManualSelection()?.nodeId == nodeId) {
            protectionMmkv.removeValueForKey(PENDING_MANUAL_SELECTION_KEY)
        }
    }

    fun migrateNode(oldNodeId: String, newNodeId: String) {
        if (oldNodeId == newNodeId || oldNodeId.isBlank() || newNodeId.isBlank()) return
        val protected = isProtected(oldNodeId)
        if (protected) {
            check(protectionMmkv.encode(newNodeId, true)) {
                "Failed to migrate metered node protection"
            }
        }
        protectionMmkv.removeValueForKey(oldNodeId)
        if (manuallyAuthorizedNodeId() == oldNodeId) {
            authorizeManualNode(newNodeId)
        }
    }

    fun manuallyAuthorizedNodeId(): String? {
        return protectionMmkv.decodeString(MANUAL_AUTHORIZATION_KEY, null)
            ?.takeIf(String::isNotBlank)
    }

    fun authorizeManualNode(nodeId: String?) {
        if (nodeId.isNullOrBlank()) {
            clearManualAuthorization()
        } else {
            check(protectionMmkv.encode(MANUAL_AUTHORIZATION_KEY, nodeId)) {
                "Failed to persist metered node authorization"
            }
        }
    }

    fun clearManualAuthorization() {
        protectionMmkv.removeValueForKey(MANUAL_AUTHORIZATION_KEY)
    }

    /** 为一次明确的手动点击建立短期授权，成功提交或失败回滚后立即清除。 */
    fun beginManualSelection(nodeId: String, nowEpochMs: Long = System.currentTimeMillis()): String {
        require(nodeId.isNotBlank()) { "Manual selection node ID is blank" }
        val pending = PendingManualNodeSelection(
            token = UUID.randomUUID().toString(),
            nodeId = nodeId,
            expiresAtEpochMs = nowEpochMs + PENDING_MANUAL_SELECTION_TTL_MS
        )
        check(protectionMmkv.encode(PENDING_MANUAL_SELECTION_KEY, gson.toJson(pending))) {
            "Failed to persist pending manual node selection"
        }
        return pending.token
    }

    fun commitManualSelection(token: String, nodeId: String, protected: Boolean) {
        val pending = pendingManualSelection()
        check(pending?.token == token && pending.nodeId == nodeId) {
            "Pending manual node selection is missing or expired"
        }
        if (protected) {
            authorizeManualNode(nodeId)
        } else {
            clearManualAuthorization()
        }
        protectionMmkv.removeValueForKey(PENDING_MANUAL_SELECTION_KEY)
    }

    fun cancelManualSelection(token: String) {
        val pending = readPendingManualSelection() ?: return
        if (pending.token == token) {
            protectionMmkv.removeValueForKey(PENDING_MANUAL_SELECTION_KEY)
        }
    }

    fun pendingManualSelectionNodeId(): String? = pendingManualSelection()?.nodeId

    fun effectiveSelectedNodeId(selectedNodeId: String?): String? {
        return pendingManualSelectionNodeId() ?: selectedNodeId?.takeIf(String::isNotBlank)
    }

    fun authorizedManualNodeId(nodeId: String): String? {
        return nodeId.takeIf {
            manuallyAuthorizedNodeId() == nodeId || pendingManualSelectionNodeId() == nodeId
        }
    }

    fun isUseAuthorized(nodeId: String, activeNodeId: String?, autoSelectionEnabled: Boolean): Boolean {
        if (!isProtected(nodeId)) return true
        return !autoSelectionEnabled && nodeId == activeNodeId && authorizedManualNodeId(nodeId) == nodeId
    }

    fun isRuntimeUseAuthorized(nodeId: String, selectedNodeId: String?): Boolean {
        if (!isProtected(nodeId)) return true
        return nodeId == effectiveSelectedNodeId(selectedNodeId) && authorizedManualNodeId(nodeId) == nodeId
    }

    fun isRuntimeRefAuthorized(ref: RuntimeNodeRef, selectedNodeId: String?): Boolean {
        return ref.explicitRouteAuthorized || isRuntimeUseAuthorized(ref.nodeId, selectedNodeId)
    }

    fun replaceRuntimeMappings(mapping: Map<String, RuntimeNodeRef>, configContent: String): Boolean {
        val snapshot = buildRuntimeNodeMappingSnapshot(
            previous = runtimeMappingSnapshot(),
            mapping = mapping,
            configContent = configContent
        )
        return runtimeMmkv.encode(RUNTIME_MAPPING_KEY, gson.toJson(snapshot))
    }

    fun stageRuntimeMappings(
        requestId: String,
        mapping: Map<String, RuntimeNodeRef>,
        configContent: String
    ): Boolean {
        if (requestId.isBlank()) return false
        val snapshot = RuntimeNodeMappingSnapshot(
            configSha256 = runtimeConfigFingerprint(configContent),
            mappings = mapping
        )
        return runtimeMmkv.encode(candidateKey(requestId), gson.toJson(snapshot))
    }

    fun activateStagedRuntimeMappings(requestId: String, configContent: String): Boolean {
        if (requestId.isBlank()) return true
        val snapshot = readCandidateSnapshot(requestId) ?: return false
        if (snapshot.configSha256 != runtimeConfigFingerprint(configContent)) return false
        val activated = replaceRuntimeMappings(snapshot.mappings, configContent)
        if (activated) runtimeMmkv.removeValueForKey(candidateKey(requestId))
        return activated
    }

    fun discardStagedRuntimeMappings(requestId: String) {
        if (requestId.isNotBlank()) runtimeMmkv.removeValueForKey(candidateKey(requestId))
    }

    fun runtimeMappings(): Map<String, RuntimeNodeRef> {
        return runtimeMappingSnapshot()?.mappings.orEmpty()
    }

    fun runtimeConfigMatches(configContent: String): Boolean {
        val snapshot = runtimeMappingSnapshot() ?: return false
        return snapshot.configSha256.isNotBlank() && snapshot.configSha256 == runtimeConfigFingerprint(configContent)
    }

    private fun pendingManualSelection(nowEpochMs: Long = System.currentTimeMillis()): PendingManualNodeSelection? {
        val pending = readPendingManualSelection() ?: return null
        if (pending.nodeId.isBlank() || pending.token.isBlank() || pending.expiresAtEpochMs <= nowEpochMs) {
            protectionMmkv.removeValueForKey(PENDING_MANUAL_SELECTION_KEY)
            return null
        }
        return pending
    }

    private fun readPendingManualSelection(): PendingManualNodeSelection? {
        val json = protectionMmkv.decodeString(PENDING_MANUAL_SELECTION_KEY, null) ?: return null
        return runCatching { gson.fromJson(json, PendingManualNodeSelection::class.java) }.getOrNull()
    }

    private fun candidateKey(requestId: String): String = "$RUNTIME_CANDIDATE_PREFIX$requestId"

    private fun readCandidateSnapshot(requestId: String): RuntimeNodeMappingSnapshot? {
        val json = runtimeMmkv.decodeString(candidateKey(requestId), null) ?: return null
        return runCatching { gson.fromJson(json, RuntimeNodeMappingSnapshot::class.java) }.getOrNull()
    }

    private fun runtimeMappingSnapshot(): RuntimeNodeMappingSnapshot? {
        val json = runtimeMmkv.decodeString(RUNTIME_MAPPING_KEY, null) ?: return null
        return runCatching {
            val root = JsonParser.parseString(json)
            if (root.isJsonObject && root.asJsonObject.has("mappings")) {
                gson.fromJson<RuntimeNodeMappingSnapshot>(root, runtimeSnapshotType)
            } else {
                RuntimeNodeMappingSnapshot(
                    configSha256 = "",
                    mappings = gson.fromJson<Map<String, RuntimeNodeRef>>(root, runtimeMapType).orEmpty()
                )
            }
        }.getOrNull()
    }
}

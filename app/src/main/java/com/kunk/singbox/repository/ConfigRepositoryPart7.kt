package com.kunk.singbox.repository

import com.kunk.singbox.R
import android.content.Context
import android.util.Log
import com.kunk.singbox.model.*
import com.kunk.singbox.repository.config.NodeLinkExporter
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Suppress("TooManyFunctions")
open class ConfigRepositoryPart7(context: Context) : ConfigRepositoryPart6(context) {
    override suspend fun addSingleNode(
        link: String,
        targetProfileId: String?,
        newProfileName: String?): Result<NodeUi> = withContext(Dispatchers.IO) {
        var createdProfileId: String? = null
        try {
            val outbound = parseNodeLink(link.trim())
                ?: return@withContext Result.failure(Exception("Failed to parse node link"))

            val profileId: String
            val existingConfig: SingBoxConfig?
            var isNewProfile = false

            when {
                targetProfileId != null -> {
                    val profile = _profiles.value.find { it.id == targetProfileId }
                    if (profile == null) {
                        return@withContext Result.failure(Exception("Profile not found"))
                    }
                    profileId = targetProfileId
                    existingConfig = loadConfig(profileId)
                }
                newProfileName != null -> {
                    profileId = UUID.randomUUID().toString()
                    existingConfig = null
                    isNewProfile = true
                    createdProfileId = profileId
                }
                else -> {
                    val manualProfileName = "Manual"
                    val manualProfile = _profiles.value.find { it.name == manualProfileName && it.type == ProfileType.Imported }
                    if (manualProfile != null) {
                        profileId = manualProfile.id
                        existingConfig = loadConfig(profileId)
                    } else {
                        profileId = UUID.randomUUID().toString()
                        existingConfig = null
                        isNewProfile = true
                        createdProfileId = profileId
                    }
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
            val nodes = extractNodesFromConfig(newConfig, profileId)
            profileNodes[profileId] = nodes

            if (isNewProfile || existingConfig == null) {
                val profileName = newProfileName ?: "Manual"
                val newProfile = ProfileUi(
                    id = profileId,
                    name = profileName,
                    type = ProfileType.Imported,
                    url = null,
                    lastUpdated = System.currentTimeMillis(),
                    enabled = true,
                    updateStatus = UpdateStatus.Idle
                )
                _profiles.update { it + newProfile }
            } else {
                _profiles.update { list ->
                    list.map { if (it.id == profileId) it.copy(lastUpdated = System.currentTimeMillis()) else it }
                }
            }

            updateAllNodesAndGroups()

            setActiveProfile(profileId)
            val addedNode = nodes.find { it.name == finalTag }
            if (addedNode != null) {
                _activeNodeId.value = addedNode.id
            }

            saveProfiles()

            Log.i(ConfigRepository.TAG, "Added single node: $finalTag to profile $profileId")

            Result.success(addedNode ?: nodes.last())
        } catch (e: Exception) {
            createdProfileId?.let { rollbackTransientProfileFile(it) }
            Log.e(ConfigRepository.TAG, "Failed to add single node", e)
            Result.failure(Exception(context.getString(R.string.nodes_add_failed) + ": ${e.message}"))
        }
    }

    override suspend fun renameNode(nodeId: String, newName: String) = withContext(Dispatchers.IO) {
        val node = _nodes.value.find { it.id == nodeId } ?: return@withContext
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return@withContext
        val newOutbounds = config.outbounds?.map {
            if (it.tag == node.name) it.copy(tag = newName) else it
        }
        var newConfig = config.copy(outbounds = newOutbounds)
        newConfig = deduplicateTags(newConfig)
        cacheConfig(profileId, newConfig)
        writeConfigFileOrThrow(profileId, newConfig)

        refreshNodesAfterNodeMutation(
            profileId = profileId,
            oldNodeId = nodeId,
            newTag = newName,
            newConfig = newConfig
        )
    }

    override suspend fun updateNode(nodeId: String, newOutbound: Outbound) = withContext(Dispatchers.IO) {
        val node = _nodes.value.find { it.id == nodeId } ?: return@withContext
        val profileId = node.sourceProfileId
        val config = loadConfig(profileId) ?: return@withContext
        val newOutbounds = config.outbounds?.map {
            if (it.tag == node.name) newOutbound else it
        }
        var newConfig = config.copy(outbounds = newOutbounds)
        newConfig = deduplicateTags(newConfig)
        cacheConfig(profileId, newConfig)
        writeConfigFileOrThrow(profileId, newConfig)

        refreshNodesAfterNodeMutation(
            profileId = profileId,
            oldNodeId = nodeId,
            newTag = newOutbound.tag,
            newConfig = newConfig
        )
    }

    protected override fun refreshNodesAfterNodeMutation(
        profileId: String,
        oldNodeId: String,
        newTag: String,
        newConfig: SingBoxConfig
    ) {
        val oldNodes = profileNodes[profileId] ?: _nodes.value
        val latencyById = oldNodes.associate { it.id to it.latencyMs }
        val updatedNodeId = ConfigRepository.stableNodeId(profileId, newTag)
        val originalLatency = oldNodes.find { it.id == oldNodeId }?.latencyMs
        scope.launch {
            val newNodes = extractNodesFromConfig(newConfig, profileId)
            val mergedNodes = mergeMutatedNodeLatencies(
                newNodes = newNodes,
                latencyById = latencyById,
                updatedNodeId = updatedNodeId,
                originalLatency = originalLatency
            )
            profileNodes[profileId] = mergedNodes
            updateAllNodesAndGroups()
            applyMutatedActiveNode(profileId, oldNodeId, newTag, mergedNodes)
            saveProfiles()
        }
    }

    protected override fun mergeMutatedNodeLatencies(
        newNodes: List<NodeUi>,
        latencyById: Map<String, Long?>,
        updatedNodeId: String,
        originalLatency: Long?
    ): List<NodeUi> {
        return newNodes.map { nodeItem ->
            val storedLatency = latencyById[nodeItem.id]
                ?: if (nodeItem.id == updatedNodeId) originalLatency else null
            if (storedLatency != null) nodeItem.copy(latencyMs = storedLatency) else nodeItem
        }
    }

    protected override fun applyMutatedActiveNode(
        profileId: String,
        oldNodeId: String,
        newTag: String,
        mergedNodes: List<NodeUi>
    ) {
        if (_activeProfileId.value != profileId) return

        _nodes.value = mergedNodes
        if (_activeNodeId.value != oldNodeId) return

        val newNode = mergedNodes.find { it.name == newTag }
        if (newNode != null) {
            _activeNodeId.value = newNode.id
        }
    }

    override suspend fun exportNode(nodeId: String): String? = withContext(Dispatchers.IO) {
        val node = _nodes.value.find { it.id == nodeId } ?: run {
            Log.e(ConfigRepository.TAG, "exportNode: Node not found in UI list: $nodeId")
            return@withContext null
        }

        val config = loadConfig(node.sourceProfileId) ?: run {
            Log.e(ConfigRepository.TAG, "exportNode: Config not found for profile: ${node.sourceProfileId}")
            return@withContext null
        }

        val outbound = config.outbounds?.find { it.tag == node.name } ?: run {
            Log.e(ConfigRepository.TAG, "exportNode: Outbound not found in config with tag: ${node.name}")
            return@withContext null
        }

        NodeLinkExporter.export(outbound, gson)
    }

    protected override fun deduplicateTags(config: SingBoxConfig): SingBoxConfig {
        val outbounds = config.outbounds ?: return config
        val seenTags = mutableSetOf<String>()

        val newOutbounds = outbounds.map { outbound ->
            var tag = outbound.tag
            if (tag.isBlank()) {
                tag = "unnamed"
            }

            var newTag = tag
            var counter = 1
            while (seenTags.contains(newTag)) {
                newTag = "${tag}_$counter"
                counter++
            }

            seenTags.add(newTag)

            if (newTag != outbound.tag) {
                outbound.copy(tag = newTag)
            } else {
                outbound
            }
        }

        return config.copy(outbounds = newOutbounds)
    }

    protected override fun findAvailablePort(startPort: Int): Int {
        for (port in startPort until startPort + 100) {
            try {
                java.net.ServerSocket(port).use {
                    return port
                }
            } catch (_: Exception) {
            }
        }
        return startPort
    }

    override fun cleanup() {
        scope.cancel()
        cacheCleanupScheduler.shutdownNow()
        ConfigRepository.nodeIdCache.clear()
        configCache.clear()
        configCacheAccessTimes.clear()
        profileNodes.clear()
        savedNodeLatencies.clear()
        inFlightLatencyTests.clear()
        Log.i(ConfigRepository.TAG, "ConfigRepository cleanup completed")
    }

    protected override fun isIpAddress(address: String?): Boolean {
        return ConfigRepository.isIpAddressValue(address)
    }
}

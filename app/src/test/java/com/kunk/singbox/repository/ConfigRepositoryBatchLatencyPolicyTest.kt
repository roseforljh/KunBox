package com.kunk.singbox.repository

import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.NodeUi
import com.kunk.singbox.model.Outbound
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigRepositoryBatchLatencyPolicyTest {

    @Test
    fun nodeTestInfoLoadsEachProfileOnceAndUsesTagIndex() {
        val nodes = listOf(
            node(id = "a-1", name = "alpha", profileId = "profile-a"),
            node(id = "a-2", name = "beta", profileId = "profile-a"),
            node(id = "b-1", name = "gamma", profileId = "profile-b")
        )
        val loadCount = mutableMapOf<String, Int>()

        val infos = ConfigRepository.buildNodeTestInfosFromContexts(nodes) { profileId ->
            loadCount[profileId] = loadCount.getOrDefault(profileId, 0) + 1
            when (profileId) {
                "profile-a" -> runtimeContext("alpha", "beta")
                "profile-b" -> runtimeContext("gamma")
                else -> null
            }
        }

        assertEquals(mapOf("profile-a" to 1, "profile-b" to 1), loadCount)
        assertEquals(listOf("a-1", "a-2", "b-1"), infos.map { it.nodeId })
        assertEquals(listOf("alpha", "beta"), infos.first().allOutbounds.map { it.tag })
    }

    @Test
    fun latencyResultsAreMergedIntoNodeListInOnePass() {
        val nodes = listOf(
            node(id = "one", name = "one", profileId = "profile"),
            node(id = "two", name = "two", profileId = "profile"),
            node(id = "three", name = "three", profileId = "profile")
        )

        val updated = ConfigRepository.applyLatencyResultsToNodes(
            nodes,
            mapOf("one" to 12L, "three" to -1L)
        )

        assertEquals(listOf(12L, null, -1L), updated.map { it.latencyMs })
    }

    @Test
    fun displayedLatencyIsKeptUntilUserClearsOrRetests() {
        val source = File(
            "src/main/java/com/kunk/singbox/repository/configrepo/ConfigRepositoryPart3.kt"
        ).readText()

        assertTrue(source.contains("latencyMs = savedLatencyMs(id)"))
        assertFalse(source.contains("refreshExpiredNodeLatencies"))
        assertFalse(source.contains("LATENCY_EXPIRY_REFRESH_INTERVAL_MS"))
    }

    @Test
    fun clearingAllLatenciesAlsoClearsRoomPersistence() {
        val repositorySource = File(
            "src/main/java/com/kunk/singbox/repository/configrepo/ConfigRepositoryPart5.kt"
        ).readText()
        val daoSource = File(
            "src/main/java/com/kunk/singbox/database/dao/NodeLatencyDao.kt"
        ).readText()

        assertTrue(repositorySource.contains("nodeLatencyDao.deleteAll()"))
        assertTrue(daoSource.contains("suspend fun deleteAll()"))
    }

    @Test
    fun batchLatencyPersistenceIsNotRepeatedByProfileSave() {
        val saveProfilesBody = File(
            "src/main/java/com/kunk/singbox/repository/configrepo/ConfigRepositoryPart1.kt"
        ).readText()
            .substringAfter("internal suspend fun ConfigRepository.saveProfilesInternal()")
            .substringBefore("internal fun ConfigRepository.writeConfigFileOrThrow")
        val batchLatencyBody = File(
            "src/main/java/com/kunk/singbox/repository/configrepo/ConfigRepositoryPart5.kt"
        ).readText()
            .substringAfter("internal suspend fun ConfigRepository.testAllNodesLatency(")
            .substringBefore("internal suspend fun ConfigRepository.updateAllProfiles()")

        assertFalse(saveProfilesBody.contains("nodeLatencyDao.insertAll"))
        assertFalse(batchLatencyBody.contains("saveProfiles()"))
    }

    @Test
    fun latencyProbeTagsAreUniqueForSameNamedOutbounds() {
        val first = ConfigRepository.buildLatencyProbeTag("node-a")
        val second = ConfigRepository.buildLatencyProbeTag("node-b")

        assertNotEquals(first, second)
    }

    @Test
    fun latencyRuntimeLoadsCrossProfileDetourAndRewritesReference() {
        val source = listOf(
            Outbound(type = "vless", tag = "tested", detour = "profile-b::front")
        )

        val resolved = ConfigRepository.resolveLatencyRuntimeDetours(
            sourceProfileId = "profile-a",
            sourceOutbounds = source
        ) { profileId ->
            when (profileId) {
                "profile-b" -> listOf(Outbound(type = "socks", tag = "front"))
                else -> null
            }
        }

        assertEquals("front", resolved.first { it.tag == "tested" }.detour)
        assertTrue(resolved.any { it.tag == "front" && it.type == "socks" })
    }

    @Test
    fun latencyRuntimeNormalizesSameProfileDetourReference() {
        val source = listOf(
            Outbound(type = "vless", tag = "tested", detour = "profile-a::front"),
            Outbound(type = "http", tag = "front")
        )

        val resolved = ConfigRepository.resolveLatencyRuntimeDetours(
            sourceProfileId = "profile-a",
            sourceOutbounds = source,
            loadProfileOutbounds = { error("同配置引用不应重复加载配置") }
        )

        assertEquals("front", resolved.first { it.tag == "tested" }.detour)
        assertEquals(2, resolved.size)
    }

    @Test
    fun latencyRuntimeLoadsPlainTagDependenciesRecursively() {
        val source = listOf(
            Outbound(type = "vless", tag = "tested", detour = "profile-b::front")
        )

        val resolved = ConfigRepository.resolveLatencyRuntimeDetours(
            sourceProfileId = "profile-a",
            sourceOutbounds = source
        ) { profileId ->
            when (profileId) {
                "profile-b" -> listOf(
                    Outbound(type = "socks", tag = "front", detour = "hop"),
                    Outbound(type = "http", tag = "hop")
                )
                else -> null
            }
        }

        val front = resolved.first { it.type == "socks" }
        assertTrue(resolved.any { it.tag == front.detour && it.type == "http" })
    }

    @Test
    fun runRuntimeLoadsCrossProfileDetourClosureForRequiredNode() {
        val profileId = "ba0ce4f3-403e-4737-a1ea-7370e1ce56f8"
        val resolution = ConfigRepository.resolveRuntimeOutboundDependencies(
            rootReferences = listOf(profileId to "1.88u idc"),
            reservedTags = setOf("direct", "PROXY")
        ) { requestedProfileId ->
            when (requestedProfileId) {
                profileId -> listOf(
                    Outbound(
                        type = "vless",
                        tag = "1.88u idc",
                        detour = "$profileId::[anytls]美国 01 10X GIA"
                    ),
                    Outbound(type = "anytls", tag = "[anytls]美国 01 10X GIA")
                )
                else -> null
            }
        }

        val targetTag = resolution.runtimeTags.getValue(profileId to "1.88u idc")
        val frontTag = resolution.runtimeTags.getValue(profileId to "[anytls]美国 01 10X GIA")
        val target = resolution.outbounds.first { it.tag == targetTag }

        assertEquals(frontTag, target.detour)
        assertTrue(resolution.outbounds.any { it.tag == frontTag && it.type == "anytls" })
    }

    @Test
    fun runConfigDoesNotSilentlyRemoveMissingDetour() {
        val source = File(
            "src/main/java/com/kunk/singbox/repository/configrepo/ConfigRepositoryPart8.kt"
        )
            .readText(Charsets.UTF_8)
        val runOutboundsBody = source
            .substringAfter("internal fun ConfigRepository.buildRunOutbounds(")
            .substringBefore("internal fun ConfigRepository.applySelectorSafeOutbounds(")

        assertTrue(runOutboundsBody.contains("resolveRuntimeOutboundDependencies("))
        assertTrue(runOutboundsBody.contains("前置代理「\$detourTag」不存在或形成自引用"))
        assertFalse(runOutboundsBody.contains("Cleared invalid detour"))
    }

    @Test
    fun latencyRuntimeDropsTargetWhoseRecursiveDetourUsesProtectedNode() {
        val source = listOf(
            Outbound(type = "vless", tag = "tested", detour = "profile-b::front")
        )
        val protectedNodeIds = setOf(ConfigRepository.stableNodeId("profile-b", "New-HTTP"))

        val resolved = ConfigRepository.resolveLatencyRuntimeDetours(
            sourceProfileId = "profile-a",
            sourceOutbounds = source,
            isProtectedReference = { profileId, reference ->
                MeteredNodeConfigGuard.isProtectedNodeReference(
                    sourceProfileId = profileId,
                    reference = reference,
                    protectedNodeIds = protectedNodeIds
                )
            }
        ) { profileId ->
            when (profileId) {
                "profile-b" -> listOf(
                    Outbound(type = "socks", tag = "front", detour = "New-HTTP"),
                    Outbound(type = "http", tag = "New-HTTP")
                )
                else -> null
            }
        }

        assertTrue(resolved.isEmpty())
    }

    @Test
    fun latencyRuntimeDropsTargetWhoseDetourGroupCanReachProtectedNode() {
        val source = listOf(
            Outbound(type = "vless", tag = "tested", detour = "automatic"),
            Outbound(
                type = "urltest",
                tag = "automatic",
                outbounds = listOf("safe", "New-HTTP")
            ),
            Outbound(type = "socks", tag = "safe"),
            Outbound(type = "http", tag = "New-HTTP")
        )
        val protectedNodeIds = setOf(ConfigRepository.stableNodeId("profile-a", "New-HTTP"))

        val resolved = ConfigRepository.resolveLatencyRuntimeDetours(
            sourceProfileId = "profile-a",
            sourceOutbounds = source,
            isProtectedReference = { profileId, reference ->
                MeteredNodeConfigGuard.isProtectedNodeReference(
                    sourceProfileId = profileId,
                    reference = reference,
                    protectedNodeIds = protectedNodeIds
                )
            },
            loadProfileOutbounds = { null }
        )

        assertTrue(resolved.none { it.tag == "tested" || it.tag == "automatic" })
        assertTrue(resolved.none { it.tag == "New-HTTP" })
    }

    @Test
    fun latencyRuntimeAllocatesUniqueTagForCrossProfileCollision() {
        val source = listOf(
            Outbound(type = "vless", tag = "tested", detour = "profile-b::front"),
            Outbound(type = "http", tag = "front", server = "source.example")
        )

        val resolved = ConfigRepository.resolveLatencyRuntimeDetours(
            sourceProfileId = "profile-a",
            sourceOutbounds = source
        ) { profileId ->
            when (profileId) {
                "profile-b" -> listOf(Outbound(type = "socks", tag = "front", server = "detour.example"))
                else -> null
            }
        }

        val tested = resolved.first { it.tag == "tested" }
        val crossProfileDetour = resolved.first { it.server == "detour.example" }
        assertNotEquals("front", crossProfileDetour.tag)
        assertEquals(crossProfileDetour.tag, tested.detour)
        assertEquals(resolved.size, resolved.map { it.tag }.distinct().size)
    }

    @Test
    fun latencyCancellationIsPropagatedBySharedTest() {
        val source = File(
            "src/main/java/com/kunk/singbox/repository/configrepo/ConfigRepositoryPart5.kt"
        ).readText()
        val singleLatencyBody = source
            .substringAfter("internal suspend fun ConfigRepository.testNodeLatency(nodeId: String): Long")
            .substringBefore("internal suspend fun ConfigRepository.clearAllNodesLatency()")

        assertTrue(singleLatencyBody.contains("catch (e: CancellationException)"))
        assertTrue(singleLatencyBody.contains("deferred.cancel(e)"))
    }

    @Test
    fun batchLatencyCancellationPersistsCompletedResultsBeforePropagating() = runBlocking {
        val batchStarted = CompletableDeferred<Unit>()
        val resultsApplied = CompletableDeferred<Unit>()
        val job = launch {
            runLatencyBatchAndApply(
                runBatch = {
                    batchStarted.complete(Unit)
                    awaitCancellation()
                },
                applyResults = {
                    currentCoroutineContext().ensureActive()
                    resultsApplied.complete(Unit)
                }
            )
        }

        batchStarted.await()
        job.cancelAndJoin()

        assertTrue(resultsApplied.isCompleted)
        assertTrue(job.isCancelled)
    }

    @Test
    fun deletingNodesRemovesPersistedLatencies() {
        val repositorySource = File(
            "src/main/java/com/kunk/singbox/repository/configrepo/ConfigRepositoryPart9.kt"
        ).readText()
        val daoSource = File(
            "src/main/java/com/kunk/singbox/database/dao/NodeLatencyDao.kt"
        ).readText()

        assertTrue(repositorySource.contains("removeNodeLatencies("))
        assertTrue(daoSource.contains("suspend fun deleteByNodeIds("))
    }

    @Test
    fun configCacheCleanupUsesRepositoryCoroutineScope() {
        val source = File(
            "src/main/java/com/kunk/singbox/repository/configrepo/ConfigRepositoryPart1.kt"
        ).readText()

        assertFalse(source.contains("newSingleThreadScheduledExecutor"))
        assertTrue(source.contains("delay(ConfigRepository.CONFIG_CACHE_CLEANUP_INTERVAL_MINUTES * 60_000L)"))
    }

    @Test
    fun rebuiltNodesRestorePersistedLatenciesAtCreation() {
        val source = File(
            "src/main/java/com/kunk/singbox/repository/configrepo/ConfigRepositoryPart3.kt"
        ).readText()
        val body = source
            .substringAfter("internal fun ConfigRepository.createNodeUi(")

        assertTrue(body.contains("latencyMs = savedLatencyMs(id)"))
    }

    @Test
    fun subscriptionRefreshUsesSharedActiveNodeResolution() {
        val source = File(
            "src/main/java/com/kunk/singbox/repository/configrepo/ConfigRepositoryPart5.kt"
        ).readText()
        val body = source
            .substringAfter("internal suspend fun ConfigRepository.importFromSubscriptionUpdate(")
            .substringBefore("internal fun ConfigRepository.buildSubscriptionUpdateSuccessResult")

        assertTrue(body.contains("applyActiveProfileNodes(profile.id, newNodes)"))
        assertFalse(body.contains("_nodes.value = newNodes"))
    }

    private fun node(id: String, name: String, profileId: String): NodeUi {
        return NodeUi(
            id = id,
            name = name,
            protocol = "vless",
            group = "test",
            sourceProfileId = profileId
        )
    }

    private fun runtimeContext(vararg tags: String): ConfigRepositoryLatencyRuntimeContext {
        return ConfigRepositoryLatencyRuntimeContext(
            outbounds = tags.map { tag -> Outbound(type = "vless", tag = tag) },
            dnsConfig = DnsConfig()
        )
    }
}

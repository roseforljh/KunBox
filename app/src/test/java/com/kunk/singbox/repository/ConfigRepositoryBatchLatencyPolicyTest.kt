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
    fun clearingAllLatenciesAlsoClearsRoomPersistence() {
        val repositorySource = File(
            "src/main/java/com/kunk/singbox/repository/ConfigRepository.kt"
        ).readText()
        val daoSource = File(
            "src/main/java/com/kunk/singbox/database/dao/NodeLatencyDao.kt"
        ).readText()

        assertTrue(repositorySource.contains("nodeLatencyDao.deleteAll()"))
        assertTrue(daoSource.contains("suspend fun deleteAll()"))
    }

    @Test
    fun batchLatencyPersistenceIsNotRepeatedByProfileSave() {
        val source = File(
            "src/main/java/com/kunk/singbox/repository/ConfigRepository.kt"
        ).readText()
        val saveProfilesBody = source
            .substringAfter("protected suspend fun saveProfilesInternal()")
            .substringBefore("protected fun writeConfigFileOrThrow")
        val batchLatencyBody = source
            .substringAfter("suspend fun testAllNodesLatency(")
            .substringBefore("suspend fun updateAllProfiles()")

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
    fun latencyCancellationIsPropagatedBySharedTest() {
        val source = File(
            "src/main/java/com/kunk/singbox/repository/ConfigRepository.kt"
        ).readText()
        val singleLatencyBody = source
            .substringAfter("suspend fun testNodeLatency(nodeId: String): Long")
            .substringBefore("suspend fun clearAllNodesLatency()")

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
            "src/main/java/com/kunk/singbox/repository/ConfigRepository.kt"
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
            "src/main/java/com/kunk/singbox/repository/ConfigRepository.kt"
        ).readText()

        assertFalse(source.contains("newSingleThreadScheduledExecutor"))
        assertTrue(source.contains("delay(ConfigRepository.CONFIG_CACHE_CLEANUP_INTERVAL_MINUTES * 60_000L)"))
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

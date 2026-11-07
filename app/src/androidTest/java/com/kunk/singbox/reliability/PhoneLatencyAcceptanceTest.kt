package com.kunk.singbox.reliability

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.manager.VpnServiceManager
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.awaitInitialProfilesLoaded
import com.kunk.singbox.repository.saveProfilesImmediate
import com.kunk.singbox.repository.setActiveProfileAndWait
import com.kunk.singbox.repository.testAllNodesLatency
import com.kunk.singbox.service.manager.VpnStopInitiator
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneLatencyAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val arguments = InstrumentationRegistry.getArguments()
    private val gson = GsonBuilder().create()

    @Test
    fun allImportedNodesHaveLatency() = runBlocking {
        awaitStopped()
        val profileId = requireArgument("profileId")
        val repository = ConfigRepository.getInstance(context)
        repository.awaitInitialProfilesLoaded()
        val originalProfile = repository.activeProfileId.value
        val originalNode = repository.activeNodeId.value
        val requestedNodeId = arguments.getString("nodeId")?.takeIf { it.isNotBlank() }
        try {
            repository.setActiveProfileAndWait(profileId, requestedNodeId)
            val nodes = repository.nodes.value.toList().filter { node ->
                requestedNodeId == null || node.id == requestedNodeId
            }
            assertEquals(
                "Unexpected latency test node count",
                if (requestedNodeId == null) 16 else 1,
                nodes.size
            )

            val results = java.util.Collections.synchronizedList(mutableListOf<LatencyResult>())
            repository.testAllNodesLatency(nodes.map { it.id }) { nodeId, latencyMs ->
                val node = nodes.first { it.id == nodeId }
                results += LatencyResult(node.name, latencyMs, latencyMs > 0)
            }
            results.sortBy { it.name }
            val output = File(context.cacheDir, "${requireArgument("runId")}-latency.json")
            output.writeText(gson.toJson(results), Charsets.UTF_8)
            assertEquals("All nodes must return latency", nodes.size, results.size)
            assertTrue("See ${output.absolutePath}", results.all { it.passed })
        } finally {
            if (originalProfile != null) {
                repository.setActiveProfileAndWait(originalProfile, originalNode)
            } else {
                repository._activeProfileId.value = null
                repository._activeNodeId.value = null
                repository.saveProfilesImmediate()
            }
            repository.saveProfilesJob?.join()
            check(repository.activeProfileId.value == originalProfile && repository.activeNodeId.value == originalNode)
        }
    }

    private suspend fun awaitStopped() {
        if (!VpnStateStore.getActive() && VpnStateStore.getMode() == VpnStateStore.CoreMode.NONE) return
        VpnServiceManager.stopVpn(context, VpnStopInitiator.UNKNOWN).getOrThrow()
        check(withTimeoutOrNull(45_000L) {
            while (VpnStateStore.getActive() || VpnStateStore.getMode() != VpnStateStore.CoreMode.NONE) {
                delay(250L)
            }
        } != null) { "VPN did not stop before latency acceptance" }
    }
    private fun requireArgument(name: String): String =
        arguments.getString(name)?.takeIf { it.isNotBlank() } ?: error("missing instrumentation arg: $name")

    private data class LatencyResult(val name: String, val latencyMs: Long, val passed: Boolean)
}

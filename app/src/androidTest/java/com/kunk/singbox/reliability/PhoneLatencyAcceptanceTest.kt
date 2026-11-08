package com.kunk.singbox.reliability

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.gson.GsonBuilder
import com.kunk.singbox.MainActivity
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
        // ColorOS freezes instrumentation processes without a visible activity; keep KunBox foreground.
        foregroundApp()
        val profileId = requireArgument("profileId")
        val repository = ConfigRepository.getInstance(context)
        repository.awaitInitialProfilesLoaded()
        val originalProfile = repository.activeProfileId.value
        val originalNode = repository.activeNodeId.value
        val requestedNodeIds = arguments.getString("nodeId")?.split(',')?.filter { it.isNotBlank() }
        try {
            repository.setActiveProfileAndWait(profileId, requestedNodeIds?.first())
            val nodes = repository.nodes.value.toList().filter { node ->
                requestedNodeIds == null || node.id in requestedNodeIds
            }
            assertEquals(
                "Unexpected latency test node count",
                requestedNodeIds?.size ?: 16,
                nodes.size
            )

            val results = java.util.Collections.synchronizedList(mutableListOf<LatencyResult>())
            val output = File(context.externalCacheDir, "${requireArgument("runId")}-latency.json")
            repository.testAllNodesLatency(nodes.map { it.id }) { nodeId, latencyMs ->
                val node = nodes.first { it.id == nodeId }
                results += LatencyResult(node.name, latencyMs, latencyMs > 0)
                output.writeText(gson.toJson(results), Charsets.UTF_8)
            }
            results.sortBy { it.name }
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
    private suspend fun foregroundApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        check(withTimeoutOrNull(45_000L) {
            var resumed = false
            while (!resumed) {
                instrumentation.runOnMainSync {
                    resumed = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).any { it is MainActivity }
                }
                if (!resumed) delay(250L)
            }
        } != null) { "KunBox activity did not resume" }
    }

    private fun requireArgument(name: String): String =
        arguments.getString(name)?.takeIf { it.isNotBlank() } ?: error("missing instrumentation arg: $name")

    private data class LatencyResult(val name: String, val latencyMs: Long, val passed: Boolean)
}

package com.kunk.singbox.reliability

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.manager.VpnServiceManager
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.awaitInitialProfilesLoaded
import com.kunk.singbox.repository.loadConfig
import com.kunk.singbox.repository.setActiveProfileAndWait
import com.kunk.singbox.repository.updateNode
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.manager.VpnStopInitiator
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

@RunWith(AndroidJUnit4::class)
class PhoneSsLadderAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val arguments = InstrumentationRegistry.getArguments()
    private val gson = GsonBuilder().create()

    @Test
    fun ss2022UdpLadder() = runBlocking {
        val runId = requireArgument("runId")
        val profileId = requireArgument("profileId")
        val probePackage = requireArgument("probePackage")
        val nodeIds = requireArgument("nodeIds").split(',').map(String::trim).filter(String::isNotEmpty)
        val repository = ConfigRepository.getInstance(context)
        repository.awaitInitialProfilesLoaded()
        val runDir = File(context.cacheDir, runId).also { it.mkdirs() }
        val results = mutableListOf<JsonObject>()
        try {
            nodeIds.forEach { nodeId ->
                val node = repository.nodes.value.firstOrNull { it.id == nodeId }
                    ?: error("node not found: $nodeId")
                repository.setActiveProfileAndWait(profileId, nodeId)
                val original = repository.loadConfig(profileId)?.outbounds.orEmpty()
                    .firstOrNull { it.tag == node.name } ?: error("outbound missing for $nodeId")
                try {
                    listOf(false, true).forEach { fragment ->
                        results += runLadder(
                            repository, profileId, nodeId, node.name, original, fragment, runId, probePackage
                        )
                        File(runDir, "ladder-result.json").writeText(gson.toJson(results), Charsets.UTF_8)
                    }
                } finally {
                    repository.updateNode(nodeId, original)
                    repository.saveProfilesJob?.join()
                }
            }
            repository.setActiveProfileAndWait(profileId, nodeIds.first())
        } finally {
            VpnServiceManager.stopVpn(context, VpnStopInitiator.UNKNOWN).getOrThrow()
            awaitStopped()
        }
        assertTrue("See $runDir", results.size == nodeIds.size * 2 && results.all { it.get("passed").asBoolean })
    }

    @Test
    fun clearUdpFragment() = runBlocking {
        val repository = ConfigRepository.getInstance(context)
        repository.awaitInitialProfilesLoaded()
        val profileId = requireArgument("profileId")
        requireArgument("nodeIds").split(',').map(String::trim).filter(String::isNotEmpty).forEach { nodeId ->
            val node = repository.nodes.value.firstOrNull { it.id == nodeId }
            val outbound = node?.let { candidate ->
                repository.loadConfig(profileId)?.outbounds.orEmpty().firstOrNull { it.tag == candidate.name }
            }
            if (outbound != null) {
                repository.updateNode(nodeId, outbound.copy(udpFragment = null))
                repository.saveProfilesJob?.join()
            }
        }
    }

    @Test
    fun setUdpFragment() = runBlocking {
        val repository = ConfigRepository.getInstance(context)
        repository.awaitInitialProfilesLoaded()
        val profileId = requireArgument("profileId")
        val value = when (requireArgument("value")) {
            "true" -> true
            "false" -> false
            else -> null
        }
        requireArgument("nodeIds").split(',').map(String::trim).filter(String::isNotEmpty).forEach { nodeId ->
            val node = repository.nodes.value.firstOrNull { it.id == nodeId }
            val outbound = node?.let { candidate ->
                repository.loadConfig(profileId)?.outbounds.orEmpty().firstOrNull { it.tag == candidate.name }
            }
            if (outbound != null) {
                repository.updateNode(nodeId, outbound.copy(udpFragment = value))
                repository.saveProfilesJob?.join()
            }
        }
    }

    private suspend fun runLadder(
        repository: ConfigRepository,
        profileId: String,
        nodeId: String,
        tag: String,
        original: Outbound,
        fragment: Boolean,
        runId: String,
        probePackage: String
    ): JsonObject {
        val phaseRunId = "$runId-f${if (fragment) 1 else 0}-${nodeId.take(8)}"
        val entry = JsonObject().apply {
            addProperty("tag", tag)
            addProperty("nodeId", nodeId)
            addProperty("udpFragment", fragment)
        }
        repository.updateNode(nodeId, original.copy(udpFragment = fragment))
        repository.saveProfilesJob?.join()
        repository.setActiveProfileAndWait(profileId, nodeId)
        VpnServiceManager.stopVpn(context, VpnStopInitiator.UNKNOWN).getOrThrow()
        check(awaitStopped()) { "VPN did not stop before $phaseRunId" }
        VpnServiceManager.startVpn(context, TrafficCaptureMode.VPN).getOrThrow()
        if (!awaitRunning()) {
            entry.addProperty("passed", false)
            entry.addProperty("error", "vpn did not start")
            awaitStopped()
            return entry
        }
        val probe = parseProbe(launchLadderProbe(probePackage, phaseRunId))
        entry.add("probe", probe)
        entry.addProperty("passed", probe.get("passed")?.asBoolean == true)
        VpnServiceManager.stopVpn(context, VpnStopInitiator.UNKNOWN).getOrThrow()
        check(awaitStopped()) { "VPN did not stop after $phaseRunId" }
        return entry
    }

    private suspend fun launchLadderProbe(packageName: String, probeRunId: String): String =
        withTimeoutOrNull(300_000L) {
            suspendCancellableCoroutine { continuation ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val json = intent.getStringExtra("json").orEmpty()
                        val probe = parseProbe(json)
                        if (continuation.isActive && probe.get("runId")?.asString == probeRunId) {
                            runCatching { context.unregisterReceiver(this) }
                            continuation.resume(json)
                        }
                    }
                }
                context.registerReceiver(
                    receiver,
                    IntentFilter(PROBE_RESULT_ACTION),
                    Context.RECEIVER_EXPORTED
                )
                val intent = Intent().apply {
                    component = ComponentName(packageName, "$packageName.ProbeActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("runId", probeRunId)
                    putExtra("caseId", probeRunId)
                    putExtra("udpSizes", LADDER_SIZES)
                    putExtra("resultPackage", context.packageName)
                }
                runCatching { context.startActivity(intent) }
                    .onFailure { continuation.resume("{\"passed\":false,\"error\":\"launch failed\"}") }
                continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            }
        } ?: "{\"passed\":false,\"error\":\"probe timeout\"}"

    private suspend fun awaitRunning(): Boolean = withTimeoutOrNull(45_000L) {
        var ready = false
        while (!ready) {
            val snapshot = VpnStateStore.getRuntimeStateSnapshot()
            ready = snapshot.stateOrdinal == ServiceState.RUNNING.ordinal &&
                snapshot.readiness.status == com.kunk.singbox.ipc.DataPlaneStatus.READY
            if (!ready) delay(250L)
        }
        true
    } ?: false

    private suspend fun awaitStopped(): Boolean = withTimeoutOrNull(45_000L) {
        var stopped = false
        while (!stopped) {
            stopped = VpnStateStore.getRuntimeStateSnapshot().stateOrdinal == ServiceState.STOPPED.ordinal &&
                VpnStateStore.getMode() == VpnStateStore.CoreMode.NONE
            if (!stopped) delay(250L)
        }
        true
    } ?: false

    private fun parseProbe(json: String): JsonObject = runCatching {
        gson.fromJson(json, JsonObject::class.java) ?: JsonObject().apply { addProperty("passed", false) }
    }.getOrElse { JsonObject().apply { addProperty("passed", false) } }

    private fun requireArgument(name: String): String =
        arguments.getString(name)?.takeIf { it.isNotBlank() } ?: error("missing instrumentation arg: $name")

    private companion object {
        const val PROBE_RESULT_ACTION = "com.kunk.phoneprobe.RESULT"
        const val LADDER_SIZES = "1,1200,1400,1472,1500,2048,4096"
    }
}

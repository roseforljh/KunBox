package com.kunk.singbox.reliability

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.kunk.singbox.MainActivity
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.manager.VpnServiceManager
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.RoutingMode
import com.kunk.singbox.repository.DataExportRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.store.SettingsStore
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.awaitInitialProfilesLoaded
import com.kunk.singbox.repository.saveProfilesImmediate
import com.kunk.singbox.repository.setActiveProfileAndWait
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
class PhoneVpnAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val arguments = InstrumentationRegistry.getArguments()
    private val gson = GsonBuilder().create()

    @Test
    fun realVpnMatrix() = runBlocking {
        val runId = requireArgument("runId")
        val profileId = requireArgument("profileId")
        val runDir = File(context.externalCacheDir, runId).also { it.mkdirs() }
        val repository = ConfigRepository.getInstance(context)
        repository.awaitInitialProfilesLoaded()
        foregroundApp()
        if (VpnStateStore.getActive() || VpnStateStore.getMode() != VpnStateStore.CoreMode.NONE) {
            VpnServiceManager.stopVpn(context, VpnStopInitiator.UNKNOWN).getOrThrow()
        }
        check(awaitState(ServiceState.STOPPED)) { "service did not finish initial cleanup" }
        val originalProfileId = repository.activeProfileId.value
        val originalNodeId = repository.activeNodeId.value
        val originalMode = VpnStateStore.getMode()
        val originalRunning = VpnStateStore.getActive()
        val nodes = repository.profiles.value.firstOrNull { it.id == profileId }
            ?.let { repository.setActiveProfileAndWait(it.id); repository.nodes.value.toList() }
            .orEmpty()
        require(nodes.size == 16) { "Expected 16 HK nodes, got ${nodes.size}" }
        val selectedNodes = arguments.getString("nodeId")?.split(',')
            ?.let { ids -> nodes.filter { it.id in ids } } ?: nodes
        require(selectedNodes.isNotEmpty()) { "No matching node" }
        val results = mutableListOf<NodeVpnResult>()
        val failures = mutableListOf<String>()
        File(runDir, "original.json").writeText(gson.toJson(mapOf(
            "profileId" to originalProfileId, "nodeId" to originalNodeId,
            "mode" to originalMode.name, "running" to originalRunning
        )))
        try {
            selectedNodes.forEach { node ->
                results += runNode(repository, profileId, node.id, node.name)
                File(runDir, "progress.json").writeText(gson.toJson(results))
            }
        } finally {
            runCatching {
                restoreOriginal(repository, originalProfileId, originalNodeId, originalMode, originalRunning)
            }.onFailure { failures += "restore failed: ${safeError(it)}" }
        }
        val resultFile = File(runDir, "vpn-result.json")
        val result = MatrixResult(
            runId = runId,
            profileId = profileId,
            selectedTags = results.map { it.tag },
            results = results,
            scope = if (arguments.getString("nodeId") == null) "full-16" else "single-node-smoke",
            originalRunning = originalRunning,
            originalMode = originalMode.name,
            restoredRunning = VpnStateStore.getActive(),
            restoredMode = VpnStateStore.getMode().name,
            failures = failures,
            passed = results.size == selectedNodes.size && results.all { it.passed } && failures.isEmpty() &&
                VpnStateStore.getActive() == originalRunning && VpnStateStore.getMode() == originalMode &&
                repository.activeProfileId.value == originalProfileId && repository.activeNodeId.value == originalNodeId
        )
        resultFile.writeText(gson.toJson(result), Charsets.UTF_8)
        assertTrue("See ${resultFile.absolutePath}", result.passed)
    }

    @Test
    fun realRuleSetTraffic() = runBlocking {
        val repository = ConfigRepository.getInstance(context)
        repository.awaitInitialProfilesLoaded()
        foregroundApp()
        check(!VpnStateStore.getActive() && awaitState(ServiceState.STOPPED)) { "Stop VPN before rule test" }
        val originalProfile = repository.activeProfileId.value
        val originalNode = repository.activeNodeId.value
        val settings = SettingsRepository.getInstance(context).settings.value
        val store = SettingsStore.getInstance(context)
        val runDir = File(context.externalCacheDir, requireArgument("runId")).also { it.mkdirs() }
        File(runDir, "backup.json").writeText(DataExportRepository.getInstance(context).exportAllData().getOrThrow())
        val source = File(runDir, "rules.json").apply {
            writeText("""{"version":3,"rules":[{"domain":["www.cloudflare.com"]}]}""")
        }
        val results = mutableListOf<Map<String, Any>>()
        try {
            for (mode in listOf(RuleSetOutboundMode.PROXY, RuleSetOutboundMode.DIRECT, RuleSetOutboundMode.BLOCK)) {
                val tag = "${requireArgument("runId")}-${mode.name.lowercase()}"
                val rule = RuleSet(tag = tag, type = RuleSetType.LOCAL, format = "source",
                    path = source.absolutePath, outboundMode = mode)
                check(store.updateSettingsAndWait { settings.copy(
                    routingMode = RoutingMode.RULE, ruleSets = listOf(rule), customRules = emptyList(),
                    appRules = emptyList(), appGroups = emptyList(), debugLoggingEnabled = true
                ) })
                repository.setActiveProfileAndWait(requireArgument("profileId"), requireArgument("nodeId"))
                foregroundApp()
                VpnServiceManager.startVpn(context, TrafficCaptureMode.VPN).getOrThrow()
                check(awaitState(ServiceState.RUNNING)) { "rule VPN failed to start: $mode" }
                val probe = parseProbe(launchProbe(requireArgument("probePackage"), tag, httpsOnly = true))
                val expected = if (mode == RuleSetOutboundMode.BLOCK) "predefined(NOERROR)" else "route(${mode.name})"
                var matches = emptyList<String>()
                withTimeoutOrNull(5_000L) {
                    while (matches.isEmpty()) {
                        val logs = File(context.filesDir, "running.log").readText()
                        File(runDir, "$tag.log").writeText(logs)
                        matches = logs.lineSequence().filter { it.contains("rule_set=$tag") &&
                            it.contains("match[") && it.contains(expected, ignoreCase = true) }.toList()
                        if (matches.isEmpty()) delay(STATE_POLL_MS)
                    }
                }
                VpnServiceManager.stopVpn(context, VpnStopInitiator.UNKNOWN).getOrThrow()
                check(awaitState(ServiceState.STOPPED))
                val success = probe.get("https")?.asJsonObject?.get("passed")?.asBoolean == true
                val passed = probe.get("vpnTransport")?.asBoolean == true && matches.isNotEmpty() &&
                    success == (mode != RuleSetOutboundMode.BLOCK) &&
                    (mode != RuleSetOutboundMode.PROXY || probe.getAsJsonObject("trace")?.get("ip")?.asString == "216.236.7.40")
                results += mapOf("mode" to mode.name, "probe" to probe, "matches" to matches, "passed" to passed)
                File(runDir, "progress.json").writeText(gson.toJson(results))
            }
        } finally {
            VpnServiceManager.stopVpn(context, VpnStopInitiator.UNKNOWN).getOrThrow()
            check(awaitState(ServiceState.STOPPED))
            check(store.updateSettingsAndWait { settings })
            restoreOriginal(repository, originalProfile, originalNode, VpnStateStore.CoreMode.NONE, false)
            check(store.settings.value == settings) { "rule settings not restored" }
        }
        File(runDir, "result.json").writeText(gson.toJson(results))
        assertTrue("See ${runDir.absolutePath}", results.size == 3 && results.all { it["passed"] == true })
    }

    private suspend fun runNode(
        repository: ConfigRepository,
        profileId: String,
        nodeId: String,
        tag: String
    ): NodeVpnResult {
        val failures = mutableListOf<String>()
        var started = false
        var stopped = false
        var probeJson: String? = null
        try {
            foregroundApp()
            VpnServiceManager.stopVpn(context, VpnStopInitiator.UNKNOWN).getOrThrow()
            if (!awaitState(ServiceState.STOPPED)) error("VPN did not stop before start")
            repository.setActiveProfileAndWait(profileId, nodeId)
            VpnServiceManager.startVpn(context, TrafficCaptureMode.VPN).getOrThrow()
            if (!awaitState(ServiceState.RUNNING)) {
                val snapshot = VpnStateStore.getRuntimeStateSnapshot()
                error(
                    "VPN did not start: lastError=${snapshot.lastError.orEmpty().take(160)}, " +
                        "state=${snapshot.stateOrdinal}, pending=${VpnStateStore.getPending()}, " +
                        "mode=${VpnStateStore.getMode()}, generation=${snapshot.generation}, " +
                        "readiness=${snapshot.readiness.status}"
                )
            }
            started = true
            probeJson = launchProbe(requireArgument("probePackage"), nodeId)
            val probe = parseProbe(probeJson)
            if (probe.get("vpnTransport")?.asBoolean != true) failures += "probe vpnTransport=false"
            if (probe.get("passed")?.asBoolean != true) failures += "probe reported failure"
        } catch (error: Throwable) {
            failures += safeError(error)
        } finally {
            runCatching { VpnServiceManager.stopVpn(context, VpnStopInitiator.UNKNOWN).getOrThrow() }
                .onFailure { failures += "stop failed: ${safeError(it)}" }
            stopped = awaitState(ServiceState.STOPPED)
            if (!stopped) failures += "VPN did not reach STOPPED"
        }
        return NodeVpnResult(nodeId, tag, started, stopped, probeJson, failures, failures.isEmpty())
    }

    private suspend fun foregroundApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context.startActivity(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        check(withTimeoutOrNull(START_TIMEOUT_MS) {
            var resumed = false
            while (!resumed) {
                instrumentation.runOnMainSync {
                    resumed = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).any { it is MainActivity }
                }
                if (!resumed) delay(STATE_POLL_MS)
            }
            true
        } == true) { "KunBox activity did not resume" }
    }

    private suspend fun launchProbe(
        packageName: String,
        nodeId: String,
        httpsOnly: Boolean = false
    ): String = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val resultReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val json = intent.getStringExtra("json").orEmpty()
                    val probe = parseProbe(json)
                    if (continuation.isActive && probe.get("caseId")?.asString == nodeId &&
                        probe.get("runId")?.asString == requireArgument("runId")
                    ) {
                        runCatching { context.unregisterReceiver(this) }
                        continuation.resume(json)
                    }
                }
            }
            val filter = IntentFilter("com.kunk.phoneprobe.RESULT")
            context.registerReceiver(resultReceiver, filter, Context.RECEIVER_EXPORTED)
            val intent = Intent().apply {
                component = ComponentName(packageName, "$packageName.ProbeActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("runId", requireArgument("runId"))
                putExtra("caseId", nodeId)
                putExtra("resultPackage", context.packageName)
                putExtra("udpSizes", arguments.getString("udpSizes").orEmpty())
                putExtra("httpsOnly", httpsOnly)
            }
            try {
                context.startActivity(intent)
            } catch (error: Throwable) {
                context.unregisterReceiver(resultReceiver)
                continuation.resume("{\"passed\":false,\"error\":\"${safeError(error)}\"}")
            }
            continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(resultReceiver) } }
        }
    } ?: "{\"passed\":false,\"error\":\"probe timeout\"}"

    private suspend fun awaitState(expected: ServiceState): Boolean = withTimeoutOrNull(START_TIMEOUT_MS) {
        while (true) {
            val snapshot = VpnStateStore.getRuntimeStateSnapshot()
            val cleanupComplete = expected != ServiceState.STOPPED ||
                VpnStateStore.getMode() == VpnStateStore.CoreMode.NONE && VpnStateStore.getPending().isEmpty()
            val ready = expected != ServiceState.RUNNING ||
                snapshot.readiness.status == com.kunk.singbox.ipc.DataPlaneStatus.READY
            if (snapshot.stateOrdinal == expected.ordinal && cleanupComplete && ready) break
            delay(STATE_POLL_MS)
        }
        true
    } ?: false

    private suspend fun restoreOriginal(
        repository: ConfigRepository,
        profileId: String?,
        nodeId: String?,
        mode: VpnStateStore.CoreMode,
        wasRunning: Boolean
    ) {
        VpnServiceManager.stopVpn(context, VpnStopInitiator.UNKNOWN).getOrThrow()
        check(awaitState(ServiceState.STOPPED)) { "VPN did not stop before restore" }
        if (profileId != null) {
            check(repository.profiles.value.any { it.id == profileId }) { "original profile missing" }
            repository.setActiveProfileAndWait(profileId, nodeId)
        } else {
            repository._activeProfileId.value = null
            repository._activeNodeId.value = null
            repository.saveProfilesImmediate()
        }
        if (wasRunning) {
            val captureMode = when (mode) {
                VpnStateStore.CoreMode.VPN -> TrafficCaptureMode.VPN
                VpnStateStore.CoreMode.PROXY -> TrafficCaptureMode.PROXY_ONLY
                VpnStateStore.CoreMode.ROOT -> TrafficCaptureMode.ROOT_TRANSPARENT
                VpnStateStore.CoreMode.NONE -> error("original running state has no mode")
            }
            VpnServiceManager.startVpn(context, captureMode).getOrThrow()
            check(awaitState(ServiceState.RUNNING)) { "original service did not start" }
        }
        check(VpnStateStore.getActive() == wasRunning && VpnStateStore.getMode() == mode) {
            "original service state was not restored"
        }
        check(repository.activeProfileId.value == profileId && repository.activeNodeId.value == nodeId) {
            "original selection was not restored"
        }
    }

    private fun parseProbe(json: String): JsonObject = runCatching {
            gson.fromJson(json, JsonObject::class.java) ?: JsonObject().apply { addProperty("passed", false) }
    }.getOrElse { JsonObject().apply { addProperty("passed", false) } }

    private fun requireArgument(name: String): String = arguments.getString(name)?.takeIf { it.isNotBlank() }
        ?: error("missing instrumentation arg: $name")

    private fun safeError(error: Throwable): String = (error.message ?: error::class.java.simpleName)
        .replace(Regex("(?i)(uuid|password|token|private[_ -]?key|certificate|secret)[^,; ]*"), "$1=***")
        .take(240)

    private data class NodeVpnResult(
        val nodeId: String,
        val tag: String,
        val started: Boolean,
        val stopped: Boolean,
        val probe: String?,
        val failures: List<String>,
        val passed: Boolean
    )

    private data class MatrixResult(
        val runId: String,
        val profileId: String,
        val selectedTags: List<String>,
        val results: List<NodeVpnResult>,
        val scope: String,
        val originalRunning: Boolean,
        val originalMode: String,
        val restoredRunning: Boolean,
        val restoredMode: String,
        val failures: List<String>,
        val passed: Boolean
    )

    private companion object {
        const val START_TIMEOUT_MS = 45_000L
        const val PROBE_TIMEOUT_MS = 300_000L
        const val STATE_POLL_MS = 250L
    }
}

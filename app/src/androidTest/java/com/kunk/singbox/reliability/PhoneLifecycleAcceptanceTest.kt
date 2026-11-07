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
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.manager.VpnServiceManager
import com.kunk.singbox.model.TrafficCaptureMode
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.awaitInitialProfilesLoaded
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
class PhoneLifecycleAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val arguments = InstrumentationRegistry.getArguments()
    private val gson = GsonBuilder().create()

    @Test
    fun startVpn() = runBlocking {
        val repository = ConfigRepository.getInstance(context)
        repository.awaitInitialProfilesLoaded()
        VpnServiceManager.stopVpn(context, VpnStopInitiator.UNKNOWN).getOrThrow()
        check(awaitStopped()) { "VPN did not stop before start" }
        repository.setActiveProfileAndWait(requireArgument("profileId"), requireArgument("nodeId"))
        VpnServiceManager.startVpn(context, TrafficCaptureMode.VPN).getOrThrow()
        check(awaitRunning()) {
            "VPN did not start: lastError=${VpnStateStore.getRuntimeStateSnapshot().lastError.take(200)}"
        }
        writeResult("start", JsonObject().apply { addProperty("passed", true) })
    }

    @Test
    fun probeCurrentVpn() = runBlocking {
        val phase = requireArgument("phase")
        val nodeId = requireArgument("nodeId")
        val probe = parseProbe(
            launchProbe(
                requireArgument("probePackage"),
                nodeId,
                arguments.getString("udpSizes").orEmpty()
            )
        )
        val output = JsonObject().apply {
            addProperty("phase", phase)
            add("probe", probe)
            add("state", stateSnapshot())
            addProperty("passed", probe.get("passed")?.asBoolean == true)
        }
        writeResult(phase, output)
        assertTrue("See ${resultFile(phase).absolutePath}", output.get("passed").asBoolean)
    }

    @Test
    fun snapshot() = runBlocking {
        val phase = requireArgument("phase")
        writeResult(phase, JsonObject().apply {
            addProperty("phase", phase)
            add("state", stateSnapshot())
            addProperty("passed", true)
        })
    }

    @Test
    fun stopVpn() = runBlocking {
        VpnServiceManager.stopVpn(context, VpnStopInitiator.UNKNOWN).getOrThrow()
        val stopped = awaitStopped()
        writeResult("stop", JsonObject().apply {
            addProperty("stopped", stopped)
            add("state", stateSnapshot())
            addProperty("passed", stopped)
        })
        assertTrue("VPN did not stop", stopped)
    }

    @Test
    fun dozeSequence() = runBlocking {
        val runId = requireArgument("runId")
        val repository = ConfigRepository.getInstance(context)
        repository.awaitInitialProfilesLoaded()
        val runDir = File(context.cacheDir, runId).also { it.mkdirs() }
        val results = JsonObject()
        try {
            VpnServiceManager.stopVpn(context, VpnStopInitiator.UNKNOWN).getOrThrow()
            check(awaitStopped()) { "VPN did not stop before doze" }
            repository.setActiveProfileAndWait(
                requireArgument("profileId"),
                requireArgument("nodeId")
            )
            VpnServiceManager.startVpn(context, TrafficCaptureMode.VPN).getOrThrow()
            check(awaitRunning()) { "VPN did not start before doze" }
            results.add("before", probePhase(requireArgument("probePackage")))
            File(runDir, "ready.flag").writeText("1")
            results.add("idle", awaitPhase(runDir, "idle.flag", requireArgument("probePackage")))
            File(runDir, "idle-done.flag").writeText("1")
            results.add("after", awaitPhase(runDir, "wake.flag", requireArgument("probePackage")))
        } finally {
            VpnServiceManager.stopVpn(context, VpnStopInitiator.UNKNOWN).getOrThrow()
            awaitStopped()
            results.add("finalState", stateSnapshot())
            File(runDir, "doze-result.json").writeText(gson.toJson(results), Charsets.UTF_8)
        }
    }

    private suspend fun awaitPhase(runDir: File, flag: String, probePackage: String): JsonObject {
        val deadline = System.currentTimeMillis() + PHASE_TIMEOUT_MS
        while (!File(runDir, flag).exists() && System.currentTimeMillis() < deadline) delay(1000L)
        return if (File(runDir, flag).exists()) {
            probePhase(probePackage)
        } else {
            JsonObject().apply {
                addProperty("passed", false)
                addProperty("error", "phase timeout: $flag")
            }
        }
    }

    private suspend fun probePhase(probePackage: String): JsonObject {
        val probe = parseProbe(
            launchProbe(
                probePackage,
                requireArgument("nodeId"),
                arguments.getString("udpSizes").orEmpty()
            )
        )
        return JsonObject().apply {
            add("probe", probe)
            add("state", stateSnapshot())
            addProperty("passed", probe.get("passed")?.asBoolean == true)
        }
    }

    private fun stateSnapshot(): JsonObject {
        val runtime = VpnStateStore.getRuntimeStateSnapshot()
        return JsonObject().apply {
            addProperty("stateOrdinal", runtime.stateOrdinal)
            addProperty("expectedState", ServiceState.RUNNING.ordinal)
            addProperty("generation", runtime.generation)
            addProperty("readiness", runtime.readiness.status.name)
            addProperty("lastError", runtime.lastError.take(200))
            addProperty("active", VpnStateStore.getActive())
            addProperty("mode", VpnStateStore.getMode().name)
            addProperty("pending", VpnStateStore.getPending())
            addProperty("selectedProfileId", VpnStateStore.getSelectedProfileId())
            addProperty("selectedNodeId", VpnStateStore.getSelectedNodeId())
            addProperty("remoteState", SingBoxRemote.state.value.name)
            addProperty("remoteRunning", SingBoxRemote.isRunning.value)
            addProperty("remoteBound", SingBoxRemote.isBound())
            addProperty("remoteSyncAgeMs", SingBoxRemote.getLastSyncAge())
            addProperty("profileId", ConfigRepository.getInstance(context).activeProfileId.value.orEmpty())
            addProperty("nodeId", ConfigRepository.getInstance(context).activeNodeId.value.orEmpty())
        }
    }

    private fun writeResult(phase: String, value: JsonObject) {
        File(context.cacheDir, requireArgument("runId")).mkdirs()
        resultFile(phase).writeText(gson.toJson(value), Charsets.UTF_8)
    }

    private fun resultFile(phase: String): File =
        File(File(context.cacheDir, requireArgument("runId")), "$phase.json")

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

    private suspend fun launchProbe(packageName: String, caseId: String, sizes: String): String =
        withTimeoutOrNull(300_000L) {
            suspendCancellableCoroutine { continuation ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val json = intent.getStringExtra("json").orEmpty()
                        val probe = parseProbe(json)
                        if (continuation.isActive && probe.get("caseId")?.asString == caseId &&
                            probe.get("runId")?.asString == requireArgument("runId")
                        ) {
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
                    putExtra("runId", requireArgument("runId"))
                    putExtra("caseId", caseId)
                    putExtra("udpSizes", sizes)
                    putExtra("resultPackage", context.packageName)
                }
                runCatching { context.startActivity(intent) }
                    .onFailure { continuation.resume("{\"passed\":false,\"error\":\"launch failed\"}") }
                continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            }
        } ?: "{\"passed\":false,\"error\":\"probe timeout\"}"

    private fun parseProbe(json: String): JsonObject = runCatching {
        gson.fromJson(json, JsonObject::class.java) ?: JsonObject().apply { addProperty("passed", false) }
    }.getOrElse { JsonObject().apply { addProperty("passed", false) } }

    private fun requireArgument(name: String): String =
        arguments.getString(name)?.takeIf { it.isNotBlank() } ?: error("missing instrumentation arg: $name")

    private companion object {
        const val PROBE_RESULT_ACTION = "com.kunk.phoneprobe.RESULT"
        const val PHASE_TIMEOUT_MS = 900_000L
    }
}

package com.kunk.singbox.reliability

import android.app.ActivityManager
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kunk.singbox.ipc.DataPlaneStatus
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.manager.VpnStopInitiator
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhysicalDialGateInstrumentationTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun nativeMarkersArePackaged() {
        val library = File(context.applicationInfo.nativeLibraryDir, "libbox.so")
        assertTrue("libbox.so missing: $library", library.isFile)
        val bytes = library.readBytes()
        REQUIRED_MARKERS.forEach { marker ->
            assertTrue("native marker missing: $marker", bytes.contains(marker.toByteArray()))
        }
    }

    @Test
    fun failedPhysicalDialsStayWithinBudget() = runBlocking {
        requireExplicitFaultInjectionOptIn()
        assertEquals("Grant VPN permission before running this device test", null, VpnService.prepare(context))
        val blackhole = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val configFile = File(context.cacheDir, "physical-dial-gate-test.json")
        try {
            configFile.writeText(buildBlackholeConfig(blackhole.localPort), Charsets.UTF_8)
            startVpn(configFile)
            awaitReadiness()
            assertShellToolAvailable("toybox nc -h >/dev/null 2>&1")

            val processId = requireBackgroundProcessId()
            val baselineFd = readFdCount(processId)
            val baselineTunPackets = readTunTxPackets()
            val before = readNativeBudgetSnapshot()

            runConnectionBatch(PREFLIGHT_CONNECTIONS)
            val preflightOne = awaitNativeSnapshotChange(before.attempts)
            delay(PREFLIGHT_HOLD_MS)
            val preflightTwo = readNativeBudgetSnapshot()
            assertTrue("fault target did not hold pending dials", preflightOne.pending > 0 && preflightTwo.pending > 0)

            repeat(TOTAL_CONNECTIONS / BATCH_CONNECTIONS) { runConnectionBatch(BATCH_CONNECTIONS) }
            val peak = samplePeak(processId)
            val afterLoad = readNativeBudgetSnapshot()
            val tunPacketsAfterLoad = readTunTxPackets()
            assertTrue("physical attempts did not reach the gate", afterLoad.attempts - before.attempts > afterLoad.pendingLimit)
            assertTrue("physical gate rejected no overload", afterLoad.rejected - before.rejected > 0)
            assertTrue("test traffic did not enter TUN", tunPacketsAfterLoad > baselineTunPackets)
            assertTrue(
                "fd peak exceeded budget: peak=$peak baseline=$baselineFd stop=${afterLoad.fdStopLine}",
                peak <= maxOf(afterLoad.fdStopLine, baselineFd + NON_NATIVE_FD_ALLOWANCE)
            )

            delay(RECOVERY_WAIT_MS)
            val recovered = readNativeBudgetSnapshot()
            assertEquals("pending leases did not recover", 0, recovered.pending)
            assertTrue("fd count did not recover", readFdCount(processId) <= baselineFd + RECOVERED_FD_ALLOWANCE)
        } finally {
            stopVpn()
            blackhole.close()
            configFile.delete()
        }
    }

    private fun requireExplicitFaultInjectionOptIn() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString("kunbox.destructivePhysicalGate")
            ?.toBooleanStrictOrNull()
        assertEquals("Pass kunbox.destructivePhysicalGate=true to run the fault test", true, enabled)
    }

    private fun startVpn(configFile: File) {
        val intent = Intent(context, SingBoxService::class.java).apply {
            action = SingBoxService.ACTION_START
            putExtra(SingBoxService.EXTRA_CONFIG_PATH, configFile.absolutePath)
            putExtra(SingBoxService.EXTRA_CLEAN_CACHE, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
        SingBoxRemote.ensureBound(context)
    }

    private suspend fun awaitReadiness() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            SingBoxRemote.queryAndSyncState(context)
            if (SingBoxRemote.readiness.value.status in setOf(DataPlaneStatus.READY, DataPlaneStatus.BLOCKING)) return
            delay(250L)
        }
        error("VPN did not establish TUN for physical dial test: ${SingBoxRemote.readiness.value}")
    }

    private fun stopVpn() {
        context.startService(Intent(context, SingBoxService::class.java).apply {
            action = SingBoxService.ACTION_STOP
            putExtra(SingBoxService.EXTRA_STOP_INITIATOR, VpnStopInitiator.USER_UI.wireValue)
        })
    }

    private fun runConnectionBatch(count: Int) {
        val script = "i=0; while [ \$i -lt $count ]; do toybox nc -w 20 198.18.0.1 443 >/dev/null 2>&1 & " +
            "i=\$((i+1)); done"
        shell(script)
    }

    private suspend fun awaitNativeSnapshotChange(previousAttempts: Long): NativeBudgetSnapshot {
        repeat(20) {
            val current = readNativeBudgetSnapshot()
            if (current.attempts > previousAttempts) return current
            delay(250L)
        }
        error("native budget snapshot did not change")
    }

    private suspend fun samplePeak(processId: Int): Int {
        var peak = 0
        repeat(PEAK_SAMPLES) {
            peak = maxOf(peak, readFdCount(processId))
            delay(PEAK_SAMPLE_INTERVAL_MS)
        }
        return peak
    }

    private fun readNativeBudgetSnapshot(): NativeBudgetSnapshot {
        val line = shell("logcat -d -v brief | grep kunbox_physical_budget_v1 | tail -n 1")
        assertTrue("native budget log unavailable", line.contains("kunbox_physical_budget_v1"))
        fun value(name: String): Long = line.substringAfter("$name=", "")
            .substringBefore(' ')
            .toLongOrNull()
            ?: error("missing $name in $line")
        return NativeBudgetSnapshot(
            pending = value("pending").toInt(),
            attempts = value("attempts"),
            rejected = value("rejected"),
            pendingLimit = value("pending_limit").toInt(),
            fdStopLine = value("fd_stop_line").toInt().takeIf { it > 0 } ?: DEFAULT_FD_STOP_LINE
        )
    }

    private fun requireBackgroundProcessId(): Int {
        val manager = context.getSystemService(ActivityManager::class.java)
        val process = manager.runningAppProcesses?.firstOrNull { it.processName == "${context.packageName}:bg" }
        assertNotNull("VPN :bg process not found", process)
        return requireNotNull(process).pid
    }

    private fun readFdCount(processId: Int): Int {
        return File("/proc/$processId/fd").list()?.size ?: error("cannot read :bg fd count")
    }

    private fun readTunTxPackets(): Long {
        val output = shell("ip -s link")
        val lines = output.lineSequence().toList()
        var total = 0L
        lines.forEachIndexed { index, line ->
            if (!line.contains(Regex("\\d+: (tun|vpn)"))) return@forEachIndexed
            total += lines.getOrNull(index + 3)?.trim()?.substringBefore(' ')?.toLongOrNull() ?: 0L
        }
        return total
    }

    private fun assertShellToolAvailable(command: String) {
        shell(command)
        assertTrue("toybox nc unavailable", shell("command -v toybox").isNotBlank())
    }

    private fun shell(command: String): String {
        return ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }
    }

    private fun buildBlackholeConfig(port: Int): String = """
        {
          "log": {"level": "info", "timestamp": true},
          "inbounds": [{
            "type": "tun", "tag": "tun-in", "address": ["172.19.0.1/30"],
            "auto_route": true, "strict_route": true, "stack": "mixed"
          }],
          "outbounds": [{
            "type": "socks", "tag": "blackhole", "server": "127.0.0.1", "server_port": $port
          }],
          "route": {"final": "blackhole", "auto_detect_interface": true}
        }
    """.trimIndent()

    private data class NativeBudgetSnapshot(
        val pending: Int,
        val attempts: Long,
        val rejected: Long,
        val pendingLimit: Int,
        val fdStopLine: Int
    )

    private companion object {
        val REQUIRED_MARKERS = listOf(
            "pre-handshake connection rejected: reason=",
            "kunbox_physical_dial_gate_v1",
            "kunbox_wireguard_physical_gate_v1"
        )
        const val PREFLIGHT_CONNECTIONS = 64
        const val PREFLIGHT_HOLD_MS = 1_000L
        const val TOTAL_CONNECTIONS = 8_192
        const val BATCH_CONNECTIONS = 1_024
        const val PEAK_SAMPLES = 150
        const val PEAK_SAMPLE_INTERVAL_MS = 2_000L
        const val RECOVERY_WAIT_MS = 30_000L
        const val NON_NATIVE_FD_ALLOWANCE = 512
        const val RECOVERED_FD_ALLOWANCE = 64
        const val DEFAULT_FD_STOP_LINE = 8_192
    }
}

private fun ByteArray.contains(needle: ByteArray): Boolean {
    if (needle.isEmpty() || needle.size > size) return false
    for (start in 0..size - needle.size) {
        var matches = true
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) {
                matches = false
                break
            }
        }
        if (matches) return true
    }
    return false
}

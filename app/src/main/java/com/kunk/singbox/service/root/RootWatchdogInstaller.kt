package com.kunk.singbox.service.root

import android.content.Context
import android.os.Process
import android.system.Os
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class RootWatchdogInstaller(
    private val context: Context,
    private val executor: RootCommandExecutor = ProcessRootCommandExecutor()
) {
    companion object {
        private const val RUNTIME_DIR = "/data/adb/kunbox"
        private const val WATCHDOG_PATH = "$RUNTIME_DIR/watchdog.sh"
        private const val LEASE_TIMEOUT_SECONDS = 3
        private const val ACK_TIMEOUT_SECONDS = 2
    }

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var leaseTask: ScheduledFuture<*>? = null
    private var ackTask: ScheduledFuture<*>? = null
    private var watchdogProcess: java.lang.Process? = null
    private var activeSessionId: String = ""
    @Volatile private var readyLatch = CountDownLatch(1)

    @Volatile
    private var ackHealthy = false

    fun start(
        runtimeSessionId: String,
        apkPath: String,
        onWatchdogLost: () -> Unit
    ): Result<Unit> = runCatching {
        require(runtimeSessionId.isNotBlank())
        require(apkPath.isNotBlank())
        stopExistingWatchdog()
        readyLatch = CountDownLatch(1)
        installScript()
        writeAtomically(File(RUNTIME_DIR, "session"), runtimeSessionId)
        writeLease()
        val process = ProcessBuilder(
            "/system/bin/sh",
            WATCHDOG_PATH,
            "watch",
            apkPath,
            Process.myPid().toString(),
            runtimeSessionId,
            LEASE_TIMEOUT_SECONDS.toString()
        ).start()
        check(process.isAlive) { "Root watchdog exited during startup" }
        watchdogProcess = process
        activeSessionId = runtimeSessionId
        leaseTask = scheduler.scheduleAtFixedRate(::writeLease, 0, 1, TimeUnit.SECONDS)
        ackTask = scheduler.scheduleAtFixedRate(
            { checkAck(runtimeSessionId, onWatchdogLost) },
            1,
            1,
            TimeUnit.SECONDS
        )
    }

    fun isReady(): Boolean = ackHealthy

    fun awaitReady(timeoutSeconds: Long = 3): Boolean =
        readyLatch.await(timeoutSeconds, TimeUnit.SECONDS) && ackHealthy

    fun stop(cleanupRules: Boolean): Result<Unit> = runCatching {
        leaseTask?.cancel(true)
        ackTask?.cancel(true)
        leaseTask = null
        ackTask = null
        ackHealthy = false
        stopWatchdogProcess()
        if (cleanupRules) {
            val result = executor.execute(listOf("/system/bin/sh", WATCHDOG_PATH, "cleanup", activeSessionId))
            check(result.success) { "Root watchdog cleanup failed: ${result.output}" }
        }
        activeSessionId = ""
    }

    fun close() {
        stop(cleanupRules = false)
        scheduler.shutdownNow()
    }

    private fun installScript() {
        val runtimeDir = File(RUNTIME_DIR)
        check(runtimeDir.exists() || runtimeDir.mkdirs()) { "Cannot create $RUNTIME_DIR" }
        val script = context.assets.open("root/kunbox-root-watchdog.sh").bufferedReader().use { it.readText() }
        writeAtomically(File(WATCHDOG_PATH), script)
        check(File(WATCHDOG_PATH).setExecutable(true, true)) { "Cannot make watchdog executable" }
        File(WATCHDOG_PATH).setReadable(false, false)
        File(WATCHDOG_PATH).setReadable(true, true)
        File(WATCHDOG_PATH).setWritable(false, false)
        File(WATCHDOG_PATH).setWritable(true, true)
    }

    private fun stopExistingWatchdog() {
        val pid = runCatching { File(RUNTIME_DIR, "watchdog.pid").readText().trim().toInt() }.getOrNull() ?: return
        val commandLine = runCatching { File("/proc/$pid/cmdline").readText() }.getOrDefault("")
        if (WATCHDOG_PATH !in commandLine) return
        executor.execute(listOf("kill", "-TERM", pid.toString()))
        if (File("/proc/$pid").exists()) executor.execute(listOf("kill", "-KILL", pid.toString()))
    }

    private fun stopWatchdogProcess() {
        val process = watchdogProcess ?: return
        process.destroy()
        if (process.isAlive) process.destroyForcibly()
        runCatching { process.waitFor() }
        watchdogProcess = null
    }

    private fun writeLease() {
        val nowSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        writeAtomically(File(RUNTIME_DIR, "lease"), nowSeconds.toString())
    }

    private fun checkAck(runtimeSessionId: String, onWatchdogLost: () -> Unit) {
        val parts = runCatching { File(RUNTIME_DIR, "watchdog_ack").readText().trim().split(':') }
            .getOrDefault(emptyList())
        val ackSeconds = parts.getOrNull(1)?.toLongOrNull()
        val nowSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        val healthy = parts.firstOrNull() == runtimeSessionId &&
            ackSeconds != null &&
            nowSeconds - ackSeconds in 0..ACK_TIMEOUT_SECONDS.toLong()
        val wasHealthy = ackHealthy
        ackHealthy = healthy
        if (healthy) readyLatch.countDown()
        if (wasHealthy && !healthy) onWatchdogLost()
    }

    private fun writeAtomically(target: File, content: String) {
        val temp = File(target.parentFile, "${target.name}.tmp.${Process.myPid()}")
        temp.writeText(content)
        Os.rename(temp.absolutePath, target.absolutePath)
    }
}

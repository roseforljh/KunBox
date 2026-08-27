package com.kunk.singbox.service.root

import android.content.Context
import android.os.Process
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
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
        private const val CLEANUP_PATH = "$RUNTIME_DIR/cleanup-owned.sh"
        private const val LEASE_TIMEOUT_SECONDS = 3
        private const val ACK_TIMEOUT_SECONDS = 2
        private const val STOP_WAIT_MS = 750L
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
        leaseTask?.cancel(true)
        ackTask?.cancel(true)
        leaseTask = null
        ackTask = null
        ackHealthy = false
        stopWatchdogProcess()
        readyLatch = CountDownLatch(1)
        installScripts().getOrThrow()
        // Keep an older watchdog alive until the caller has completed its
        // stale-rule preflight. If preflight fails, that watchdog remains the
        // last fail-closed cleanup owner. Only replace it once this session
        // is ready to take ownership.
        stopExistingWatchdog()
        writeAtomically(File(RUNTIME_DIR, "session"), runtimeSessionId)
        writeLease()
        val process = ProcessBuilder(
            "/system/bin/sh",
            WATCHDOG_PATH,
            "watch",
            apkPath,
            Process.myPid().toString(),
            runtimeSessionId,
            LEASE_TIMEOUT_SECONDS.toString(),
            requireCurrentProcessStartTime()
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

    fun awaitReady(timeoutSeconds: Long = 3): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (true) {
            checkAck(activeSessionId) {}
            if (ackHealthy) return true
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) return false
            readyLatch.await(
                minOf(remaining, TimeUnit.MILLISECONDS.toNanos(25)),
                TimeUnit.NANOSECONDS
            )
        }
    }

    fun stop(cleanupRules: Boolean): Result<Unit> = runCatching {
        val sessionId = activeSessionId
        if (cleanupRules && sessionId.isNotBlank()) {
            // The watchdog can delete its own launcher after detecting a stale
            // parent.  The ownership cleanup script is the stable entrypoint.
            cleanupOwnedRules(sessionId).getOrThrow()
        }
        leaseTask?.cancel(true)
        ackTask?.cancel(true)
        leaseTask = null
        ackTask = null
        ackHealthy = false
        stopWatchdogProcess()
        clearRuntimeFiles(sessionId)
        activeSessionId = ""
    }

    fun close() {
        // Do not kill the external watchdog while an ownership session is
        // still live.  A failed cleanup must remain supervised until the
        // watchdog or a later retry can remove the rules safely.
        if (activeSessionId.isBlank()) {
            stop(cleanupRules = false)
        }
        scheduler.shutdownNow()
    }

    internal fun cleanupOwnedRules(sessionId: String = activeSessionId): Result<Unit> = runCatching {
        if (sessionId.isBlank()) return@runCatching
        val result = executor.execute(listOf("/system/bin/sh", CLEANUP_PATH, "cleanup", sessionId))
        check(result.success) { "Root watchdog cleanup failed: ${result.output}" }
    }

    internal fun installScripts(): Result<Unit> = runCatching {
        val runtimeDir = File(RUNTIME_DIR)
        check(!Files.isSymbolicLink(runtimeDir.toPath())) { "Root watchdog directory cannot be a symbolic link" }
        check(!runtimeDir.exists() || runtimeDir.isDirectory) { "Root watchdog path is not a directory" }
        check(runtimeDir.exists() || runtimeDir.mkdirs()) { "Cannot create $RUNTIME_DIR" }
        Os.chmod(runtimeDir.absolutePath, 0b111000000)
        val script = context.assets.open("root/kunbox-root-watchdog.sh").bufferedReader().use { it.readText() }
        val cleanupScript = context.assets.open("root/kunbox-root-cleanup-owned.sh")
            .bufferedReader()
            .use { it.readText() }
        installScript(File(WATCHDOG_PATH), script, "watchdog")
        installScript(File(CLEANUP_PATH), cleanupScript, "cleanup")
    }

    @Suppress("ReturnCount")
    private fun stopExistingWatchdog() {
        val pidFile = File(RUNTIME_DIR, "watchdog.pid")
        if (!pidFile.exists()) return
        val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull() ?: run {
            check(pidFile.delete()) { "Cannot remove malformed Root watchdog PID file" }
            return
        }
        val identity = readWatchdogIdentity(pid) ?: run {
            if (!File("/proc/$pid").exists()) runCatching { pidFile.delete() }
            else error("Cannot verify existing Root watchdog PID $pid")
            return
        }
        executor.execute(listOf("kill", "-TERM", pid.toString()))
        awaitWatchdogExit(pid, identity)
        if (readWatchdogIdentity(pid) == null) {
            runCatching { pidFile.delete() }
            return
        }
        check(readWatchdogIdentity(pid) == identity) { "Root watchdog PID was reused" }
        executor.execute(listOf("kill", "-KILL", pid.toString()))
        awaitWatchdogExit(pid, identity)
        check(readWatchdogIdentity(pid) == null) { "Existing Root watchdog did not exit" }
        runCatching { pidFile.delete() }
    }

    private fun clearRuntimeFiles(sessionId: String) {
        if (sessionId.isBlank()) return
        val runtimeDir = File(RUNTIME_DIR)
        val sessionFile = File(runtimeDir, "session")
        if (!sessionFile.isFile || Files.isSymbolicLink(sessionFile.toPath())) return
        if (sessionFile.readText().trim() != sessionId) return
        listOf("lease", "watchdog_ack", "watchdog.pid", "watchdog.sh").forEach { name ->
            val file = File(runtimeDir, name)
            check(!Files.isSymbolicLink(file.toPath())) { "Root watchdog runtime file is a symbolic link: $name" }
            check(!file.exists() || file.delete()) { "Cannot remove Root watchdog runtime file: $name" }
        }
        check(!sessionFile.exists() || sessionFile.delete()) { "Cannot remove Root watchdog session file" }
    }

    private data class WatchdogIdentity(val commandLine: String, val startTime: String)

    private fun readWatchdogIdentity(pid: Int): WatchdogIdentity? = runCatching {
        require(pid > 1)
        val processDir = File("/proc/$pid")
        check(processDir.exists())
        val commandLine = File(processDir, "cmdline").readText()
        check(WATCHDOG_PATH in commandLine)
        val stat = File(processDir, "stat").readText()
        val fields = stat.substringAfterLast(") ", "").trim().split(' ').filter(String::isNotBlank)
        WatchdogIdentity(commandLine, checkNotNull(fields.getOrNull(19)))
    }.getOrNull()

    private fun awaitWatchdogExit(pid: Int, identity: WatchdogIdentity) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_WAIT_MS)
        val latch = CountDownLatch(1)
        while (System.nanoTime() < deadline) {
            val current = readWatchdogIdentity(pid)
            if (current == null) return
            check(current == identity) { "Root watchdog PID was reused" }
            val remaining = deadline - System.nanoTime()
            if (remaining > 0L) latch.await(
                minOf(remaining, TimeUnit.MILLISECONDS.toNanos(25L)),
                TimeUnit.NANOSECONDS
            )
        }
    }

    private fun stopWatchdogProcess() {
        val process = watchdogProcess ?: return
        process.destroy()
        if (!runCatching { process.waitFor(STOP_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
            process.destroyForcibly()
            runCatching { process.waitFor(STOP_WAIT_MS, TimeUnit.MILLISECONDS) }
        }
        watchdogProcess = null
    }

    private fun requireCurrentProcessStartTime(): String {
        val stat = File("/proc/self/stat").readText()
        return stat.substringAfterLast(") ", "")
            .trim()
            .split(' ')
            .filter(String::isNotBlank)
            .getOrNull(19)
            ?.takeIf { it.all(Char::isDigit) }
            ?: error("Cannot read Root process start time")
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
        val parent = target.parentFile ?: error("Root watchdog target has no parent")
        check(!Files.isSymbolicLink(parent.toPath()) && parent.isDirectory) {
            "Root watchdog target directory is unsafe"
        }
        check(!Files.isSymbolicLink(target.toPath()) && (!target.exists() || target.isFile)) {
            "Root watchdog target is unsafe: ${target.name}"
        }
        val temp = File.createTempFile(".${target.name}.", ".tmp", parent)
        try {
            FileOutputStream(temp).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            Os.rename(temp.absolutePath, target.absolutePath)
        } finally {
            if (temp.exists()) check(temp.delete()) { "Cannot remove Root watchdog temporary file" }
        }
    }

    private fun installScript(target: File, expectedContent: String, label: String) {
        check(!Files.isSymbolicLink(target.toPath()) && (!target.exists() || target.isFile)) {
            "Root $label script path is unsafe"
        }
        if (!rootScriptContentMatches(target.takeIf(File::isFile)?.readText(Charsets.UTF_8), expectedContent)) {
            writeAtomically(target, expectedContent)
        }
        check(target.isFile && target.readText(Charsets.UTF_8) == expectedContent) {
            "Root $label script content does not match this APK"
        }
        Os.chmod(target.absolutePath, 0b111100000)
    }
}

internal fun rootScriptContentMatches(installed: String?, bundled: String): Boolean =
    installed != null && installed == bundled

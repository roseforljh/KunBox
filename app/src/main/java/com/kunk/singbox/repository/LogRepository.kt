package com.kunk.singbox.repository

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import com.kunk.singbox.ipc.VpnStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal data class LogPersistenceBatch(
    val lines: List<String>,
    val rewriteAll: Boolean,
    val generation: Long,
    val queueGeneration: Long
)

private const val LOG_FILE_GENERATION_PREFIX = "# KunBox log generation: "

internal fun mergeLogLinesForRewrite(
    persistedLines: List<String>,
    batchLines: List<String>,
    maxLines: Int
): List<String> {
    require(maxLines >= 0)
    return selectBoundedLogLines(persistedLines + batchLines, maxLines)
}

internal fun selectLogReloadLines(
    persistedLines: List<String>,
    maxLines: Int
): List<String> {
    require(maxLines >= 0)
    return selectBoundedLogLines(persistedLines.filter { it.isNotBlank() }, maxLines)
}

internal fun selectBoundedLogLines(lines: List<String>, maxLines: Int): List<String> {
    require(maxLines >= 0)
    val diagnosticCount = lines.count(LogRepository::isPreservedDiagnosticLine)
    return when {
        maxLines == 0 || lines.isEmpty() -> emptyList()
        lines.size <= maxLines -> lines
        diagnosticCount >= maxLines -> {
            lines.filter(LogRepository::isPreservedDiagnosticLine).takeLast(maxLines)
        }
        else -> {
            var ordinaryToSkip = lines.size - diagnosticCount - (maxLines - diagnosticCount)
            // ponytail: 上限固定为 2000，单次线性筛选比维护第二套索引更简单且不易失序。
            lines.filter { line ->
                LogRepository.isPreservedDiagnosticLine(line) || if (ordinaryToSkip > 0) {
                    ordinaryToSkip--
                    false
                } else {
                    true
                }
            }
        }
    }
}

private fun ArrayDeque<String>.addBoundedLogLine(line: String, maxLines: Int): Boolean {
    require(maxLines > 0)
    if (size < maxLines) {
        addLast(line)
        return true
    }
    val removedOrdinary = removeFirstMatching { !LogRepository.isPreservedDiagnosticLine(it) }
    if (!removedOrdinary && !LogRepository.isPreservedDiagnosticLine(line)) return false
    if (!removedOrdinary) removeFirst()
    addLast(line)
    return true
}

private fun <T> ArrayDeque<T>.removeFirstMatching(predicate: (T) -> Boolean): Boolean {
    val iterator = iterator()
    while (iterator.hasNext()) {
        if (predicate(iterator.next())) {
            iterator.remove()
            return true
        }
    }
    return false
}

internal fun selectLogGeneration(knownGeneration: Long, sharedGeneration: Long): Long {
    return maxOf(knownGeneration, sharedGeneration)
}

internal fun writeLogLinesAtomically(file: File, generation: Long, lines: List<String>) {
    writeTextAtomically(
        file,
        buildString {
            appendLine("$LOG_FILE_GENERATION_PREFIX$generation")
            lines.forEach { appendLine(it) }
        }
    )
}

internal fun writeLogBatchIfCurrent(
    file: File,
    currentGeneration: Long,
    batch: LogPersistenceBatch,
    maxLines: Int
): Boolean {
    require(maxLines >= 0)
    if (batch.generation != currentGeneration) return false

    if (batch.rewriteAll || !fileHasGenerationMarker(file, currentGeneration)) {
        val persistedLines = readPersistedLogLines(file, currentGeneration, maxLines)
        writeLogLinesAtomically(
            file,
            currentGeneration,
            mergeLogLinesForRewrite(persistedLines, batch.lines, maxLines)
        )
    } else {
        file.parentFile?.mkdirs()
        file.appendText(
            batch.lines.joinToString(separator = "\n", postfix = "\n"),
            Charsets.UTF_8
        )
    }
    return true
}

private fun fileHasGenerationMarker(file: File, generation: Long): Boolean {
    if (!file.exists()) return false
    return file.bufferedReader(Charsets.UTF_8).use { reader ->
        reader.readLine() == "$LOG_FILE_GENERATION_PREFIX$generation"
    }
}

@Suppress("CognitiveComplexMethod", "ReturnCount")
internal fun readPersistedLogLines(file: File, generation: Long, maxLines: Int): List<String> {
    require(maxLines >= 0)
    if (!file.exists() || maxLines == 0) return emptyList()
    val lines = ArrayDeque<String>(maxLines)

    fun addLine(line: String) {
        lines.addBoundedLogLine(line, maxLines)
    }

    file.bufferedReader(Charsets.UTF_8).use { reader ->
        val firstLine = reader.readLine() ?: return emptyList()
        if (firstLine.startsWith(LOG_FILE_GENERATION_PREFIX)) {
            val fileGeneration = firstLine.removePrefix(LOG_FILE_GENERATION_PREFIX).toLongOrNull()
                ?: return emptyList()
            if (fileGeneration != generation) return emptyList()
        } else {
            if (generation != 0L) return emptyList()
            addLine(firstLine)
        }

        while (true) {
            addLine(reader.readLine() ?: break)
        }
    }
    return lines.toList()
}

internal fun readLogGeneration(file: File): Long {
    if (!file.exists()) return 0L
    return file.readText(Charsets.UTF_8).trim().toLongOrNull()
        ?: throw IOException("Invalid log generation: ${file.absolutePath}")
}

private fun writeTextAtomically(file: File, content: String) {
    val parent = file.absoluteFile.parentFile ?: error("日志文件缺少父目录")
    check(parent.exists() || parent.mkdirs()) { "无法创建日志目录: ${parent.absolutePath}" }
    val temporaryFile = File.createTempFile(file.name.padEnd(3, '_') + ".", ".tmp", parent)
    try {
        FileOutputStream(temporaryFile).use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        replaceFileAtomically(temporaryFile, file)
    } finally {
        temporaryFile.delete()
    }
}

@SuppressLint("NewApi")
private fun replaceFileAtomically(source: File, target: File) {
    // 本地单测的 SDK 值为 0，使用 JDK 的原子移动验证同一写入路径。
    if (Build.VERSION.SDK_INT == 0 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } else {
        Os.rename(source.absolutePath, target.absolutePath)
    }
}

internal class LogPersistenceQueue(private val maxPendingLines: Int = 2000) {
    private class PendingLine(
        val line: String,
        var rewriteAll: Boolean,
        val generation: Long
    )

    private val pendingLines = ArrayDeque<PendingLine>(maxPendingLines)
    private var generation = 0L

    init {
        require(maxPendingLines > 0)
    }

    @Synchronized
    fun enqueue(line: String, rewriteAll: Boolean, generation: Long) {
        var requiresRewrite = rewriteAll
        if (pendingLines.size >= maxPendingLines) {
            val removedOrdinary = pendingLines.removeFirstMatching {
                !LogRepository.isPreservedDiagnosticLine(it.line)
            }
            if (!removedOrdinary && !LogRepository.isPreservedDiagnosticLine(line)) return
            if (!removedOrdinary) pendingLines.removeFirst()
            requiresRewrite = true
        }
        pendingLines.addLast(PendingLine(line, requiresRewrite, generation))
    }

    @Synchronized
    fun drain(): LogPersistenceBatch? {
        val firstGeneration = pendingLines.peekFirst()?.generation ?: return null
        val lines = mutableListOf<String>()
        var rewriteAll = false
        while (pendingLines.peekFirst()?.generation == firstGeneration) {
            val pending = pendingLines.removeFirst()
            lines += pending.line
            rewriteAll = rewriteAll || pending.rewriteAll
        }
        val batch = LogPersistenceBatch(
            lines = lines,
            rewriteAll = rewriteAll,
            generation = firstGeneration,
            queueGeneration = this.generation
        )
        return batch
    }

    @Synchronized
    fun restore(batch: LogPersistenceBatch) {
        if (batch.queueGeneration != generation) return
        batch.lines.asReversed().forEach { line ->
            pendingLines.addFirst(PendingLine(line, rewriteAll = false, generation = batch.generation))
        }
        if (batch.rewriteAll) pendingLines.peekFirst()?.rewriteAll = true
        var dropped = false
        while (pendingLines.size > maxPendingLines) {
            val removedOrdinary = pendingLines.removeFirstMatching {
                !LogRepository.isPreservedDiagnosticLine(it.line)
            }
            if (!removedOrdinary) pendingLines.removeFirst()
            dropped = true
        }
        if (dropped) pendingLines.peekFirst()?.rewriteAll = true
    }

    @Synchronized
    fun clear() {
        pendingLines.clear()
        generation++
    }

    @Synchronized
    fun isStale(batch: LogPersistenceBatch): Boolean = batch.queueGeneration != generation

    @Synchronized
    fun hasPending(): Boolean = pendingLines.isNotEmpty()
}

class LogRepository private constructor() {
    private data class LogFileSnapshot(
        val lines: List<String>,
        val size: Long,
        val mtime: Long,
        val generation: Long
    )

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val maxLogSize = 2000
    private val maxLogLineLength = 2000
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val buffer = ArrayDeque<String>(maxLogSize)
    private val logVersion = AtomicLong(0)
    private val flushRunning = AtomicBoolean(false)
    private val logUiActiveCount = AtomicInteger(0)
    private val persistenceQueue = LogPersistenceQueue(maxLogSize)
    private val persistenceSignal = Channel<Unit>(Channel.CONFLATED)
    private val knownFileGeneration = AtomicLong(UNINITIALIZED_GENERATION)

    @Volatile private var enabled: Boolean = false
    @Volatile private var fileSyncJob: Job? = null
    @Volatile private var lastSyncedFileSize: Long = -1L
    @Volatile private var lastSyncedFileMtime: Long = -1L
    @Volatile private var lastSyncedFileGeneration: Long = UNINITIALIZED_GENERATION

    init {
        scope.launch(Dispatchers.IO) {
            for (ignored in persistenceSignal) {
                delay(FILE_WRITE_BATCH_DELAY_MS)
                persistPendingLogs()
            }
        }
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        if (value) {
            if (refreshKnownFileGeneration() == null) return
            enabled = true
            if (logUiActiveCount.get() > 0) {
                reloadFromFileBestEffort()
                startFileSyncLoopIfNeeded()
            }
            addDebugModeEnabledLog()
        } else {
            enabled = false
            clearLogs(preserveRecoveryDiagnostics = true)
            stopFileSyncLoop()
        }
    }

    fun isEnabled(): Boolean = enabled

    fun setLogUiActive(active: Boolean) {
        if (active) {
            val count = logUiActiveCount.incrementAndGet()
            if (!enabled) {
                _logs.value = emptyList()
                return
            }
            if (count == 1) {
                reloadFromFileBestEffort()
                startFileSyncLoopIfNeeded()
            }
            requestFlush()
        } else {
            while (true) {
                val cur = logUiActiveCount.get()
                if (cur <= 0) return
                if (logUiActiveCount.compareAndSet(cur, cur - 1)) {
                    if (cur - 1 <= 0) {
                        stopFileSyncLoop()
                    }
                    return
                }
            }
        }
    }

    /**
     * 恢复链路诊断：调试开关关闭时也要落盘，否则远程只能看到内核流量。
     */
    fun addAlwaysLog(message: String) {
        appendLogLine(message, requireEnabled = false)
    }

    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
    fun addLog(message: String) {
        appendLogLine(message, requireEnabled = true)
    }

    @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
    private fun appendLogLine(message: String, requireEnabled: Boolean) {
        if (requireEnabled && !enabled) return

        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }

        if (message.contains("TRACE")) {
            return
        }

        if (message.contains("DEBUG")) {
            val isHighFreq = message.contains("selector: selected outbound") ||
                message.contains("dns: cached")

            if (isHighFreq) return
        }

        val formattedLog = "[$timestamp] $message"
        val finalLog = if (formattedLog.length > maxLogLineLength) {
            formattedLog.substring(0, maxLogLineLength)
        } else {
            formattedLog
        }

        synchronized(buffer) {
            if (requireEnabled && !enabled) return
            // 诊断日志在调试关时也要写；生成号未初始化时先从文件补齐
            if (currentLogGeneration() == UNINITIALIZED_GENERATION) {
                refreshKnownFileGeneration()
            }
            val fileGeneration = currentLogGeneration()
            if (fileGeneration == UNINITIALIZED_GENERATION) return
            knownFileGeneration.set(fileGeneration)
            val shouldRewriteFile = buffer.size >= maxLogSize
            if (!buffer.addBoundedLogLine(finalLog, maxLogSize)) return
            logVersion.incrementAndGet()
            persistenceQueue.enqueue(finalLog, shouldRewriteFile, fileGeneration)
        }
        persistenceSignal.trySend(Unit)

        requestFlush()
    }

    private fun currentLogGeneration(): Long {
        val knownGeneration = knownFileGeneration.get()
        val sharedGeneration = appContext?.let { VpnStateStore.getLogClearGeneration() } ?: knownGeneration
        return selectLogGeneration(knownGeneration, sharedGeneration)
    }

    private fun requestFlush() {
        if (logUiActiveCount.get() <= 0) return
        if (!flushRunning.compareAndSet(false, true)) return

        scope.launch {
            var lastSeenVersion = logVersion.get()
            delay(200)

            while (true) {
                val snapshot = synchronized(buffer) {
                    buffer.toList()
                }
                _logs.value = snapshot

                val nowVersion = logVersion.get()
                if (nowVersion == lastSeenVersion) {
                    flushRunning.set(false)
                    if (logVersion.get() != nowVersion) {
                        if (flushRunning.compareAndSet(false, true)) {
                            lastSeenVersion = logVersion.get()
                            delay(200)
                            continue
                        }
                    }
                    break
                }

                lastSeenVersion = nowVersion
                delay(200)
            }
        }
    }

    @Suppress("CognitiveComplexMethod")
    fun clearLogs(preserveRecoveryDiagnostics: Boolean = false) {
        var memoryCleared = false
        try {
            val locked = withLogFileLock { file, generationFile ->
                val currentGeneration = readLogGeneration(generationFile)
                val preserved = preservedLogs(file, currentGeneration, preserveRecoveryDiagnostics)
                val nextGeneration = currentGeneration + 1L
                writeTextAtomically(generationFile, "$nextGeneration\n")
                VpnStateStore.setLogClearGeneration(nextGeneration)
                knownFileGeneration.set(nextGeneration)
                replaceMemoryLogs(preserved)
                memoryCleared = true
                writeLogLinesAtomically(file, nextGeneration, preserved)
                lastSyncedFileSize = -1L
                lastSyncedFileMtime = -1L
                lastSyncedFileGeneration = UNINITIALIZED_GENERATION
            }
            if (locked == null) {
                replaceMemoryLogs(preservedLogs(null, 0L, preserveRecoveryDiagnostics))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!memoryCleared) clearMemoryLogs()
            Log.e("LogRepository", "Failed to clear persisted logs", e)
        }
    }

    private fun preservedLogs(file: File?, generation: Long, preserve: Boolean): List<String> {
        if (!preserve) return emptyList()
        val fromFile = file?.let { readPersistedLogLines(it, generation, maxLogSize) }.orEmpty()
        val fromMemory = synchronized(buffer) { buffer.toList() }
        return (fromFile + fromMemory)
            .filter { isPreservedDiagnosticLine(it) }
            .distinct()
            .takeLast(maxLogSize)
    }

    private fun replaceMemoryLogs(lines: List<String>) {
        synchronized(buffer) {
            persistenceQueue.clear()
            buffer.clear()
            buffer.addAll(lines)
            logVersion.incrementAndGet()
        }
        _logs.value = lines
    }

    private fun clearMemoryLogs() {
        replaceMemoryLogs(emptyList())
    }

    fun getLogsAsText(): String {
        return buildLogsText()
    }

    suspend fun getLogsAsTextForExport(): String = withContext(Dispatchers.IO) {
        reloadFromFileBestEffort()
        buildLogsText()
    }

    private fun buildLogsText(): String {
        val exportDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val header = buildString {
            appendLine("=== KunBox Logs ===")
            appendLine("Export Time: ${exportDateFormat.format(Date())}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            buildDebugPathLines().forEach { appendLine(it) }
            appendLine("========================")
            appendLine()
        }

        val logContent = synchronized(buffer) {
            buffer.joinToString("\n")
        }

        return header + logContent
    }

    companion object {
        private const val RUNNING_LOG_FILE = "running.log"
        private const val RUNNING_LOG_LOCK_FILE = "running.log.lock"
        private const val RUNNING_LOG_GENERATION_FILE = "running.log.generation"
        private const val RUNNING_CONFIG_FILE = "running_config.json"
        private const val EXPORTS_DIR = "exports"
        private const val FILE_WRITE_BATCH_DELAY_MS = 100L
        private const val UNINITIALIZED_GENERATION = -1L
        private const val PRESERVED_DIAGNOSTIC_MARKER = "[Recovery]"

        private val processFileLock = Any()

        @Volatile
        private var instance: LogRepository? = null

        @Volatile
        private var appContext: Context? = null

        fun init(context: Context) {
            appContext = context.applicationContext
        }

        fun getInstance(): LogRepository {
            return instance ?: synchronized(this) {
                instance ?: LogRepository().also { instance = it }
            }
        }

        internal fun isPreservedDiagnosticLine(line: String): Boolean {
            return line.contains(PRESERVED_DIAGNOSTIC_MARKER) ||
                line.contains(" resource_fd ") ||
                line.contains(" resource_fd_breakdown ") ||
                line.contains(" resource_exhausted ")
        }
    }

    private fun addDebugModeEnabledLog() {
        addLog("INFO [DBG] Debug logging enabled")
        buildDebugPathLines().forEach { line ->
            addLog("INFO [DBG] $line")
        }
    }

    private fun buildDebugPathLines(): List<String> {
        val ctx = appContext ?: return emptyList()
        val exportDir = ctx.getExternalFilesDir(null)?.let { File(it, EXPORTS_DIR).absolutePath }
            ?: "(unavailable)"
        return listOf(
            "Log File: ${File(ctx.filesDir, RUNNING_LOG_FILE).absolutePath}",
            "Running Config: ${File(ctx.filesDir, RUNNING_CONFIG_FILE).absolutePath}",
            "Running Config Export Dir: $exportDir"
        )
    }

    private fun persistPendingLogs() {
        val batch = synchronized(buffer) {
            persistenceQueue.drain() ?: return
        }
        val persisted = try {
            if (persistenceQueue.isStale(batch)) true else writeBatchToFile(batch)
        } catch (e: CancellationException) {
            persistenceQueue.restore(batch)
            throw e
        }
        if (!persisted) {
            persistenceQueue.restore(batch)
        }
        if (persistenceQueue.hasPending()) {
            persistenceSignal.trySend(Unit)
        }
    }

    private fun writeBatchToFile(batch: LogPersistenceBatch): Boolean {
        return try {
            withLogFileLock { file, generationFile ->
                val currentGeneration = readLogGeneration(generationFile)
                knownFileGeneration.set(currentGeneration)
                writeLogBatchIfCurrent(file, currentGeneration, batch, maxLogSize)
                true
            } ?: true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("LogRepository", "Failed to persist log batch", e)
            false
        }
    }

    private fun reloadFromFileBestEffort() {
        if (persistenceQueue.hasPending()) return
        val snapshot = readFileSnapshotBestEffort() ?: return
        val changed = applyFileSnapshot(snapshot)
        if (changed != null) {
            markFileSnapshotSynced(snapshot)
        }
    }

    @Synchronized
    private fun startFileSyncLoopIfNeeded() {
        if (fileSyncJob?.isActive == true) return
        fileSyncJob = scope.launch(Dispatchers.IO) {
            while (logUiActiveCount.get() > 0) {
                syncFromFileOnceBestEffort()
                delay(600)
            }
        }
    }

    @Synchronized
    private fun stopFileSyncLoop() {
        fileSyncJob?.cancel()
        fileSyncJob = null
    }

    private fun syncFromFileOnceBestEffort() {
        if (persistenceQueue.hasPending()) return
        val snapshot = readFileSnapshotBestEffort(lastSyncedFileSize, lastSyncedFileMtime) ?: return
        val changed = applyFileSnapshot(snapshot)
        if (changed == null) return
        markFileSnapshotSynced(snapshot)
        if (changed) requestFlush()
    }

    private fun applyFileSnapshot(snapshot: LogFileSnapshot): Boolean? {
        return try {
            withLogFileLock { _, generationFile ->
                val currentGeneration = readLogGeneration(generationFile)
                knownFileGeneration.set(currentGeneration)
                if (currentGeneration != snapshot.generation) {
                    return@withLogFileLock null
                }
                synchronized(buffer) {
                    if (snapshot.generation != knownFileGeneration.get() || persistenceQueue.hasPending()) {
                        return@synchronized null
                    }
                    val current = buffer.toList()
                    val selected = selectLogReloadLines(snapshot.lines, maxLogSize)
                    if (selected == current) {
                        false
                    } else {
                        buffer.clear()
                        buffer.addAll(selected)
                        logVersion.incrementAndGet()
                        true
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("LogRepository", "Failed to apply persisted logs", e)
            null
        }
    }

    private fun markFileSnapshotSynced(snapshot: LogFileSnapshot) {
        lastSyncedFileSize = snapshot.size
        lastSyncedFileMtime = snapshot.mtime
        lastSyncedFileGeneration = snapshot.generation
    }

    private fun refreshKnownFileGeneration(): Long? {
        if (appContext == null) {
            knownFileGeneration.compareAndSet(UNINITIALIZED_GENERATION, 0L)
            return knownFileGeneration.get()
        }
        return try {
            val generation = withLogFileLock { _, generationFile -> readLogGeneration(generationFile) }
                ?: return null
            knownFileGeneration.set(generation)
            generation
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("LogRepository", "Failed to read log generation", e)
            null
        }
    }

    private fun readFileSnapshotBestEffort(
        unchangedSize: Long = Long.MIN_VALUE,
        unchangedMtime: Long = Long.MIN_VALUE
    ): LogFileSnapshot? {
        return try {
            withLogFileLock { file, generationFile ->
                val generation = readLogGeneration(generationFile)
                knownFileGeneration.set(generation)
                if (!file.exists()) {
                    null
                } else {
                    val size = file.length()
                    val mtime = file.lastModified()
                    if (
                        generation == lastSyncedFileGeneration &&
                        size == unchangedSize &&
                        mtime == unchangedMtime
                    ) {
                        null
                    } else {
                        LogFileSnapshot(
                            lines = readPersistedLogLines(file, generation, maxLogSize),
                            size = size,
                            mtime = mtime,
                            generation = generation
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("LogRepository", "Failed to reload persisted logs", e)
            null
        }
    }

    private fun <T> withLogFileLock(block: (File, File) -> T): T? {
        val ctx = appContext ?: return null
        val directory = ctx.filesDir
        check(directory.exists() || directory.mkdirs()) { "无法创建日志目录: ${directory.absolutePath}" }
        val logFile = File(directory, RUNNING_LOG_FILE)
        val generationFile = File(directory, RUNNING_LOG_GENERATION_FILE)
        val lockFile = File(directory, RUNNING_LOG_LOCK_FILE)
        return synchronized(processFileLock) {
            RandomAccessFile(lockFile, "rw").use { lockAccess ->
                lockAccess.channel.lock().use {
                    block(logFile, generationFile)
                }
            }
        }
    }
}

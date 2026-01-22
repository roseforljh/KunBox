package com.kunk.singbox.repository

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import android.os.Build

class LogRepository private constructor() {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    // 当前过滤的日志类别（null = 显示全部）
    private val _currentFilter = MutableStateFlow<String?>(null)
    val currentFilter: StateFlow<String?> = _currentFilter.asStateFlow()

    // 可用的日志类别列表
    val availableCategories = listOf("CONN", "VPN", "CFG", "NET", "ERR", "DBG", "INFO")

    private val maxLogSize = 500
    private val maxLogLineLength = 2000
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val buffer = ArrayDeque<String>(maxLogSize)
    private val logVersion = AtomicLong(0)
    private val flushRunning = AtomicBoolean(false)
    private val logUiActiveCount = AtomicInteger(0)

    @Volatile private var fileSyncJob: Job? = null
    @Volatile private var lastSyncedFileSize: Long = -1L
    @Volatile private var lastSyncedFileMtime: Long = -1L

    fun setLogUiActive(active: Boolean) {
        if (active) {
            val count = logUiActiveCount.incrementAndGet()
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

    fun addLog(message: String) {
        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }
        // 过滤掉过于频繁的无用日志
        if (message.contains("DEBUG") &&
            (message.contains("selector: selected outbound") ||
                message.contains("dns: exchange") ||
                message.contains("internet v4 address"))) {
            return
        }

        val formattedLog = "[$timestamp] $message"
        val finalLog = if (formattedLog.length > maxLogLineLength) {
            formattedLog.substring(0, maxLogLineLength)
        } else {
            formattedLog
        }

        var shouldRewriteFile = false
        synchronized(buffer) {
            if (buffer.size >= maxLogSize) {
                buffer.removeFirst()
                shouldRewriteFile = true
            }
            buffer.addLast(finalLog)
            logVersion.incrementAndGet()
        }

        writeToFileBestEffort(finalLog, shouldRewriteFile)

        requestFlush()
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

    fun clearLogs() {
        synchronized(buffer) {
            buffer.clear()
            logVersion.incrementAndGet()
        }
        _logs.value = emptyList()
        clearFileBestEffort()
    }

    /**
     * 设置日志过滤类别
     * @param category 类别前缀（如 "CONN", "VPN", "ERR"），null 表示显示全部
     */
    fun setFilter(category: String?) {
        _currentFilter.value = category
        requestFlush()
    }

    /**
     * 获取过滤后的日志
     */
    fun getFilteredLogs(): List<String> {
        val filter = _currentFilter.value
        val allLogs = synchronized(buffer) { buffer.toList() }

        return if (filter == null) {
            allLogs
        } else {
            allLogs.filter { log ->
                // 匹配格式: [时间] emoji [类别][级别] ...
                log.contains("[$filter]")
            }
        }
    }

    /**
     * 搜索日志
     * @param keyword 关键词
     * @return 匹配的日志列表
     */
    fun searchLogs(keyword: String): List<String> {
        if (keyword.isBlank()) return getFilteredLogs()

        val keywordLower = keyword.lowercase()
        return getFilteredLogs().filter { log ->
            log.lowercase().contains(keywordLower)
        }
    }

    /**
     * 获取错误日志摘要（用于快速定位问题）
     */
    fun getErrorSummary(): List<String> {
        return synchronized(buffer) {
            buffer.filter { log ->
                log.contains("[ERR]") || log.contains("[E]") || log.contains("❌")
            }.toList()
        }
    }

    fun getLogsAsText(): String {
        val exportDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val header = buildString {
            appendLine("=== KunBox 运行日志 ===")
            appendLine("导出时间: ${exportDateFormat.format(Date())}")
            appendLine("设备型号: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android 版本: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("========================")
            appendLine()
        }

        val logContent = synchronized(buffer) {
            buffer.joinToString("\n")
        }

        return header + logContent
    }

    companion object {
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
    }

    private fun getLogFile(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, "running.log")
    }

    private fun writeToFileBestEffort(line: String, rewriteAll: Boolean) {
        val file = getLogFile() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            if (!rewriteAll && file.exists()) {
                file.appendText(line + "\n")
            } else {
                val snapshot = synchronized(buffer) { buffer.toList() }
                file.writeText(snapshot.joinToString("\n") + if (snapshot.isNotEmpty()) "\n" else "")
            }
        }
    }

    private fun clearFileBestEffort() {
        val file = getLogFile() ?: return
        runCatching {
            if (file.exists()) {
                file.writeText("")
            }
        }
    }

    private fun reloadFromFileBestEffort() {
        val file = getLogFile() ?: return
        runCatching {
            if (!file.exists()) return
            val lines = readLastLines(file, maxLogSize)
            synchronized(buffer) {
                buffer.clear()
                for (l in lines) {
                    if (l.isNotBlank()) buffer.addLast(l)
                }
                logVersion.incrementAndGet()
            }
        }
    }

    private fun startFileSyncLoopIfNeeded() {
        if (fileSyncJob?.isActive == true) return
        fileSyncJob = scope.launch {
            while (logUiActiveCount.get() > 0) {
                syncFromFileOnceBestEffort()
                delay(600)
            }
        }
    }

    private fun stopFileSyncLoop() {
        fileSyncJob?.cancel()
        fileSyncJob = null
    }

    private fun syncFromFileOnceBestEffort() {
        val file = getLogFile() ?: return
        runCatching {
            if (!file.exists()) return
            val size = file.length()
            val mtime = file.lastModified()
            if (size == lastSyncedFileSize && mtime == lastSyncedFileMtime) return
            lastSyncedFileSize = size
            lastSyncedFileMtime = mtime

            val lines = readLastLines(file, maxLogSize)
            val changed = synchronized(buffer) {
                val current = buffer.toList()
                if (current == lines) {
                    false
                } else {
                    buffer.clear()
                    for (l in lines) {
                        if (l.isNotBlank()) buffer.addLast(l)
                    }
                    logVersion.incrementAndGet()
                    true
                }
            }
            if (changed) requestFlush()
        }
    }

    private fun readLastLines(file: File, maxLines: Int): List<String> {
        val deque = ArrayDeque<String>(maxLines)
        file.useLines { seq ->
            seq.forEach { line ->
                if (deque.size >= maxLines) deque.removeFirst()
                deque.addLast(line)
            }
        }
        return deque.toList()
    }
}

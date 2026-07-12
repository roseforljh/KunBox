package com.kunk.singbox.repository

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRepositoryPersistencePolicyTest {

    @Test
    fun pendingLinesAreDrainedAsOneBatch() {
        val queue = LogPersistenceQueue()

        queue.enqueue("first", rewriteAll = false, generation = 3L)
        queue.enqueue("second", rewriteAll = true, generation = 3L)
        queue.enqueue("third", rewriteAll = false, generation = 3L)

        val batch = queue.drain()

        assertEquals(listOf("first", "second", "third"), batch?.lines)
        assertTrue(batch?.rewriteAll == true)
        assertEquals(3L, batch?.generation)
        assertNull(queue.drain())
    }

    @Test
    fun clearInvalidatesAlreadyDrainedBatch() {
        val queue = LogPersistenceQueue()
        queue.enqueue("stale", rewriteAll = false, generation = 7L)
        val staleBatch = requireNotNull(queue.drain())

        queue.clear()

        assertTrue(queue.isStale(staleBatch))
    }

    @Test
    fun sharedClearGenerationRejectsStaleBatchAndAllowsFreshBatch() {
        val tempDir = Files.createTempDirectory("log-generation-write-").toFile()
        try {
            val logFile = File(tempDir, "running.log")
            writeLogLinesAtomically(logFile, generation = 8L, lines = listOf("existing"))

            val staleBatch = LogPersistenceBatch(
                lines = listOf("stale"),
                rewriteAll = false,
                generation = 7L,
                queueGeneration = 0L
            )
            assertFalse(writeLogBatchIfCurrent(logFile, 8L, staleBatch, maxLines = 10))
            assertEquals(listOf("existing"), readPersistedLogLines(logFile, 8L, maxLines = 10))

            val freshBatch = staleBatch.copy(lines = listOf("fresh"), generation = 8L)
            assertTrue(writeLogBatchIfCurrent(logFile, 8L, freshBatch, maxLines = 10))
            assertEquals(
                listOf("existing", "fresh"),
                readPersistedLogLines(logFile, 8L, maxLines = 10)
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun sharedClearGenerationIsUsedForNewLogLines() {
        assertEquals(8L, selectLogGeneration(knownGeneration = 7L, sharedGeneration = 8L))
        assertEquals(8L, selectLogGeneration(knownGeneration = 8L, sharedGeneration = 7L))
    }

    @Test
    fun pendingLinesFromDifferentGenerationsDrainAsSeparateBatches() {
        val queue = LogPersistenceQueue()
        queue.enqueue("old-1", rewriteAll = false, generation = 7L)
        queue.enqueue("old-2", rewriteAll = true, generation = 7L)
        queue.enqueue("fresh", rewriteAll = false, generation = 8L)

        val oldBatch = queue.drain()
        val freshBatch = queue.drain()

        assertEquals(7L, oldBatch?.generation)
        assertEquals(listOf("old-1", "old-2"), oldBatch?.lines)
        assertTrue(oldBatch?.rewriteAll == true)
        assertEquals(8L, freshBatch?.generation)
        assertEquals(listOf("fresh"), freshBatch?.lines)
    }

    @Test
    fun failedPersistenceRestoresOriginalGenerationBatch() {
        val queue = LogPersistenceQueue()
        queue.enqueue("retry", rewriteAll = true, generation = 9L)
        val failedBatch = requireNotNull(queue.drain())

        queue.restore(failedBatch)

        val retryBatch = queue.drain()
        assertEquals(9L, retryBatch?.generation)
        assertEquals(listOf("retry"), retryBatch?.lines)
        assertTrue(retryBatch?.rewriteAll == true)
    }

    @Test
    fun rotationMergesDiskStateWithCurrentProcessBatch() {
        val merged = mergeLogLinesForRewrite(
            persistedLines = listOf("disk-1", "disk-2"),
            batchLines = listOf("batch-1", "batch-2"),
            maxLines = 3
        )

        assertEquals(listOf("disk-2", "batch-1", "batch-2"), merged)
    }

    @Test
    fun reloadUsesLatestDiskStateWhenQueueIsEmpty() {
        val selected = selectLogReloadLines(
            persistedLines = listOf("disk-1", "disk-2", "disk-3"),
            maxLines = 2
        )

        assertEquals(listOf("disk-2", "disk-3"), selected)
    }

    @Test
    fun atomicRewriteLeavesOnlyCompleteTargetFile() {
        val tempDir = Files.createTempDirectory("log-atomic-rewrite-").toFile()
        try {
            val logFile = File(tempDir, "running.log")
            logFile.writeText("old\n", Charsets.UTF_8)

            writeLogLinesAtomically(logFile, generation = 4L, lines = listOf("new-1", "new-2"))

            assertEquals(listOf("new-1", "new-2"), readPersistedLogLines(logFile, 4L, maxLines = 10))
            assertEquals(listOf("running.log"), tempDir.list()?.sorted())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun advancedGenerationHidesLogFileLeftByFailedClear() {
        val tempDir = Files.createTempDirectory("log-clear-generation-").toFile()
        try {
            val logFile = File(tempDir, "running.log")
            writeLogLinesAtomically(logFile, generation = 4L, lines = listOf("stale"))

            assertEquals(emptyList<String>(), readPersistedLogLines(logFile, 5L, maxLines = 10))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun invalidGenerationFileIsNotDowngradedToZero() {
        val tempDir = Files.createTempDirectory("log-invalid-generation-").toFile()
        try {
            val generationFile = File(tempDir, "running.log.generation")
            generationFile.writeText("broken", Charsets.UTF_8)

            assertThrows(IOException::class.java) {
                readLogGeneration(generationFile)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun repositoryAndViewModelDeclareConcurrencyAndIoBoundaries() {
        val repositorySource = mainSource("repository/LogRepository.kt").readNormalizedText()
        val viewModelSource = mainSource("viewmodel/LogViewModel.kt").readNormalizedText()
        val applicationSource = File("src/main/java/com/kunk/singbox/SingBoxApplication.kt").readNormalizedText()
        val addLogBody = repositorySource.functionBody("fun addLog(")
        val activeBody = repositorySource.functionBody("fun setLogUiActive(")
        val enabledBody = repositorySource.functionBody("fun setEnabled(")
        val applySnapshotBody = repositorySource.functionBody("fun applyFileSnapshot(")
        val clearBody = viewModelSource.functionBody("fun clearLogs(")

        assertTrue(repositorySource.contains("ATOMIC_MOVE"))
        assertTrue(repositorySource.contains("REPLACE_EXISTING"))
        assertTrue(repositorySource.functionBody("fun startFileSyncLoopIfNeeded(").contains("Dispatchers.IO"))
        assertTrue(addLogBody.windowed("if (!enabled) return".length).count { it == "if (!enabled) return" } >= 2)
        assertTrue(activeBody.indexOf("incrementAndGet()") < activeBody.indexOf("if (!enabled)"))
        assertFalse(enabledBody.contains("logUiActiveCount.set(0)"))
        assertTrue(enabledBody.contains("logUiActiveCount.get() > 0"))
        assertTrue(applySnapshotBody.contains("withLogFileLock"))
        assertTrue(applySnapshotBody.contains("currentGeneration != snapshot.generation"))
        assertFalse(viewModelSource.contains("\n    init {"))
        assertFalse(viewModelSource.contains("override fun onCleared()"))
        assertTrue(viewModelSource.contains(".onStart"))
        assertTrue(viewModelSource.contains(".onCompletion"))
        assertTrue(viewModelSource.contains("withContext(Dispatchers.IO)"))
        assertTrue(clearBody.contains("viewModelScope.launch(Dispatchers.IO)"))
        assertTrue(
            applicationSource.contains("withContext(Dispatchers.IO) {\n                logRepository.setEnabled")
        )
        assertTrue(applicationSource.settingsCollectorPrefix().contains("launch(Dispatchers.IO)"))
    }

    private fun mainSource(path: String): File = File("src/main/java/com/kunk/singbox/$path")

    private fun File.readNormalizedText(): String = readText(Charsets.UTF_8).replace("\r\n", "\n")

    private fun String.functionBody(startToken: String): String {
        val start = indexOf(startToken)
        require(start >= 0) { "未找到 $startToken" }
        val openingBrace = indexOf('{', start)
        require(openingBrace >= 0) { "$startToken 缺少函数体" }
        var depth = 0
        for (index in openingBrace until length) {
            when (this[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return substring(start, index + 1)
            }
        }
        error("$startToken 函数体未闭合")
    }

    private fun String.settingsCollectorPrefix(): String {
        val collectIndex = indexOf("settingsRepository.settings.collect")
        require(collectIndex >= 0) { "未找到日志设置监听" }
        return substring((collectIndex - 120).coerceAtLeast(0), collectIndex)
    }
}

package com.kunk.singbox.service.root

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootCleanupScriptTest {
    @Test
    fun absentHookUsesSnapshotAndNeverTreatsCheckExitOneAsQueryFailure() {
        val result = runCleanup("empty")

        assertEquals(result.output, 0, result.exitCode)
        assertTrue(result.output.contains("binary=ip6tables table=nat"))
        assertTrue(result.output.contains("classification=AVAILABLE"))
        assertFalse(result.commands.lineSequence().any { " -C " in it })
        assertFalse(result.commands.lineSequence().any { " -D " in it })
    }

    @Test
    fun absentChainIsCleanWhenItsTableSnapshotSucceeded() {
        val result = runCleanup("empty")

        assertEquals(result.output, 0, result.exitCode)
        assertTrue(result.output, result.output.contains("remaining_owned_chains=\"<empty>\""))
        assertFalse(result.commands.lineSequence().any { " -X KBX_RED6" in it })
    }

    @Test
    fun backendFailureRemainsQueryFailedWithRealDiagnostics() {
        val result = runCleanup("syntax_failure")

        assertEquals(result.output, 75, result.exitCode)
        assertTrue(result.output.contains("classification=QUERY_FAILED"))
        assertTrue(result.output.contains("mock syntax or backend failure"))
        assertTrue(result.output.contains("NETFILTER_VERIFICATION_FAILED"))
    }

    @Test
    fun successfulTableSnapshotWithoutTargetIsClean() {
        val result = runCleanup("empty")

        assertEquals(result.output, 0, result.exitCode)
        assertTrue(result.output.contains("backend=iptables-nft"))
        assertTrue(result.output.contains("classification=AVAILABLE"))
        assertTrue(result.output, result.output.contains("remaining_owned_rules=\"<empty>\""))
    }

    @Test
    fun confirmedIpv6JumpIsDeletedBeforeItsChain() {
        val result = runCleanup(
            "dirty",
            "-N KBX_RED6\n-A OUTPUT -j KBX_RED6\n" +
                "-A KBX_RED6 -p tcp -j REDIRECT --to-ports 1537\n"
        )
        val commands = result.commands.lineSequence().toList()
        val hook = commands.indexOfFirst { "ip6tables" in it && " -D OUTPUT -j KBX_RED6" in it }
        val flush = commands.indexOfFirst { "ip6tables" in it && " -F KBX_RED6" in it }
        val delete = commands.indexOfFirst { "ip6tables" in it && " -X KBX_RED6" in it }

        assertEquals(result.output, 0, result.exitCode)
        assertTrue(hook in 0 until flush)
        assertTrue(flush in 0 until delete)
        assertFalse(result.finalIpv6NatRules.contains("KBX_RED6"))
    }

    @Test
    fun confirmedIpv6PrivacyJumpIsDeletedBeforeItsChain() {
        val result = runCleanup(
            mode = "dirty",
            ipv6FilterRules = "-N KBX_PRIV6\n-A OUTPUT -j KBX_PRIV6\n" +
                "-A KBX_PRIV6 -m owner --uid-owner 10000-99999 -j REJECT\n"
        )
        val commands = result.commands.lineSequence().toList()
        val hook = commands.indexOfFirst { "ip6tables" in it && " -D OUTPUT -j KBX_PRIV6" in it }
        val flush = commands.indexOfFirst { "ip6tables" in it && " -F KBX_PRIV6" in it }
        val delete = commands.indexOfFirst { "ip6tables" in it && " -X KBX_PRIV6" in it }

        assertEquals(result.output, 0, result.exitCode)
        assertTrue(hook in 0 until flush)
        assertTrue(flush in 0 until delete)
        assertFalse(result.finalIpv6FilterRules.contains("KBX_PRIV6"))
    }

    @Test
    fun mdnsCidrRuleIsRecognizedAsOwnedChain() {
        val result = runCleanup(
            "dirty",
            "-N KBX_RED6\n-A OUTPUT -j KBX_RED6\n" +
                "-A KBX_RED6 -d ff02::fb/128 -p tcp -j REDIRECT --to-ports 1537\n"
        )

        assertEquals(result.output, 0, result.exitCode)
        assertFalse(result.finalIpv6NatRules.contains("KBX_RED6"))
    }

    @Test
    fun emptyDeviceAndMissingIpv6NatTableFinishQuickly() {
        val result = runCleanup("ipv6_nat_absent")

        assertEquals(result.output, 0, result.exitCode)
        assertTrue(result.output.contains("binary=ip6tables"))
        assertTrue(result.output.contains("table=nat"))
        assertTrue(result.output.contains("exitCode=3"))
        assertTrue(result.output.contains("classification=ABSENT"))
        assertEquals(12, result.commands.lineSequence().count { " -S" in it })
        assertTrue("Desktop shell cleanup took ${result.elapsedMs}ms", result.elapsedMs < 30_000L)
    }

    @Test
    fun unavailableIpv6NatDoesNotTriggerAFullSaveDump() {
        val result = runCleanup("ipv6_nat_absent_wait_unsupported")
        val saveCommands = result.commands.lineSequence().filter { it.startsWith("ip6tables-save ") }.toList()

        assertEquals(result.output, 0, result.exitCode)
        assertTrue(result.output.contains("classification=ABSENT"))
        assertTrue(saveCommands.isEmpty())
    }

    private fun runCleanup(
        mode: String,
        ipv6NatRules: String = "",
        ipv6FilterRules: String = ""
    ): CleanupResult {
        cachedResult(mode, ipv6NatRules, ipv6FilterRules)?.let { return it }
        val root = Files.createTempDirectory("kunbox-root-cleanup-test").toFile()
        val result = try {
            val runtime = root.resolve("runtime").apply { mkdirs() }
            val state = root.resolve("state").apply { mkdirs() }
            val bin = root.resolve("bin").apply { mkdirs() }
            val commandLog = root.resolve("commands.log")
            val outputLog = root.resolve("output.log")
            val source = File("src/main/assets/root/kunbox-root-cleanup-owned.sh").readText()
            val script = root.resolve("cleanup.sh")
            script.writeText(
                source.replace(
                    "RUNTIME_DIR=\"/data/adb/kunbox\"",
                    "RUNTIME_DIR=\"${runtime.posixPath()}\""
                ).replace(
                    "trap 'cleanup_temp_files' EXIT HUP INT TERM",
                    "trap ':' EXIT HUP INT TERM"
                )
            )
            val mock = File("src/test/resources/root/mock-netfilter.sh").readText()
            listOf("iptables", "ip6tables", "iptables-save", "ip6tables-save", "ip").forEach { name ->
                bin.resolve(name).apply {
                    writeText(mock)
                    setExecutable(true)
                }
            }
            writeMockRules(state, ipv6NatRules, ipv6FilterRules)
            val shellCommand = "PATH=${bin.posixPath().shellQuote()}:/usr/bin:/bin; export PATH; " +
                "exec /usr/bin/sh ${script.posixPath().shellQuote()} legacy-cleanup"
            val process = ProcessBuilder(findShell(), "-c", shellCommand)
                .redirectErrorStream(true)
                .redirectOutput(outputLog)
                .apply {
                    environment()["MOCK_MODE"] = mode
                    environment()["MOCK_STATE_DIR"] = state.posixPath()
                    environment()["MOCK_COMMAND_LOG"] = commandLog.posixPath()
                }
                .start()
            val startedAt = System.nanoTime()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val output = outputLog.takeIf(File::isFile)?.readText().orEmpty()
            check(finished) { "Cleanup script timed out: $output" }
            CleanupResult(
                exitCode = process.exitValue(),
                output = output,
                commands = commandLog.takeIf(File::isFile)?.readText().orEmpty(),
                finalIpv6NatRules = readRules(state, "ip6tables-nat.rules"),
                finalIpv6FilterRules = readRules(state, "ip6tables-filter.rules"),
                elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            )
        } finally {
            root.deleteRecursively()
        }
        cacheResult(result, mode, ipv6NatRules, ipv6FilterRules)
        return result
    }

    private fun cachedResult(mode: String, ipv6NatRules: String, ipv6FilterRules: String): CleanupResult? =
        cachedEmptyResult.takeIf {
            mode == "empty" && ipv6NatRules.isBlank() && ipv6FilterRules.isBlank()
        }

    private fun cacheResult(
        result: CleanupResult,
        mode: String,
        ipv6NatRules: String,
        ipv6FilterRules: String
    ) {
        if (mode == "empty" && ipv6NatRules.isBlank() && ipv6FilterRules.isBlank()) cachedEmptyResult = result
    }

    private fun writeMockRules(state: File, ipv6NatRules: String, ipv6FilterRules: String) {
        if (ipv6NatRules.isNotBlank()) state.resolve("ip6tables-nat.rules").writeText(ipv6NatRules)
        if (ipv6FilterRules.isNotBlank()) state.resolve("ip6tables-filter.rules").writeText(ipv6FilterRules)
    }

    private fun readRules(state: File, name: String): String =
        state.resolve(name).takeIf(File::isFile)?.readText().orEmpty()

    private fun findShell(): String = listOf(
        "/bin/sh",
        "C:/Program Files/Git/usr/bin/sh.exe",
        "C:/Program Files/Git/bin/sh.exe"
    ).firstOrNull { File(it).isFile } ?: error("POSIX shell is unavailable")

    private fun File.posixPath(): String {
        val normalized = absolutePath.replace('\\', '/')
        return if (normalized.length > 2 && normalized[1] == ':') {
            "/${normalized[0].lowercaseChar()}${normalized.substring(2)}"
        } else {
            normalized
        }
    }

    private fun String.shellQuote(): String = "'${replace("'", "'\"'\"'")}'"

    private data class CleanupResult(
        val exitCode: Int,
        val output: String,
        val commands: String,
        val finalIpv6NatRules: String,
        val finalIpv6FilterRules: String,
        val elapsedMs: Long
    )

    private companion object {
        @Volatile
        var cachedEmptyResult: CleanupResult? = null
    }
}

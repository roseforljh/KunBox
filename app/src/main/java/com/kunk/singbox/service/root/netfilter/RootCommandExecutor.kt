@file:Suppress("InvalidPackageDeclaration")

package com.kunk.singbox.service.root

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal const val XTABLES_WAIT_SECONDS = 2
internal const val XTABLES_MAX_ATTEMPTS = 3
internal const val ROOT_STATE_IPTABLES4 = "iptables4"
internal const val ROOT_STATE_IPTABLES6 = "iptables6"
internal const val ROOT_STATE_RULE4 = "rule4"
internal const val ROOT_STATE_RULE6 = "rule6"
internal const val ROOT_STATE_ROUTE4 = "route4"
internal const val ROOT_STATE_ROUTE6 = "route6"
private const val ROOT_STATE_MARKER_PREFIX = "__KBX_ROOT_STATE_"
private val rootCommandSerializationLock = Any()

internal fun <T> withSerializedRootCommand(block: () -> T): T = synchronized(rootCommandSerializationLock, block)

internal fun withXtablesWait(arguments: List<String>): List<String> {
    val binary = arguments.firstOrNull()
    if (binary != "iptables" && binary != "ip6tables") return arguments
    if ("-w" in arguments || "--wait" in arguments) return arguments
    return listOf(binary, "-w", XTABLES_WAIT_SECONDS.toString()) + arguments.drop(1)
}

internal fun isXtablesLockContention(result: RootCommandResult): Boolean =
    result.diagnosticOutput.contains("xtables.lock", ignoreCase = true) ||
        result.diagnosticOutput.contains("holding the xtables lock", ignoreCase = true)

internal fun extractIptablesSaveTable(output: String, table: String): String? {
    val lines = mutableListOf<String>()
    var inside = false
    output.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (!inside && trimmed == "*$table") {
            inside = true
            lines += line
        } else if (inside) {
            lines += line
            if (trimmed == "COMMIT") return lines.joinToString("\n")
        }
    }
    check(!inside) { "iptables-save table $table is incomplete" }
    return null
}

internal fun rootStateSnapshotCommands(
    netfilterBinaries: Collection<String> = emptyList(),
    includePolicyRouting: Boolean = true
): List<List<String>> =
    buildList {
        if ("iptables" in netfilterBinaries) {
            add(rootStateMarkerCommand(ROOT_STATE_IPTABLES4))
            add(listOf("iptables-save"))
        }
        if ("ip6tables" in netfilterBinaries) {
            add(rootStateMarkerCommand(ROOT_STATE_IPTABLES6))
            add(listOf("ip6tables-save"))
        }
        if (includePolicyRouting) {
            add(rootStateMarkerCommand(ROOT_STATE_RULE4))
            add(listOf("ip", "rule", "show"))
            add(rootStateMarkerCommand(ROOT_STATE_RULE6))
            add(listOf("ip", "-6", "rule", "show"))
            add(rootStateMarkerCommand(ROOT_STATE_ROUTE4))
            add(listOf("ip", "route", "show", "table", RootNetfilterPlanner.ROUTE_TABLE))
            add(rootStateMarkerCommand(ROOT_STATE_ROUTE6))
            add(listOf("ip", "-6", "route", "show", "table", RootNetfilterPlanner.ROUTE_TABLE))
        }
    }

internal fun parseRootStateSnapshot(output: String): Map<String, String>? {
    val sections = linkedMapOf<String, StringBuilder>()
    var current: String? = null
    output.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith(ROOT_STATE_MARKER_PREFIX) && trimmed.endsWith("__")) {
            val section = trimmed.removePrefix(ROOT_STATE_MARKER_PREFIX).removeSuffix("__")
            current = section
            sections.getOrPut(section) { StringBuilder() }
            return@forEach
        }
        val section = current ?: return@forEach
        val content = sections.getValue(section)
        if (content.isNotEmpty()) content.append('\n')
        content.append(line)
    }
    return sections.takeIf { it.isNotEmpty() }?.mapValues { it.value.toString().trim() }
}

private fun rootStateMarkerCommand(section: String): List<String> =
    listOf("printf", "$ROOT_STATE_MARKER_PREFIX${section}__\\n")

internal fun executeXtablesWithRetry(
    executeOnce: () -> RootCommandResult,
    onContention: (attempt: Int, elapsedMs: Long) -> Unit = { _, _ -> }
): RootCommandResult {
    var last = RootCommandResult(124, "", "Root command did not run")
    repeat(XTABLES_MAX_ATTEMPTS) { index ->
        val startedAt = System.nanoTime()
        last = executeOnce()
        if (last.success || !isXtablesLockContention(last)) return last
        onContention(index + 1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
    }
    return last
}

private fun xtablesCommandType(arguments: List<String>): String {
    val operation = arguments.firstOrNull { it in setOf("-A", "-I", "-D", "-C", "-F", "-X", "-N", "-S", "-L") }
    return "${arguments.firstOrNull().orEmpty()}:${operation.orEmpty()}"
}

data class RootCommandResult(
    val exitCode: Int,
    val output: String,
    val stderr: String = ""
) {
    val success: Boolean get() = exitCode == 0
    val diagnosticOutput: String
        get() = buildList {
            output.takeIf(String::isNotBlank)?.let { add("stdout=$it") }
            stderr.takeIf(String::isNotBlank)?.let { add("stderr=$it") }
        }.joinToString(" ")
}

fun interface RootCommandExecutor {
    fun execute(arguments: List<String>): RootCommandResult

    fun executeWithTimeout(arguments: List<String>, timeoutMs: Long): RootCommandResult = execute(arguments)

    fun executeFastNetfilterPlan(commands: List<List<String>>): RootCommandResult? = null

    fun executeFastNetfilterTransitionPlan(commands: List<List<String>>): RootCommandResult? = null

    fun executeFastNetfilterCleanupPlan(commands: List<List<String>>): RootCommandResult? = null

    fun executeBatch(commands: List<List<String>>): RootCommandResult {
        val output = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        commands.forEachIndexed { index, command ->
            val result = execute(command)
            if (!result.success) {
                val failure = "Batch command $index failed: ${command.joinToString(" ")} " +
                    "exitCode=${result.exitCode} ${result.diagnosticOutput}"
                return result.copy(
                    output = (output + failure).joinToString("\n"),
                    stderr = (stderr + result.stderr).filter(String::isNotBlank).joinToString("\n")
                )
            }
            result.output.takeIf(String::isNotBlank)?.let(output::add)
            result.stderr.takeIf(String::isNotBlank)?.let(stderr::add)
        }
        return RootCommandResult(0, output.joinToString("\n"), stderr.joinToString("\n"))
    }

    fun executeBestEffortBatch(
        commands: List<List<String>>,
        repeatUntilFailure: Set<Int>,
        maxAttempts: Int
    ): RootCommandResult {
        commands.forEachIndexed { index, command ->
            var attempts = 0
            do {
                val result = runCatching { execute(command) }.getOrElse { break }
                attempts++
            } while (index in repeatUntilFailure && result.success && attempts < maxAttempts)
        }
        return RootCommandResult(0, "")
    }
}

class ProcessRootCommandExecutor internal constructor(
    private val timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS
) : RootCommandExecutor {
    private val xtablesWaitMs = AtomicLong(0L)
    private val activeProcesses = Collections.synchronizedSet(mutableSetOf<Process>())

    init {
        require(timeoutMs > 0L)
    }

    override fun execute(arguments: List<String>): RootCommandResult = executeSerializedIfNeeded(arguments) {
        require(arguments.isNotEmpty()) { "Root command is empty" }
        val command = withXtablesWait(arguments)
        if (isXtablesCommand(command)) {
            executeWithXtablesRetry(command)
        } else {
            collectProcess(startProcess(command)).also(::recordXtablesWaitEvents)
        }
    }

    override fun executeWithTimeout(arguments: List<String>, timeoutMs: Long): RootCommandResult =
        executeSerializedIfNeeded(arguments) {
            require(arguments.isNotEmpty()) { "Root command is empty" }
            require(timeoutMs > 0L) { "Root command timeout must be positive" }
            val command = withXtablesWait(arguments)
            collectProcess(startProcess(command), timeoutMs).also(::recordXtablesWaitEvents)
        }

    private fun <T> executeSerializedIfNeeded(arguments: List<String>, block: () -> T): T =
        if (isReadOnlyPackageInventoryCommand(arguments)) block() else withSerializedRootCommand(block)

    override fun executeBatch(commands: List<List<String>>): RootCommandResult =
        withSerializedRootCommand {
            executeScript(buildRootCommandBatchScript(commands))
        }

    override fun executeBestEffortBatch(
        commands: List<List<String>>,
        repeatUntilFailure: Set<Int>,
        maxAttempts: Int
    ): RootCommandResult = withSerializedRootCommand {
        executeScript(buildRootCommandBatchScript(commands, repeatUntilFailure, maxAttempts))
    }

    override fun executeFastNetfilterPlan(commands: List<List<String>>): RootCommandResult? =
        withSerializedRootCommand {
            buildRootNetfilterRestoreScript(commands)?.let(::executeScript)
        }

    override fun executeFastNetfilterTransitionPlan(commands: List<List<String>>): RootCommandResult? =
        withSerializedRootCommand {
            buildRootNetfilterTransitionScript(commands)?.let(::executeScript)
        }

    override fun executeFastNetfilterCleanupPlan(commands: List<List<String>>): RootCommandResult? =
        withSerializedRootCommand {
            buildRootNetfilterCleanupScript(commands)?.let(::executeScript)
        }

    internal fun resetXtablesWaitMetrics() = xtablesWaitMs.set(0L)

    internal fun currentXtablesWaitMs(): Long = xtablesWaitMs.get()

    private fun executeWithXtablesRetry(arguments: List<String>): RootCommandResult {
        return executeXtablesWithRetry(
            executeOnce = {
                collectProcess(startProcess(arguments)).also(::recordXtablesWaitEvents)
            }
        ) { attempt, elapsedMs ->
            xtablesWaitMs.addAndGet(elapsedMs)
            Log.w(
                TAG,
                "[ROOT_NET] event=xtables_lock_wait commandType=${xtablesCommandType(arguments)} " +
                    "attempt=$attempt elapsed_ms=$elapsedMs"
            )
        }
    }

    private fun executeScript(script: String): RootCommandResult {
        if (script.isBlank()) return RootCommandResult(0, "")
        val process = startProcess(listOf("/system/bin/sh"))
        process.outputStream.bufferedWriter().use { it.write(script) }
        return collectProcess(process).also(::recordXtablesWaitEvents)
    }

    private fun recordXtablesWaitEvents(result: RootCommandResult) {
        result.stderr.lineSequence()
            .filter { "event=xtables_lock_wait" in it }
            .mapNotNull { it.substringAfter("elapsed_ms=", "").substringBefore(' ').toLongOrNull() }
            .forEach(xtablesWaitMs::addAndGet)
    }

    internal fun cancelActiveCommands() {
        val processes = synchronized(activeProcesses) { activeProcesses.filter(Process::isAlive) }
        processes.forEach { process ->
            runCatching { process.destroyForcibly() }
        }
    }

    private fun startProcess(command: List<String>): Process = ProcessBuilder(command)
        .start()
        .also(activeProcesses::add)

    @Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod")
    private fun collectProcess(process: Process, commandTimeoutMs: Long = timeoutMs): RootCommandResult {
        val output = StringBuilder()
        val errorOutput = StringBuilder()
        val outputReadError = AtomicReference<Throwable?>()
        val errorReadError = AtomicReference<Throwable?>()
        val outputReader = Thread({
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream)).use { input ->
                    val buffer = CharArray(4_096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.append(buffer, 0, count)
                    }
                }
            }.onFailure(outputReadError::set)
        }, "kunbox-root-command-output").apply {
            isDaemon = true
            start()
        }
        val errorReader = Thread({
            runCatching {
                BufferedReader(InputStreamReader(process.errorStream)).use { input ->
                    val buffer = CharArray(4_096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        errorOutput.append(buffer, 0, count)
                    }
                }
            }.onFailure(errorReadError::set)
        }, "kunbox-root-command-error").apply {
            isDaemon = true
            start()
        }
        if (!process.waitFor(commandTimeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroy()
            if (!process.waitFor(PROCESS_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                process.waitFor(PROCESS_DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)
            }
            outputReader.join(PROCESS_READER_JOIN_MS)
            errorReader.join(PROCESS_READER_JOIN_MS)
            activeProcesses.remove(process)
            return RootCommandResult(
                COMMAND_TIMEOUT_EXIT_CODE,
                output.toString().trim(),
                errorOutput.toString().trim()
            )
        }
        outputReader.join(PROCESS_READER_JOIN_MS)
        errorReader.join(PROCESS_READER_JOIN_MS)
        check(!outputReader.isAlive && !errorReader.isAlive) { "Root command output reader did not finish" }
        outputReadError.get()?.let { throw IllegalStateException("Cannot read Root command stdout", it) }
        errorReadError.get()?.let { throw IllegalStateException("Cannot read Root command stderr", it) }
        activeProcesses.remove(process)
        return RootCommandResult(process.exitValue(), output.toString().trim(), errorOutput.toString().trim())
    }

    private companion object {
        const val DEFAULT_COMMAND_TIMEOUT_MS = 15_000L
        const val PROCESS_DESTROY_GRACE_MS = 250L
        const val PROCESS_READER_JOIN_MS = 1_000L
        const val COMMAND_TIMEOUT_EXIT_CODE = 124
        const val TAG = "RootCommandExecutor"
    }
}

@Suppress("CyclomaticComplexMethod", "CognitiveComplexMethod", "ReturnCount", "LongMethod")
internal fun buildRootNetfilterRestoreScript(commands: List<List<String>>): String? {
    data class TableRestore(
        val chains: MutableList<String> = mutableListOf(),
        val rules: MutableList<String> = mutableListOf()
    )

    val restores = linkedMapOf<String, LinkedHashMap<String, TableRestore>>()
    val policyCommands = mutableListOf<List<String>>()
    val activationCommands = mutableListOf<List<String>>()

    commands.forEach { command ->
        val binary = command.firstOrNull() ?: return null
        if (binary !in setOf("iptables", "ip6tables")) {
            policyCommands += command
            return@forEach
        }
        if ("-I" in command) {
            activationCommands += command
            return@forEach
        }
        val tableIndex = command.indexOf("-t")
        val table = command.getOrNull(tableIndex + 1) ?: return null
        val operation = command.getOrNull(tableIndex + 2) ?: return null
        val restore = restores.getOrPut(binary) { linkedMapOf() }.getOrPut(table) { TableRestore() }
        when (operation) {
            "-N" -> {
                val chain = command.getOrNull(tableIndex + 3)?.takeIf(::isSafeRestoreToken) ?: return null
                restore.chains += ":$chain - [0:0]"
            }
            "-A" -> {
                val arguments = command.drop(tableIndex + 2)
                if (arguments.any { !isSafeRestoreToken(it) }) return null
                restore.rules += arguments.joinToString(" ")
            }
            else -> return null
        }
    }
    if (restores.isEmpty()) return null

    val (ipCommands, otherPolicyCommands) = policyCommands.partition { it.firstOrNull() == "ip" }

    return buildString {
        append(xtablesRetryShellFunction())
        restores.forEach { (binary, tables) ->
            val restoreBinary = if (binary == "iptables") "iptables-restore" else "ip6tables-restore"
            append("command -v ").append(restoreBinary).append(" >/dev/null 2>&1 || exit 127\n")
            append("kb_run_xtables ").append(shellQuote(restoreBinary)).append(' ')
                .append(restoreBinary).append(" -w ").append(XTABLES_WAIT_SECONDS)
                .append(" --noflush <<'KBX_RESTORE_")
                .append(if (binary == "iptables") "4" else "6").append("'\n")
            tables.forEach { (table, restore) ->
                append('*').append(table).append('\n')
                restore.chains.forEach { append(it).append('\n') }
                restore.rules.forEach { append(it).append('\n') }
                append("COMMIT\n")
            }
            append("KBX_RESTORE_").append(if (binary == "iptables") "4" else "6").append('\n')
            append("kb_status=${'$'}?\nif [ \"${'$'}kb_status\" -ne 0 ]; then ")
                .append("exit \"${'$'}kb_status\"; fi\n")
        }
        append(buildRootIpBatchScript(ipCommands))
        append(buildRootCommandBatchScript(otherPolicyCommands + activationCommands))
        append(buildRootCommandBatchScript(rootStateSnapshotCommands(restores.keys)))
    }
}

internal fun buildRootNetfilterTransitionScript(commands: List<List<String>>): String? =
    buildRootNetfilterMutationScript(commands, setOf("-I", "-D", "-F", "-X"), "TRANSITION")

internal fun buildRootNetfilterCleanupScript(commands: List<List<String>>): String? =
    buildRootNetfilterMutationScript(commands, setOf("-D", "-F", "-X"), "CLEANUP")

@Suppress("CognitiveComplexMethod")
private fun buildRootNetfilterMutationScript(
    commands: List<List<String>>,
    allowedOperations: Set<String>,
    marker: String
): String? {
    val policyCommands = commands.filter { it.firstOrNull() == "ip" }
    val xtablesCommands = commands.filter { it.firstOrNull() != "ip" }
    val parsedCommands = xtablesCommands.mapNotNull { parseRootMutationCommand(it, allowedOperations) }
    if (policyCommands.size + xtablesCommands.size != commands.size || parsedCommands.size != xtablesCommands.size) {
        return null
    }
    if (parsedCommands.isEmpty()) return null
    val phases = if ("-I" in allowedOperations) {
        listOf(
            "${marker}_ACTIVATE" to parsedCommands.filter { it.third.startsWith("-I ") },
            "${marker}_CLEANUP" to parsedCommands.filterNot { it.third.startsWith("-I ") }
        ).filter { it.second.isNotEmpty() }
    } else {
        listOf(marker to parsedCommands)
    }

    return buildString {
        append(xtablesRetryShellFunction())
        phases.forEach { (phaseMarker, phaseCommands) ->
            val restores = linkedMapOf<String, LinkedHashMap<String, MutableList<String>>>()
            phaseCommands.forEach { (binary, table, arguments) ->
                restores.getOrPut(binary) { linkedMapOf() }
                    .getOrPut(table) { mutableListOf() }
                    .add(arguments)
            }
            restores.forEach { (binary, tables) ->
                val suffix = if (binary == "iptables") "4" else "6"
                val restoreBinary = if (binary == "iptables") "iptables-restore" else "ip6tables-restore"
                append("command -v ").append(restoreBinary).append(" >/dev/null 2>&1 || exit 127\n")
                append("kb_run_xtables ").append(shellQuote(restoreBinary)).append(' ')
                    .append(restoreBinary).append(" -w ").append(XTABLES_WAIT_SECONDS)
                    .append(" --noflush <<'KBX_").append(phaseMarker).append('_').append(suffix).append("'\n")
                tables.forEach { (table, operations) ->
                    append('*').append(table).append('\n')
                    operations.forEach { append(it).append('\n') }
                    append("COMMIT\n")
                }
                append("KBX_").append(phaseMarker).append('_').append(suffix).append('\n')
                append("kb_status=${'$'}?\nif [ \"${'$'}kb_status\" -ne 0 ]; then exit \"${'$'}kb_status\"; fi\n")
            }
        }
        append(buildRootIpBatchScript(policyCommands))
        append(buildRootCommandBatchScript(rootStateSnapshotCommands(parsedCommands.map { it.first }.distinct())))
    }
}

private fun parseRootMutationCommand(
    command: List<String>,
    allowedOperations: Set<String>
): Triple<String, String, String>? {
    val binary = command.firstOrNull()
    val tableIndex = command.indexOf("-t")
    val table = command.getOrNull(tableIndex + 1)
    val operationIndex = tableIndex + 2
    val operation = command.getOrNull(operationIndex)
    val arguments = command.drop(operationIndex.coerceIn(0, command.size))
    val headerValid = binary in setOf("iptables", "ip6tables") && table != null
    val operationValid = operation in allowedOperations
    val argumentsValid = arguments.isNotEmpty() && arguments.all(::isSafeRestoreToken)
    return if (headerValid && operationValid && argumentsValid) {
        Triple(requireNotNull(binary), table, arguments.joinToString(" "))
    } else {
        null
    }
}

internal fun buildRootIpBatchScript(commands: List<List<String>>): String {
    if (commands.isEmpty()) return ""
    require(commands.all { it.firstOrNull() == "ip" }) { "Root policy batch contains a non-ip command" }
    val groups = commands.groupBy { it.getOrNull(1) == "-6" }
    return buildString {
        append("command -v ip >/dev/null 2>&1 || exit 127\n")
        groups.forEach { (ipv6, familyCommands) ->
            val suffix = if (ipv6) "6" else "4"
            append("ip ")
            if (ipv6) append("-6 ")
            append("-batch - <<'KBX_IP_").append(suffix).append("'\n")
            familyCommands.forEach { command ->
                val arguments = command.drop(if (ipv6) 2 else 1)
                require(arguments.isNotEmpty() && arguments.all(::isSafeRestoreToken)) {
                    "Unsafe Root policy batch command"
                }
                append(arguments.joinToString(" ")).append('\n')
            }
            append("KBX_IP_").append(suffix).append("\n")
            append("kb_status=${'$'}?\nif [ \"${'$'}kb_status\" -ne 0 ]; then exit \"${'$'}kb_status\"; fi\n")
        }
    }
}

private fun isSafeRestoreToken(value: String): Boolean = value.isNotBlank() && value.all { char ->
    char.isLetterOrDigit() || char in "_-.:/,"
}

internal fun buildRootCommandBatchScript(
    commands: List<List<String>>,
    repeatUntilFailure: Set<Int> = emptySet(),
    maxAttempts: Int = 1
): String {
    require(maxAttempts > 0)
    require(repeatUntilFailure.all(commands.indices::contains))
    val serializedCommands = commands.map(::withXtablesWait)
    return buildString {
        if (serializedCommands.any(::isXtablesCommand)) append(xtablesRetryShellFunction())
        serializedCommands.forEachIndexed { index, arguments ->
            require(arguments.isNotEmpty()) { "Root command is empty" }
            val command = arguments.joinToString(" ", transform = ::shellQuote)
            val invocation = if (isXtablesCommand(arguments)) {
                "kb_run_xtables ${shellQuote(xtablesCommandType(arguments))} $command"
            } else {
                command
            }
            if (index in repeatUntilFailure) {
                append("kb_attempt=0\nwhile [ \"${'$'}kb_attempt\" -lt ")
                append(maxAttempts)
                append(" ]; do\n  ")
                append(invocation)
                append(" >/dev/null 2>&1 || break\n  kb_attempt=${'$'}((kb_attempt + 1))\ndone\n")
            } else if (repeatUntilFailure.isNotEmpty()) {
                append(invocation)
                append(" >/dev/null 2>&1 || :\n")
            } else {
                append(invocation)
                append("\nkb_status=${'$'}?\nif [ \"${'$'}kb_status\" -ne 0 ]; then\n")
                append("  printf '%s\\n' ")
                append(shellQuote("Batch command $index failed: $command"))
                append(" >&2\n  exit \"${'$'}kb_status\"\nfi\n")
            }
        }
    }
}

private fun isXtablesCommand(arguments: List<String>): Boolean =
    arguments.firstOrNull() == "iptables" || arguments.firstOrNull() == "ip6tables"

internal fun isReadOnlyPackageInventoryCommand(arguments: List<String>): Boolean =
    arguments.take(2) == listOf("cmd", "user") || arguments.take(2) == listOf("cmd", "package")

private fun xtablesRetryShellFunction(): String = """
    kb_run_xtables() {
      kb_command_type="${'$'}1"
      shift
      kb_lock_attempt=1
      kb_use_wait=1
      while [ "${'$'}kb_lock_attempt" -le $XTABLES_MAX_ATTEMPTS ]; do
        kb_lock_started="${'$'}(date +%s)"
        kb_error_file="${'$'}{TMPDIR:-/data/local/tmp}/kunbox-xtables.${'$'}${'$'}"
        if [ "${'$'}kb_use_wait" -eq 1 ]; then
          "${'$'}@" 2>"${'$'}kb_error_file"
        else
          if [ "${'$'}1" = "-w" ] || [ "${'$'}1" = "--wait" ]; then
            shift 2
          fi
          "${'$'}@" 2>"${'$'}kb_error_file"
        fi
        kb_status="${'$'}?"
        if [ "${'$'}kb_status" -eq 0 ]; then
          cat "${'$'}kb_error_file" >&2
          rm -f "${'$'}kb_error_file"
          return 0
        fi
        if [ "${'$'}kb_use_wait" -eq 1 ] && grep -E -i -q \
            'unknown option.*(--wait|-w)|unrecognized option.*(--wait|-w)|invalid option.*(--wait|-w)|illegal option.*(--wait|-w)|option.*(--wait|-w).*(not supported|unknown)' \
            "${'$'}kb_error_file"; then
          kb_use_wait=0
          printf '[ROOT_NET] event=xtables_wait_unsupported commandType=%s fallback=no_wait\n' \
            "${'$'}kb_command_type" >&2
          continue
        fi
        if grep -F -q 'xtables.lock' "${'$'}kb_error_file" || \
            grep -F -q 'holding the xtables lock' "${'$'}kb_error_file"; then
          kb_elapsed="${'$'}((($(date +%s) - kb_lock_started) * 1000))"
          printf '[ROOT_NET] event=xtables_lock_wait commandType=%s attempt=%s elapsed_ms=%s\n' \
            "${'$'}kb_command_type" "${'$'}kb_lock_attempt" "${'$'}kb_elapsed" >&2
          if [ "${'$'}kb_lock_attempt" -lt $XTABLES_MAX_ATTEMPTS ]; then
            kb_lock_attempt="${'$'}((kb_lock_attempt + 1))"
            continue
          fi
        fi
        cat "${'$'}kb_error_file" >&2
        rm -f "${'$'}kb_error_file"
        return "${'$'}kb_status"
      done
    }

""".trimIndent()

internal fun shellQuote(value: String): String {
    require('\u0000' !in value) { "Root command argument contains NUL" }
    return "'${value.replace("'", "'\"'\"'")}'"
}

package com.kunk.singbox.service.root

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class RootIpv6PrivacyGuard(
    private val confDirectory: File = File("/proc/sys/net/ipv6/conf"),
    private val addressesFile: File = File("/proc/net/if_inet6"),
    private val stateFile: File = File("/data/adb/kunbox/ipv6-privacy-state")
) {
    private data class State(
        val sessionId: String,
        val defaultValue: Int,
        val interfaces: Map<String, Int>
    )

    private val lock = Any()
    private var activeSessionId = ""

    fun activate(runtimeSessionId: String): Result<Unit> = runCatching {
        require(runtimeSessionId.isNotBlank()) { "Root IPv6 privacy session is empty" }
        synchronized(lock) {
            readState()?.let { stale ->
                if (stale.sessionId != runtimeSessionId) restoreLocked(stale)
            }
            if (!stateFile.exists()) {
                val state = State(
                    sessionId = runtimeSessionId,
                    defaultValue = readControl("default"),
                    interfaces = interfaceNames().associateWith(::readControl)
                )
                writeState(state)
            }
            val state = requireNotNull(readState())
            check(state.sessionId == runtimeSessionId) { "Root IPv6 privacy session mismatch" }
            activeSessionId = runtimeSessionId
            enforceLocked(state)
            Log.i(TAG, "[ROOT_PRIVACY] IPv6 physical-address guard active session=$runtimeSessionId")
        }
    }

    fun enforce(): Result<Unit> = runCatching {
        synchronized(lock) {
            val state = readState() ?: return@synchronized
            if (activeSessionId.isNotBlank()) {
                check(state.sessionId == activeSessionId) { "Root IPv6 privacy ownership changed" }
            }
            activeSessionId = state.sessionId
            enforceLocked(state)
        }
    }

    fun restore(): Result<Unit> = runCatching {
        synchronized(lock) {
            readState()?.let(::restoreLocked)
            activeSessionId = ""
        }
    }

    private fun enforceLocked(saved: State) {
        val currentInterfaces = interfaceNames()
        val newInterfaces = currentInterfaces.filterNot(saved.interfaces::containsKey)
        val state = if (newInterfaces.isEmpty()) {
            saved
        } else {
            saved.copy(
                interfaces = saved.interfaces + newInterfaces.associateWith { saved.defaultValue }
            ).also(::writeState)
        }
        writeControl("default", 1)
        publicIpv6Interfaces().forEach { interfaceName ->
            check(interfaceName in state.interfaces) { "Untracked IPv6 interface: $interfaceName" }
            writeControl(interfaceName, 1)
        }
        check(readControl("default") == 1) { "Root IPv6 privacy default guard is inactive" }
        publicIpv6Interfaces().forEach { interfaceName ->
            check(readControl(interfaceName) == 1) {
                "Root IPv6 privacy guard is inactive on $interfaceName"
            }
        }
    }

    private fun restoreLocked(state: State) {
        val currentInterfaces = interfaceNames()
        currentInterfaces.forEach { interfaceName ->
            writeControl(interfaceName, state.interfaces[interfaceName] ?: state.defaultValue)
        }
        writeControl("default", state.defaultValue)
        check(stateFile.delete() || !stateFile.exists()) { "Cannot remove Root IPv6 privacy state" }
        Log.i(TAG, "[ROOT_PRIVACY] IPv6 interface state restored session=${state.sessionId}")
    }

    private fun interfaceNames(): List<String> {
        check(confDirectory.isDirectory && !Files.isSymbolicLink(confDirectory.toPath())) {
            "IPv6 sysctl directory is unavailable"
        }
        return confDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .map(File::getName)
            .filter(::isSafeInterfaceName)
            .filterNot { it == "all" || it == "default" || it == "lo" }
            .sorted()
            .toList()
    }

    private fun publicIpv6Interfaces(): Set<String> {
        if (!addressesFile.isFile) return emptySet()
        return addressesFile.useLines { lines ->
            lines.mapNotNull { line ->
                val fields = line.trim().split(' ').filter(String::isNotBlank)
                val address = fields.firstOrNull().orEmpty()
                val interfaceName = fields.lastOrNull().orEmpty()
                interfaceName.takeIf {
                    address.length == IPV6_HEX_LENGTH &&
                        address.all { it.isHexDigit() } &&
                        address.first().lowercaseChar() in PUBLIC_IPV6_PREFIXES &&
                        isSafeInterfaceName(interfaceName) &&
                        interfaceName != "lo"
                }
            }.toSet()
        }
    }

    private fun readControl(interfaceName: String): Int {
        val file = controlFile(interfaceName)
        return file.readText().trim().toIntOrNull()?.takeIf { it == 0 || it == 1 }
            ?: error("Invalid IPv6 sysctl value for $interfaceName")
    }

    private fun writeControl(interfaceName: String, value: Int) {
        require(value == 0 || value == 1)
        val file = controlFile(interfaceName)
        file.writeText(value.toString())
        check(readControl(interfaceName) == value) { "Cannot update IPv6 state for $interfaceName" }
    }

    private fun controlFile(interfaceName: String): File {
        require(interfaceName == "default" || isSafeInterfaceName(interfaceName))
        val directory = File(confDirectory, interfaceName)
        check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "Unsafe IPv6 interface path: $interfaceName"
        }
        val file = File(directory, "disable_ipv6")
        check(file.isFile && !Files.isSymbolicLink(file.toPath())) {
            "IPv6 control is unavailable for $interfaceName"
        }
        return file
    }

    private fun readState(): State? {
        if (!stateFile.exists()) return null
        check(stateFile.isFile && !Files.isSymbolicLink(stateFile.toPath())) {
            "Root IPv6 privacy state path is unsafe"
        }
        check(stateFile.length() in 1..MAX_STATE_BYTES) { "Root IPv6 privacy state is invalid" }
        val lines = stateFile.readLines(Charsets.UTF_8)
        check(lines.firstOrNull() == "version=$STATE_VERSION") { "Root IPv6 privacy state version mismatch" }
        val sessionId = lines.firstOrNull { it.startsWith("session=") }
            ?.substringAfter("session=")
            .orEmpty()
        check(sessionId.isNotBlank() && sessionId.all { it.isLetterOrDigit() || it == '-' }) {
            "Root IPv6 privacy state session is invalid"
        }
        val defaultValue = lines.firstOrNull { it.startsWith("default=") }
            ?.substringAfter("default=")
            ?.toIntOrNull()
            ?.takeIf { it == 0 || it == 1 }
            ?: error("Root IPv6 privacy default state is invalid")
        val interfaces = linkedMapOf<String, Int>()
        lines.filter { it.startsWith("iface=") }.forEach { line ->
            val payload = line.substringAfter("iface=")
            val separator = payload.lastIndexOf('|')
            check(separator > 0) { "Root IPv6 privacy interface state is invalid" }
            val name = payload.substring(0, separator)
            val value = payload.substring(separator + 1).toIntOrNull()
                ?.takeIf { it == 0 || it == 1 }
                ?: error("Root IPv6 privacy interface state is invalid")
            check(isSafeInterfaceName(name) && interfaces.put(name, value) == null) {
                "Root IPv6 privacy interface state is invalid"
            }
        }
        return State(sessionId, defaultValue, interfaces)
    }

    private fun writeState(state: State) {
        val parent = stateFile.parentFile ?: error("Root IPv6 privacy state has no parent")
        check(parent.isDirectory && !Files.isSymbolicLink(parent.toPath())) {
            "Root IPv6 privacy state directory is unsafe"
        }
        val content = buildString {
            appendLine("version=$STATE_VERSION")
            appendLine("session=${state.sessionId}")
            appendLine("default=${state.defaultValue}")
            state.interfaces.toSortedMap().forEach { (name, value) -> appendLine("iface=$name|$value") }
        }
        val temp = File.createTempFile(".ipv6-privacy-", ".tmp", parent)
        try {
            FileOutputStream(temp).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            runCatching {
                Files.move(
                    temp.toPath(),
                    stateFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.getOrElse {
                Files.move(temp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temp.exists()) check(temp.delete()) { "Cannot remove Root IPv6 privacy temporary state" }
        }
    }

    private fun isSafeInterfaceName(value: String): Boolean =
        value.isNotBlank() && value.length <= MAX_INTERFACE_NAME_LENGTH &&
            value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' || it == ':' }

    private fun Char.isHexDigit(): Boolean = isDigit() || lowercaseChar() in 'a'..'f'

    companion object {
        private const val TAG = "RootIpv6PrivacyGuard"
        private const val STATE_VERSION = 1
        private const val IPV6_HEX_LENGTH = 32
        private const val MAX_INTERFACE_NAME_LENGTH = 15
        private const val MAX_STATE_BYTES = 64 * 1024L
        private val PUBLIC_IPV6_PREFIXES = setOf('2', '3')
    }
}

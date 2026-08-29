package com.kunk.singbox.service.root

import java.io.File

data class RootListenerExpectation(
    val ipv4: Set<Int>,
    val ipv6: Set<Int>,
    val udpIpv4: Set<Int>,
    val udpIpv6: Set<Int>
)

class RootListenerVerifier(
    private val procRoot: File = File("/proc"),
    private val socketInodesProvider: ((Int) -> Set<Long>)? = null
) {
    fun verify(expectation: RootListenerExpectation, rootPid: Int): Result<Unit> = runCatching {
        verifyFamily(
            File(procRoot, "net/tcp"),
            expectation.ipv4,
            state = "0A",
            rootPid = rootPid,
            label = "tcp4"
        )
        verifyFamily(
            File(procRoot, "net/tcp6"),
            expectation.ipv6,
            state = "0A",
            rootPid = rootPid,
            label = "tcp6"
        )
        verifyFamily(
            File(procRoot, "net/udp"),
            expectation.udpIpv4,
            state = null,
            rootPid = rootPid,
            label = "udp4"
        )
        verifyFamily(
            File(procRoot, "net/udp6"),
            expectation.udpIpv6,
            state = null,
            rootPid = rootPid,
            label = "udp6"
        )
    }

    fun verifyAbsent(expectation: RootListenerExpectation): Result<Unit> = runCatching {
        listOf(
            Triple(File(procRoot, "net/tcp"), expectation.ipv4, "0A"),
            Triple(File(procRoot, "net/tcp6"), expectation.ipv6, "0A"),
            Triple(File(procRoot, "net/udp"), expectation.udpIpv4, null),
            Triple(File(procRoot, "net/udp6"), expectation.udpIpv6, null)
        ).forEach { (file, ports, state) ->
            if (ports.isEmpty()) return@forEach
            check(file.isFile) { "Listener proc table is unavailable: ${file.path}" }
            val owned = file.readLines().drop(1).mapNotNull(::parseRow)
                .filter { it.port in ports && (state == null || it.state == state) }
            check(owned.isEmpty()) {
                "Listener remained after core stop, including foreign sockets: ${owned.map { it.port }}"
            }
        }
    }

    private fun verifyFamily(
        file: File,
        ports: Set<Int>,
        state: String?,
        rootPid: Int,
        label: String
    ) {
        if (ports.isEmpty()) return
        check(file.isFile) { "$label proc table is unavailable" }
        val rows = file.readLines().drop(1).mapNotNull(::parseRow)
        val inodes = socketInodes(rootPid)
        check(inodes.isNotEmpty()) { "$label Root PID $rootPid has no socket descriptors" }
        ports.forEach { port ->
            val matches = rows.filter { it.port == port && (state == null || it.state == state) }
            check(matches.isNotEmpty()) { "$label listener is missing for port $port" }
            check(matches.all { it.inode in inodes }) {
                "$label listener port $port has a socket outside Root PID $rootPid"
            }
        }
        check(rows.filter { it.port in ports && (state == null || it.state == state) }.all { it.inode in inodes }) {
            "$label contains a foreign listener socket"
        }
    }

    private fun socketInodes(rootPid: Int): Set<Long> = socketInodesProvider?.invoke(rootPid)
        ?: File(procRoot, "$rootPid/fd")
            .listFiles()
            .orEmpty()
            .mapNotNull { fd ->
                fd.absoluteFile.toPath().let { path ->
                    runCatching { java.nio.file.Files.readSymbolicLink(path).toString() }
                        .getOrNull()
                        ?.removePrefix("socket:[")
                        ?.removeSuffix("]")
                        ?.toLongOrNull()
                }
            }
            .toSet()

    private data class ProcSocketRow(val port: Int, val state: String, val inode: Long)

    @Suppress("ReturnCount")
    private fun parseRow(raw: String): ProcSocketRow? {
        val fields = raw.trim().split(Regex("\\s+"))
        if (fields.size < 10) return null
        val port = fields.getOrNull(1)?.substringAfter(':')?.toIntOrNull(16) ?: return null
        val state = fields.getOrNull(3) ?: return null
        val inode = fields.getOrNull(9)?.toLongOrNull() ?: return null
        return ProcSocketRow(port, state, inode)
    }
}

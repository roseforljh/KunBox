package com.kunk.singbox.service.root

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class RootListenerVerifierTest {
    @Test
    fun acceptsMultipleUdpSocketsWhenEverySocketBelongsToRootPid() = withProcFixture { proc ->
        writeTable(proc, "net/udp", listOf(row(16_000, "07", 100L), row(16_000, "07", 101L)))
        val verifier = RootListenerVerifier(proc) { setOf(100L, 101L) }

        assertTrue(verifier.verify(
            RootListenerExpectation(emptySet(), emptySet(), setOf(16_000), emptySet()),
            77
        ).isSuccess)
    }

    @Test
    fun rejectsExpectedPortWhenAnyMatchingSocketIsForeign() = withProcFixture { proc ->
        writeTable(proc, "net/tcp", listOf(row(16_001, "0A", 200L), row(16_001, "0A", 201L)))
        val verifier = RootListenerVerifier(proc) { setOf(200L) }

        assertTrue(verifier.verify(
            RootListenerExpectation(setOf(16_001), emptySet(), emptySet(), emptySet()),
            77
        ).isFailure)
    }

    @Test
    fun verifyAbsentRejectsRootOwnedListener() = withProcFixture { proc ->
        writeTable(proc, "net/tcp6", listOf(row(16_002, "0A", 300L)))
        val verifier = RootListenerVerifier(proc) { setOf(300L) }

        assertTrue(verifier.verifyAbsent(
            RootListenerExpectation(emptySet(), setOf(16_002), emptySet(), emptySet())
        ).isFailure)
    }

    private fun row(port: Int, state: String, inode: Long): String =
        "0: 00000000:${port.toString(16).padStart(4, '0')} 00000000:0000 $state " +
            "00000000:00000000 00:00000000 00000000 0 0 $inode"

    private fun writeTable(proc: File, relativePath: String, rows: List<String>) {
        val file = File(proc, relativePath)
        file.parentFile?.mkdirs()
        file.writeText("header\n${rows.joinToString("\n")}\n")
    }

    private inline fun withProcFixture(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("root-listener-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}

package com.kunk.singbox.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SingBoxCoreLatencyPathTest {

    @Test
    fun testPublicLatencyEntrypointsUseOnlyOfflineTemporaryServicePath() {
        val source = readSingBoxCoreSource()
        val singleNodeBody = extractFunctionBody(source, "testOutboundLatency")
        val batchBody = extractFunctionBody(source, "testOutboundsLatency")
        val latencyEntrypoints = singleNodeBody + "\n" + batchBody

        assertTrue(singleNodeBody.contains("testOutboundLatencyWithOfflineTemporaryService"))
        assertTrue(batchBody.contains("testOutboundsLatencyOfflineWithTemporaryService"))

        listOf(
            "SafeLatencyTester",
            "urlTestBatch",
            "urlTestGroup",
            "testOutboundLatencyWithLibbox",
            "testWithLibboxStaticUrlTest",
            "VpnStateStore.getActive()",
            "SingBoxService.instance"
        ).forEach { forbidden ->
            assertFalse("Latency entrypoints must not use $forbidden", latencyEntrypoints.contains(forbidden))
        }
    }

    private fun readSingBoxCoreSource(): String {
        val candidates = listOf(
            File("src/main/java/com/kunk/singbox/core/SingBoxCore.kt"),
            File("app/src/main/java/com/kunk/singbox/core/SingBoxCore.kt")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("SingBoxCore.kt not found from ${File(".").absolutePath}")
    }

    private fun extractFunctionBody(source: String, functionName: String): String {
        val marker = "suspend fun $functionName("
        val start = source.indexOf(marker)
        require(start >= 0) { "$functionName not found" }
        val bodyStart = source.indexOf('{', start)
        require(bodyStart >= 0) { "$functionName body not found" }

        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return source.substring(bodyStart, index + 1)
                    }
                }
            }
        }
        error("$functionName body end not found")
    }
}

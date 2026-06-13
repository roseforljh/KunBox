package com.kunk.singbox.repository

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigRepositoryLatencySourceTest {

    @Test
    fun singleNodeLatencyPassesRuntimeDnsConfigToCore() {
        val source = readConfigRepositorySource()
        val body = extractFunctionBody(source, "testNodeLatency")

        assertTrue(body.contains("val runtimeContext = buildLatencyRuntimeContext"))
        assertTrue(body.contains("runtimeContext.dnsConfig"))
        assertTrue(body.contains("singBoxCore.testOutboundLatency("))
    }

    @Test
    fun batchLatencyGroupsByDnsConfigAndPassesItToCore() {
        val source = readConfigRepositorySource()
        val body = extractFunctionBody(source, "testRegularOutboundsLatency")

        assertTrue(body.contains("val infosByDnsConfig = infos.groupBy { it.dnsConfig }"))
        assertTrue(body.contains("singBoxCore.testOutboundsLatency(preparedInfoPairs.map { it.second }, dnsConfig)"))
    }

    private fun readConfigRepositorySource(): String {
        val candidates = listOf(
            File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt"),
            File("app/src/main/java/com/kunk/singbox/repository/ConfigRepository.kt")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("ConfigRepository.kt not found from ${File(".").absolutePath}")
    }

    private fun extractFunctionBody(source: String, functionName: String): String {
        val marker = "fun $functionName("
        val start = source.indexOf(marker).takeIf { it >= 0 }
            ?: source.indexOf("suspend fun $functionName(")
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

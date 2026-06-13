package com.kunk.singbox.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigRepositorySourceTest {
    @Test
    fun customRuleSetConfigUsesDetectedFileFormat() {
        val source = readConfigRepositorySourcesForTextTests()

        assertTrue(source.contains("fun detectValidRuleSetFileFormat(file: File, tag: String): String?"))
        assertTrue(source.contains("format = detectedFormat"))
        assertFalse(source.contains("format = ruleSet.format,\n                        path = localPath"))
        assertFalse(source.contains("format = ruleSet.format,\n                        path = ruleSet.path"))
    }

    @Test
    fun togglingSubscriptionProfileUpdatesAutoUpdateWork() {
        val source = readConfigRepositorySourcesForTextTests()

        assertTrue(source.contains("var updatedProfile: ProfileUi? = null"))
        assertTrue(source.contains("if (profile.type == ProfileType.Subscription)"))
        assertTrue(source.contains("profile.enabled && profile.autoUpdateInterval > 0"))
        assertTrue(source.contains("SubscriptionAutoUpdateWorker.schedule"))
        assertTrue(source.contains("SubscriptionAutoUpdateWorker.cancel(context, profile.id)"))
    }

    @Test
    fun directProfileImportParsesNodesBeforePersistingProfileState() {
        val source = readConfigRepositorySourcesForTextTests()
        val function = extractKotlinFunctionBodyForTextTests(source, "importProfileDirectly")

        val ioIndex = function.indexOf("withContext(Dispatchers.IO)")
        val extractIndex = function.indexOf("val nodes = extractNodesFromConfigSync")
        val insertIndex = function.indexOf("profileDao.insert(entity)")
        val cacheIndex = function.indexOf("cacheConfig(profile.id, deduplicatedConfig)")

        assertTrue(ioIndex >= 0)
        assertTrue(extractIndex >= 0)
        assertTrue(insertIndex >= 0)
        assertTrue(cacheIndex >= 0)
        assertTrue(ioIndex < extractIndex)
        assertTrue(extractIndex < insertIndex)
        assertTrue(extractIndex < cacheIndex)
    }

    @Test
    fun nodeExtractionInitializesTrafficRepositoryOnIoBeforeCpuParsing() {
        val source = readConfigRepositorySourcesForTextTests()
        val function = extractKotlinFunctionBodyForTextTests(source, "extractNodesFromConfig")

        val ioInitIndex = function.indexOf("val trafficRepo = withContext(Dispatchers.IO)")
        val defaultParsingIndex = function.indexOf("return withContext(Dispatchers.Default)")

        assertTrue(ioInitIndex >= 0)
        assertTrue(defaultParsingIndex >= 0)
        assertTrue(ioInitIndex < defaultParsingIndex)
    }

    @Test
    fun deleteNodeRunsConfigFileWorkOnIoDispatcher() {
        val source = readConfigRepositorySourcesForTextTests()
        val function = extractKotlinFunctionBodyForTextTests(source, "deleteNode")

        assertTrue(function.contains("withContext(Dispatchers.IO)"))
        assertTrue(function.contains("loadConfig(profileId)"))
        assertTrue(function.contains("writeConfigFileOrThrow(profileId, newConfig)"))
        assertTrue(function.contains("return@withContext"))
    }

    @Test
    fun profileInitializationRunsOnIoAndRuntimeConfigWaitsForIt() {
        val source = readConfigRepositorySourcesForTextTests()
        val initBlock = extractKotlinBlockAfterMarkerForTextTests(source, "init {")
        val generateConfig = extractKotlinFunctionBodyForTextTests(source, "generateConfigFile")

        assertTrue(initBlock.contains("initialProfilesLoadJob = scope.launch"))
        assertTrue(initBlock.contains("loadProfileNodeMemory()"))
        assertTrue(initBlock.contains("loadSavedProfiles()"))
        assertTrue(generateConfig.contains("withContext(Dispatchers.IO)"))
        assertTrue(generateConfig.contains("awaitInitialProfilesLoaded()"))
    }

    @Test
    fun profileAndNodeMutationsKeepFileIoOffCallingThread() {
        val source = readConfigRepositorySourcesForTextTests()
        val deleteProfile = extractKotlinFunctionBodyForTextTests(source, "deleteProfile")
        val renameNode = extractKotlinFunctionBodyForTextTests(source, "renameNode")
        val updateNode = extractKotlinFunctionBodyForTextTests(source, "updateNode")

        assertTrue(deleteProfile.contains("withContext(Dispatchers.IO)"))
        assertTrue(deleteProfile.contains("configFile.delete()"))
        assertTrue(deleteProfile.contains("profileDao.deleteById(profileId)"))
        assertTrue(renameNode.contains("withContext(Dispatchers.IO)"))
        assertTrue(renameNode.contains("loadConfig(profileId)"))
        assertTrue(renameNode.contains("writeConfigFileOrThrow(profileId, newConfig)"))
        assertTrue(updateNode.contains("withContext(Dispatchers.IO)"))
        assertTrue(updateNode.contains("loadConfig(profileId)"))
        assertTrue(updateNode.contains("writeConfigFileOrThrow(profileId, newConfig)"))
    }

    @Test
    fun nodeExportLoadsConfigOnIoDispatcher() {
        val source = readConfigRepositorySourcesForTextTests()
        val exportNode = extractKotlinFunctionBodyForTextTests(source, "exportNode")

        assertTrue(exportNode.contains("withContext(Dispatchers.IO)"))
        assertTrue(exportNode.contains("loadConfig(node.sourceProfileId)"))
        assertTrue(exportNode.contains("NodeLinkExporter.export(outbound, gson)"))
    }

    @Test
    fun activeConfigAndOutboundLookupLoadConfigOnIoDispatcher() {
        val source = readConfigRepositorySourcesForTextTests()
        val activeConfig = extractKotlinFunctionBodyForTextTests(source, "getActiveConfig")
        val outboundByNode = extractKotlinFunctionBodyForTextTests(source, "getOutboundByNodeId")

        assertTrue(activeConfig.contains("withContext(Dispatchers.IO)"))
        assertTrue(activeConfig.contains("loadConfig(id)"))
        assertTrue(outboundByNode.contains("withContext(Dispatchers.IO)"))
        assertTrue(outboundByNode.contains("loadConfig(node.sourceProfileId)"))
    }
}

internal fun readConfigRepositorySourcesForTextTests(): String {
    val candidates = listOf(
        File("src/main/java/com/kunk/singbox/repository"),
        File("app/src/main/java/com/kunk/singbox/repository")
    )
    val sourceDir = candidates.firstOrNull { it.isDirectory }
        ?: error("ConfigRepository sources not found from ${File(".").absolutePath}")

    return sourceDir.listFiles { file ->
        file.isFile && file.name.startsWith("ConfigRepository") && file.extension == "kt"
    }.orEmpty()
        .sortedWith(compareBy<File> { configRepositorySourceOrder(it.name) }.thenBy { it.name })
        .joinToString(separator = "\n") { it.readText() }
}

internal fun extractKotlinFunctionBodyForTextTests(source: String, functionName: String): String {
    val markers = listOf(
        "protected override suspend fun $functionName(",
        "override suspend fun $functionName(",
        "protected override fun $functionName(",
        "override fun $functionName(",
        "private suspend fun $functionName(",
        "suspend fun $functionName(",
        "private fun $functionName(",
        "fun $functionName("
    )
    val start = markers.asSequence()
        .map { source.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull()
        ?: error("$functionName not found")
    val bodyStart = source.indexOf('{', start)
    val body = extractKotlinBlockAtStartForTextTests(source, bodyStart, "$functionName body")
    return source.substring(start, bodyStart) + body
}

internal fun extractKotlinBlockAfterMarkerForTextTests(source: String, marker: String): String {
    val markerStart = source.indexOf(marker)
    require(markerStart >= 0) { "$marker not found" }
    return extractKotlinBlockAtStartForTextTests(source, source.indexOf('{', markerStart), "$marker block")
}

private fun extractKotlinBlockAtStartForTextTests(source: String, bodyStart: Int, label: String): String {
    require(bodyStart >= 0) { "$label not found" }

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
    error("$label end not found")
}

private fun configRepositorySourceOrder(fileName: String): Int {
    return when {
        fileName == "ConfigRepository.kt" -> 0
        fileName.startsWith("ConfigRepositoryPart") -> 1
        fileName.startsWith("ConfigRepositoryCompanion") -> 2
        fileName == "ConfigRepositoryBase.kt" -> 3
        fileName == "ConfigRepositoryTypes.kt" -> 4
        else -> 5
    }
}

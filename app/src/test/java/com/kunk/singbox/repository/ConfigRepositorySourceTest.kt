package com.kunk.singbox.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigRepositorySourceTest {
    @Test
    fun customRuleSetConfigUsesDetectedFileFormat() {
        val source = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt").readText()

        assertTrue(source.contains("private fun detectValidRuleSetFileFormat(file: File, tag: String): String?"))
        assertTrue(source.contains("format = detectedFormat"))
        assertFalse(source.contains("format = ruleSet.format,\n                        path = localPath"))
        assertFalse(source.contains("format = ruleSet.format,\n                        path = ruleSet.path"))
    }

    @Test
    fun togglingSubscriptionProfileUpdatesAutoUpdateWork() {
        val source = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt").readText()

        assertTrue(source.contains("var updatedProfile: ProfileUi? = null"))
        assertTrue(source.contains("if (profile.type == ProfileType.Subscription)"))
        assertTrue(source.contains("profile.enabled && profile.autoUpdateInterval > 0"))
        assertTrue(source.contains("SubscriptionAutoUpdateWorker.schedule"))
        assertTrue(source.contains("SubscriptionAutoUpdateWorker.cancel(context, profile.id)"))
    }

    @Test
    fun directProfileImportParsesNodesBeforePersistingProfileState() {
        val source = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt").readText()
        val function = source.substringAfter("suspend fun importProfileDirectly")
            .substringBefore("fun toggleProfileEnabled")

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
        val source = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt").readText()
        val function = source.substringAfter("private suspend fun extractNodesFromConfig")
            .substringBefore("private fun extractNodesFromConfigSync")

        val ioInitIndex = function.indexOf("val trafficRepo = withContext(Dispatchers.IO)")
        val defaultParsingIndex = function.indexOf("return withContext(Dispatchers.Default)")

        assertTrue(ioInitIndex >= 0)
        assertTrue(defaultParsingIndex >= 0)
        assertTrue(ioInitIndex < defaultParsingIndex)
    }

    @Test
    fun deleteNodeRunsConfigFileWorkOnIoDispatcher() {
        val source = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt").readText()
        val function = source.substringAfter("suspend fun deleteNode")
            .substringBefore("suspend fun addSingleNode")

        assertTrue(function.contains("withContext(Dispatchers.IO)"))
        assertTrue(function.contains("loadConfig(profileId)"))
        assertTrue(function.contains("writeConfigFileOrThrow(profileId, newConfig)"))
        assertTrue(function.contains("return@withContext"))
    }

    @Test
    fun profileInitializationRunsOnIoAndRuntimeConfigWaitsForIt() {
        val source = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt").readText()
        val initBlock = source.substringAfter("init {")
            .substringBefore("private suspend fun awaitInitialProfilesLoaded")
        val generateConfig = source.substringAfter("suspend fun generateConfigFile")
            .substringBefore("private fun buildOutboundForRuntime")

        assertTrue(initBlock.contains("initialProfilesLoadJob = scope.launch"))
        assertTrue(initBlock.contains("loadProfileNodeMemory()"))
        assertTrue(initBlock.contains("loadSavedProfiles()"))
        assertTrue(generateConfig.contains("withContext(Dispatchers.IO)"))
        assertTrue(generateConfig.contains("awaitInitialProfilesLoaded()"))
    }

    @Test
    fun profileAndNodeMutationsKeepFileIoOffCallingThread() {
        val source = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt").readText()
        val deleteProfile = source.substringAfter("suspend fun deleteProfile")
            .substringBefore("suspend fun importProfileDirectly")
        val renameNode = source.substringAfter("suspend fun renameNode")
            .substringBefore("suspend fun updateNode")
        val updateNode = source.substringAfter("suspend fun updateNode")
            .substringBefore("fun exportNode")

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
        val source = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt").readText()
        val exportNode = source.substringAfter("suspend fun exportNode")
            .substringBefore("private fun deduplicateTags")

        assertTrue(exportNode.contains("withContext(Dispatchers.IO)"))
        assertTrue(exportNode.contains("loadConfig(node.sourceProfileId)"))
        assertTrue(exportNode.contains("NodeLinkExporter.export(outbound, gson)"))
    }

    @Test
    fun activeConfigAndOutboundLookupLoadConfigOnIoDispatcher() {
        val source = File("src/main/java/com/kunk/singbox/repository/ConfigRepository.kt").readText()
        val activeConfig = source.substringAfter("suspend fun getActiveConfig")
            .substringBefore("fun getConfig")
        val outboundByNode = source.substringAfter("suspend fun getOutboundByNodeId")
            .substringBefore("fun getNodeById")

        assertTrue(activeConfig.contains("withContext(Dispatchers.IO)"))
        assertTrue(activeConfig.contains("loadConfig(id)"))
        assertTrue(outboundByNode.contains("withContext(Dispatchers.IO)"))
        assertTrue(outboundByNode.contains("loadConfig(node.sourceProfileId)"))
    }
}

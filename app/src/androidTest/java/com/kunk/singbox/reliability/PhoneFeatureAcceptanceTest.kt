package com.kunk.singbox.reliability

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.config.OutboundFixer
import com.kunk.singbox.repository.awaitInitialProfilesLoaded
import com.kunk.singbox.repository.exportNode
import com.kunk.singbox.repository.deleteProfile
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.generateConfigFile
import com.kunk.singbox.repository.importFromContent
import com.kunk.singbox.repository.loadConfig
import com.kunk.singbox.repository.parseNodeLink
import com.kunk.singbox.repository.saveProfilesImmediate
import com.kunk.singbox.repository.setActiveProfileAndWait
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneFeatureAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val gson = GsonBuilder().create()
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun importExportAndGenerateConfigAcceptance() = runBlocking {
        val runId = requireRunId()
        val contentPath = requireArgument("contentPath")
        val profileName = arguments.getString("profileName") ?: DEFAULT_PROFILE_NAME
        val runDir = File(context.externalCacheDir, runId).also { it.mkdirs() }
        val repository = ConfigRepository.getInstance(context)
        repository.awaitInitialProfilesLoaded()
        check(!VpnStateStore.getActive()) { "Stop VPN before feature acceptance" }
        val originalSelection = repository.activeProfileId.value to repository.activeNodeId.value
        val originalSettings = com.kunk.singbox.repository.SettingsRepository.getInstance(context).settings.value
        val beforeProfiles = repository.profiles.value
        val beforeHash = sha256(repository.profilesFileJson)
        val failures = mutableListOf<String>()
        val nodeResults = mutableListOf<NodeResult>()
        try {
            val before = com.kunk.singbox.repository.DataExportRepository.getInstance(context).exportAllData()
                .getOrThrow()
            File(runDir, "before.json").writeText(before, Charsets.UTF_8)
            val source = File(contentPath).readText(Charsets.UTF_8)
            val sourceConfig = gson.fromJson(source, SingBoxConfig::class.java)
            val sourceOutbounds = sourceConfig.outbounds.orEmpty()
            check(sourceOutbounds.size == EXPECTED_OUTBOUND_COUNT) {
                "expected $EXPECTED_OUTBOUND_COUNT outbounds, got ${sourceOutbounds.size}"
            }
            val profile = resolveProfile(repository, profileName, source, failures)
            if (profile == null) {
                failures += "profile unavailable"
            } else {
                repository.setActiveProfileAndWait(profile.id)
                val importedConfig = repository.loadConfig(profile.id)
                val importedOutbounds = importedConfig?.outbounds.orEmpty()
                checkImportedOutbounds(sourceOutbounds, importedOutbounds, failures)
                val nodes = repository.nodes.value.toList()
                if (nodes.size != EXPECTED_OUTBOUND_COUNT) {
                    failures += "node count mismatch: ${nodes.size}"
                }
                sourceOutbounds.forEach { sourceOutbound ->
                    val node = nodes.firstOrNull { it.name == sourceOutbound.tag }
                    if (node == null) {
                        failures += "missing node tag ${safeTag(sourceOutbound.tag)}"
                    } else {
                        nodeResults += verifyNode(repository, profile.id, node.id, sourceOutbound, importedOutbounds)
                    }
                }
            }
        } catch (error: Throwable) {
            failures += "setup failed: ${safeError(error)}"
        } finally {
            val afterSettings = com.kunk.singbox.repository.SettingsRepository.getInstance(context).settings.value
            if (afterSettings != originalSettings) {
                failures += "settings changed during acceptance config validation"
            }
            val configResult = AcceptanceConfigResult(
                settingsChanged = afterSettings != originalSettings,
                fullGenerateConfigEquivalent = false,
                outboundChecks = nodeResults.size
            )
            File(runDir, "config-validation.json").writeText(gson.toJson(configResult), Charsets.UTF_8)
            runCatching {
                repository.profiles.value.filter { profile ->
                    profile.name == profileName && beforeProfiles.none { it.id == profile.id }
                }.forEach { repository.deleteProfile(it.id) }
                repository.saveProfilesJob?.join()
                check(repository.profiles.value.map { it.id }.toSet() == beforeProfiles.map { it.id }.toSet())
            }.onFailure { failures += "test profile cleanup failed: ${safeError(it)}" }
            runCatching { restoreSelection(repository, originalSelection) }
                .onFailure { failures += "selection restore failed: ${safeError(it)}" }
        }
        val afterProfiles = repository.profiles.value
        val afterExport = com.kunk.singbox.repository.DataExportRepository.getInstance(context).exportAllData()
        if (afterExport.isSuccess) {
            File(runDir, "after.json").writeText(afterExport.getOrThrow(), Charsets.UTF_8)
        } else {
            failures += "after export failed: ${safeError(afterExport.exceptionOrNull())}"
        }
        val result = AcceptanceResult(
            runId = runId,
            profileName = profileName,
            profileId = afterProfiles.firstOrNull { it.name == profileName }?.id,
            nodeIds = nodeResults.map { it.nodeId },
            nodeTags = nodeResults.map { it.tag },
            expectedOutbounds = EXPECTED_OUTBOUND_COUNT,
            beforeProfilesHash = beforeHash,
            afterProfilesHash = sha256(repository.profilesFileJson),
            beforeProfileIds = beforeProfiles.map { it.id },
            afterProfileIds = afterProfiles.map { it.id },
            sameNamedProfileCount = afterProfiles.count { it.name == profileName },
            nodes = nodeResults,
            failures = failures,
            passed = failures.isEmpty() && nodeResults.size == EXPECTED_OUTBOUND_COUNT &&
                nodeResults.all { it.passed }
        )
        File(runDir, "result.json").writeText(gson.toJson(result), Charsets.UTF_8)
        assertTrue("See ${File(runDir, "result.json").absolutePath}", result.passed)
    }

    private suspend fun resolveProfile(
        repository: ConfigRepository,
        profileName: String,
        source: String,
        failures: MutableList<String>
    ): com.kunk.singbox.model.ProfileUi? {
        val matches = repository.profiles.value.filter { it.name == profileName }
        if (matches.size > 1) {
            failures += "duplicate profile name: ${safeTag(profileName)}"
            return null
        }
        if (matches.singleOrNull() != null) return matches.single()
        return repository.importFromContent(profileName, source, ProfileType.Imported).fold(
            onSuccess = { it },
            onFailure = {
                failures += "import failed: ${safeError(it)}"
                null
            }
        )
    }

    private fun checkImportedOutbounds(
        source: List<Outbound>,
        imported: List<Outbound>,
        failures: MutableList<String>
    ) {
        if (imported.size != EXPECTED_OUTBOUND_COUNT) {
            failures += "imported outbound count mismatch: ${imported.size}"
        }
        source.forEach { expected ->
            val actual = imported.firstOrNull { it.tag == expected.tag }
            if (actual == null) {
                failures += "imported outbound missing: ${safeTag(expected.tag)}"
            } else {
                compareOutbound(expected, actual, "import", failures)
            }
        }
    }

    private suspend fun verifyNode(
        repository: ConfigRepository,
        profileId: String,
        nodeId: String,
        expected: Outbound,
        imported: List<Outbound>
    ): NodeResult {
        val failures = mutableListOf<String>()
        val importedOutbound = imported.firstOrNull { it.tag == expected.tag }
        var exported = false
        var roundTrip = false
        var generated = false
        var validated = false
        runCatching {
            repository.setActiveProfileAndWait(profileId, nodeId)
            val link = repository.exportNode(nodeId) ?: error("export returned no link")
            exported = true
            val parsed = repository.parseNodeLink(link) ?: error("parser returned no outbound")
            compareOutbound(expected, parsed, "roundtrip", failures)
            roundTrip = failures.none { it.startsWith("roundtrip") }
        }.onFailure { failures += "export/roundtrip failed: ${safeError(it)}" }
        runCatching {
            val runtimeOutbound = OutboundFixer.buildForRuntime(context, expected)
                ?: error("runtime outbound conversion returned no output")
            val validation = SingBoxCore.getInstance(context).validateConfig(
                SingBoxConfig(outbounds = listOf(runtimeOutbound))
            )
            if (validation.isFailure) error(safeError(validation.exceptionOrNull()))
            generated = true
            validated = true
        }.onFailure { failures += "runtime outbound validation failed: ${safeError(it)}" }
        if (importedOutbound == null) failures += "node source outbound missing"
        return NodeResult(nodeId, expected.tag, exported, roundTrip, generated, validated, failures, failures.isEmpty())
    }

    private suspend fun verifyNodesWithoutSource(
        repository: ConfigRepository,
        profileId: String,
        nodes: List<com.kunk.singbox.model.NodeUi>,
        source: List<Outbound>,
        results: List<NodeResult>
    ): List<NodeResult> {
        return nodes.filter { node -> source.none { it.tag == node.name } && results.none { it.nodeId == node.id } }
            .map { node ->
                verifyNode(
                    repository,
                    profileId,
                    node.id,
                    Outbound(type = node.protocol, tag = node.name),
                    emptyList()
                )
            }
    }

    private fun compareOutbound(
        expected: Outbound,
        actual: Outbound,
        stage: String,
        failures: MutableList<String>
    ) {
        val expectedJson = gson.toJsonTree(expected).asJsonObject
        val actualJson = gson.toJsonTree(actual).asJsonObject
        KEY_FIELDS.forEach { key ->
            val expectedValue = expectedJson.get(key)
            val actualValue = actualJson.get(key)
            if (expectedValue != actualValue && (key == "packet_encoding" || expectedValue != null)) {
                failures += "$stage $key mismatch for ${safeTag(expected.tag)}"
            }
        }
        compareNested(expectedJson, actualJson, "tls", stage, expected.tag, failures)
        compareNested(expectedJson, actualJson, "transport", stage, expected.tag, failures)
    }

    private fun compareNested(
        expected: JsonObject,
        actual: JsonObject,
        name: String,
        stage: String,
        tag: String,
        failures: MutableList<String>
    ) {
        val expectedObject = expected.getAsJsonObject(name) ?: return
        val actualObject = actual.getAsJsonObject(name)
        if (actualObject == null) {
            failures += "$stage $name missing for ${safeTag(tag)}"
            return
        }
        NESTED_FIELDS[name].orEmpty().forEach { key ->
            val expectedValue = expectedObject.get(key)
            val actualValue = actualObject.get(key)
            if (expectedValue != actualValue && (name == "transport" || expectedValue != null)) {
                failures += "$stage $name.$key mismatch for ${safeTag(tag)}"
            }
        }
    }

    private suspend fun restoreSelection(repository: ConfigRepository, selection: Pair<String?, String?>) {
        val (profileId, nodeId) = selection
        if (profileId != null) {
            check(repository.profiles.value.any { it.id == profileId }) { "original profile missing" }
            repository.setActiveProfileAndWait(profileId, nodeId)
        } else {
            repository._activeProfileId.value = null
            repository._activeNodeId.value = null
            repository.saveProfilesImmediate()
        }
        repository.saveProfilesJob?.join()
        check(repository.activeProfileId.value == profileId && repository.activeNodeId.value == nodeId) {
            "original selection was not restored"
        }
    }

    private fun requireRunId(): String {
        val runId = requireArgument("runId")
        require(RUN_ID.matches(runId)) { "runId must contain only letters, digits and hyphens" }
        return runId
    }

    private fun requireArgument(name: String): String = arguments.getString(name)?.takeIf { it.isNotBlank() }
        ?: error("missing instrumentation arg: $name")

    private fun sha256(file: File): String? {
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun safeError(error: Throwable?): String = error?.message.orEmpty()
        .replace(Regex("(?i)(uuid|password|token|private[_ -]?key|certificate|secret)[^,; ]*"), "$1=***")
        .replace(Regex("[0-9a-f]{8}-[0-9a-f-]{27,}", RegexOption.IGNORE_CASE), "uuid-***")
        .take(240)

    private fun safeTag(tag: String): String = tag.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)

    private data class NodeResult(
        val nodeId: String,
        val tag: String,
        val exported: Boolean,
        val roundTrip: Boolean,
        val generated: Boolean,
        val validated: Boolean,
        val failures: List<String>,
        val passed: Boolean
    )

    private data class AcceptanceConfigResult(
        val settingsChanged: Boolean,
        val fullGenerateConfigEquivalent: Boolean,
        val outboundChecks: Int
    )

    private data class AcceptanceResult(
        val runId: String,
        val profileName: String,
        val profileId: String?,
        val nodeIds: List<String>,
        val nodeTags: List<String>,
        val expectedOutbounds: Int,
        val beforeProfilesHash: String?,
        val afterProfilesHash: String?,
        val beforeProfileIds: List<String>,
        val afterProfileIds: List<String>,
        val sameNamedProfileCount: Int,
        val nodes: List<NodeResult>,
        val failures: List<String>,
        val passed: Boolean
    )

    private companion object {
        const val EXPECTED_OUTBOUND_COUNT = 16
        const val DEFAULT_PROFILE_NAME = "HK 协议验收 20261106"
        val RUN_ID = Regex("[A-Za-z0-9-]+")
        val KEY_FIELDS = listOf(
            "type", "server", "server_port", "uuid", "method", "password", "security",
            "encryption", "packet_encoding", "detour", "private_key", "local_address", "peers"
        )
        val NESTED_FIELDS = mapOf(
            "tls" to listOf(
                "enabled", "server_name", "insecure", "alpn", "disable_sni", "utls", "reality",
                "certificate", "certificate_path", "client_certificate", "client_certificate_path",
                "client_key", "client_key_path"
            ),
            "transport" to listOf("type", "path", "service_name", "host", "headers", "max_early_data", "early_data_header_name")
        )
    }
}

package com.kunk.singbox.reliability

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.ExportData
import com.kunk.singbox.model.ImportOptions
import com.kunk.singbox.model.ImportResult
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.deleteProfile
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.DataExportRepository
import com.kunk.singbox.repository.addSingleNode
import com.kunk.singbox.repository.deleteNode
import com.kunk.singbox.repository.awaitInitialProfilesLoaded
import com.kunk.singbox.repository.exportNode
import com.kunk.singbox.repository.loadConfig
import com.kunk.singbox.repository.importFromContent
import com.kunk.singbox.repository.parseNodeLink
import com.kunk.singbox.repository.saveProfilesImmediate
import com.kunk.singbox.repository.setActiveProfileAndWait
import com.kunk.singbox.repository.updateNode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneProfileAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gson = Gson()

    @Test
    fun isolatedProfileAcceptance() = runBlocking {
        val repository = ConfigRepository.getInstance(context)
        repository.awaitInitialProfilesLoaded()
        val originalSelection = repository.activeProfileId.value to repository.activeNodeId.value
        val originalProfiles = repository.profiles.value.associateBy { it.id }
        val originalConfigs = originalProfiles.keys.associateWith { repository.loadConfig(it) }
        val originalSettings = SettingsRepository.getInstance(context).settings.value
        val imported = mutableMapOf<String, String>()
        val results = linkedMapOf<String, String>()
        val prefix = "phone-acceptance-${UUID.randomUUID()}"
        val resultFile = File(context.cacheDir, "$prefix.json")
        suspend fun step(name: String, action: suspend () -> Unit) {
            try {
                action()
                results[name] = "pass"
            } catch (error: Exception) {
                results[name] = "fail: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}"
            }
        }
        try {
            check(!VpnStateStore.getActive() && !SingBoxRemote.isRunning.value && !SingBoxRemote.isStarting.value) {
                "Stop VPN/proxy before editing nodes"
            }
            val link1 = "vless://11111111-1111-4111-8111-111111111111@192.0.2.11:443#accept-one"
            val link2 = "vless://22222222-2222-4222-8222-222222222222@192.0.2.12:443#accept-two"
            val json = """{"outbounds":[{"type":"vless","tag":"accept-json","server":"192.0.2.10","server_port":443,"uuid":"11111111-1111-4111-8111-111111111111"}]}"""
            val clash = """
                proxies:
                  - name: accept-clash
                    type: ss
                    server: 192.0.2.13
                    port: 443
                    cipher: aes-128-gcm
                    password: acceptance-only
            """.trimIndent()
            val contents = linkedMapOf(
                "json" to json,
                "clash" to clash,
                "base64" to Base64.encodeToString("$link1\n$link2".toByteArray(), Base64.NO_WRAP),
                "multiline" to "$link1\n$link2"
            )
            DataExportRepository.getInstance(context).exportAllData().getOrThrow()
            contents.forEach { (format, content) ->
                step("import_$format") {
                    val profile = repository.importFromContent("$prefix-$format", content).getOrThrow()
                    imported[format] = profile.id
                    val count = repository.loadConfig(profile.id)?.outbounds.orEmpty()
                        .count { it.type in setOf("vless", "shadowsocks") }
                    check(count == if (format in setOf("base64", "multiline")) 2 else 1) {
                        "unexpected outbound count: $count"
                    }
                }
            }
            step("add_edit_export_roundtrip") {
                val profileId = imported["json"] ?: error("JSON profile missing")
                val node = repository.addSingleNode(link1, targetProfileId = profileId).getOrThrow()
                val original = repository.loadConfig(profileId)?.outbounds.orEmpty().first { it.tag == node.name }
                repository.updateNode(node.id, original.copy(server = "192.0.2.21", serverPort = 8443))
                val updated = repository.loadConfig(profileId)?.outbounds.orEmpty().first { it.tag == node.name }
                check(updated.server == "192.0.2.21" && updated.serverPort == 8443)
                repository.setActiveProfileAndWait(profileId, node.id)
                val parsed = repository.parseNodeLink(repository.exportNode(node.id) ?: error("export failed"))
                    ?: error("roundtrip parse failed")
                check(parsed.type == updated.type && parsed.server == updated.server && parsed.serverPort == updated.serverPort)
                repository.deleteNode(node.id)
                check(repository.loadConfig(profileId)?.outbounds.orEmpty().none { it.tag == node.name })
            }
            step("invalid_input_preserves_profiles") {
                val before = repository.profiles.value.associateBy { it.id }
                val beforeConfigs = before.keys.associateWith { repository.loadConfig(it) }
                check(repository.importFromContent("$prefix-invalid", "not-a-valid-profile-%%%").isFailure)
                check(repository.profiles.value.associateBy { it.id } == before)
                check(beforeConfigs.all { (id, config) -> repository.loadConfig(id) == config })
            }
            step("backup_import_restores_test_profile") {
                val profileId = imported["json"] ?: error("JSON profile missing")
                verifyBackupRestore(repository, profileId, File(context.cacheDir, "$prefix-backup.json"))
                check(SettingsRepository.getInstance(context).settings.value == originalSettings)
                check(originalConfigs.all { (id, config) -> repository.loadConfig(id) == config })
            }
            step("full_export_preserves_originals") {
                val exported = DataExportRepository.getInstance(context).exportAllData().getOrThrow()
                val profiles = gson.fromJson(exported, com.google.gson.JsonObject::class.java)
                    .getAsJsonArray("profiles")
                val exportedIds = profiles.map { it.asJsonObject.getAsJsonObject("profile").get("id").asString }.toSet()
                check(exportedIds.containsAll(originalProfiles.keys))
                check(originalProfiles.all { (id, profile) -> repository.profiles.value.any { it == profile && it.id == id } })
                check(originalConfigs.all { (id, config) -> repository.loadConfig(id) == config })
            }
        } catch (error: Exception) {
            results["setup"] = "fail: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(160)}"
        } finally {
            step("cleanup_test_profiles") {
                imported.values.forEach { id ->
                    check(id !in originalProfiles && repository.profiles.value.any {
                        it.id == id && it.name.startsWith("$prefix-")
                    }) { "test profile identity mismatch" }
                    repository.deleteProfile(id)
                }
                check(repository.profiles.value.map { it.id }.toSet() == originalProfiles.keys)
                check(originalConfigs.all { (id, config) -> repository.loadConfig(id) == config })
                check(SettingsRepository.getInstance(context).settings.value == originalSettings)
            }
            try {
                val (profileId, nodeId) = originalSelection
                if (profileId != null && repository.profiles.value.any { it.id == profileId }) {
                    repository.setActiveProfileAndWait(profileId, nodeId)
                } else if (profileId == null) {
                    repository._activeProfileId.value = null
                    repository._activeNodeId.value = null
                    repository.saveProfilesImmediate()
                }
                repository.saveProfilesJob?.join()
                check(repository.activeProfileId.value == profileId && repository.activeNodeId.value == nodeId)
                results["restore_selection"] = "pass"
            } catch (error: Exception) {
                results["restore_selection"] = "fail: ${error.javaClass.simpleName}"
            }
            repository.saveProfilesJob?.join()
            resultFile.writeText(gson.toJson(results), Charsets.UTF_8)
        }
        assertTrue("See ${resultFile.absolutePath}: $results", results.values.all { it == "pass" })
    }

    private suspend fun verifyBackupRestore(repository: ConfigRepository, profileId: String, backupFile: File) {
        val exporter = DataExportRepository.getInstance(context)
        val exported = exporter.exportAllData().getOrThrow()
        backupFile.writeText(exported, Charsets.UTF_8)
        val backup = exporter.validateImportData(backupFile.readText(Charsets.UTF_8)).getOrThrow()
        val profile = backup.profiles.single { it.profile.id == profileId }
        repository.setActiveProfileAndWait(profileId)
        val node = repository.nodes.value.first()
        val outbound = profile.config.outbounds.orEmpty().single { it.tag == node.name }
        repository.updateNode(node.id, outbound.copy(server = "192.0.2.99", serverPort = 9443))
        check(repository.loadConfig(profileId) != profile.config) { "test mutation was not persisted" }
        val isolated: ExportData = backup.copy(
            profiles = listOf(profile),
            activeProfileId = profileId,
            activeNodeId = node.id,
            profileNodeMemory = backup.profileNodeMemory.orEmpty().filterKeys { it == profileId },
            profileAutoSelection = backup.profileAutoSelection.orEmpty().filterKeys { it == profileId }
        )
        val restored = exporter.importData(
            gson.toJson(isolated),
            ImportOptions(importSettings = false, importProfiles = true, importRules = false)
        ).getOrThrow()
        check(restored is ImportResult.Success && restored.profilesImported == 1 && !restored.settingsImported) {
            "profile backup import failed: $restored"
        }
        repository.saveProfilesJob?.join()
        check(repository.loadConfig(profileId) == profile.config) { "backup config was not restored" }
        check(repository.activeProfileId.value == profileId && repository.activeNodeId.value == node.id) {
            "backup selection was not restored"
        }
    }
}

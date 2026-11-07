package com.kunk.singbox.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.manager.VpnServiceManager
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.SubscriptionUpdateResult
import com.kunk.singbox.service.manager.VpnStopInitiator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneRemoteSubscriptionAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gson = Gson()
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun importRemoteSubscription() = runBlocking {
        awaitStopped()
        val repository = ConfigRepository.getInstance(context)
        repository.awaitInitialProfilesLoaded()
        val name = requireArgument("profileName")
        val url = requireArgument("subscriptionUrl")
        check(repository.profiles.value.none { it.name == name }) { "test profile already exists" }

        val profile = repository.importFromSubscription(name = name, url = url).getOrThrow()
        val nodes = repository.loadConfig(profile.id)?.outbounds.orEmpty()
            .filter { it.type != "direct" && it.type != "block" }
        check(profile.type == ProfileType.Subscription)
        check(profile.url == url)
        check(nodes.size == 16) { "expected 16 subscription nodes, got ${nodes.size}" }
        check(nodes.all { it.tag.startsWith("SUB · ") }) { "subscription tags were not imported" }
        if (repository.saveProfilesJob != null) repository.saveProfilesJob?.join()
        File(context.cacheDir, requireArgument("resultFile")).writeText(
            gson.toJson(mapOf("profileId" to profile.id, "nodes" to nodes.map { it.tag })),
            Charsets.UTF_8
        )
    }

    @Test
    fun updateRemoteSubscriptionAndCleanup() = runBlocking {
        awaitStopped()
        val repository = ConfigRepository.getInstance(context)
        repository.awaitInitialProfilesLoaded()
        val profileId = requireArgument("profileId")
        val profile = repository.profiles.value.single { it.id == profileId }
        val before = repository.loadConfig(profile.id)?.outbounds.orEmpty()
            .filter { it.type != "direct" && it.type != "block" }
            .map { it.tag }
            .toSet()
        check(before.size == 16)

        val result = repository.updateProfile(profile.id)
        check(result is SubscriptionUpdateResult.SuccessWithChanges) { "unexpected update result: $result" }
        val after = repository.loadConfig(profile.id)?.outbounds.orEmpty()
            .filter { it.type != "direct" && it.type != "block" }
            .map { it.tag }
            .toSet()
        check(after.size == 15) { "expected 15 updated nodes, got ${after.size}" }
        check(after != before)
        check(before - after == setOf("SUB · HK · wppt-early"))

        val staleNode = repository.allNodes.value.firstOrNull { it.name == "SUB · HK · wppt-early" }
        check(staleNode == null) { "removed subscription node remained in node index" }
        File(context.cacheDir, requireArgument("resultFile")).writeText(
            gson.toJson(mapOf("before" to before, "after" to after, "result" to result.toString())),
            Charsets.UTF_8
        )
        repository.deleteProfile(profile.id)
        assertTrue(repository.profiles.value.none { it.id == profile.id })
    }

    @Test
    fun importUpdateAndCleanupIsolatedSubscription() = runBlocking {
        awaitStopped()
        val repository = ConfigRepository.getInstance(context)
        repository.awaitInitialProfilesLoaded()
        val originalIds = repository.profiles.value.map { it.id }.toSet()
        val originalSelection = repository.activeProfileId.value to repository.activeNodeId.value
        val originalSettings = SettingsRepository.getInstance(context).settings.value
        val exporter = DataExportRepository.getInstance(context)
        val backup = exporter.exportAllData().getOrThrow()
        val originalConfigs = originalIds.associateWith { repository.loadConfig(it) }
        val resultFile = File(context.cacheDir, requireArgument("resultFile"))
        File(context.cacheDir, "${resultFile.name}-backup.json").writeText(backup)
        var testId: String? = null
        val result = linkedMapOf<String, Any>()
        try {
            val profile = repository.importFromSubscription(
                name = "phone-sub-${UUID.randomUUID()}", url = requireArgument("subscriptionUrl")
            ).getOrThrow()
            testId = profile.id
            val beforeConfig = repository.loadConfig(profile.id) ?: error("import config missing")
            val before = beforeConfig.outbounds.orEmpty()
                .filter { it.tag.startsWith("SUB · ") }
            check(before.size == 16) {
                "expected 16 imported nodes, got ${before.size}: ${before.map { it.tag }}"
            }
            val updated = repository.updateProfile(profile.id)
            check(updated is SubscriptionUpdateResult.SuccessWithChanges)
            val after = repository.loadConfig(profile.id)?.outbounds.orEmpty().filter { it.tag.startsWith("SUB · ") }
            check(after.size == 15)
            check(before.map { it.tag }.toSet() - after.map { it.tag }.toSet() == setOf("SUB · HK · wppt-early"))
            check(after.all { actual -> before.single { it.tag == actual.tag } == actual })
            repository.setActiveProfileAndWait(profile.id)
            check(repository.nodes.value.none { it.name == "SUB · HK · wppt-early" })
            val generation = repository.generateConfigFile(selectedProfileId = profile.id)
                ?: error("subscription runtime config missing")
            result["before"] = before.map { it.tag }
            result["after"] = after.map { it.tag }
            result["profileId"] = profile.id
            result["config"] = generation.path
            result["fieldsPreserved"] = true
        } finally {
            testId?.let { id ->
                check(id !in originalIds)
                repository.deleteProfile(id)
            }
            val (originalProfileId, originalNodeId) = originalSelection
            if (originalProfileId != null) {
                repository.setActiveProfileAndWait(originalProfileId, originalNodeId)
                check(repository.activeProfileId.value == originalProfileId) {
                    "profile restore mismatch expected=$originalProfileId actual=${repository.activeProfileId.value}"
                }
                if (originalNodeId != null && repository.activeNodeId.value != originalNodeId) {
                    val restored = repository.nodes.value.firstOrNull { it.id == originalNodeId }
                    check(restored != null) { "original node missing after profile restore: $originalNodeId" }
                    repository._activeNodeId.value = originalNodeId
                    VpnStateStore.setSelectedNode(originalProfileId, originalNodeId)
                    VpnStateStore.setSelectedNodeLabel(restored.name)
                    repository.saveProfilesImmediate()
                }
                if (originalNodeId == null) {
                    repository._activeNodeId.value = null
                    VpnStateStore.setSelectedNode(originalProfileId, null)
                    VpnStateStore.setSelectedNodeLabel(null)
                    repository.saveProfilesImmediate()
                }
            } else {
                repository._activeProfileId.value = null
                repository._activeNodeId.value = null
                repository.saveProfilesImmediate()
            }
            repository.saveProfilesJob?.join()
            check(repository.profiles.value.map { it.id }.toSet() == originalIds) {
                "profile cleanup changed profile ids"
            }
            check(originalConfigs.all { (id, config) -> repository.loadConfig(id) == config }) {
                "profile cleanup changed original config"
            }
            check(SettingsRepository.getInstance(context).settings.value == originalSettings) {
                "profile cleanup changed settings"
            }
            check(
                repository.activeProfileId.value == originalProfileId &&
                    repository.activeNodeId.value == originalNodeId
            ) {
                "selection restore mismatch expected=$originalSelection actual=" +
                    "${repository.activeProfileId.value to repository.activeNodeId.value}"
            }
            result["cleanupAndRestore"] = true
            resultFile.writeText(gson.toJson(result))
        }
        result["passed"] = true
        resultFile.writeText(gson.toJson(result))
    }

    private fun requireStopped() {
        check(!VpnStateStore.getActive() && !SingBoxRemote.isRunning.value && !SingBoxRemote.isStarting.value) {
            "Stop VPN/proxy before remote subscription acceptance"
        }
    }

    private suspend fun awaitStopped() {
        if (!VpnStateStore.getActive() && !SingBoxRemote.isRunning.value && !SingBoxRemote.isStarting.value) return
        VpnServiceManager.stopVpn(context, VpnStopInitiator.UNKNOWN).getOrThrow()
        check(withTimeoutOrNull(45_000L) {
            while (VpnStateStore.getActive() || SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value) {
                delay(250L)
            }
        } != null) { "VPN/proxy did not stop before remote subscription acceptance" }
    }

    private fun requireArgument(name: String): String =
        arguments.getString(name)?.takeIf { it.isNotBlank() } ?: error("missing instrumentation arg: $name")
}

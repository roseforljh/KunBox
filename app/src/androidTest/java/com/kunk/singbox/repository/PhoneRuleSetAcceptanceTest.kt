package com.kunk.singbox.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.RoutingMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneRuleSetAcceptanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val gson = Gson()
    private val arguments = InstrumentationRegistry.getArguments()

    @Test
    fun downloadEnableAndGenerateRuleSet() = runBlocking {
        check(!VpnStateStore.getActive() && !SingBoxRemote.isRunning.value && !SingBoxRemote.isStarting.value)
        val repository = ConfigRepository.getInstance(context)
        repository.awaitInitialProfilesLoaded()
        val settingsRepository = SettingsRepository.getInstance(context)
        val ruleSetRepository = RuleSetRepository.getInstance(context)
        val profileId = requireArgument("profileId")
        val original = settingsRepository.settings.value
        val ruleSet = original.ruleSets.first { it.tag == "geosite-category-ads-all" }
            .copy(enabled = true, outboundMode = RuleSetOutboundMode.BLOCK)
        check(ruleSetRepository.prefetchRuleSet(ruleSet, forceUpdate = true, allowNetwork = true, requireSource = true))
        try {
            settingsRepository.setRoutingMode(RoutingMode.RULE, notifyRestartRequired = false)
            settingsRepository.setRuleSets(original.ruleSets.map { if (it.tag == ruleSet.tag) ruleSet else it })
            val saved = settingsRepository.settings.value.ruleSets.first { it.tag == ruleSet.tag }
            check(saved.enabled)
            val generation = repository.generateConfigFile(selectedProfileId = profileId)
                ?: error("config generation failed")
            val config = gson.fromJson(File(generation.path).readText(), com.kunk.singbox.model.SingBoxConfig::class.java)
            check(config.route?.ruleSet.orEmpty().any { it.tag == saved.tag }) { "rule set definition missing" }
            check(config.route?.rules.orEmpty().any { it.ruleSet?.contains(saved.tag) == true }) {
                "enabled rule set has no route match"
            }
            File(context.cacheDir, requireArgument("resultFile")).writeText(
                gson.toJson(mapOf(
                    "tag" to saved.tag,
                    "enabled" to saved.enabled,
                    "path" to ruleSetRepository.getRuleSetPath(saved.tag),
                    "config" to generation.path
                )),
                Charsets.UTF_8
            )
        } finally {
            settingsRepository.setRuleSets(original.ruleSets)
            settingsRepository.setRoutingMode(original.routingMode, notifyRestartRequired = false)
            assertTrue(settingsRepository.settings.value.ruleSets == original.ruleSets)
            assertTrue(settingsRepository.settings.value.routingMode == original.routingMode)
        }
    }

    private fun requireArgument(name: String): String =
        arguments.getString(name)?.takeIf { it.isNotBlank() } ?: error("missing instrumentation arg: $name")
}

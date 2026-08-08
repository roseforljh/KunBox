package com.kunk.singbox.repository

import com.kunk.singbox.model.AppLanguage
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.AppThemeStyle
import com.kunk.singbox.model.BackgroundPowerSavingDelay
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.IpVersionMode
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.RuleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsRepositoryTest {

    @Test
    fun sanitizeProxyPortKeepsValidPortAndRejectsInvalidValues() {
        assertEquals(2080, SettingsRepository.sanitizeProxyPort(2080))
        assertEquals(1, SettingsRepository.sanitizeProxyPort(0))
        assertEquals(65535, SettingsRepository.sanitizeProxyPort(70000))
    }

    @Test
    fun sanitizeLatencyConcurrencyKeepsSemaphorePermitRange() {
        assertEquals(5, SettingsRepository.sanitizeLatencyTestConcurrency(5))
        assertEquals(1, SettingsRepository.sanitizeLatencyTestConcurrency(0))
        assertEquals(20, SettingsRepository.sanitizeLatencyTestConcurrency(99))
    }

    @Test
    fun buildImportedSettingsImportsPreviouslySkippedFields() {
        val currentLatencyTestUrl = "https://probe.current.example/204"
        val imported = AppSettings(
            appLanguage = AppLanguage.ENGLISH,
            appThemeStyle = AppThemeStyle.LIQUID_GLASS,
            showNotificationSpeed = false,
            ipVersionMode = IpVersionMode.IPV6_ONLY,
            tunMtuAuto = false,
            fakeIpExcludeDomains = "login.example.com",
            tcpKeepAliveEnabled = false,
            tcpKeepAliveInterval = 45,
            subscriptionUpdateTimeout = 12,
            autoCheckUpdate = false,
            backgroundPowerSavingDelay = BackgroundPowerSavingDelay.NEVER,
            proxyPort = 70000,
            latencyTestUrl = "file:///tmp/probe",
            latencyTestConcurrency = 99,
            ruleSetAutoUpdateInterval = 1
        )

        val result = SettingsRepository.buildImportedSettings(
            current = AppSettings(latencyTestUrl = currentLatencyTestUrl),
            imported = imported,
            importRules = true
        )

        assertEquals(AppLanguage.ENGLISH, result.appLanguage)
        assertEquals(AppThemeStyle.LIQUID_GLASS, result.appThemeStyle)
        assertFalse(result.showNotificationSpeed)
        assertEquals(IpVersionMode.IPV6_ONLY, result.ipVersionMode)
        assertFalse(result.tunMtuAuto)
        assertEquals("login.example.com", result.fakeIpExcludeDomains)
        assertFalse(result.tcpKeepAliveEnabled)
        assertEquals(45, result.tcpKeepAliveInterval)
        assertEquals(12, result.subscriptionUpdateTimeout)
        assertFalse(result.autoCheckUpdate)
        assertEquals(BackgroundPowerSavingDelay.NEVER, result.backgroundPowerSavingDelay)
        assertEquals(65535, result.proxyPort)
        assertEquals(currentLatencyTestUrl, result.latencyTestUrl)
        assertEquals(20, result.latencyTestConcurrency)
        assertEquals(15, result.ruleSetAutoUpdateInterval)
    }

    @Test
    fun buildImportedSettingsPreservesCurrentRulesWhenRulesAreNotImported() {
        val currentRuleSets = listOf(RuleSet(tag = "current", type = RuleSetType.REMOTE))
        val currentRules = listOf(
            CustomRule(name = "current", type = RuleType.DOMAIN, value = "current.example")
        )
        val importedRuleSets = listOf(RuleSet(tag = "imported", type = RuleSetType.REMOTE))
        val importedRules = listOf(
            CustomRule(name = "imported", type = RuleType.DOMAIN, value = "imported.example")
        )

        val result = SettingsRepository.buildImportedSettings(
            current = AppSettings(
                customRules = currentRules,
                ruleSets = currentRuleSets,
                ruleSetAutoUpdateEnabled = true,
                ruleSetAutoUpdateInterval = 60
            ),
            imported = AppSettings(
                customRules = importedRules,
                ruleSets = importedRuleSets,
                ruleSetAutoUpdateEnabled = false,
                ruleSetAutoUpdateInterval = 15
            ),
            importRules = false
        )

        assertEquals(currentRules, result.customRules)
        assertEquals(currentRuleSets, result.ruleSets)
        assertEquals(true, result.ruleSetAutoUpdateEnabled)
        assertEquals(60, result.ruleSetAutoUpdateInterval)
    }

    @Test
    fun restartNotificationRequiresSuccessfulSettingsPersistence() {
        val source = File("src/main/java/com/kunk/singbox/repository/SettingsRepository.kt").readText()

        assertTrue(source.contains("private suspend fun updateSettingsAndNotifyRestart("))
        assertTrue(source.contains("if (persisted) notifyRestartRequired()"))
        assertTrue(source.contains("if (persisted && notifyRestartRequired)"))
        assertFalse(
            source.contains(
                "settingsStore.updateSettingsAndWait { it.copy(localDns = value) }\n" +
                    "        notifyRestartRequired()"
            )
        )
        assertFalse(
            source.contains(
                "settingsStore.updateSettingsAndWait { it.copy(ruleSets = value) }\n" +
                    "        if (notify)"
            )
        )
    }

    @Test
    fun packageAutoSyncLeavesRuntimeApplicationToReceiver() {
        val source = File("src/main/java/com/kunk/singbox/repository/SettingsRepository.kt")
            .readText(Charsets.UTF_8)
        val start = source.indexOf("suspend fun addPackageToCurrentPerAppRule")
        val body = source.substring(
            start,
            source.indexOf("suspend fun removePackageFromPerAppSettings", start)
        )

        assertTrue(body.contains("return persisted && changed"))
        assertFalse(body.contains("notifyRestartRequired()"))
    }

    @Test
    fun appRoutingWritesUseExclusiveAtomicUpserts() {
        val repository = File("src/main/java/com/kunk/singbox/repository/SettingsRepository.kt")
            .readText(Charsets.UTF_8)
        val viewModel = File("src/main/java/com/kunk/singbox/viewmodel/SettingsViewModel.kt")
            .readText(Charsets.UTF_8)

        assertTrue(repository.contains("updateSettingsAndNotifyRestart { upsertExclusiveAppRule(it, value) }"))
        assertTrue(repository.contains("updateSettingsAndNotifyRestart { upsertExclusiveAppGroup(it, value) }"))
        assertEquals(2, viewModel.windowed("repository.upsertAppRule(rule)".length).count {
            it == "repository.upsertAppRule(rule)"
        })
        assertEquals(2, viewModel.windowed("repository.upsertAppGroup(group)".length).count {
            it == "repository.upsertAppGroup(group)"
        })
    }
}

package com.kunk.singbox.repository.store

import com.google.gson.Gson
import com.kunk.singbox.database.entity.SettingsEntity
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.AppInfo
import com.kunk.singbox.model.AppRule
import com.kunk.singbox.model.RuleSetOutboundMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsStoreTest {

    @Test
    fun testMigrateSettingsReplacesLegacyLocalDnsAtVersionFour() {
        val migrated = SettingsStore.migrateSettings(
            version = 3,
            settings = AppSettings(localDns = AppSettings.LEGACY_LOCAL_DNS)
        )

        assertEquals(AppSettings.DEFAULT_LOCAL_DNS, migrated.localDns)
    }

    @Test
    fun testMigrateSettingsKeepsCustomLocalDns() {
        val customDns = "https://dns.google/dns-query"
        val migrated = SettingsStore.migrateSettings(
            version = 3,
            settings = AppSettings(localDns = customDns)
        )

        assertEquals(customDns, migrated.localDns)
    }

    @Test
    fun testMigrateSettingsReplacesOldVersionLocalDefaultWithDoh() {
        val migrated = SettingsStore.migrateSettings(
            version = 2,
            settings = AppSettings(localDns = "223.5.5.5")
        )

        assertEquals(AppSettings.DEFAULT_LOCAL_DNS, migrated.localDns)
    }

    @Test
    fun testMigrateSettingsNormalizesNullAppOutboundModesToProxy() {
        val migrated = SettingsStore.migrateSettings(
            version = 4,
            settings = AppSettings(
                appRules = listOf(
                    AppRule(
                        packageName = "com.example.x",
                        appName = "X",
                        outboundMode = null
                    )
                ),
                appGroups = listOf(
                    AppGroup(
                        name = "social",
                        apps = listOf(AppInfo(packageName = "com.example.y", appName = "Y")),
                        outboundMode = null
                    )
                )
            )
        )

        assertEquals(RuleSetOutboundMode.PROXY, migrated.appRules.single().outboundMode)
        assertEquals(RuleSetOutboundMode.PROXY, migrated.appGroups.single().outboundMode)
    }

    @Test
    fun testMigrateSettingsEnablesAutoRouteForLegacyTunSettings() {
        val migrated = SettingsStore.migrateSettings(
            version = 5,
            settings = AppSettings(autoRoute = false, strictRoute = true)
        )

        assertTrue(migrated.autoRoute)
        assertTrue(migrated.strictRoute)
    }

    @Test
    fun testDefaultFakeIpRangeIncludesIpv4AndIpv6Ranges() {
        assertEquals("198.18.0.0/15,fc00::/18", AppSettings().fakeIpRange)
    }

    @Test
    fun testMigrateSettingsAddsIpv6RangeToLegacyDefaultFakeIpRange() {
        val migrated = SettingsStore.migrateSettings(
            version = 6,
            settings = AppSettings(fakeIpRange = "198.18.0.0/15")
        )

        assertEquals("198.18.0.0/15,fc00::/18", migrated.fakeIpRange)
    }

    @Test
    fun testMigrateSettingsRecoversNullFakeIpRange() {
        val settings = Gson().fromJson("""{"fakeIpRange":null}""", AppSettings::class.java)

        val migrated = SettingsStore.migrateSettings(version = 6, settings = settings)

        assertEquals(AppSettings.DEFAULT_FAKE_IP_RANGE, migrated.fakeIpRange)
    }

    @Test
    fun testMigrateSettingsRecoversNullFakeIpRangeAtCurrentVersion() {
        val settings = Gson().fromJson("""{"fakeIpRange":null}""", AppSettings::class.java)

        val migrated = SettingsStore.migrateSettings(version = SettingsEntity.CURRENT_VERSION, settings = settings)

        assertEquals(AppSettings.DEFAULT_FAKE_IP_RANGE, migrated.fakeIpRange)
    }

    @Test
    fun testMigrateSettingsRecoversNullTrustedWifiSsids() {
        val settings = Gson().fromJson("""{"trustedWifiSsids":null}""", AppSettings::class.java)

        val migrated = SettingsStore.migrateSettings(version = 7, settings = settings)

        assertEquals("", migrated.trustedWifiSsids)
    }

    @Test
    fun testShouldPersistCurrentVersionWhenMigrationChangesSettings() {
        val loaded = Gson().fromJson("""{"fakeIpRange":null}""", AppSettings::class.java)
        val migrated = SettingsStore.migrateSettings(version = SettingsEntity.CURRENT_VERSION, settings = loaded)

        assertTrue(
            SettingsStore.shouldPersistMigratedSettings(
                version = SettingsEntity.CURRENT_VERSION,
                loaded = loaded,
                migrated = migrated
            )
        )
    }

    @Test
    fun testShouldNotPersistCurrentVersionWhenMigrationDoesNotChangeSettings() {
        val loaded = AppSettings()
        val migrated = SettingsStore.migrateSettings(version = SettingsEntity.CURRENT_VERSION, settings = loaded)

        assertFalse(
            SettingsStore.shouldPersistMigratedSettings(
                version = SettingsEntity.CURRENT_VERSION,
                loaded = loaded,
                migrated = migrated
            )
        )
    }

    @Test
    fun testFailedSettingsPersistenceRollsBackInMemorySettings() {
        val previous = AppSettings(localDns = "https://old.example/dns-query")
        val updated = previous.copy(localDns = "https://new.example/dns-query")

        val result = SettingsStore.resolveSettingsAfterPersistenceForTest(
            previous = previous,
            updated = updated,
            persisted = false
        )

        assertEquals(previous, result)
    }

    @Test
    fun testSuccessfulSettingsPersistenceKeepsUpdatedSettings() {
        val previous = AppSettings(localDns = "https://old.example/dns-query")
        val updated = previous.copy(localDns = "https://new.example/dns-query")

        val result = SettingsStore.resolveSettingsAfterPersistenceForTest(
            previous = previous,
            updated = updated,
            persisted = true
        )

        assertEquals(updated, result)
    }

    @Test
    fun settingsStoreDoesNotUseSynchronousSettingsDaoMethods() {
        val storeSource = File("src/main/java/com/kunk/singbox/repository/store/SettingsStore.kt").readText()
        val daoSource = File("src/main/java/com/kunk/singbox/database/dao/SettingsDao.kt").readText()

        assertTrue(storeSource.contains("runBlocking(Dispatchers.IO)"))
        assertTrue(storeSource.contains("private suspend fun loadSettings()"))
        assertTrue(storeSource.contains("suspend fun reload()"))
        assertTrue(storeSource.contains("settingsDao.getSettings()"))
        assertTrue(storeSource.contains("settingsDao.hasSettings()"))
        assertFalse(storeSource.contains("getSettingsSync"))
        assertFalse(storeSource.contains("saveSettingsSync"))
        assertFalse(storeSource.contains("hasSettingsSync"))
        assertFalse(daoSource.contains("getSettingsSync"))
        assertFalse(daoSource.contains("saveSettingsSync"))
        assertFalse(daoSource.contains("hasSettingsSync"))
    }
}

package com.kunk.singbox.repository.store

import com.google.gson.Gson
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.AppInfo
import com.kunk.singbox.model.AppRule
import com.kunk.singbox.model.RuleSetOutboundMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

        val migrated = SettingsStore.migrateSettings(version = 7, settings = settings)

        assertEquals(AppSettings.DEFAULT_FAKE_IP_RANGE, migrated.fakeIpRange)
    }

    @Test
    fun testShouldPersistCurrentVersionWhenMigrationChangesSettings() {
        val loaded = Gson().fromJson("""{"fakeIpRange":null}""", AppSettings::class.java)
        val migrated = SettingsStore.migrateSettings(version = 7, settings = loaded)

        assertTrue(SettingsStore.shouldPersistMigratedSettings(version = 7, loaded = loaded, migrated = migrated))
    }

    @Test
    fun testShouldNotPersistCurrentVersionWhenMigrationDoesNotChangeSettings() {
        val loaded = AppSettings()
        val migrated = SettingsStore.migrateSettings(version = 7, settings = loaded)

        assertFalse(SettingsStore.shouldPersistMigratedSettings(version = 7, loaded = loaded, migrated = migrated))
    }
}

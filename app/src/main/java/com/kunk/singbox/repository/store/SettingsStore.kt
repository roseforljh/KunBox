package com.kunk.singbox.repository.store

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kunk.singbox.database.AppDatabase
import com.kunk.singbox.database.entity.SettingsEntity
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.AppInfo
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.AppThemeStyle
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.repository.normalizeExclusiveAppAssignments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SettingsStore private constructor(context: Context) {
    companion object {
        private const val TAG = "SettingsStore"
        private const val AUTO_ROUTE_MIGRATION_VERSION = 6
        private const val FAKE_IP_RANGE_MIGRATION_VERSION = 7
        private const val NETWORK_AUTO_SWITCH_MIGRATION_VERSION = 8
        private const val APP_THEME_STYLE_MIGRATION_VERSION = 9
        private const val LOCAL_DNS_IP_DOH_MIGRATION_VERSION = 10
        private const val LEGACY_DEFAULT_FAKE_IP_RANGE = "198.18.0.0/15"

        @Volatile
        private var INSTANCE: SettingsStore? = null

        fun getInstance(context: Context): SettingsStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsStore(context.applicationContext).also { INSTANCE = it }
            }
        }

        @Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod")
        internal fun migrateSettings(version: Int, settings: AppSettings): AppSettings {
            var result = migrateEarlySettings(version, settings)
            result = migrateRoutingAndDnsSettings(version, result)
            result = migrateFakeIpRange(version, result)
            result = migrateNetworkAutoSwitch(version, result)
            result = migrateAppThemeStyle(version, result)
            result = migrateLocalDnsIpDoh(version, result)
            result = recoverLatencyTestUrl(result)
            val perAppPolicyRevision: Long? = runCatching { result.perAppPolicyRevision }.getOrNull()
            result = result.copy(perAppPolicyRevision = perAppPolicyRevision?.coerceAtLeast(0L) ?: 0L)
            return normalizeExclusiveAppAssignments(migrateLegacyAppRules(result))
        }

        private fun migrateEarlySettings(version: Int, settings: AppSettings): AppSettings {
            var result = settings
            if (version < 2) {
                result = result.copy(tunMtuAuto = true)
            }
            if (version < 3) {
                result = migrateLegacyDnsDefaults(result)
            }
            if (version < 4 && result.localDns.equals(AppSettings.LEGACY_LOCAL_DNS, ignoreCase = true)) {
                result = result.copy(localDns = AppSettings.DEFAULT_LOCAL_DNS)
                Log.i(TAG, "Migrating legacy localDns to '${AppSettings.DEFAULT_LOCAL_DNS}'")
            }
            return result
        }

        private fun migrateLegacyDnsDefaults(settings: AppSettings): AppSettings {
            val oldLocalDefaults = listOf(
                "https://dns.alidns.com/dns-query",
                "https://1.1.1.1/dns-query",
                "223.5.5.5",
                ""
            )
            val oldRemoteDefaults = listOf(
                "https://dns.google/dns-query",
                "https://1.1.1.1/dns-query",
                "8.8.8.8",
                "1.1.1.1",
                ""
            )

            var newLocal = settings.localDns
            var newRemote = settings.remoteDns

            if (settings.localDns in oldLocalDefaults) {
                newLocal = AppSettings.DEFAULT_LOCAL_DNS
                Log.i(TAG, "Migrating localDns from '${settings.localDns}' to '$newLocal'")
            }
            if (settings.remoteDns in oldRemoteDefaults) {
                newRemote = AppSettings.DEFAULT_REMOTE_DNS
                Log.i(TAG, "Migrating remoteDns from '${settings.remoteDns}' to '$newRemote'")
            }

            return settings.copy(localDns = newLocal, remoteDns = newRemote)
        }

        private fun migrateRoutingAndDnsSettings(version: Int, settings: AppSettings): AppSettings {
            var result = settings
            if (version < 5) {
                result = result.copy(
                    appRules = result.appRules.map { rule ->
                        if (rule.outboundMode != null) rule else rule.copy(outboundMode = RuleSetOutboundMode.PROXY)
                    },
                    appGroups = result.appGroups.map { group ->
                        if (group.outboundMode != null) group else group.copy(outboundMode = RuleSetOutboundMode.PROXY)
                    }
                )
            }
            if (version < AUTO_ROUTE_MIGRATION_VERSION && result.strictRoute && !result.autoRoute) {
                result = result.copy(autoRoute = true)
                Log.i(TAG, "Migrating legacy tun settings to enable autoRoute when strictRoute is enabled")
            }
            return result
        }

        private fun migrateLocalDnsIpDoh(version: Int, settings: AppSettings): AppSettings {
            if (
                version < LOCAL_DNS_IP_DOH_MIGRATION_VERSION &&
                settings.localDns.equals(AppSettings.LEGACY_DOMAIN_LOCAL_DNS, ignoreCase = true)
            ) {
                Log.i(TAG, "Migrating domain localDns to '${AppSettings.DEFAULT_LOCAL_DNS}'")
                return settings.copy(localDns = AppSettings.DEFAULT_LOCAL_DNS)
            }
            return settings
        }

        private fun migrateLegacyAppRules(settings: AppSettings): AppSettings {
            if (settings.appRules.isEmpty()) return settings

            val assignedPackages = settings.appGroups
                .flatMapTo(mutableSetOf()) { group -> group.apps.map { it.packageName.trim() } }
            val migratedGroups = settings.appRules
                .asReversed()
                .mapNotNull { rule ->
                    val packageName = rule.packageName.trim()
                    if (packageName.isEmpty() || !assignedPackages.add(packageName)) return@mapNotNull null
                    val appName = rule.appName.ifBlank { packageName }
                    AppGroup(
                        id = "legacy-rule-${rule.id}",
                        name = appName,
                        apps = listOf(AppInfo(packageName = packageName, appName = appName)),
                        outboundMode = rule.outboundMode ?: RuleSetOutboundMode.PROXY,
                        outboundValue = rule.outboundValue,
                        enabled = rule.enabled
                    )
                }
                .asReversed()

            Log.i(TAG, "Migrated ${settings.appRules.size} legacy app rules into app groups")
            return settings.copy(appRules = emptyList(), appGroups = migratedGroups + settings.appGroups)
        }

        private fun recoverLatencyTestUrl(settings: AppSettings): AppSettings {
            val latencyTestUrl = AppSettings.validateLatencyTestUrl(settings.latencyTestUrl)
            if (latencyTestUrl == null) {
                Log.w(TAG, "Recovering invalid latency test URL to the official sing-box default")
            }
            return settings.copy(latencyTestUrl = latencyTestUrl ?: AppSettings.DEFAULT_LATENCY_TEST_URL)
        }

        private fun migrateFakeIpRange(version: Int, settings: AppSettings): AppSettings {
            val fakeIpRange = settings.fakeIpRange.orEmpty().trim()
            if (fakeIpRange.isEmpty()) {
                Log.i(TAG, "Recovering empty fakeIpRange to '${AppSettings.DEFAULT_FAKE_IP_RANGE}'")
                return settings.copy(fakeIpRange = AppSettings.DEFAULT_FAKE_IP_RANGE)
            }
            if (
                version >= FAKE_IP_RANGE_MIGRATION_VERSION ||
                fakeIpRange != LEGACY_DEFAULT_FAKE_IP_RANGE
            ) {
                return settings
            }

            Log.i(TAG, "Migrating legacy fakeIpRange to '${AppSettings.DEFAULT_FAKE_IP_RANGE}'")
            return settings.copy(fakeIpRange = AppSettings.DEFAULT_FAKE_IP_RANGE)
        }

        private fun migrateNetworkAutoSwitch(version: Int, settings: AppSettings): AppSettings {
            val trustedWifiSsids: String? = runCatching { settings.trustedWifiSsids }.getOrNull()
            if (version >= NETWORK_AUTO_SWITCH_MIGRATION_VERSION && trustedWifiSsids != null) {
                return settings
            }

            return settings.copy(trustedWifiSsids = trustedWifiSsids.orEmpty())
        }

        private fun migrateAppThemeStyle(version: Int, settings: AppSettings): AppSettings {
            val appThemeStyle: AppThemeStyle? = runCatching { settings.appThemeStyle }.getOrNull()
            if (version >= APP_THEME_STYLE_MIGRATION_VERSION && appThemeStyle != null) {
                return settings
            }

            return settings.copy(appThemeStyle = appThemeStyle ?: AppThemeStyle.DEFAULT)
        }

        internal fun shouldPersistMigratedSettings(
            version: Int,
            loaded: AppSettings,
            migrated: AppSettings
        ): Boolean {
            return version != SettingsEntity.CURRENT_VERSION || migrated != loaded
        }

        internal fun resolveSettingsAfterPersistence(
            previous: AppSettings,
            updated: AppSettings,
            persisted: Boolean
        ): AppSettings {
            return if (persisted) updated else previous
        }
    }

    private val database = AppDatabase.getInstance(context)
    private val settingsDao = database.settingsDao()

    private val gson: Gson = GsonBuilder()
        .serializeNulls()
        .create()

    private val writeMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        runBlocking(Dispatchers.IO) {
            loadSettings()
        }
    }

    @Suppress("NestedBlockDepth")
    private suspend fun loadSettings() {
        try {
            val startTime = System.currentTimeMillis()

            val entity = settingsDao.getSettings()
            if (entity != null) {
                val loaded = gson.fromJson(entity.data, AppSettings::class.java)
                if (loaded != null) {
                    val migrated = migrateSettings(entity.version, loaded)
                    _settings.value = migrated
                    if (shouldPersistMigratedSettings(entity.version, loaded, migrated)) {
                        saveSettingsInternal(migrated)
                    }
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i(TAG, "Settings loaded from Room in ${elapsed}ms")
                    return
                }
            }

            Log.i(TAG, "No existing settings, using defaults")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load settings", e)
        }
    }

    fun updateSettings(update: (AppSettings) -> AppSettings) {
        scope.launch {
            updateSettingsLocked(update)
        }
    }

    suspend fun updateSettingsAndWait(update: (AppSettings) -> AppSettings): Boolean {
        return updateSettingsLocked(update)
    }

    private suspend fun updateSettingsLocked(update: (AppSettings) -> AppSettings): Boolean {
        return writeMutex.withLock {
            val previousSettings = _settings.value
            val newSettings = normalizeExclusiveAppAssignments(migrateLegacyAppRules(update(previousSettings)))
            _settings.value = newSettings
            val persisted = saveSettingsLocked(newSettings)
            _settings.value = resolveSettingsAfterPersistence(previousSettings, newSettings, persisted)
            persisted
        }
    }

    private suspend fun saveSettingsInternal(settings: AppSettings) {
        writeMutex.withLock {
            saveSettingsLocked(settings)
        }
    }

    private suspend fun saveSettingsLocked(settings: AppSettings): Boolean {
        try {
            val startTime = System.currentTimeMillis()
            val json = gson.toJson(settings)
            val entity = SettingsEntity(
                id = 1,
                version = SettingsEntity.CURRENT_VERSION,
                data = json,
                updatedAt = System.currentTimeMillis()
            )
            settingsDao.saveSettings(entity)
            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Settings saved to Room in ${elapsed}ms")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save settings", e)
            return false
        }
    }

    fun getCurrentSettings(): AppSettings = _settings.value

    suspend fun reload() {
        withContext(Dispatchers.IO) {
            loadSettings()
        }
    }

    suspend fun hasSettings(): Boolean = withContext(Dispatchers.IO) {
        settingsDao.hasSettings()
    }

    suspend fun resetSettings() {
        writeMutex.withLock {
            try {
                settingsDao.deleteSettings()
                _settings.value = AppSettings()
                Log.i(TAG, "Settings reset to defaults")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reset settings", e)
            }
        }
    }
}

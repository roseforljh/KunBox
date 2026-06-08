package com.kunk.singbox.repository.store

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kunk.singbox.database.AppDatabase
import com.kunk.singbox.database.entity.SettingsEntity
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.RuleSetOutboundMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 *
 */
class SettingsStore private constructor(context: Context) {
    companion object {
        private const val TAG = "SettingsStore"
        private const val AUTO_ROUTE_MIGRATION_VERSION = 6
        private const val FAKE_IP_RANGE_MIGRATION_VERSION = 7
        private const val LEGACY_DEFAULT_FAKE_IP_RANGE = "198.18.0.0/15"

        @Volatile
        private var INSTANCE: SettingsStore? = null

        fun getInstance(context: Context): SettingsStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsStore(context.applicationContext).also { INSTANCE = it }
            }
        }

        @Suppress("CognitiveComplexMethod")
        internal fun migrateSettings(version: Int, settings: AppSettings): AppSettings {
            var result = settings

            if (version < 2) {
                result = result.copy(tunMtuAuto = true)
            }

            if (version < 3) {
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

                var newLocal = result.localDns
                var newRemote = result.remoteDns

                if (result.localDns in oldLocalDefaults) {
                    newLocal = AppSettings.DEFAULT_LOCAL_DNS
                    Log.i(TAG, "Migrating localDns from '${result.localDns}' to '$newLocal'")
                }
                if (result.remoteDns in oldRemoteDefaults) {
                    newRemote = AppSettings.DEFAULT_REMOTE_DNS
                    Log.i(TAG, "Migrating remoteDns from '${result.remoteDns}' to '$newRemote'")
                }

                result = result.copy(localDns = newLocal, remoteDns = newRemote)
            }

            if (version < 4 && result.localDns.equals(AppSettings.LEGACY_LOCAL_DNS, ignoreCase = true)) {
                result = result.copy(localDns = AppSettings.DEFAULT_LOCAL_DNS)
                Log.i(TAG, "Migrating legacy localDns to '${AppSettings.DEFAULT_LOCAL_DNS}'")
            }

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

            result = migrateFakeIpRange(version, result)

            return result
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

        internal fun shouldPersistMigratedSettings(
            version: Int,
            loaded: AppSettings,
            migrated: AppSettings
        ): Boolean {
            return version != SettingsEntity.CURRENT_VERSION || migrated != loaded
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
        loadSettings()
    }

    @Suppress("NestedBlockDepth")
    private fun loadSettings() {
        try {
            val startTime = System.currentTimeMillis()

            val entity = settingsDao.getSettingsSync()
            if (entity != null) {
                val loaded = gson.fromJson(entity.data, AppSettings::class.java)
                if (loaded != null) {
                    val migrated = migrateSettings(entity.version, loaded)
                    _settings.value = migrated
                    if (shouldPersistMigratedSettings(entity.version, loaded, migrated)) {
                        scope.launch {
                            saveSettingsInternal(migrated)
                        }
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

    /**
     */
    fun updateSettings(update: (AppSettings) -> AppSettings) {
        val newSettings = update(_settings.value)
        _settings.value = newSettings

        scope.launch {
            saveSettingsInternal(newSettings)
        }
    }

    /**
     */
    suspend fun updateSettingsAndWait(update: (AppSettings) -> AppSettings) {
        val newSettings = update(_settings.value)
        _settings.value = newSettings
        saveSettingsInternal(newSettings)
    }

    private suspend fun saveSettingsInternal(settings: AppSettings) {
        writeMutex.withLock {
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
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save settings", e)
            }
        }
    }

    /**
     */
    private fun saveSettingsSync(settings: AppSettings) {
        try {
            val json = gson.toJson(settings)
            val entity = SettingsEntity(
                id = 1,
                version = SettingsEntity.CURRENT_VERSION,
                data = json,
                updatedAt = System.currentTimeMillis()
            )
            settingsDao.saveSettingsSync(entity)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save settings sync", e)
        }
    }

    /**
     */
    fun getCurrentSettings(): AppSettings = _settings.value

    /**
     */
    fun reload() {
        loadSettings()
    }

    fun hasSettings(): Boolean = settingsDao.hasSettingsSync()

    /**
     */
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

package com.kunk.singbox.repository.store

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.kunk.singbox.database.AppDatabase
import com.kunk.singbox.database.entity.SettingsEntity
import com.kunk.singbox.model.AppSettings
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
 * 设置存储 - 使用 Room 数据库存储
 *
 * 相比 NekoBox 的 KeyValuePair 方案:
 * - 单次读写整个设置对象 vs N 次键值对操作
 * - JSON 序列化类型安全 vs 字符串手动转换
 * - Flow 实时观察 vs 手动刷新
 * - 内置版本控制支持迁移 vs 无版本
 *
 * 优势:
 * - Room 数据库在重装后保留
 * - 事务支持保证数据一致性
 */
class SettingsStore private constructor(context: Context) {
    companion object {
        private const val TAG = "SettingsStore"

        @Volatile
        private var INSTANCE: SettingsStore? = null

        fun getInstance(context: Context): SettingsStore {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsStore(context.applicationContext).also { INSTANCE = it }
            }
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

    private fun loadSettings() {
        try {
            val startTime = System.currentTimeMillis()

            // 从 Room 加载设置
            val entity = settingsDao.getSettingsSync()
            if (entity != null) {
                val loaded = gson.fromJson(entity.data, AppSettings::class.java)
                if (loaded != null) {
                    _settings.value = loaded
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.i(TAG, "Settings loaded from Room in ${elapsed}ms")
                    return
                }
            }

            // 使用默认设置
            Log.i(TAG, "No existing settings, using defaults")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load settings", e)
        }
    }

    /**
     * 更新设置 - 同步更新内存，异步保存到数据库
     */
    fun updateSettings(update: (AppSettings) -> AppSettings) {
        val newSettings = update(_settings.value)
        _settings.value = newSettings

        // 异步保存
        scope.launch {
            saveSettingsInternal(newSettings)
        }
    }

    /**
     * 更新设置并等待保存完成
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
     * 同步保存设置 (仅用于迁移)
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
     * 获取当前设置快照
     */
    fun getCurrentSettings(): AppSettings = _settings.value

    /**
     * 强制重新加载设置
     */
    fun reload() {
        loadSettings()
    }

    /**
     * 检查是否有设置数据
     */
    fun hasSettings(): Boolean = settingsDao.hasSettingsSync()

    /**
     * 重置设置 (恢复默认)
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

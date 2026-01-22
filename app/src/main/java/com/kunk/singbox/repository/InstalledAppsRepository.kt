package com.kunk.singbox.repository

import com.kunk.singbox.R
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.kunk.singbox.model.InstalledApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 已安装应用的 Repository，单例模式
 * 负责加载和缓存已安装应用列表，提供进度回调
 */
class InstalledAppsRepository private constructor(private val context: Context) {

    /**
     * 加载状态
     */
    sealed class LoadingState {
        /** 空闲状态，尚未加载 */
        object Idle : LoadingState()

        /** 加载中 */
        data class Loading(
            val progress: Float,
            val current: Int,
            val total: Int
        ) : LoadingState()

        /** 加载完成 */
        object Loaded : LoadingState()

        /** 加载出错 */
        data class Error(val message: String) : LoadingState()
    }

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Idle)
    val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()

    /**
     * 加载已安装应用列表
     * 如果已经加载过或正在加载，则直接返回
     */
    suspend fun loadApps() {
        // 如果已加载，直接返回
        if (_loadingState.value is LoadingState.Loaded) return
        // 如果正在加载，直接返回
        if (_loadingState.value is LoadingState.Loading) return

        try {
            withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.packageName != context.packageName }

                val total = allApps.size
                val result = mutableListOf<InstalledApp>()

                // 初始化加载状态
                _loadingState.value = LoadingState.Loading(
                    progress = 0f,
                    current = 0,
                    total = total
                )

                // 性能优化: 批量更新进度，每 20 个应用更新一次，减少 recomposition 次数
                val batchSize = 20
                allApps.forEachIndexed { index, app ->
                    // 加载应用信息
                    val appName = try {
                        app.loadLabel(pm).toString()
                    } catch (e: Exception) {
                        app.packageName
                    }

                    result.add(
                        InstalledApp(
                            packageName = app.packageName,
                            appName = appName,
                            isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    )

                    // 批量更新进度：每 batchSize 个应用或最后一个应用时更新
                    if ((index + 1) % batchSize == 0 || index == total - 1) {
                        _loadingState.value = LoadingState.Loading(
                            progress = (index + 1).toFloat() / total,
                            current = index + 1,
                            total = total
                        )
                    }
                }

                // 排序并保存结果
                _installedApps.value = result.sortedBy { it.appName.lowercase() }
                _loadingState.value = LoadingState.Loaded
            }
        } catch (e: Exception) {
            _loadingState.value = LoadingState.Error(e.message ?: context.getString(R.string.common_loading)) // TODO: Better error string
        }
    }

    /**
     * 强制重新加载应用列表
     */
    suspend fun reloadApps() {
        _loadingState.value = LoadingState.Idle
        _installedApps.value = emptyList()
        loadApps()
    }

    /**
     * 检查是否需要加载
     */
    fun needsLoading(): Boolean {
        return _loadingState.value is LoadingState.Idle
    }

    /**
     * 检查是否已加载完成
     */
    fun isLoaded(): Boolean {
        return _loadingState.value is LoadingState.Loaded
    }

    companion object {
        @Volatile
        private var instance: InstalledAppsRepository? = null

        fun getInstance(context: Context): InstalledAppsRepository {
            return instance ?: synchronized(this) {
                instance ?: InstalledAppsRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}

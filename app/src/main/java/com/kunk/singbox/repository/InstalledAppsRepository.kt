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
 */
class InstalledAppsRepository private constructor(private val context: Context) {

    /**
     */
    sealed class LoadingState {
        object Idle : LoadingState()

        data class Loading(
            val progress: Float,
            val current: Int,
            val total: Int
        ) : LoadingState()

        object Loaded : LoadingState()

        data class Error(val message: String) : LoadingState()
    }

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Idle)
    val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()

    /**
     */
    suspend fun loadApps() {

        if (_loadingState.value is LoadingState.Loaded) return

        if (_loadingState.value is LoadingState.Loading) return

        try {
            withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.packageName != context.packageName }

                val total = allApps.size
                val result = mutableListOf<InstalledApp>()

                _loadingState.value = LoadingState.Loading(
                    progress = 0f,
                    current = 0,
                    total = total
                )

                val batchSize = 20
                allApps.forEachIndexed { index, app ->
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

                    if ((index + 1) % batchSize == 0 || index == total - 1) {
                        _loadingState.value = LoadingState.Loading(
                            progress = (index + 1).toFloat() / total,
                            current = index + 1,
                            total = total
                        )
                    }
                }

                _installedApps.value = result.sortedBy { it.appName.lowercase() }
                _loadingState.value = LoadingState.Loaded
            }
        } catch (e: Exception) {
            _loadingState.value = LoadingState.Error(e.message ?: context.getString(R.string.common_loading)) // TODO: Better error string
        }
    }

    /**
     */
    suspend fun reloadApps() {
        _loadingState.value = LoadingState.Idle
        _installedApps.value = emptyList()
        loadApps()
    }

    suspend fun refreshApps() {
        _loadingState.value = LoadingState.Idle
        loadApps()
    }

    fun needsLoading(): Boolean {
        return _loadingState.value is LoadingState.Idle
    }

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

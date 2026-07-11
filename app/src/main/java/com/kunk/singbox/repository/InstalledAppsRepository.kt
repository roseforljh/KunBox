package com.kunk.singbox.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import com.kunk.singbox.R
import com.kunk.singbox.model.InstalledAppUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class InstalledAppsRepository private constructor(private val context: Context) {

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

    private val _appItems = MutableStateFlow<List<InstalledAppUi>>(emptyList())
    val appItems: StateFlow<List<InstalledAppUi>> = _appItems.asStateFlow()

    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Idle)
    val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()

    private val loadMutex = Mutex()
    private val iconCache = object : LruCache<String, Bitmap>(ICON_CACHE_MAX_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    suspend fun loadApps() {
        loadMutex.withLock {
            loadAppsLocked(force = false, clearBeforeLoad = false)
        }
    }

    private suspend fun loadAppsLocked(force: Boolean, clearBeforeLoad: Boolean) {
        if (!prepareForLoad(force, clearBeforeLoad)) return

        try {
            withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val allApps = pm.getInstalledApplications(0)
                    .filter { it.packageName != context.packageName }
                val launcherPackages = queryLauncherPackages(pm)

                val total = allApps.size
                val result = mutableListOf<InstalledAppUi>()

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
                        InstalledAppUi(
                            packageName = app.packageName,
                            appName = appName,
                            isSystemApp = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                            hasLauncher = app.packageName in launcherPackages
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

                val sortedItems = result.sortedBy { it.appName.lowercase() }
                _appItems.value = sortedItems
                _loadingState.value = LoadingState.Loaded
            }
        } catch (e: CancellationException) {
            _loadingState.value = LoadingState.Idle
            throw e
        } catch (e: Exception) {
            _loadingState.value = LoadingState.Error(
                e.message ?: context.getString(R.string.common_loading)
            )
        }
    }

    suspend fun loadIcon(packageName: String): Bitmap? {
        if (packageName.isBlank()) return null
        iconCache.get(packageName)?.let { return it }

        return withContext(Dispatchers.IO) {
            iconCache.get(packageName) ?: try {
                context.packageManager
                    .getApplicationIcon(packageName)
                    .toBitmap(ICON_SIZE_PX, ICON_SIZE_PX)
                    .also { iconCache.put(packageName, it) }
            } catch (_: Exception) {
                null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun queryLauncherPackages(packageManager: PackageManager): Set<String> {
        return sequenceOf(Intent.CATEGORY_INFO, Intent.CATEGORY_LAUNCHER)
            .flatMap { category ->
                packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(category),
                    0
                ).asSequence()
            }
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    private fun prepareForLoad(force: Boolean, clearBeforeLoad: Boolean): Boolean {
        if (force) {
            _loadingState.value = LoadingState.Idle
            iconCache.evictAll()
            if (clearBeforeLoad) {
                _appItems.value = emptyList()
            }
            return true
        }

        if (_loadingState.value is LoadingState.Loaded) return false

        if (_loadingState.value is LoadingState.Loading) return false

        return true
    }

    suspend fun reloadApps() {
        loadMutex.withLock {
            loadAppsLocked(force = true, clearBeforeLoad = true)
        }
    }

    fun needsLoading(): Boolean {
        return when (_loadingState.value) {
            LoadingState.Idle, is LoadingState.Error -> true
            is LoadingState.Loading, LoadingState.Loaded -> false
        }
    }

    companion object {
        private const val ICON_SIZE_PX = 160
        private const val ICON_CACHE_MAX_KB = 8 * 1024

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

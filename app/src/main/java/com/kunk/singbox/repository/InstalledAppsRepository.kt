package com.kunk.singbox.repository

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kunk.singbox.R
import com.kunk.singbox.model.InstalledAppUi
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val INSTALLED_APPS_SNAPSHOT_SCHEMA = 1

internal fun parseInstalledAppsSnapshot(
    json: String,
    expectedUserId: Int
): List<InstalledAppUi>? = runCatching {
    val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
        ?: return@runCatching null
    if (root["schema"]?.asInt != INSTALLED_APPS_SNAPSHOT_SCHEMA || root["userId"]?.asInt != expectedUserId) {
        return@runCatching null
    }
    val apps = root["apps"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return@runCatching null
    apps.mapNotNull(::parseInstalledApp)
}.getOrNull()

private fun parseInstalledApp(element: JsonElement): InstalledAppUi? {
    if (!element.isJsonObject) return null
    val appObject = element.asJsonObject
    val packageName = appObject["packageName"]?.asString.orEmpty().trim()
    if (packageName.isBlank()) return null
    return InstalledAppUi(
        packageName = packageName,
        appName = appObject["appName"]?.asString.orEmpty().ifBlank { packageName },
        isSystemApp = appObject["isSystemApp"]?.asBoolean ?: false,
        hasLauncher = appObject["hasLauncher"]?.asBoolean ?: false,
        uid = appObject["uid"]?.asInt ?: -1
    )
}

internal fun serializeInstalledAppsSnapshot(
    apps: List<InstalledAppUi>,
    userId: Int,
    localeTag: String
): String = JsonObject().apply {
    addProperty("schema", INSTALLED_APPS_SNAPSHOT_SCHEMA)
    addProperty("userId", userId)
    addProperty("localeTag", localeTag)
    add(
        "apps",
        JsonArray().apply {
            apps.forEach { app ->
                add(
                    JsonObject().apply {
                        addProperty("packageName", app.packageName)
                        addProperty("appName", app.appName)
                        addProperty("isSystemApp", app.isSystemApp)
                        addProperty("hasLauncher", app.hasLauncher)
                        addProperty("uid", app.uid)
                    }
                )
            }
        }
    )
}.toString()

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
    private val snapshotFile = File(context.noBackupFilesDir, SNAPSHOT_FILE_NAME)
    private val iconLoadDispatcher = Dispatchers.IO.limitedParallelism(4)
    private val iconCache = object : LruCache<String, Bitmap>(ICON_CACHE_MAX_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.allocationByteCount / 1024).coerceAtLeast(1)
        }
    }

    suspend fun loadApps() {
        loadMutex.withLock {
            loadAppsLocked(force = false)
        }
    }

    @Suppress("LongMethod")
    private suspend fun loadAppsLocked(force: Boolean) {
        if (!prepareForLoad(force)) return

        try {
            withContext(Dispatchers.IO) {
                val startedAt = android.os.SystemClock.elapsedRealtime()
                if (_appItems.value.isEmpty()) {
                    readSnapshot()?.let { cached ->
                        _appItems.value = cached
                        android.util.Log.i(TAG, "[APP_INVENTORY] snapshot_loaded count=${cached.size}")
                    }
                }
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

                val batchSize = 40
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
                            hasLauncher = app.packageName in launcherPackages,
                            uid = app.uid
                        )
                    )

                    if ((index + 1) % batchSize == 0 || index == total - 1) {
                        _appItems.value = mergeScannedApps(_appItems.value, result, complete = false)
                        _loadingState.value = LoadingState.Loading(
                            progress = (index + 1).toFloat() / total,
                            current = index + 1,
                            total = total
                        )
                    }
                }

                val sortedItems = result.sortedBy { it.appName.lowercase() }
                _appItems.value = sortedItems
                writeSnapshot(sortedItems)
                _loadingState.value = LoadingState.Loaded
                android.util.Log.i(
                    TAG,
                    "[APP_INVENTORY] scan_complete count=$total " +
                        "duration_ms=${android.os.SystemClock.elapsedRealtime() - startedAt}"
                )
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

        return withContext(iconLoadDispatcher) {
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

    suspend fun loadIcons(packageNames: Collection<String>): Map<String, Bitmap> = coroutineScope {
        normalizeIconPackages(packageNames)
            .map { packageName ->
                async {
                    loadIcon(packageName)?.let { packageName to it }
                }
            }
            .awaitAll()
            .filterNotNull()
            .toMap()
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

    private fun prepareForLoad(force: Boolean): Boolean {
        if (force) {
            _loadingState.value = LoadingState.Idle
            return true
        }

        if (_loadingState.value is LoadingState.Loaded) return false

        if (_loadingState.value is LoadingState.Loading) return false

        return true
    }

    suspend fun reloadApps() {
        loadMutex.withLock {
            loadAppsLocked(force = true)
        }
    }

    private fun readSnapshot(): List<InstalledAppUi>? = runCatching {
        val json = snapshotFile.takeIf(File::isFile)?.readText(Charsets.UTF_8)
            ?: return@runCatching null
        parseInstalledAppsSnapshot(json, currentUserId())
    }.onFailure { error ->
        android.util.Log.w(TAG, "[APP_INVENTORY] snapshot_read_failed", error)
    }.getOrNull()

    private fun writeSnapshot(apps: List<InstalledAppUi>) {
        runCatching {
            ConfigRepository.writeTextFileAtomically(
                snapshotFile,
                serializeInstalledAppsSnapshot(
                    apps = apps,
                    userId = currentUserId(),
                    localeTag = context.resources.configuration.locales[0].toLanguageTag()
                )
            )
        }.onFailure { error ->
            android.util.Log.w(TAG, "[APP_INVENTORY] snapshot_write_failed", error)
        }
    }

    private fun currentUserId(): Int = context.applicationInfo.uid / 100_000

    fun needsLoading(): Boolean {
        return when (_loadingState.value) {
            LoadingState.Idle, is LoadingState.Error -> true
            is LoadingState.Loading, LoadingState.Loaded -> false
        }
    }

    companion object {
        private const val TAG = "InstalledAppsRepository"
        private const val ICON_SIZE_PX = 160
        private const val ICON_CACHE_MAX_KB = 8 * 1024
        private const val SNAPSHOT_FILE_NAME = "installed_apps_snapshot.json"

        internal fun mergeScannedApps(
            cached: List<InstalledAppUi>,
            scanned: List<InstalledAppUi>,
            complete: Boolean
        ): List<InstalledAppUi> {
            if (complete) return scanned.sortedBy { it.appName.lowercase() }
            val merged = cached.associateByTo(linkedMapOf(), InstalledAppUi::packageName)
            scanned.forEach { merged[it.packageName] = it }
            return merged.values.sortedBy { it.appName.lowercase() }
        }

        internal fun normalizeIconPackages(packageNames: Collection<String>): List<String> = packageNames
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .toList()

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

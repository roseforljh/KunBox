package com.kunk.singbox.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kunk.singbox.database.AppDatabase
import com.kunk.singbox.database.dao.ActiveStateDao
import com.kunk.singbox.database.dao.NodeLatencyDao
import com.kunk.singbox.database.dao.ProfileDao
import com.kunk.singbox.database.entity.ActiveStateEntity
import com.kunk.singbox.database.entity.NodeLatencyEntity
import com.kunk.singbox.database.entity.ProfileEntity
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.model.SavedProfilesData
import com.kunk.singbox.model.UpdateStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 *
 *
 */
class ProfilePersistence(private val context: Context) {

    companion object {
        private const val TAG = "ProfilePersistence"
        private const val SAVE_DEBOUNCE_MS = 300L

        private val TYPE_SAVED_PROFILES_DATA = object : TypeToken<SavedProfilesData>() {}.type

        @Volatile
        private var instance: ProfilePersistence? = null

        fun getInstance(context: Context): ProfilePersistence {
            return instance ?: synchronized(this) {
                instance ?: ProfilePersistence(context.applicationContext).also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private val database = AppDatabase.getInstance(context)
    private val profileDao: ProfileDao = database.profileDao()
    private val activeStateDao: ActiveStateDao = database.activeStateDao()
    private val nodeLatencyDao: NodeLatencyDao = database.nodeLatencyDao()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var saveJob: Job? = null

    private val profilesFileJson: File
        get() = File(context.filesDir, "profiles.json")

    /**
     */
    data class LoadResult(
        val profiles: List<ProfileUi>,
        val activeProfileId: String?,
        val activeNodeId: String?,
        val nodeLatencies: Map<String, Long>
    )

    /**
     *
     */
    fun loadSync(): LoadResult {
        val startTime = System.currentTimeMillis()

        val profileEntities = profileDao.getAllSync()
        val activeState = activeStateDao.getSync()
        val latencyEntities = nodeLatencyDao.getAllSync()

        if (profileEntities.isNotEmpty()) {
            val profiles = profileEntities.map { it.toUiModel().copy(updateStatus = UpdateStatus.Idle) }
            val latencies = latencyEntities.associate { it.nodeId to it.latencyMs }
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Loaded ${profiles.size} profiles from Room in ${elapsed}ms")

            cleanupLegacyFiles()

            return LoadResult(
                profiles = profiles,
                activeProfileId = activeState?.activeProfileId,
                activeNodeId = activeState?.activeNodeId,
                nodeLatencies = latencies
            )
        }

        val savedData = tryLoadFromJson()
        if (savedData != null) {
            migrateToRoom(savedData)
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "Migrated ${savedData.profiles.size} profiles to Room in ${elapsed}ms")

            return LoadResult(
                profiles = savedData.profiles.map { it.copy(updateStatus = UpdateStatus.Idle) },
                activeProfileId = savedData.activeProfileId,
                activeNodeId = savedData.activeNodeId,
                nodeLatencies = savedData.nodeLatencies
            )
        }

        return LoadResult(
            profiles = emptyList(),
            activeProfileId = null,
            activeNodeId = null,
            nodeLatencies = emptyMap()
        )
    }

    private fun tryLoadFromJson(): SavedProfilesData? {
        if (!profilesFileJson.exists()) return null
        return try {
            Log.i(TAG, "Migrating profiles from JSON to Room...")
            val json = profilesFileJson.readText()
            gson.fromJson<SavedProfilesData>(json, TYPE_SAVED_PROFILES_DATA)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load from JSON", e)
            null
        }
    }

    private fun migrateToRoom(savedData: SavedProfilesData) {
        try {
            val entities = savedData.profiles.mapIndexed { index, profile ->
                ProfileEntity.fromUiModel(profile, sortOrder = index)
            }
            profileDao.insertAllSync(entities)

            if (savedData.activeProfileId != null || savedData.activeNodeId != null) {
                activeStateDao.saveSync(
                    ActiveStateEntity(
                        id = 1,
                        activeProfileId = savedData.activeProfileId,
                        activeNodeId = savedData.activeNodeId
                    )
                )
            }

            if (savedData.nodeLatencies.isNotEmpty()) {
                val latencies = savedData.nodeLatencies.map { (nodeId, latency) ->
                    NodeLatencyEntity(nodeId = nodeId, latencyMs = latency)
                }
                scope.launch { nodeLatencyDao.insertAll(latencies) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to migrate to Room", e)
        }
    }

    /**
     *
     */
    fun save(
        profiles: List<ProfileUi>,
        activeProfileId: String?,
        activeNodeId: String?,
        nodeLatencies: Map<String, Long>
    ) {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            saveInternal(profiles, activeProfileId, activeNodeId, nodeLatencies)
        }
    }

    /**
     *
     */
    fun saveImmediate(
        profiles: List<ProfileUi>,
        activeProfileId: String?,
        activeNodeId: String?,
        nodeLatencies: Map<String, Long>
    ) {
        saveJob?.cancel()
        scope.launch {
            saveInternal(profiles, activeProfileId, activeNodeId, nodeLatencies)
        }
    }

    /**
     */
    fun saveActiveStateSync(activeProfileId: String?, activeNodeId: String?) {
        try {
            activeStateDao.saveSync(
                ActiveStateEntity(
                    id = 1,
                    activeProfileId = activeProfileId,
                    activeNodeId = activeNodeId
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save active state", e)
        }
    }

    private suspend fun saveInternal(
        profiles: List<ProfileUi>,
        activeProfileId: String?,
        activeNodeId: String?,
        nodeLatencies: Map<String, Long>
    ) {
        val startTime = System.currentTimeMillis()
        try {
            activeStateDao.saveSync(
                ActiveStateEntity(
                    id = 1,
                    activeProfileId = activeProfileId,
                    activeNodeId = activeNodeId
                )
            )

            val entities = profiles.mapIndexed { index, profile ->
                ProfileEntity.fromUiModel(profile, sortOrder = index)
            }
            profileDao.insertAll(entities)

            if (nodeLatencies.isNotEmpty()) {
                val latencyEntities = nodeLatencies.map { (nodeId, latency) ->
                    NodeLatencyEntity(nodeId = nodeId, latencyMs = latency)
                }
                nodeLatencyDao.insertAll(latencyEntities)
            }

            val elapsed = System.currentTimeMillis() - startTime
            Log.d(TAG, "Saved ${profiles.size} profiles in ${elapsed}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save profiles", e)
        }
    }

    /**
     */
    fun saveNodeLatency(nodeId: String, latencyMs: Long) {
        scope.launch {
            try {
                nodeLatencyDao.upsert(nodeId, latencyMs)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist latency for $nodeId", e)
            }
        }
    }

    /**
     *
     */
    suspend fun deleteProfile(profileId: String) {
        try {
            profileDao.deleteById(profileId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete profile $profileId", e)
        }
    }

    private fun cleanupLegacyFiles() {
        scope.launch {
            try {
                if (profilesFileJson.exists()) {
                    profilesFileJson.delete()
                    Log.i(TAG, "Deleted legacy JSON profiles file")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cleanup legacy profile files", e)
            }
        }
    }
}

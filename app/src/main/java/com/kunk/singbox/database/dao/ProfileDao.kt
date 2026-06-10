package com.kunk.singbox.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kunk.singbox.database.entity.ProfileEntity
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.UpdateStatus
import kotlinx.coroutines.flow.Flow

/**
 *
 *
 */
@Dao
interface ProfileDao {

    @Query("SELECT * FROM profiles ORDER BY sortOrder ASC")
    fun getAllFlow(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY sortOrder ASC")
    suspend fun getAll(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun getByIdFlow(id: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE enabled = 1 ORDER BY sortOrder ASC")
    suspend fun getEnabled(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE type = :type ORDER BY sortOrder ASC")
    suspend fun getByType(type: ProfileType): List<ProfileEntity>

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    @Query("SELECT MAX(sortOrder) FROM profiles")
    suspend fun getMaxSortOrder(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<ProfileEntity>)

    @Update
    suspend fun update(profile: ProfileEntity)

    @Query("UPDATE profiles SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("UPDATE profiles SET updateStatus = :status WHERE id = :id")
    suspend fun setUpdateStatus(id: String, status: UpdateStatus)

    @Query("UPDATE profiles SET lastUpdated = :timestamp WHERE id = :id")
    suspend fun setLastUpdated(id: String, timestamp: Long)

    @Query("UPDATE profiles SET name = :name WHERE id = :id")
    suspend fun setName(id: String, name: String)

    @Query("UPDATE profiles SET autoUpdateInterval = :interval WHERE id = :id")
    suspend fun setAutoUpdateInterval(id: String, interval: Int)

    @Query("UPDATE profiles SET expireDate = :expireDate, totalTraffic = :totalTraffic, usedTraffic = :usedTraffic WHERE id = :id")
    suspend fun updateTrafficInfo(id: String, expireDate: Long, totalTraffic: Long, usedTraffic: Long)

    @Delete
    suspend fun delete(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM profiles")
    suspend fun deleteAll()

    // ==================== 鎺掑簭 ====================

    @Query("UPDATE profiles SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: String, order: Int)
}

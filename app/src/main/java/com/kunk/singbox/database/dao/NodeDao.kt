package com.kunk.singbox.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kunk.singbox.database.entity.NodeEntity
import kotlinx.coroutines.flow.Flow

/**
 *
 *
 */
@Dao
interface NodeDao {

    @Query("SELECT * FROM nodes ORDER BY sortOrder ASC")
    fun getAllFlow(): Flow<List<NodeEntity>>

    @Query("SELECT * FROM nodes ORDER BY sortOrder ASC")
    suspend fun getAll(): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE id = :id")
    suspend fun getById(id: String): NodeEntity?

    @Query("SELECT * FROM nodes WHERE sourceProfileId = :profileId ORDER BY sortOrder ASC")
    fun getByProfileIdFlow(profileId: String): Flow<List<NodeEntity>>

    @Query("SELECT * FROM nodes WHERE sourceProfileId = :profileId ORDER BY sortOrder ASC")
    suspend fun getByProfileId(profileId: String): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE isFavorite = 1 ORDER BY sortOrder ASC")
    suspend fun getFavorites(): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE isFavorite = 1 ORDER BY sortOrder ASC")
    fun getFavoritesFlow(): Flow<List<NodeEntity>>

    @Query("SELECT * FROM nodes WHERE protocol = :protocol ORDER BY sortOrder ASC")
    suspend fun getByProtocol(protocol: String): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE `group` = :group ORDER BY sortOrder ASC")
    suspend fun getByGroup(group: String): List<NodeEntity>

    @Query("SELECT DISTINCT `group` FROM nodes WHERE sourceProfileId = :profileId")
    suspend fun getGroupsByProfileId(profileId: String): List<String>

    @Query("SELECT DISTINCT protocol FROM nodes")
    suspend fun getAllProtocols(): List<String>

    @Query("SELECT COUNT(*) FROM nodes")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM nodes WHERE sourceProfileId = :profileId")
    suspend fun countByProfileId(profileId: String): Int

    @Query("SELECT MAX(sortOrder) FROM nodes WHERE sourceProfileId = :profileId")
    suspend fun getMaxSortOrder(profileId: String): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: NodeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<NodeEntity>)

    @Update
    suspend fun update(node: NodeEntity)

    @Query("UPDATE nodes SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE nodes SET latencyMs = :latencyMs WHERE id = :id")
    suspend fun setLatency(id: String, latencyMs: Long?)

    @Query("UPDATE nodes SET trafficUsed = :trafficUsed WHERE id = :id")
    suspend fun setTrafficUsed(id: String, trafficUsed: Long)

    @Delete
    suspend fun delete(node: NodeEntity)

    @Query("DELETE FROM nodes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM nodes WHERE sourceProfileId = :profileId")
    suspend fun deleteByProfileId(profileId: String)

    @Query("DELETE FROM nodes")
    suspend fun deleteAll()

    @Query("UPDATE nodes SET latencyMs = :latencyMs WHERE id = :id")
    suspend fun updateLatency(id: String, latencyMs: Long?)

    // ==================== 鎺掑簭 ====================

    @Query("UPDATE nodes SET sortOrder = :order WHERE id = :id")
    suspend fun setSortOrder(id: String, order: Int)
}

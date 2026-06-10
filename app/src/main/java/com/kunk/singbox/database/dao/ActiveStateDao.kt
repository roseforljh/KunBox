package com.kunk.singbox.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kunk.singbox.database.entity.ActiveStateEntity
import kotlinx.coroutines.flow.Flow

/**
 *
 */
@Dao
interface ActiveStateDao {

    @Query("SELECT * FROM active_state WHERE id = 1")
    fun getFlow(): Flow<ActiveStateEntity?>

    @Query("SELECT * FROM active_state WHERE id = 1")
    suspend fun get(): ActiveStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(state: ActiveStateEntity)

    @Query("UPDATE active_state SET activeProfileId = :profileId WHERE id = 1")
    suspend fun setActiveProfileId(profileId: String?)

    @Query("UPDATE active_state SET activeNodeId = :nodeId WHERE id = 1")
    suspend fun setActiveNodeId(nodeId: String?)

    @Query("DELETE FROM active_state")
    suspend fun clear()
}

package com.kunk.singbox.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kunk.singbox.database.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow

/**
 *
 *
 *
 *
 *
 */
@Dao
interface SettingsDao {

    /**
     *
     */
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observeSettings(): Flow<SettingsEntity?>

    /**
     *
     */
    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun getSettings(): SettingsEntity?

    /**
     */
    @Query("SELECT * FROM settings WHERE id = 1")
    fun getSettingsSync(): SettingsEntity?

    /**
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: SettingsEntity)

    /**
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveSettingsSync(settings: SettingsEntity)

    /**
     */
    @Query("DELETE FROM settings")
    suspend fun deleteSettings()

    /**
     *
     */
    @Query("SELECT EXISTS(SELECT 1 FROM settings WHERE id = 1)")
    suspend fun hasSettings(): Boolean

    /**
     *
     */
    @Query("SELECT EXISTS(SELECT 1 FROM settings WHERE id = 1)")
    fun hasSettingsSync(): Boolean
}

package com.kunk.singbox.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kunk.singbox.database.entity.ProfileEntity

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profiles ORDER BY sortOrder ASC")
    suspend fun getAll(): List<ProfileEntity>

    @Query("SELECT MAX(sortOrder) FROM profiles")
    suspend fun getMaxSortOrder(): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: ProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<ProfileEntity>)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: String)
}

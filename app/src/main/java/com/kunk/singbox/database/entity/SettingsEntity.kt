package com.kunk.singbox.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey
    val id: Int = 1,

    val version: Int = CURRENT_VERSION,

    val data: String,

    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val CURRENT_VERSION = 13
    }
}

package com.kunk.singbox.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "active_state")
data class ActiveStateEntity(
    @PrimaryKey
    val id: Int = 1,
    val activeProfileId: String?,
    val activeNodeId: String?
)

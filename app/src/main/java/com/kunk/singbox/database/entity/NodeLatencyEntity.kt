package com.kunk.singbox.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "node_latencies")
data class NodeLatencyEntity(
    @PrimaryKey
    val nodeId: String,
    val latencyMs: Long,
    val testedAt: Long = System.currentTimeMillis()
)

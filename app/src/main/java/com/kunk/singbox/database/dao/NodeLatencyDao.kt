package com.kunk.singbox.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kunk.singbox.database.entity.NodeLatencyEntity

@Dao
interface NodeLatencyDao {

    @Query("SELECT * FROM node_latencies")
    suspend fun getAll(): List<NodeLatencyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(latencies: List<NodeLatencyEntity>)

    @Query("DELETE FROM node_latencies")
    suspend fun deleteAll()

    @Query("DELETE FROM node_latencies WHERE nodeId IN (:nodeIds)")
    suspend fun deleteByNodeIds(nodeIds: List<String>)

    @Query("INSERT OR REPLACE INTO node_latencies (nodeId, latencyMs, testedAt) VALUES (:nodeId, :latencyMs, :testedAt)")
    suspend fun upsert(nodeId: String, latencyMs: Long, testedAt: Long = System.currentTimeMillis())
}

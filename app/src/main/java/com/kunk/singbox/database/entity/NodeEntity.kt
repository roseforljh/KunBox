package com.kunk.singbox.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.kunk.singbox.model.NodeUi

/**
 *
 *
 *
 */
@Entity(
    tableName = "nodes",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceProfileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sourceProfileId"]),
        Index(value = ["protocol"]),
        Index(value = ["group"]),
        Index(value = ["isFavorite"])
    ]
)
data class NodeEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val protocol: String,
    val group: String,
    val latencyMs: Long?,
    val isFavorite: Boolean = false,
    val sourceProfileId: String,
    val tags: String = "",
    val trafficUsed: Long = 0,
    val sortOrder: Int = 0
) {
    /**
     *
     */
    fun toUiModel(): NodeUi = NodeUi(
        id = id,
        name = name,
        protocol = protocol,
        group = group,
        latencyMs = latencyMs,
        isFavorite = isFavorite,
        sourceProfileId = sourceProfileId,
        tags = parseTagsJson(tags),
        trafficUsed = trafficUsed
    )

    companion object {
        /**
         */
        fun fromUiModel(ui: NodeUi, sortOrder: Int = 0): NodeEntity = NodeEntity(
            id = ui.id,
            name = ui.name,
            protocol = ui.protocol,
            group = ui.group,
            latencyMs = ui.latencyMs,
            isFavorite = ui.isFavorite,
            sourceProfileId = ui.sourceProfileId,
            tags = tagsToJson(ui.tags),
            trafficUsed = ui.trafficUsed,
            sortOrder = sortOrder
        )

        private fun parseTagsJson(json: String): List<String> {
            if (json.isBlank()) return emptyList()
            return try {
                json.removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().removeSurrounding("\"") }
                    .filter { it.isNotBlank() }
            } catch (e: Exception) {
                emptyList()
            }
        }

        private fun tagsToJson(tags: List<String>): String {
            if (tags.isEmpty()) return ""
            return tags.joinToString(",", "[", "]") { "\"$it\"" }
        }
    }
}

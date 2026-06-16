package com.kunk.singbox.model

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class ClashConnectionsResponse(
    @SerializedName("downloadTotal") val downloadTotal: Long = 0,
    @SerializedName("uploadTotal") val uploadTotal: Long = 0,
    @SerializedName("connections") val connections: List<ClashConnection> = emptyList()
)

@Keep
data class ClashConnection(
    @SerializedName("id") val id: String,
    @SerializedName("metadata") val metadata: ClashConnectionMetadata,
    @SerializedName("upload") val upload: Long = 0,
    @SerializedName("download") val download: Long = 0,
    @SerializedName("start") val start: String = "",
    @SerializedName("chains") val chains: List<String> = emptyList(),
    @SerializedName("rule") val rule: String = "",
    @SerializedName("rulePayload") val rulePayload: String = ""
)

@Keep
data class ClashConnectionMetadata(
    @SerializedName("network") val network: String = "",
    @SerializedName("type") val type: String = "",
    @SerializedName("sourceIP") val sourceIP: String = "",
    @SerializedName("destinationIP") val destinationIP: String = "",
    @SerializedName("sourcePort") val sourcePort: String = "",
    @SerializedName("destinationPort") val destinationPort: String = "",
    @SerializedName("host") val host: String = ""
)

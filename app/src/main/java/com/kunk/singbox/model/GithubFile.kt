package com.kunk.singbox.model

import com.google.gson.annotations.SerializedName

data class GithubTreeResponse(
    @SerializedName("tree") val tree: List<GithubTreeItem>
)

data class GithubTreeItem(
    @SerializedName("path") val path: String,
    @SerializedName("type") val type: String
)

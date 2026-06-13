package com.kunk.singbox.core

data class ActiveConnection(
    val packageName: String?,
    val uid: Int,
    val network: String,
    val remoteAddr: String,
    val remotePort: Int,
    val state: String,
    val connectionCount: Int = 0,
    val totalUpload: Long = 0,
    val totalDownload: Long = 0,
    val oldestConnMs: Long = 0,
    val newestConnMs: Long = 0,
    val hasRecentData: Boolean = true
)

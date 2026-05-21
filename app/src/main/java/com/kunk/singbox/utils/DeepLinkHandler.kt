package com.kunk.singbox.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 */
object DeepLinkHandler {

    data class SubscriptionImportData(
        val name: String,
        val url: String,
        val autoUpdateInterval: Int
    )

    private val _pendingSubscriptionImport = MutableStateFlow<SubscriptionImportData?>(null)
    val pendingSubscriptionImport: StateFlow<SubscriptionImportData?> = _pendingSubscriptionImport.asStateFlow()

    /**
     *
     */
    fun setPendingSubscriptionImport(name: String, url: String, interval: Int) {
        _pendingSubscriptionImport.value = SubscriptionImportData(name, url, interval)
    }

    /**
     *
     */
    fun clearPendingSubscriptionImport() {
        _pendingSubscriptionImport.value = null
    }
}

package com.kunk.singbox.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.ProfileUi
import com.kunk.singbox.model.UpdateStatus

/**
 *
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val type: ProfileType,
    val url: String?,
    val lastUpdated: Long,
    val enabled: Boolean,
    val autoUpdateInterval: Int = 0,
    val updateStatus: UpdateStatus = UpdateStatus.Idle,
    val expireDate: Long = 0,
    val totalTraffic: Long = 0,
    val usedTraffic: Long = 0,
    val sortOrder: Int = 0,

    val dnsPreResolve: Boolean = false,
    val dnsServer: String? = null,
    val dnsOverride: String? = null
) {
    /**
     */
    fun toUiModel(): ProfileUi = ProfileUi(
        id = id,
        name = name,
        type = type,
        url = url,
        lastUpdated = lastUpdated,
        enabled = enabled,
        autoUpdateInterval = autoUpdateInterval,
        updateStatus = updateStatus,
        expireDate = expireDate,
        totalTraffic = totalTraffic,
        usedTraffic = usedTraffic,
        dnsPreResolve = dnsPreResolve,
        dnsServer = dnsServer,
        dnsOverride = dnsOverride
    )

    companion object {
        /**
         */
        fun fromUiModel(ui: ProfileUi, sortOrder: Int = 0): ProfileEntity = ProfileEntity(
            id = ui.id,
            name = ui.name,
            type = ui.type,
            url = ui.url,
            lastUpdated = ui.lastUpdated,
            enabled = ui.enabled,
            autoUpdateInterval = ui.autoUpdateInterval,
            updateStatus = ui.updateStatus,
            expireDate = ui.expireDate,
            totalTraffic = ui.totalTraffic,
            usedTraffic = ui.usedTraffic,
            sortOrder = sortOrder,
            dnsPreResolve = ui.dnsPreResolve,
            dnsServer = ui.dnsServer,
            dnsOverride = ui.dnsOverride
        )
    }
}

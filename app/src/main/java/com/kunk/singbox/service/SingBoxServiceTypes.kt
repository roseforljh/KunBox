package com.kunk.singbox.service

import io.nekohasekai.libbox.*
import kotlinx.coroutines.*

data class SingBoxServiceRecoveryDebounceContext(
    val now: Long,
    val lane: String,
    val effectiveGlobalDebounceMs: Long,
    val effectiveSourceDebounceMs: Long,
    val reasonKey: String
)

data class SingBoxServiceForegroundFallbackState(
    val shouldSkip: Boolean,
    val event: String,
    val outcome: String
)

data class SingBoxServiceNetworkTypeChangedFallbackState(
    val shouldSkip: Boolean,
    val event: String,
    val outcome: String,
    val action: NetworkTypeChangedFallbackAction? = null
)

data class SingBoxServiceNetworkTypeChangedRecoverySignal(
    val probeSucceeded: Boolean,
    val networkRecoveryNeeded: Boolean,
    val strongSignal: Boolean
)

enum class RecoveryReason(
    val priority: Int,
    val sourceDebounceMs: Long,
    val isFastLane: Boolean
) {
    NETWORK_TYPE_CHANGED(priority = 100, sourceDebounceMs = 3000L, isFastLane = true),
    DOZE_EXIT(priority = 90, sourceDebounceMs = 3000L, isFastLane = true),
    NETWORK_VALIDATED(priority = 80, sourceDebounceMs = 3000L, isFastLane = false),
    VPN_HEALTH(priority = 70, sourceDebounceMs = 30000L, isFastLane = false),
    APP_FOREGROUND(priority = 50, sourceDebounceMs = 1500L, isFastLane = true),
    SCREEN_ON(priority = 50, sourceDebounceMs = 1500L, isFastLane = true),
    UNKNOWN(priority = 10, sourceDebounceMs = 3000L, isFastLane = false);

    companion object {
        @JvmStatic
        fun fromReasonString(reason: String): RecoveryReason {
            val normalized = reason.trim().lowercase()
            return when {
                normalized.contains("network_type_changed") ||
                    normalized.contains("typechange") -> NETWORK_TYPE_CHANGED
                normalized.contains("doze_exit") -> DOZE_EXIT
                normalized.contains("network_validated") -> NETWORK_VALIDATED
                normalized.contains("vpnhealth") || normalized.contains("vpn_health") -> VPN_HEALTH
                normalized.contains("app_foreground") -> APP_FOREGROUND
                normalized.contains("screen_on") -> SCREEN_ON
                else -> UNKNOWN
            }
        }
    }
}

enum class RecoveryProfile {
    DEFAULT,
    HYSTERIA2
}

enum class NetworkTypeChangedFallbackAction {
    ESCALATE_HARD,
    RESTART_VPN
}

data class RecoveryRequest(
    val reason: RecoveryReason,
    val rawReason: String,
    val force: Boolean,
    val requestedAtMs: Long,
    val merged: Boolean
)

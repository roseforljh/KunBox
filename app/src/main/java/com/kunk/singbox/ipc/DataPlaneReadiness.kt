package com.kunk.singbox.ipc

import com.kunk.singbox.service.ServiceState

enum class DataPlaneStatus {
    STOPPED,
    STARTING,
    BLOCKING,
    READY,
    RECOVERING,
    FAILED_BLOCKED,
    FAILED_UNPROTECTED
}

enum class VpnOwnerStatus {
    MATCH,
    MISMATCH,
    UNKNOWN
}

enum class VpnLockdownStatus {
    ENABLED,
    DISABLED,
    UNKNOWN
}

data class DataPlaneReadinessSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val status: DataPlaneStatus = DataPlaneStatus.STOPPED,
    val tunEstablished: Boolean = false,
    val systemVpnTransport: Boolean = false,
    val systemVpnOwnerStatus: VpnOwnerStatus = VpnOwnerStatus.UNKNOWN,
    val coreReady: Boolean = false,
    val selectorReady: Boolean = false,
    val recoveryActive: Boolean = false,
    val routingScope: String = "unknown",
    val lockdownStatus: VpnLockdownStatus = VpnLockdownStatus.UNKNOWN,
    val foreignVpnDetected: Boolean = false,
    val ownedVpnNetworkLost: Boolean = false,
    val ownedVpnNetworkHandle: Long = 0L,
    val observedVpnNetworkHandle: Long = 0L,
    val lastReadinessReason: String = "",
    val updatedAtElapsedMs: Long = 0L,
    val serviceInstanceId: String = "",
    val generation: Long = 0L
) {
    fun normalized(): DataPlaneReadinessSnapshot = copy(
        schemaVersion = schemaVersion.coerceAtLeast(0),
        routingScope = routingScope.ifBlank { "unknown" },
        ownedVpnNetworkHandle = ownedVpnNetworkHandle.coerceAtLeast(0L),
        observedVpnNetworkHandle = observedVpnNetworkHandle.coerceAtLeast(0L),
        updatedAtElapsedMs = updatedAtElapsedMs.coerceAtLeast(0L),
        generation = generation.coerceAtLeast(0L)
    )

    fun isFresh(nowElapsedMs: Long): Boolean {
        if (schemaVersion != CURRENT_SCHEMA_VERSION || updatedAtElapsedMs <= 0L) return false
        val ageMs = nowElapsedMs - updatedAtElapsedMs
        return ageMs in 0 until MAX_READINESS_AGE_MS
    }

    fun isReady(
        serviceState: ServiceState,
        mode: VpnStateStore.CoreMode,
        ipcBound: Boolean,
        apiLevel: Int,
        nowElapsedMs: Long
    ): Boolean {
        if (!hasReadyControlPlane(serviceState, ipcBound, nowElapsedMs)) return false
        if (mode == VpnStateStore.CoreMode.PROXY) return true
        if (mode != VpnStateStore.CoreMode.VPN) return false
        return hasReadyVpnDataPlane(apiLevel)
    }

    private fun hasReadyControlPlane(
        serviceState: ServiceState,
        ipcBound: Boolean,
        nowElapsedMs: Long
    ): Boolean {
        val running = ipcBound && serviceState == ServiceState.RUNNING && status == DataPlaneStatus.READY
        val healthy = coreReady && selectorReady && !recoveryActive
        return running && healthy && isFresh(nowElapsedMs)
    }

    private fun hasReadyVpnDataPlane(apiLevel: Int): Boolean {
        val vpnPresent = tunEstablished && systemVpnTransport
        val vpnExclusive = !foreignVpnDetected && !ownedVpnNetworkLost
        if (!vpnPresent || !vpnExclusive) return false
        return if (apiLevel >= OWNER_UID_MIN_API) {
            systemVpnOwnerStatus == VpnOwnerStatus.MATCH
        } else {
            ownedVpnNetworkHandle > 0L && observedVpnNetworkHandle == ownedVpnNetworkHandle
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val MAX_READINESS_AGE_MS = 10_000L
        const val HEARTBEAT_INTERVAL_MS = 5_000L
        const val PERSIST_HEARTBEAT_INTERVAL_MS = 30_000L
        const val OWNER_UID_MIN_API = 30

        fun stopped(serviceInstanceId: String = ""): DataPlaneReadinessSnapshot =
            DataPlaneReadinessSnapshot(serviceInstanceId = serviceInstanceId)
    }
}

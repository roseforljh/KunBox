package com.kunk.singbox.ipc

import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.model.isRootSha256

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

enum class VpnNetworkOwnership {
    OWNED,
    FOREIGN,
    UNKNOWN,
    IGNORE
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
    val rootPid: Int = 0,
    val rootFdCount: Int = 0,
    val rootRuntimeSessionId: String = "",
    val rootRuleRevision: Long = 0L,
    val rootRoutingGeneration: Long = 0L,
    val rootConfigSha256: String = "",
    val rootSidecarSha256: String = "",
    val rootStaticPlanSha256: String = "",
    val rootAppRoutingSha256: String = "",
    val rootResolvedPlanSha256: String = "",
    val rootWatchdogReady: Boolean = false,
    val rootRulesInstalled: Boolean = false,
    val lastReadinessReason: String = "",
    val updatedAtElapsedMs: Long = 0L,
    val serviceInstanceId: String = "",
    val generation: Long = 0L,
    val vpnSessionId: Long = 0L
) {
    fun normalized(): DataPlaneReadinessSnapshot = copy(
        schemaVersion = schemaVersion.coerceAtLeast(0),
        routingScope = routingScope.ifBlank { "unknown" },
        ownedVpnNetworkHandle = ownedVpnNetworkHandle.coerceAtLeast(0L),
        observedVpnNetworkHandle = observedVpnNetworkHandle.coerceAtLeast(0L),
        rootPid = rootPid.coerceAtLeast(0),
        rootFdCount = rootFdCount.coerceAtLeast(0),
        rootRuleRevision = rootRuleRevision.coerceAtLeast(0L),
        rootRoutingGeneration = rootRoutingGeneration.coerceAtLeast(0L),
        updatedAtElapsedMs = updatedAtElapsedMs.coerceAtLeast(0L),
        generation = generation.coerceAtLeast(0L),
        vpnSessionId = vpnSessionId.coerceAtLeast(0L)
    )

    internal fun beginVpnSession(
        serviceInstanceId: String,
        sessionId: Long
    ): DataPlaneReadinessSnapshot = DataPlaneReadinessSnapshot(
        status = DataPlaneStatus.STARTING,
        serviceInstanceId = serviceInstanceId,
        lastReadinessReason = "service_starting",
        vpnSessionId = sessionId.coerceAtLeast(0L)
    )

    internal fun observeVpnNetwork(
        ownership: VpnNetworkOwnership,
        networkHandle: Long,
        serviceState: ServiceState
    ): DataPlaneReadinessSnapshot {
        val handle = networkHandle.coerceAtLeast(0L)
        return when (ownership) {
            VpnNetworkOwnership.OWNED -> {
                val canBeReady = serviceState == ServiceState.RUNNING &&
                    coreReady && selectorReady && tunEstablished && !recoveryActive
                val updated = copy(
                    systemVpnTransport = true,
                    systemVpnOwnerStatus = VpnOwnerStatus.MATCH,
                    foreignVpnDetected = false,
                    ownedVpnNetworkLost = false,
                    ownedVpnNetworkHandle = handle,
                    observedVpnNetworkHandle = handle,
                    lastReadinessReason = "vpn_owner_match"
                )
                updated.copy(status = updated.resolveVpnStatus(canBeReady))
            }

            VpnNetworkOwnership.UNKNOWN -> {
                val updated = copy(
                    systemVpnTransport = true,
                    systemVpnOwnerStatus = VpnOwnerStatus.UNKNOWN,
                    observedVpnNetworkHandle = handle,
                    lastReadinessReason = "vpn_owner_verifying"
                )
                updated.copy(status = updated.resolveVpnStatus(canBeReady = false))
            }

            VpnNetworkOwnership.FOREIGN -> copy(
                status = if (serviceState == ServiceState.RUNNING || serviceState == ServiceState.STARTING) {
                    DataPlaneStatus.FAILED_UNPROTECTED
                } else {
                    status
                },
                systemVpnTransport = true,
                systemVpnOwnerStatus = VpnOwnerStatus.MISMATCH,
                foreignVpnDetected = true,
                observedVpnNetworkHandle = handle,
                lastReadinessReason = "foreign_vpn_confirmed"
            )

            VpnNetworkOwnership.IGNORE -> this
        }
    }

    internal fun resolveVpnStatus(canBeReady: Boolean): DataPlaneStatus {
        return when {
            status == DataPlaneStatus.FAILED_BLOCKED -> DataPlaneStatus.FAILED_BLOCKED
            foreignVpnDetected || ownedVpnNetworkLost -> DataPlaneStatus.FAILED_UNPROTECTED
            canBeReady -> DataPlaneStatus.READY
            recoveryActive -> DataPlaneStatus.RECOVERING
            else -> DataPlaneStatus.BLOCKING
        }
    }

    internal fun observeOwnedVpnNetworkLost(
        networkHandle: Long,
        serviceState: ServiceState
    ): DataPlaneReadinessSnapshot {
        val handle = networkHandle.coerceAtLeast(0L)
        if (ownedVpnNetworkHandle <= 0L || ownedVpnNetworkHandle != handle) return this
        val protectionLost = serviceState == ServiceState.RUNNING && !recoveryActive
        return copy(
            status = when {
                protectionLost -> DataPlaneStatus.FAILED_UNPROTECTED
                serviceState == ServiceState.STOPPED -> DataPlaneStatus.STOPPED
                else -> DataPlaneStatus.BLOCKING
            },
            systemVpnTransport = false,
            systemVpnOwnerStatus = VpnOwnerStatus.UNKNOWN,
            ownedVpnNetworkLost = true,
            ownedVpnNetworkHandle = 0L,
            observedVpnNetworkHandle = 0L,
            lastReadinessReason = "owned_vpn_network_lost"
        )
    }

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
        if (mode == VpnStateStore.CoreMode.ROOT) {
            return rootPid > 0 &&
                rootRuntimeSessionId.isNotBlank() &&
                rootRoutingGeneration > 0L &&
                isRootSha256(rootConfigSha256) &&
                isRootSha256(rootSidecarSha256) &&
                isRootSha256(rootStaticPlanSha256) &&
                isRootSha256(rootAppRoutingSha256) &&
                isRootSha256(rootResolvedPlanSha256) &&
                rootWatchdogReady &&
                rootRulesInstalled
        }
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
        const val CURRENT_SCHEMA_VERSION = 3
        const val MAX_READINESS_AGE_MS = 10_000L
        const val HEARTBEAT_INTERVAL_MS = 5_000L
        const val PERSIST_HEARTBEAT_INTERVAL_MS = 30_000L
        const val OWNER_UID_MIN_API = 30

        fun stopped(serviceInstanceId: String = ""): DataPlaneReadinessSnapshot =
            DataPlaneReadinessSnapshot(serviceInstanceId = serviceInstanceId)
    }
}

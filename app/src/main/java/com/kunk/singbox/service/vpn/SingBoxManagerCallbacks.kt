@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.service

import android.net.ConnectivityManager
import android.net.Network
import android.os.ParcelFileDescriptor
import android.util.Log
import com.kunk.singbox.ipc.SingBoxIpcHub
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.repository.*
import com.kunk.singbox.service.manager.PlatformInterfaceImpl
import com.kunk.singbox.service.manager.RecoveryIntentLease
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.service.manager.ShutdownManager
import io.nekohasekai.libbox.*
import kotlinx.coroutines.*

private const val STOP_FOREGROUND_REMOVE = android.app.Service.STOP_FOREGROUND_REMOVE

internal fun SingBoxService.createPlatformCallbacks(): PlatformInterfaceImpl.Callbacks = object : PlatformInterfaceImpl.Callbacks {
    override fun protect(fd: Int): Boolean = this@createPlatformCallbacks.protect(fd)

    override fun openTun(options: TunOptions): Result<Int> {
        isConnectingTun.set(true)
        return try {
            val network = getCurrentPhysicalNetwork()
            val result = coreManager.openTun(
                options = options,
                underlyingNetwork = network,
                reuseExisting = true,
                serviceInstanceId = SingBoxIpcHub.serviceInstanceId(),
                runtimeGeneration = VpnStateStore.getRuntimeStateSnapshot().generation,
                expectedPerAppPolicyRevision = pendingPerAppPolicyRevision,
                requestId = pendingAppRouteRequestId,
                configDigest = pendingConfigDigest.ifBlank {
                    coreManager.currentConfigContent?.let { ConfigRepository.sha256(it) }.orEmpty()
                },
                appRoutingDigest = pendingAppRoutingDigest.ifBlank {
                    coreManager.currentSettings?.let { ConfigRepository.appRoutingDigest(it) }.orEmpty()
                }
            )
            result.onSuccess { _ ->
                vpnInterface = coreManager.vpnInterface
                pendingPerAppPolicyRevision = 0L
                pendingAppRouteRequestId = ""
                pendingConfigDigest = ""
                pendingAppRoutingDigest = ""
                publishEstablishedTunReadiness()
                if (network != null) {
                    lastKnownNetwork = network
                    markPhysicalNetworkChanged()
                }
            }
            result
        } finally {
            isConnectingTun.set(false)
        }
    }

    override fun getConnectivityManager(): ConnectivityManager? = connectivityManager
    override fun getCurrentNetwork(): Network? = getCurrentPhysicalNetwork()
    override fun getLastKnownNetwork(): Network? = lastKnownNetwork
    override fun setLastKnownNetwork(network: Network?) { lastKnownNetwork = network }
    override fun markVpnStarted() { markPhysicalNetworkChanged() }

    override fun onDefaultNetworkChanged() {
        this@createPlatformCallbacks.handleDefaultNetworkChanged()
    }
    override fun setUnderlyingNetworks(networks: Array<Network>?) {
        this@createPlatformCallbacks.setUnderlyingNetworks(networks)
    }

    override fun getCurrentSettings(): AppSettings? = coreManager.currentSettings

    override fun incrementConnectionOwnerCalls() { ServiceStateHolder.incrementConnectionOwnerCalls() }
    override fun incrementConnectionOwnerInvalidArgs() { ServiceStateHolder.incrementConnectionOwnerInvalidArgs() }
    override fun incrementConnectionOwnerUidResolved() { ServiceStateHolder.incrementConnectionOwnerUidResolved() }
    override fun incrementConnectionOwnerSecurityDenied() {
        ServiceStateHolder.incrementConnectionOwnerSecurityDenied()
    }
    override fun incrementConnectionOwnerOtherException() {
        ServiceStateHolder.incrementConnectionOwnerOtherException()
    }
    override fun setConnectionOwnerLastEvent(event: String) {
        ServiceStateHolder.setConnectionOwnerLastEvent(event)
    }
    override fun setConnectionOwnerLastUid(uid: Int) {
        ServiceStateHolder.setConnectionOwnerLastUid(uid)
    }
    override fun isConnectionOwnerPermissionDeniedLogged(): Boolean =
        ServiceStateHolder.connectionOwnerPermissionDeniedLogged
    override fun setConnectionOwnerPermissionDeniedLogged(logged: Boolean) {
        ServiceStateHolder.connectionOwnerPermissionDeniedLogged = logged
    }

    override fun cacheUidToPackage(uid: Int, packageName: String) {
        this@createPlatformCallbacks.cacheUidToPackage(uid, packageName)
    }
    override fun getUidFromCache(uid: Int): String? = getCachedPackageForUid(uid)

    override fun findBestPhysicalNetwork(): Network? = this@createPlatformCallbacks.findBestPhysicalNetwork()
}

@Suppress("TooManyFunctions")
internal fun SingBoxService.createShutdownCallbacks(): ShutdownManager.Callbacks = object : ShutdownManager.Callbacks {
    // 状态管理
    override fun updateServiceState(state: ServiceState) {
        this@createShutdownCallbacks.updateServiceState(state)
    }
    override fun updateTileState() { this@createShutdownCallbacks.updateTileState() }
    override fun stopForegroundService() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e(SingBoxService.TAG, "Error stopping foreground", e)
        }
    }
    override fun stopSelf() {
        if (stopSelfRequested) {
            this@createShutdownCallbacks.stopSelf()
        }
    }
    override fun forceStopProcess(reason: String) {
        this@createShutdownCallbacks.forceStopProcess(reason)
    }
    override fun onRecoveryTimeout(kind: ShutdownManager.ShutdownKind, reason: String) {
        Log.e(SingBoxService.TAG, "Recovery timeout kind=$kind reason=$reason; TUN retained")
        synchronized(this@createShutdownCallbacks) {
            isStopping = false
            stopSelfRequested = false
            hardStopRecoveryIntentLease = null
            cleanupJob = null
            coreManager.completeStop()
        }
        SingBoxService.isRunning = false
        SingBoxService.setLastError("VPN recovery failed; traffic remains blocked")
        SingBoxIpcHub.update(
            state = ServiceState.RUNNING,
            lastError = SingBoxService.lastErrorFlow.value.orEmpty(),
            readiness = SingBoxIpcHub.currentReadiness().copy(
                status = com.kunk.singbox.ipc.DataPlaneStatus.FAILED_BLOCKED,
                coreReady = false,
                selectorReady = false,
                recoveryActive = false,
                tunEstablished = coreManager.isVpnInterfaceValid(),
                lastReadinessReason = reason
            )
        )
        requestNotificationUpdate(force = true)
        updateTileState()
    }

    // 组件管理
    override fun cancelStartVpnJob(): Job? {
        val job = startVpnJob
        startVpnJob = null
        job?.cancel()
        return job
    }
    override fun cancelPostStartJob(): Job? {
        postStartGeneration.incrementAndGet()
        val job = postStartJob
        postStartJob = null
        job?.cancel()
        return job
    }
    override fun cancelHotReloadJob(): Job? {
        val job = hotReloadJob
        hotReloadJob = null
        job?.cancel()
        return job
    }
    override fun cancelRemoteStateUpdateJob() {
        remoteStateUpdateJob?.cancel()
        remoteStateUpdateJob = null
    }
    override fun cancelAutoFailoverJob() {
        autoFailoverJob?.cancel()
        autoFailoverJob = null
        sameNodeRecoveryInFlight.set(false)
        autoFailoverServiceStartedAtMs = 0L
    }

    // 资源清理
    override fun stopForeignVpnMonitor() { foreignVpnMonitor.stop() }
    override fun tryClearRunningServiceForLibbox() {
        this@createShutdownCallbacks.tryClearRunningServiceForLibbox()
    }
    override fun unregisterScreenStateReceiver() {
        screenStateManager.unregisterScreenStateReceiver()
    }
    override fun closeDefaultInterfaceMonitor() {
        platformInterfaceImpl.closeDefaultInterfaceMonitor(null)
    }

    // 获取状态
    override fun getConnectivityManager(): ConnectivityManager? = connectivityManager

    // 设置状态
    override fun setVpnInterface(fd: ParcelFileDescriptor?) { vpnInterface = fd }
    override fun setIsRunning(running: Boolean) { SingBoxService.isRunning = running }
    override fun setRealTimeNodeName(name: String?) {
        realTimeNodeName = name
        if (!name.isNullOrBlank() && name == pendingNodeName) {
            pendingNodeName = null
        }
    }
    override fun setNetworkCallbackReady(ready: Boolean) { networkCallbackReady = ready }
    override fun setLastKnownNetwork(network: Network?) { lastKnownNetwork = network }
    override fun clearUnderlyingNetworks() {
        runCatching { setUnderlyingNetworks(null) }
    }

    // 获取配置路径用于重启
    override fun hasPendingStartConfigPath(): Boolean = synchronized(this@createShutdownCallbacks) {
        !pendingStartConfigPath.isNullOrBlank()
    }
    override fun completeStop(
        initialStopService: Boolean,
        recoveryIntentLease: RecoveryIntentLease
    ): ShutdownManager.StopCompletion =
        synchronized(this@createShutdownCallbacks) {
            val completion = ShutdownManager.resolveStopCompletion(
                initialStopService = initialStopService,
                hardStopRequested = stopSelfRequested,
                cleanupRecoveryIntentLease = recoveryIntentLease,
                hardStopRecoveryIntentLease = hardStopRecoveryIntentLease,
                pendingStartConfigPath = pendingStartConfigPath,
                pendingRecoveryIntentLease = pendingStartRecoveryIntentLease
            )
            coreManager.completeStop()
            stopSelfRequested = completion.stopService
            hardStopRecoveryIntentLease = completion.recoveryIntentLease.takeIf { completion.stopService }
            pendingStartConfigPath = null
            pendingStartRecoveryIntentLease = null
            SingBoxService.isStarting = false
            isStopping = false
            completion
        }
    override fun startVpn(configPath: String, recoveryIntentLease: RecoveryIntentLease?) {
        this@createShutdownCallbacks.startVpn(configPath, recoveryIntentLease)
    }
}

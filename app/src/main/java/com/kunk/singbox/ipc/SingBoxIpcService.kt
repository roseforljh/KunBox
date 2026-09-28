package com.kunk.singbox.ipc

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import com.kunk.singbox.aidl.ISingBoxService
import com.kunk.singbox.aidl.ISingBoxServiceCallback
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.service.manager.ServiceStateHolder
import com.kunk.singbox.service.root.RootTransparentForegroundService
import com.kunk.singbox.service.root.RootServicePrewarmer
import com.kunk.singbox.utils.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SingBoxIpcService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val binder = object : ISingBoxService.Stub() {
        override fun getStateSnapshot(): Bundle = SingBoxIpcHub.getStateSnapshotBundle()

        override fun registerCallback(callback: ISingBoxServiceCallback?) {
            if (callback == null) return
            SingBoxIpcHub.registerCallback(callback)
        }

        override fun unregisterCallback(callback: ISingBoxServiceCallback?) {
            if (callback == null) return
            SingBoxIpcHub.unregisterCallback(callback)
        }

        override fun notifyAppLifecycle(isForeground: Boolean) {
            SingBoxIpcHub.onAppLifecycle(isForeground)
            if (isForeground) {
                scheduleRootPrewarm()
            } else {
                serviceScope.launch { RootServicePrewarmer.stopIdle() }
            }
        }

        override fun hotReloadConfig(configContent: String?): Int {
            if (configContent.isNullOrEmpty()) {
                return SingBoxIpcHub.HotReloadResult.UNKNOWN_ERROR
            }
            return SingBoxIpcHub.hotReloadConfig(configContent)
        }

        override fun requestUrlTestNodeDelay(requestId: Long, groupTag: String?, nodeTag: String?, timeoutMs: Int) {
            if (groupTag.isNullOrBlank() || nodeTag.isNullOrBlank()) {
                SingBoxIpcHub.requestUrlTestNodeDelayResult(requestId, -1)
                return
            }
            SingBoxIpcHub.requestUrlTestNodeDelay(requestId, groupTag, nodeTag, timeoutMs)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapFromCache(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        val isRootZombie = VpnStateStore.getMode() == VpnStateStore.CoreMode.ROOT &&
            !RootTransparentForegroundService.isRunning &&
            !RootTransparentForegroundService.isStarting
        val isVpnOrProxyZombie = (VpnStateStore.getMode() == VpnStateStore.CoreMode.VPN ||
            VpnStateStore.getMode() == VpnStateStore.CoreMode.PROXY) &&
            !ServiceStateHolder.isRunning &&
            !ServiceStateHolder.isStarting &&
            !ProxyOnlyService.isRunning &&
            !ProxyOnlyService.isStarting
        if (isRootZombie || isVpnOrProxyZombie) {
            VpnStateStore.setActive(false)
            VpnStateStore.setPending("")
            VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
            val currentSnapshot = VpnStateStore.getRuntimeStateSnapshot()
            VpnStateStore.buildNextRuntimeStateSnapshot(currentSnapshot) {
                it.copy(
                    stateOrdinal = ServiceState.STOPPED.ordinal,
                    readiness = DataPlaneReadinessSnapshot.stopped("SingBoxIpcService_reconcile")
                )
            }.also { VpnStateStore.persistRuntimeStateSnapshotBestEffort(it) }
        }
        SingBoxIpcHub.registerService(this)
        scheduleRootPrewarm()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        SingBoxIpcHub.unregisterService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder {
        scheduleRootPrewarm()
        return binder
    }

    private fun scheduleRootPrewarm() {
        serviceScope.launch { RootServicePrewarmer.prewarmIfIdle(this@SingBoxIpcService) }
    }
}

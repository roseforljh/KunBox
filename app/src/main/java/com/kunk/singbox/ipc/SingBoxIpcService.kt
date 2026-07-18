package com.kunk.singbox.ipc

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import com.kunk.singbox.aidl.ISingBoxService
import com.kunk.singbox.aidl.ISingBoxServiceCallback

class SingBoxIpcService : Service() {

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

    override fun onCreate() {
        super.onCreate()
        SingBoxIpcHub.registerService(this)
    }

    override fun onDestroy() {
        SingBoxIpcHub.unregisterService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}

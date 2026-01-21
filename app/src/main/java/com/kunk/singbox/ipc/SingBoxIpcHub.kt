package com.kunk.singbox.ipc

import android.os.RemoteCallbackList
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.aidl.ISingBoxServiceCallback
import com.kunk.singbox.service.SingBoxService
import com.kunk.singbox.service.manager.BackgroundPowerManager
import java.util.concurrent.atomic.AtomicLong

object SingBoxIpcHub {
    private const val TAG = "SingBoxIpcHub"

    @Volatile
    private var stateOrdinal: Int = SingBoxService.ServiceState.STOPPED.ordinal

    @Volatile
    private var activeLabel: String = ""

    @Volatile
    private var lastError: String = ""

    @Volatile
    private var manuallyStopped: Boolean = false

    private val callbacks = RemoteCallbackList<ISingBoxServiceCallback>()

    private val broadcastLock = Any()
    @Volatile private var broadcasting: Boolean = false
    @Volatile private var broadcastPending: Boolean = false

    // 省电管理器引用，由 SingBoxService 设置
    @Volatile
    private var powerManager: BackgroundPowerManager? = null

    // 2025-fix-v6: 状态更新时间戳，用于检测回调通道是否正常
    private val lastStateUpdateAtMs = AtomicLong(0L)

    fun setPowerManager(manager: BackgroundPowerManager?) {
        powerManager = manager
        Log.d(TAG, "PowerManager ${if (manager != null) "set" else "cleared"}")
    }

    /**
     * 接收主进程的 App 生命周期通知
     */
    fun onAppLifecycle(isForeground: Boolean) {
        Log.i(TAG, "onAppLifecycle: isForeground=$isForeground")
        if (isForeground) {
            powerManager?.onAppForeground()
        } else {
            powerManager?.onAppBackground()
        }
    }

    fun getStateOrdinal(): Int = stateOrdinal

    fun getActiveLabel(): String = activeLabel

    fun getLastError(): String = lastError

    fun isManuallyStopped(): Boolean = manuallyStopped

    /**
     * 2025-fix-v6: 获取上次状态更新时间戳
     */
    fun getLastStateUpdateTime(): Long = lastStateUpdateAtMs.get()

    fun update(
        state: SingBoxService.ServiceState? = null,
        activeLabel: String? = null,
        lastError: String? = null,
        manuallyStopped: Boolean? = null
    ) {
        var shouldStartBroadcast = false
        synchronized(broadcastLock) {
            state?.let {
                stateOrdinal = it.ordinal
                // 2025-fix-v6: 同步状态到 VpnStateStore (跨进程持久化)
                // 这确保主进程恢复时可以直接读取真实状态，不依赖回调
                VpnStateStore.setActive(it == SingBoxService.ServiceState.RUNNING)
            }
            activeLabel?.let {
                this.activeLabel = it
                // 2025-fix-v6: 同步 activeLabel 到 VpnStateStore
                VpnStateStore.setActiveLabel(it)
            }
            lastError?.let {
                this.lastError = it
                VpnStateStore.setLastError(it)
            }
            manuallyStopped?.let {
                this.manuallyStopped = it
                VpnStateStore.setManuallyStopped(it)
            }

            // 更新时间戳
            lastStateUpdateAtMs.set(SystemClock.elapsedRealtime())

            if (broadcasting) {
                broadcastPending = true
            } else {
                broadcasting = true
                shouldStartBroadcast = true
            }
        }

        if (shouldStartBroadcast) {
            drainBroadcastLoop()
        }
    }

    fun registerCallback(callback: ISingBoxServiceCallback) {
        callbacks.register(callback)
        runCatching {
            callback.onStateChanged(stateOrdinal, activeLabel, lastError, manuallyStopped)
        }
    }

    fun unregisterCallback(callback: ISingBoxServiceCallback) {
        callbacks.unregister(callback)
    }

    private fun drainBroadcastLoop() {
        while (true) {
            val snapshot = synchronized(broadcastLock) {
                broadcastPending = false
                StateSnapshot(stateOrdinal, activeLabel, lastError, manuallyStopped)
            }

            val n = callbacks.beginBroadcast()
            try {
                for (i in 0 until n) {
                    runCatching {
                        callbacks.getBroadcastItem(i)
                            .onStateChanged(snapshot.stateOrdinal, snapshot.activeLabel, snapshot.lastError, snapshot.manuallyStopped)
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }

            val shouldContinue = synchronized(broadcastLock) {
                if (broadcastPending) {
                    true
                } else {
                    broadcasting = false
                    false
                }
            }

            if (!shouldContinue) return
        }
    }

    private data class StateSnapshot(
        val stateOrdinal: Int,
        val activeLabel: String,
        val lastError: String,
        val manuallyStopped: Boolean
    )
}

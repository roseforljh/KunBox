package com.kunk.singbox.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.aidl.ISingBoxService
import com.kunk.singbox.aidl.ISingBoxServiceCallback
import com.kunk.singbox.service.SingBoxService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * SingBoxRemote - IPC 客户端
 *
 * 2025-fix-v6: 解决后台恢复后 UI 一直加载中的问题
 *
 * 核心改进:
 * 1. VpnStateStore 双重验证 - 回调失效时从 MMKV 读取真实状态
 * 2. 回调心跳检测 - 检测回调通道是否正常工作
 * 3. 强制重连机制 - rebind() 时直接断开再重连，不尝试复用
 * 4. 状态同步超时 - 如果回调超过阈值未更新，主动从 VpnStateStore 恢复
 *
 * 参考: NekoBox SagerConnection.kt
 */
object SingBoxRemote {
    private const val TAG = "SingBoxRemote"
    private const val RECONNECT_DELAY_MS = 300L
    private const val MAX_RECONNECT_ATTEMPTS = 5
    // 2025-fix-v6: 回调超时阈值，超过此时间未收到回调则认为回调通道失效
    private const val CALLBACK_TIMEOUT_MS = 10_000L
    // 2025-fix-v6: 强制从 VpnStateStore 同步的阈值
    private const val FORCE_STORE_SYNC_THRESHOLD_MS = 5_000L

    private val _state = MutableStateFlow(SingBoxService.ServiceState.STOPPED)
    val state: StateFlow<SingBoxService.ServiceState> = _state.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    private val _activeLabel = MutableStateFlow("")
    val activeLabel: StateFlow<String> = _activeLabel.asStateFlow()

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    private val _manuallyStopped = MutableStateFlow(false)
    val manuallyStopped: StateFlow<Boolean> = _manuallyStopped.asStateFlow()

    @Volatile
    private var service: ISingBoxService? = null

    @Volatile
    private var connectionActive = false

    @Volatile
    private var bound = false

    @Volatile
    private var callbackRegistered = false

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var contextRef: WeakReference<Context>? = null

    @Volatile
    private var reconnectAttempts = 0

    @Volatile
    private var lastSyncTimeMs = 0L

    // 2025-fix-v6: 上次收到回调的时间 (基于 SystemClock.elapsedRealtime)
    @Volatile
    private var lastCallbackReceivedAtMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val callback = object : ISingBoxServiceCallback.Stub() {
        override fun onStateChanged(state: Int, activeLabel: String?, lastError: String?, manuallyStopped: Boolean) {
            // 2025-fix-v6: 记录回调接收时间
            lastCallbackReceivedAtMs = SystemClock.elapsedRealtime()
            val st = SingBoxService.ServiceState.values().getOrNull(state)
                ?: SingBoxService.ServiceState.STOPPED
            updateState(st, activeLabel, lastError, manuallyStopped)
            Log.d(TAG, "Callback received: state=$st, activeLabel=$activeLabel")
        }
    }

    private fun updateState(
        st: SingBoxService.ServiceState,
        activeLabel: String? = null,
        lastError: String? = null,
        manuallyStopped: Boolean? = null
    ) {
        _state.value = st
        _isRunning.value = st == SingBoxService.ServiceState.RUNNING
        _isStarting.value = st == SingBoxService.ServiceState.STARTING
        activeLabel?.let { _activeLabel.value = it }
        lastError?.let { _lastError.value = it }
        manuallyStopped?.let { _manuallyStopped.value = it }
        lastSyncTimeMs = System.currentTimeMillis()
    }

    /**
     * 2025-fix-v6: 从 VpnStateStore 同步状态 (不依赖 AIDL 回调)
     * 当回调通道失效时，直接从 MMKV 读取跨进程共享的真实状态
     */
    private fun syncStateFromStore() {
        val isActive = VpnStateStore.getActive()
        val storedLabel = VpnStateStore.getActiveLabel()
        val storedError = VpnStateStore.getLastError()
        val storedManuallyStopped = VpnStateStore.isManuallyStopped()

        val newState = if (isActive) {
            SingBoxService.ServiceState.RUNNING
        } else {
            SingBoxService.ServiceState.STOPPED
        }

        Log.i(TAG, "syncStateFromStore: isActive=$isActive, label=$storedLabel")
        updateState(newState, storedLabel, storedError, storedManuallyStopped)
    }

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            Log.w(TAG, "Binder died, performing NekoBox-style immediate reconnect")
            service = null
            callbackRegistered = false

            mainHandler.post {
                val ctx = contextRef?.get()
                if (ctx != null && !SagerConnection_restartingApp) {
                    disconnect(ctx)
                    connect(ctx)
                }
            }
        }
    }

    @Volatile
    private var SagerConnection_restartingApp = false

    private fun cleanupConnection() {
        runCatching { binder?.unlinkToDeath(deathRecipient, 0) }
        binder = null
        service = null
        bound = false
        callbackRegistered = false
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "Service connected")
            this@SingBoxRemote.binder = binder
            reconnectAttempts = 0

            runCatching { binder?.linkToDeath(deathRecipient, 0) }

            val s = ISingBoxService.Stub.asInterface(binder)
            service = s
            bound = true

            if (s != null && !callbackRegistered) {
                runCatching {
                    s.registerCallback(callback)
                    callbackRegistered = true
                }
            }

            syncStateFromService(s)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Service disconnected")
            unregisterCallback()
            service = null
            bound = false

            val ctx = contextRef?.get()
            if (ctx != null && hasSystemVpn(ctx)) {
                Log.i(TAG, "System VPN still active, keeping current state and reconnecting")
                scheduleReconnect()
            } else {
                updateState(SingBoxService.ServiceState.STOPPED, "", "", false)
            }
        }
    }

    private fun unregisterCallback() {
        val s = service
        if (s != null && callbackRegistered) {
            runCatching { s.unregisterCallback(callback) }
        }
        callbackRegistered = false
    }

    private fun syncStateFromService(s: ISingBoxService?) {
        if (s == null) return
        runCatching {
            val st = SingBoxService.ServiceState.values().getOrNull(s.state)
                ?: SingBoxService.ServiceState.STOPPED
            updateState(st, s.activeLabel.orEmpty(), s.lastError.orEmpty(), s.isManuallyStopped)
            Log.i(TAG, "State synced: $st, running=${_isRunning.value}")
        }.onFailure {
            Log.e(TAG, "Failed to sync state from service", it)
        }
    }

    private fun hasSystemVpn(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val cm = context.getSystemService(ConnectivityManager::class.java)
                cm?.allNetworks?.any { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@any false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } == true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check system VPN", e)
            false
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnect attempts reached")
            return
        }

        val ctx = contextRef?.get() ?: return
        reconnectAttempts++
        val delay = RECONNECT_DELAY_MS * reconnectAttempts

        mainHandler.postDelayed({
            if (!bound && contextRef?.get() != null) {
                Log.i(TAG, "Reconnect attempt #$reconnectAttempts")
                doBindService(ctx)
            }
        }, delay)
    }

    private fun doBindService(context: Context) {
        val intent = Intent(context, SingBoxIpcService::class.java)
        runCatching {
            context.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.onFailure {
            Log.e(TAG, "Failed to bind service", it)
        }
    }

    fun connect(context: Context) {
        if (connectionActive) {
            Log.d(TAG, "connect: already active, skip")
            return
        }
        connectionActive = true
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0
        doBindService(context)
    }

    fun disconnect(context: Context) {
        unregisterCallback()
        if (connectionActive) {
            runCatching { context.applicationContext.unbindService(conn) }
        }
        connectionActive = false
        runCatching { binder?.unlinkToDeath(deathRecipient, 0) }
        binder = null
        service = null
        bound = false
    }

    fun ensureBound(context: Context) {
        contextRef = WeakReference(context.applicationContext)

        if (connectionActive && bound && service != null) {
            val isAlive = runCatching { service?.state }.isSuccess
            if (isAlive) return

            Log.w(TAG, "Service connection stale, rebinding...")
        }

        if (!connectionActive) {
            connect(context)
        } else if (!bound || service == null) {
            disconnect(context)
            connect(context)
        }
    }

    /**
     * v2rayNG 风格: 主动查询并同步状态
     * 用于 Activity onResume 时确保 UI 与服务状态一致
     *
     * 2025-fix-v5: 增强版 - 如果连接 stale 则强制重连
     */
    fun queryAndSyncState(context: Context): Boolean {
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        val s = service
        if (connectionActive && bound && s != null) {
            val synced = runCatching {
                syncStateFromService(s)
                true
            }.getOrDefault(false)

            if (synced) {
                Log.i(TAG, "queryAndSyncState: synced from service")
                return true
            } else {
                Log.w(TAG, "queryAndSyncState: sync failed, forcing reconnect")
                disconnect(context)
                connect(context)
                return false
            }
        }

        val ctx = contextRef?.get() ?: return false
        val hasVpn = hasSystemVpn(ctx)

        if (hasVpn && !connectionActive) {
            Log.i(TAG, "queryAndSyncState: system VPN active but not connected, connecting")
            connect(ctx)

            if (_state.value != SingBoxService.ServiceState.RUNNING) {
                updateState(SingBoxService.ServiceState.RUNNING)
            }
            return true
        }

        if (!hasVpn && _state.value == SingBoxService.ServiceState.RUNNING) {
            Log.i(TAG, "queryAndSyncState: no system VPN but state is RUNNING, correcting")
            updateState(SingBoxService.ServiceState.STOPPED)
        }

        if (!connectionActive) {
            connect(ctx)
        }

        return connectionActive
    }

    /**
     * NekoBox 风格: 强制重新绑定
     *
     * 2025-fix-v6: 增强版 - 直接断开再重连，不尝试复用 stale 连接
     * 这是解决后台恢复后 UI 卡住的关键修复
     */
    fun rebind(context: Context) {
        Log.i(TAG, "rebind: forcing disconnect -> connect cycle")
        contextRef = WeakReference(context.applicationContext)
        reconnectAttempts = 0

        // 2025-fix-v6: 不再尝试复用现有连接，直接断开再重连
        // 原来的逻辑是先检查连接有效性再决定是否重连，但这无法检测回调通道失效
        disconnect(context)
        connect(context)

        // 2025-fix-v6: 在重连期间，先从 VpnStateStore 恢复状态
        // 这样 UI 不会显示过时状态，即使回调还没到达
        syncStateFromStore()
    }

    /**
     * 2025-fix-v6: 检测回调通道是否超时
     * 如果超过阈值未收到回调，返回 true
     */
    fun isCallbackStale(): Boolean {
        if (lastCallbackReceivedAtMs == 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - lastCallbackReceivedAtMs
        return elapsed > CALLBACK_TIMEOUT_MS
    }

    /**
     * 2025-fix-v6: 强制从 VpnStateStore 同步状态
     * 用于 Activity onResume 时确保 UI 显示正确状态
     */
    fun forceStoreSync() {
        syncStateFromStore()
    }

    fun isBound(): Boolean = connectionActive && bound && service != null

    fun isConnectionActive(): Boolean = connectionActive

    fun unbind(context: Context) {
        disconnect(context)
    }

    fun getLastSyncAge(): Long = System.currentTimeMillis() - lastSyncTimeMs

    /**
     * 通知 :bg 进程 App 生命周期变化
     * 用于触发省电模式
     */
    fun notifyAppLifecycle(isForeground: Boolean) {
        val s = service
        if (s != null && connectionActive && bound) {
            runCatching {
                s.notifyAppLifecycle(isForeground)
                Log.d(TAG, "notifyAppLifecycle: isForeground=$isForeground")
            }.onFailure {
                Log.w(TAG, "Failed to notify app lifecycle", it)
            }
        } else {
            Log.d(TAG, "notifyAppLifecycle: service not connected, skip")
        }
    }
}

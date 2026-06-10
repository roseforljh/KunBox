package com.kunk.singbox.service.manager

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 */
class ScreenStateManager(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ScreenStateManager"
        private const val DOZE_EXIT_RECOVERY_DEBOUNCE_MS = 5_000L
        private const val ACTIVITY_RESUME_RECOVERY_MIN_AWAY_MS = 3_000L

        internal fun nextStartedActivityCount(current: Int, started: Boolean): Int {
            return if (started) current + 1 else (current - 1).coerceAtLeast(0)
        }

        internal fun isForegroundFromStartedActivityCount(count: Int): Boolean {
            return count > 0
        }
    }

    interface Callbacks {
        val isRunning: Boolean

        /**
         */
        fun notifyRemoteStateUpdate(force: Boolean)

        /**
         */
        fun requestCoreNetworkRecovery(reason: String, force: Boolean = false)
    }

    private var callbacks: Callbacks? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private var activityLifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var powerManager: BackgroundPowerManager? = null

    @Volatile private var lastDozeExitRecoveryAtMs: Long = 0L
    @Volatile private var screenOffAtMs: Long = 0L
    @Volatile private var appBackgroundAtMs: Long = 0L
    @Volatile private var startedActivityCount: Int = 0

    @Volatile var isScreenOn: Boolean = true
        private set
    @Volatile var isAppInForeground: Boolean = true
        private set

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    /**
     */
    fun setPowerManager(manager: BackgroundPowerManager?) {
        powerManager = manager
        Log.d(TAG, "PowerManager ${if (manager != null) "set" else "cleared"}")
    }

    /**
     */
    fun registerScreenStateReceiver() {
        try {
            if (screenStateReceiver != null) return

            screenStateReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_ON -> handleScreenOn()
                        Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                        Intent.ACTION_USER_PRESENT -> handleUserPresent()
                        PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> handleDeviceIdleModeChanged(ctx)
                    }
                }

                private fun handleScreenOn() {
                    Log.i(TAG, "Screen ON detected")
                    isScreenOn = true
                    screenOffAtMs = 0L

                    powerManager?.onScreenOn()
                }

                private fun handleScreenOff() {
                    Log.i(TAG, "Screen OFF detected")
                    isScreenOn = false
                    screenOffAtMs = SystemClock.elapsedRealtime()
                    powerManager?.onScreenOff()
                }

                private fun handleUserPresent() {
                    Log.i(TAG, "[Unlock] User unlocked device")
                }

                private fun handleDeviceIdleModeChanged(ctx: Context) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                        val isIdleMode = pm?.isDeviceIdleMode == true

                        if (isIdleMode) {
                            Log.i(TAG, "[Doze Enter] Device entering idle mode")
                            serviceScope.launch { handleDeviceIdle() }
                        } else {
                            Log.i(TAG, "[Doze Exit] Device exiting idle mode")
                            serviceScope.launch { handleDeviceWake() }
                        }
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                }
            }

            context.registerReceiver(screenStateReceiver, filter)
            Log.i(TAG, "Screen state receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen state receiver", e)
        }
    }

    /**
     */
    fun unregisterScreenStateReceiver() {
        try {
            screenStateReceiver?.let {
                context.unregisterReceiver(it)
                screenStateReceiver = null
                Log.i(TAG, "Screen state receiver unregistered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister screen state receiver", e)
        }
    }

    /**
     */
    @Suppress("CognitiveComplexMethod")
    fun registerActivityLifecycleCallbacks(application: Application?) {
        try {
            if (activityLifecycleCallbacks != null) return

            val app = application ?: return

            activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: android.app.Activity) {
                    startedActivityCount = nextStartedActivityCount(startedActivityCount, started = true)

                    if (!isAppInForeground) {
                        val wasInBackground = !isAppInForeground
                        isAppInForeground = isForegroundFromStartedActivityCount(startedActivityCount)
                        Log.i(TAG, "App returned to FOREGROUND (${activity.localClassName})")

                        val backgroundDuration = if (appBackgroundAtMs > 0) {
                            SystemClock.elapsedRealtime() - appBackgroundAtMs
                        } else 0L

                        if (wasInBackground && backgroundDuration >= ACTIVITY_RESUME_RECOVERY_MIN_AWAY_MS) {
                            val seconds = backgroundDuration / 1000
                            Log.i(TAG, "[ActivityResume] Background ${seconds}s, recovery delegated to PowerManager")
                        }

                        appBackgroundAtMs = 0L
                        callbacks?.notifyRemoteStateUpdate(true)
                    }
                }

                override fun onActivityResumed(activity: android.app.Activity) {}
                override fun onActivityPaused(activity: android.app.Activity) {}
                override fun onActivityStopped(activity: android.app.Activity) {
                    startedActivityCount = nextStartedActivityCount(startedActivityCount, started = false)
                    if (isAppInForeground && !isForegroundFromStartedActivityCount(startedActivityCount)) {
                        isAppInForeground = false
                        appBackgroundAtMs = SystemClock.elapsedRealtime()
                        Log.d(TAG, "App moved to BACKGROUND at $appBackgroundAtMs")
                    }
                }
                override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
                override fun onActivityDestroyed(activity: android.app.Activity) {}
                override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            }

            app.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
            Log.i(TAG, "Activity lifecycle callbacks registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register activity lifecycle callbacks", e)
        }
    }

    /**
     */
    fun unregisterActivityLifecycleCallbacks(application: Application?) {
        try {
            activityLifecycleCallbacks?.let { cb ->
                application?.unregisterActivityLifecycleCallbacks(cb)
                activityLifecycleCallbacks = null
                Log.i(TAG, "Activity lifecycle callbacks unregistered")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister activity lifecycle callbacks", e)
        }
    }

    /**
     */
    fun onAppBackground() {
        Log.i(TAG, "App moved to BACKGROUND")
        startedActivityCount = 0
        isAppInForeground = false
    }

    /**
     */
    private suspend fun handleDeviceIdle() {
        if (callbacks?.isRunning != true) return
        Log.i(TAG, "[Doze] Device idle, sleeping core")
        BoxWrapperManager.sleep()
    }

    /**
     */
    private suspend fun handleDeviceWake() {
        if (callbacks?.isRunning != true) return

        try {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastDozeExitRecoveryAtMs
            if (elapsed < DOZE_EXIT_RECOVERY_DEBOUNCE_MS) {
                Log.d(TAG, "[Doze] Wake recovery skipped (debounce)")
                callbacks?.notifyRemoteStateUpdate(true)
                return
            }

            lastDozeExitRecoveryAtMs = now

            Log.i(TAG, "[Doze] Device wake, request recovery")
            callbacks?.requestCoreNetworkRecovery(reason = "doze_exit", force = false)
            callbacks?.notifyRemoteStateUpdate(true)
        } catch (e: Exception) {
            Log.e(TAG, "[Doze] handleDeviceWake failed", e)
        }
    }

    fun cleanup() {
        unregisterScreenStateReceiver()
        callbacks = null
    }
}

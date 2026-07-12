package com.kunk.singbox.service.manager

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ScreenStateManager(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ScreenStateManager"

        internal fun nextStartedActivityCount(current: Int, started: Boolean): Int {
            return if (started) current + 1 else (current - 1).coerceAtLeast(0)
        }

        internal fun isForegroundFromStartedActivityCount(count: Int): Boolean {
            return count > 0
        }
    }

    interface Callbacks {
        val isRunning: Boolean

        fun notifyRemoteStateUpdate(force: Boolean)
    }

    private var callbacks: Callbacks? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private var activityLifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var powerManager: BackgroundPowerManager? = null

    @Volatile private var startedActivityCount: Int = 0

    @Volatile var isScreenOn: Boolean = true
        private set
    @Volatile var isAppInForeground: Boolean = true
        private set

    fun init(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    fun setPowerManager(manager: BackgroundPowerManager?) {
        powerManager = manager
        Log.d(TAG, "PowerManager ${if (manager != null) "set" else "cleared"}")
    }

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

                    powerManager?.onScreenOn()
                }

                private fun handleScreenOff() {
                    Log.i(TAG, "Screen OFF detected")
                    isScreenOn = false
                    powerManager?.onScreenOff()
                }

                private fun handleUserPresent() {
                    Log.i(TAG, "[Unlock] User unlocked device")
                }

                private fun handleDeviceIdleModeChanged(ctx: Context) {
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

            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }

            context.registerReceiver(screenStateReceiver, filter)
            Log.i(TAG, "Screen state receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen state receiver", e)
        }
    }

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

    @Suppress("CognitiveComplexMethod")
    fun registerActivityLifecycleCallbacks(application: Application?) {
        try {
            if (activityLifecycleCallbacks != null) return

            val app = application ?: return

            activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: android.app.Activity) {
                    startedActivityCount = nextStartedActivityCount(startedActivityCount, started = true)

                    if (!isAppInForeground) {
                        isAppInForeground = isForegroundFromStartedActivityCount(startedActivityCount)
                        Log.i(TAG, "App returned to FOREGROUND (${activity.localClassName})")
                        callbacks?.notifyRemoteStateUpdate(true)
                    }
                }

                override fun onActivityResumed(activity: android.app.Activity) {}
                override fun onActivityPaused(activity: android.app.Activity) {}
                override fun onActivityStopped(activity: android.app.Activity) {
                    startedActivityCount = nextStartedActivityCount(startedActivityCount, started = false)
                    if (isAppInForeground && !isForegroundFromStartedActivityCount(startedActivityCount)) {
                        isAppInForeground = false
                        Log.d(TAG, "App moved to BACKGROUND")
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

    fun onAppBackground() {
        Log.i(TAG, "App moved to BACKGROUND")
        startedActivityCount = 0
        isAppInForeground = false
    }

    private suspend fun handleDeviceIdle() {
        if (callbacks?.isRunning != true) return
        Log.i(TAG, "[Doze] Device idle, pausing core")
        BoxWrapperManager.pause()
    }

    private suspend fun handleDeviceWake() {
        if (callbacks?.isRunning != true) return

        Log.i(TAG, "[Doze] Device wake, waking core")
        BoxWrapperManager.wake()
        callbacks?.notifyRemoteStateUpdate(true)
    }

    fun cleanup() {
        unregisterScreenStateReceiver()
        callbacks = null
    }
}

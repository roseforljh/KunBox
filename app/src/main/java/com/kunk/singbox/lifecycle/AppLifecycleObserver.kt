package com.kunk.singbox.lifecycle

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.model.BackgroundPowerSavingDelay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppLifecycleObserver : DefaultLifecycleObserver {
    private const val TAG = "AppLifecycleObserver"
    private const val ACTION_KILL_PROCESS = "com.kunk.singbox.ACTION_KILL_MAIN_PROCESS"
    private const val REQUEST_CODE_KILL = 19527

    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    @Volatile
    private var isRegistered = false

    @Volatile
    private var backgroundTimeoutMs: Long = BackgroundPowerSavingDelay.MINUTES_30.delayMs

    @Volatile
    private var backgroundAtMs: Long = 0L

    private var appContext: Context? = null

    fun register(context: Context) {
        if (isRegistered) return
        isRegistered = true
        appContext = context.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        Log.i(TAG, "AppLifecycleObserver registered with ProcessLifecycleOwner")
    }

    fun setBackgroundTimeout(timeoutMs: Long) {
        backgroundTimeoutMs = timeoutMs
        val displayMin = if (timeoutMs == Long.MAX_VALUE) "NEVER" else "${timeoutMs / 1000 / 60}min"
        Log.i(TAG, "Background timeout set to $displayMin")
    }

    override fun onStart(owner: LifecycleOwner) {
        Log.i(TAG, "App entered FOREGROUND")
        _isAppInForeground.value = true
        backgroundAtMs = 0L

        cancelKillAlarm()

        SingBoxRemote.notifyAppLifecycle(isForeground = true)
    }

    override fun onStop(owner: LifecycleOwner) {
        Log.i(TAG, "App entered BACKGROUND")
        _isAppInForeground.value = false
        backgroundAtMs = SystemClock.elapsedRealtime()

        SingBoxRemote.notifyAppLifecycle(isForeground = false)

        scheduleKillAlarm()
    }

    private fun scheduleKillAlarm() {
        if (backgroundTimeoutMs == Long.MAX_VALUE) {
            Log.d(TAG, "Power saving disabled, skip scheduling kill alarm")
            return
        }

        if (!VpnStateStore.getActive()) {
            Log.d(TAG, "VPN not running (VpnStateStore), skip scheduling kill alarm")
            return
        }

        val context = appContext ?: return

        cancelKillAlarm()

        val intent = Intent(context, KillProcessReceiver::class.java).apply {
            action = ACTION_KILL_PROCESS
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_KILL,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAtMillis = SystemClock.elapsedRealtime() + backgroundTimeoutMs

        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAtMillis,
            pendingIntent
        )

        Log.i(TAG, "Scheduled kill alarm in ${backgroundTimeoutMs / 1000 / 60}min")
    }

    private fun cancelKillAlarm() {
        val context = appContext ?: return
        val intent = Intent(context, KillProcessReceiver::class.java).apply {
            action = ACTION_KILL_PROCESS
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_KILL,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        if (pendingIntent != null) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "Cancelled pending kill alarm")
        }
    }

    class KillProcessReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION_KILL_PROCESS) return

            if (!_isAppInForeground.value && VpnStateStore.getActive()) {
                Log.i(TAG, ">>> Kill alarm fired, killing main process to save power")
                Log.i(TAG, ">>> VPN will continue running in :bg process")
                android.os.Process.killProcess(android.os.Process.myPid())
            } else {
                Log.d(
                    TAG,
                    "Kill alarm fired but conditions not met " +
                        "(fg=${_isAppInForeground.value}, vpn=${VpnStateStore.getActive()})"
                )
            }
        }
    }
}

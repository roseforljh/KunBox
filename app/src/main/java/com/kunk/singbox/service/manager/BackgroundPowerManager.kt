package com.kunk.singbox.service.manager

import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.repository.LogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BackgroundPowerManager(
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "BackgroundPowerManager"

        const val DEFAULT_BACKGROUND_THRESHOLD_MS = 30 * 60 * 1000L

        const val MIN_THRESHOLD_MS = 5 * 60 * 1000L

        const val MAX_THRESHOLD_MS = 2 * 60 * 60 * 1000L

        internal fun remainingPowerSavingDelayMs(
            thresholdMs: Long,
            userAwayAtMs: Long,
            nowMs: Long
        ): Long {
            if (thresholdMs == Long.MAX_VALUE || userAwayAtMs <= 0L) return thresholdMs
            val awayDurationMs = (nowMs - userAwayAtMs).coerceAtLeast(0L)
            return (thresholdMs - awayDurationMs).coerceAtLeast(0L)
        }
    }

    enum class PowerMode {
        NORMAL,
        POWER_SAVING
    }

    interface Callbacks {
        fun suspendNonEssentialProcesses()

        fun resumeNonEssentialProcesses()
    }

    private var callbacks: Callbacks? = null
    private var backgroundThresholdMs: Long = DEFAULT_BACKGROUND_THRESHOLD_MS
    private var powerSavingJob: Job? = null
    private val powerStateLock = Any()

    @Volatile
    private var currentMode: PowerMode = PowerMode.NORMAL

    @Volatile
    private var userAwayAtMs: Long = 0L

    @Volatile
    private var isAppInBackground: Boolean = false

    @Volatile
    private var isScreenOff: Boolean = false

    @Volatile
    private var backgroundStartTimeMs: Long = 0L

    private val logRepo by lazy { LogRepository.getInstance() }

    private fun logState(message: String) {
        Log.i(TAG, message)
        runCatching { logRepo.addLog("INFO [Power] $message") }
    }

    val powerMode: PowerMode get() = currentMode

    val isPowerSaving: Boolean get() = currentMode == PowerMode.POWER_SAVING

    private val isUserAway: Boolean get() = isAppInBackground || isScreenOff

    fun init(callbacks: Callbacks, thresholdMs: Long = DEFAULT_BACKGROUND_THRESHOLD_MS) {
        this.callbacks = callbacks
        this.backgroundThresholdMs = if (thresholdMs == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            thresholdMs.coerceIn(MIN_THRESHOLD_MS, MAX_THRESHOLD_MS)
        }
        val thresholdDisplay = if (backgroundThresholdMs == Long.MAX_VALUE) "NEVER" else "${backgroundThresholdMs / 1000 / 60}min"
        Log.i(TAG, "BackgroundPowerManager initialized (threshold=$thresholdDisplay)")
    }

    fun setThreshold(thresholdMs: Long) {
        backgroundThresholdMs = if (thresholdMs == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            thresholdMs.coerceIn(MIN_THRESHOLD_MS, MAX_THRESHOLD_MS)
        }
        val thresholdDisplay = if (backgroundThresholdMs == Long.MAX_VALUE) "NEVER" else "${backgroundThresholdMs / 1000 / 60}min"
        Log.i(TAG, "Threshold updated to $thresholdDisplay")
        if (isUserAway) {
            schedulePowerSaving()
        }
    }

    fun onAppBackground() {
        if (isAppInBackground) return
        isAppInBackground = true
        backgroundStartTimeMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "[IPC] App entered background at $backgroundStartTimeMs")
        evaluateUserPresence()
    }

    fun onAppForeground() {
        if (!isAppInBackground) {
            logState("[IPC] App foreground ignored: state mismatch (isAppInBackground=false)")
            return
        }

        val now = SystemClock.elapsedRealtime()
        val backgroundDuration = if (backgroundStartTimeMs > 0) {
            now - backgroundStartTimeMs
        } else {
            0L
        }
        isAppInBackground = false
        logState("[IPC] App returned to foreground after ${backgroundDuration / 1000}s")
        backgroundStartTimeMs = 0L
        evaluateUserPresence()
    }

    fun onScreenOff() {
        if (isScreenOff) return
        isScreenOff = true
        Log.i(TAG, "[Screen] Screen turned OFF")
        evaluateUserPresence()
    }

    fun onScreenOn() {
        if (!isScreenOff) return

        isScreenOff = false
        logState("[Screen] Screen turned ON")
        evaluateUserPresence()
    }

    private fun evaluateUserPresence() {
        if (isUserAway) {
            val thresholdDisplay = synchronized(powerStateLock) {
                if (userAwayAtMs != 0L) {
                    null
                } else {
                    userAwayAtMs = SystemClock.elapsedRealtime()
                    if (backgroundThresholdMs == Long.MAX_VALUE) {
                        "NEVER"
                    } else {
                        "${backgroundThresholdMs / 1000 / 60}min"
                    }
                }
            }
            if (thresholdDisplay != null) {
                Log.i(
                    TAG,
                    "User away (background=$isAppInBackground, " +
                        "screenOff=$isScreenOff), threshold=$thresholdDisplay"
                )
            }
            schedulePowerSaving()
            return
        }

        val awayDuration = synchronized(powerStateLock) {
            powerSavingJob?.cancel()
            powerSavingJob = null
            val duration = userAwayAtMs.takeIf { it > 0L }?.let {
                SystemClock.elapsedRealtime() - it
            }
            userAwayAtMs = 0L
            duration
        }
        if (awayDuration != null) {
            Log.i(TAG, "User returned after ${awayDuration / 1000}s")
        }
        exitPowerSavingMode()
    }

    private fun schedulePowerSaving() {
        val scheduledJob = synchronized(powerStateLock) {
            powerSavingJob?.cancel()
            powerSavingJob = null
            if (!isUserAway || backgroundThresholdMs == Long.MAX_VALUE || currentMode == PowerMode.POWER_SAVING) {
                return
            }

            val remainingDelayMs = remainingPowerSavingDelayMs(
                thresholdMs = backgroundThresholdMs,
                userAwayAtMs = userAwayAtMs,
                nowMs = SystemClock.elapsedRealtime()
            )
            serviceScope.launch(start = CoroutineStart.LAZY) {
                delay(remainingDelayMs)
                val shouldEnterPowerSaving = synchronized(powerStateLock) {
                    if (powerSavingJob !== coroutineContext[Job]) {
                        false
                    } else {
                        powerSavingJob = null
                        isUserAway
                    }
                }
                if (shouldEnterPowerSaving) {
                    enterPowerSavingMode()
                }
            }.also { powerSavingJob = it }
        }
        scheduledJob.start()
    }

    private fun enterPowerSavingMode() {
        val activeCallbacks = synchronized(powerStateLock) {
            if (currentMode == PowerMode.POWER_SAVING) return
            currentMode = PowerMode.POWER_SAVING
            callbacks
        }
        activeCallbacks?.suspendNonEssentialProcesses()
        logState("Entered power saving mode")
    }

    private fun exitPowerSavingMode() {
        val activeCallbacks = synchronized(powerStateLock) {
            if (currentMode == PowerMode.NORMAL) return
            currentMode = PowerMode.NORMAL
            callbacks
        }
        activeCallbacks?.resumeNonEssentialProcesses()
        logState("Exited power saving mode")
    }

    fun forceEnterPowerSaving() {
        enterPowerSavingMode()
    }

    fun forceExitPowerSaving() {
        exitPowerSavingMode()
    }

    fun cleanup() {
        synchronized(powerStateLock) {
            powerSavingJob?.cancel()
            powerSavingJob = null
            currentMode = PowerMode.NORMAL
        }
        isAppInBackground = false
        isScreenOff = false
        userAwayAtMs = 0L
        backgroundStartTimeMs = 0L
        callbacks = null
        Log.i(TAG, "BackgroundPowerManager cleaned up")
    }

    fun getStats(): Map<String, Any> {
        return mapOf(
            "currentMode" to currentMode.name,
            "isAppInBackground" to isAppInBackground,
            "isScreenOff" to isScreenOff,
            "isUserAway" to isUserAway,
            "thresholdMin" to if (backgroundThresholdMs == Long.MAX_VALUE) {
                Long.MAX_VALUE
            } else {
                backgroundThresholdMs / 1000 / 60
            },
            "awayDurationSec" to if (userAwayAtMs > 0) {
                (SystemClock.elapsedRealtime() - userAwayAtMs) / 1000
            } else {
                0L
            },
            "backgroundDurationSec" to if (backgroundStartTimeMs > 0) {
                (SystemClock.elapsedRealtime() - backgroundStartTimeMs) / 1000
            } else {
                0L
            }
        )
    }
}

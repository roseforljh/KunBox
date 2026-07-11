package com.kunk.singbox.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.*
import com.kunk.singbox.ipc.VpnStateStore
import java.io.File
import java.util.concurrent.TimeUnit

class VpnKeepaliveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "VpnKeepaliveWorker"
        private const val WORK_NAME = "vpn_keepalive"

        private const val CHECK_INTERVAL_MINUTES = 15L
        private const val RUNNING_CONFIG_FILE = "running_config.json"

        internal fun existingWorkPolicyForSchedule(): ExistingPeriodicWorkPolicy {
            return ExistingPeriodicWorkPolicy.UPDATE
        }

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true) // 避免低电量时触发恢复任务
                .build()

            val workRequest = PeriodicWorkRequestBuilder<VpnKeepaliveWorker>(
                repeatInterval = CHECK_INTERVAL_MINUTES,
                repeatIntervalTimeUnit = TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                existingWorkPolicyForSchedule(),
                workRequest
            )

            Log.i(TAG, "VPN keepalive worker scheduled (interval: ${CHECK_INTERVAL_MINUTES}min)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "VPN keepalive worker cancelled")
        }

        @Suppress("DEPRECATION")
        private fun isCoreServiceAlive(context: Context, mode: VpnStateStore.CoreMode): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val expectedServiceName = when (mode) {
                VpnStateStore.CoreMode.VPN -> SingBoxService::class.java.name
                VpnStateStore.CoreMode.PROXY -> ProxyOnlyService::class.java.name
                else -> return false
            }
            val services = activityManager.getRunningServices(Int.MAX_VALUE) ?: return false
            return services.any { running ->
                running.service.className == expectedServiceName
            }
        }

        internal fun shouldAttemptRecovery(
            manuallyStopped: Boolean,
            mode: VpnStateStore.CoreMode,
            coreServiceAlive: Boolean,
            runningConfigUsable: Boolean
        ): Boolean {
            return !manuallyStopped &&
                mode != VpnStateStore.CoreMode.NONE &&
                !coreServiceAlive &&
                runningConfigUsable
        }

        internal fun shouldClearStaleRecoveryState(
            manuallyStopped: Boolean,
            mode: VpnStateStore.CoreMode,
            coreServiceAlive: Boolean,
            runningConfigUsable: Boolean
        ): Boolean {
            return !manuallyStopped &&
                mode != VpnStateStore.CoreMode.NONE &&
                !coreServiceAlive &&
                !runningConfigUsable
        }

        internal fun shouldClearAfterRecoveryFailure(
            runAttemptCount: Int,
            foregroundStartDenied: Boolean
        ): Boolean {
            return foregroundStartDenied || runAttemptCount >= 3
        }

        private fun isForegroundStartDenied(error: Exception): Boolean {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                error.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
        }

        private fun isRunningConfigUsable(file: File): Boolean {
            return isRunningConfigUsable(
                exists = file.exists(),
                isFile = file.isFile,
                canRead = file.canRead(),
                length = file.length()
            )
        }

        internal fun isRunningConfigUsable(
            exists: Boolean,
            isFile: Boolean,
            canRead: Boolean,
            length: Long
        ): Boolean {
            return exists && isFile && canRead && length > 0L
        }
    }

    override suspend fun doWork(): Result {
        return try {
            performKeepaliveCheck()
        } catch (e: Exception) {
            handleKeepaliveError(e)
        }
    }

    private fun performKeepaliveCheck(): Result {
        val isManuallyStopped = VpnStateStore.isManuallyStopped()
        val currentMode = VpnStateStore.getMode()
        if (isManuallyStopped || currentMode == VpnStateStore.CoreMode.NONE) {
            return Result.success()
        }

        val coreServiceAlive = isCoreServiceAlive(applicationContext, currentMode)
        val runningConfigFile = applicationContext.filesDir.resolve(RUNNING_CONFIG_FILE)
        val runningConfigUsable = isRunningConfigUsable(runningConfigFile)

        return when {
            shouldClearStaleRecoveryState(isManuallyStopped, currentMode, coreServiceAlive, runningConfigUsable) -> {
                Log.w(TAG, "Recovery skipped: running config is missing or unreadable")
                clearStaleRecoveryState("VPN recovery skipped: running config is missing or unreadable")
                Result.success()
            }
            shouldAttemptRecovery(isManuallyStopped, currentMode, coreServiceAlive, runningConfigUsable) -> {
                Log.w(TAG, "Detected core service died unexpectedly, attempting recovery...")
                val recovered = attemptVpnRecovery(currentMode, runningConfigFile.absolutePath)
                handleRecoveryResult(recovered)
            }
            else -> Result.success()
        }
    }

    private fun handleRecoveryResult(recovered: Boolean): Result {
        if (recovered) {
            return Result.success()
        }

        if (VpnStateStore.getMode() == VpnStateStore.CoreMode.NONE) {
            return Result.failure()
        }

        if (shouldClearAfterRecoveryFailure(runAttemptCount = runAttemptCount, foregroundStartDenied = false)) {
            clearStaleRecoveryState("VPN recovery failed after retries")
            return Result.failure()
        }

        return Result.retry()
    }

    private fun handleKeepaliveError(error: Exception): Result {
        Log.e(TAG, "VPN keepalive check failed", error)
        return if (runAttemptCount < 3) {
            Result.retry()
        } else {
            Result.failure()
        }
    }

    private fun attemptVpnRecovery(mode: VpnStateStore.CoreMode, runningConfigPath: String): Boolean {
        return try {
            Log.i(TAG, "Attempting to recover VPN service (mode: $mode)...")

            val intent = when (mode) {
                VpnStateStore.CoreMode.VPN -> {
                    Intent(applicationContext, SingBoxService::class.java).apply {
                        action = SingBoxService.ACTION_START
                        putExtra(SingBoxService.EXTRA_CONFIG_PATH, runningConfigPath)
                    }
                }
                VpnStateStore.CoreMode.PROXY -> {
                    Intent(applicationContext, ProxyOnlyService::class.java).apply {
                        action = ProxyOnlyService.ACTION_START
                        putExtra(ProxyOnlyService.EXTRA_CONFIG_PATH, runningConfigPath)
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown mode: $mode, skip recovery")
                    return false
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            Log.i(TAG, "VPN service recovery triggered successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "VPN recovery failed", e)
            if (isForegroundStartDenied(e)) {
                clearStaleRecoveryState("VPN recovery blocked by Android background service restrictions")
            }
            false
        }
    }

    private fun clearStaleRecoveryState(reason: String) {
        VpnTileService.persistVpnState(false)
        VpnTileService.persistVpnPending("")
        VpnStateStore.clearRuntimeState()
        VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
        VpnStateStore.setLastError(reason)
    }
}

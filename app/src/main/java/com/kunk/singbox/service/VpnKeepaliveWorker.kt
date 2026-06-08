package com.kunk.singbox.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 *
 *
 */
class VpnKeepaliveWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "VpnKeepaliveWorker"
        private const val WORK_NAME = "vpn_keepalive"

        private const val CHECK_INTERVAL_MINUTES = 15L
        private const val RUNNING_CONFIG_FILE = "running_config.json"

        /**
         *
         */
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
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )

            Log.i(TAG, "VPN keepalive worker scheduled (interval: ${CHECK_INTERVAL_MINUTES}min)")
        }

        /**
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "VPN keepalive worker cancelled")
        }

        /**
         */
        private fun isBackgroundProcessAlive(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val processes = activityManager.runningAppProcesses ?: return false

            val bgProcessName = "${context.packageName}:bg"
            return processes.any { it.processName == bgProcessName }
        }
    }

    @Suppress("NestedBlockDepth", "ReturnCount")
    override suspend fun doWork(): Result {
        return try {

            val isManuallyStopped = VpnStateStore.isManuallyStopped()
            if (isManuallyStopped) {
                return Result.success()
            }
            val currentMode = VpnStateStore.getMode()
            if (currentMode == VpnStateStore.CoreMode.NONE) {
                return Result.success()
            }

            val bgProcessAlive = isBackgroundProcessAlive(applicationContext)

            if (!bgProcessAlive) {
                Log.w(TAG, "Detected background process died unexpectedly, attempting recovery...")
                val recovered = attemptVpnRecovery(currentMode)
                if (!recovered) {
                    return if (runAttemptCount < 3) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "VPN keepalive check failed", e)

            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    /**
     *
     */
    private suspend fun attemptVpnRecovery(mode: VpnStateStore.CoreMode): Boolean {
        return try {
            Log.i(TAG, "Attempting to recover VPN service (mode: $mode)...")

            val settingsRepo = SettingsRepository.getInstance(applicationContext)
            val settings = settingsRepo.settings.first()

            val intent = when (mode) {
                VpnStateStore.CoreMode.VPN -> {
                    Intent(applicationContext, SingBoxService::class.java).apply {
                        action = SingBoxService.ACTION_START
                        putExtra(
                            SingBoxService.EXTRA_CONFIG_PATH,
                            applicationContext.filesDir.resolve(RUNNING_CONFIG_FILE).absolutePath
                        )
                    }
                }
                VpnStateStore.CoreMode.PROXY -> {
                    Intent(applicationContext, ProxyOnlyService::class.java).apply {
                        action = ProxyOnlyService.ACTION_START
                        putExtra(
                            ProxyOnlyService.EXTRA_CONFIG_PATH,
                            applicationContext.filesDir.resolve(RUNNING_CONFIG_FILE).absolutePath
                        )
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
            false
        }
    }
}

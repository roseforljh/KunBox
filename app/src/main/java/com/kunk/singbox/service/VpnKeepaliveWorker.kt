package com.kunk.singbox.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.service.manager.RecoveryPolicy
import com.kunk.singbox.service.manager.VpnRecoveryManager
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

        /**
         * KEEP：已有周期任务时不重置计时。
         * 冷启动/频繁进 App 若用 UPDATE + initialDelay，会把下次检查不断推后 15 分钟。
         */
        internal fun existingWorkPolicyForSchedule(): ExistingPeriodicWorkPolicy {
            return ExistingPeriodicWorkPolicy.KEEP
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
                // 仅首次 enqueue 生效；KEEP 下后续 schedule 不会用它重置周期
                .setInitialDelay(CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                existingWorkPolicyForSchedule(),
                workRequest
            )

            Log.i(TAG, "VPN keepalive worker scheduled (interval: ${CHECK_INTERVAL_MINUTES}min, policy=KEEP)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "VPN keepalive worker cancelled")
        }

        private fun isCoreServiceAlive(context: Context, mode: VpnStateStore.CoreMode): Boolean {
            return VpnRecoveryManager.isCoreServiceAlive(context, mode)
        }

        internal fun shouldAttemptRecovery(
            manuallyStopped: Boolean,
            mode: VpnStateStore.CoreMode,
            coreServiceAlive: Boolean,
            runningConfigUsable: Boolean
        ): Boolean {
            return RecoveryPolicy.shouldAttemptRecovery(manuallyStopped, mode, coreServiceAlive, runningConfigUsable)
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
                val recovered = attemptVpnRecovery()
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
            clearRuntimeAfterFailedRecovery("VPN recovery failed after retries")
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

    private fun attemptVpnRecovery(): Boolean {
        return VpnRecoveryManager.attemptOnce(applicationContext, source = "keepalive")
    }

    private fun clearStaleRecoveryState(reason: String) {
        VpnTileService.persistVpnState(false)
        VpnTileService.persistVpnPending("")
        VpnStateStore.clearRuntimeState()
        VpnStateStore.setMode(VpnStateStore.CoreMode.NONE)
        VpnStateStore.setLastError(reason)
    }

    /** 恢复失败只清运行态，保留 mode 意图，留给下一次触发源再试。 */
    private fun clearRuntimeAfterFailedRecovery(reason: String) {
        VpnTileService.persistVpnState(false)
        VpnTileService.persistVpnPending("")
        VpnStateStore.clearRuntimeState()
        VpnStateStore.setLastError(reason)
    }
}

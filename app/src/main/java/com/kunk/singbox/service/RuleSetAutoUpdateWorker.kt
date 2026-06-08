package com.kunk.singbox.service

import android.content.Context
import android.util.Log
import androidx.work.*
import com.kunk.singbox.repository.ConfigRepository
import com.kunk.singbox.repository.RuleSetRepository
import com.kunk.singbox.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 */
class RuleSetAutoUpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "RuleSetAutoUpdate"
        private const val WORK_NAME = "ruleset_global_auto_update"
        private const val MIN_INTERVAL_MINUTES = 15

        fun normalizeIntervalMinutes(intervalMinutes: Int): Int {
            return when {
                intervalMinutes <= 0 -> 0
                intervalMinutes < MIN_INTERVAL_MINUTES -> MIN_INTERVAL_MINUTES
                else -> intervalMinutes
            }
        }

        /**
         * @param context Context
         */
        fun schedule(context: Context, intervalMinutes: Int) {
            val workManager = WorkManager.getInstance(context)
            val normalizedInterval = normalizeIntervalMinutes(intervalMinutes)

            if (normalizedInterval <= 0) {

                workManager.cancelUniqueWork(WORK_NAME)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<RuleSetAutoUpdateWorker>(
                normalizedInterval.toLong(),
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.MINUTES
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )
        }

        /**
         */
        fun cancel(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(WORK_NAME)
        }

        /**
         */
        suspend fun rescheduleAll(context: Context) = withContext(Dispatchers.IO) {
            try {
                val settingsRepository = SettingsRepository.getInstance(context)
                val settings = settingsRepository.settings.first()

                if (settings.ruleSetAutoUpdateEnabled && settings.ruleSetAutoUpdateInterval > 0) {
                    schedule(context, settings.ruleSetAutoUpdateInterval)
                } else {
                    cancel(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule auto-update task", e)
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {

        try {
            val settingsRepository = SettingsRepository.getInstance(applicationContext)
            val ruleSetRepository = RuleSetRepository.getInstance(applicationContext)
            val configRepository = ConfigRepository(applicationContext)
            val settings = settingsRepository.settings.first()

            if (!settings.ruleSetAutoUpdateEnabled) {
                cancel(applicationContext)
                return@withContext Result.success()
            }

            val remoteRuleSets = configRepository.getAppliedRemoteRuleSets(settings)

            if (remoteRuleSets.isEmpty()) {
                return@withContext Result.success()
            }

            var successCount = 0
            var failCount = 0

            remoteRuleSets.forEach { ruleSet ->
                try {
                    val success = ruleSetRepository.prefetchRuleSet(
                        ruleSet = ruleSet,
                        forceUpdate = true,
                        allowNetwork = true
                    )
                    if (success) {
                        successCount++
                    } else {
                        failCount++
                        Log.w(TAG, "Failed to update rule set: ${ruleSet.tag}")
                    }
                } catch (e: Exception) {
                    failCount++
                    Log.e(TAG, "Error updating rule set: ${ruleSet.tag}", e)
                }
            }

            when {
                failCount == 0 -> Result.success()
                successCount > 0 -> Result.success()
                runAttemptCount < 3 -> Result.retry()
                else -> Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto-update failed", e)

            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}

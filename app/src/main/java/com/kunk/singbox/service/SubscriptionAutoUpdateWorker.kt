package com.kunk.singbox.service

import android.content.Context
import android.util.Log
import androidx.work.*
import com.kunk.singbox.model.ProfileType
import com.kunk.singbox.model.SubscriptionUpdateResult
import com.kunk.singbox.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class SubscriptionAutoUpdateWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "SubscriptionAutoUpdate"
        private const val WORK_NAME_PREFIX = "subscription_auto_update_"
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
        fun schedule(context: Context, profileId: String, intervalMinutes: Int) {
            val workManager = WorkManager.getInstance(context)
            val workName = "$WORK_NAME_PREFIX$profileId"
            val normalizedInterval = normalizeIntervalMinutes(intervalMinutes)

            if (normalizedInterval <= 0) {

                workManager.cancelUniqueWork(workName)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val inputData = Data.Builder()
                .putString("profile_id", profileId)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<SubscriptionAutoUpdateWorker>(
                normalizedInterval.toLong(),
                TimeUnit.MINUTES
            )
                .addTag(TAG)
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.MINUTES
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.REPLACE,
                workRequest
            )
        }

        fun cancel(context: Context, profileId: String) {
            val workManager = WorkManager.getInstance(context)
            val workName = "$WORK_NAME_PREFIX$profileId"
            workManager.cancelUniqueWork(workName)
        }

        fun cancelAll(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelAllWorkByTag(TAG)
        }

        suspend fun rescheduleAll(context: Context) = withContext(Dispatchers.IO) {
            try {
                val configRepository = ConfigRepository.getInstance(context)
                val profiles = configRepository.profiles.first()

                profiles.forEach { profile ->
                    if (profile.type == ProfileType.Subscription &&
                        profile.enabled &&
                        profile.autoUpdateInterval > 0) {
                        schedule(context, profile.id, profile.autoUpdateInterval)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reschedule auto-update tasks", e)
            }
        }
    }

    @Suppress("CognitiveComplexMethod")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val profileId = inputData.getString("profile_id")

        if (profileId.isNullOrBlank()) {
            Log.e(TAG, "Profile ID is missing")
            return@withContext Result.failure()
        }

        try {
            val configRepository = ConfigRepository.getInstance(applicationContext)

            val profile = configRepository.profiles.first().find { it.id == profileId }
            if (profile == null) {
                Log.w(TAG, "Profile not found: $profileId, cancelling auto-update")
                cancel(applicationContext, profileId)
                return@withContext Result.failure()
            }

            if (!profile.enabled) {
                cancel(applicationContext, profileId)
                return@withContext Result.success()
            }

            if (profile.autoUpdateInterval <= 0) {
                cancel(applicationContext, profileId)
                return@withContext Result.success()
            }

            when (val result = configRepository.updateProfile(profileId)) {
                is SubscriptionUpdateResult.SuccessNoChanges,
                is SubscriptionUpdateResult.SuccessWithChanges -> Result.success()

                is SubscriptionUpdateResult.Failed -> {
                    Log.w(
                        TAG,
                        "Auto-update failed for profile: $profileId, " +
                            "profile=${result.profileName}, error=${result.error}"
                    )
                    if (runAttemptCount < 3) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto-update failed for profile: $profileId", e)

            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}

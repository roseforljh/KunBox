package com.kunk.singbox.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kunk.singbox.repository.RuleSetRepository

class RuleSetUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "RuleSetUpdateWorker"
        const val WORK_NAME = "rule_set_update_work"
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting background rule set update")
        return try {
            val repository = RuleSetRepository.getInstance(applicationContext)
            // Force update to check for new versions
            val success = repository.ensureRuleSetsReady(forceUpdate = true, allowNetwork = false) { progress ->
            }

            if (success) {
                Log.i(TAG, "Rule set update completed successfully")
                Result.success()
            } else {
                Log.w(TAG, "Rule set update finished with some failures")
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating rule sets", e)
            Result.success()
        }
    }
}

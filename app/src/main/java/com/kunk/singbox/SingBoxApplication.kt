package com.kunk.singbox

import android.app.ActivityManager
import android.app.Application
import android.net.ConnectivityManager
import android.os.Process
import androidx.work.Configuration
import com.kunk.singbox.lifecycle.AppLifecycleObserver
import com.kunk.singbox.repository.LogRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.service.RuleSetAutoUpdateWorker
import com.kunk.singbox.service.SubscriptionAutoUpdateWorker
import com.kunk.singbox.service.VpnKeepaliveWorker
import com.kunk.singbox.utils.DefaultNetworkListener
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SingBoxApplication : Application(), Configuration.Provider {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setDefaultProcessName(packageName)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()

        MMKV.initialize(this)

        LogRepository.init(this)
        applicationScope.launch {
            val settingsRepository = withContext(Dispatchers.IO) {
                SettingsRepository.getInstance(this@SingBoxApplication)
            }
            LogRepository.getInstance().setEnabled(settingsRepository.settings.value.debugLoggingEnabled)
            launch {
                settingsRepository.settings.collect { settings ->
                    LogRepository.getInstance().setEnabled(settings.debugLoggingEnabled)
                }
            }

            if (isMainProcess()) {
                AppLifecycleObserver.register(this@SingBoxApplication)
                try {
                    val settings = settingsRepository.settings.value
                    AppLifecycleObserver.setBackgroundTimeout(settings.backgroundPowerSavingDelay.delayMs)
                } catch (e: Exception) {
                    android.util.Log.w("SingBoxApp", "Failed to read power saving setting", e)
                }

                val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) {
                    DefaultNetworkListener.start(cm, this@SingBoxApplication) { network ->
                        android.util.Log.d("SingBoxApp", "Underlying network updated: $network")
                    }
                }

                SubscriptionAutoUpdateWorker.rescheduleAll(this@SingBoxApplication)
                RuleSetAutoUpdateWorker.rescheduleAll(this@SingBoxApplication)

                VpnKeepaliveWorker.schedule(this@SingBoxApplication)
            }
        }

        applicationScope.launch(Dispatchers.IO) {
            cleanupOrphanedTempFiles()
        }
    }

    private fun isMainProcess(): Boolean {
        val pid = Process.myPid()
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val processName = activityManager.runningAppProcesses?.find { it.pid == pid }?.processName
        return processName == packageName
    }

    /**
     */
    private fun cleanupOrphanedTempFiles() {
        try {
            val tempDir = java.io.File(cacheDir, "singbox_temp")
            if (!tempDir.exists() || !tempDir.isDirectory) return

            val cleaned = mutableListOf<String>()
            tempDir.listFiles()?.forEach { file ->

                if (file.name.startsWith("test_") || file.name.startsWith("batch_test_")) {
                    if (file.delete()) {
                        cleaned.add(file.name)
                    }
                }
            }

            if (cleaned.isNotEmpty()) {
                android.util.Log.i("SingBoxApp", "Cleaned ${cleaned.size} orphaned temp files: ${cleaned.take(5).joinToString()}")
            }
        } catch (e: Exception) {
            android.util.Log.w("SingBoxApp", "Failed to cleanup orphaned temp files", e)
        }
    }
}

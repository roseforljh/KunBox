package com.kunk.singbox.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kunk.singbox.repository.InstalledAppsRepository
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.shouldReloadInstalledAppsForPackageChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PackageRemovedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED && intent.action != Intent.ACTION_PACKAGE_REMOVED) return
        val packageName = intent.data?.packageName() ?: return
        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        if (!shouldReloadInstalledAppsForPackageChange(intent.action, isReplacing, packageName)) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                InstalledAppsRepository.getInstance(appContext).reloadApps()
                if (intent.action == Intent.ACTION_PACKAGE_REMOVED) {
                    SettingsRepository.getInstance(appContext).removePackageFromPerAppSettings(packageName)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun Uri.packageName(): String? = schemeSpecificPart.takeIf { it.isNotBlank() }
}

package com.kunk.singbox.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kunk.singbox.repository.InstalledAppsRepository
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
        if (!shouldReloadInstalledAppsForPackageChange(isReplacing, packageName)) return

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            InstalledAppsRepository.getInstance(context.applicationContext).reloadApps()
        }
    }

    private fun Uri.packageName(): String? = schemeSpecificPart.takeIf { it.isNotBlank() }
}

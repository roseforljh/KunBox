package com.kunk.singbox.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.kunk.singbox.manager.VpnServiceManager
import com.kunk.singbox.repository.InstalledAppsRepository
import com.kunk.singbox.repository.PerAppPackageSyncAction
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.resolvePerAppPackageSyncAction
import com.kunk.singbox.repository.shouldReloadInstalledAppsForPackageChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PackageRemovedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PackageRemovedReceiver"
        private val packageChangeScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
        )
    }

    @Suppress("CognitiveComplexMethod")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED && intent.action != Intent.ACTION_PACKAGE_REMOVED) return
        val packageName = intent.data?.packageName() ?: return
        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
        if (!shouldReloadInstalledAppsForPackageChange(intent.action, isReplacing, packageName)) return

        val pendingResult = goAsync()
        packageChangeScope.launch {
            try {
                val appContext = context.applicationContext
                val settingsRepository = SettingsRepository.getInstance(appContext)
                val syncAction = resolvePerAppPackageSyncAction(
                    action = intent.action,
                    isReplacing = isReplacing,
                    packageName = packageName,
                    isInstalled = isPackageInstalled(appContext, packageName)
                )
                when (syncAction) {
                    PerAppPackageSyncAction.ADD -> {
                        val update = settingsRepository.addPackageToCurrentPerAppRule(packageName).getOrThrow()
                        if (update.runtimeChanged) {
                            VpnServiceManager.applyPerAppRuleChangeIfRunning(appContext, update.revision)
                                .getOrThrow()
                        }
                    }
                    PerAppPackageSyncAction.REMOVE -> {
                        val update = settingsRepository.removePackageFromPerAppSettings(packageName).getOrThrow()
                        if (update.runtimeChanged) {
                            VpnServiceManager.applyPerAppRuleChangeIfRunning(appContext, update.revision)
                                .getOrThrow()
                        }
                    }
                    PerAppPackageSyncAction.NONE -> Unit
                }
                InstalledAppsRepository.getInstance(appContext).reloadApps()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(
                    TAG,
                    "Failed to synchronize package change: action=${intent.action}, package=$packageName",
                    e
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                context.packageManager.getApplicationInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun Uri.packageName(): String? = schemeSpecificPart.takeIf { it.isNotBlank() }
}

package com.kunk.singbox.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.nekohasekai.libbox.Libbox

/**
 *
 */
object VersionInfo {
    private const val TAG = "VersionInfo"

    /**
     *
     */
    fun getAppVersionName(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get app version name", e)
            "Unknown"
        }
    }

    /**
     */
    fun getAppVersionCode(context: Context): Long {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get app version code", e)
            0L
        }
    }

    /**
     */
    fun getSingBoxVersion(): String {
        return try {
            Libbox.version()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get sing-box version", e)
            "Unknown"
        } catch (e: NoClassDefFoundError) {
            Log.e(TAG, "Libbox class not found", e)
            "Not available"
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Libbox native library not loaded", e)
            "Not loaded"
        }
    }

    /**
     */
    fun getFormattedVersionInfo(context: Context): String {
        val appVersion = getAppVersionName(context)
        val appVersionCode = getAppVersionCode(context)
        val singBoxVersion = getSingBoxVersion()

        return buildString {
            appendLine("搴旂敤鐗堟湰: $appVersion ($appVersionCode)")
            appendLine("·呮牳鐗堟湰: $singBoxVersion")
        }.trimEnd()
    }
}

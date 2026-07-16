package com.kunk.singbox.service.manager

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.service.ProxyOnlyService
import com.kunk.singbox.service.SingBoxService
import java.io.File

/**
 * 单路恢复执行器：sticky 之外的恢复触发源（keepalive、冷启动）统一走这里。
 * 与 sticky 之间通过 VpnStateStore.tryClaimRecovery 跨进程互斥，
 * 服务端再用 EXTRA_RECOVERY 幂等兜底，保证"恢复只能一路"。
 */
object VpnRecoveryManager {
    private const val TAG = "VpnRecoveryManager"

    internal const val RUNNING_CONFIG_FILE = "running_config.json"
    internal const val RECOVERY_CLAIM_WINDOW_MS = 60_000L

    /** 冷启动恢复延迟：让 sticky 优先，互斥锁兜底。 */
    internal const val COLD_START_RECOVERY_DELAY_MS = 2_500L

    /**
     * 条件满足时发起一次恢复启动（用 running_config 原样拉起，不 CLEAN_CACHE、不重新生成配置）。
     *
     * @return true = 已发出恢复 START；false = 条件不满足 / 互斥被占 / 发起失败
     */
    fun attemptOnce(context: Context, source: String): Boolean {
        val mode = VpnStateStore.getMode()
        val manuallyStopped = VpnStateStore.isManuallyStopped()
        val configFile = File(context.filesDir, RUNNING_CONFIG_FILE)
        val configUsable = configFile.exists() && configFile.isFile && configFile.canRead() && configFile.length() > 0L
        val coreAlive = isCoreServiceAlive(context, mode)
        val serviceClass = when (mode) {
            VpnStateStore.CoreMode.VPN -> SingBoxService::class.java
            VpnStateStore.CoreMode.PROXY -> ProxyOnlyService::class.java
            else -> null
        }

        if (serviceClass == null ||
            !RecoveryPolicy.shouldAttemptRecovery(manuallyStopped, mode, coreAlive, configUsable)
        ) {
            Log.d(
                TAG,
                "Recovery skipped ($source): manuallyStopped=$manuallyStopped, mode=$mode, " +
                    "coreAlive=$coreAlive, configUsable=$configUsable"
            )
            return false
        }

        if (!VpnStateStore.tryClaimRecovery(RECOVERY_CLAIM_WINDOW_MS)) {
            Log.i(TAG, "Recovery skipped ($source): another recovery was issued recently")
            return false
        }

        return issueRecoveryStart(context, serviceClass, configFile, source, mode)
    }

    private fun issueRecoveryStart(
        context: Context,
        serviceClass: Class<*>,
        configFile: File,
        source: String,
        mode: VpnStateStore.CoreMode
    ): Boolean {
        val intent = Intent(context, serviceClass).apply {
            action = ServiceStateHolder.ACTION_START
            putExtra(ServiceStateHolder.EXTRA_CONFIG_PATH, configFile.absolutePath)
            putExtra(ServiceStateHolder.EXTRA_RECOVERY, true)
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.i(TAG, "Recovery start issued ($source, mode=$mode)")
            true
        } catch (e: Exception) {
            // 失败（如后台 FGS 限制）不抹恢复意图，释放互斥，留给下一次触发源再试
            VpnStateStore.clearRecoveryClaim()
            Log.e(TAG, "Recovery start failed ($source)", e)
            false
        }
    }

    @Suppress("DEPRECATION")
    internal fun isCoreServiceAlive(context: Context, mode: VpnStateStore.CoreMode): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val expectedServiceName = when (mode) {
            VpnStateStore.CoreMode.VPN -> SingBoxService::class.java.name
            VpnStateStore.CoreMode.PROXY -> ProxyOnlyService::class.java.name
            else -> return false
        }
        val services = activityManager.getRunningServices(Int.MAX_VALUE) ?: return false
        return services.any { running ->
            running.service.className == expectedServiceName
        }
    }
}

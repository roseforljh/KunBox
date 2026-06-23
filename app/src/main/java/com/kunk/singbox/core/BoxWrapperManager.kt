package com.kunk.singbox.core

import android.util.Log
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.utils.PreciseLatencyTester

object BoxWrapperManager {
    private const val TAG = "BoxWrapperManager"

    enum class RecoveryMode {
        SOFT,
        HARD
    }

    @Volatile
    private var commandServer: CommandServer? = null

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _hasSelector = MutableStateFlow(false)
    val hasSelector: StateFlow<Boolean> = _hasSelector.asStateFlow()

    @Volatile
    private var lastResumeTimestamp: Long = 0L

    @Volatile
    private var lastResetNetworkTimestamp: Long = 0L
    private const val RESET_NETWORK_DEBOUNCE_MS = 500L

    /**
     */
    fun init(server: CommandServer): Boolean {
        return try {
            commandServer = server
            _isPaused.value = false
            _hasSelector.value = LibboxCompat.hasSelector()
            Log.i(TAG, "BoxWrapperManager initialized, hasSelector=${_hasSelector.value}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init BoxWrapperManager", e)
            commandServer = null
            false
        }
    }

    /**
     */
    fun release() {
        commandServer = null
        _isPaused.value = false
        _hasSelector.value = false
        Log.i(TAG, "BoxWrapperManager released")
    }

    fun isAvailable(): Boolean {
        return commandServer != null
    }

    /**
     */
    fun selectOutbound(nodeTag: String): Boolean {
        if (!isAvailable()) return false
        return try {
            val result = LibboxCompat.selectOutboundByTag(nodeTag)
            if (result) {
                Log.i(TAG, "selectOutbound($nodeTag) success")
            } else {
                Log.w(TAG, "selectOutbound($nodeTag) failed")
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "selectOutbound($nodeTag) failed: ${e.message}")
            false
        }
    }

    /**
     */
    fun getSelectedOutbound(): String? {
        if (!isAvailable()) return null
        return try {
            LibboxCompat.getSelectedOutbound()
        } catch (e: Exception) {
            Log.w(TAG, "getSelectedOutbound failed: ${e.message}")
            null
        }
    }

    /**
     */
    fun listOutbounds(): List<String> {
        if (!isAvailable()) return emptyList()
        return try {
            LibboxCompat.listOutboundsString()
                ?.split("\n")
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "listOutbounds failed: ${e.message}")
            emptyList()
        }
    }

    /**
     */
    fun hasSelector(): Boolean {
        if (!isAvailable()) return false
        return LibboxCompat.hasSelector()
    }

    /**
     */
    fun pause(): Boolean {
        if (!isAvailable()) return false
        return try {
            val invoked = LibboxCompat.pauseService()
            if (!invoked) return false
            _isPaused.value = true
            Log.i(TAG, "pause() success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "pause() failed: ${e.message}")
            false
        }
    }

    /**
     */
    fun resume(): Boolean {
        if (!isAvailable()) return false
        return try {
            val invoked = LibboxCompat.resumeService()
            if (!invoked) return false
            _isPaused.value = false
            lastResumeTimestamp = System.currentTimeMillis()
            Log.i(TAG, "resume() success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "resume() failed: ${e.message}")
            false
        }
    }

    fun isPausedNow(): Boolean {
        if (!isAvailable()) return false
        return try {
            LibboxCompat.isPaused()
        } catch (e: Exception) {
            _isPaused.value
        }
    }

    fun wasPausedRecently(thresholdMs: Long = 30_000L): Boolean {
        val timestamp = lastResumeTimestamp
        if (timestamp == 0L) return false
        return (System.currentTimeMillis() - timestamp) < thresholdMs
    }

    /**
     *
     */
    fun sleep(): Boolean {
        return pause()
    }

    /**
     *
     */
    fun wake(): Boolean {
        return resume()
    }

    /**
     *
     */
    fun wakeAndResetNetwork(source: String, force: Boolean = false): Boolean {
        return recoverNetwork(source = source, mode = RecoveryMode.SOFT, force = force)
    }

    fun recoverNetwork(source: String, mode: RecoveryMode, force: Boolean = false): Boolean {
        if (!isAvailable()) {
            Log.d(TAG, "[$source] recoverNetwork skipped (service not available)")
            return false
        }

        val now = System.currentTimeMillis()
        val elapsed = now - lastResetNetworkTimestamp

        if (!force && elapsed < RESET_NETWORK_DEBOUNCE_MS) {
            Log.d(TAG, "[$source] recoverNetwork skipped (debounce: ${elapsed}ms)")
            return true
        }

        val connCount = runCatching { getConnectionCount() }.getOrDefault(0)
        val needRecovery = runCatching { isNetworkRecoveryNeeded() }.getOrDefault(false)
        val hasActiveState = connCount > 0 || needRecovery || isPausedNow()
        val bypassIdleGuard = shouldBypassIdleGuard(source)
        if (!force && !hasActiveState && !bypassIdleGuard) {
            Log.d(
                TAG,
                "[$source] recoverNetwork skipped (no connections, " +
                    "recovery not needed, bypass=$bypassIdleGuard)"
            )
            return true
        }

        Log.d(
            TAG,
            "[$source] recoverNetwork proceed (mode=$mode force=$force " +
                "hasActiveState=$hasActiveState bypass=$bypassIdleGuard)"
        )

        lastResetNetworkTimestamp = now
        _isPaused.value = false
        lastResumeTimestamp = now

        return when (mode) {
            RecoveryMode.SOFT -> recoverNetworkSoft(source)
            RecoveryMode.HARD -> recoverNetworkHard(source)
        }
    }

    /**
     *
     *
     */
    suspend fun smartRecover(
        context: android.content.Context,
        source: String,
        skipProbe: Boolean = false
    ): SmartRecoveryResult {
        if (!isAvailable()) {
            Log.d(TAG, "[$source] smartRecover skipped (service not available)")
            return SmartRecoveryResult(RecoveryLevel.NONE, false, "service not available")
        }

        val startTime = System.currentTimeMillis()
        val effectiveSkipProbe = skipProbe || shouldSkipProbeLevel(source)

        // Level 1: PROBE
        if (!effectiveSkipProbe) {
            val probeResult = executeProbeLevel(context, source, startTime)
            if (probeResult != null) return probeResult
        } else {
            Log.i(TAG, "[$source] smartRecover: skip Level 1 (PROBE) due to untrusted source")
        }

        // Level 2: SELECTIVE
        val selectiveResult = executeSelectiveLevel(context, source, startTime)
        if (selectiveResult.success && selectiveResult.level == RecoveryLevel.SELECTIVE) {
            return selectiveResult
        }

        // Level 3: NUCLEAR
        return executeNuclearLevel(source, startTime, selectiveResult.closedConnections)
    }

    @Suppress("ReturnCount")
    private suspend fun executeProbeLevel(
        context: android.content.Context,
        source: String,
        startTime: Long
    ): SmartRecoveryResult? {
        Log.i(TAG, "[$source] smartRecover: Level 1 (PROBE)")
        val probeResult = ProbeManager.probeFirstSuccessViaVpnDetailed(context, timeoutMs = 1500L)

        if (probeResult.firstSuccess != null) {
            if (!probeTunnelViaLocalProxy(context)) {
                Log.w(TAG, "[$source] PROBE physical network ok, but tunnel check failed. Escalating to SELECTIVE")
                return null
            }
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "[$source] PROBE success (${probeResult.firstSuccess.latencyMs}ms), total: ${elapsed}ms")
            return SmartRecoveryResult(
                RecoveryLevel.PROBE,
                true,
                "VPN link healthy",
                probeLatencyMs = probeResult.firstSuccess.latencyMs
            )
        }

        if (probeResult.allFailedByBindPermission) {
            Log.w(TAG, "[$source] PROBE unavailable due to permission error, skip escalation")
            return SmartRecoveryResult(RecoveryLevel.PROBE, true, "probe unavailable by permission")
        }

        Log.w(TAG, "[$source] PROBE failed, escalating to SELECTIVE")
        return null
    }

    @Suppress("LongMethod")
    private suspend fun executeSelectiveLevel(
        context: android.content.Context,
        source: String,
        startTime: Long
    ): SmartRecoveryResult {
        Log.i(TAG, "[$source] smartRecover: Level 2 (SELECTIVE)")
        wake()

        val closedIdle = closeIdleConnections(maxIdleSeconds = 30)
        val shouldForceCloseTracked = source.equals("network_type_changed", ignoreCase = true)
        val closedTracked = if (shouldForceCloseTracked) closeAllTrackedConnections() else 0

        var autoRecoverOk: Boolean? = null
        if (shouldForceCloseTracked) {
            resetAllConnections(true)
            autoRecoverOk = recoverNetworkAuto()
        }
        resetNetwork()

        val closedCount = closedIdle + closedTracked
        Log.i(
            TAG,
            "[$source] SELECTIVE closedIdle=$closedIdle closedTracked=$closedTracked autoRecover=$autoRecoverOk"
        )

        if (!shouldTrustSelectiveProbeSuccess(source)) {
            Log.w(TAG, "[$source] SELECTIVE verify is untrusted for this source, escalating to NUCLEAR")
            return SmartRecoveryResult(
                RecoveryLevel.SELECTIVE,
                false,
                "untrusted selective verify signal",
                closedConnections = closedCount
            )
        }

        kotlinx.coroutines.delay(300)
        val verifyResult = ProbeManager.probeFirstSuccessViaVpnDetailed(context, timeoutMs = 1500L)

        val result = if (verifyResult.firstSuccess != null) {
            // 物理网络探测成功后，再验证隧道是否真正可用
            if (!probeTunnelViaLocalProxy(context)) {
                Log.w(
                    TAG,
                    "[$source] SELECTIVE physical verify ok, but tunnel still dead. Escalating to NUCLEAR"
                )
                SmartRecoveryResult(
                    RecoveryLevel.SELECTIVE,
                    false,
                    "tunnel verify failed",
                    closedConnections = closedCount
                )
            } else {
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(
                    TAG,
                    "[$source] SELECTIVE success, verify=${verifyResult.firstSuccess.latencyMs}ms, total: ${elapsed}ms"
                )
                SmartRecoveryResult(
                    RecoveryLevel.SELECTIVE,
                    true,
                    "SELECTIVE succeeded",
                    closedConnections = closedCount,
                    probeLatencyMs = verifyResult.firstSuccess.latencyMs
                )
            }
        } else if (verifyResult.allFailedByBindPermission) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.w(
                TAG,
                "[$source] SELECTIVE verify unavailable by permission, keep SELECTIVE result, total: ${elapsed}ms"
            )
            SmartRecoveryResult(
                RecoveryLevel.SELECTIVE,
                true,
                "verify unavailable by permission",
                closedConnections = closedCount
            )
        } else {
            Log.w(TAG, "[$source] SELECTIVE verify failed, escalating to NUCLEAR")
            SmartRecoveryResult(RecoveryLevel.SELECTIVE, false, "verify failed", closedCount)
        }

        return result
    }

    private fun executeNuclearLevel(source: String, startTime: Long, closedCount: Int): SmartRecoveryResult {
        Log.i(TAG, "[$source] smartRecover: Level 3 (NUCLEAR)")
        resetAllConnections(true)
        resetNetwork()
        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "[$source] NUCLEAR completed, total: ${elapsed}ms")
        return SmartRecoveryResult(
            level = RecoveryLevel.NUCLEAR,
            success = true,
            reason = "NUCLEAR completed",
            closedConnections = closedCount,
            needsKernelRestart = true
        )
    }

    /** 智能恢复等级 */
    enum class RecoveryLevel { NONE, PROBE, SELECTIVE, NUCLEAR }

    /** 智能恢复结果 */
    data class SmartRecoveryResult(
        val level: RecoveryLevel,
        val success: Boolean,
        val reason: String,
        val closedConnections: Int = 0,
        val probeLatencyMs: Long? = null,
        val needsKernelRestart: Boolean = false
    )

    /**
     * 通过本地代理端口探测 VPN 隧道是否真正可用
     */
    suspend fun probeTunnelViaLocalProxy(context: android.content.Context, timeoutMs: Long = 3000L): Boolean {
        if (!isAvailable()) {
            Log.d(TAG, "probeTunnelViaLocalProxy: Kernel is not available, skip")
            return false
        }
        return try {
            val settings = SettingsRepository.getInstance(context).settings.value
            val proxyPort = settings.proxyPort
            if (proxyPort <= 0) {
                Log.d(TAG, "probeTunnelViaLocalProxy: Proxy port is invalid ($proxyPort), skip tunnel probe")
                return true
            }
            val probeUrl = settings.latencyTestUrl.ifBlank { "https://www.google.com/generate_204" }
            val realTimeout = if (timeoutMs > 0) timeoutMs.toInt() else settings.latencyTestTimeout
            val result = PreciseLatencyTester.test(
                proxyPort = proxyPort,
                url = probeUrl,
                timeoutMs = realTimeout,
                warmup = false
            )
            Log.d(
                TAG,
                "probeTunnelViaLocalProxy: url=$probeUrl, " +
                    "latency=${result.latencyMs}ms, isSuccess=${result.isSuccess}"
            )
            result.isSuccess
        } catch (e: Exception) {
            Log.w(TAG, "probeTunnelViaLocalProxy failed with exception", e)
            false
        }
    }

    internal fun shouldSkipProbeLevel(source: String): Boolean {
        return source.equals("network_type_changed", ignoreCase = true)
    }

    internal fun shouldTrustSelectiveProbeSuccess(source: String): Boolean {
        return !source.equals("network_type_changed", ignoreCase = true)
    }

    /**
     */
    fun getUploadTotal(): Long {
        if (!isAvailable()) return -1L
        return try {
            LibboxCompat.getTrafficTotalUplink()
        } catch (e: Exception) {
            Log.w(TAG, "getUploadTotal failed: ${e.message}")
            -1L
        }
    }

    /**
     */
    fun getDownloadTotal(): Long {
        if (!isAvailable()) return -1L
        return try {
            LibboxCompat.getTrafficTotalDownlink()
        } catch (e: Exception) {
            Log.w(TAG, "getDownloadTotal failed: ${e.message}")
            -1L
        }
    }

    /**
     */
    fun resetTraffic(): Boolean {
        if (!isAvailable()) return false
        return try {
            val result = LibboxCompat.resetTrafficStats()
            Log.i(TAG, "resetTraffic() result=$result")
            result
        } catch (e: Exception) {
            Log.w(TAG, "resetTraffic() failed: ${e.message}")
            false
        }
    }

    /**
     */
    fun getConnectionCount(): Int {
        if (!isAvailable()) return 0
        return try {
            Libbox.getConnectionCount().toInt()
        } catch (e: Exception) {
            0
        }
    }

    /**
     */
    fun resetAllConnections(system: Boolean = true): Boolean {
        if (!isAvailable()) return false
        return try {
            Libbox.resetAllConnections(system)
            Log.i(TAG, "resetAllConnections($system) success")
            true
        } catch (e: Exception) {
            Log.w(TAG, "resetAllConnections failed: ${e.message}")
            LibboxCompat.resetAllConnections(system)
        }
    }

    /**
     */
    fun resetNetwork(): Boolean {
        if (!isAvailable()) return false
        return try {
            Libbox.resetAllConnections(false)
            Log.i(TAG, "resetNetwork() success (via resetAllConnections)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "resetNetwork() failed: ${e.message}")
            false
        }
    }

    /**
     */
    fun closeAllTrackedConnections(): Int {
        if (!isAvailable()) return 0
        return try {
            val count = Libbox.closeAllTrackedConnections().toInt()
            if (count > 0) {
                Log.i(TAG, "closeAllTrackedConnections: closed $count connections")
            }
            count
        } catch (e: Exception) {
            Log.w(TAG, "closeAllTrackedConnections failed: ${e.message}")
            0
        }
    }

    /**
     *
     */
    fun closeIdleConnections(maxIdleSeconds: Int = 30): Int {
        if (!isAvailable()) return 0
        return try {
            val method = Libbox::class.java.getMethod("closeIdleConnections", Long::class.javaPrimitiveType)
            val count = (method.invoke(null, maxIdleSeconds.toLong()) as Number).toInt()
            if (count > 0) {
                Log.i(TAG, "closeIdleConnections($maxIdleSeconds): closed $count connections")
            }
            count
        } catch (e: NoSuchMethodException) {

            Log.w(TAG, "closeIdleConnections not available in kernel: ${e.message}, fallback")
            closeAllTrackedConnections()
        } catch (e: Exception) {
            Log.w(TAG, "closeIdleConnections failed: ${e.message}, fallback to closeAllTrackedConnections")
            closeAllTrackedConnections()
        }
    }

    /**
     */
    fun getExtensionVersion(): String {
        return try {
            Libbox.getKunBoxVersion()
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     */
    fun getCommandServer(): CommandServer? {
        return commandServer
    }

    // ==================== Network Recovery (Fix loading issue after background resume) ====================

    /**
     * Auto network recovery - Recommended entry point
     * Automatically selects recovery strategy based on current state
     * @return true if recovery succeeded
     */
    fun recoverNetworkAuto(): Boolean {
        return try {
            Libbox.recoverNetworkAuto()
        } catch (e: Exception) {
            Log.w(TAG, "recoverNetworkAuto kernel call failed, fallback to SOFT", e)
            recoverNetwork(source = "recoverNetworkAuto-fallback", mode = RecoveryMode.SOFT, force = true)
        }
    }

    /**
     * Check if network recovery is needed
     */
    fun isNetworkRecoveryNeeded(): Boolean {
        return try {
            Libbox.checkNetworkRecoveryNeeded()
        } catch (e: Exception) {
            isPausedNow()
        }
    }

    private fun shouldBypassIdleGuard(source: String): Boolean {
        return when (source) {
            "app_foreground",
            "screen_on",
            "doze_exit",
            "network_type_changed" -> true

            else -> false
        }
    }

    private fun recoverNetworkSoft(source: String): Boolean {
        val forceTag = "[SOFT][$source]"
        return try {
            val wakeOk = wake()
            val resetOk = resetNetwork()
            val ok = wakeOk && resetOk
            Log.i(TAG, "$forceTag wake=$wakeOk resetNetwork=$resetOk")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "$forceTag failed", e)
            false
        }
    }

    private fun recoverNetworkHard(source: String): Boolean {
        val forceTag = "[HARD][$source]"
        return try {
            val wakeOk = wake()
            val closed = closeAllTrackedConnections()
            val resetConnOk = resetAllConnections(true)
            val resetOk = resetNetwork()
            val ok = wakeOk && resetConnOk && resetOk
            Log.i(
                TAG,
                "$forceTag wake=$wakeOk closed=$closed resetAllConnections=$resetConnOk resetNetwork=$resetOk"
            )
            ok
        } catch (e: Exception) {
            Log.e(TAG, "$forceTag failed", e)
            false
        }
    }

    // ==================== Main Traffic Protection ====================

    /**
     */
    fun notifyMainTrafficActive() {

        Log.d(TAG, "notifyMainTrafficActive not available in current core")
    }

    // ==================== Per-Outbound Traffic ====================

    /**
     *
     */
    fun getTrafficByOutbound(): Map<String, Pair<Long, Long>> {
        if (!isAvailable()) return emptyMap()
        return try {
            LibboxCompat.getTrafficByOutbound()
        } catch (e: Exception) {
            Log.w(TAG, "getTrafficByOutbound failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     */
    @Suppress("UNUSED_PARAMETER")
    fun closeConnectionsForApp(packageName: String): Int {

        Log.d(TAG, "closeConnectionsForApp not available in current core")
        return 0
    }
}

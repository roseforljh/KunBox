package com.kunk.singbox.ipc

import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.kunk.singbox.service.ServiceState
import com.kunk.singbox.model.VpnAppMode
import com.tencent.mmkv.MMKV
import java.io.File
import java.io.RandomAccessFile

internal class CrossProcessRuntimeStateLock(private val lockFile: File) {
    private val processLock = Any()

    fun <T> withLock(block: () -> T): T {
        return synchronized(processLock) {
            lockFile.parentFile?.let { directory ->
                check(directory.exists() || directory.mkdirs()) {
                    "无法创建 VPN 状态锁目录: ${directory.absolutePath}"
                }
            }
            RandomAccessFile(lockFile, "rw").use { lockAccess ->
                lockAccess.channel.lock().use { block() }
            }
        }
    }
}

internal fun isFileDescriptorExhaustion(
    error: Throwable,
    errnoOf: (Throwable) -> Int? = { cause -> (cause as? ErrnoException)?.errno }
): Boolean {
    var cause: Throwable? = error
    repeat(MAX_RESOURCE_ERROR_CAUSE_DEPTH) {
        val current = cause ?: return false
        if (errnoOf(current) == OsConstants.EMFILE) return true
        cause = current.cause
    }
    return false
}

private const val MAX_RESOURCE_ERROR_CAUSE_DEPTH = 16

/**
 *
 * MMKV 浼樺娍:
 *
 */
@Suppress("TooManyFunctions")
object VpnStateStore {
    private const val TAG = "VpnStateStore"
    private const val MMKV_ID = "vpn_state"

    private const val KEY_VPN_ACTIVE = "vpn_active"
    private const val KEY_VPN_PENDING = "vpn_pending"
    private const val KEY_VPN_ACTIVE_LABEL = "vpn_active_label"
    private const val KEY_SELECTED_NODE_LABEL = "selected_node_label"
    private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    private const val KEY_SELECTED_NODE_ID = "selected_node_id"
    private const val KEY_VPN_LAST_ERROR = "vpn_last_error"
    private const val KEY_VPN_MANUALLY_STOPPED = "vpn_manually_stopped"
    private const val KEY_RUNTIME_STATE_SNAPSHOT = "runtime_state_snapshot"
    private const val SNAPSHOT_JSON_GENERATION = "generation"
    private const val SNAPSHOT_JSON_STATE_ORDINAL = "stateOrdinal"
    private const val SNAPSHOT_JSON_ACTIVE_LABEL = "activeLabel"
    private const val SNAPSHOT_JSON_LAST_ERROR = "lastError"
    private const val SNAPSHOT_JSON_MANUALLY_STOPPED = "manuallyStopped"
    private const val SNAPSHOT_JSON_READINESS = "readiness"
    private const val KEY_CORE_MODE = "core_mode"
    private const val KEY_LAST_APP_MODE = "last_app_mode"
    private const val KEY_LAST_ALLOWLIST_HASH = "last_allowlist_hash"
    private const val KEY_LAST_BLOCKLIST_HASH = "last_blocklist_hash"
    private const val KEY_APPLIED_PER_APP_POLICY = "applied_per_app_policy"
    private const val KEY_LAST_TUN_SETTINGS_HASH = "last_tun_settings_hash"
    private const val KEY_LAST_ROUTING_MODE = "last_routing_mode"

    // Sender-side throttle for ACTION_PREPARE_RESTART to reduce repeated network oscillations.
    private const val KEY_LAST_PREPARE_RESTART_AT_MS = "last_prepare_restart_at_ms"

    // Cross-process mutex for recovery issuers (sticky / keepalive / cold-start recovery).
    private const val KEY_LAST_RECOVERY_ISSUED_AT_MS = "last_recovery_issued_at_ms"
    private const val KEY_LAST_RECOVERY_CLAIM_TOKEN = "last_recovery_claim_token"
    private val recoveryClaimLock = Any()
    private const val KEY_RESOURCE_RECOVERY_WINDOW_START_AT_MS = "resource_recovery_window_start_at_ms"
    private const val KEY_RESOURCE_CORE_RESTART_COUNT = "resource_core_restart_count"
    private const val KEY_RESOURCE_PROCESS_RECLAIM_COUNT = "resource_process_reclaim_count"
    internal const val RESOURCE_RECOVERY_WINDOW_MS = 60 * 60_000L
    internal const val RESOURCE_CORE_RESTART_LIMIT = 1
    internal const val RESOURCE_PROCESS_RECLAIM_LIMIT = 1
    private const val KEY_TRAFFIC_CLEAR_TIMESTAMP = "traffic_clear_timestamp"
    private const val KEY_LOG_CLEAR_GENERATION = "log_clear_generation"
    private const val KEY_LAST_MANUAL_STOP_AT_MS = "last_manual_stop_at_ms"
    private const val KEY_LAST_AUTO_FAILOVER_AT_MS = "last_auto_failover_at_ms"
    private const val KEY_AUTO_FAILOVER_WINDOW_START_AT_MS = "auto_failover_window_start_at_ms"
    private const val KEY_AUTO_FAILOVER_COUNT_IN_WINDOW = "auto_failover_count_in_window"
    private const val KEY_AUTO_FAILOVER_QUARANTINED_TAGS = "auto_failover_quarantined_tags"
    private const val KEY_LAST_AUTO_FAILOVER_NODE_TAG = "last_auto_failover_node_tag"
    enum class CoreMode {
        NONE,
        VPN,
        PROXY
    }

    enum class ResourceRecoveryAction {
        CORE_RESTART,
        PROCESS_RECLAIM
    }

    internal data class ResourceRecoveryBudgetState(
        val windowStartAtMs: Long = 0L,
        val coreRestartCount: Int = 0,
        val processReclaimCount: Int = 0
    )

    internal data class ResourceRecoveryBudgetResult(
        val state: ResourceRecoveryBudgetState,
        val consumed: Boolean
    )

    data class AppliedPerAppPolicySnapshot(
        val revision: Long = 0L,
        val mode: String = "",
        val digest: String = "",
        val capturedCount: Int = 0,
        val excludedCount: Int = 0,
        val appliedAtElapsedMs: Long = 0L,
        val serviceInstanceId: String = "",
        val runtimeGeneration: Long = 0L
    )

    internal data class RuntimeStateSnapshot(
        @field:SerializedName(SNAPSHOT_JSON_GENERATION)
        val generation: Long = 0L,
        @field:SerializedName(SNAPSHOT_JSON_STATE_ORDINAL)
        val stateOrdinal: Int = ServiceState.STOPPED.ordinal,
        @field:SerializedName(SNAPSHOT_JSON_ACTIVE_LABEL)
        val activeLabel: String = "",
        @field:SerializedName(SNAPSHOT_JSON_LAST_ERROR)
        val lastError: String = "",
        @field:SerializedName(SNAPSHOT_JSON_MANUALLY_STOPPED)
        val manuallyStopped: Boolean = false,
        @field:SerializedName(SNAPSHOT_JSON_READINESS)
        val readiness: DataPlaneReadinessSnapshot = DataPlaneReadinessSnapshot.stopped()
    )

    private val mmkv: MMKV by lazy {
        MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE)
    }

    private val gson by lazy { Gson() }

    private val runtimeStateFileLock by lazy {
        CrossProcessRuntimeStateLock(
            File(MMKV.getRootDir(), "$MMKV_ID.runtime_state.lock")
        )
    }

    fun getActive(): Boolean {
        val snapshot = readRuntimeStateSnapshot()
        return snapshot?.stateOrdinal == ServiceState.RUNNING.ordinal ||
            (snapshot == null && mmkv.decodeBool(KEY_VPN_ACTIVE, false))
    }

    fun setActive(active: Boolean) {
        updateRuntimeStateSnapshot(
            state = if (active) ServiceState.RUNNING else ServiceState.STOPPED
        )
    }

    fun getPending(): String = mmkv.decodeString(KEY_VPN_PENDING, "") ?: ""

    fun setPending(pending: String?) {
        mmkv.encode(KEY_VPN_PENDING, pending ?: "")
    }

    fun getActiveLabel(): String {
        return readRuntimeStateSnapshot()?.activeLabel
            ?: mmkv.decodeString(KEY_VPN_ACTIVE_LABEL, "").orEmpty()
    }

    fun setActiveLabel(label: String?) {
        updateRuntimeStateSnapshot(activeLabel = label.orEmpty())
    }

    fun getSelectedNodeLabel(): String = mmkv.decodeString(KEY_SELECTED_NODE_LABEL, "") ?: ""

    fun setSelectedNodeLabel(label: String?) {
        mmkv.encode(KEY_SELECTED_NODE_LABEL, label ?: "")
    }

    fun getSelectedProfileId(): String = mmkv.decodeString(KEY_SELECTED_PROFILE_ID, "") ?: ""

    fun getSelectedNodeId(): String = mmkv.decodeString(KEY_SELECTED_NODE_ID, "") ?: ""

    fun setSelectedNode(profileId: String?, nodeId: String?) {
        mmkv.encode(KEY_SELECTED_PROFILE_ID, profileId ?: "")
        mmkv.encode(KEY_SELECTED_NODE_ID, nodeId ?: "")
    }

    fun getLastError(): String {
        return readRuntimeStateSnapshot()?.lastError
            ?: mmkv.decodeString(KEY_VPN_LAST_ERROR, "").orEmpty()
    }

    fun setLastError(message: String?) {
        updateRuntimeStateSnapshot(lastError = message.orEmpty())
    }

    fun isManuallyStopped(): Boolean {
        return readRuntimeStateSnapshot()?.manuallyStopped
            ?: mmkv.decodeBool(KEY_VPN_MANUALLY_STOPPED, false)
    }

    fun setManuallyStopped(value: Boolean) {
        updateRuntimeStateSnapshot(manuallyStopped = value)
    }

    internal fun getRuntimeStateSnapshot(): RuntimeStateSnapshot {
        return readRuntimeStateSnapshot() ?: readLegacyRuntimeStateSnapshot()
    }

    internal fun updateRuntimeStateSnapshot(
        state: ServiceState? = null,
        activeLabel: String? = null,
        lastError: String? = null,
        manuallyStopped: Boolean? = null,
        readiness: DataPlaneReadinessSnapshot? = null
    ): RuntimeStateSnapshot {
        return transformRuntimeStateSnapshot { current ->
            current.copy(
                stateOrdinal = state?.ordinal ?: current.stateOrdinal,
                activeLabel = activeLabel ?: current.activeLabel,
                lastError = lastError ?: current.lastError,
                manuallyStopped = manuallyStopped ?: current.manuallyStopped,
                readiness = readiness ?: current.readiness
            )
        }
    }

    private fun transformRuntimeStateSnapshot(
        transform: (RuntimeStateSnapshot) -> RuntimeStateSnapshot
    ): RuntimeStateSnapshot {
        return try {
            runtimeStateFileLock.withLock {
                transformRuntimeStateSnapshotLocked(transform)
            }
        } catch (error: Exception) {
            if (!isFileDescriptorExhaustion(error)) throw error
            val current = readRuntimeStateSnapshot() ?: readLegacyRuntimeStateSnapshot()
            Log.e(TAG, "FD exhausted while locking runtime state; returning in-memory update", error)
            buildNextRuntimeStateSnapshot(current, transform = transform)
        }
    }

    private fun transformRuntimeStateSnapshotLocked(
        transform: (RuntimeStateSnapshot) -> RuntimeStateSnapshot
    ): RuntimeStateSnapshot {
        val current = readRuntimeStateSnapshot() ?: readLegacyRuntimeStateSnapshot()
        val updated = buildNextRuntimeStateSnapshot(current, transform = transform)
        return persistRuntimeStateSnapshot(updated, previous = current)
    }

    internal fun buildNextRuntimeStateSnapshot(
        current: RuntimeStateSnapshot,
        monotonicCandidate: Long = SystemClock.elapsedRealtimeNanos(),
        transform: (RuntimeStateSnapshot) -> RuntimeStateSnapshot
    ): RuntimeStateSnapshot {
        val generation = nextRuntimeGeneration(current.generation, monotonicCandidate)
        return normalizeRuntimeStateSnapshot(
            transform(current).copy(generation = generation)
        ).let { updated ->
            updated.copy(readiness = updated.readiness.copy(generation = generation))
        }
    }

    internal fun persistRuntimeStateSnapshotBestEffort(snapshot: RuntimeStateSnapshot): Boolean {
        return runCatching {
            runtimeStateFileLock.withLock {
                val current = readRuntimeStateSnapshot() ?: readLegacyRuntimeStateSnapshot()
                if (current.generation > snapshot.generation) return@withLock false
                persistRuntimeStateSnapshot(snapshot, previous = current)
                true
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to persist runtime state snapshot generation=${snapshot.generation}", error)
        }.getOrDefault(false)
    }

    private fun persistRuntimeStateSnapshot(
        snapshot: RuntimeStateSnapshot,
        previous: RuntimeStateSnapshot
    ): RuntimeStateSnapshot {
        mmkv.encode(KEY_RUNTIME_STATE_SNAPSHOT, encodeRuntimeStateSnapshot(snapshot))
        mmkv.encode(KEY_VPN_ACTIVE, snapshot.stateOrdinal == ServiceState.RUNNING.ordinal)
        mmkv.encode(KEY_VPN_ACTIVE_LABEL, snapshot.activeLabel)
        mmkv.encode(KEY_VPN_LAST_ERROR, snapshot.lastError)
        mmkv.encode(KEY_VPN_MANUALLY_STOPPED, snapshot.manuallyStopped)
        if (snapshot.manuallyStopped && !previous.manuallyStopped) {
            mmkv.encode(KEY_LAST_MANUAL_STOP_AT_MS, System.currentTimeMillis())
        }
        if (shouldResetResourceRecoveryBudget(previous.manuallyStopped, snapshot.manuallyStopped)) {
            resetResourceRecoveryBudgetLocked()
        }
        return snapshot
    }

    private fun readRuntimeStateSnapshot(): RuntimeStateSnapshot? {
        val raw = mmkv.decodeString(KEY_RUNTIME_STATE_SNAPSHOT, null) ?: return null
        return decodeRuntimeStateSnapshot(raw)
    }

    private fun readLegacyRuntimeStateSnapshot(): RuntimeStateSnapshot {
        val pending = mmkv.decodeString(KEY_VPN_PENDING, "").orEmpty()
        val state = when {
            pending == "starting" -> ServiceState.STARTING
            pending == "stopping" -> ServiceState.STOPPING
            mmkv.decodeBool(KEY_VPN_ACTIVE, false) -> ServiceState.RUNNING
            else -> ServiceState.STOPPED
        }
        return RuntimeStateSnapshot(
            stateOrdinal = state.ordinal,
            activeLabel = mmkv.decodeString(KEY_VPN_ACTIVE_LABEL, "").orEmpty(),
            lastError = mmkv.decodeString(KEY_VPN_LAST_ERROR, "").orEmpty(),
            manuallyStopped = mmkv.decodeBool(KEY_VPN_MANUALLY_STOPPED, false)
        )
    }

    internal fun encodeRuntimeStateSnapshot(snapshot: RuntimeStateSnapshot): String {
        return gson.toJson(snapshot)
    }

    @Suppress("CyclomaticComplexMethod")
    internal fun decodeRuntimeStateSnapshot(raw: String): RuntimeStateSnapshot? {
        if (raw.isBlank()) return null
        return runCatching {
            val json = JsonParser.parseString(raw)
            if (!json.isJsonObject) return@runCatching null
            val snapshot = json.asJsonObject
            val generationValue = snapshot.get(SNAPSHOT_JSON_GENERATION)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asString
                ?.toLongOrNull()
                ?: return@runCatching null
            val stateOrdinalValue = snapshot.get(SNAPSHOT_JSON_STATE_ORDINAL)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asString
                ?.toIntOrNull()
                ?: return@runCatching null
            val activeLabelValue = snapshot.get(SNAPSHOT_JSON_ACTIVE_LABEL)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?: return@runCatching null
            val lastErrorValue = snapshot.get(SNAPSHOT_JSON_LAST_ERROR)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?: return@runCatching null
            val manuallyStoppedValue = snapshot.get(SNAPSHOT_JSON_MANUALLY_STOPPED)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?.asBoolean
                ?: return@runCatching null
            val readinessValue = snapshot.get(SNAPSHOT_JSON_READINESS)
                ?.takeIf { it.isJsonObject }
                ?.let { gson.fromJson(it, DataPlaneReadinessSnapshot::class.java) }
                ?: DataPlaneReadinessSnapshot.stopped()
            normalizeRuntimeStateSnapshot(
                RuntimeStateSnapshot(
                    generation = generationValue,
                    stateOrdinal = stateOrdinalValue,
                    activeLabel = activeLabelValue,
                    lastError = lastErrorValue,
                    manuallyStopped = manuallyStoppedValue,
                    readiness = readinessValue
                )
            )
        }.onFailure { error ->
            runCatching { Log.w(TAG, "Failed to decode runtime state snapshot", error) }
        }.getOrNull()
    }

    internal fun normalizeRuntimeStateSnapshot(snapshot: RuntimeStateSnapshot): RuntimeStateSnapshot {
        val generation = snapshot.generation.coerceAtLeast(0L)
        return snapshot.copy(
            generation = generation,
            stateOrdinal = ServiceState.values().getOrNull(snapshot.stateOrdinal)?.ordinal
                ?: ServiceState.STOPPED.ordinal,
            activeLabel = snapshot.activeLabel.orEmpty(),
            lastError = snapshot.lastError.orEmpty(),
            readiness = snapshot.readiness.normalized().copy(generation = generation)
        )
    }

    internal fun nextRuntimeGeneration(current: Long, monotonicCandidate: Long): Long {
        val incremented = if (current == Long.MAX_VALUE) Long.MAX_VALUE else current + 1L
        return maxOf(incremented, monotonicCandidate.coerceAtLeast(1L))
    }

    fun getLastManualStopAtMs(): Long = mmkv.decodeLong(KEY_LAST_MANUAL_STOP_AT_MS, 0L)

    fun getMode(): CoreMode {
        val raw = mmkv.decodeString(KEY_CORE_MODE, CoreMode.NONE.name) ?: CoreMode.NONE.name
        return runCatching { CoreMode.valueOf(raw) }.getOrDefault(CoreMode.NONE)
    }

    fun setMode(mode: CoreMode) {
        mmkv.encode(KEY_CORE_MODE, mode.name)
    }

    fun getLastAppMode(): String = mmkv.decodeString(KEY_LAST_APP_MODE, "") ?: ""

    fun setLastAppMode(mode: String) {
        mmkv.encode(KEY_LAST_APP_MODE, mode)
    }

    fun getLastAllowlistHash(): Int = mmkv.decodeInt(KEY_LAST_ALLOWLIST_HASH, 0)

    fun setLastAllowlistHash(hash: Int) {
        mmkv.encode(KEY_LAST_ALLOWLIST_HASH, hash)
    }

    fun getLastBlocklistHash(): Int = mmkv.decodeInt(KEY_LAST_BLOCKLIST_HASH, 0)

    fun setLastBlocklistHash(hash: Int) {
        mmkv.encode(KEY_LAST_BLOCKLIST_HASH, hash)
    }

    fun savePerAppVpnSettings(appMode: String, allowlist: String?, blocklist: String?) {
        setLastAppMode(appMode)
        setLastAllowlistHash(allowlist?.hashCode() ?: 0)
        setLastBlocklistHash(blocklist?.hashCode() ?: 0)
    }

    fun hasPerAppVpnSettingsChanged(appMode: String, allowlist: String?, blocklist: String?): Boolean {
        val lastMode = getLastAppMode()

        if (lastMode.isEmpty()) {
            Log.d("VpnStateStore", "hasPerAppVpnSettingsChanged: lastMode is empty, returning false")
            return false
        }

        val lastAllowHash = getLastAllowlistHash()
        val lastBlockHash = getLastBlocklistHash()

        val currentAllowHash = allowlist?.hashCode() ?: 0
        val currentBlockHash = blocklist?.hashCode() ?: 0

        val changed = lastMode != appMode || when (appMode) {
            VpnAppMode.ALLOWLIST.name -> lastAllowHash != currentAllowHash
            VpnAppMode.BLOCKLIST.name -> lastBlockHash != currentBlockHash
            else -> false
        }
        Log.d(
            "VpnStateStore",
            "hasPerAppVpnSettingsChanged: lastMode=$lastMode, appMode=$appMode, " +
                "lastAllowHash=$lastAllowHash, currentAllowHash=$currentAllowHash, changed=$changed"
        )
        return changed
    }

    fun saveRoutingMode(mode: String) {
        mmkv.encode(KEY_LAST_ROUTING_MODE, mode)
    }

    fun hasRoutingModeChanged(mode: String): Boolean {
        val lastMode = mmkv.decodeString(KEY_LAST_ROUTING_MODE, "").orEmpty()
        val changed = lastMode.isBlank() || lastMode != mode
        Log.d(TAG, "hasRoutingModeChanged: last=$lastMode, current=$mode, changed=$changed")
        return changed
    }

    fun saveTunSettings(tunStack: String, tunMtu: Int, autoRoute: Boolean, strictRoute: Boolean, proxyPort: Int) {
        val hash = computeTunSettingsHash(tunStack, tunMtu, autoRoute, strictRoute, proxyPort)
        mmkv.encode(KEY_LAST_TUN_SETTINGS_HASH, hash)
    }

    fun hasTunSettingsChanged(
        tunStack: String,
        tunMtu: Int,
        autoRoute: Boolean,
        strictRoute: Boolean,
        proxyPort: Int
    ): Boolean {
        val lastHash = mmkv.decodeInt(KEY_LAST_TUN_SETTINGS_HASH, 0)
        if (lastHash == 0) {
            Log.d("VpnStateStore", "hasTunSettingsChanged: no previous hash, returning false")
            return false
        }
        val currentHash = computeTunSettingsHash(tunStack, tunMtu, autoRoute, strictRoute, proxyPort)
        val changed = lastHash != currentHash
        Log.d("VpnStateStore", "hasTunSettingsChanged: lastHash=$lastHash, currentHash=$currentHash, changed=$changed")
        return changed
    }

    private fun computeTunSettingsHash(
        tunStack: String,
        tunMtu: Int,
        autoRoute: Boolean,
        strictRoute: Boolean,
        proxyPort: Int
    ): Int {
        var result = tunStack.hashCode()
        result = 31 * result + tunMtu
        result = 31 * result + autoRoute.hashCode()
        result = 31 * result + strictRoute.hashCode()
        result = 31 * result + proxyPort
        return result
    }

    /**
     * Cross-process throttle for ACTION_PREPARE_RESTART senders.
     *
     * Returns true if the caller should proceed (and records the timestamp), false if it's too soon.
     */
    fun shouldTriggerPrepareRestart(minIntervalMs: Long): Boolean {
        if (minIntervalMs <= 0) return true
        val now = System.currentTimeMillis()
        val last = mmkv.decodeLong(KEY_LAST_PREPARE_RESTART_AT_MS, 0L)
        val elapsed = now - last
        if (elapsed in 0 until minIntervalMs) {
            return false
        }
        mmkv.encode(KEY_LAST_PREPARE_RESTART_AT_MS, now)
        return true
    }

    /**
     * 恢复互斥：进程内 synchronized，写入 token 后二次确认，降低跨进程叠枪概率。
     * 非严格 CAS（MMKV 无原生 compare-and-swap），服务侧幂等仍是最终兜底。
     */
    fun tryClaimRecovery(windowMs: Long): Boolean {
        if (windowMs <= 0) return true
        val now = System.currentTimeMillis()
        val token = "${android.os.Process.myPid()}:$now:${System.identityHashCode(Thread.currentThread())}"
        synchronized(recoveryClaimLock) {
            val last = mmkv.decodeLong(KEY_LAST_RECOVERY_ISSUED_AT_MS, 0L)
            if (now - last in 0 until windowMs) return false
            mmkv.encode(KEY_LAST_RECOVERY_ISSUED_AT_MS, now)
            mmkv.encode(KEY_LAST_RECOVERY_CLAIM_TOKEN, token)
            val storedAt = mmkv.decodeLong(KEY_LAST_RECOVERY_ISSUED_AT_MS, 0L)
            val storedToken = mmkv.decodeString(KEY_LAST_RECOVERY_CLAIM_TOKEN, null)
            return storedAt == now && storedToken == token
        }
    }

    /** Releases the recovery claim, e.g. when the issued start failed immediately. */
    fun clearRecoveryClaim() {
        synchronized(recoveryClaimLock) {
            mmkv.removeValueForKey(KEY_LAST_RECOVERY_ISSUED_AT_MS)
            mmkv.removeValueForKey(KEY_LAST_RECOVERY_CLAIM_TOKEN)
        }
    }

    fun getTrafficClearTimestamp(): Long = mmkv.decodeLong(KEY_TRAFFIC_CLEAR_TIMESTAMP, 0L)

    fun setTrafficClearTimestamp(timestamp: Long) {
        mmkv.encode(KEY_TRAFFIC_CLEAR_TIMESTAMP, timestamp)
    }

    fun getLogClearGeneration(): Long = mmkv.decodeLong(KEY_LOG_CLEAR_GENERATION, 0L)

    fun setLogClearGeneration(generation: Long) {
        mmkv.encode(KEY_LOG_CLEAR_GENERATION, generation)
    }

    fun getLastAutoFailoverAtMs(): Long = mmkv.decodeLong(KEY_LAST_AUTO_FAILOVER_AT_MS, 0L)

    fun setLastAutoFailoverAtMs(timestamp: Long) {
        mmkv.encode(KEY_LAST_AUTO_FAILOVER_AT_MS, timestamp)
    }

    fun getAutoFailoverWindowStartAtMs(): Long = mmkv.decodeLong(KEY_AUTO_FAILOVER_WINDOW_START_AT_MS, 0L)

    fun setAutoFailoverWindowStartAtMs(timestamp: Long) {
        mmkv.encode(KEY_AUTO_FAILOVER_WINDOW_START_AT_MS, timestamp)
    }

    fun getAutoFailoverCountInWindow(): Int = mmkv.decodeInt(KEY_AUTO_FAILOVER_COUNT_IN_WINDOW, 0)

    fun setAutoFailoverCountInWindow(count: Int) {
        mmkv.encode(KEY_AUTO_FAILOVER_COUNT_IN_WINDOW, count)
    }

    fun getAutoFailoverQuarantinedTags(): String = mmkv.decodeString(KEY_AUTO_FAILOVER_QUARANTINED_TAGS, "") ?: ""

    fun setAutoFailoverQuarantinedTags(value: String?) {
        mmkv.encode(KEY_AUTO_FAILOVER_QUARANTINED_TAGS, value ?: "")
    }

    fun getLastAutoFailoverNodeTag(): String = mmkv.decodeString(KEY_LAST_AUTO_FAILOVER_NODE_TAG, "") ?: ""

    fun setLastAutoFailoverNodeTag(tag: String?) {
        mmkv.encode(KEY_LAST_AUTO_FAILOVER_NODE_TAG, tag ?: "")
    }

    fun clear() {
        Log.i(TAG, "Clearing VPN config state keys")
        clearConfig()
    }

    fun clearAll() {
        Log.w(TAG, "Clearing all VPN state store data")
        runtimeStateFileLock.withLock { mmkv.clearAll() }
    }

    fun getAppliedPerAppPolicy(): AppliedPerAppPolicySnapshot = runCatching {
        mmkv.decodeString(KEY_APPLIED_PER_APP_POLICY, "")
            ?.takeIf(String::isNotBlank)
            ?.let { gson.fromJson(it, AppliedPerAppPolicySnapshot::class.java) }
    }.getOrNull() ?: AppliedPerAppPolicySnapshot()

    fun commitAppliedPerAppPolicy(snapshot: AppliedPerAppPolicySnapshot): Boolean {
        if (snapshot.revision < 0L || snapshot.serviceInstanceId.isBlank() || snapshot.digest.isBlank()) {
            return false
        }
        return runCatching {
            runtimeStateFileLock.withLock {
                val runtime = readRuntimeStateSnapshot() ?: readLegacyRuntimeStateSnapshot()
                val current = getAppliedPerAppPolicy()
                if (!canCommitAppliedPerAppPolicy(
                        current,
                        snapshot,
                        runtime.readiness.serviceInstanceId
                    )
                ) {
                    return@withLock false
                }
                mmkv.encode(KEY_APPLIED_PER_APP_POLICY, gson.toJson(snapshot))
            }
        }.getOrDefault(false)
    }

    internal fun canCommitAppliedPerAppPolicy(
        current: AppliedPerAppPolicySnapshot,
        incoming: AppliedPerAppPolicySnapshot,
        activeServiceInstanceId: String
    ): Boolean {
        if (activeServiceInstanceId.isNotBlank() && incoming.serviceInstanceId != activeServiceInstanceId) return false
        if (activeServiceInstanceId.isBlank() && current.serviceInstanceId.isNotBlank() &&
            current.serviceInstanceId != incoming.serviceInstanceId
        ) {
            return false
        }
        return current.serviceInstanceId != incoming.serviceInstanceId || current.revision <= incoming.revision
    }

    fun tryConsumeResourceRecovery(
        action: ResourceRecoveryAction,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = runtimeStateFileLock.withLock {
        val current = ResourceRecoveryBudgetState(
            windowStartAtMs = mmkv.decodeLong(KEY_RESOURCE_RECOVERY_WINDOW_START_AT_MS, 0L),
            coreRestartCount = mmkv.decodeInt(KEY_RESOURCE_CORE_RESTART_COUNT, 0),
            processReclaimCount = mmkv.decodeInt(KEY_RESOURCE_PROCESS_RECLAIM_COUNT, 0)
        )
        val result = consumeResourceRecoveryBudget(current, action, nowMs)
        if (!result.consumed) return@withLock false
        val saved = mmkv.encode(KEY_RESOURCE_RECOVERY_WINDOW_START_AT_MS, result.state.windowStartAtMs) &&
            mmkv.encode(KEY_RESOURCE_CORE_RESTART_COUNT, result.state.coreRestartCount) &&
            mmkv.encode(KEY_RESOURCE_PROCESS_RECLAIM_COUNT, result.state.processReclaimCount)
        saved
    }

    fun resetResourceRecoveryBudget() {
        runtimeStateFileLock.withLock { resetResourceRecoveryBudgetLocked() }
    }

    internal fun shouldResetResourceRecoveryBudget(
        previousManuallyStopped: Boolean,
        manuallyStopped: Boolean
    ): Boolean = previousManuallyStopped && !manuallyStopped

    private fun resetResourceRecoveryBudgetLocked() {
        mmkv.removeValueForKey(KEY_RESOURCE_RECOVERY_WINDOW_START_AT_MS)
        mmkv.removeValueForKey(KEY_RESOURCE_CORE_RESTART_COUNT)
        mmkv.removeValueForKey(KEY_RESOURCE_PROCESS_RECLAIM_COUNT)
    }

    internal fun consumeResourceRecoveryBudget(
        current: ResourceRecoveryBudgetState,
        action: ResourceRecoveryAction,
        nowMs: Long,
        windowMs: Long = RESOURCE_RECOVERY_WINDOW_MS,
        coreRestartLimit: Int = RESOURCE_CORE_RESTART_LIMIT,
        processReclaimLimit: Int = RESOURCE_PROCESS_RECLAIM_LIMIT
    ): ResourceRecoveryBudgetResult {
        val resetWindow = current.windowStartAtMs <= 0L || nowMs < current.windowStartAtMs ||
            nowMs - current.windowStartAtMs >= windowMs
        val base = if (resetWindow) ResourceRecoveryBudgetState(windowStartAtMs = nowMs) else current
        return when (action) {
            ResourceRecoveryAction.CORE_RESTART -> {
                if (base.coreRestartCount >= coreRestartLimit) {
                    ResourceRecoveryBudgetResult(base, consumed = false)
                } else {
                    ResourceRecoveryBudgetResult(
                        base.copy(coreRestartCount = base.coreRestartCount + 1),
                        consumed = true
                    )
                }
            }
            ResourceRecoveryAction.PROCESS_RECLAIM -> {
                if (base.processReclaimCount >= processReclaimLimit) {
                    ResourceRecoveryBudgetResult(base, consumed = false)
                } else {
                    ResourceRecoveryBudgetResult(
                        base.copy(processReclaimCount = base.processReclaimCount + 1),
                        consumed = true
                    )
                }
            }
        }
    }

    fun clearConfig() {
        runtimeStateFileLock.withLock {
            mmkv.removeValueForKey(KEY_RUNTIME_STATE_SNAPSHOT)
            mmkv.removeValueForKey(KEY_VPN_ACTIVE)
            mmkv.removeValueForKey(KEY_VPN_PENDING)
            mmkv.removeValueForKey(KEY_VPN_ACTIVE_LABEL)
            mmkv.removeValueForKey(KEY_VPN_LAST_ERROR)
            mmkv.removeValueForKey(KEY_VPN_MANUALLY_STOPPED)
            mmkv.removeValueForKey(KEY_CORE_MODE)
            mmkv.removeValueForKey(KEY_LAST_APP_MODE)
            mmkv.removeValueForKey(KEY_LAST_ALLOWLIST_HASH)
            mmkv.removeValueForKey(KEY_LAST_BLOCKLIST_HASH)
            mmkv.removeValueForKey(KEY_APPLIED_PER_APP_POLICY)
            mmkv.removeValueForKey(KEY_LAST_TUN_SETTINGS_HASH)
            mmkv.removeValueForKey(KEY_LAST_ROUTING_MODE)
            mmkv.removeValueForKey(KEY_LAST_MANUAL_STOP_AT_MS)
            clearAutoFailoverRuntimeState()
        }
    }

    /**
     * Clears only transient runtime state (active, pending, activeLabel, lastError).
     * Preserves manuallyStopped and all config hashes to maintain manual-stop semantics
     * and avoid unnecessary re-computation of settings.
     */
    fun clearRuntimeState(preserveLastError: Boolean = false) {
        Log.i(TAG, "Clearing transient runtime state")
        runtimeStateFileLock.withLock {
            mmkv.removeValueForKey(KEY_VPN_PENDING)
            mmkv.removeValueForKey(KEY_APPLIED_PER_APP_POLICY)
            transformRuntimeStateSnapshotLocked { current ->
                current.copy(
                    stateOrdinal = ServiceState.STOPPED.ordinal,
                    activeLabel = "",
                    lastError = if (preserveLastError) current.lastError else "",
                    readiness = DataPlaneReadinessSnapshot.stopped(current.readiness.serviceInstanceId)
                )
            }
        }
        clearAutoFailoverRuntimeState()
    }

    fun clearAutoFailoverRuntimeState() {
        mmkv.removeValueForKey(KEY_LAST_AUTO_FAILOVER_AT_MS)
        mmkv.removeValueForKey(KEY_AUTO_FAILOVER_WINDOW_START_AT_MS)
        mmkv.removeValueForKey(KEY_AUTO_FAILOVER_COUNT_IN_WINDOW)
        mmkv.removeValueForKey(KEY_AUTO_FAILOVER_QUARANTINED_TAGS)
        mmkv.removeValueForKey(KEY_LAST_AUTO_FAILOVER_NODE_TAG)
    }
}

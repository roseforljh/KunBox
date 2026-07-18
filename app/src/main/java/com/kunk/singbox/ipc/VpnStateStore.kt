package com.kunk.singbox.ipc

import android.os.SystemClock
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.kunk.singbox.service.ServiceState
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
    private const val KEY_CORE_MODE = "core_mode"
    private const val KEY_LAST_APP_MODE = "last_app_mode"
    private const val KEY_LAST_ALLOWLIST_HASH = "last_allowlist_hash"
    private const val KEY_LAST_BLOCKLIST_HASH = "last_blocklist_hash"
    private const val KEY_LAST_TUN_SETTINGS_HASH = "last_tun_settings_hash"
    private const val KEY_LAST_ROUTING_MODE = "last_routing_mode"

    // Sender-side throttle for ACTION_PREPARE_RESTART to reduce repeated network oscillations.
    private const val KEY_LAST_PREPARE_RESTART_AT_MS = "last_prepare_restart_at_ms"

    // Cross-process mutex for recovery issuers (sticky / keepalive / cold-start recovery).
    private const val KEY_LAST_RECOVERY_ISSUED_AT_MS = "last_recovery_issued_at_ms"
    private const val KEY_LAST_RECOVERY_CLAIM_TOKEN = "last_recovery_claim_token"
    private val recoveryClaimLock = Any()
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
        val manuallyStopped: Boolean = false
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
        manuallyStopped: Boolean? = null
    ): RuntimeStateSnapshot {
        return transformRuntimeStateSnapshot { current ->
            current.copy(
                stateOrdinal = state?.ordinal ?: current.stateOrdinal,
                activeLabel = activeLabel ?: current.activeLabel,
                lastError = lastError ?: current.lastError,
                manuallyStopped = manuallyStopped ?: current.manuallyStopped
            )
        }
    }

    private fun transformRuntimeStateSnapshot(
        transform: (RuntimeStateSnapshot) -> RuntimeStateSnapshot
    ): RuntimeStateSnapshot {
        return runtimeStateFileLock.withLock {
            transformRuntimeStateSnapshotLocked(transform)
        }
    }

    private fun transformRuntimeStateSnapshotLocked(
        transform: (RuntimeStateSnapshot) -> RuntimeStateSnapshot
    ): RuntimeStateSnapshot {
        val current = readRuntimeStateSnapshot() ?: readLegacyRuntimeStateSnapshot()
        val updated = transform(current).copy(
            generation = nextRuntimeGeneration(current.generation, SystemClock.elapsedRealtimeNanos())
        )
        return persistRuntimeStateSnapshot(updated, previous = current)
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
            normalizeRuntimeStateSnapshot(
                RuntimeStateSnapshot(
                    generation = generationValue,
                    stateOrdinal = stateOrdinalValue,
                    activeLabel = activeLabelValue,
                    lastError = lastErrorValue,
                    manuallyStopped = manuallyStoppedValue
                )
            )
        }.onFailure { error ->
            runCatching { Log.w(TAG, "Failed to decode runtime state snapshot", error) }
        }.getOrNull()
    }

    internal fun normalizeRuntimeStateSnapshot(snapshot: RuntimeStateSnapshot): RuntimeStateSnapshot {
        return snapshot.copy(
            generation = snapshot.generation.coerceAtLeast(0L),
            stateOrdinal = ServiceState.values().getOrNull(snapshot.stateOrdinal)?.ordinal
                ?: ServiceState.STOPPED.ordinal,
            activeLabel = snapshot.activeLabel.orEmpty(),
            lastError = snapshot.lastError.orEmpty()
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

        val changed = lastMode != appMode || lastAllowHash != currentAllowHash || lastBlockHash != currentBlockHash
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
            transformRuntimeStateSnapshotLocked { current ->
                current.copy(
                    stateOrdinal = ServiceState.STOPPED.ordinal,
                    activeLabel = "",
                    lastError = if (preserveLastError) current.lastError else ""
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

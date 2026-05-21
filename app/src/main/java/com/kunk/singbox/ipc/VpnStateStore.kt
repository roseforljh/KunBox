package com.kunk.singbox.ipc

import android.util.Log
import com.tencent.mmkv.MMKV

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
    private const val KEY_VPN_LAST_ERROR = "vpn_last_error"
    private const val KEY_VPN_MANUALLY_STOPPED = "vpn_manually_stopped"
    private const val KEY_CORE_MODE = "core_mode"
    private const val KEY_LAST_APP_MODE = "last_app_mode"
    private const val KEY_LAST_ALLOWLIST_HASH = "last_allowlist_hash"
    private const val KEY_LAST_BLOCKLIST_HASH = "last_blocklist_hash"
    private const val KEY_LAST_TUN_SETTINGS_HASH = "last_tun_settings_hash"

    // Sender-side throttle for ACTION_PREPARE_RESTART to reduce repeated network oscillations.
    private const val KEY_LAST_PREPARE_RESTART_AT_MS = "last_prepare_restart_at_ms"
    private const val KEY_TRAFFIC_CLEAR_TIMESTAMP = "traffic_clear_timestamp"
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

    private val mmkv: MMKV by lazy {
        MMKV.mmkvWithID(MMKV_ID, MMKV.MULTI_PROCESS_MODE)
    }

    fun getActive(): Boolean = mmkv.decodeBool(KEY_VPN_ACTIVE, false)

    fun setActive(active: Boolean) {
        mmkv.encode(KEY_VPN_ACTIVE, active)
    }

    fun getPending(): String = mmkv.decodeString(KEY_VPN_PENDING, "") ?: ""

    fun setPending(pending: String?) {
        mmkv.encode(KEY_VPN_PENDING, pending ?: "")
    }

    fun getActiveLabel(): String = mmkv.decodeString(KEY_VPN_ACTIVE_LABEL, "") ?: ""

    fun setActiveLabel(label: String?) {
        mmkv.encode(KEY_VPN_ACTIVE_LABEL, label ?: "")
    }

    fun getLastError(): String = mmkv.decodeString(KEY_VPN_LAST_ERROR, "") ?: ""

    fun setLastError(message: String?) {
        mmkv.encode(KEY_VPN_LAST_ERROR, message ?: "")
    }

    fun isManuallyStopped(): Boolean = mmkv.decodeBool(KEY_VPN_MANUALLY_STOPPED, false)

    fun setManuallyStopped(value: Boolean) {
        mmkv.encode(KEY_VPN_MANUALLY_STOPPED, value)
        if (value) {
            mmkv.encode(KEY_LAST_MANUAL_STOP_AT_MS, System.currentTimeMillis())
        }
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

    fun getTrafficClearTimestamp(): Long = mmkv.decodeLong(KEY_TRAFFIC_CLEAR_TIMESTAMP, 0L)

    fun setTrafficClearTimestamp(timestamp: Long) {
        mmkv.encode(KEY_TRAFFIC_CLEAR_TIMESTAMP, timestamp)
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
        mmkv.clearAll()
    }

    fun clearConfig() {
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
        mmkv.removeValueForKey(KEY_LAST_MANUAL_STOP_AT_MS)
        clearAutoFailoverRuntimeState()
    }

    /**
     * Clears only transient runtime state (active, pending, activeLabel, lastError).
     * Preserves manuallyStopped and all config hashes to maintain manual-stop semantics
     * and avoid unnecessary re-computation of settings.
     */
    fun clearRuntimeState(preserveLastError: Boolean = false) {
        Log.i(TAG, "Clearing transient runtime state")
        mmkv.removeValueForKey(KEY_VPN_ACTIVE)
        mmkv.removeValueForKey(KEY_VPN_PENDING)
        mmkv.removeValueForKey(KEY_VPN_ACTIVE_LABEL)
        if (!preserveLastError) {
            mmkv.removeValueForKey(KEY_VPN_LAST_ERROR)
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

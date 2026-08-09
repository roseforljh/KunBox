package com.kunk.singbox.service.manager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.kunk.singbox.ipc.SingBoxRemote
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.manager.VpnServiceManager
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.utils.DefaultNetworkListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object NetworkAutoSwitchManager {
    private const val TAG = "NetworkAutoSwitchManager"
    private const val PREFS_NAME = "network_auto_switch"
    private const val KEY_STOPPED_BY_TRUSTED_WIFI = "stopped_by_trusted_wifi"
    private const val ACTION_THROTTLE_MS = 1500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listenerKey = Any()

    @Volatile
    private var started = false

    @Volatile
    private var lastActionAtMs = 0L

    fun start(context: Context) {
        if (started) return

        val appContext = context.applicationContext
        synchronized(this) {
            if (started) return
            started = true
        }

        scope.launch {
            val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
            if (connectivityManager == null) {
                Log.w(TAG, "ConnectivityManager unavailable")
                return@launch
            }

            val settingsRepository = SettingsRepository.getInstance(appContext)
            DefaultNetworkListener.start(connectivityManager, listenerKey) { network ->
                scope.launch {
                    handleNetworkChanged(appContext, settingsRepository, network)
                }
            }
            Log.i(TAG, "Network auto switch monitor started")
        }
    }

    fun requiredWifiSsidPermissions(): Array<String> {
        return buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
    }

    fun hasWifiSsidPermission(context: Context): Boolean {
        return requiredWifiSsidPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun handleNetworkChanged(
        context: Context,
        settingsRepository: SettingsRepository,
        network: Network?
    ) {
        if (network == null) return

        val settings = settingsRepository.settings.value
        if (!settings.networkAutoSwitchEnabled) return

        val networkType = resolveNetworkType(context, network)
        val ssid = if (networkType == NetworkAutoSwitchPolicy.NetworkType.WIFI) {
            readCurrentWifiSsid(context, network)
        } else {
            null
        }
        val stoppedByTrustedWifi = isStoppedByTrustedWifi(context)

        val action = NetworkAutoSwitchPolicy.evaluate(
            config = NetworkAutoSwitchPolicy.Config(
                enabled = settings.networkAutoSwitchEnabled,
                trustedWifiSsids = settings.trustedWifiSsids
            ),
            network = NetworkAutoSwitchPolicy.NetworkSnapshot(
                type = networkType,
                ssid = ssid
            ),
            vpn = NetworkAutoSwitchPolicy.VpnSnapshot(
                isRunning = VpnServiceManager.isRunning(),
                isStarting = VpnServiceManager.isStarting(),
                manuallyStopped = VpnStateStore.isManuallyStopped(),
                stoppedByTrustedWifi = stoppedByTrustedWifi
            )
        )

        when (action) {
            NetworkAutoSwitchPolicy.Action.StopForTrustedWifi -> stopForTrustedWifi(context, ssid)
            NetworkAutoSwitchPolicy.Action.StartForCellular -> startForCellular(context)
            NetworkAutoSwitchPolicy.Action.None -> Unit
        }
    }

    private fun stopForTrustedWifi(context: Context, ssid: String?) {
        if (!claimActionSlot()) return

        setStoppedByTrustedWifi(context, true)
        Log.i(TAG, "Stopping VPN for trusted WiFi: ${ssid.orEmpty()}")
        VpnServiceManager.stopVpn(context, VpnStopInitiator.TRUSTED_WIFI)
    }

    private fun startForCellular(context: Context) {
        if (!claimActionSlot()) return

        Log.i(TAG, "Starting VPN after switching to cellular")
        VpnStateStore.setManuallyStopped(false)
        VpnServiceManager.startVpn(context)
            .onSuccess {
                setStoppedByTrustedWifi(context, false)
                SingBoxRemote.ensureBound(context)
            }
    }

    private fun claimActionSlot(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAtMs < ACTION_THROTTLE_MS) {
            return false
        }
        lastActionAtMs = now
        return true
    }

    private fun resolveNetworkType(context: Context, network: Network): NetworkAutoSwitchPolicy.NetworkType {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkAutoSwitchPolicy.NetworkType.OTHER
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkAutoSwitchPolicy.NetworkType.OTHER

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkAutoSwitchPolicy.NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkAutoSwitchPolicy.NetworkType.CELLULAR
            else -> NetworkAutoSwitchPolicy.NetworkType.OTHER
        }
    }

    private fun readCurrentWifiSsid(context: Context, network: Network): String? {
        if (!hasWifiSsidPermission(context)) return null

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(network)
        val transportSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (caps?.transportInfo as? WifiInfo)?.ssid
        } else {
            null
        }

        NetworkAutoSwitchPolicy.normalizeSsid(transportSsid)?.let { return it }

        @Suppress("DEPRECATION")
        val legacySsid = runCatching {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.connectionInfo?.ssid
        }.getOrNull()

        return NetworkAutoSwitchPolicy.normalizeSsid(legacySsid)
    }

    private fun isStoppedByTrustedWifi(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_STOPPED_BY_TRUSTED_WIFI, false)
    }

    private fun setStoppedByTrustedWifi(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STOPPED_BY_TRUSTED_WIFI, value)
            .apply()
    }
}

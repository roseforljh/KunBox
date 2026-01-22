package com.kunk.singbox.service.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.utils.DefaultNetworkListener
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicLong

class NetworkManager(
    private val context: Context,
    private val vpnService: VpnService
) {
    companion object {
        private const val TAG = "NetworkManager"
        private const val DEBOUNCE_MS = 2000L
    }

    interface Listener {
        fun onNetworkChanged(network: Network, interfaceName: String)
        fun onNetworkLost()
        fun onInterfaceChanged(oldInterface: String, newInterface: String, index: Int)
    }

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var listener: Listener? = null

    @Volatile var lastKnownNetwork: Network? = null
        private set

    @Volatile var defaultInterfaceName: String = ""
        private set

    private val lastSetUnderlyingNetworksAtMs = AtomicLong(0L)
    private var noPhysicalNetworkWarningLogged = false

    fun start(listener: Listener) {
        this.listener = listener
        registerNetworkCallback()
    }

    fun stop() {
        unregisterNetworkCallback()
        listener = null
    }

    fun setUnderlyingNetworks(networks: Array<Network>?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            vpnService.setUnderlyingNetworks(networks)
            lastSetUnderlyingNetworksAtMs.set(SystemClock.elapsedRealtime())
            if (networks?.isNotEmpty() == true) {
                lastKnownNetwork = networks[0]
            }
        }
    }

    fun findBestPhysicalNetwork(): Network? {
        val cm = connectivityManager ?: return null

        DefaultNetworkListener.underlyingNetwork?.let { cached ->
            val caps = cm.getNetworkCapabilities(cached)
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                return cached
            }
        }

        lastKnownNetwork?.let { cached ->
            val caps = cm.getNetworkCapabilities(cached)
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                return cached
            }
        }

        val activeNetwork = cm.activeNetwork
        if (activeNetwork != null) {
            val caps = cm.getNetworkCapabilities(activeNetwork)
            if (caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                return activeNetwork
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            @Suppress("DEPRECATION")
            val allNetworks = cm.allNetworks
            var bestNetwork: Network? = null
            var bestScore = -1

            for (net in allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                val isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

                if (hasInternet && notVpn) {
                    var score = 0
                    if (validated) {
                        score = when {
                            isEthernet -> 5
                            isWifi -> 4
                            isCellular -> 3
                            else -> 1
                        }
                    } else {
                        score = when {
                            isEthernet -> 2
                            isWifi -> 2
                            isCellular -> 1
                            else -> 0
                        }
                    }

                    if (score > bestScore) {
                        bestScore = score
                        bestNetwork = net
                    }
                }
            }

            if (bestNetwork != null) {
                return bestNetwork
            }
        }

        return cm.activeNetwork?.takeIf {
            val caps = cm.getNetworkCapabilities(it)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
        }
    }

    fun updateDefaultInterface(network: Network, vpnStartedAtMs: Long, vpnStartupWindowMs: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        val timeSinceVpnStart = now - vpnStartedAtMs
        val inStartupWindow = vpnStartedAtMs > 0 && timeSinceVpnStart < vpnStartupWindowMs

        if (inStartupWindow) {
            Log.d(TAG, "updateDefaultInterface: skipped during startup window")
            return false
        }

        val caps = connectivityManager?.getNetworkCapabilities(network)
        val isValidPhysical = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true

        if (!isValidPhysical) {
            return false
        }

        val linkProperties = connectivityManager?.getLinkProperties(network)
        val interfaceName = linkProperties?.interfaceName ?: ""
        val upstreamChanged = interfaceName.isNotEmpty() && interfaceName != defaultInterfaceName

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 && (network != lastKnownNetwork || upstreamChanged)) {
            val lastSet = lastSetUnderlyingNetworksAtMs.get()
            val timeSinceLastSet = now - lastSet
            val shouldSetNetwork = timeSinceLastSet >= DEBOUNCE_MS || network != lastKnownNetwork

            if (shouldSetNetwork) {
                setUnderlyingNetworks(arrayOf(network))
                noPhysicalNetworkWarningLogged = false
                Log.i(TAG, "Switched underlying network to $network (upstream=$interfaceName)")
                listener?.onNetworkChanged(network, interfaceName)
            }
        }

        if (interfaceName.isNotEmpty() && interfaceName != defaultInterfaceName) {
            val oldInterfaceName = defaultInterfaceName
            defaultInterfaceName = interfaceName
            val index = try {
                NetworkInterface.getByName(interfaceName)?.index ?: 0
            } catch (e: Exception) { 0 }

            Log.i(TAG, "Default interface updated: $oldInterfaceName -> $interfaceName (index: $index)")
            listener?.onInterfaceChanged(oldInterfaceName, interfaceName, index)
            return true
        }

        return false
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available: $network")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                if (isVpn) return

                if (cm.activeNetwork == network) {
                    listener?.onNetworkChanged(network, "")
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost: $network")
                if (lastKnownNetwork == network) {
                    lastKnownNetwork = null
                    listener?.onNetworkLost()
                }
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
            Log.i(TAG, "Network callback registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
                Log.i(TAG, "Network callback unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister network callback", e)
            }
        }
        networkCallback = null
    }

    fun reset() {
        lastKnownNetwork = null
        defaultInterfaceName = ""
        noPhysicalNetworkWarningLogged = false
        lastSetUnderlyingNetworksAtMs.set(0)
    }
}

package com.kunk.singbox.service.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.utils.perf.StateCache
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

/**
 *
 *
 */
class ConnectManager(
    private val context: Context,
    private val serviceScope: CoroutineScope
) {
    companion object {
        private const val TAG = "ConnectManager"
        private const val CONNECTION_RESET_DEBOUNCE_MS = 2000L
        private const val STARTUP_WINDOW_MS = 3000L
        private const val NETWORK_SWITCH_DELAY_MS = 2000L

        internal fun shouldHandoverToActiveDefaultNetwork(
            isActiveDefault: Boolean,
            isValidPhysical: Boolean
        ): Boolean {
            return isActiveDefault && isValidPhysical
        }
    }

    private val connectivityManager: ConnectivityManager? by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastKnownNetwork: Network? = null
    private var setUnderlyingNetworksFn: ((Array<Network>?) -> Unit)? = null

    /**
     */
    @Volatile
    private var upstreamInterfaceName: String? = null

    @Volatile
    private var isReady = false

    private val vpnStartedAtMs = AtomicLong(0)
    private val lastConnectionResetAtMs = AtomicLong(0)

    private var onNetworkChanged: ((Network?) -> Unit)? = null
    private var onNetworkLost: (() -> Unit)? = null

    data class NetworkState(
        val network: Network?,
        val isValid: Boolean,
        val hasInternet: Boolean,
        val isNotVpn: Boolean
    )

    fun init(
        onNetworkChanged: (Network?) -> Unit,
        onNetworkLost: () -> Unit,
        setUnderlyingNetworksFn: ((Array<Network>?) -> Unit)? = null
    ): Result<Unit> {
        return runCatching {
            this.onNetworkChanged = onNetworkChanged
            this.onNetworkLost = onNetworkLost
            this.setUnderlyingNetworksFn = setUnderlyingNetworksFn
            Log.i(TAG, "ConnectManager initialized")
        }
    }

    fun registerNetworkCallback(): Result<Unit> {
        return runCatching {
            val cm = connectivityManager
                ?: throw IllegalStateException("ConnectivityManager not available")

            if (networkCallback != null) {
                Log.w(TAG, "Network callback already registered")
                return@runCatching
            }

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handleNetworkAvailable(network)
                }

                override fun onLost(network: Network) {
                    handleNetworkLost(network)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    caps: NetworkCapabilities
                ) {
                    handleCapabilitiesChanged(network, caps)
                }
            }

            val callback = networkCallback ?: return@runCatching
            cm.registerNetworkCallback(request, callback)
            Log.i(TAG, "Network callback registered")
        }
    }

    /**
     */
    fun unregisterNetworkCallback(): Result<Unit> {
        return runCatching {
            networkCallback?.let { callback ->
                runCatching {
                    connectivityManager?.unregisterNetworkCallback(callback)
                }
            }
            networkCallback = null
            Log.i(TAG, "Network callback unregistered")
        }
    }

    fun getCurrentNetwork(): Network? {
        return StateCache.getNetwork {
            getPhysicalNetwork()
        }
    }

    /**
     */
    fun getPhysicalNetwork(): Network? {
        val cm = connectivityManager ?: return null

        lastKnownNetwork?.let { network ->
            val caps = cm.getNetworkCapabilities(network)
            if (isValidPhysicalNetwork(caps)) {
                return network
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activeNetwork = cm.activeNetwork
            val caps = activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (isValidPhysicalNetwork(caps)) {
                lastKnownNetwork = activeNetwork
                return activeNetwork
            }
        }

        return null
    }

    /**
     */
    suspend fun waitForNetwork(timeoutMs: Long): Result<Network?> {
        return runCatching {
            withTimeout(timeoutMs) {
                while (!isReady || lastKnownNetwork == null) {
                    delay(100)
                }
                lastKnownNetwork
            }
        }
    }

    /**
     */
    fun markVpnStarted() {
        vpnStartedAtMs.set(SystemClock.elapsedRealtime())
    }

    /**
     */
    fun isInStartupWindow(): Boolean {
        val startedAt = vpnStartedAtMs.get()
        if (startedAt == 0L) return false
        return (SystemClock.elapsedRealtime() - startedAt) < STARTUP_WINDOW_MS
    }

    /**
     */
    fun setUnderlyingNetworks(
        networks: Array<Network>?,
        setUnderlyingFn: (Array<Network>?) -> Unit
    ): Result<Boolean> {
        return runCatching {

            if (isInStartupWindow()) {
                Log.d(TAG, "Skipping setUnderlyingNetworks during startup window")
                return@runCatching false
            }

            setUnderlyingFn(networks)
            Log.i(TAG, "setUnderlyingNetworks: ${networks?.size ?: 0} networks")
            true
        }
    }

    /**
     */
    fun resetConnections(resetFn: () -> Unit): Result<Boolean> {
        return runCatching {
            val now = SystemClock.elapsedRealtime()
            val last = lastConnectionResetAtMs.get()
            if ((now - last) < CONNECTION_RESET_DEBOUNCE_MS) {
                Log.d(TAG, "Debouncing connection reset")
                return@runCatching false
            }

            lastConnectionResetAtMs.set(now)
            resetFn()
            Log.i(TAG, "Connections reset")
            true
        }
    }

    /**
     */
    fun getNetworkState(): NetworkState {
        val network = lastKnownNetwork
        val caps = network?.let { connectivityManager?.getNetworkCapabilities(it) }

        return NetworkState(
            network = network,
            isValid = network != null,
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            isNotVpn = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
        )
    }

    /**
     */
    fun isReady(): Boolean = isReady

    /**
     */
    fun cleanup(): Result<Unit> {
        return runCatching {
            unregisterNetworkCallback()
            lastKnownNetwork = null
            upstreamInterfaceName = null
            isReady = false
            onNetworkChanged = null
            onNetworkLost = null
            setUnderlyingNetworksFn = null
            StateCache.invalidateNetworkCache()
            Log.i(TAG, "ConnectManager cleaned up")
        }
    }

    private fun handleNetworkAvailable(network: Network) {
        val cm = connectivityManager ?: return
        val isActiveDefault = cm.activeNetwork == network
        if (!isActiveDefault) {
            Log.d(TAG, "Network available but not active default: $network, ignoring")
            return
        }

        val caps = cm.getNetworkCapabilities(network)
        val isValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val isValidPhysical = isValidPhysicalNetwork(caps)
        if (!shouldHandoverToActiveDefaultNetwork(isActiveDefault, isValidPhysical)) {
            Log.d(TAG, "Network available but not a valid physical default: $network, ignoring")
            return
        }

        if (!isValidated) {
            Log.d(TAG, "Active default network $network not yet validated, handing over underlying network first")
        }

        Log.i(TAG, "Network available: $network (active, validated=$isValidated)")
        lastKnownNetwork = network
        StateCache.updateNetworkCache(network)
        isReady = true

        setUnderlyingNetworksFn?.invoke(arrayOf(network))
        onNetworkChanged?.invoke(network)

        checkAndResetOnInterfaceChange(network)
    }

    /**
     *
     *
     */
    private fun handleNetworkLost(network: Network) {
        Log.i(TAG, "Network lost: $network")
        if (lastKnownNetwork != network) {
            onNetworkLost?.invoke()
            return
        }

        val cm = connectivityManager ?: run {
            lastKnownNetwork = null
            StateCache.invalidateNetworkCache()
            setUnderlyingNetworksFn?.invoke(null)
            onNetworkLost?.invoke()
            return
        }

        if (tryFindReplacementNetwork(cm, network)) {
            return
        }

        serviceScope.launch {
            delay(NETWORK_SWITCH_DELAY_MS)

            if (lastKnownNetwork != null && lastKnownNetwork != network) {
                Log.i(TAG, "Network already switched during delay")
                return@launch
            }
            if (tryFindReplacementNetwork(cm, network)) {
                return@launch
            }

            Log.i(TAG, "No replacement network found, clearing underlying networks")
            lastKnownNetwork = null
            StateCache.invalidateNetworkCache()
            setUnderlyingNetworksFn?.invoke(null)
            onNetworkLost?.invoke()
        }
    }

    private fun tryFindReplacementNetwork(cm: ConnectivityManager, lostNetwork: Network): Boolean {
        val activeNetwork = cm.activeNetwork
        if (activeNetwork != null && activeNetwork != lostNetwork) {
            val caps = cm.getNetworkCapabilities(activeNetwork)
            if (isValidPhysicalNetwork(caps)) {
                Log.i(TAG, "Network switch detected: $lostNetwork -> $activeNetwork")
                lastKnownNetwork = activeNetwork
                StateCache.updateNetworkCache(activeNetwork)
                setUnderlyingNetworksFn?.invoke(arrayOf(activeNetwork))
                onNetworkChanged?.invoke(activeNetwork)
                checkAndResetOnInterfaceChange(activeNetwork)
                return true
            }
        }
        return false
    }

    private fun handleCapabilitiesChanged(
        network: Network,
        caps: NetworkCapabilities
    ) {
        if (!isValidPhysicalNetwork(caps)) {
            Log.i(TAG, "Network capabilities no longer valid: $network")
            if (lastKnownNetwork == network) {
                handleNetworkLost(network)
            }
            return
        }

        val cm = connectivityManager ?: return
        val isActiveDefault = cm.activeNetwork == network
        if (!isActiveDefault) {
            return
        }

        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        if (lastKnownNetwork != network) {
            if (!shouldHandoverToActiveDefaultNetwork(isActiveDefault, true)) {
                return
            }
            if (!isValidated) {
                Log.d(TAG, "Active network $network not yet validated, switching underlying network first")
            }
            Log.i(TAG, "Capabilities changed, switching to active network: $network (validated=$isValidated)")
            lastKnownNetwork = network
            StateCache.updateNetworkCache(network)
            setUnderlyingNetworksFn?.invoke(arrayOf(network))
            onNetworkChanged?.invoke(network)
        } else {
            setUnderlyingNetworksFn?.invoke(arrayOf(network))
        }

        checkAndResetOnInterfaceChange(network)
    }

    /**
     */
    private fun checkAndResetOnInterfaceChange(network: Network) {
        val linkProps = connectivityManager?.getLinkProperties(network)
        val newInterfaceName = linkProps?.interfaceName

        val oldName = upstreamInterfaceName
        upstreamInterfaceName = newInterfaceName

        if (oldName != null && newInterfaceName != null && oldName != newInterfaceName) {
            Log.i(TAG, "[InterfaceChange] $oldName -> $newInterfaceName, resetting connections")
            serviceScope.launch(Dispatchers.IO) {
                resetConnections {
                    BoxWrapperManager.resetAllConnections(true)
                }
            }
        } else if (oldName == null && newInterfaceName != null) {
            Log.d(TAG, "[InterfaceInit] First interface: $newInterfaceName")
        }
    }

    private fun isValidPhysicalNetwork(caps: NetworkCapabilities?): Boolean {
        if (caps == null) return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }
}

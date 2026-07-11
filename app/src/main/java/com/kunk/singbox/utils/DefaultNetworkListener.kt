package com.kunk.singbox.utils

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import android.os.Handler
import android.os.Looper
import android.util.Log

internal data class PhysicalNetworkCandidate<T>(
    val network: T,
    val identity: Long,
    val isActive: Boolean,
    val isValidated: Boolean,
    val transportPriority: Int,
    val isCurrent: Boolean
)

internal fun <T> selectPreferredPhysicalNetwork(candidates: List<PhysicalNetworkCandidate<T>>): T? {
    return candidates
        .distinctBy(PhysicalNetworkCandidate<T>::identity)
        .maxWithOrNull(
            compareBy<PhysicalNetworkCandidate<T>> { it.isActive }
                .thenBy { it.isValidated }
                .thenBy { it.transportPriority }
                .thenBy { it.isCurrent }
        )
        ?.network
}

object DefaultNetworkListener {
    private const val TAG = "DefaultNetworkListener"
    private const val NETWORK_SWITCH_DELAY_MS = 2000L

    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?) -> Unit) : NetworkMessage()
        class Stop(val key: Any) : NetworkMessage()
        class Set(val network: Network?) : NetworkMessage()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Suppress("OPT_IN_USAGE")
    private val networkActor = scope.actor<NetworkMessage>(capacity = Channel.UNLIMITED) {
        val listeners = mutableMapOf<Any, (Network?) -> Unit>()
        var network: Network? = null

        for (message in channel) when (message) {
            is NetworkMessage.Start -> {
                if (listeners.isEmpty()) register()
                listeners[message.key] = message.listener
                if (network != null) message.listener(network)
            }
            is NetworkMessage.Stop -> {
                listeners.remove(message.key)
                if (listeners.isEmpty()) {
                    unregister()
                    network = null
                    underlyingNetwork = null
                }
            }
            is NetworkMessage.Set -> {
                if (!hasSameIdentity(network, message.network)) {
                    network = message.network
                    listeners.values.forEach { it(network) }
                }
            }
        }
    }

    @Volatile
    var underlyingNetwork: Network? = null
        private set

    private var connectivityManagerRef: WeakReference<ConnectivityManager>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackGeneration = AtomicLong(0L)

    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        // 禁止把 VPN 网络缓存为物理底层网络。
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }.build()

    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available: $network")
            refreshSelectedNetwork()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            refreshSelectedNetwork()
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost: $network")
            val cm = connectivityManagerRef?.get() ?: return
            val generation = callbackGeneration.get()
            val replacement = selectBestPhysicalNetwork(cm, excludedNetwork = network)
            if (replacement != null) {
                publishSelectedNetwork(replacement)
                return
            }

            mainHandler.postDelayed({
                if (callbackGeneration.get() != generation) return@postDelayed
                val activeManager = connectivityManagerRef?.get() ?: return@postDelayed
                val delayedReplacement = selectBestPhysicalNetwork(activeManager, excludedNetwork = network)
                if (delayedReplacement == null) {
                    Log.d(TAG, "No replacement network found")
                }
                publishSelectedNetwork(delayedReplacement)
            }, NETWORK_SWITCH_DELAY_MS)
        }
    }

    @Suppress("DEPRECATION")
    internal fun selectBestPhysicalNetwork(
        connectivityManager: ConnectivityManager,
        excludedNetwork: Network? = null
    ): Network? {
        return runCatching {
            val activeNetwork = connectivityManager.activeNetwork
            val activeIdentity = activeNetwork?.networkHandle
            val currentIdentity = underlyingNetwork?.networkHandle
            val excludedIdentity = excludedNetwork?.networkHandle
            val candidates = buildList {
                activeNetwork?.let(::add)
                underlyingNetwork?.let(::add)
                addAll(connectivityManager.allNetworks)
            }.distinctBy { network -> network.networkHandle }.mapNotNull { network ->
                if (network.networkHandle == excludedIdentity) return@mapNotNull null
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!isUsablePhysicalNetwork(capabilities)) return@mapNotNull null
                PhysicalNetworkCandidate(
                    network = network,
                    identity = network.networkHandle,
                    isActive = network.networkHandle == activeIdentity,
                    isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    transportPriority = transportPriority(capabilities),
                    isCurrent = network.networkHandle == currentIdentity
                )
            }
            selectPreferredPhysicalNetwork(candidates)
        }.onFailure { error ->
            Log.w(TAG, "Failed to select physical network", error)
        }.getOrNull()
    }

    private fun isUsablePhysicalNetwork(capabilities: NetworkCapabilities): Boolean {
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun transportPriority(capabilities: NetworkCapabilities): Int {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
            else -> 0
        }
    }

    private fun refreshSelectedNetwork() {
        val cm = connectivityManagerRef?.get() ?: return
        publishSelectedNetwork(selectBestPhysicalNetwork(cm))
    }

    private fun publishSelectedNetwork(network: Network?) {
        if (hasSameIdentity(underlyingNetwork, network)) return
        Log.i(TAG, "Selected physical network: $underlyingNetwork -> $network")
        underlyingNetwork = network
        enqueue(NetworkMessage.Set(network))
    }

    private fun hasSameIdentity(first: Network?, second: Network?): Boolean {
        return first?.networkHandle == second?.networkHandle
    }

    fun start(connectivityManager: ConnectivityManager, key: Any, listener: (Network?) -> Unit) {
        connectivityManagerRef = WeakReference(connectivityManager)
        enqueue(NetworkMessage.Start(key, listener))
    }

    fun stop(key: Any) {
        enqueue(NetworkMessage.Stop(key))
    }

    private fun enqueue(message: NetworkMessage) {
        if (networkActor.trySend(message).isFailure) {
            Log.e(TAG, "Network listener actor is unavailable: ${message.javaClass.simpleName}")
        }
    }

    private fun register() {
        val cm = connectivityManagerRef?.get() ?: return
        callbackGeneration.incrementAndGet()
        try {
            when {
                Build.VERSION.SDK_INT >= 31 -> {
                    cm.registerBestMatchingNetworkCallback(request, Callback, mainHandler)
                }
                Build.VERSION.SDK_INT >= 26 -> {
                    cm.registerNetworkCallback(request, Callback, mainHandler)
                }
                else -> {
                    cm.registerNetworkCallback(request, Callback)
                }
            }
            publishSelectedNetwork(selectBestPhysicalNetwork(cm))
            Log.i(TAG, "Network listener registered (SDK ${Build.VERSION.SDK_INT})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network listener", e)
        }
    }

    private fun unregister() {
        callbackGeneration.incrementAndGet()
        val cm = connectivityManagerRef?.get()
        connectivityManagerRef = null
        if (cm != null) {
            runCatching { cm.unregisterNetworkCallback(Callback) }
                .onFailure { error -> Log.w(TAG, "Failed to unregister network listener", error) }
        }
    }
}

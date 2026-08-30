package com.kunk.singbox.service.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.kunk.singbox.ipc.VpnNetworkOwnership

internal fun resolveVpnNetworkOwnership(
    ownerUid: Int?,
    applicationUid: Int,
    preExisting: Boolean,
    canClaim: Boolean,
    alreadyOwned: Boolean
): VpnNetworkOwnership {
    val ownerKnown = ownerUid != null && ownerUid >= 0
    val supportsOwnerUid = ownerUid != null
    return when {
        ownerKnown && ownerUid == applicationUid -> VpnNetworkOwnership.OWNED
        alreadyOwned && (!ownerKnown || ownerUid == applicationUid) -> VpnNetworkOwnership.OWNED
        supportsOwnerUid && ownerKnown -> VpnNetworkOwnership.FOREIGN
        preExisting -> VpnNetworkOwnership.IGNORE
        supportsOwnerUid && canClaim -> VpnNetworkOwnership.UNKNOWN
        supportsOwnerUid -> VpnNetworkOwnership.IGNORE
        canClaim -> VpnNetworkOwnership.OWNED
        else -> VpnNetworkOwnership.FOREIGN
    }
}

class ForeignVpnMonitor(
    private val context: Context
) {
    companion object {
        private const val TAG = "ForeignVpnMonitor"
    }

    interface Callbacks {
        val isStarting: Boolean
        val isRunning: Boolean
        val isConnectingTun: Boolean
        fun onVpnNetworkObserved(
            network: Network,
            ownership: VpnNetworkOwnership,
            sessionId: Long
        ) {}

        fun onVpnNetworkLost(network: Network, owned: Boolean, sessionId: Long) {}
    }

    private data class OwnershipObservation(
        val callbacks: Callbacks,
        val network: Network,
        val ownership: VpnNetworkOwnership,
        val sessionId: Long
    )

    private data class LostObservation(
        val callbacks: Callbacks,
        val network: Network,
        val owned: Boolean,
        val sessionId: Long
    )

    private val stateLock = Any()
    private var callbacks: Callbacks? = null
    private var connectivityManager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var registrationGeneration = 0L
    private var activeSessionId = 0L
    private var preExistingVpnNetworks: Set<Network> = emptySet()
    private val networkSessionIds = mutableMapOf<Network, Long>()
    private val claimEligibleNetworks = mutableMapOf<Network, Boolean>()
    private val lastOwnership = mutableMapOf<Network, VpnNetworkOwnership>()
    @Volatile private var ownedVpnNetwork: Network? = null

    fun init(callbacks: Callbacks) {
        synchronized(stateLock) {
            this.callbacks = callbacks
        }
    }

    fun isSessionCurrent(sessionId: Long): Boolean = synchronized(stateLock) {
        callback != null && activeSessionId == sessionId
    }

    fun findOwnedVpnNetwork(ownerUid: Int): Network? {
        val cm = connectivityManager ?: context.getSystemService(ConnectivityManager::class.java)
        connectivityManager = cm
        cm ?: return null

        val knownOwned = ownedVpnNetwork
        if (knownOwned != null && isOwnedByApplication(cm, knownOwned, ownerUid)) return knownOwned

        return runCatching {
            @Suppress("DEPRECATION")
            cm.allNetworks.firstOrNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@firstOrNull false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    caps.ownerUid == ownerUid
                } else {
                    synchronized(stateLock) { network !in preExistingVpnNetworks }
                }
            }
        }.getOrNull()
    }

    private fun isOwnedByApplication(
        cm: ConnectivityManager,
        network: Network,
        ownerUid: Int
    ): Boolean {
        return runCatching {
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@runCatching false
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R || caps.ownerUid == ownerUid
        }.getOrDefault(false)
    }

    fun setOwnedVpnNetwork(network: Network?, sessionId: Long = activeSessionId) {
        if (network == null) return
        synchronized(stateLock) {
            if (callback == null || sessionId != activeSessionId) return
            ownedVpnNetwork = network
            networkSessionIds[network] = activeSessionId
            claimEligibleNetworks[network] = true
            lastOwnership[network] = VpnNetworkOwnership.OWNED
        }
    }

    fun detectExistingVpnNetworks(): List<Network> {
        val cm = connectivityManager ?: context.getSystemService(ConnectivityManager::class.java)
        connectivityManager = cm
        if (cm == null) return emptyList()

        return runCatching {
            @Suppress("DEPRECATION")
            cm.allNetworks.filter { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }.getOrDefault(emptyList())
    }

    fun hasExistingVpn(): Boolean {
        val vpnNetworks = detectExistingVpnNetworks()
        if (vpnNetworks.isNotEmpty()) {
            Log.w(TAG, "Detected ${vpnNetworks.size} existing VPN network(s): $vpnNetworks")
            return true
        }
        return false
    }

    fun start(sessionId: Long = 0L) {
        val cm = connectivityManager ?: context.getSystemService(ConnectivityManager::class.java)
        connectivityManager = cm
        if (cm == null) return

        val existingNetworks = snapshotVpnNetworks(cm)
        val shouldRegister = synchronized(stateLock) {
            if (callback != null) {
                beginSessionLocked(sessionId)
                false
            } else {
                registrationGeneration = nextGeneration(registrationGeneration)
                activeSessionId = sessionId.coerceAtLeast(0L)
                preExistingVpnNetworks = existingNetworks
                networkSessionIds.clear()
                claimEligibleNetworks.clear()
                lastOwnership.clear()
                ownedVpnNetwork = null
                callback = createNetworkCallback(registrationGeneration)
                true
            }
        }
        if (!shouldRegister) return

        val registeredCallback = synchronized(stateLock) { callback } ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(request, registeredCallback) }
            .onSuccess { discardStaleRegistration(cm, registeredCallback) }
            .onFailure { error -> rollbackFailedRegistration(registeredCallback, error) }
    }

    private fun discardStaleRegistration(
        cm: ConnectivityManager,
        registeredCallback: ConnectivityManager.NetworkCallback
    ) {
        val stillCurrent = synchronized(stateLock) { callback === registeredCallback }
        if (stillCurrent) return
        runCatching { cm.unregisterNetworkCallback(registeredCallback) }
            .onFailure { error -> Log.w(TAG, "Failed to discard stale foreign VPN monitor", error) }
    }

    private fun beginSessionLocked(sessionId: Long) {
        activeSessionId = sessionId.coerceAtLeast(0L)
        val owned = ownedVpnNetwork
        networkSessionIds.keys.filter { it != owned }.forEach(networkSessionIds::remove)
        claimEligibleNetworks.keys.filter { it != owned }.forEach(claimEligibleNetworks::remove)
        lastOwnership.keys.filter { it != owned }.forEach(lastOwnership::remove)
        owned?.let {
            networkSessionIds[it] = activeSessionId
            claimEligibleNetworks[it] = true
            lastOwnership[it] = VpnNetworkOwnership.OWNED
        }
    }

    private fun rollbackFailedRegistration(
        registeredCallback: ConnectivityManager.NetworkCallback,
        error: Throwable
    ) {
        synchronized(stateLock) {
            if (callback === registeredCallback) {
                callback = null
                activeSessionId = 0L
                preExistingVpnNetworks = emptySet()
                networkSessionIds.clear()
                claimEligibleNetworks.clear()
                lastOwnership.clear()
                ownedVpnNetwork = null
            }
        }
        Log.w(TAG, "Failed to register foreign VPN monitor", error)
    }

    private fun snapshotVpnNetworks(cm: ConnectivityManager): Set<Network> {
        return runCatching {
            @Suppress("DEPRECATION")
            cm.allNetworks.filter { network ->
                val caps = cm.getNetworkCapabilities(network)
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun createNetworkCallback(generation: Long): ConnectivityManager.NetworkCallback {
        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(stateLock) {
                    if (!isCurrentGenerationLocked(generation)) return
                    networkSessionIds[network] = activeSessionId
                    val currentCallbacks = callbacks
                    claimEligibleNetworks[network] = currentCallbacks?.isConnectingTun == true ||
                        currentCallbacks?.isStarting == true
                }
                Log.d(TAG, "VPN network candidate observed: $network generation=$generation")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                val observation = resolveObservation(generation, network, caps)
                observation?.callbacks?.onVpnNetworkObserved(
                    observation.network,
                    observation.ownership,
                    observation.sessionId
                )
            }

            override fun onLost(network: Network) {
                val lost = resolveLostObservation(generation, network)
                lost?.callbacks?.onVpnNetworkLost(lost.network, lost.owned, lost.sessionId)
            }
        }
    }

    private fun resolveObservation(
        generation: Long,
        network: Network,
        caps: NetworkCapabilities
    ): OwnershipObservation? = synchronized(stateLock) {
        if (!isCurrentGenerationLocked(generation)) return@synchronized null
        val sessionId = networkSessionIds[network] ?: activeSessionId
        if (sessionId != activeSessionId) return@synchronized null

        val currentCallbacks = callbacks ?: return@synchronized null
        val ownership = classifyObservationLocked(network, caps, currentCallbacks)
        if (ownership == VpnNetworkOwnership.IGNORE || lastOwnership[network] == ownership) {
            return@synchronized null
        }

        lastOwnership[network] = ownership
        recordOwnershipLocked(network, ownership)
        OwnershipObservation(currentCallbacks, network, ownership, sessionId)
    }

    private fun classifyObservationLocked(
        network: Network,
        caps: NetworkCapabilities,
        currentCallbacks: Callbacks
    ): VpnNetworkOwnership {
        val supportsOwnerUid = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val ownerUid = if (supportsOwnerUid) caps.ownerUid else null
        val hasDifferentOwnedNetwork = ownedVpnNetwork?.let { it != network } == true
        val ownerUnknown = ownerUid == null || ownerUid < 0
        if (hasDifferentOwnedNetwork && ownerUnknown) {
            return VpnNetworkOwnership.IGNORE
        }
        return resolveVpnNetworkOwnership(
            ownerUid = ownerUid,
            applicationUid = context.applicationInfo.uid,
            preExisting = network in preExistingVpnNetworks,
            canClaim = claimEligibleNetworks[network] == true ||
                currentCallbacks.isConnectingTun || currentCallbacks.isStarting,
            alreadyOwned = network == ownedVpnNetwork
        )
    }

    private fun recordOwnershipLocked(network: Network, ownership: VpnNetworkOwnership) {
        when (ownership) {
            VpnNetworkOwnership.OWNED -> {
                ownedVpnNetwork = network
                networkSessionIds[network] = activeSessionId
            }
            VpnNetworkOwnership.FOREIGN -> if (network == ownedVpnNetwork) ownedVpnNetwork = null
            else -> Unit
        }
    }

    private fun resolveLostObservation(
        generation: Long,
        network: Network
    ): LostObservation? = synchronized(stateLock) {
        if (!isCurrentGenerationLocked(generation)) return@synchronized null
        val sessionId = networkSessionIds.remove(network) ?: activeSessionId
        claimEligibleNetworks.remove(network)
        val currentCallbacks = callbacks ?: return@synchronized null
        val wasOwned = network == ownedVpnNetwork
        if (wasOwned) ownedVpnNetwork = null
        lastOwnership.remove(network)
        LostObservation(currentCallbacks, network, wasOwned, sessionId)
    }

    private fun isCurrentGenerationLocked(generation: Long): Boolean {
        return callback != null && registrationGeneration == generation
    }

    private fun nextGeneration(current: Long): Long = if (current == Long.MAX_VALUE) 1L else current + 1L

    fun stop() {
        val cm = connectivityManager
        val callbackToRemove = synchronized(stateLock) {
            val current = callback
            callback = null
            registrationGeneration = nextGeneration(registrationGeneration)
            activeSessionId = 0L
            preExistingVpnNetworks = emptySet()
            networkSessionIds.clear()
            claimEligibleNetworks.clear()
            lastOwnership.clear()
            ownedVpnNetwork = null
            current
        }
        if (cm != null && callbackToRemove != null) {
            runCatching { cm.unregisterNetworkCallback(callbackToRemove) }
                .onFailure { error -> Log.w(TAG, "Failed to unregister foreign VPN monitor", error) }
        }
    }

    fun cleanup() {
        stop()
        synchronized(stateLock) {
            callbacks = null
        }
    }
}

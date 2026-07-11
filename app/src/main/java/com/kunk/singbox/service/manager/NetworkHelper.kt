package com.kunk.singbox.service.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.kunk.singbox.utils.DefaultNetworkListener
import kotlinx.coroutines.delay

class NetworkHelper(
    private val context: Context
) {
    companion object {
        private const val TAG = "NetworkHelper"
    }

    private val connectivityManager: ConnectivityManager? by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    suspend fun ensureNetworkCallbackReady(
        isCallbackReady: () -> Boolean,
        lastKnownNetwork: () -> Network?,
        updateNetworkState: (Network?, Boolean) -> Unit,
        timeoutMs: Long = 2000L
    ) {
        if (isCallbackReady() && lastKnownNetwork() != null) {
            return
        }

        val cm = connectivityManager ?: return

        val selectedNetwork = DefaultNetworkListener.selectBestPhysicalNetwork(cm)
        if (selectedNetwork != null) {
            updateNetworkState(selectedNetwork, true)
            Log.i(TAG, "Pre-sampled physical network: $selectedNetwork")
            return
        }

        val startTime = SystemClock.elapsedRealtime()
        while (!isCallbackReady() && SystemClock.elapsedRealtime() - startTime < timeoutMs) {
            delay(100)
        }

        if (!isCallbackReady()) {
            val bestNetwork = DefaultNetworkListener.selectBestPhysicalNetwork(cm)
            if (bestNetwork != null) {
                updateNetworkState(bestNetwork, true)
                Log.i(TAG, "Found physical network after timeout: $bestNetwork")
            } else {
                Log.w(TAG, "Network callback not ready after ${timeoutMs}ms")
            }
        }
    }

    suspend fun waitForUsablePhysicalNetwork(
        timeoutMs: Long
    ): Network? {
        val cm = connectivityManager ?: return null
        val start = SystemClock.elapsedRealtime()
        var best: Network? = null
        while (SystemClock.elapsedRealtime() - start < timeoutMs) {
            val candidate = DefaultNetworkListener.selectBestPhysicalNetwork(cm)
            if (candidate != null) {
                val caps = cm.getNetworkCapabilities(candidate)
                best = candidate
                if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) return candidate
            }
            delay(100)
        }
        return best
    }
}

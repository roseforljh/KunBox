package com.kunk.singbox.service.manager

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.kunk.singbox.utils.DefaultNetworkListener
import com.kunk.singbox.utils.perf.StateCache

class ConnectManager(private val context: Context) {
    private val connectivityManager: ConnectivityManager? by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }
    fun getCurrentNetwork(): Network? {
        return StateCache.getNetwork(::getPhysicalNetwork)
    }

    fun markVpnStarted() {
        StateCache.invalidateNetworkCache()
    }

    private fun getPhysicalNetwork(): Network? {
        val cm = connectivityManager ?: return null
        return DefaultNetworkListener.selectBestPhysicalNetwork(cm)
    }
}

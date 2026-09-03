@file:Suppress("UnusedImports", "TooManyFunctions", "LongMethod", "LargeClass", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeCons")

package com.kunk.singbox.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import com.kunk.singbox.core.LibboxCompat
import com.kunk.singbox.core.StringIteratorImpl
import com.kunk.singbox.repository.*
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

private const val TAG = "ProxyOnlyService"

private fun setLastError(message: String?) = ProxyOnlyService.setLastError(message)

internal fun ProxyOnlyService.createProxyOnlyPlatformInterface(): PlatformInterface = object : PlatformInterface {
    override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport {
        return com.kunk.singbox.core.LocalResolverImpl
    }

    override fun autoDetectInterfaceControl(fd: Int) {
    }

    override fun openTun(options: TunOptions?): Int {
        setLastError("Proxy-only mode: TUN is disabled")
        return -1
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun useProcFS(): Boolean {
        val procPaths = listOf(
            "/proc/net/tcp",
            "/proc/net/tcp6",
            "/proc/net/udp",
            "/proc/net/udp6"
        )

        fun hasUidHeader(path: String): Boolean {
            return try {
                val file = File(path)
                if (!file.exists() || !file.canRead()) return false
                val header = file.bufferedReader().use { it.readLine() } ?: return false
                header.contains("uid")
            } catch (_: Exception) {
                false
            }
        }

        return procPaths.all { path -> hasUidHeader(path) }
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ConnectionOwner()

        fun parseAddress(value: String?): InetAddress? {
            if (value.isNullOrBlank()) return null
            val cleaned = value.trim().replace("[", "").replace("]", "").substringBefore("%")
            return try {
                InetAddress.getByName(cleaned)
            } catch (_: Exception) {
                null
            }
        }

        val sourceIp = parseAddress(sourceAddress)
        val destinationIp = parseAddress(destinationAddress)
        if (sourceIp == null || sourcePort <= 0 || destinationIp == null || destinationPort <= 0) {
            return ConnectionOwner()
        }

        return try {
            val cm = connectivityManager
                ?: getSystemService(ConnectivityManager::class.java)
                ?: return ConnectionOwner()
            val uid = cm.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceIp, sourcePort),
                InetSocketAddress(destinationIp, destinationPort)
            )
            if (uid > 0) {
                ConnectionOwner().apply {
                    userId = uid
                    LibboxCompat.setConnectionOwnerPackageName(
                        owner = this,
                        packageName = packageManager.getPackagesForUid(uid)?.firstOrNull().orEmpty()
                    )
                }
            } else {
                ConnectionOwner()
            }
        } catch (_: Exception) {
            ConnectionOwner()
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        currentInterfaceListener = listener
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val cm = connectivityManager ?: return
                val isActiveDefault = cm.activeNetwork == network
                if (!isActiveDefault) return
                val caps = cm.getNetworkCapabilities(network)
                val isValidated =
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                if (!isValidated) {
                    Log.d(TAG, "Network available but not validated: $network, waiting")
                    return
                }
                updateDefaultInterface(network)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val cm = connectivityManager ?: return
                if (cm.activeNetwork != network) return
                val isValidated =
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!isValidated) {
                    Log.d(TAG, "Active network $network not yet validated, waiting")
                    return
                }
                updateDefaultInterface(network)
            }

            override fun onLost(network: Network) {
                currentInterfaceListener?.updateDefaultInterface("", 0, false, false)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = networkCallback ?: return
        runCatching {
            connectivityManager?.registerNetworkCallback(request, callback)
        }

        val activeNet = connectivityManager?.activeNetwork
        if (activeNet != null) {
            val caps = connectivityManager?.getNetworkCapabilities(activeNet)
            val isValidated =
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            if (isValidated) {
                updateDefaultInterface(activeNet)
            } else {
                Log.d(TAG, "Initial active network $activeNet not validated, deferring")
            }
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        networkCallback?.let {
            runCatching {
                connectivityManager?.unregisterNetworkCallback(it)
            }
        }
        networkCallback = null
        currentInterfaceListener = null
    }

    override fun getInterfaces(): NetworkInterfaceIterator? {
        return try {
            val interfaces = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            object : NetworkInterfaceIterator {
                private val iterator = interfaces.filter { it.isUp && !it.isLoopback }.iterator()

                override fun hasNext(): Boolean = iterator.hasNext()

                override fun next(): io.nekohasekai.libbox.NetworkInterface {
                    val iface = iterator.next()
                    return io.nekohasekai.libbox.NetworkInterface().apply {
                        name = iface.name
                        index = iface.index
                        mtu = iface.mtu

                        var flagsStr = 0
                        if (iface.isUp) flagsStr = flagsStr or 1
                        if (iface.isLoopback) flagsStr = flagsStr or 4
                        if (iface.isPointToPoint) flagsStr = flagsStr or 8
                        if (iface.supportsMulticast()) flagsStr = flagsStr or 16
                        flags = flagsStr

                        val addrList = ArrayList<String>()
                        for (addr in iface.interfaceAddresses) {
                            val ip = addr.address.hostAddress
                            val cleanIp = if (ip != null && ip.contains("%")) ip.substring(0, ip.indexOf("%")) else ip
                            if (cleanIp != null) {
                                addrList.add("$cleanIp/${addr.networkPrefixLength}")
                            }
                        }
                        addresses = StringIteratorImpl(addrList)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get interfaces", e)
            null
        }
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() {
    }

    override fun sendNotification(notification: io.nekohasekai.libbox.Notification?) {
    }

    override fun cancelNotification(identifier: String?, typeID: Int) {
    }

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) =
        throw UnsupportedOperationException("Platform neighbor monitor is unavailable")

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {
    }

    override fun registerMyInterface(name: String?) {
    }

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() =
        throw UnsupportedOperationException("Platform shell is unavailable")

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: io.nekohasekai.libbox.StringIterator?,
        term: String?,
        rows: Int,
        cols: Int
    ): ShellSession = throw UnsupportedOperationException("Platform shell is unavailable")

    override fun lookupUser(username: String?): PlatformUser =
        throw UnsupportedOperationException("Platform user lookup is unavailable")

    override fun lookupSFTPServer(): String =
        throw UnsupportedOperationException("Platform SFTP is unavailable")

    override fun readSystemSSHHostKey(): String =
        throw UnsupportedOperationException("Platform SSH host key is unavailable")

    override fun tailscaleHostname(): String = ""

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession =
        throw UnsupportedOperationException("Platform bridge is unavailable")
}

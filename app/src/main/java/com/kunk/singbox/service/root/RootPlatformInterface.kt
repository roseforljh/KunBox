package com.kunk.singbox.service.root

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.service.manager.PlatformInterfaceImpl
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.TunOptions
import java.io.FileDescriptor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope

class RootPlatformInterface(
    context: Context,
    serviceScope: CoroutineScope,
    private val forceConnectionOwnerRouting: Boolean,
    private val serverProvider: () -> CommandServer?
) {
    companion object {
        private const val TAG = "RootPlatformInterface"
        private const val LINUX_SO_MARK = 36

        private fun resolveGetSocketMarkMethod(): Method = runCatching {
            Os::class.java.getDeclaredMethod(
                "getsockoptInt",
                FileDescriptor::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        }.getOrElse { error ->
            throw IllegalStateException("Reading SO_MARK is unavailable", error)
        }
    }

    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val packageCache = ConcurrentHashMap<Int, String>()
    private val getSocketMarkMethod = resolveGetSocketMarkMethod()

    @Volatile
    private var lastKnownNetwork: Network? = null

    val delegate = PlatformInterfaceImpl(
        context = appContext,
        serviceScope = serviceScope,
        mainHandler = Handler(Looper.getMainLooper()),
        callbacks = object : PlatformInterfaceImpl.Callbacks {
            override fun protect(fd: Int): Boolean = runCatching {
                val network = findBestPhysicalNetwork() ?: return@runCatching false
                ParcelFileDescriptor.fromFd(fd).use { descriptor ->
                    network.bindSocket(descriptor.fileDescriptor)
                    val currentMark = (getSocketMarkMethod.invoke(
                        null,
                        descriptor.fileDescriptor,
                        OsConstants.SOL_SOCKET,
                        LINUX_SO_MARK
                    ) as Number).toInt()
                    Os.setsockoptInt(
                        descriptor.fileDescriptor,
                        OsConstants.SOL_SOCKET,
                        LINUX_SO_MARK,
                        RootNetfilterPlanner.withCoreBypassMark(currentMark)
                    )
                }
                true
            }.onFailure { error ->
                Log.e(TAG, "Root socket protection failed for fd=$fd", error)
            }.getOrDefault(false)

            override fun openTun(options: TunOptions): Result<Int> =
                Result.failure(IllegalStateException("TUN is unavailable in Root transparent mode"))

            override fun getConnectivityManager(): ConnectivityManager? = connectivityManager

            override fun getCurrentNetwork(): Network? = findBestPhysicalNetwork()

            override fun getLastKnownNetwork(): Network? = lastKnownNetwork

            override fun setLastKnownNetwork(network: Network?) {
                lastKnownNetwork = network
            }

            override fun markVpnStarted() = Unit

            override fun onDefaultNetworkChanged() {
                runCatching { serverProvider()?.resetNetwork() }
            }

            override fun setUnderlyingNetworks(networks: Array<Network>?) = Unit

            override fun getCurrentSettings(): AppSettings? = null

            override fun forceConnectionOwnerRouting(): Boolean = this@RootPlatformInterface.forceConnectionOwnerRouting

            override fun incrementConnectionOwnerCalls() = Unit

            override fun incrementConnectionOwnerInvalidArgs() = Unit

            override fun incrementConnectionOwnerUidResolved() = Unit

            override fun incrementConnectionOwnerSecurityDenied() = Unit

            override fun incrementConnectionOwnerOtherException() = Unit

            override fun setConnectionOwnerLastEvent(event: String) = Unit

            override fun setConnectionOwnerLastUid(uid: Int) = Unit

            override fun isConnectionOwnerPermissionDeniedLogged(): Boolean = false

            override fun setConnectionOwnerPermissionDeniedLogged(logged: Boolean) = Unit

            override fun cacheUidToPackage(uid: Int, packageName: String) {
                if (uid > 0 && packageName.isNotBlank()) packageCache[uid] = packageName
            }

            override fun getUidFromCache(uid: Int): String? = packageCache[uid]

            override fun findBestPhysicalNetwork(): Network? = this@RootPlatformInterface.findBestPhysicalNetwork()
        }
    )

    private fun findBestPhysicalNetwork(): Network? {
        val manager = connectivityManager ?: return null
        fun isUsable(network: Network, requireValidated: Boolean): Boolean {
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (!requireValidated || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }
        return manager.activeNetwork?.takeIf { isUsable(it, requireValidated = false) }
            ?: manager.allNetworks.firstOrNull { isUsable(it, requireValidated = true) }
            ?: manager.allNetworks.firstOrNull { isUsable(it, requireValidated = false) }
    }
}

package com.kunk.singbox.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Process
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.LatencyTestMethod
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.config.OutboundFixer
import com.kunk.singbox.ipc.VpnStateStore
import kotlinx.coroutines.flow.first
import io.nekohasekai.libbox.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URI
import java.net.InetSocketAddress
import java.net.Socket
import com.kunk.singbox.utils.PreciseLatencyTester
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 *
 */
class SingBoxCore private constructor(private val context: Context) {

    private val gson = Gson()
    private val workDir: File = File(context.filesDir, "singbox_work")
    private val tempDir: File = File(context.cacheDir, "singbox_temp")

    private var libboxAvailable = false

    // Global lock for libbox operations to prevent native concurrency issues

    @Suppress("UnusedPrivateProperty")
    private val libboxMutex = kotlinx.coroutines.sync.Mutex()

    private val httpProxySemaphore = Semaphore(3)

    companion object {
        private const val TAG = "SingBoxCore"

        private val libboxSetupDone = AtomicBoolean(false)

        @Volatile
        private var lastNativeWarmupAt: Long = 0

        @Volatile
        private var instance: SingBoxCore? = null

        fun getInstance(context: Context): SingBoxCore {
            return instance ?: synchronized(this) {
                instance ?: SingBoxCore(context.applicationContext).also { instance = it }
            }
        }

        fun ensureLibboxSetup(context: Context) {
            if (libboxSetupDone.get()) return

            val appContext = context.applicationContext
            val pid = runCatching { Process.myPid() }.getOrDefault(0)
            val baseDir = File(appContext.filesDir, "libbox_$pid").also { it.mkdirs() }
            val workDir = File(baseDir, "singbox_work").also { it.mkdirs() }
            val tempDir = File(baseDir, "singbox_temp").also { it.mkdirs() }

            val setupOptions = SetupOptions().apply {
                basePath = baseDir.absolutePath
                workingPath = workDir.absolutePath
                this.tempPath = tempDir.absolutePath
            }

            if (!libboxSetupDone.compareAndSet(false, true)) return
            try {
                Libbox.setup(setupOptions)
            } catch (e: Exception) {
                libboxSetupDone.set(false)
                Log.w(TAG, "Libbox setup warning: ${e.message}")
            }
        }
    }

    init {
        workDir.mkdirs()
        tempDir.mkdirs()

        libboxAvailable = initLibbox()

        if (!libboxAvailable) {
            Log.w(TAG, "Libbox not available, using fallback mode")
        }
    }

    private fun initLibbox(): Boolean {
        return try {
            val coreVersion = Libbox.version() // Simple check
            val kunBoxVersion = runCatching { Libbox.getKunBoxVersion() }.getOrDefault("unknown")
            Log.i(TAG, "Libbox version=$coreVersion, KunBox extension version=$kunBoxVersion")
            ensureLibboxSetup(context)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Libbox init failed", e)
            false
        } catch (e: NoClassDefFoundError) {
            Log.e(TAG, "Libbox class not found", e)
            false
        }
    }

    /**
     */
    fun isLibboxAvailable(): Boolean = libboxAvailable

    /**
     */
    @Suppress("CognitiveComplexMethod")
    private suspend fun testOutboundLatencyWithLibbox(
        outbound: Outbound,
        settings: com.kunk.singbox.model.AppSettings? = null,
        dependencyOutbounds: List<Outbound> = emptyList()
    ): Long = withContext(Dispatchers.IO) {
        if (!libboxAvailable) return@withContext -1L

        val finalSettings = settings ?: SettingsRepository.getInstance(context).settings.first()
        val url = adjustUrlForMode(finalSettings.latencyTestUrl, finalSettings.latencyTestMethod)
        val timeoutMs = finalSettings.latencyTestTimeout

        // Remove mutex to allow concurrent testing
        val nativeRtt = testWithLibboxStaticUrlTest(outbound, url, timeoutMs, finalSettings.latencyTestMethod)

        if (nativeRtt >= 0) {
            return@withContext nativeRtt
        }

        if (VpnStateStore.getActive()) {
            Log.d(TAG, "VPN is running, skipping local HTTP proxy fallback to avoid command.sock conflict")
            return@withContext -1L
        }

        return@withContext try {
            val fallbackUrl = try {
                if (finalSettings.latencyTestMethod == com.kunk.singbox.model.LatencyTestMethod.TCP) {
                    adjustUrlForMode("http://www.gstatic.com/generate_204", finalSettings.latencyTestMethod)
                } else {
                    adjustUrlForMode("https://www.gstatic.com/generate_204", finalSettings.latencyTestMethod)
                }
            } catch (_: Exception) { url }
            testWithLocalHttpProxy(outbound, url, fallbackUrl, timeoutMs, dependencyOutbounds)
        } catch (e: Exception) {
            Log.w(TAG, "Native HTTP proxy test failed: ${e.message}")
            -1L
        }
    }

    // private var discoveredUrlTestMethod: java.lang.reflect.Method? = null
    // private var discoveredMethodType: Int = 0 // 0: long, 1: URLTest object

    /**
     */
    private fun resolveDependencyOutbounds(
        outbound: Outbound,
        allOutbounds: List<Outbound>
    ): List<Outbound> {
        val dependencies = mutableListOf<Outbound>()
        val visited = mutableSetOf<String>()

        fun resolve(current: Outbound) {
            val detourTag = current.detour
            if (detourTag.isNullOrBlank() || visited.contains(detourTag)) return
            visited.add(detourTag)

            val detourOutbound = allOutbounds.find { it.tag == detourTag }
            if (detourOutbound != null) {
                dependencies.add(detourOutbound)

                resolve(detourOutbound)
            }
        }

        resolve(outbound)
        return dependencies
    }

    private fun adjustUrlForMode(original: String, method: LatencyTestMethod): String {
        return try {
            val u = URI(original)
            val host = u.host ?: return original
            val path = if ((u.path ?: "").isNotEmpty()) u.path else "/"
            val query = u.query
            val fragment = u.fragment
            val userInfo = u.userInfo
            val port = u.port
            when (method) {
                LatencyTestMethod.TCP -> URI("http", userInfo, host, if (port == -1) -1 else port, path, query, fragment).toString()
                LatencyTestMethod.HANDSHAKE -> URI("https", userInfo, host, if (port == -1) -1 else port, path, query, fragment).toString()
                else -> original
            }
        } catch (_: Exception) {
            original
        }
    }

    // Removed reflection helpers: extractDelayFromUrlTest, hasDelayAccessors, buildUrlTestArgs

    private suspend fun testWithLocalHttpProxy(
        outbound: Outbound,
        targetUrl: String,
        fallbackUrl: String? = null,
        timeoutMs: Int,
        dependencyOutbounds: List<Outbound> = emptyList()
    ): Long = withContext(Dispatchers.IO) {

        httpProxySemaphore.withPermit {
            testWithLocalHttpProxyInternal(outbound, targetUrl, fallbackUrl, timeoutMs, dependencyOutbounds)
        }
    }

    private suspend fun testWithLocalHttpProxyInternal(
        outbound: Outbound,
        targetUrl: String,
        fallbackUrl: String? = null,
        timeoutMs: Int,
        dependencyOutbounds: List<Outbound> = emptyList()
    ): Long {

        val port = allocateLocalPort()
        val inbound = com.kunk.singbox.model.Inbound(
            type = "mixed",
            tag = "test-in",
            listen = "127.0.0.1",
            listenPort = port
        )

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val vpnRunning = com.kunk.singbox.service.SingBoxService.instance != null || VpnStateStore.getActive()
        var previousNetwork: Network? = null

        if (!vpnRunning) {
            try {
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork != null) {
                    previousNetwork = connectivityManager.boundNetworkForProcess
                    val bound = connectivityManager.bindProcessToNetwork(activeNetwork)
                    Log.d(TAG, "bindProcessToNetwork: bound=$bound, network=$activeNetwork")
                } else {
                    Log.w(TAG, "No active network available for binding")
                }
            } catch (e: Exception) {
                Log.w(TAG, "bindProcessToNetwork failed: ${e.message}")
            }
        }

        return try {
            val direct = com.kunk.singbox.model.Outbound(type = "direct", tag = "direct")

            val allOutbounds = mutableListOf(outbound)
            allOutbounds.addAll(dependencyOutbounds)
            allOutbounds.add(direct)

            val testDbPath = File(tempDir, "test_${UUID.randomUUID()}.db").absolutePath

            val config = SingBoxConfig(
                log = com.kunk.singbox.model.LogConfig(level = "debug", timestamp = true),

                // sing-box 1.13+: 不设 detour 即为直连
                dns = com.kunk.singbox.model.DnsConfig(
                    servers = listOf(
                        com.kunk.singbox.model.DnsServer(
                            tag = "dns-direct-v4",
                            type = "udp",
                            server = "223.5.5.5"
                        ),
                        com.kunk.singbox.model.DnsServer(
                            tag = "dns-direct-v6",
                            type = "https",
                            server = "2606:4700:4700::1111"
                        ),
                        com.kunk.singbox.model.DnsServer(
                            tag = "dns-backup",
                            type = "udp",
                            server = "119.29.29.29"
                        )
                    ),
                    rules = listOf(
                        com.kunk.singbox.model.DnsRule(queryType = listOf("A"), server = "dns-direct-v4"),
                        com.kunk.singbox.model.DnsRule(queryType = listOf("AAAA"), server = "dns-direct-v6")
                    ),
                    finalServer = "dns-backup",
                    strategy = "prefer_ipv4"
                ),
                inbounds = listOf(inbound),
                outbounds = allOutbounds,
                route = com.kunk.singbox.model.RouteConfig(
                    rules = listOf(
                        com.kunk.singbox.model.RouteRule(protocolRaw = listOf("dns"), outbound = "direct"),
                        com.kunk.singbox.model.RouteRule(inbound = listOf("test-in"), outbound = outbound.tag)
                    ),
                    finalOutbound = "direct",
                    autoDetectInterface = true,
                    defaultDomainResolver = com.kunk.singbox.model.DomainResolveConfig(
                        server = "dns-direct-v4",
                        strategy = "prefer_ipv4"
                    )
                ),

                experimental = com.kunk.singbox.model.ExperimentalConfig(
                    cacheFile = com.kunk.singbox.model.CacheFileConfig(
                        enabled = false,
                        path = testDbPath,
                        storeFakeip = false
                    )
                )
            )

            val configJson = gson.toJson(config)
            var commandServer: io.nekohasekai.libbox.CommandServer? = null
            try {
                ensureLibboxSetup(context)
                val platformInterface = TestPlatformInterface(context)
                val serverHandler = TestCommandServerHandler()
                commandServer = Libbox.newCommandServer(serverHandler, platformInterface)
                commandServer.start()

                val overrideOptions = OverrideOptions().apply {
                    autoRedirect = false
                }
                commandServer.startOrReloadService(configJson, overrideOptions)

                val deadline = System.currentTimeMillis() + 500L
                while (System.currentTimeMillis() < deadline) {
                    try {
                        Socket().use { s ->
                            s.soTimeout = 50
                            s.connect(InetSocketAddress("127.0.0.1", port), 50)
                        }
                        break
                    } catch (_: Exception) {
                        delay(20)
                    }
                }

                val result = PreciseLatencyTester.test(
                    proxyPort = port,
                    url = targetUrl,
                    timeoutMs = timeoutMs,
                    standard = PreciseLatencyTester.Standard.RTT,
                    warmup = false
                )
                if (result.isSuccess && result.latencyMs <= timeoutMs) {
                    result.latencyMs
                } else {
                    -1L
                }
            } finally {
                try {
                    runCatching { commandServer?.closeService() }
                    commandServer?.close()
                } catch (e: Exception) { Log.w(TAG, "Failed to close command server", e) }

                try {
                    File(testDbPath).delete()
                    File("$testDbPath-shm").delete()
                    File("$testDbPath-wal").delete()
                } catch (e: Exception) { Log.w(TAG, "Failed to delete temp db files", e) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Local HTTP proxy setup failed", e)
            -1L
        } finally {

            if (!vpnRunning) {
                try {
                    connectivityManager.bindProcessToNetwork(previousNetwork)
                    Log.d(TAG, "Restored process network binding")
                } catch (e: Exception) { Log.w(TAG, "Failed to restore network binding", e) }
            }
        }
    }

    /**
     *
     *
     *
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun testWithLibboxStaticUrlTest(
        outbound: Outbound,
        targetUrl: String,
        timeoutMs: Int,
        method: LatencyTestMethod
    ): Long = withContext(Dispatchers.IO) {

        Log.d(TAG, "Native URLTest not available in current core, returning -1")
        return@withContext -1L
    }

    private suspend fun testWithTemporaryServiceUrlTestOnRunning(
        outbound: Outbound,
        targetUrl: String,
        fallbackUrl: String? = null,
        timeoutMs: Int,
        method: LatencyTestMethod,
        dependencyOutbounds: List<Outbound> = emptyList()
    ): Long = withContext(Dispatchers.IO) {

        if (VpnStateStore.getActive() && libboxAvailable) {
            val rtt = testWithLibboxStaticUrlTest(outbound, targetUrl, timeoutMs, method)
            if (rtt >= 0) return@withContext rtt

            Log.d(TAG, "VPN is running, skipping temporary service fallback to avoid command.sock conflict")
            return@withContext -1L
        }

        testWithLocalHttpProxyInternal(outbound, targetUrl, fallbackUrl, timeoutMs, dependencyOutbounds)
    }

    private suspend fun testOutboundsLatencyOfflineWithTemporaryService(
        outbounds: List<Outbound>,
        targetUrl: String,
        timeoutMs: Int,
        method: LatencyTestMethod,
        onResult: (tag: String, latency: Long) -> Unit
    ) = withContext(Dispatchers.IO) {

        val batchSize = 50

        val settings = SettingsRepository.getInstance(context).settings.first()
        val concurrency = settings.latencyTestConcurrency

        outbounds.chunked(batchSize).forEach { batch ->

            testOutboundsLatencyBatchInternal(batch, targetUrl, timeoutMs, concurrency, onResult)
        }
    }

    /**
     */
    @Suppress("CognitiveComplexMethod", "LongMethod")
    private suspend fun testOutboundsLatencyBatchInternal(
        batchOutbounds: List<Outbound>,
        targetUrl: String,
        timeoutMs: Int,
        concurrency: Int,
        onResult: (tag: String, latency: Long) -> Unit
    ) {
        if (batchOutbounds.isEmpty()) return

        if (VpnStateStore.getActive() && BoxWrapperManager.isAvailable()) {
            Log.i(TAG, "VPN is running, using native batch URL test instead of temporary service")
            val results = BoxWrapperManager.urlTestBatch(
                outboundTags = batchOutbounds.map { it.tag },
                url = targetUrl,
                timeoutMs = timeoutMs,
                concurrency = concurrency.coerceIn(1, 20)
            )
            batchOutbounds.forEach { outbound ->
                onResult(outbound.tag, results[outbound.tag]?.toLong() ?: -1L)
            }
            return
        }

        val ports: List<Int>
        try {
            ports = allocateMultipleLocalPorts(batchOutbounds.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to allocate ports for batch test", e)
            batchOutbounds.forEach { onResult(it.tag, -1L) }
            return
        }

        val portToTagMap = ports.zip(batchOutbounds.map { it.tag }).toMap()
        val fixedOutbounds = batchOutbounds.mapNotNull { OutboundFixer.buildForRuntime(context, it) }
        val config = buildBatchTestConfig(fixedOutbounds, ports)
        val configJson = gson.toJson(config)
        val batchTestDbPath = config.experimental?.cacheFile?.path

        var commandServer: CommandServer? = null
        try {
            ensureLibboxSetup(context)
            val platformInterface = TestPlatformInterface(context)
            val serverHandler = TestCommandServerHandler()
            commandServer = Libbox.newCommandServer(serverHandler, platformInterface)
            commandServer.start()

            val overrideOptions = OverrideOptions().apply {
                autoRedirect = false
            }
            commandServer.startOrReloadService(configJson, overrideOptions)

            val portsReady = waitForPortsReady(ports)
            if (!portsReady) {
                Log.e(TAG, "Batch test: ports not ready")
                batchOutbounds.forEach { onResult(it.tag, -1L) }
                return
            }

            runPreciseLatencyTests(portToTagMap, targetUrl, timeoutMs, concurrency, onResult)
        } catch (e: Exception) {
            Log.e(TAG, "Batch test failed", e)
            batchOutbounds.forEach { onResult(it.tag, -1L) }
        } finally {
            runCatching { commandServer?.closeService() }
            runCatching { commandServer?.close() }
            batchTestDbPath?.let { path ->
                runCatching { File(path).delete() }
                runCatching { File("$path-shm").delete() }
                runCatching { File("$path-wal").delete() }
            }
        }
    }

    @Suppress("UnusedPrivateMember")
    private fun restoreNetworkBinding(vpnRunning: Boolean, cm: ConnectivityManager, network: Network?) {
        if (!vpnRunning) {
            try {
                cm.bindProcessToNetwork(network)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore network binding", e)
            }
        }
    }

    @Suppress("LongMethod")
    private fun buildBatchTestConfig(
        batchOutbounds: List<Outbound>,
        ports: List<Int>
    ): SingBoxConfig {
        val inbounds = ArrayList<com.kunk.singbox.model.Inbound>()
        val rules = ArrayList<com.kunk.singbox.model.RouteRule>()

        batchOutbounds.forEachIndexed { index, outbound ->
            val port = ports[index]
            val inboundTag = "test-in-$index"
            inbounds.add(com.kunk.singbox.model.Inbound(
                type = "mixed",
                tag = inboundTag,
                listen = "127.0.0.1",
                listenPort = port
            ))
            rules.add(com.kunk.singbox.model.RouteRule(
                inbound = listOf(inboundTag),
                outbound = outbound.tag
            ))
        }

        // sing-box 1.13+: 不设 detour 即为直连
        val dnsConfig = com.kunk.singbox.model.DnsConfig(
            servers = listOf(
                com.kunk.singbox.model.DnsServer(
                    tag = "dns-direct-v4",
                    type = "udp",
                    server = "223.5.5.5"
                ),
                com.kunk.singbox.model.DnsServer(
                    tag = "dns-direct-v6",
                    type = "https",
                    server = "2606:4700:4700::1111"
                ),
                com.kunk.singbox.model.DnsServer(
                    tag = "dns-backup",
                    type = "udp",
                    server = "119.29.29.29"
                ),
                com.kunk.singbox.model.DnsServer(
                    tag = "dns-bootstrap",
                    type = "udp",
                    server = "223.5.5.5"
                )
            ),
            rules = listOf(
                com.kunk.singbox.model.DnsRule(queryType = listOf("A"), server = "dns-direct-v4"),
                com.kunk.singbox.model.DnsRule(queryType = listOf("AAAA"), server = "dns-direct-v6")
                // sing-box 1.12.0+: outbound DNS rule deprecated, use route.default_domain_resolver instead
            ),
            finalServer = "dns-backup",
            strategy = "prefer_ipv4"
        )

        val safeOutbounds = ArrayList(batchOutbounds)
        val addedTags = batchOutbounds.map { it.tag }.toMutableSet()

        for (outbound in batchOutbounds) {
            val dependencies = resolveDependencyOutbounds(outbound, batchOutbounds)
            for (dep in dependencies) {
                if (addedTags.add(dep.tag)) {
                    safeOutbounds.add(dep)
                }
            }
        }

        if (safeOutbounds.none { it.tag == "direct" }) safeOutbounds.add(com.kunk.singbox.model.Outbound(type = "direct", tag = "direct"))
        // sing-box 1.13.0+: "block" outbound type removed, no longer needed

        val batchTestDbPath = File(tempDir, "batch_test_${UUID.randomUUID()}.db").absolutePath

        return SingBoxConfig(
            log = com.kunk.singbox.model.LogConfig(level = "debug", timestamp = true),
            dns = dnsConfig,
            inbounds = inbounds,
            outbounds = safeOutbounds,
            route = com.kunk.singbox.model.RouteConfig(
                rules = listOf(
                    com.kunk.singbox.model.RouteRule(protocolRaw = listOf("dns"), outbound = "direct")
                ) + rules,
                finalOutbound = "direct",
                autoDetectInterface = true,
                defaultDomainResolver = com.kunk.singbox.model.DomainResolveConfig(
                    server = "dns-bootstrap",
                    strategy = "prefer_ipv4"
                )
            ),
            experimental = com.kunk.singbox.model.ExperimentalConfig(
                cacheFile = com.kunk.singbox.model.CacheFileConfig(
                    enabled = false,
                    path = batchTestDbPath,
                    storeFakeip = false
                )
            )
        )
    }

    private suspend fun waitForPortsReady(ports: List<Int>): Boolean {
        val firstPort = ports.first()
        val deadline = System.currentTimeMillis() + 3000L
        var portReady = false
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { s ->
                    s.soTimeout = 100
                    s.connect(InetSocketAddress("127.0.0.1", firstPort), 100)
                }
                portReady = true
                break
            } catch (_: Exception) {
                delay(50)
            }
        }
        if (!portReady) {
            Log.e(TAG, "Batch test: port $firstPort not ready after 3s")
            return false
        }

        val portsToCheck = ports
        var allPortsReady = false
        for (attempt in 1..5) {
            allPortsReady = portsToCheck.all { port ->
                try {
                    Socket().use { s ->
                        s.soTimeout = 50
                        s.connect(InetSocketAddress("127.0.0.1", port), 50)
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (allPortsReady) break
            if (attempt < 5) delay(50)
        }
        if (!allPortsReady) {
            Log.e(TAG, "Batch test: not all ports are ready after retries")
            return false
        }
        return true
    }

    private suspend fun runPreciseLatencyTests(
        portToTagMap: Map<Int, String>,
        targetUrl: String,
        timeoutMs: Int,
        concurrency: Int,
        onResult: (tag: String, latency: Long) -> Unit
    ) {
        val semaphore = Semaphore(concurrency)
        coroutineScope {
            val jobs = portToTagMap.map { (port, originalTag) ->
                async {
                    semaphore.withPermit {
                        val result = PreciseLatencyTester.test(
                            proxyPort = port,
                            url = targetUrl,
                            timeoutMs = timeoutMs,
                            standard = PreciseLatencyTester.Standard.RTT,
                            warmup = false
                        )
                        val latency = if (result.isSuccess && result.latencyMs <= timeoutMs) {
                            result.latencyMs
                        } else {
                            -1L
                        }
                        onResult(originalTag, latency)
                    }
                }
            }
            jobs.awaitAll()
        }
    }

    /**
     *
     *
     */
    private fun allocateMultipleLocalPorts(count: Int): List<Int> {
        val ports = mutableListOf<Int>()
        val sockets = mutableListOf<ServerSocket>()
        try {
            for (i in 0 until count) {
                val socket = ServerSocket(0)
                socket.reuseAddress = true
                ports.add(socket.localPort)
                sockets.add(socket)
            }
        } catch (e: Exception) {
            sockets.forEach { runCatching { it.close() } }
            throw RuntimeException("Failed to allocate $count ports (allocated ${ports.size})", e)
        }
        sockets.forEach { runCatching { it.close() } }
        return ports
    }

    /**
     */
    suspend fun testOutboundLatency(
        outbound: Outbound,
        allOutbounds: List<Outbound> = emptyList()
    ): Long = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.getInstance(context).settings.first()
        val timeoutMs = settings.latencyTestTimeout
        val serviceRunning = com.kunk.singbox.service.SingBoxService.instance != null || VpnStateStore.getActive()

        val dependencyOutbounds = if (allOutbounds.isNotEmpty()) {
            resolveDependencyOutbounds(outbound, allOutbounds)
        } else {
            emptyList()
        }

        if (serviceRunning) {
            val isNativeUrlTestSupported = BoxWrapperManager.isAvailable()
            if (libboxAvailable && isNativeUrlTestSupported) {
                val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)
                var safeLatency = -1L
                SafeLatencyTester.getInstance().testOutboundsLatencySafe(
                    outbounds = listOf(outbound),
                    targetUrl = url,
                    timeoutMs = timeoutMs
                ) { tag, latency ->
                    if (tag == outbound.tag) {
                        safeLatency = latency
                    }
                }
                if (safeLatency > 0) {
                    return@withContext safeLatency
                }
                Log.w(TAG, "Safe single-node latency test failed for ${outbound.tag}, use native single test once")
                return@withContext testOutboundLatencyWithLibbox(outbound, settings, dependencyOutbounds)
            }

            Log.w(
                TAG,
                "VPN is active but native URL test is unavailable in current process, " +
                    "skipping temporary test-in"
            )
            return@withContext -1L
        }

        val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)

        val fallbackUrl = try {
            if (settings.latencyTestMethod == com.kunk.singbox.model.LatencyTestMethod.TCP) {
                adjustUrlForMode("http://www.gstatic.com/generate_204", settings.latencyTestMethod)
            } else {
                adjustUrlForMode("https://www.gstatic.com/generate_204", settings.latencyTestMethod)
            }
        } catch (_: Exception) { url }

        val rtt =
            testWithTemporaryServiceUrlTestOnRunning(outbound, url, fallbackUrl, timeoutMs, settings.latencyTestMethod, dependencyOutbounds)
        if (rtt >= 0) {
            return@withContext rtt
        }

        val fallback = testWithLocalHttpProxy(outbound, url, fallbackUrl, timeoutMs, dependencyOutbounds)
        return@withContext fallback
    }

    /**
     */
    suspend fun testOutboundsLatency(
        outbounds: List<Outbound>,
        onResult: (tag: String, latency: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.getInstance(context).settings.first()

        val isNativeUrlTestSupported = BoxWrapperManager.isAvailable()

        if (libboxAvailable && VpnStateStore.getActive() && isNativeUrlTestSupported) {
            val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)
            val timeoutMs = settings.latencyTestTimeout

            SafeLatencyTester.getInstance().testOutboundsLatencySafe(
                outbounds = outbounds,
                targetUrl = url,
                timeoutMs = timeoutMs,
                onResult = onResult
            )
            return@withContext
        }

        val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)
        val timeoutMs = settings.latencyTestTimeout
        testOutboundsLatencyOfflineWithTemporaryService(outbounds, url, timeoutMs, settings.latencyTestMethod, onResult)
    }

    private fun allocateLocalPort(): Int {
        var attempts = 0
        val maxAttempts = 10
        while (attempts < maxAttempts) {
            try {
                val socket = ServerSocket(0)
                socket.reuseAddress = true
                val port = socket.localPort
                socket.close()
                if (isPortAvailable(port)) {
                    return port
                }
            } catch (e: Exception) {
                Log.w(TAG, "Port allocation attempt $attempts failed", e)
            }
            attempts++
        }
        throw RuntimeException("Failed to allocate local port after $maxAttempts attempts")
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     */
    private fun getPhysicalNetworkInterface(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        val activeNetwork = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return null

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            cm.allNetworks.forEach { network ->
                val netCaps = cm.getNetworkCapabilities(network) ?: return@forEach
                if (!netCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    netCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    val linkProps = cm.getLinkProperties(network)
                    val ifaceName = linkProps?.interfaceName
                    if (!ifaceName.isNullOrEmpty()) {
                        Log.d(TAG, "Found physical network interface: $ifaceName")
                        return ifaceName
                    }
                }
            }
            return null
        }

        val linkProps = cm.getLinkProperties(activeNetwork)
        return linkProps?.interfaceName
    }

    /**
     */
    suspend fun validateConfig(config: SingBoxConfig): Result<Unit> = withContext(Dispatchers.IO) {
        if (!libboxAvailable) {
            return@withContext try {
                gson.toJson(config)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        try {
            val configJson = gson.toJson(config)
            Libbox.checkConfig(configJson)
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Config validation failed", e)
            Result.failure(e)
        }
    }

    /**
     */
    fun validateOutbound(outbound: Outbound): Boolean {
        if (!libboxAvailable) {
            return true
        }

        if (outbound.type in listOf("direct", "block", "dns", "selector", "urltest", "url-test")) {
            return true
        }

        val resolverServerTag = outbound.domainResolver?.server?.takeIf { it.isNotBlank() }
        val validationDns = resolverServerTag?.let { tag ->
            DnsConfig(
                servers = listOf(
                    DnsServer(
                        tag = tag,
                        type = "udp",
                        server = "223.5.5.5"
                    )
                )
            )
        }

        val minimalConfig = SingBoxConfig(
            log = null,
            dns = validationDns,
            inbounds = null,
            outbounds = listOf(
                outbound,
                Outbound(type = "direct", tag = "direct")
            ),
            route = null,
            experimental = null
        )

        return try {
            val configJson = gson.toJson(minimalConfig)
            Libbox.checkConfig(configJson)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Outbound validation failed for '${outbound.tag}': ${e.message}")
            false
        }
    }

    fun formatConfig(config: SingBoxConfig): String = gson.toJson(config)

    // --- Inner Classes for Platform Interface ---

    private class TestPlatformInterface(private val context: Context) : PlatformInterface {
        private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        override fun autoDetectInterfaceControl(fd: Int) {

            val service = com.kunk.singbox.service.SingBoxService.instance
            if (service != null) {
                try {
                    val protected = service.protect(fd)
                    if (!protected) {
                        Log.w(TAG, "Failed to protect socket fd=$fd, continuing anyway")
                    } else {
                        Log.d(TAG, "autoDetectInterfaceControl: protected fd=$fd")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Socket protection error for fd=$fd: ${e.message}")
                }
                return
            }

            try {
                val network = connectivityManager.activeNetwork
                if (network != null) {

                    val pfd = android.os.ParcelFileDescriptor.adoptFd(fd)
                    try {
                        network.bindSocket(pfd.fileDescriptor)
                        Log.d(TAG, "autoDetectInterfaceControl: bound fd=$fd to network")
                    } finally {

                        pfd.detachFd()
                    }
                } else {
                    Log.w(TAG, "autoDetectInterfaceControl: no active network for fd=$fd")
                }
            } catch (e: Exception) {
                Log.w(TAG, "autoDetectInterfaceControl: bind network error for fd=$fd: ${e.message}")
            }
        }

        override fun openTun(options: TunOptions?): Int {
            // Should not be called as we don't provide tun inbound
            Log.w(TAG, "TestPlatformInterface: openTun called unexpected!")
            return -1
        }

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {

            if (listener == null) return

            try {
                val activeNetwork = connectivityManager.activeNetwork
                if (activeNetwork != null) {
                    val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
                    val interfaceName = linkProperties?.interfaceName ?: ""
                    if (interfaceName.isNotEmpty()) {
                        val index = try {
                            java.net.NetworkInterface.getByName(interfaceName)?.index ?: 0
                        } catch (e: Exception) { 0 }
                        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                        val isExpensive = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                        listener.updateDefaultInterface(interfaceName, index, isExpensive, false)
                        Log.d(TAG, "TestPlatformInterface: initialized default interface: $interfaceName (index=$index)")
                    } else {
                        Log.w(TAG, "TestPlatformInterface: no interface name for active network")
                    }
                } else {
                    Log.w(TAG, "TestPlatformInterface: no active network available")
                }
            } catch (e: Exception) {
                Log.w(TAG, "TestPlatformInterface: failed to get default interface: ${e.message}")
            }
        }

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        }

        override fun getInterfaces(): NetworkInterfaceIterator? {
            return try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                object : NetworkInterfaceIterator {
                    private val iterator = interfaces.filter { it.isUp && !it.isLoopback }.iterator()
                    override fun hasNext(): Boolean = iterator.hasNext()
                    override fun next(): io.nekohasekai.libbox.NetworkInterface {
                        val iface = iterator.next()
                        return io.nekohasekai.libbox.NetworkInterface().apply {
                            name = iface.name
                            index = iface.index
                            mtu = iface.mtu
                            // type = ... (Field removed/renamed in v1.10)
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
                                if (cleanIp != null) addrList.add("$cleanIp/${addr.networkPrefixLength}")
                            }
                            addresses = StringIteratorImpl(addrList)
                        }
                    }
                }
            } catch (e: Exception) { null }
        }

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
        override fun useProcFS(): Boolean = false

        override fun findConnectionOwner(
            p0: Int,
            p1: String?,
            p2: Int,
            p3: String?,
            p4: Int
        ): ConnectionOwner {
            return ConnectionOwner()
        }

        override fun underNetworkExtension(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun readWIFIState(): WIFIState? = null
        override fun clearDNSCache() {}
        override fun sendNotification(p0: io.nekohasekai.libbox.Notification?) {}
        override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport {
            return com.kunk.singbox.core.LocalResolverImpl
        }
        override fun systemCertificates(): StringIterator? = null
    }

/**
     */
    private class TestCommandServerHandler : io.nekohasekai.libbox.CommandServerHandler {
        override fun serviceStop() {}
        override fun serviceReload() {}
        override fun getSystemProxyStatus(): io.nekohasekai.libbox.SystemProxyStatus? = null
        override fun setSystemProxyEnabled(isEnabled: Boolean) {}
        override fun writeDebugMessage(message: String?) {}
    }

    private class StringIteratorImpl(private val list: List<String>) : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < list.size
        override fun next(): String = list[index++]
        override fun len(): Int = list.size
    }

    /**
     */
    fun hasActiveConnections(): Boolean {
        if (!libboxAvailable) return false

        return try {
            BoxWrapperManager.isAvailable() && VpnStateStore.getActive()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check active connections", e)
            false
        }
    }

    /**
     */
    @Suppress("FunctionOnlyReturningConstant")
    fun getActiveConnections(): List<ActiveConnection> = emptyList()

    /**
     */
    fun closeConnectionsForApp(packageName: String): Int {
        if (!libboxAvailable) return 0

        return BoxWrapperManager.closeConnectionsForApp(packageName)
    }

    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
    fun closeConnections(packageName: String, uid: Int): Boolean {
        return closeConnectionsForApp(packageName) > 0
    }

    data class ActiveConnection(
        val packageName: String?,
        val uid: Int,
        val network: String,
        val remoteAddr: String,
        val remotePort: Int,
        val state: String,
        val connectionCount: Int = 0,
        val totalUpload: Long = 0,
        val totalDownload: Long = 0,
        val oldestConnMs: Long = 0,
        val newestConnMs: Long = 0,
        val hasRecentData: Boolean = true
    )

    fun cleanup() {
    }
}

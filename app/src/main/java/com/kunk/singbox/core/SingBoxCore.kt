package com.kunk.singbox.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Process
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsRule
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.LatencyTestMethod
import com.kunk.singbox.repository.ConfigRepository
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.InetSocketAddress
import java.net.Socket
import com.kunk.singbox.utils.PreciseLatencyTester
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
    private val processNetworkBindMutex = Mutex()

    companion object {
        private const val TAG = "SingBoxCore"

        private val libboxSetupDone = AtomicBoolean(false)

        @Volatile
        private var lastNativeWarmupAt: Long = 0

        @Volatile
        private var instance: SingBoxCore? = null

        private const val LATENCY_LOCAL_DNS_TAG = "local"
        private val REGEX_IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val REGEX_IPV6 = Regex("^[0-9a-fA-F:]+$")

        fun getInstance(context: Context): SingBoxCore {
            return instance ?: synchronized(this) {
                instance ?: SingBoxCore(context.applicationContext).also { instance = it }
            }
        }

        internal fun buildLatencyTestDnsConfigForTest(
            settings: AppSettings,
            outbounds: List<Outbound> = emptyList(),
            dnsOverride: DnsConfig? = null,
            sanitizeDnsServer: (DnsServer) -> DnsServer = { it }
        ): DnsConfig {
            return buildLatencyTestDnsConfigForRuntime(settings, outbounds, dnsOverride, sanitizeDnsServer)
        }

        internal fun buildLatencyTestDnsConfigForRuntime(
            settings: AppSettings,
            outbounds: List<Outbound> = emptyList(),
            dnsOverride: DnsConfig? = null,
            sanitizeDnsServer: (DnsServer) -> DnsServer = { it }
        ): DnsConfig {
            return buildLatencyTestDnsConfig(settings, outbounds, dnsOverride, sanitizeDnsServer)
        }

        internal fun applyLatencyBootstrapDomainResolverForTest(outbound: Outbound): Outbound {
            return applyLatencyBootstrapDomainResolver(outbound)
        }

        private fun buildLatencyTestDnsConfig(
            settings: AppSettings,
            outbounds: List<Outbound> = emptyList(),
            dnsOverride: DnsConfig? = null,
            sanitizeDnsServer: (DnsServer) -> DnsServer = { it }
        ): DnsConfig {
            val localDnsAddr = ConfigRepository.normalizeLocalDns(settings.localDns)
            val localResolver = ConfigRepository.buildDnsResolverForAddress(localDnsAddr)
            val localServer = ConfigRepository.buildDnsServer(
                address = localDnsAddr,
                tag = LATENCY_LOCAL_DNS_TAG,
                domainStrategy = "prefer_ipv4",
                domainResolver = localResolver
            )
            val servers = mutableListOf(
                DnsServer(
                    tag = ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG,
                    type = "udp",
                    server = "223.5.5.5",
                    serverPort = 53
                ),
                localServer,
                DnsServer(
                    tag = "dns-backup",
                    type = "udp",
                    server = "119.29.29.29",
                    serverPort = 53
                )
            )
            dnsOverride?.servers.orEmpty().forEach { server ->
                val tag = server.tag?.trim().orEmpty()
                if (tag.isBlank()) return@forEach
                val sanitized = sanitizeDnsServer(server)
                val existingIndex = servers.indexOfFirst { it.tag == tag }
                if (existingIndex >= 0) {
                    servers[existingIndex] = sanitized
                } else {
                    servers.add(sanitized)
                }
            }
            val specificRules = buildList {
                dnsOverride?.rules.orEmpty().map { normalizeLatencyDnsRule(it) }.forEach { add(it) }
                addAll(ConfigRepository.buildOutboundDomainResolverDnsRulesForRuntime(outbounds))
            }

            return DnsConfig(
                servers = servers,
                rules = specificRules + listOf(
                    DnsRule(
                        queryType = listOf("A", "AAAA"),
                        server = LATENCY_LOCAL_DNS_TAG
                    )
                ),
                finalServer = LATENCY_LOCAL_DNS_TAG,
                strategy = "prefer_ipv4"
            )
        }

        private fun normalizeLatencyDnsRule(rule: DnsRule): DnsRule {
            if (!rule.action.isNullOrBlank() || rule.server.isNullOrBlank()) {
                return rule
            }
            return rule.copy(action = "route")
        }

        private fun applyLatencyBootstrapDomainResolver(outbound: Outbound): Outbound {
            val server = outbound.server?.trim().orEmpty()
            if (server.isBlank() || isIpLiteral(server)) return outbound

            val existingResolver = outbound.domainResolver
            val existingResolverServer = existingResolver?.server?.trim().orEmpty()
            if (
                existingResolverServer.isNotBlank() &&
                existingResolverServer != ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG
            ) {
                return outbound
            }
            return outbound.copy(
                domainResolver = (existingResolver ?: DomainResolveConfig()).copy(
                    server = ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG
                )
            )
        }

        private fun isIpLiteral(value: String): Boolean {
            val v = value.trim()
            if (v.isEmpty()) return false
            if (REGEX_IPV4.matches(v)) {
                return v.split(".").all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
            }
            return v.contains(":") && REGEX_IPV6.matches(v)
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
    fun isLibboxAvailable(): Boolean = libboxAvailable
    private suspend fun testOutboundLatencyWithOfflineTemporaryService(
        outbound: Outbound,
        settings: com.kunk.singbox.model.AppSettings? = null,
        dependencyOutbounds: List<Outbound> = emptyList(),
        dnsConfig: DnsConfig? = null
    ): Long = withContext(Dispatchers.IO) {
        if (!libboxAvailable) return@withContext -1L

        val finalSettings = settings ?: SettingsRepository.getInstance(context).settings.first()
        val url = adjustUrlForMode(finalSettings.latencyTestUrl, finalSettings.latencyTestMethod)
        val timeoutMs = finalSettings.latencyTestTimeout

        return@withContext try {
            val fallbackUrl = try {
                if (finalSettings.latencyTestMethod == com.kunk.singbox.model.LatencyTestMethod.TCP) {
                    adjustUrlForMode("http://www.gstatic.com/generate_204", finalSettings.latencyTestMethod)
                } else {
                    adjustUrlForMode("https://www.gstatic.com/generate_204", finalSettings.latencyTestMethod)
                }
            } catch (_: Exception) { url }
            val rtt = testWithTemporaryServiceUrlTest(
                outbound,
                url,
                fallbackUrl,
                timeoutMs,
                dependencyOutbounds,
                finalSettings,
                dnsConfig
            )
            if (rtt >= 0) {
                rtt
            } else {
                testWithLocalHttpProxy(
                    outbound,
                    url,
                    fallbackUrl,
                    timeoutMs,
                    dependencyOutbounds,
                    finalSettings,
                    dnsConfig
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Temporary HTTP proxy latency test failed: ${e.message}")
            -1L
        }
    }
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

    private suspend fun testWithLocalHttpProxy(
        outbound: Outbound,
        targetUrl: String,
        fallbackUrl: String? = null,
        timeoutMs: Int,
        dependencyOutbounds: List<Outbound> = emptyList(),
        settings: AppSettings,
        dnsConfig: DnsConfig? = null
    ): Long = withContext(Dispatchers.IO) {

        httpProxySemaphore.withPermit {
            testWithLocalHttpProxyInternal(
                outbound,
                targetUrl,
                fallbackUrl,
                timeoutMs,
                dependencyOutbounds,
                settings,
                dnsConfig
            )
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "NestedBlockDepth")
    private suspend fun testWithLocalHttpProxyInternal(
        outbound: Outbound,
        targetUrl: String,
        fallbackUrl: String? = null,
        timeoutMs: Int,
        dependencyOutbounds: List<Outbound> = emptyList(),
        settings: AppSettings,
        dnsConfig: DnsConfig? = null
    ): Long = processNetworkBindMutex.withLock {

        val port = allocateLocalPort()
        val inbound = com.kunk.singbox.model.Inbound(
            type = "mixed",
            tag = "test-in",
            listen = "127.0.0.1",
            listenPort = port
        )

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var previousNetwork: Network? = null

        try {
            val testNetwork = resolveLatencyTestNetwork(connectivityManager)
            if (testNetwork != null) {
                previousNetwork = connectivityManager.boundNetworkForProcess
                val bound = connectivityManager.bindProcessToNetwork(testNetwork)
                Log.d(TAG, "bindProcessToNetwork for latency test: bound=$bound, network=$testNetwork")
            } else {
                Log.w(TAG, "No active network available for latency test binding")
            }
        } catch (e: Exception) {
            Log.w(TAG, "bindProcessToNetwork failed: ${e.message}")
        }

        return try {
            val fixedOutbound = prepareLatencyTestOutbound(outbound) ?: return -1L
            val fixedDependencies = dependencyOutbounds.mapNotNull { prepareLatencyTestOutbound(it) }
            val direct = com.kunk.singbox.model.Outbound(type = "direct", tag = "direct")

            val allOutbounds = mutableListOf(fixedOutbound)
            val addedTags = mutableSetOf(fixedOutbound.tag)
            fixedDependencies.forEach { dependency ->
                if (addedTags.add(dependency.tag)) {
                    allOutbounds.add(dependency)
                }
            }
            allOutbounds.add(direct)

            val testDbPath = File(tempDir, "test_${UUID.randomUUID()}.db").absolutePath

            val config = SingBoxConfig(
                log = com.kunk.singbox.model.LogConfig(level = "debug", timestamp = true),

                // sing-box 1.13+: 不设 detour 即为直连
                dns = dnsConfig ?: buildLatencyTestDnsConfig(settings, allOutbounds),
                inbounds = listOf(inbound),
                outbounds = allOutbounds,
                route = com.kunk.singbox.model.RouteConfig(
                    rules = listOf(
                        com.kunk.singbox.model.RouteRule(protocolRaw = listOf("dns"), outbound = "direct"),
                        com.kunk.singbox.model.RouteRule(inbound = listOf("test-in"), outbound = fixedOutbound.tag)
                    ),
                    finalOutbound = "direct",
                    autoDetectInterface = true,
                    defaultDomainResolver = com.kunk.singbox.model.DomainResolveConfig(
                        server = LATENCY_LOCAL_DNS_TAG,
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
            try {
                connectivityManager.bindProcessToNetwork(previousNetwork)
                Log.d(TAG, "Restored process network binding")
            } catch (e: Exception) { Log.w(TAG, "Failed to restore network binding", e) }
        }
    }

    private fun prepareLatencyTestOutbound(outbound: Outbound): Outbound? {
        return OutboundFixer.buildForRuntime(context, outbound)?.let { applyLatencyBootstrapDomainResolver(it) }
    }

    private fun resolveLatencyTestNetwork(connectivityManager: ConnectivityManager): Network? {
        val activeNetwork = connectivityManager.activeNetwork
        val activeCaps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        if (activeNetwork != null && activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) {
            return activeNetwork
        }

        return connectivityManager.allNetworks.firstOrNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: activeNetwork
    }

    private suspend fun testWithTemporaryServiceUrlTest(
        outbound: Outbound,
        targetUrl: String,
        fallbackUrl: String? = null,
        timeoutMs: Int,
        dependencyOutbounds: List<Outbound> = emptyList(),
        settings: AppSettings,
        dnsConfig: DnsConfig? = null
    ): Long = withContext(Dispatchers.IO) {
        testWithLocalHttpProxyInternal(
            outbound,
            targetUrl,
            fallbackUrl,
            timeoutMs,
            dependencyOutbounds,
            settings,
            dnsConfig
        )
    }

    private suspend fun testOutboundsLatencyOfflineWithTemporaryService(
        outbounds: List<Outbound>,
        targetUrl: String,
        timeoutMs: Int,
        method: LatencyTestMethod,
        dnsConfig: DnsConfig? = null,
        onResult: (tag: String, latency: Long) -> Unit
    ) = withContext(Dispatchers.IO) {

        val batchSize = 50

        val settings = SettingsRepository.getInstance(context).settings.first()
        val concurrency = settings.latencyTestConcurrency

        outbounds.chunked(batchSize).forEach { batch ->

            testOutboundsLatencyBatchInternal(batch, targetUrl, timeoutMs, concurrency, settings, dnsConfig, onResult)
        }
    }
    @Suppress("CognitiveComplexMethod", "LongMethod", "LongParameterList")
    private suspend fun testOutboundsLatencyBatchInternal(
        batchOutbounds: List<Outbound>,
        targetUrl: String,
        timeoutMs: Int,
        concurrency: Int,
        settings: AppSettings,
        dnsConfig: DnsConfig? = null,
        onResult: (tag: String, latency: Long) -> Unit
    ) {
        if (batchOutbounds.isEmpty()) return

        val fixedOutbounds = batchOutbounds.mapNotNull { outbound ->
            val fixed = prepareLatencyTestOutbound(outbound)
            if (fixed == null) {
                onResult(outbound.tag, -1L)
            }
            fixed
        }
        if (fixedOutbounds.isEmpty()) return

        val ports: List<Int>
        try {
            ports = allocateMultipleLocalPorts(fixedOutbounds.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to allocate ports for batch test", e)
            batchOutbounds.forEach { onResult(it.tag, -1L) }
            return
        }

        val portToTagMap = ports.zip(fixedOutbounds.map { it.tag }).toMap()
        val config = buildBatchTestConfig(fixedOutbounds, ports, settings, dnsConfig)
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
        ports: List<Int>,
        settings: AppSettings,
        dnsConfig: DnsConfig? = null
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
        val latencyDnsConfig = dnsConfig ?: buildLatencyTestDnsConfig(settings, safeOutbounds)

        val batchTestDbPath = File(tempDir, "batch_test_${UUID.randomUUID()}.db").absolutePath

        return SingBoxConfig(
            log = com.kunk.singbox.model.LogConfig(level = "debug", timestamp = true),
            dns = latencyDnsConfig,
            inbounds = inbounds,
            outbounds = safeOutbounds,
            route = com.kunk.singbox.model.RouteConfig(
                rules = listOf(
                    com.kunk.singbox.model.RouteRule(protocolRaw = listOf("dns"), outbound = "direct")
                ) + rules,
                finalOutbound = "direct",
                autoDetectInterface = true,
                defaultDomainResolver = com.kunk.singbox.model.DomainResolveConfig(
                    server = LATENCY_LOCAL_DNS_TAG,
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
    suspend fun testOutboundLatency(
        outbound: Outbound,
        allOutbounds: List<Outbound> = emptyList(),
        dnsConfig: DnsConfig? = null
    ): Long = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.getInstance(context).settings.first()

        val dependencyOutbounds = if (allOutbounds.isNotEmpty()) {
            resolveDependencyOutbounds(outbound, allOutbounds)
        } else {
            emptyList()
        }

        testOutboundLatencyWithOfflineTemporaryService(outbound, settings, dependencyOutbounds, dnsConfig)
    }
    suspend fun testOutboundsLatency(
        outbounds: List<Outbound>,
        dnsConfig: DnsConfig? = null,
        onResult: (tag: String, latency: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.getInstance(context).settings.first()
        val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)
        val timeoutMs = settings.latencyTestTimeout
        testOutboundsLatencyOfflineWithTemporaryService(
            outbounds,
            url,
            timeoutMs,
            settings.latencyTestMethod,
            dnsConfig,
            onResult
        )
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
    fun hasActiveConnections(): Boolean {
        if (!libboxAvailable) return false

        return try {
            BoxWrapperManager.isAvailable() && VpnStateStore.getActive()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check active connections", e)
            false
        }
    }
    @Suppress("FunctionOnlyReturningConstant")
    fun getActiveConnections(): List<ActiveConnection> = emptyList()
    fun closeConnectionsForApp(packageName: String): Int {
        if (!libboxAvailable) return 0

        return BoxWrapperManager.closeConnectionsForApp(packageName)
    }

    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
    fun closeConnections(packageName: String, uid: Int): Boolean {
        return closeConnectionsForApp(packageName) > 0
    }

    fun cleanup() {}
}

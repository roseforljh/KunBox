package com.kunk.singbox.core

import android.content.Context
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.InetSocketAddress
import java.net.Socket
import com.kunk.singbox.utils.PreciseLatencyTester
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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

        private const val LATENCY_LOCAL_DNS_TAG = "local"
        private const val DEFAULT_PORT_READY_TIMEOUT_MS = 3_000L
        private val REGEX_IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val REGEX_IPV6 = Regex("^[0-9a-fA-F:]+$")

        fun getInstance(context: Context): SingBoxCore {
            return instance ?: synchronized(this) {
                instance ?: SingBoxCore(context.applicationContext).also { instance = it }
            }
        }

        internal fun buildLatencyTestDnsConfig(
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
                ConfigRepository.buildBootstrapDnsServer(
                    localDnsAddress = localDnsAddr,
                    tag = ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG,
                    domainStrategy = "prefer_ipv4"
                ),
                localServer
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
                addAll(ConfigRepository.buildOutboundDomainResolverDnsRules(outbounds))
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

        internal fun applyLatencyBootstrapDomainResolver(outbound: Outbound): Outbound {
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
            Log.i(TAG, "Libbox version=${Libbox.version()}")
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
        val url = adjustUrlForMode(
            AppSettings.requireLatencyTestUrl(finalSettings.latencyTestUrl),
            finalSettings.latencyTestMethod
        )
        val timeoutMs = finalSettings.latencyTestTimeout

        return@withContext try {
            testWithLocalHttpProxy(
                outbound,
                url,
                timeoutMs,
                dependencyOutbounds,
                finalSettings,
                dnsConfig
            )
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

    private fun latencyStandardForMethod(method: LatencyTestMethod): PreciseLatencyTester.Standard {
        return when (method) {
            LatencyTestMethod.HANDSHAKE -> PreciseLatencyTester.Standard.HANDSHAKE
            LatencyTestMethod.URL_TEST -> PreciseLatencyTester.Standard.TOTAL
            LatencyTestMethod.TCP,
            LatencyTestMethod.REAL_RTT -> PreciseLatencyTester.Standard.RTT
        }
    }

    private suspend fun testWithLocalHttpProxy(
        outbound: Outbound,
        targetUrl: String,
        timeoutMs: Int,
        dependencyOutbounds: List<Outbound> = emptyList(),
        settings: AppSettings,
        dnsConfig: DnsConfig? = null
    ): Long = withContext(Dispatchers.IO) {

        httpProxySemaphore.withPermit {
            testWithLocalHttpProxyInternal(
                outbound,
                targetUrl,
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
        timeoutMs: Int,
        dependencyOutbounds: List<Outbound> = emptyList(),
        settings: AppSettings,
        dnsConfig: DnsConfig? = null
    ): Long {

        val port = allocateLocalPort()
        val inbound = com.kunk.singbox.model.Inbound(
            type = "mixed",
            tag = "test-in",
            listen = "127.0.0.1",
            listenPort = port
        )

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

            val configJson = gson.toJson(stripLatencyRuntimeMetadata(config))
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
                    standard = latencyStandardForMethod(settings.latencyTestMethod),
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
        }
    }

    private fun prepareLatencyTestOutbound(outbound: Outbound): Outbound? {
        return OutboundFixer.buildForRuntime(context, outbound)?.let { applyLatencyBootstrapDomainResolver(it) }
    }

    private fun stripLatencyRuntimeMetadata(config: SingBoxConfig): SingBoxConfig {
        return config.copy(
            outbounds = config.outbounds?.map { stripLatencyRuntimeMetadata(it) },
            proxies = config.proxies?.map { stripLatencyRuntimeMetadata(it) }
        )
    }

    private fun stripLatencyRuntimeMetadata(outbound: Outbound): Outbound {
        val tls = outbound.tls ?: return outbound
        val ech = tls.ech ?: return outbound
        if (ech.dnsServer == null) return outbound
        return outbound.copy(tls = tls.copy(ech = ech.copy(dnsServer = null)))
    }

    @Suppress("LongParameterList")
    private suspend fun testOutboundsLatencyOfflineWithTemporaryService(
        outbounds: List<Outbound>,
        targetUrl: String,
        timeoutMs: Int,
        settings: AppSettings,
        dnsConfig: DnsConfig? = null,
        dependencySourceOutbounds: List<Outbound> = outbounds,
        portReadyTimeoutMs: Long = DEFAULT_PORT_READY_TIMEOUT_MS,
        onResult: (tag: String, latency: Long) -> Unit
    ) = withContext(Dispatchers.IO) {

        val batchSize = 50

        outbounds.chunked(batchSize).forEach { batch ->

            testOutboundsLatencyBatchInternal(
                batch,
                targetUrl,
                timeoutMs,
                settings,
                dnsConfig,
                dependencySourceOutbounds,
                portReadyTimeoutMs,
                onResult
            )
        }
    }
    @Suppress("CognitiveComplexMethod", "LongMethod", "LongParameterList")
    private suspend fun testOutboundsLatencyBatchInternal(
        batchOutbounds: List<Outbound>,
        targetUrl: String,
        timeoutMs: Int,
        settings: AppSettings,
        dnsConfig: DnsConfig? = null,
        dependencySourceOutbounds: List<Outbound> = batchOutbounds,
        portReadyTimeoutMs: Long = DEFAULT_PORT_READY_TIMEOUT_MS,
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
        val fixedDependencySourceOutbounds = dependencySourceOutbounds.mapNotNull { prepareLatencyTestOutbound(it) }

        val ports: List<Int>
        try {
            ports = allocateMultipleLocalPorts(fixedOutbounds.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to allocate ports for batch test", e)
            batchOutbounds.forEach { onResult(it.tag, -1L) }
            return
        }

        val portToTagMap = ports.zip(fixedOutbounds.map { it.tag }).toMap()
        val config = buildBatchTestConfig(
            fixedOutbounds,
            ports,
            settings,
            dnsConfig,
            fixedDependencySourceOutbounds
        )
        val configJson = gson.toJson(stripLatencyRuntimeMetadata(config))
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

            val portsReady = waitForPortsReady(ports, portReadyTimeoutMs)
            if (!portsReady) {
                Log.e(TAG, "Batch test: ports not ready")
                batchOutbounds.forEach { onResult(it.tag, -1L) }
                return
            }

            runPreciseLatencyTests(portToTagMap, targetUrl, timeoutMs, settings, onResult)
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

    @Suppress("LongMethod")
    private fun buildBatchTestConfig(
        batchOutbounds: List<Outbound>,
        ports: List<Int>,
        settings: AppSettings,
        dnsConfig: DnsConfig? = null,
        dependencySourceOutbounds: List<Outbound> = batchOutbounds
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
            val dependencies = resolveDependencyOutbounds(outbound, dependencySourceOutbounds)
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

    private suspend fun waitForPortsReady(
        ports: List<Int>,
        portReadyTimeoutMs: Long = DEFAULT_PORT_READY_TIMEOUT_MS
    ): Boolean {
        val boundedTimeoutMs = portReadyTimeoutMs.coerceAtLeast(50L)
        val deadline = System.currentTimeMillis() + boundedTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            val allPortsReady = ports.all { port ->
                isLocalPortReady(port)
            }
            if (allPortsReady) {
                return true
            }
            delay(50)
        }
        Log.e(TAG, "Batch test: ports not ready after ${boundedTimeoutMs}ms")
        return false
    }

    private fun isLocalPortReady(port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.soTimeout = 50
                s.connect(InetSocketAddress("127.0.0.1", port), 50)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun runPreciseLatencyTests(
        portToTagMap: Map<Int, String>,
        targetUrl: String,
        timeoutMs: Int,
        settings: AppSettings,
        onResult: (tag: String, latency: Long) -> Unit
    ) {
        val semaphore = Semaphore(settings.latencyTestConcurrency)
        val standard = latencyStandardForMethod(settings.latencyTestMethod)
        coroutineScope {
            val jobs = portToTagMap.map { (port, originalTag) ->
                async {
                    semaphore.withPermit {
                        val result = PreciseLatencyTester.test(
                            proxyPort = port,
                            url = targetUrl,
                            timeoutMs = timeoutMs,
                            standard = standard,
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
        dnsConfig: DnsConfig? = null,
        timeoutOverrideMs: Int? = null
    ): Long = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.getInstance(context).settings.first()
            .let { currentSettings ->
                timeoutOverrideMs
                    ?.takeIf { it > 0 }
                    ?.let { currentSettings.copy(latencyTestTimeout = it) }
                    ?: currentSettings
            }

        val dependencyOutbounds = if (allOutbounds.isNotEmpty()) {
            resolveDependencyOutbounds(outbound, allOutbounds)
        } else {
            emptyList()
        }

        testOutboundLatencyWithOfflineTemporaryService(
            outbound = outbound,
            settings = settings,
            dependencyOutbounds = dependencyOutbounds,
            dnsConfig = dnsConfig
        )
    }
    @Suppress("LongParameterList")
    suspend fun testOutboundsLatency(
        outbounds: List<Outbound>,
        allOutbounds: List<Outbound> = outbounds,
        dnsConfig: DnsConfig? = null,
        timeoutOverrideMs: Int? = null,
        concurrencyOverride: Int? = null,
        portReadyTimeoutOverrideMs: Long? = null,
        onResult: (tag: String, latency: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.getInstance(context).settings.first()
            .let { currentSettings ->
                val timeout = timeoutOverrideMs?.takeIf { it > 0 } ?: currentSettings.latencyTestTimeout
                val concurrency = concurrencyOverride
                    ?.takeIf { it > 0 }
                    ?.coerceIn(1, 20)
                    ?: currentSettings.latencyTestConcurrency
                currentSettings.copy(
                    latencyTestTimeout = timeout,
                    latencyTestConcurrency = concurrency
                )
            }
        val url = adjustUrlForMode(
            AppSettings.requireLatencyTestUrl(settings.latencyTestUrl),
            settings.latencyTestMethod
        )
        val timeoutMs = settings.latencyTestTimeout
        val portReadyTimeoutMs = portReadyTimeoutOverrideMs?.takeIf { it > 0L } ?: DEFAULT_PORT_READY_TIMEOUT_MS
        testOutboundsLatencyOfflineWithTemporaryService(
            outbounds = outbounds,
            targetUrl = url,
            timeoutMs = timeoutMs,
            settings = settings,
            dnsConfig = dnsConfig,
            dependencySourceOutbounds = allOutbounds,
            portReadyTimeoutMs = portReadyTimeoutMs,
            onResult = onResult
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
    suspend fun validateConfig(config: SingBoxConfig): Result<Unit> = withContext(Dispatchers.IO) {
        if (!libboxAvailable) {
            return@withContext runCatching { gson.toJson(config) }.map { Unit }
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
                        type = "local"
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
    fun cleanup() {}
}

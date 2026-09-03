@file:Suppress("TooManyFunctions", "Indentation", "InvalidPackageDeclaration", "MaxLineLength", "LoopWithTooManyJumpStatements", "LongMethod", "CognitiveComplexMethod", "ComplexCondition", "CyclomaticComplexMethod", "EmptyCatchBlock", "NestedBlockDepth", "ReturnCount", "SwallowedException", "TooGenericExceptionThrown", "UnusedParameter", "UnusedPrivateProperty", "VariableNaming", "NoUnusedImports", "MayBeConst")

package com.kunk.singbox.core

import android.content.Context
import android.os.Process
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.R
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.DnsConfig
import com.kunk.singbox.model.DnsRule
import com.kunk.singbox.model.DnsServer
import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.Endpoint
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.LatencyTestMethod
import com.kunk.singbox.repository.*
import com.kunk.singbox.repository.SettingsRepository
import com.kunk.singbox.repository.TrafficRepository
import com.kunk.singbox.ipc.VpnStateStore
import kotlinx.coroutines.flow.first
import io.nekohasekai.libbox.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.InetSocketAddress
import java.net.Socket
import com.kunk.singbox.utils.PreciseLatencyTester
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class LatencyProbeParts(
    val outbounds: List<Outbound>,
    val endpoints: List<Endpoint>
)

enum class LatencyProbeTrafficKind {
    BACKGROUND_PROBE,
    HEALTH_CHECK
}

internal class TemporaryProbeTrafficRecorder(
    internal val context: Context,
    internal val kind: LatencyProbeTrafficKind
) : CommandClientHandler {
    internal val uploadTotal = AtomicLong(0L)
    internal val downloadTotal = AtomicLong(0L)
    internal val firstSample = CompletableDeferred<Unit>()
    internal val stopped = AtomicBoolean(false)
    internal var client: CommandClient? = null

    fun start() {
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            statusInterval = STATUS_INTERVAL_NS
        }
        val created = Libbox.newCommandClient(this, options)
        client = created
        created.connect()
    }

    suspend fun stopAndRecord() {
        if (!stopped.compareAndSet(false, true)) return
        withTimeoutOrNull(FINAL_SAMPLE_TIMEOUT_MS) { firstSample.await() }
        runCatching { client?.disconnect() }
            .onFailure { Log.w(TAG, "Failed to disconnect probe traffic client", it) }
        client = null

        val upload = uploadTotal.get().coerceAtLeast(0L)
        val download = downloadTotal.get().coerceAtLeast(0L)
        val (nodeId, nodeName) = when (kind) {
            LatencyProbeTrafficKind.BACKGROUND_PROBE -> {
                TrafficRepository.BACKGROUND_PROBE_NODE_ID to context.getString(R.string.traffic_background_probe)
            }
            LatencyProbeTrafficKind.HEALTH_CHECK -> {
                TrafficRepository.HEALTH_CHECK_NODE_ID to context.getString(R.string.traffic_health_check)
            }
        }
        TrafficRepository.getInstance(context).addTraffic(nodeId, upload, download, nodeName)
    }

    override fun connected() = Unit
    override fun disconnected(message: String?) = Unit
    override fun clearLogs() = Unit
    override fun setDefaultLogLevel(level: Int) = Unit
    override fun writeLogs(messageList: LogIterator?) = Unit
    override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
    override fun updateClashMode(newMode: String?) = Unit
    override fun writeConnectionEvents(events: ConnectionEvents?) = Unit
    override fun writeGroups(groups: OutboundGroupIterator?) = Unit
    override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit

    override fun writeStatus(message: StatusMessage?) {
        message ?: return
        uploadTotal.accumulateAndGet(message.uplinkTotal.coerceAtLeast(0L), ::maxOf)
        downloadTotal.accumulateAndGet(message.downlinkTotal.coerceAtLeast(0L), ::maxOf)
        firstSample.complete(Unit)
    }

    private companion object {
        internal const val TAG = "ProbeTrafficRecorder"
        internal const val STATUS_INTERVAL_NS = 50L * 1_000L * 1_000L
        internal const val FINAL_SAMPLE_TIMEOUT_MS = 250L
    }
}

class SingBoxCore private constructor(internal val context: Context) {

    internal val gson = Gson()
    internal val workDir: File = File(context.filesDir, "singbox_work")
    internal val tempDir: File = File(context.cacheDir, "singbox_temp")

    internal var libboxAvailable = false

    // Global lock for libbox operations to prevent native concurrency issues

    @Suppress("UnusedPrivateProperty")
    internal val libboxMutex = kotlinx.coroutines.sync.Mutex()

    internal val httpProxySemaphore = Semaphore(3)

    companion object {
        internal const val TAG = "SingBoxCore"

        internal val libboxSetupDone = AtomicBoolean(false)

        @Volatile
        internal var lastNativeWarmupAt: Long = 0

        @Volatile
        internal var instance: SingBoxCore? = null

        internal const val LATENCY_LOCAL_DNS_TAG = "local"
        internal const val DEFAULT_PORT_READY_TIMEOUT_MS = 3_000L
        internal val REGEX_IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        internal val REGEX_IPV6 = Regex("^[0-9a-fA-F:]+$")

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
            val serverTags = servers.mapNotNullTo(mutableSetOf()) { it.tag }
            val runtimeRules = ConfigRepository.sanitizeDnsRulesForRuntime(
                specificRules + DnsRule(
                    queryType = listOf("A", "AAAA"),
                    action = "route",
                    server = LATENCY_LOCAL_DNS_TAG
                ),
                serverTags
            )

            return DnsConfig(
                servers = servers,
                rules = runtimeRules,
                finalServer = LATENCY_LOCAL_DNS_TAG,
                strategy = "prefer_ipv4"
            )
        }

        internal fun normalizeLatencyDnsRule(rule: DnsRule): DnsRule {
            if (!rule.action.isNullOrBlank() || rule.server.isNullOrBlank()) {
                return rule
            }
            return rule.copy(action = "route")
        }

        internal fun applyLatencyBootstrapDomainResolver(outbound: Outbound): Outbound {
            return ConfigRepository.applyDefaultOutboundDomainResolver(
                listOf(outbound),
                ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG
            ).single()
        }

        internal fun isWireGuardOutbound(outbound: Outbound): Boolean {
            return outbound.type.equals("wireguard", ignoreCase = true)
        }

        /** WireGuard 延迟探测：规范化 peers，域名 peer 补 bootstrap resolver。 */
        internal fun prepareWireGuardLatencyTarget(outbound: Outbound): Outbound? {
            if (!isWireGuardOutbound(outbound)) return null
            val normalizedPeers = ConfigRepository.normalizeWireGuardPeersForRuntime(outbound.peers)
            val withPeers = outbound.copy(peers = normalizedPeers)
            val needsResolver = normalizedPeers.orEmpty().any { peer ->
                val host = peer.server?.trim().orEmpty()
                host.isNotBlank() && !isIpLiteral(host)
            }
            val existing = withPeers.domainResolver
            val resolverServer = existing?.server?.trim()?.takeIf(String::isNotBlank)
                ?: ConfigRepository.DEFAULT_ROUTE_DOMAIN_RESOLVER_TAG.takeIf {
                    needsResolver || !withPeers.domainStrategy.isNullOrBlank()
                }
            val resolver = resolverServer?.let {
                (existing ?: DomainResolveConfig()).copy(
                    server = it,
                    strategy = existing?.strategy ?: withPeers.domainStrategy
                )
            }
            return withPeers.copy(
                domainResolver = resolver,
                domainStrategy = null
            )
        }

        /**
         * 组装延迟临时配置：非 WG 进 outbounds，WG 转 endpoint。
         * 任一目标无法转换时返回 null。
         */
        internal fun buildLatencyProbeParts(
            targets: List<Outbound>,
            resolveDependencies: (Outbound) -> List<Outbound> = { emptyList() }
        ): LatencyProbeParts? {
            if (targets.isEmpty()) return null
            val endpoints = ArrayList<Endpoint>()
            val outbounds = ArrayList<Outbound>()
            val addedTags = mutableSetOf<String>()

            for (target in targets) {
                if (!appendLatencyProbeNode(target, endpoints, outbounds, addedTags)) {
                    return null
                }
                for (dep in resolveDependencies(target)) {
                    if (!appendLatencyProbeNode(dep, endpoints, outbounds, addedTags)) {
                        return null
                    }
                }
            }

            if (outbounds.none { it.tag == "direct" }) {
                outbounds.add(Outbound(type = "direct", tag = "direct"))
            }
            return LatencyProbeParts(outbounds = outbounds, endpoints = endpoints)
        }

        internal fun appendLatencyProbeNode(
            node: Outbound,
            endpoints: MutableList<Endpoint>,
            outbounds: MutableList<Outbound>,
            addedTags: MutableSet<String>
        ): Boolean {
            if (!addedTags.add(node.tag)) return true
            return if (isWireGuardOutbound(node)) {
                val endpoint = ConfigRepository.convertWireGuardOutboundToEndpoint(node)
                if (endpoint == null) {
                    false
                } else {
                    endpoints.add(endpoint)
                    true
                }
            } else {
                outbounds.add(node)
                true
            }
        }

        internal fun isIpLiteral(value: String): Boolean {
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

    internal fun initLibbox(): Boolean {
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
        dnsConfig: DnsConfig? = null,
        trafficKind: LatencyProbeTrafficKind
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
                dnsConfig,
                trafficKind
            )
        } catch (e: Exception) {
            Log.w(TAG, "Temporary HTTP proxy latency test failed: ${e.message}")
            -1L
        }
    }

    internal fun resolveDependencyOutbounds(
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

    internal fun adjustUrlForMode(original: String, method: LatencyTestMethod): String {
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

    internal fun latencyStandardForMethod(method: LatencyTestMethod): PreciseLatencyTester.Standard {
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
        dnsConfig: DnsConfig? = null,
        trafficKind: LatencyProbeTrafficKind
    ): Long = withContext(Dispatchers.IO) {

        httpProxySemaphore.withPermit {
            libboxMutex.withLock {
                testWithLocalHttpProxyInternal(
                    outbound,
                    targetUrl,
                    timeoutMs,
                    dependencyOutbounds,
                    settings,
                    dnsConfig,
                    trafficKind
                )
            }
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "CognitiveComplexMethod", "NestedBlockDepth")
    private suspend fun testWithLocalHttpProxyInternal(
        outbound: Outbound,
        targetUrl: String,
        timeoutMs: Int,
        dependencyOutbounds: List<Outbound> = emptyList(),
        settings: AppSettings,
        dnsConfig: DnsConfig? = null,
        trafficKind: LatencyProbeTrafficKind
    ): Long {

        val port = allocateLocalPort()
        val inbound = com.kunk.singbox.model.Inbound(
            type = "mixed",
            tag = "test-in",
            listen = "127.0.0.1",
            listenPort = port
        )

        return try {
            // WireGuard 仅作为 endpoint；逻辑 outbound 仅用于路由 tag
            val preparedTarget = prepareLatencyProbeTarget(outbound) ?: return -1L
            val preparedDependencies = dependencyOutbounds.mapNotNull { prepareLatencyProbeTarget(it) }
            val probeParts = assembleLatencyProbeParts(
                targets = listOf(preparedTarget),
                dependencySourceOutbounds = preparedDependencies
            ) ?: return -1L

            val testDbPath = File(tempDir, "test_${UUID.randomUUID()}.db").absolutePath
            val dnsOutbounds = probeParts.outbounds + listOf(preparedTarget)
            val config = SingBoxConfig(
                log = com.kunk.singbox.model.LogConfig(level = "debug", timestamp = true),
                // sing-box 1.13+: 不设 detour 即为直连
                dns = dnsConfig ?: buildLatencyTestDnsConfig(settings, dnsOutbounds),
                inbounds = listOf(inbound),
                outbounds = probeParts.outbounds,
                endpoints = probeParts.endpoints.takeIf { it.isNotEmpty() },
                route = com.kunk.singbox.model.RouteConfig(
                    rules = listOf(
                        com.kunk.singbox.model.RouteRule(protocolRaw = listOf("dns"), outbound = "direct"),
                        com.kunk.singbox.model.RouteRule(inbound = listOf("test-in"), outbound = preparedTarget.tag)
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
            var trafficRecorder: TemporaryProbeTrafficRecorder? = null
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
                trafficRecorder = TemporaryProbeTrafficRecorder(context, trafficKind).also { it.start() }

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
                trafficRecorder?.stopAndRecord()
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

    suspend fun testOutboundLatency(
        outbound: Outbound,
        allOutbounds: List<Outbound> = emptyList(),
        dnsConfig: DnsConfig? = null,
        timeoutOverrideMs: Int? = null,
        trafficKind: LatencyProbeTrafficKind = LatencyProbeTrafficKind.BACKGROUND_PROBE
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
            dnsConfig = dnsConfig,
            trafficKind = trafficKind
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
        trafficKind: LatencyProbeTrafficKind = LatencyProbeTrafficKind.BACKGROUND_PROBE,
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
            trafficKind = trafficKind,
            onResult = onResult
        )
    }

    internal fun allocateLocalPort(): Int {
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

    internal fun isPortAvailable(port: Int): Boolean {
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

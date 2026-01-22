package com.kunk.singbox.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Process
import android.util.Log
import com.google.gson.Gson
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.SingBoxConfig
import com.kunk.singbox.model.LatencyTestMethod
import com.kunk.singbox.repository.SettingsRepository
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
import kotlinx.coroutines.sync.withLock
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
 * Sing-box 核心封装类
 * 负责与 libbox 交互，提供延迟测试等功能
 *
 * 如果 libbox 不可用，将使用降级方案进行测试
 */
class SingBoxCore private constructor(private val context: Context) {

    private val gson = Gson()
    private val workDir: File = File(context.filesDir, "singbox_work")
    private val tempDir: File = File(context.cacheDir, "singbox_temp")

    // libbox 是否可用
    private var libboxAvailable = false

    // Global lock for libbox operations to prevent native concurrency issues
    // 注意: 这个 mutex 只用于需要独占资源的操作(如 HTTP proxy fallback)
    private val libboxMutex = kotlinx.coroutines.sync.Mutex()

    // 优化: 使用 Semaphore 限制 HTTP 代理并发数,允许一定程度的并发
    // 批量测试时,不再完全串行化,性能提升 2-3倍
    private val httpProxySemaphore = Semaphore(3) // 最多3个并发HTTP代理测试

    companion object {
        private const val TAG = "SingBoxCore"

        private val libboxSetupDone = AtomicBoolean(false)
        // 最近一次原生测速预热时间（避免每次都预热）
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
        // 确保工作目录存在
        workDir.mkdirs()
        tempDir.mkdirs()

        // 尝试初始化 libbox
        libboxAvailable = initLibbox()

        if (!libboxAvailable) {
            Log.w(TAG, "Libbox not available, using fallback mode")
        }
    }

    private fun initLibbox(): Boolean {
        return try {
            Libbox.version() // Simple check
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
     * 检查 libbox 是否可用
     */
    fun isLibboxAvailable(): Boolean = libboxAvailable

    private fun maybeWarmupNative(url: String) {
        // No-op for official libbox without urlTest
    }

    /**
     * 使用 Libbox 原生方法进行延迟测试
     * 优先尝试调用 NekoBox 内核的 urlTest 方法，失败则回退到本地 HTTP 代理测速
     */
    private suspend fun testOutboundLatencyWithLibbox(
        outbound: Outbound,
        settings: com.kunk.singbox.model.AppSettings? = null,
        dependencyOutbounds: List<Outbound> = emptyList()
    ): Long = withContext(Dispatchers.IO) {
        if (!libboxAvailable) return@withContext -1L

        val finalSettings = settings ?: SettingsRepository.getInstance(context).settings.first()
        val url = adjustUrlForMode(finalSettings.latencyTestUrl, finalSettings.latencyTestMethod)
        val timeoutMs = finalSettings.latencyTestTimeout

        // 尝试使用 NekoBox 原生 urlTest
        // Remove mutex to allow concurrent testing
        val nativeRtt = testWithLibboxStaticUrlTest(outbound, url, timeoutMs, finalSettings.latencyTestMethod)

        if (nativeRtt >= 0) {
            return@withContext nativeRtt
        }

        // 回退方案：本地 HTTP 代理测速
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
     * 解析 outbound 的依赖 outbounds
     * 例如 SS + ShadowTLS 节点，SS 的 detour 字段指向 shadowtls outbound
     * @param outbound 主节点
     * @param allOutbounds 完整的 outbound 列表（包含所有潜在依赖）
     * @return 依赖的 outbound 列表（不包含主节点本身）
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
                // 递归解析依赖的依赖
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
        // 优化: 使用 Semaphore 替代 Mutex,允许有限并发
        // 原因: 虽然每个测试都启动临时 service,但通过限制并发数(3个)
        //       可以在保证稳定性的同时,显著提升批量测试性能
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

        // 关键修复: 在 VPN 未运行时,将进程绑定到默认网络
        // 这样 sing-box 创建的所有 socket 都会使用物理网络,而不是尝试 auto-detect
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val vpnRunning = com.kunk.singbox.service.SingBoxService.instance != null
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

            // 构建 outbound 列表，包含主节点和依赖的辅助节点（如 shadowtls）
            val allOutbounds = mutableListOf(outbound)
            allOutbounds.addAll(dependencyOutbounds)
            allOutbounds.add(direct)

            // 为测试服务生成唯一的临时数据库路径,避免与 VPN 服务的数据库冲突
            // 使用 UUID 确保绝对唯一性,防止高并发时时间戳重复导致路径冲突
            val testDbPath = File(tempDir, "test_${UUID.randomUUID()}.db").absolutePath

            val config = SingBoxConfig(
                log = com.kunk.singbox.model.LogConfig(level = "debug", timestamp = true), // 使用 debug 级别诊断问题
                // 测速服务使用纯 IP DNS，避免 DoH 请求被 VPN 拦截
                // 添加多个 DNS 服务器作为备份，提高可靠性
                // 关键: 必须设置 finalServer，否则 sing-box 不知道使用哪个 DNS 服务器
                dns = com.kunk.singbox.model.DnsConfig(
                    servers = listOf(
                        com.kunk.singbox.model.DnsServer(
                            tag = "dns-direct",
                            address = "223.5.5.5",
                            detour = "direct",
                            strategy = "ipv4_only"
                        ),
                        com.kunk.singbox.model.DnsServer(
                            tag = "dns-backup",
                            address = "119.29.29.29",
                            detour = "direct",
                            strategy = "ipv4_only"
                        )
                    ),
                    finalServer = "dns-direct", // 指定默认 DNS 服务器
                    strategy = "ipv4_only" // 全局 DNS 策略
                ),
                inbounds = listOf(inbound),
                outbounds = allOutbounds,
                route = com.kunk.singbox.model.RouteConfig(
                    rules = listOf(
                        com.kunk.singbox.model.RouteRule(protocolRaw = listOf("dns"), outbound = "direct"),
                        com.kunk.singbox.model.RouteRule(inbound = listOf("test-in"), outbound = outbound.tag)
                    ),
                    finalOutbound = "direct",
                    // 关键修复: 不设置 defaultInterface，因为测速服务没有权限绑定到特定接口
                    // 只启用 autoDetectInterface，通过 autoDetectInterfaceControl 回调来保护 socket
                    autoDetectInterface = true
                ),
                // 完全禁用测试服务的缓存,避免数据库冲突
                // 关键: 必须指定唯一的临时路径,防止与 VPN 服务的 cache.db 冲突
                // bbolt 不支持多进程并发访问同一数据库文件,会触发 "page already freed" panic
                experimental = com.kunk.singbox.model.ExperimentalConfig(
                    cacheFile = com.kunk.singbox.model.CacheFileConfig(
                        enabled = false, // 禁用缓存
                        path = testDbPath, // 使用唯一的临时路径
                        storeFakeip = false
                    )
                )
            )

            val configJson = gson.toJson(config)
            var service: BoxService? = null
            try {
                ensureLibboxSetup(context)
                val platformInterface = TestPlatformInterface(context)
                service = Libbox.newService(configJson, platformInterface)
                service.start()

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
                try { service?.close() } catch (e: Exception) { Log.w(TAG, "Failed to close service", e) }
                // 清理临时数据库文件,防止泄漏
                try {
                    File(testDbPath).delete()
                    File("$testDbPath-shm").delete() // SQLite WAL 模式的共享内存文件
                    File("$testDbPath-wal").delete() // SQLite WAL 日志文件
                } catch (e: Exception) { Log.w(TAG, "Failed to delete temp db files", e) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Local HTTP proxy setup failed", e)
            -1L
        } finally {
            // 恢复进程网络绑定状态
            if (!vpnRunning) {
                try {
                    connectivityManager.bindProcessToNetwork(previousNetwork)
                    Log.d(TAG, "Restored process network binding")
                } catch (e: Exception) { Log.w(TAG, "Failed to restore network binding", e) }
            }
        }
    }

    /**
     * 尝试使用 libbox 原生 urlTest 方法进行延迟测试
     *
     * KunBox 扩展内核 (v1.1.0+) 包含 URLTestOutbound 和 URLTestStandalone 方法:
     * - URLTestOutbound: VPN 运行时，使用当前 BoxService 实例测试
     * - URLTestStandalone: VPN 未运行时，创建临时实例测试
     *
     * 如果内核不支持（未使用 KunBox 扩展编译），回退到本地 HTTP 代理测速
     *
     * @return 延迟时间(毫秒), -1 表示不支持或测试失败
     */
    private suspend fun testWithLibboxStaticUrlTest(
        outbound: Outbound,
        targetUrl: String,
        timeoutMs: Int,
        method: LatencyTestMethod
    ): Long = withContext(Dispatchers.IO) {
        try {
            // 使用 KunBox 扩展内核的 urlTestOutbound 方法
            val boxService = BoxWrapperManager.getBoxService()
            if (boxService != null) {
                // Go 导出到 Java 时方法名首字母小写: URLTestOutbound -> urlTestOutbound
                val result = boxService.urlTestOutbound(outbound.tag, targetUrl, timeoutMs)
                if (result >= 0) {
                    Log.d(TAG, "Native URLTest ${outbound.tag}: ${result}ms")
                    return@withContext result.toLong()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native URLTest failed: ${e.message}")
        }

        // 不支持或失败，返回 -1 触发回退
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
        // 尝试调用 native 方法 (如果 VPN 正在运行)
        if (VpnStateStore.getActive() && libboxAvailable) {
            val rtt = testWithLibboxStaticUrlTest(outbound, targetUrl, timeoutMs, method)
            if (rtt >= 0) return@withContext rtt
        }

        // 内核不支持或未运行，直接走 HTTP 代理测速
        testWithLocalHttpProxyInternal(outbound, targetUrl, fallbackUrl, timeoutMs, dependencyOutbounds)
    }

    private suspend fun testOutboundsLatencyOfflineWithTemporaryService(
        outbounds: List<Outbound>,
        targetUrl: String,
        fallbackUrl: String? = null,
        timeoutMs: Int,
        method: LatencyTestMethod,
        onResult: (tag: String, latency: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        // 使用批量测试优化
        // 为了避免单次启动配置过大，我们按 50 个节点一批进行处理
        // 这样既能享受批量优势，又能避免内存或配置过大问题
        val batchSize = 50

        // 获取设置中的并发数
        val settings = SettingsRepository.getInstance(context).settings.first()
        val concurrency = settings.latencyTestConcurrency

        outbounds.chunked(batchSize).forEach { batch ->
            // 对每一批节点启动一次服务
            testOutboundsLatencyBatchInternal(batch, targetUrl, fallbackUrl, timeoutMs, concurrency, onResult)
        }
    }

    private suspend fun testOutboundsLatencyBatchInternal(
        batchOutbounds: List<Outbound>,
        targetUrl: String,
        fallbackUrl: String?,
        timeoutMs: Int,
        concurrency: Int,
        onResult: (tag: String, latency: Long) -> Unit
    ) {
        // 使用 Mutex 确保同一时间只有一个测试服务在运行
        libboxMutex.withLock {
            // 关键修复: 在 VPN 未运行时,将进程绑定到默认网络
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val vpnRunning = com.kunk.singbox.service.SingBoxService.instance != null
            var previousNetwork: Network? = null

            if (!vpnRunning) {
                try {
                    val activeNetwork = connectivityManager.activeNetwork
                    if (activeNetwork != null) {
                        previousNetwork = connectivityManager.boundNetworkForProcess
                        val bound = connectivityManager.bindProcessToNetwork(activeNetwork)
                        Log.d(TAG, "Batch test bindProcessToNetwork: bound=$bound, network=$activeNetwork")
                    } else {
                        Log.w(TAG, "Batch test: No active network available for binding")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Batch test bindProcessToNetwork failed: ${e.message}")
                }
            }

            // 1. 分配端口池
            // 我们为批次中的每个节点分配一个独立的本地端口
            // 这样就可以通过连接不同的端口来区分不同的节点，完全避开鉴权问题
            val ports = try {
                allocateMultipleLocalPorts(batchOutbounds.size)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to allocate ports for batch test", e)
                batchOutbounds.forEach { onResult(it.tag, -1L) }
                // 恢复网络绑定后返回
                if (!vpnRunning) {
                    try { connectivityManager.bindProcessToNetwork(previousNetwork) } catch (e: Exception) { Log.w(TAG, "Failed to restore network binding after port allocation failure", e) }
                }
                return
            }

            // 2. 构建 Inbounds 和 Rules
            val inbounds = ArrayList<com.kunk.singbox.model.Inbound>()
            val rules = ArrayList<com.kunk.singbox.model.RouteRule>()

            // 建立 端口 -> 原始Tag 的映射，方便后续测试
            val portToTagMap = mutableMapOf<Int, String>()

            batchOutbounds.forEachIndexed { index, outbound ->
                val port = ports[index]
                val inboundTag = "test-in-$index"
                portToTagMap[port] = outbound.tag

                // 每个节点对应一个监听端口
                inbounds.add(com.kunk.singbox.model.Inbound(
                    type = "mixed",
                    tag = inboundTag,
                    listen = "127.0.0.1",
                    listenPort = port
                ))

                // 该端口的流量强制转发到对应节点
                rules.add(com.kunk.singbox.model.RouteRule(
                    inbound = listOf(inboundTag),
                    outbound = outbound.tag
                ))
            }

            // 测速服务使用纯 IP DNS，避免 DoH 请求被 VPN 拦截
            // 添加多个 DNS 服务器作为备份，提高可靠性
            // 关键: 必须设置 finalServer，否则 sing-box 不知道使用哪个 DNS 服务器
            val dnsConfig = com.kunk.singbox.model.DnsConfig(
                servers = listOf(
                    com.kunk.singbox.model.DnsServer(tag = "dns-direct", address = "223.5.5.5", detour = "direct", strategy = "ipv4_only"),
                    com.kunk.singbox.model.DnsServer(tag = "dns-backup", address = "119.29.29.29", detour = "direct", strategy = "ipv4_only")
                ),
                finalServer = "dns-direct", // 指定默认 DNS 服务器
                strategy = "ipv4_only" // 全局 DNS 策略
            )

            // 确保有 direct 和 block
            // 关键修复: 先收集所有节点的依赖 outbounds（如 shadowtls）
            val safeOutbounds = ArrayList(batchOutbounds)
            val addedTags = batchOutbounds.map { it.tag }.toMutableSet()

            // 解析所有节点的依赖
            for (outbound in batchOutbounds) {
                val dependencies = resolveDependencyOutbounds(outbound, batchOutbounds)
                for (dep in dependencies) {
                    if (addedTags.add(dep.tag)) {
                        safeOutbounds.add(dep)
                    }
                }
            }

            if (safeOutbounds.none { it.tag == "direct" }) safeOutbounds.add(com.kunk.singbox.model.Outbound(type = "direct", tag = "direct"))
            if (safeOutbounds.none { it.tag == "block" }) safeOutbounds.add(com.kunk.singbox.model.Outbound(type = "block", tag = "block"))
            if (safeOutbounds.none { it.tag == "dns-out" }) safeOutbounds.add(com.kunk.singbox.model.Outbound(type = "dns", tag = "dns-out"))

            // 为批量测试服务生成唯一的临时数据库路径,避免与 VPN 服务的数据库冲突
            // 使用 UUID 确保绝对唯一性,防止高并发时时间戳重复导致路径冲突
            val batchTestDbPath = File(tempDir, "batch_test_${UUID.randomUUID()}.db").absolutePath

            val config = SingBoxConfig(
                log = com.kunk.singbox.model.LogConfig(level = "debug", timestamp = true), // 使用 debug 级别诊断问题
                dns = dnsConfig,
                inbounds = inbounds,
                outbounds = safeOutbounds,
                route = com.kunk.singbox.model.RouteConfig(
                    rules = listOf(
                        com.kunk.singbox.model.RouteRule(protocolRaw = listOf("dns"), outbound = "direct")
                    ) + rules,
                    finalOutbound = "direct",
                    // 关键修复: 不设置 defaultInterface，因为测速服务没有权限绑定到特定接口
                    // 只启用 autoDetectInterface，通过 autoDetectInterfaceControl 回调来保护 socket
                    autoDetectInterface = true
                ),
                // 完全禁用测试服务的缓存,避免数据库冲突
                // 关键: 必须指定唯一的临时路径,防止与 VPN 服务的 cache.db 冲突
                // bbolt 不支持多进程并发访问同一数据库文件,会触发 "page already freed" panic
                experimental = com.kunk.singbox.model.ExperimentalConfig(
                    cacheFile = com.kunk.singbox.model.CacheFileConfig(
                        enabled = false, // 禁用缓存
                        path = batchTestDbPath, // 使用唯一的临时路径
                        storeFakeip = false
                    )
                )
            )

            // 3. 启动服务
            val configJson = gson.toJson(config)
            // 诊断：打印第一个节点的配置（仅调试用）
            Log.d(TAG, "Batch test config: outbounds count=${safeOutbounds.size}, first node type=${batchOutbounds.firstOrNull()?.type}")
            if (batchOutbounds.isNotEmpty()) {
                val firstNode = batchOutbounds.first()
                Log.d(TAG, "First node: tag=${firstNode.tag}, type=${firstNode.type}, server=${firstNode.server}, port=${firstNode.serverPort}")
                Log.d(TAG, "First node TLS: enabled=${firstNode.tls?.enabled}, sni=${firstNode.tls?.serverName}, reality=${firstNode.tls?.reality?.enabled}")
                // 打印完整的第一个节点配置 JSON（仅调试）
                Log.d(TAG, "First node full config: ${gson.toJson(firstNode)}")
            }
            var service: BoxService? = null

            try {
                ensureLibboxSetup(context)
                val platformInterface = TestPlatformInterface(context)
                service = Libbox.newService(configJson, platformInterface)
                service.start()
                Log.d(TAG, "Batch test: service started, waiting for port ${ports.first()}")

                // 等待第一个端口就绪 (通常这就代表服务启动了)
                // 增加等待时间以确保服务完全就绪，特别是对于大批量节点
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
                    Log.e(TAG, "Batch test: port $firstPort not ready after 3s, aborting")
                    batchOutbounds.forEach { onResult(it.tag, -1L) }
                    return
                }
                Log.d(TAG, "Batch test: port ready, starting tests for ${batchOutbounds.size} nodes")

                // 智能就绪检测：验证多个端口就绪状态，而非固定等待
                // 抽样检测前几个端口（最多 3 个），确保服务完全就绪
                val portsToCheck = ports.take(minOf(3, ports.size))
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
                    // 兜底短暂等待
                    delay(100)
                }

                // 4. 并发测试（使用精确延迟测试器）
                val semaphore = Semaphore(concurrency)

                coroutineScope {
                    val jobs = portToTagMap.map { (port, originalTag) ->
                        async {
                            semaphore.withPermit {
                                // 使用精确延迟测试器进行批量测试
                                suspend fun runPreciseTest(url: String): Long {
                                    val result = PreciseLatencyTester.test(
                                        proxyPort = port,
                                        url = url,
                                        timeoutMs = timeoutMs,
                                        standard = PreciseLatencyTester.Standard.RTT,
                                        warmup = false
                                    )
                                    return if (result.isSuccess && result.latencyMs <= timeoutMs) {
                                        result.latencyMs
                                    } else {
                                        -1L
                                    }
                                }

                                // 带重试的精确测试
                                suspend fun runWithRetry(url: String, maxRetries: Int = 2): Long {
                                    var lastResult = -1L
                                    for (attempt in 0 until maxRetries) {
                                        lastResult = runPreciseTest(url)
                                        if (lastResult >= 0) {
                                            return lastResult
                                        }
                                        if (attempt < maxRetries - 1) {
                                            delay(100L * (attempt + 1))
                                        }
                                    }
                                    return lastResult
                                }

                                // 执行精确延迟测试（带 fallback）
                                var latency = runWithRetry(targetUrl)
                                if (latency < 0 && fallbackUrl != null && fallbackUrl != targetUrl) {
                                    latency = runWithRetry(fallbackUrl)
                                    if (latency < 0) {
                                        Log.w(TAG, "Batch test node $originalTag: both URLs failed")
                                    }
                                }

                                if (latency > 0) {
                                    Log.d(TAG, "Batch test node $originalTag: ${latency}ms")
                                }
                                onResult(originalTag, latency)
                            }
                        }
                    }
                    jobs.awaitAll()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Batch test failed", e)
                batchOutbounds.forEach { onResult(it.tag, -1L) }
            } finally {
                try { service?.close() } catch (e: Exception) { Log.w(TAG, "Failed to close batch test service", e) }
                // 清理临时数据库文件,防止泄漏
                try {
                    File(batchTestDbPath).delete()
                    File("$batchTestDbPath-shm").delete() // SQLite WAL 模式的共享内存文件
                    File("$batchTestDbPath-wal").delete() // SQLite WAL 日志文件
                } catch (e: Exception) { Log.w(TAG, "Failed to delete batch test temp db files", e) }
                // 恢复进程网络绑定状态
                if (!vpnRunning) {
                    try {
                        connectivityManager.bindProcessToNetwork(previousNetwork)
                        Log.d(TAG, "Batch test: Restored process network binding")
                    } catch (e: Exception) { Log.w(TAG, "Batch test: Failed to restore network binding", e) }
                }
            }
        }
    }

    /**
     * 分配多个本地端口用于批量测试
     *
     * @param count 需要分配的端口数量
     * @return 已分配的端口列表
     * @throws RuntimeException 如果无法分配足够的端口
     *
     * 注意: 此方法存在微小的竞态条件窗口 (TOCTOU - Time-of-check to time-of-use)
     * 在 ServerSocket 关闭后到 sing-box 绑定端口之间,理论上其他进程可能占用端口。
     * 但在本地回环接口上,此风险极低。如果发生冲突,上层逻辑会捕获异常并重试。
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
            // 失败时释放已分配的端口
            sockets.forEach { runCatching { it.close() } }
            throw RuntimeException("Failed to allocate $count ports (allocated ${ports.size})", e)
        }
        // 全部关闭以释放端口给 sing-box 使用
        // 在关闭后立即返回,最小化竞态窗口
        sockets.forEach { runCatching { it.close() } }
        return ports
    }

    /**
     * 测试单个节点的延迟
     * @param outbound 节点出站配置
     * @param allOutbounds 可选的完整 outbound 列表，用于解析依赖（如 SS+ShadowTLS）
     * @return 延迟时间（毫秒），-1 表示测试失败
     */
    suspend fun testOutboundLatency(
        outbound: Outbound,
        allOutbounds: List<Outbound> = emptyList()
    ): Long = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.getInstance(context).settings.first()
        val timeoutMs = settings.latencyTestTimeout

        // 解析依赖的 outbound（如 SS 节点依赖的 shadowtls）
        val dependencyOutbounds = if (allOutbounds.isNotEmpty()) {
            resolveDependencyOutbounds(outbound, allOutbounds)
        } else {
            emptyList()
        }

        // When VPN is running, prefer running-instance URLTest.
        // When VPN is stopped, try Libbox static URLTest first, then local HTTP proxy fallback.
        if (VpnStateStore.getActive()) {
            return@withContext testOutboundLatencyWithLibbox(outbound, settings, dependencyOutbounds)
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
     * 批量测试节点延迟
     * @param outbounds 节点列表
     * @param onResult 每个节点测试完成后的回调
     */
    suspend fun testOutboundsLatency(
        outbounds: List<Outbound>,
        onResult: (tag: String, latency: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val settings = SettingsRepository.getInstance(context).settings.first()

        // Check if native URLTest is supported (currently hardcoded to -1/false in testWithLibboxStaticUrlTest)
        // If VPN is running AND native URLTest is supported, use it.
        // Otherwise (VPN off OR native URLTest unsupported), use our efficient batch test.
        val isNativeUrlTestSupported = false // Currently false for official libbox

        if (libboxAvailable && VpnStateStore.getActive() && isNativeUrlTestSupported) {
            // 先做一次轻量预热，避免批量首个请求落在 link 验证/路由冷启动窗口
            try {
                val warmupOutbound = outbounds.firstOrNull()
                if (warmupOutbound != null) {
                    val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)
                    maybeWarmupNative(url)
                }
            } catch (e: Exception) { Log.w(TAG, "Warmup native test failed", e) }
            // 提高并发数以加快批量测速
            val semaphore = Semaphore(permits = 10)
            coroutineScope {
                val jobs = outbounds.map { outbound ->
                    async {
                        semaphore.withPermit {
                            val latency = testOutboundLatencyWithLibbox(outbound, settings)
                            onResult(outbound.tag, latency)
                        }
                    }
                }
                jobs.awaitAll()
            }
            return@withContext
        }

        // Use efficient batch test (Multi-port routing)
        // This works whether VPN is running or not, as it uses separate ports and protects sockets.
        val url = adjustUrlForMode(settings.latencyTestUrl, settings.latencyTestMethod)
        val timeoutMs = settings.latencyTestTimeout
        val fallbackUrl = try {
            if (settings.latencyTestMethod == com.kunk.singbox.model.LatencyTestMethod.TCP) {
                adjustUrlForMode("http://www.gstatic.com/generate_204", settings.latencyTestMethod)
            } else {
                adjustUrlForMode("https://www.gstatic.com/generate_204", settings.latencyTestMethod)
            }
        } catch (_: Exception) { url }
        testOutboundsLatencyOfflineWithTemporaryService(outbounds, url, fallbackUrl, timeoutMs, settings.latencyTestMethod, onResult)
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
     * 获取当前活动的物理网络接口名称（非 VPN）
     * 用于测速服务显式绑定到物理网络，避免流量被 VPN 拦截
     */
    private fun getPhysicalNetworkInterface(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null

        // 获取活动网络
        val activeNetwork = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return null

        // 如果活动网络是 VPN，需要找到底层物理网络
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            // 遍历所有网络，找到非 VPN 的物理网络
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

        // 活动网络不是 VPN，直接获取其接口名
        val linkProps = cm.getLinkProperties(activeNetwork)
        return linkProps?.interfaceName
    }

    /**
     * 验证配置是否有效
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

    fun formatConfig(config: SingBoxConfig): String = gson.toJson(config)

    // --- Inner Classes for Platform Interface ---

    private class TestPlatformInterface(private val context: Context) : PlatformInterface {
        private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        override fun autoDetectInterfaceControl(fd: Int) {
            // 如果 VPN 正在运行，必须 protect 测速 socket，否则流量会被 VPN 拦截
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

            // VPN 未运行时，需要将 socket 绑定到默认网络
            // 否则 sing-box 无法确定使用哪个网络接口，报错 "no available network interface"
            try {
                val network = connectivityManager.activeNetwork
                if (network != null) {
                    // 使用 ParcelFileDescriptor 包装 fd，然后绑定到网络
                    val pfd = android.os.ParcelFileDescriptor.adoptFd(fd)
                    try {
                        network.bindSocket(pfd.fileDescriptor)
                        Log.d(TAG, "autoDetectInterfaceControl: bound fd=$fd to network")
                    } finally {
                        // detachFd 防止 ParcelFileDescriptor.close() 关闭原始 fd
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
            // 关键修复: 必须立即通知 sing-box 当前的默认网络接口
            // 否则 sing-box 会报 "no available network interface" 错误
            // 注意: 不能在 cgo 回调中注册 NetworkCallback，会导致 Go runtime 栈溢出崩溃
            // 但我们可以同步获取当前网络状态并立即通知
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
            // 测速服务是短暂的，无需清理网络监听
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

        // 关键: 必须返回 true，否则 sing-box 不会调用 autoDetectInterfaceControl 来 protect socket
        // 这会导致 VPN 运行时测速流量被拦截，形成回环，返回 502 错误
        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
        override fun useProcFS(): Boolean = false
        override fun findConnectionOwner(p0: Int, p1: String?, p2: Int, p3: String?, p4: Int): Int = 0
        override fun packageNameByUid(p0: Int): String = ""
        override fun uidByPackageName(p0: String?): Int = 0
        override fun underNetworkExtension(): Boolean = false
        override fun includeAllNetworks(): Boolean = false
        override fun readWIFIState(): WIFIState? = null
        override fun clearDNSCache() {}
        override fun sendNotification(p0: io.nekohasekai.libbox.Notification?) {}
        override fun localDNSTransport(): io.nekohasekai.libbox.LocalDNSTransport {
            return com.kunk.singbox.core.LocalResolverImpl
        }
        override fun systemCertificates(): StringIterator? = null
        override fun writeLog(message: String?) {
            // 临时：记录所有日志以便诊断 502 问题
            message?.let {
                Log.d(TAG, "[libbox] $it")
                if (it.contains("error", ignoreCase = true) || it.contains("warn", ignoreCase = true)) {
                    com.kunk.singbox.repository.LogRepository.getInstance().addLog("[Test] $it")
                }
            }
        }
    }

    private class StringIteratorImpl(private val list: List<String>) : StringIterator {
        private var index = 0
        override fun hasNext(): Boolean = index < list.size
        override fun next(): String = list[index++]
        override fun len(): Int = list.size
    }

    fun cleanup() {
    }
}

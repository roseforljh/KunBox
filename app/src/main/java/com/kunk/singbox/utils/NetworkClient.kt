package com.kunk.singbox.utils

import android.util.Log
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 全局共享的 OkHttpClient 单例 - 优化版
 *
 * 参考 NekoBox 的连接池实现进行优化：
 * 1. 更大的连接池容量（10 连接 vs 5）
 * 2. 智能 VPN 状态感知，自动清理失效连接
 * 3. HTTP/2 多路复用支持
 * 4. 连接健康检查
 * 5. 统计和诊断支持
 */
object NetworkClient {
    private const val TAG = "NetworkClient"

    // 超时配置（秒）
    private const val CONNECT_TIMEOUT = 15L
    private const val READ_TIMEOUT = 20L
    private const val WRITE_TIMEOUT = 20L
    private const val CALL_TIMEOUT = 60L // 整体调用超时

    // 连接池配置优化：
    // - 10 个空闲连接（原 5 个）：适应更多并发场景
    // - 5 分钟存活时间：平衡复用效率和资源占用
    private val connectionPool = ConnectionPool(10, 5, TimeUnit.MINUTES)

    // 调度器配置：限制并发请求数
    private val dispatcher = Dispatcher().apply {
        maxRequests = 64 // 最大并发请求数
        maxRequestsPerHost = 10 // 每个 Host 最大并发
    }

    // VPN 状态追踪
    private val isVpnActive = AtomicBoolean(false)
    private val lastVpnStateChangeAt = AtomicLong(0)

    // 统计信息
    private val totalRequests = AtomicLong(0)
    private val failedRequests = AtomicLong(0)
    private val connectionPoolHits = AtomicLong(0)

    /**
     * 统计拦截器 - 记录请求统计信息
     */
    private val statsInterceptor = Interceptor { chain ->
        totalRequests.incrementAndGet()
        try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            failedRequests.incrementAndGet()
            throw e
        }
    }

    /**
     * 轻量级重试拦截器
     *
     * 优化说明:
     * - 无阻塞等待
     * - 仅对可重试的瞬时错误重试一次
     * - 区分可重试和不可重试错误
     */
    private val retryInterceptor = Interceptor { chain ->
        val request = chain.request()

        try {
            chain.proceed(request)
        } catch (e: IOException) {
            val isRetryable = isRetryableError(e)
            if (isRetryable) {
                Log.d(TAG, "Retrying request to ${request.url.host}: ${e.message}")
                try {
                    chain.proceed(request)
                } catch (retryException: IOException) {
                    throw retryException
                }
            } else {
                throw e
            }
        }
    }

    /**
     * 判断是否为可重试的错误
     */
    private fun isRetryableError(e: IOException): Boolean {
        val msg = e.message ?: return false
        return msg.contains("Connection reset", ignoreCase = true) ||
            msg.contains("Connection refused", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true) ||
            msg.contains("connection closed", ignoreCase = true) ||
            msg.contains("broken pipe", ignoreCase = true) ||
            msg.contains("ECONNRESET", ignoreCase = true)
    }

    /**
     * 主 Client - 支持 HTTP/2 多路复用
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT, TimeUnit.SECONDS)
            .connectionPool(connectionPool)
            .dispatcher(dispatcher)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)) // 优先 HTTP/2
            .addInterceptor(statsInterceptor)
            .addInterceptor(retryInterceptor)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 获取一个新的 Builder，共享连接池
     */
    fun newBuilder(): OkHttpClient.Builder {
        return client.newBuilder()
    }

    /**
     * 创建自定义超时的 Client
     */
    fun createClientWithTimeout(
        connectTimeoutSeconds: Long,
        readTimeoutSeconds: Long,
        writeTimeoutSeconds: Long = readTimeoutSeconds
    ): OkHttpClient {
        return newBuilder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 创建不带重试的 Client（用于需要精确控制的场景）
     */
    fun createClientWithoutRetry(
        connectTimeoutSeconds: Long,
        readTimeoutSeconds: Long,
        writeTimeoutSeconds: Long = readTimeoutSeconds
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .connectionPool(connectionPool)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 创建使用本地代理的 Client
     */
    fun createClientWithProxy(
        proxyPort: Int,
        connectTimeoutSeconds: Long,
        readTimeoutSeconds: Long,
        writeTimeoutSeconds: Long = readTimeoutSeconds
    ): OkHttpClient {
        val proxy = java.net.Proxy(
            java.net.Proxy.Type.HTTP,
            java.net.InetSocketAddress("127.0.0.1", proxyPort)
        )
        // 代理连接使用独立的连接池，避免与直连混用
        return OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 2, TimeUnit.MINUTES)) // 代理专用池
            .protocols(listOf(Protocol.HTTP_1_1)) // 代理模式使用 HTTP/1.1
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 通知 VPN 状态变化
     * 当 VPN 启动/停止时调用，自动清理失效连接
     */
    fun onVpnStateChanged(active: Boolean) {
        val previousState = isVpnActive.getAndSet(active)
        if (previousState != active) {
            lastVpnStateChangeAt.set(System.currentTimeMillis())
            Log.i(TAG, "VPN state changed: $previousState -> $active, clearing connection pool")
            clearConnectionPool()
        }
    }

    /**
     * 通知网络变化
     * 当网络切换（WiFi <-> 移动数据）时调用
     */
    fun onNetworkChanged() {
        Log.i(TAG, "Network changed, clearing connection pool")
        clearConnectionPool()
    }

    /**
     * 清理连接池
     */
    fun clearConnectionPool() {
        connectionPool.evictAll()
    }

    /**
     * 获取连接池状态
     */
    fun getPoolStatus(): PoolStatus {
        return PoolStatus(
            idleConnections = connectionPool.idleConnectionCount(),
            totalConnections = connectionPool.connectionCount(),
            totalRequests = totalRequests.get(),
            failedRequests = failedRequests.get(),
            isVpnActive = isVpnActive.get()
        )
    }

    /**
     * 重置统计信息
     */
    fun resetStats() {
        totalRequests.set(0)
        failedRequests.set(0)
        connectionPoolHits.set(0)
    }

    /**
     * 连接池状态数据类
     */
    data class PoolStatus(
        val idleConnections: Int,
        val totalConnections: Int,
        val totalRequests: Long,
        val failedRequests: Long,
        val isVpnActive: Boolean
    ) {
        val successRate: Double
            get() = if (totalRequests > 0) {
                ((totalRequests - failedRequests).toDouble() / totalRequests) * 100
            } else 100.0

        override fun toString(): String {
            return "PoolStatus(idle=$idleConnections, total=$totalConnections, " +
                "requests=$totalRequests, failed=$failedRequests, " +
                "successRate=${String.format("%.1f", successRate)}%, vpn=$isVpnActive)"
        }
    }
}

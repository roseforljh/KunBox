package com.kunk.singbox.utils

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object NetworkClient {
    private const val TAG = "NetworkClient"

    private const val CONNECT_TIMEOUT = 15L
    private const val READ_TIMEOUT = 20L
    private const val WRITE_TIMEOUT = 20L
    private const val CALL_TIMEOUT = 60L

    private val connectionPool = ConnectionPool(10, 5, TimeUnit.MINUTES)

    private val dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 10
    }

    private val isVpnActive = AtomicBoolean(false)
    private val lastVpnStateChangeAt = AtomicLong(0)

    private val totalRequests = AtomicLong(0)
    private val failedRequests = AtomicLong(0)
    private val connectionPoolHits = AtomicLong(0)

    /**
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
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT, TimeUnit.SECONDS)
            .connectionPool(connectionPool)
            .dispatcher(dispatcher)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .addInterceptor(statsInterceptor)
            // Rely on OkHttp built-in retry logic to avoid retry amplification.
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     */
    fun newBuilder(): OkHttpClient.Builder {
        return client.newBuilder()
    }

    /**
     */
    fun createClientWithTimeout(
        connectTimeoutSeconds: Long,
        readTimeoutSeconds: Long,
        writeTimeoutSeconds: Long = readTimeoutSeconds,
        callTimeoutSeconds: Long? = null
    ): OkHttpClient {
        val builder = newBuilder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
        callTimeoutSeconds?.let { builder.callTimeout(it, TimeUnit.SECONDS) }
        return builder.build()
    }

    /**
     */
    fun createClientWithoutRetry(
        connectTimeoutSeconds: Long,
        readTimeoutSeconds: Long,
        writeTimeoutSeconds: Long = readTimeoutSeconds,
        callTimeoutSeconds: Long? = null
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .connectionPool(connectionPool)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
        callTimeoutSeconds?.let { builder.callTimeout(it, TimeUnit.SECONDS) }
        return builder.build()
    }

    /**
     */
    fun createClientWithProxy(
        proxyPort: Int,
        connectTimeoutSeconds: Long,
        readTimeoutSeconds: Long,
        writeTimeoutSeconds: Long = readTimeoutSeconds,
        callTimeoutSeconds: Long? = CALL_TIMEOUT
    ): OkHttpClient {
        val proxy = java.net.Proxy(
            java.net.Proxy.Type.HTTP,
            java.net.InetSocketAddress("127.0.0.1", proxyPort)
        )

        val builder = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 2, TimeUnit.MINUTES))
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(false)
            .followRedirects(true)
            .followSslRedirects(true)
        callTimeoutSeconds?.let { builder.callTimeout(it, TimeUnit.SECONDS) }
        return builder.build()
    }

    /**
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
     */
    fun onNetworkChanged() {
        Log.i(TAG, "Network changed, clearing connection pool")
        clearConnectionPool()
    }

    /**
     */
    fun clearConnectionPool() {
        connectionPool.evictAll()
    }

    /**
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
     */
    fun resetStats() {
        totalRequests.set(0)
        failedRequests.set(0)
        connectionPoolHits.set(0)
    }

    suspend fun <T> executeCancellable(
        call: Call,
        block: (Response) -> T
    ): T {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                call.cancel()
            }

            try {
                val result = call.execute().use(block)
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            } catch (e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    suspend fun <T> executeCancellable(
        client: OkHttpClient,
        request: Request,
        block: (Response) -> T
    ): T {
        return executeCancellable(client.newCall(request), block)
    }

    /**
     *
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

    /**
     *
     */
    @Suppress("ReturnCount")
    fun executeWithFallback(
        request: Request,
        proxyPort: Int,
        isVpnActive: Boolean,
        connectTimeoutSeconds: Long = 15,
        readTimeoutSeconds: Long = 30
    ): Response? {
        if (isVpnActive && proxyPort > 0) {
            try {
                val proxyClient = createClientWithProxy(
                    proxyPort = proxyPort,
                    connectTimeoutSeconds = connectTimeoutSeconds,
                    readTimeoutSeconds = readTimeoutSeconds
                )
                val response = proxyClient.newCall(request).execute()
                if (response.isSuccessful) {
                    return response
                }
                response.close()
                Log.w(TAG, "Proxy request failed with ${response.code}")
                return null
            } catch (e: Exception) {
                Log.w(TAG, "Proxy request failed: ${e.message}")
                return null
            }
        }

        return try {
            val directClient = createClientWithTimeout(
                connectTimeoutSeconds = connectTimeoutSeconds,
                readTimeoutSeconds = readTimeoutSeconds
            )
            directClient.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "Direct request also failed: ${e.message}")
            null
        }
    }
}

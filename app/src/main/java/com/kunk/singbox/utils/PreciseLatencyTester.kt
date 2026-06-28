package com.kunk.singbox.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 *
 *
 */
object PreciseLatencyTester {
    private const val TAG = "PreciseLatencyTester"

    /**
     */
    enum class Standard {
        RTT,
        HANDSHAKE,
        FIRST_BYTE,
        TOTAL
    }

    /**
     */
    data class LatencyResult(
        val latencyMs: Long,
        val dnsTimeMs: Long = 0,
        val connectTimeMs: Long = 0,
        val tlsHandshakeMs: Long = 0,
        val firstByteMs: Long = 0,
        val totalMs: Long = 0
    ) {
        val isSuccess: Boolean get() = latencyMs >= 0
    }

    /**
     *
     */
    suspend fun test(
        proxyPort: Int,
        url: String,
        timeoutMs: Int,
        standard: Standard = Standard.RTT,
        warmup: Boolean = true,
        headersOnly: Boolean = false
    ): LatencyResult = withContext(Dispatchers.IO) {
        val timingListener = TimingEventListener()
        val client = buildClient(proxyPort, timeoutMs, standard, timingListener)

        try {
            val request = buildRequest(url, headersOnly)

            if (warmup) {
                runWarmup(client, request, timingListener)
            }

            timingListener.reset()
            if (!executeProbeRequest(client, request, url, headersOnly, timingListener)) {
                return@withContext LatencyResult(-1L)
            }

            buildLatencyResult(resolveLatency(timingListener, standard), timingListener)
        } catch (e: Exception) {
            Log.w(TAG, "Latency test failed: ${e.message}")
            LatencyResult(-1L)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun buildClient(
        proxyPort: Int,
        timeoutMs: Int,
        standard: Standard,
        timingListener: TimingEventListener
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
            .connectTimeout(1000L, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .eventListener(timingListener)
            .apply {
                if (standard == Standard.HANDSHAKE) {
                    connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                }
            }
            .followRedirects(false)
            .build()
    }

    private fun runWarmup(
        client: OkHttpClient,
        request: Request,
        timingListener: TimingEventListener
    ) {
        try {
            timingListener.reset()
            client.newCall(request).execute().use { resp ->
                resp.body?.close()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Warmup request failed: ${e.message}")
        }
    }

    private fun executeProbeRequest(
        client: OkHttpClient,
        request: Request,
        url: String,
        headersOnly: Boolean,
        timingListener: TimingEventListener
    ): Boolean {
        client.newCall(request).execute().use { response ->
            return when {
                headersOnly && response.code == 405 -> executeHeadersOnlyFallback(client, url, timingListener)
                response.code >= 400 -> false
                else -> true
            }
        }
    }

    private fun executeHeadersOnlyFallback(
        client: OkHttpClient,
        url: String,
        timingListener: TimingEventListener
    ): Boolean {
        timingListener.reset()
        client.newCall(buildHeadersOnlyFallbackRequest(url)).execute().use { fallback ->
            return fallback.code < 400
        }
    }

    private fun resolveLatency(timingListener: TimingEventListener, standard: Standard): Long {
        return when (standard) {
            Standard.RTT -> resolveRttLatency(timingListener)
            Standard.HANDSHAKE -> resolveHandshakeLatency(timingListener)
            Standard.FIRST_BYTE -> timingListener.responseHeadersStart.get() - timingListener.callStart.get()
            Standard.TOTAL -> timingListener.callEnd.get() - timingListener.callStart.get()
        }
    }

    private fun resolveRttLatency(timingListener: TimingEventListener): Long {
        val handshakeEnd = timingListener.secureConnectEnd.get()
            .takeIf { it > 0 } ?: timingListener.connectEnd.get()
        val firstByte = timingListener.responseHeadersStart.get()
        return if (handshakeEnd > 0 && firstByte > handshakeEnd) {
            firstByte - handshakeEnd
        } else {
            timingListener.callEnd.get() - timingListener.callStart.get()
        }
    }

    private fun resolveHandshakeLatency(timingListener: TimingEventListener): Long {
        val start = timingListener.secureConnectStart.get()
        val end = timingListener.secureConnectEnd.get()
        return if (start > 0 && end > start) {
            end - start
        } else {
            timingListener.connectEnd.get() - timingListener.connectStart.get()
        }
    }

    private fun buildLatencyResult(latency: Long, timingListener: TimingEventListener): LatencyResult {
        return LatencyResult(
            latencyMs = latency.coerceAtLeast(0),
            dnsTimeMs = (timingListener.dnsEnd.get() - timingListener.dnsStart.get()).coerceAtLeast(0),
            connectTimeMs = (timingListener.connectEnd.get() - timingListener.connectStart.get()).coerceAtLeast(0),
            tlsHandshakeMs = (
                timingListener.secureConnectEnd.get() - timingListener.secureConnectStart.get()
                ).coerceAtLeast(0),
            firstByteMs = (
                timingListener.responseHeadersStart.get() - timingListener.callStart.get()
                ).coerceAtLeast(0),
            totalMs = (timingListener.callEnd.get() - timingListener.callStart.get()).coerceAtLeast(0)
        )
    }

    internal fun buildRequestForTest(url: String, headersOnly: Boolean): Request {
        return buildRequest(url, headersOnly)
    }

    internal fun buildHeadersOnlyFallbackRequestForTest(url: String): Request {
        return buildHeadersOnlyFallbackRequest(url)
    }

    private fun buildRequest(url: String, headersOnly: Boolean): Request {
        val builder = Request.Builder().url(url)
        if (headersOnly) {
            builder.head()
                .header("Cache-Control", "no-cache")
        } else {
            builder.get()
        }
        return builder.build()
    }

    private fun buildHeadersOnlyFallbackRequest(url: String): Request {
        return Request.Builder()
            .url(url)
            .get()
            .header("Cache-Control", "no-cache")
            .header("Range", "bytes=0-0")
            .build()
    }

    /**
     */
    suspend fun testSimple(
        proxyPort: Int,
        url: String,
        timeoutMs: Int
    ): Long {
        val result = test(proxyPort, url, timeoutMs, Standard.RTT, warmup = false)
        return if (result.isSuccess) result.latencyMs else -1L
    }

    /**
     */
    private class TimingEventListener : EventListener() {
        val callStart = AtomicLong(0)
        val callEnd = AtomicLong(0)
        val dnsStart = AtomicLong(0)
        val dnsEnd = AtomicLong(0)
        val connectStart = AtomicLong(0)
        val connectEnd = AtomicLong(0)
        val secureConnectStart = AtomicLong(0)
        val secureConnectEnd = AtomicLong(0)
        val requestHeadersStart = AtomicLong(0)
        val requestHeadersEnd = AtomicLong(0)
        val responseHeadersStart = AtomicLong(0)
        val responseHeadersEnd = AtomicLong(0)

        fun reset() {
            callStart.set(0)
            callEnd.set(0)
            dnsStart.set(0)
            dnsEnd.set(0)
            connectStart.set(0)
            connectEnd.set(0)
            secureConnectStart.set(0)
            secureConnectEnd.set(0)
            requestHeadersStart.set(0)
            requestHeadersEnd.set(0)
            responseHeadersStart.set(0)
            responseHeadersEnd.set(0)
        }

        private fun now(): Long = System.currentTimeMillis()

        override fun callStart(call: Call) {
            callStart.set(now())
        }

        override fun callEnd(call: Call) {
            callEnd.set(now())
        }

        override fun callFailed(call: Call, ioe: IOException) {
            callEnd.set(now())
        }

        override fun dnsStart(call: Call, domainName: String) {
            dnsStart.set(now())
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            dnsEnd.set(now())
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStart.set(now())
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            connectEnd.set(now())
        }

        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
            connectEnd.set(now())
        }

        override fun secureConnectStart(call: Call) {
            secureConnectStart.set(now())
        }

        override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) {
            secureConnectEnd.set(now())
        }

        override fun requestHeadersStart(call: Call) {
            requestHeadersStart.set(now())
        }

        override fun requestHeadersEnd(call: Call, request: Request) {
            requestHeadersEnd.set(now())
        }

        override fun responseHeadersStart(call: Call) {
            responseHeadersStart.set(now())
        }

        override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
            responseHeadersEnd.set(now())
        }
    }
}

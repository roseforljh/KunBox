package com.kunk.singbox.utils

import android.content.Context
import android.util.Log
import com.kunk.singbox.core.BoxWrapperManager
import com.kunk.singbox.ipc.VpnStateStore
import com.kunk.singbox.repository.SettingsRepository
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 *
 *
 */
object KernelHttpClient {
    private const val TAG = "KernelHttpClient"

    private const val DEFAULT_TIMEOUT_MS = 30000

    private const val DEFAULT_PROXY_PORT = 2080

    @Volatile
    private var cachedProxyPort: Int = DEFAULT_PROXY_PORT

    /**
     */
    data class HttpResult(
        val success: Boolean,
        val statusCode: Int,
        val body: String,
        val error: String?
    ) {
        val isOk: Boolean get() = success && statusCode in 200..299

        companion object {
            fun error(message: String): HttpResult {
                return HttpResult(false, 0, "", message)
            }
        }
    }

    /**
     */
    fun updateProxyPort(port: Int) {
        cachedProxyPort = port
        Log.d(TAG, "Proxy port updated to $port")
    }

    /**
     */
    suspend fun updateProxyPortFromSettings(context: Context) {
        try {
            val settings = SettingsRepository.getInstance(context).settings.first()
            cachedProxyPort = settings.proxyPort
            Log.d(TAG, "Proxy port loaded from settings: $cachedProxyPort")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load proxy port from settings: ${e.message}")
        }
    }

    internal fun shouldFallbackToOkHttp(
        kernelFetchAvailable: Boolean,
        vpnActive: Boolean = VpnStateStore.getActive()
    ): Boolean {
        return !kernelFetchAvailable && !vpnActive
    }

    internal fun timeoutSecondsForTest(timeoutMs: Int): Long {
        return timeoutSeconds(timeoutMs)
    }

    private fun timeoutSeconds(timeoutMs: Int): Long {
        val millis = timeoutMs.coerceAtLeast(1).toLong()
        return ((millis + 999L) / 1000L).coerceAtLeast(1L)
    }

    private suspend fun <T> withRequestTimeout(timeoutMs: Int, block: suspend () -> T): T {
        return withTimeout(timeoutMs.coerceAtLeast(1).toLong()) {
            block()
        }
    }

    /**
     */
    fun getProxyPort(): Int = cachedProxyPort

    /**
     *
     * @return HttpResult
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun fetch(
        url: String,
        outboundTag: String = "proxy",
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): HttpResult = withContext(Dispatchers.IO) {
        withRequestTimeout(timeoutMs) {

            val kernelFetchAvailable = isKernelFetchAvailable()
            if (kernelFetchAvailable) {
                val kernelResult = fetchViaKernel(url)
                if (kernelResult.success) {
                    return@withRequestTimeout kernelResult
                }
                Log.w(TAG, "Kernel fetch failed: ${kernelResult.error}")
            }

            if (!shouldFallbackToOkHttp(kernelFetchAvailable)) {
                return@withRequestTimeout HttpResult.error("Kernel fetch failed while VPN is active")
            }

            Log.d(TAG, "fetch: $url (using OkHttp)")
            fetchWithOkHttp(url, timeoutMs)
        }
    }

    /**
     *
     * @return HttpResult
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun fetchWithHeaders(
        url: String,
        headers: Map<String, String>,
        outboundTag: String = "proxy",
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): HttpResult = withContext(Dispatchers.IO) {
        withRequestTimeout(timeoutMs) {

            val kernelFetchAvailable = isKernelFetchAvailable()
            if (kernelFetchAvailable) {
                val kernelResult = fetchViaKernel(url, headers)
                if (kernelResult.success) {
                    return@withRequestTimeout kernelResult
                }
                Log.w(TAG, "Kernel fetch with headers failed: ${kernelResult.error}")
            }

            if (!shouldFallbackToOkHttp(kernelFetchAvailable)) {
                return@withRequestTimeout HttpResult.error("Kernel fetch with headers failed while VPN is active")
            }

            Log.d(TAG, "fetchWithHeaders: $url (using OkHttp)")
            fetchWithOkHttpAndHeaders(url, headers, timeoutMs)
        }
    }

    /**
     *
     * @return HttpResult
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun smartFetch(
        url: String,
        preferKernel: Boolean = true,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): HttpResult = withContext(Dispatchers.IO) {
        withRequestTimeout(timeoutMs) {

            val kernelFetchAvailable = preferKernel && isKernelFetchAvailable()
            if (kernelFetchAvailable) {
                val kernelResult = fetchViaKernel(url)
                if (kernelResult.success) {
                    return@withRequestTimeout kernelResult
                }
                Log.w(TAG, "smartFetch kernel failed: ${kernelResult.error}")
            }

            if (!shouldFallbackToOkHttp(kernelFetchAvailable)) {
                return@withRequestTimeout HttpResult.error("Kernel fetch failed while VPN is active")
            }

            fetchWithOkHttp(url, timeoutMs)
        }
    }

    /**
     */
    private fun fetchWithOkHttp(url: String, timeoutMs: Int): HttpResult {
        return try {
            val client = NetworkClient.createClientWithTimeout(
                connectTimeoutSeconds = timeoutSeconds(timeoutMs),
                readTimeoutSeconds = timeoutSeconds(timeoutMs),
                callTimeoutSeconds = timeoutSeconds(timeoutMs)
            )

            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "KunBox/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""

                HttpResult(
                    success = response.isSuccessful,
                    statusCode = response.code,
                    body = body,
                    error = null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "OkHttp fetch error: ${e.message}")
            HttpResult.error("OkHttp error: ${e.message}")
        }
    }

    /**
     */
    private fun fetchWithOkHttpAndHeaders(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int
    ): HttpResult {
        return try {
            val client = NetworkClient.createClientWithTimeout(
                connectTimeoutSeconds = timeoutSeconds(timeoutMs),
                readTimeoutSeconds = timeoutSeconds(timeoutMs),
                callTimeoutSeconds = timeoutSeconds(timeoutMs)
            )

            val requestBuilder = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "KunBox/1.0")

            headers.forEach { (key, value) ->
                requestBuilder.header(key, value)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string() ?: ""

                HttpResult(
                    success = response.isSuccessful,
                    statusCode = response.code,
                    body = body,
                    error = null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "OkHttp fetch with headers error: ${e.message}")
            HttpResult.error("OkHttp error: ${e.message}")
        }
    }

    /**
     *
     * @return HttpResult
     */
    private fun fetchViaKernel(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): HttpResult {
        var client: io.nekohasekai.libbox.HTTPClient? = null
        try {

            client = Libbox.newHTTPClient()

            val proxyPort = cachedProxyPort
            client.trySocks5(proxyPort)

            client.modernTLS()
            client.keepAlive()

            val request = client.newRequest()
            request.setURL(url)
            request.setMethod("GET")
            request.randomUserAgent()

            headers.forEach { (key, value) ->
                request.setHeader(key, value)
            }

            val response = request.execute()
            val content = response.content?.value ?: ""

            Log.d(TAG, "Kernel fetch success: $url (${content.length} bytes)")

            return HttpResult(
                success = true,
                statusCode = 200,
                body = content,
                error = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Kernel fetch error: ${e.message}")
            return HttpResult.error("Kernel error: ${e.message}")
        } finally {
            try {
                client?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close HTTP client: ${e.message}")
            }
        }
    }

    /**
     */
    fun isKernelFetchAvailable(): Boolean {

        val vpnActive = VpnStateStore.getActive()
        val boxAvailable = BoxWrapperManager.isAvailable()
        return vpnActive && boxAvailable
    }

    /**
     */
    fun isVpnRunning(): Boolean {
        return BoxWrapperManager.isAvailable()
    }
}

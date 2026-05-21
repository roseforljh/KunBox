package com.kunk.singbox.utils.dns

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 */
data class DnsResolveResult(
    val ip: String?,
    val source: String,
    val error: String? = null
) {
    val isSuccess: Boolean get() = ip != null && error == null
}

/**
 *
 */
class DnsResolver(
    private val client: OkHttpClient = createDefaultClient()
) {
    companion object {
        private const val TAG = "DnsResolver"

        const val DOH_CLOUDFLARE = "https://1.1.1.1/dns-query"
        const val DOH_GOOGLE = "https://8.8.8.8/dns-query"
        const val DOH_ALIDNS = "https://223.5.5.5/dns-query"

        private val IPV4_REGEX = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
        private val IPV6_REGEX = Regex("^[0-9a-fA-F:]+$")

        private fun createDefaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .writeTimeout(3, TimeUnit.SECONDS)
                .build()
        }

        /**
         */
        fun isIpAddress(host: String): Boolean {
            return IPV4_REGEX.matches(host) || (host.contains(":") && IPV6_REGEX.matches(host))
        }
    }

    /**
     *
     */
    suspend fun resolveViaDoH(
        domain: String,
        dohServer: String = DOH_CLOUDFLARE
    ): DnsResolveResult = withContext(Dispatchers.IO) {
        if (isIpAddress(domain)) {
            return@withContext DnsResolveResult(domain, "direct")
        }

        try {
            executeDoHRequest(domain, dohServer)
        } catch (e: Exception) {
            Log.w(TAG, "DoH resolve failed for $domain: ${e.message}")
            DnsResolveResult(null, "doh", e.message)
        }
    }

    private fun executeDoHRequest(domain: String, dohServer: String): DnsResolveResult {
        val query = buildDnsQuery(domain)

        val request = Request.Builder()
            .url(dohServer)
            .header("Accept", "application/dns-message")
            .header("Content-Type", "application/dns-message")
            .post(query.toRequestBody("application/dns-message".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return DnsResolveResult(null, "doh", "HTTP ${response.code}")
            }

            val body = response.body?.bytes()
                ?: return DnsResolveResult(null, "doh", "Empty response")

            val ip = parseDnsResponse(body)
            return if (ip != null) {
                Log.d(TAG, "DoH resolved $domain -> $ip")
                DnsResolveResult(ip, "doh")
            } else {
                DnsResolveResult(null, "doh", "No A/AAAA record found")
            }
        }
    }

    /**
     */
    suspend fun resolveViaSystem(domain: String): DnsResolveResult = withContext(Dispatchers.IO) {
        if (isIpAddress(domain)) {
            return@withContext DnsResolveResult(domain, "direct")
        }

        try {
            val addresses = InetAddress.getAllByName(domain)
            val ip = addresses.firstOrNull()?.hostAddress
            if (ip != null) {
                Log.d(TAG, "System resolved $domain -> $ip")
                DnsResolveResult(ip, "system")
            } else {
                DnsResolveResult(null, "system", "No address found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "System resolve failed for $domain: ${e.message}")
            DnsResolveResult(null, "system", e.message)
        }
    }

    /**
     */
    @Suppress("CognitiveComplexMethod")
    private suspend fun resolveViaDoHAsync(
        domain: String,
        dohServer: String
    ): DnsResolveResult = suspendCancellableCoroutine { cont ->
        val query = buildDnsQuery(domain)

        val request = Request.Builder()
            .url(dohServer)
            .header("Accept", "application/dns-message")
            .header("Content-Type", "application/dns-message")
            .post(query.toRequestBody("application/dns-message".toMediaType()))
            .build()

        val call = client.newCall(request)

        cont.invokeOnCancellation {
            call.cancel()
        }

        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (cont.isActive) {
                    Log.w(TAG, "DoH resolve failed for $domain: ${e.message}")
                    cont.resume(DnsResolveResult(null, "doh", e.message))
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!cont.isActive) {
                    response.close()
                    return
                }

                response.use { resp ->
                    if (!resp.isSuccessful) {
                        cont.resume(DnsResolveResult(null, "doh", "HTTP ${resp.code}"))
                        return
                    }

                    val body = resp.body?.bytes()
                    if (body == null) {
                        cont.resume(DnsResolveResult(null, "doh", "Empty response"))
                        return
                    }

                    val ip = parseDnsResponse(body)
                    if (ip != null) {
                        Log.d(TAG, "DoH resolved $domain -> $ip")
                        cont.resume(DnsResolveResult(ip, "doh"))
                    } else {
                        cont.resume(DnsResolveResult(null, "doh", "No A/AAAA record found"))
                    }
                }
            }
        })
    }

    /**
     */
    @Suppress("CognitiveComplexMethod")
    suspend fun resolve(
        domain: String,
        dohServer: String? = DOH_CLOUDFLARE
    ): DnsResolveResult = withContext(Dispatchers.IO) {
        if (isIpAddress(domain)) {
            return@withContext DnsResolveResult(domain, "direct")
        }

        // DoH first to avoid DNS pollution from system DNS
        if (dohServer != null) {
            val dohResult = resolveViaDoHAsync(domain, dohServer)
            if (dohResult.isSuccess) {
                return@withContext dohResult
            }
            Log.w(TAG, "DoH failed for $domain")
            return@withContext dohResult
        }

        resolveViaSystem(domain)
    }

    /**
     */
    suspend fun resolveBatch(
        domains: List<String>,
        dohServer: String? = DOH_CLOUDFLARE,
        concurrency: Int = 8
    ): Map<String, DnsResolveResult> = withContext(Dispatchers.IO) {
        val uniqueDomains = domains.filter { !isIpAddress(it) }.distinct()
        if (uniqueDomains.isEmpty()) {
            return@withContext emptyMap()
        }

        Log.d(TAG, "Batch resolving ${uniqueDomains.size} domains...")

        val semaphore = Semaphore(concurrency)
        val results = uniqueDomains.map { domain ->
            async {
                semaphore.withPermit {
                    domain to resolve(domain, dohServer)
                }
            }
        }.awaitAll()

        val resultMap = results.toMap()
        val successCount = resultMap.values.count { result -> result.isSuccess }
        Log.d(TAG, "Batch resolved: $successCount/${uniqueDomains.size} succeeded")

        resultMap
    }

    internal fun parseDnsResponseForTest(data: ByteArray): String? {
        return parseDnsResponse(data)
    }

    /**
     */
    private fun buildDnsQuery(domain: String): ByteArray {
        val buffer = ByteBuffer.allocate(512)

        // Transaction ID (random)
        buffer.putShort((System.currentTimeMillis() and 0xFFFF).toShort())

        // Flags: standard query, recursion desired
        buffer.putShort(0x0100.toShort())

        // Questions: 1, Answers: 0, Authority: 0, Additional: 0
        buffer.putShort(1)
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(0)

        // Question section
        val labels = domain.split(".")
        for (label in labels) {
            buffer.put(label.length.toByte())
            buffer.put(label.toByteArray(Charsets.US_ASCII))
        }
        buffer.put(0) // End of name

        // Type: A (1)
        buffer.putShort(1)
        // Class: IN (1)
        buffer.putShort(1)

        val result = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(result)
        return result
    }

    /**
     */
    private fun parseDnsResponse(data: ByteArray): String? {
        if (data.size < 12) return null

        val buffer = ByteBuffer.wrap(data)

        // Skip header (12 bytes)
        buffer.position(12)

        // Skip question section
        skipName(buffer)
        buffer.position(buffer.position() + 4) // Type + Class

        // Read answer count from header
        val answerCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)

        // Parse answers
        repeat(answerCount) {
            if (buffer.remaining() >= 12) {
                skipName(buffer)

                val type = buffer.short.toInt() and 0xFFFF
                buffer.short // Class
                buffer.int // TTL
                val rdLength = buffer.short.toInt() and 0xFFFF
                val ip = parseAnswerAddress(buffer, type, rdLength)
                if (ip != null) return ip
            }
        }

        return null
    }

    private fun parseAnswerAddress(buffer: ByteBuffer, type: Int, rdLength: Int): String? {
        return if (type == 1 && rdLength == 4) {
            val ip = ByteArray(4)
            buffer.get(ip)
            "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}." +
                "${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
        } else if (type == 28 && rdLength == 16) {
            val ip = ByteArray(16)
            buffer.get(ip)
            Inet6Address.getByAddress(ip).hostAddress
        } else {
            buffer.position(buffer.position() + rdLength)
            null
        }
    }

    /**
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun skipName(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            val len = buffer.get().toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) {
                buffer.get() // Compression pointer
                break
            }
            buffer.position(buffer.position() + len)
        }
    }
}

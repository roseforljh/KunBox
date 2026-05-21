package com.kunk.singbox.utils

import com.kunk.singbox.utils.dns.DnsResolver
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TcpPing {
    private val dnsResolver = DnsResolver()

    private val dohFallbackServers = listOf(
        DnsResolver.DOH_ALIDNS,
        DnsResolver.DOH_CLOUDFLARE
    )

    /**
     * Performs a TCP ping to the specified host and port.
     *
     * @param host The hostname or IP address to ping.
     * @param port The port to connect to (default 80).
     * @param timeout The connection timeout in milliseconds (default 3000ms).
     * @return The latency in milliseconds, or -1 if the connection failed.
     */
    suspend fun connect(host: String, port: Int = 80, timeout: Int = 3000): Long = withContext(Dispatchers.IO) {
        val resolvedHost = resolveHost(host) ?: return@withContext -1L
        val socket = Socket()
        val start = System.currentTimeMillis()
        try {
            val address = InetSocketAddress(resolvedHost, port)
            socket.connect(address, timeout)
            val end = System.currentTimeMillis()
            end - start
        } catch (e: Exception) {
            -1L
        } finally {
            try {
                socket.close()
            } catch (_: Exception) { }
        }
    }

    private suspend fun resolveHost(host: String): String? {
        if (DnsResolver.isIpAddress(host)) return host
        for (server in dohFallbackServers) {
            val result = dnsResolver.resolveViaDoH(host, server)
            if (result.isSuccess) return result.ip
        }
        return null
    }
}

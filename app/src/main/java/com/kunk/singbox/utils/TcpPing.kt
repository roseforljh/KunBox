package com.kunk.singbox.utils

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object TcpPing {
    /**
     * Performs a TCP ping to the specified host and port.
     *
     * @param host The hostname or IP address to ping.
     * @param port The port to connect to (default 80).
     * @param timeout The connection timeout in milliseconds (default 3000ms).
     * @return The latency in milliseconds, or -1 if the connection failed.
     */
    suspend fun connect(host: String, port: Int = 80, timeout: Int = 3000): Long = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeout)
            }
            (System.nanoTime() - start) / 1_000_000L
        } catch (_: Exception) {
            -1L
        }
    }
}

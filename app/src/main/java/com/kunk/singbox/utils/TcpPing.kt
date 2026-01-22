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
        val socket = Socket()
        val start = System.currentTimeMillis()
        try {
            val address = InetSocketAddress(host, port)
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
}

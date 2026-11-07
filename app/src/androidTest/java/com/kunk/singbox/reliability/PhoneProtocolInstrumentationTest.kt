package com.kunk.singbox.reliability

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kunk.singbox.core.SingBoxCore
import com.kunk.singbox.core.TestCommandServerHandler
import com.kunk.singbox.core.TestPlatformInterface
import io.raybox.raybox.OverrideOptions
import io.raybox.raybox.Raybox
import java.io.DataInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneProtocolInstrumentationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun protocolMatrix() {
        val path = requireNotNull(InstrumentationRegistry.getArguments().getString("cases"))
        val cases = JSONArray(File(path).readText())
        val results = File(context.cacheDir, "phone-protocol-results.jsonl")
        results.writeText("")
        val diagnostics = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        diagnostics.scheduleAtFixedRate({
            File(context.cacheDir, "phone-protocol-stacks.txt").writeText(
                Thread.getAllStackTraces().entries.joinToString("\n") { (thread, stack) ->
                    "${thread.name}: ${stack.joinToString("\n")}"
                } + "\n" + Raybox.goroutineDump()
            )
        }, 30, 30, java.util.concurrent.TimeUnit.SECONDS)
        SingBoxCore.ensureLibboxSetup(context)
        var failures = 0
        repeat(2) { round ->
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val result = JSONObject().put("name", case.getString("name"))
                    .put("round", round + 1).put("version", Raybox.version())
                val server = Raybox.newCommandServer(TestCommandServerHandler(), TestPlatformInterface(context))
                try {
                    File(context.cacheDir, "phone-protocol-progress.txt").writeText("${case.getString("name")}: start")
                    val port = ServerSocket(0).use { it.localPort }
                    val outbound = JSONObject(case.getJSONObject("outbound").toString()).put("tag", "candidate")
                    val config = JSONObject().put("log", JSONObject().put("level", "debug")
                        .put("output", File(context.cacheDir, "phone-protocol-core.log").absolutePath))
                        .put("inbounds", JSONArray().put(JSONObject().put("type", "mixed")
                            .put("listen", "127.0.0.1").put("listen_port", port)))
                        .put("outbounds", JSONArray().put(outbound))
                        .put("route", JSONObject().put("final", "candidate").put("auto_detect_interface", true))
                    server.startOrReloadService(config.toString(), OverrideOptions())
                    socks(port, 1, 29180).use { tcp ->
                        val payload = ByteArray(90_112) { (it % 251).toByte() }
                        tcp.getOutputStream().write(payload)
                        val received = ByteArray(payload.size)
                        DataInputStream(tcp.getInputStream()).readFully(received)
                        assertArrayEquals(payload, received)
                        result.put("tcpBytes", payload.size)
                        File(context.cacheDir, "phone-protocol-progress.txt").writeText("${case.getString("name")}: udp")
                        checkUdp(port, case, result)
                        result.put("udp", true).put("dns", true)
                        File(context.cacheDir, "phone-protocol-progress.txt").writeText("${case.getString("name")}: close")
                        server.closeService()
                        tcp.soTimeout = 2_000
                        val closed = try {
                            tcp.getInputStream().read() == -1
                        } catch (_: java.net.SocketTimeoutException) {
                            false
                        } catch (_: java.net.SocketException) {
                            true
                        }
                        assertTrue("TCP remained open after service close", closed)
                        result.put("closed", true)
                    }
                    result.put("passed", true)
                } catch (error: Exception) {
                    failures++
                    result.put("passed", false).put("error", error.toString())
                } catch (error: AssertionError) {
                    failures++
                    result.put("passed", false).put("error", error.toString())
                } finally {
                    runCatching { server.closeService() }
                    server.close()
                    results.appendText("$result\n")
                }
            }
        }
        diagnostics.shutdownNow()
        assertEquals("See ${results.absolutePath}", 0, failures)
    }

    private fun socks(port: Int, command: Int, targetPort: Int): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), 10_000)
            socket.soTimeout = 10_000
            socket.getOutputStream().write(byteArrayOf(5, 1, 0))
            val input = DataInputStream(socket.getInputStream())
            assertEquals(5, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            socket.getOutputStream().write(byteArrayOf(5, command.toByte(), 0, 1, 127, 0, 0, 1,
                (targetPort shr 8).toByte(), targetPort.toByte()))
            assertEquals(5, input.readUnsignedByte())
            assertEquals(0, input.readUnsignedByte())
            input.readUnsignedByte()
            assertEquals(1, input.readUnsignedByte())
            input.readInt()
            val boundPort = input.readUnsignedShort()
            if (command == 3) socket.tagPort = boundPort
            return socket
        } catch (error: Throwable) {
            socket.close()
            throw error
        }
    }

    private var Socket.tagPort: Int
        get() = relayPorts.getValue(this)
        set(value) { relayPorts[this] = value }

    private val relayPorts = mutableMapOf<Socket, Int>()

    private fun checkUdp(port: Int, case: JSONObject, result: JSONObject) {
        socks(port, 3, 0).use { control ->
            DatagramSocket().use { udp ->
                udp.soTimeout = 10_000
                val sizes = case.optJSONArray("udp_sizes") ?: JSONArray(listOf(1, 1200, 4096))
                for (index in 0 until sizes.length()) {
                    for (target in listOf(29181, 29182)) {
                        val size = sizes.getInt(index)
                        val payload = ByteArray(size) { (it % 251).toByte() }
                        result.put("udpAttempt", "$size/$target")
                        assertArrayEquals(payload, exchange(udp, control.tagPort, target, payload))
                    }
                }
                val query = byteArrayOf(0x6b, 0x32, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
                    7, 101, 120, 97, 109, 112, 108, 101, 3, 111, 114, 103, 0, 0, 1, 0, 1)
                val response = exchange(udp, control.tagPort, 29183, query)
                assertTrue(response.size >= 12 && response[0] == query[0] && response[1] == query[1])
                assertTrue(response[2].toInt() and 128 != 0)
            }
            relayPorts.remove(control)
        }
    }

    private fun exchange(udp: DatagramSocket, relay: Int, target: Int, payload: ByteArray): ByteArray {
        val header = byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1, (target shr 8).toByte(), target.toByte())
        val bytes = header + payload
        udp.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), relay))
        val response = DatagramPacket(ByteArray(65_535), 65_535)
        udp.receive(response)
        assertTrue(response.length >= header.size)
        assertArrayEquals(header, response.data.copyOfRange(0, header.size))
        return response.data.copyOfRange(header.size, response.length)
    }
}

package com.kunk.singbox.service.manager

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficShutdownPolicyTest {

    @Test
    fun finalTrafficFlushesBeforeTransportCanForceStop() = runBlocking {
        val callerThread = Thread.currentThread()
        val events = mutableListOf<String>()
        var flushThread: Thread? = null

        stopTrafficProducerThenFlush(
            stopProducer = {
                events += "final-traffic"
            },
            stopUpdatesAndWait = {
                events += "updates-stop"
            },
            flush = {
                flushThread = Thread.currentThread()
                events += "flush"
            },
            stopTransport = {
                events += "transport-stop"
            }
        )

        assertEquals(listOf("final-traffic", "updates-stop", "flush", "transport-stop"), events)
        assertNotEquals(callerThread, requireNotNull(flushThread))
    }

    @Test
    fun transportStillStopsWhenFinalFlushFails() = runBlocking {
        val events = mutableListOf<String>()
        val failure = runCatching {
            stopTrafficProducerThenFlush(
                stopProducer = { events += "producer-stop" },
                stopUpdatesAndWait = { events += "updates-stop" },
                flush = {
                    events += "flush"
                    error("disk failure")
                },
                stopTransport = { events += "transport-stop" }
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("producer-stop", "updates-stop", "flush", "transport-stop"), events)
    }

    @Test
    fun trafficStatusGateWaitsForActiveCallbackAndRejectsLaterUpdates() {
        val gate = TrafficStatusGate()
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val stopAttempted = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)

        gate.start()
        val callbackThread = thread(isDaemon = true) {
            gate.runIfActive {
                callbackEntered.countDown()
                releaseCallback.await()
            }
        }
        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS))

        val stopThread = thread(isDaemon = true) {
            stopAttempted.countDown()
            gate.stopAndWait()
            stopReturned.countDown()
        }
        assertTrue(stopAttempted.await(1, TimeUnit.SECONDS))
        assertFalse(stopReturned.await(100, TimeUnit.MILLISECONDS))

        releaseCallback.countDown()
        assertTrue(stopReturned.await(1, TimeUnit.SECONDS))
        callbackThread.join(1_000L)
        stopThread.join(1_000L)

        var acceptedAfterStop = false
        gate.runIfActive { acceptedAfterStop = true }
        assertFalse(acceptedAfterStop)
    }

    @Test
    fun shutdownManagerUsesFinalTrafficFlushPipeline() {
        val source = mainSource("service/manager/ShutdownManager.kt").readText(Charsets.UTF_8)
        val stopVpnBody = source
            .substringAfter("fun stopVpn(")
            .substringBefore("private suspend fun waitForSystemVpnDown")

        assertTrue(stopVpnBody.contains("stopTrafficProducerThenFlush("))
    }

    @Test
    fun singBoxServiceOnDestroyDoesNotPersistTrafficDirectly() {
        val source = mainSource("service/SingBoxService.kt").readText(Charsets.UTF_8)
        val onDestroyBody = source
            .substringAfter("override fun onDestroy() {")
            .substringBefore("override fun onRevoke()")

        assertFalse(onDestroyBody.contains("TrafficRepository"))
        assertFalse(onDestroyBody.contains("saveStats()"))
    }

    private fun mainSource(path: String): File = File("src/main/java/com/kunk/singbox/$path")
}

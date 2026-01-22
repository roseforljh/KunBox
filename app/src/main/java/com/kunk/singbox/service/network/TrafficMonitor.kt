package com.kunk.singbox.service.network

import android.net.TrafficStats
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TrafficMonitor(
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TrafficMonitor"
        private const val SAMPLE_INTERVAL_MS = 1000L
        private const val STALL_CHECK_INTERVAL_MS = 15000L
        private const val STALL_MIN_BYTES_DELTA = 1024L
        private const val STALL_MIN_SAMPLES = 3
        private const val PROXY_IDLE_THRESHOLD_MS = 60_000L
    }

    data class TrafficSnapshot(
        val uploadSpeed: Long,
        val downloadSpeed: Long,
        val totalUpload: Long,
        val totalDownload: Long
    )

    interface Listener {
        fun onTrafficUpdate(snapshot: TrafficSnapshot)
        fun onTrafficStall(consecutiveCount: Int)
        fun onProxyIdle(idleDurationMs: Long) {}
    }

    private var monitorJob: Job? = null
    private var baseTxBytes: Long = 0L
    private var baseRxBytes: Long = 0L
    private var lastTxBytes: Long = 0L
    private var lastRxBytes: Long = 0L
    private var lastSampleTime: Long = 0L

    private var lastStallCheckAtMs: Long = 0L
    private var stallConsecutiveCount: Int = 0
    private var lastStallTrafficBytes: Long = 0L

    private var lastTrafficActiveAtMs: Long = 0L
    private var proxyIdleNotified: Boolean = false

    @Volatile
    private var isPaused: Boolean = false

    @Volatile
    private var cachedUid: Int = 0

    @Volatile
    private var cachedListener: Listener? = null

    @Volatile var currentUploadSpeed: Long = 0L
        private set

    @Volatile var currentDownloadSpeed: Long = 0L
        private set

    fun start(uid: Int, listener: Listener) {
        stop()

        cachedUid = uid
        cachedListener = listener
        isPaused = false

        val tx0 = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
        val rx0 = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)

        baseTxBytes = tx0
        baseRxBytes = rx0
        lastTxBytes = tx0
        lastRxBytes = rx0
        lastSampleTime = SystemClock.elapsedRealtime()
        stallConsecutiveCount = 0
        lastStallTrafficBytes = 0L
        lastStallCheckAtMs = 0L
        lastTrafficActiveAtMs = SystemClock.elapsedRealtime()
        proxyIdleNotified = false

        monitorJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(SAMPLE_INTERVAL_MS)

                val nowElapsed = SystemClock.elapsedRealtime()
                val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
                val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)

                val dtMs = (nowElapsed - lastSampleTime).coerceAtLeast(1L)
                val dTx = (tx - lastTxBytes).coerceAtLeast(0L)
                val dRx = (rx - lastRxBytes).coerceAtLeast(0L)

                val uploadSpeedBps = dTx * 1000 / dtMs
                val downloadSpeedBps = dRx * 1000 / dtMs

                currentUploadSpeed = uploadSpeedBps
                currentDownloadSpeed = downloadSpeedBps

                val totalTx = (tx - baseTxBytes).coerceAtLeast(0L)
                val totalRx = (rx - baseRxBytes).coerceAtLeast(0L)

                listener.onTrafficUpdate(TrafficSnapshot(
                    uploadSpeed = uploadSpeedBps,
                    downloadSpeed = downloadSpeedBps,
                    totalUpload = totalTx,
                    totalDownload = totalRx
                ))

                checkForStall(tx + rx, listener)
                checkForProxyIdle(dTx + dRx, nowElapsed, listener)

                lastTxBytes = tx
                lastRxBytes = rx
                lastSampleTime = nowElapsed
            }
        }

        Log.i(TAG, "Traffic monitor started for uid=$uid")
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        currentUploadSpeed = 0L
        currentDownloadSpeed = 0L
        baseTxBytes = 0L
        baseRxBytes = 0L
        lastTxBytes = 0L
        lastRxBytes = 0L
        lastSampleTime = 0L
        stallConsecutiveCount = 0
        Log.i(TAG, "Traffic monitor stopped")
    }

    fun getTotalBytes(): Long {
        return (lastTxBytes + lastRxBytes).coerceAtLeast(0L)
    }

    fun resetStallCounter() {
        stallConsecutiveCount = 0
        lastStallTrafficBytes = 0L
    }

    private fun checkForStall(currentTotalBytes: Long, listener: Listener) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastStallCheckAtMs < STALL_CHECK_INTERVAL_MS) {
            return
        }
        lastStallCheckAtMs = now

        val delta = currentTotalBytes - lastStallTrafficBytes
        lastStallTrafficBytes = currentTotalBytes

        if (delta < STALL_MIN_BYTES_DELTA) {
            stallConsecutiveCount++
            if (stallConsecutiveCount >= STALL_MIN_SAMPLES) {
                Log.w(TAG, "Traffic stall detected: consecutiveCount=$stallConsecutiveCount")
                listener.onTrafficStall(stallConsecutiveCount)
            }
        } else {
            if (stallConsecutiveCount > 0) {
                Log.i(TAG, "Traffic resumed, resetting stall counter")
            }
            stallConsecutiveCount = 0
        }
    }

    private fun checkForProxyIdle(bytesDelta: Long, nowElapsedMs: Long, listener: Listener) {
        if (bytesDelta > 0) {
            lastTrafficActiveAtMs = nowElapsedMs
            if (proxyIdleNotified) {
                Log.i(TAG, "Proxy traffic resumed after idle")
                proxyIdleNotified = false
            }
            return
        }

        val idleDuration = nowElapsedMs - lastTrafficActiveAtMs
        if (idleDuration >= PROXY_IDLE_THRESHOLD_MS && !proxyIdleNotified) {
            proxyIdleNotified = true
            Log.i(TAG, "Proxy idle detected: ${idleDuration}ms")
            listener.onProxyIdle(idleDuration)
        }
    }

    fun pause() {
        if (isPaused) return
        isPaused = true
        monitorJob?.cancel()
        monitorJob = null
        currentUploadSpeed = 0L
        currentDownloadSpeed = 0L
        Log.i(TAG, "Traffic monitor paused")
    }

    fun resume() {
        if (!isPaused) return
        isPaused = false

        val uid = cachedUid
        val listener = cachedListener
        if (uid > 0 && listener != null) {
            val tx0 = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
            val rx0 = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
            lastTxBytes = tx0
            lastRxBytes = rx0
            lastSampleTime = SystemClock.elapsedRealtime()
            stallConsecutiveCount = 0
            lastStallTrafficBytes = 0L
            lastStallCheckAtMs = 0L
            lastTrafficActiveAtMs = SystemClock.elapsedRealtime()
            proxyIdleNotified = false

            monitorJob = scope.launch(Dispatchers.IO) {
                while (true) {
                    delay(SAMPLE_INTERVAL_MS)

                    val nowElapsed = SystemClock.elapsedRealtime()
                    val tx = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)
                    val rx = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)

                    val dtMs = (nowElapsed - lastSampleTime).coerceAtLeast(1L)
                    val dTx = (tx - lastTxBytes).coerceAtLeast(0L)
                    val dRx = (rx - lastRxBytes).coerceAtLeast(0L)

                    val uploadSpeedBps = dTx * 1000 / dtMs
                    val downloadSpeedBps = dRx * 1000 / dtMs

                    currentUploadSpeed = uploadSpeedBps
                    currentDownloadSpeed = downloadSpeedBps

                    val totalTx = (tx - baseTxBytes).coerceAtLeast(0L)
                    val totalRx = (rx - baseRxBytes).coerceAtLeast(0L)

                    listener.onTrafficUpdate(TrafficSnapshot(
                        uploadSpeed = uploadSpeedBps,
                        downloadSpeed = downloadSpeedBps,
                        totalUpload = totalTx,
                        totalDownload = totalRx
                    ))

                    checkForStall(tx + rx, listener)
                    checkForProxyIdle(dTx + dRx, nowElapsed, listener)

                    lastTxBytes = tx
                    lastRxBytes = rx
                    lastSampleTime = nowElapsed
                }
            }
            Log.i(TAG, "Traffic monitor resumed")
        } else {
            Log.w(TAG, "Cannot resume: missing uid or listener")
        }
    }

    val isMonitoringPaused: Boolean
        get() = isPaused
}

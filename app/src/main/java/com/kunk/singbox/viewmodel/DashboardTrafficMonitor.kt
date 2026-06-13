package com.kunk.singbox.viewmodel

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import com.kunk.singbox.core.BoxWrapperManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

internal data class DashboardTrafficSnapshot(
    val uploadSpeed: Long,
    val downloadSpeed: Long,
    val uploadTotal: Long,
    val downloadTotal: Long
)

internal class DashboardTrafficMonitor(
    private val scope: CoroutineScope,
    private val onSnapshot: (DashboardTrafficSnapshot) -> Unit
) {
    private var job: Job? = null
    private var lastUploadSpeed: Long = 0
    private var lastDownloadSpeed: Long = 0
    private var trafficBaseTxBytes: Long = 0
    private var trafficBaseRxBytes: Long = 0
    private var lastTrafficTxBytes: Long = 0
    private var lastTrafficRxBytes: Long = 0
    private var lastTrafficSampleAtElapsedMs: Long = 0
    private var wrapperBaseUpload: Long = 0
    private var wrapperBaseDownload: Long = 0

    fun start() {
        stop()
        val uid = Process.myUid()
        val tx0 = readUidTx(uid)
        val rx0 = readUidRx(uid)
        trafficBaseTxBytes = tx0
        trafficBaseRxBytes = rx0
        lastTrafficTxBytes = tx0
        lastTrafficRxBytes = rx0
        lastTrafficSampleAtElapsedMs = SystemClock.elapsedRealtime()
        wrapperBaseUpload = readWrapperUpload()
        wrapperBaseDownload = readWrapperDownload()

        job = scope.launch(Dispatchers.Default) {
            while (true) {
                delay(1000)
                publishSample(uid, SystemClock.elapsedRealtime())
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        lastUploadSpeed = 0
        lastDownloadSpeed = 0
        trafficBaseTxBytes = 0
        trafficBaseRxBytes = 0
        lastTrafficTxBytes = 0
        lastTrafficRxBytes = 0
        lastTrafficSampleAtElapsedMs = 0
        wrapperBaseUpload = 0
        wrapperBaseDownload = 0
    }

    private fun publishSample(uid: Int, nowElapsed: Long) {
        val sample = readTrafficSample(uid)
        val dtMs = (nowElapsed - lastTrafficSampleAtElapsedMs).coerceAtLeast(1L)
        val dTx = (sample.tx - lastTrafficTxBytes).coerceAtLeast(0L)
        val dRx = (sample.rx - lastTrafficRxBytes).coerceAtLeast(0L)
        val up = (dTx * 1000L) / dtMs
        val down = (dRx * 1000L) / dtMs
        val uploadSmoothFactor = calculateAdaptiveSmoothFactor(up, lastUploadSpeed)
        val downloadSmoothFactor = calculateAdaptiveSmoothFactor(down, lastDownloadSpeed)
        val smoothedUp = if (lastUploadSpeed == 0L) {
            up
        } else {
            (lastUploadSpeed * (1 - uploadSmoothFactor) + up * uploadSmoothFactor).toLong()
        }
        val smoothedDown = if (lastDownloadSpeed == 0L) {
            down
        } else {
            (lastDownloadSpeed * (1 - downloadSmoothFactor) + down * downloadSmoothFactor).toLong()
        }

        lastUploadSpeed = smoothedUp
        lastDownloadSpeed = smoothedDown
        lastTrafficTxBytes = sample.tx
        lastTrafficRxBytes = sample.rx
        lastTrafficSampleAtElapsedMs = nowElapsed
        onSnapshot(
            DashboardTrafficSnapshot(
                uploadSpeed = smoothedUp,
                downloadSpeed = smoothedDown,
                uploadTotal = sample.totalTx,
                downloadTotal = sample.totalRx
            )
        )
    }

    private fun readTrafficSample(uid: Int): DashboardTrafficSample {
        if (BoxWrapperManager.isAvailable()) {
            val wrapperUp = BoxWrapperManager.getUploadTotal()
            val wrapperDown = BoxWrapperManager.getDownloadTotal()
            if (wrapperUp >= 0 && wrapperDown >= 0) {
                return DashboardTrafficSample(
                    tx = wrapperUp,
                    rx = wrapperDown,
                    totalTx = (wrapperUp - wrapperBaseUpload).coerceAtLeast(0L),
                    totalRx = (wrapperDown - wrapperBaseDownload).coerceAtLeast(0L)
                )
            }
        }

        val sysTx = readUidTx(uid)
        val sysRx = readUidRx(uid)
        return DashboardTrafficSample(
            tx = sysTx,
            rx = sysRx,
            totalTx = (sysTx - trafficBaseTxBytes).coerceAtLeast(0L),
            totalRx = (sysRx - trafficBaseRxBytes).coerceAtLeast(0L)
        )
    }

    private fun readWrapperUpload(): Long {
        return if (BoxWrapperManager.isAvailable()) BoxWrapperManager.getUploadTotal().coerceAtLeast(0L) else 0L
    }

    private fun readWrapperDownload(): Long {
        return if (BoxWrapperManager.isAvailable()) BoxWrapperManager.getDownloadTotal().coerceAtLeast(0L) else 0L
    }

    private fun readUidTx(uid: Int): Long = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0L)

    private fun readUidRx(uid: Int): Long = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0L)
}

private data class DashboardTrafficSample(
    val tx: Long,
    val rx: Long,
    val totalTx: Long,
    val totalRx: Long
)

private fun calculateAdaptiveSmoothFactor(current: Long, previous: Long): Double {
    if (previous <= 0) return 1.0

    val ratio = abs(current - previous).toDouble() / previous
    return when {
        ratio > 2.0 -> 0.7
        ratio > 0.5 -> 0.4
        ratio > 0.1 -> 0.25
        else -> 0.15
    }
}

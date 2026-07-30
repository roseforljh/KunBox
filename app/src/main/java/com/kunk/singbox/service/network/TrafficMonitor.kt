package com.kunk.singbox.service.network

class TrafficMonitor {

    data class TrafficSnapshot(
        val uploadSpeed: Long,
        val downloadSpeed: Long,
        val uploadDelta: Long,
        val downloadDelta: Long
    ) {
        companion object {
            val ZERO = TrafficSnapshot(0L, 0L, 0L, 0L)
        }
    }

    private var hasBaseline = false
    private var lastUploadTotal = 0L
    private var lastDownloadTotal = 0L
    private var lastSampleTimeMs = 0L

    @Synchronized
    fun updateTotals(uploadTotal: Long, downloadTotal: Long, sampleTimeMs: Long): TrafficSnapshot {
        if (uploadTotal < 0L || downloadTotal < 0L) {
            reset()
            return TrafficSnapshot.ZERO
        }

        val invalidTime = !hasBaseline || sampleTimeMs <= lastSampleTimeMs
        val countersRolledBack = uploadTotal < lastUploadTotal || downloadTotal < lastDownloadTotal
        if (invalidTime || countersRolledBack) {
            setBaseline(uploadTotal, downloadTotal, sampleTimeMs)
            return TrafficSnapshot.ZERO
        }

        val elapsedMs = sampleTimeMs - lastSampleTimeMs
        val uploadDelta = uploadTotal - lastUploadTotal
        val downloadDelta = downloadTotal - lastDownloadTotal
        setBaseline(uploadTotal, downloadTotal, sampleTimeMs)
        return TrafficSnapshot(
            uploadSpeed = bytesPerSecond(uploadDelta, elapsedMs),
            downloadSpeed = bytesPerSecond(downloadDelta, elapsedMs),
            uploadDelta = uploadDelta,
            downloadDelta = downloadDelta
        )
    }

    @Synchronized
    fun reset() {
        hasBaseline = false
        lastUploadTotal = 0L
        lastDownloadTotal = 0L
        lastSampleTimeMs = 0L
    }

    private fun setBaseline(uploadTotal: Long, downloadTotal: Long, sampleTimeMs: Long) {
        hasBaseline = true
        lastUploadTotal = uploadTotal
        lastDownloadTotal = downloadTotal
        lastSampleTimeMs = sampleTimeMs
    }

    companion object {
        internal fun bytesPerSecond(bytes: Long, elapsedMs: Long): Long {
            if (bytes <= 0L || elapsedMs <= 0L) return 0L
            return if (bytes > Long.MAX_VALUE / 1_000L) {
                Long.MAX_VALUE
            } else {
                bytes * 1_000L / elapsedMs
            }
        }
    }
}

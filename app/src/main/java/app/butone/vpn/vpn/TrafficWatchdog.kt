package app.butone.vpn.vpn

/**
 * Detects the "requests go out but nothing comes back" failure mode.
 * A suspicion never switches nodes by itself; the service must confirm it with
 * independent connectivity probes first.
 */
internal class TrafficWatchdog {
    data class Evaluation(val suspect: Boolean, val reason: String = "")

    private var previous: CoreApi.TrafficSnapshot? = null
    private var windowStartedAt = 0L
    private var uploadedInWindow = 0L
    private var downloadedInWindow = 0L

    fun observe(
        snapshot: CoreApi.TrafficSnapshot,
        now: Long,
        ignoreSample: Boolean = false,
    ): Evaluation {
        val before = previous
        previous = snapshot
        if (before == null || ignoreSample) {
            resetWindow(now)
            return Evaluation(false)
        }

        val uploaded = snapshot.uploadTotal - before.uploadTotal
        val downloaded = snapshot.downloadTotal - before.downloadTotal
        if (uploaded < 0L || downloaded < 0L) {
            // The core restarted and its cumulative counters returned to zero.
            resetWindow(now)
            return Evaluation(false)
        }

        if (windowStartedAt == 0L) windowStartedAt = now
        uploadedInWindow += uploaded
        downloadedInWindow += downloaded

        // A meaningful response proves that the path is moving data. Start a
        // fresh observation window instead of punishing a later quiet period.
        if (downloaded >= MIN_HEALTHY_DOWNLOAD_BYTES) {
            resetWindow(now)
            return Evaluation(false)
        }
        if (now - windowStartedAt < OBSERVATION_WINDOW_MS) return Evaluation(false)

        val strongPerConnectionSignal =
            snapshot.uploadOnlyProxied >= MIN_UPLOAD_ONLY_CONNECTIONS ||
                snapshot.stalledDnsConnections >= MIN_STALLED_DNS_CONNECTIONS
        val hasDemand = uploadedInWindow >= MIN_REQUEST_BYTES || strongPerConnectionSignal
        // Root totals include DIRECT Russian traffic too. Do not let an active
        // direct app hide several proxy connections that receive no bytes.
        val noUsefulResponse = downloadedInWindow < MIN_RESPONSE_BYTES || strongPerConnectionSignal
        val suspect = hasDemand && noUsefulResponse
        val reason = when {
            !suspect -> ""
            snapshot.stalledDnsConnections >= MIN_STALLED_DNS_CONNECTIONS ->
                "DNS-запросы уходят без ответа"
            snapshot.uploadOnlyProxied >= MIN_UPLOAD_ONLY_CONNECTIONS ->
                "несколько соединений отправляют данные без ответа"
            else -> "исходящий трафик есть, входящий отсутствует"
        }
        resetWindow(now)
        return Evaluation(suspect, reason)
    }

    fun reset() {
        previous = null
        windowStartedAt = 0L
        uploadedInWindow = 0L
        downloadedInWindow = 0L
    }

    private fun resetWindow(now: Long) {
        windowStartedAt = now
        uploadedInWindow = 0L
        downloadedInWindow = 0L
    }

    companion object {
        private const val OBSERVATION_WINDOW_MS = 20_000L
        private const val MIN_REQUEST_BYTES = 8 * 1024L
        private const val MIN_RESPONSE_BYTES = 1024L
        private const val MIN_HEALTHY_DOWNLOAD_BYTES = 4 * 1024L
        private const val MIN_UPLOAD_ONLY_CONNECTIONS = 3
        private const val MIN_STALLED_DNS_CONNECTIONS = 2
    }
}

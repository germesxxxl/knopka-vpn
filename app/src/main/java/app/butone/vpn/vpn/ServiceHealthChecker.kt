package app.butone.vpn.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import kotlin.system.measureTimeMillis

/** Tests real services through Mihomo's local proxy, not through Android's direct network. */
class ServiceHealthChecker(private val proxyPort: Int = ConfigBuilder.MIXED_PORT) {
    data class Result(
        val service: String,
        val ok: Boolean,
        val latencyMs: Long,
        val detail: String,
        val bytes: Int = 0,
    )

    data class Report(val results: List<Result>) {
        val okCount get() = results.count { it.ok }
        val total get() = results.size
        val usable get() = okCount >= 3
        fun compact() = results.joinToString("; ") {
            "${it.service}=${if (it.ok) "ok" else "fail"}/${it.latencyMs}ms/${it.detail}"
        }
    }

    suspend fun check(): Report = coroutineScope {
        val probes = listOf(
            async(Dispatchers.IO) { http("YouTube", "https://www.youtube.com/generate_204", exactStatus = 204) },
            async(Dispatchers.IO) { http("Instagram", "https://www.instagram.com/robots.txt") },
            async(Dispatchers.IO) { telegram() },
            async(Dispatchers.IO) { http("WhatsApp", "https://web.whatsapp.com/favicon.ico") },
        )
        Report(probes.map { it.await() })
    }

    private fun http(service: String, address: String, exactStatus: Int? = null): Result {
        var status = -1
        var bytes = 0
        var error = ""
        val elapsed = measureTimeMillis {
            runCatching {
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
                val connection = URL(address).openConnection(proxy) as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept-Encoding", "identity")
                connection.setRequestProperty("Range", "bytes=0-65535")
                try {
                    status = connection.responseCode
                    val input = if (status < 400) connection.inputStream else connection.errorStream
                    if (input != null) input.use { stream ->
                        val buffer = ByteArray(8192)
                        while (bytes < MAX_READ) {
                            val read = stream.read(buffer, 0, minOf(buffer.size, MAX_READ - bytes))
                            if (read < 0) break
                            bytes += read
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }.onFailure { error = it.javaClass.simpleName }
        }
        val statusOk = if (exactStatus != null) status == exactStatus else status in 200..499
        val bodyOk = exactStatus == 204 || bytes > 0
        return Result(service, error.isEmpty() && statusOk && bodyOk, elapsed, error.ifEmpty { "HTTP $status" }, bytes)
    }

    private fun telegram(): Result {
        var error = ""
        val elapsed = measureTimeMillis {
            runCatching {
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", proxyPort))
                Socket(proxy).use { socket ->
                    socket.connect(InetSocketAddress("149.154.167.50", 443), TIMEOUT_MS)
                    socket.soTimeout = TIMEOUT_MS
                    socket.getOutputStream().write(byteArrayOf(0xEF.toByte()))
                    socket.getOutputStream().flush()
                }
            }.onFailure { error = it.javaClass.simpleName }
        }
        return Result("Telegram", error.isEmpty(), elapsed, error.ifEmpty { "TCP 443" })
    }

    companion object {
        private const val TIMEOUT_MS = 8_000
        private const val MAX_READ = 64 * 1024
        private const val USER_AGENT = "Mozilla/5.0 (Android) butone-connectivity/1"
    }
}

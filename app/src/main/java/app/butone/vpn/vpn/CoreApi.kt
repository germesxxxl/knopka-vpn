package app.butone.vpn.vpn

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal client for Mihomo's REST controller on 127.0.0.1. */
class CoreApi(private val secret: String) {

    data class Status(
        val node: String?,
        val delayMs: Int?,
        val currentAlive: Boolean,
        val alive: Int,
        val total: Int,
    )

    data class Candidate(
        val name: String,
        val provider: String,
        val delayMs: Int,
        /** 0 regular, 1 restricted/white-list, 2 unnamed or reserve. */
        val pool: Int,
    )

    /** [providerNames]: our subscription providers (Mihomo also lists a built-in "default"). */
    fun status(providerNames: Set<String>): Status {
        val group = JSONObject(get("/proxies/${enc(ConfigBuilder.AUTO_GROUP)}"))
        val now = group.optString("now").ifEmpty { null }
        val candidates = candidates(providerNames)
        val current = candidates.firstOrNull { it.name == now }
        return Status(
            node = now,
            delayMs = current?.delayMs,
            currentAlive = current != null,
            alive = candidates.size,
            total = candidates.size,
        )
    }

    /** Fresh Mihomo measurements, sorted by pool first and latency second. */
    fun candidates(providerNames: Set<String>): List<Candidate> {
        val result = mutableListOf<Candidate>()
        val providers = JSONObject(get("/providers/proxies")).getJSONObject("providers")
        for (key in providers.keys()) {
            if (key !in providerNames) continue
            val proxies = providers.getJSONObject(key).optJSONArray("proxies") ?: continue
            for (i in 0 until proxies.length()) {
                val proxy = proxies.getJSONObject(i)
                val delay = lastDelay(proxy) ?: 0
                val alive = delay > 0 && (!proxy.has("alive") || proxy.optBoolean("alive"))
                if (!alive) continue
                val name = proxy.optString("name")
                result += Candidate(name, key, delay, pool(key, name))
            }
        }
        return result.sortedWith(compareBy<Candidate> { it.pool }.thenBy { it.delayMs })
    }

    /** Selects the exact provider node in our selector group. */
    fun selectNode(name: String) {
        request(
            "PUT",
            "/proxies/${enc(ConfigBuilder.AUTO_GROUP)}",
            JSONObject().put("name", name).toString(),
        )
    }

    /** Performs a fresh request through the currently selected outbound. */
    fun probeSelected(): Int {
        var lastError: Exception? = null
        for (url in PROBE_URLS) {
            try {
                val path = "/proxies/${enc(ConfigBuilder.AUTO_GROUP)}/delay" +
                    "?url=${enc(url)}&timeout=8000&expected=204"
                return JSONObject(get(path)).getInt("delay")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("all connectivity probes failed")
    }

    /** Triggers a fresh check of every node in a provider so AUTO can switch away. */
    fun healthCheckProvider(name: String) {
        get("/providers/proxies/${enc(name)}/healthcheck")
    }

    /** Cumulative bytes seen by the tunnel; growth proves that real traffic is flowing. */
    fun trafficTotal(): Long {
        val data = connections()
        return data.optLong("downloadTotal") + data.optLong("uploadTotal")
    }

    /** Raw /connections: totals plus every live connection with its inbound type and chain. */
    fun connections(): JSONObject = JSONObject(get("/connections"))

    /** Makes a file provider re-read its file, then re-tests its nodes. */
    fun reloadProvider(name: String) {
        request("PUT", "/providers/proxies/${enc(name)}")
    }

    private fun lastDelay(proxy: JSONObject): Int? {
        val history = proxy.optJSONArray("history") ?: return null
        if (history.length() == 0) return null
        return history.getJSONObject(history.length() - 1).optInt("delay", 0)
    }

    private fun get(path: String) = request("GET", path)

    private fun request(method: String, path: String, body: String? = null): String {
        val conn = URL("http://127.0.0.1:${ConfigBuilder.CONTROLLER_PORT}$path")
            .openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 2_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer $secret")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return try {
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                .orEmpty()
            if (status !in 200..299) throw IOException("Core API $status: ${body.take(300)}")
            body
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun pool(provider: String, name: String): Int = when {
        provider == "ru_white" || provider.contains("white") -> 1
        provider == "ru_other" && !hasCountryFlag(name) -> 2
        provider == "ru_mobile" || provider == "ru_other" -> 0
        else -> 2
    }

    private fun hasCountryFlag(value: String): Boolean =
        value.codePoints().anyMatch { it in 0x1F1E6..0x1F1FF }

    companion object {
        private val PROBE_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204",
        )

        /** "Igareck Node 001 | 🇫🇮 Finland， Helsinki | [BL]" -> "🇫🇮 Finland, Helsinki" */
        fun prettyName(raw: String): String {
            val withoutProvider = raw.replace(Regex("^\\[[^]]+]\\s*"), "")
            val parts = withoutProvider.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            val name = if (parts.size >= 2) parts[1] else withoutProvider
            return name.replace("，", ", ").replace(Regex("\\s+"), " ").take(40)
        }
    }
}

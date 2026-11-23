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
    ) {
        /** Stable identity for reputation; display names can be identical across sources. */
        val id: String get() = "$provider|$name"
    }

    data class TrafficSnapshot(
        val uploadTotal: Long,
        val downloadTotal: Long,
        val activeProxied: Int,
        val uploadOnlyProxied: Int,
        val stalledDnsConnections: Int,
    )

    private data class ProviderSnapshot(
        val candidates: List<Candidate>,
        val total: Int,
    )

    /** [providerNames]: our subscription providers (Mihomo also lists a built-in "default"). */
    fun status(providerNames: Set<String>): Status {
        val group = JSONObject(get("/proxies/${enc(ConfigBuilder.AUTO_GROUP)}"))
        val now = group.optString("now").ifEmpty { null }
        val snapshot = providerSnapshot(providerNames)
        val current = snapshot.candidates.firstOrNull { it.name == now }
        return Status(
            node = now,
            delayMs = current?.delayMs,
            currentAlive = current != null,
            alive = snapshot.candidates.size,
            total = snapshot.total,
        )
    }

    /** Fresh Mihomo measurements, sorted by pool first and latency second. */
    fun candidates(providerNames: Set<String>): List<Candidate> = providerSnapshot(providerNames).candidates

    private fun providerSnapshot(providerNames: Set<String>): ProviderSnapshot {
        val result = linkedMapOf<String, Candidate>()
        var total = 0
        val providers = JSONObject(get("/providers/proxies")).getJSONObject("providers")
        for (key in providers.keys()) {
            if (key !in providerNames) continue
            val proxies = providers.getJSONObject(key).optJSONArray("proxies") ?: continue
            for (i in 0 until proxies.length()) {
                total++
                val proxy = proxies.getJSONObject(i)
                val delay = lastDelay(proxy) ?: 0
                val alive = delay > 0 && (!proxy.has("alive") || proxy.optBoolean("alive"))
                if (!alive) continue
                val name = proxy.optString("name")
                val candidate = Candidate(name, key, delay, pool(key, name))
                val previous = result[name]
                if (previous == null || candidate.delayMs < previous.delayMs) {
                    result[name] = candidate
                }
            }
        }
        return ProviderSnapshot(
            candidates = result.values.sortedWith(compareBy<Candidate> { it.pool }.thenBy { it.delayMs }),
            total = total,
        )
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
                    "?url=${enc(url)}&timeout=5000&expected=204"
                return JSONObject(get(path)).getInt("delay")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("all connectivity probes failed")
    }

    /**
     * A single captive-portal URL can fail while the tunnel is healthy. Require
     * independent destinations before treating the selected path as alive.
     */
    fun probeSelectedQuorum(required: Int = 2): Boolean {
        var successes = 0
        for (url in PROBE_URLS) {
            try {
                val path = "/proxies/${enc(ConfigBuilder.AUTO_GROUP)}/delay" +
                    "?url=${enc(url)}&timeout=5000&expected=204"
                if (JSONObject(get(path)).getInt("delay") > 0) successes++
                if (successes >= required) return true
            } catch (_: Exception) {
                // A quorum deliberately tolerates one failed destination.
            }
        }
        return false
    }

    /** Tests a provider-owned node without changing the user's AUTO selector. */
    fun probeCandidate(candidate: Candidate, required: Int = 2): Boolean {
        var successes = 0
        for (url in PROBE_URLS) {
            try {
                val path = "/providers/proxies/${enc(candidate.provider)}/${enc(candidate.name)}/healthcheck" +
                    "?url=${enc(url)}&timeout=5000"
                if (JSONObject(get(path)).getInt("delay") > 0) successes++
                if (successes >= required) return true
            } catch (_: Exception) {
                // The node can disappear during a provider refresh; just skip it.
            }
        }
        return false
    }

    /** Triggers a fresh check of every node in a provider so AUTO can switch away. */
    fun healthCheckProvider(name: String) {
        get("/providers/proxies/${enc(name)}/healthcheck")
    }

    /** Raw /connections: totals plus every live connection with its inbound type and chain. */
    fun connections(): JSONObject = JSONObject(get("/connections"))

    /**
     * Cumulative counters are monotonic for the core lifetime. Per-connection
     * fields help diagnostics distinguish a quiet phone from an active black hole.
     */
    fun trafficSnapshot(selectedNode: String? = null): TrafficSnapshot {
        val root = connections()
        val items = root.optJSONArray("connections")
        var activeProxied = 0
        var uploadOnly = 0
        var stalledDns = 0
        if (items != null) {
            for (i in 0 until items.length()) {
                val connection = items.optJSONObject(i) ?: continue
                val chains = connection.optJSONArray("chains")
                var throughVpn = false
                if (chains != null) {
                    for (j in 0 until chains.length()) {
                        val chain = chains.optString(j)
                        if (chain == ConfigBuilder.AUTO_GROUP || chain == selectedNode) {
                            throughVpn = true
                            break
                        }
                    }
                }
                if (!throughVpn) continue
                activeProxied++
                val uploaded = connection.optLong("upload")
                val downloaded = connection.optLong("download")
                if (uploaded > 0L && downloaded == 0L) {
                    uploadOnly++
                    val metadata = connection.optJSONObject("metadata")
                    val host = metadata?.optString("host").orEmpty().lowercase()
                    if (host in DNS_HOSTS) stalledDns++
                }
            }
        }
        return TrafficSnapshot(
            uploadTotal = root.optLong("uploadTotal"),
            downloadTotal = root.optLong("downloadTotal"),
            activeProxied = activeProxied,
            uploadOnlyProxied = uploadOnly,
            stalledDnsConnections = stalledDns,
        )
    }

    /** Close only connections pinned to the dead node; DIRECT traffic is preserved. */
    fun closeConnectionsThrough(node: String): Int {
        val items = connections().optJSONArray("connections") ?: return 0
        val ids = mutableListOf<String>()
        for (i in 0 until items.length()) {
            val connection = items.optJSONObject(i) ?: continue
            val chains = connection.optJSONArray("chains") ?: continue
            var usesNode = false
            for (j in 0 until chains.length()) {
                if (chains.optString(j) == node) {
                    usesNode = true
                    break
                }
            }
            if (usesNode) connection.optString("id").takeIf { it.isNotEmpty() }?.let(ids::add)
        }
        var closed = 0
        for (id in ids) {
            runCatching { request("DELETE", "/connections/${enc(id)}") }
                .onSuccess { closed++ }
        }
        return closed
    }

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
        private val DNS_HOSTS = setOf(
            "dns.google",
            "cloudflare-dns.com",
            "dns.quad9.net",
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

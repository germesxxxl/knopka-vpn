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

    /** [providerNames]: our subscription providers (Mihomo also lists a built-in "default"). */
    fun status(providerNames: Set<String>): Status {
        val group = JSONObject(get("/proxies/${enc(ConfigBuilder.AUTO_GROUP)}"))
        val now = group.optString("now").ifEmpty { null }

        // Provider nodes are not served by /proxies/{name} (404), so read their
        // ping history from the provider listing instead.
        var alive = 0
        var total = 0
        var delay: Int? = null
        var currentAlive = false
        val providers = JSONObject(get("/providers/proxies")).getJSONObject("providers")
        for (key in providers.keys()) {
            if (key !in providerNames) continue
            val proxies = providers.getJSONObject(key).optJSONArray("proxies") ?: continue
            for (i in 0 until proxies.length()) {
                val p = proxies.getJSONObject(i)
                val d = lastDelay(p) ?: 0
                val testedAlive = d > 0 && (!p.has("alive") || p.optBoolean("alive"))
                total++
                // Untested nodes can initially be marked alive, so require a
                // positive measurement as well as Mihomo's live flag.
                if (testedAlive) alive++
                if (delay == null && p.optString("name") == now) {
                    delay = d
                    currentAlive = testedAlive
                }
            }
        }
        return Status(now, delay?.takeIf { it > 0 }, currentAlive, alive, total)
    }

    /** Performs a fresh request through the currently selected outbound. */
    fun probeSelected(): Int {
        val path = "/proxies/${enc(ConfigBuilder.AUTO_GROUP)}/delay" +
            "?url=${enc(PROBE_URL)}&timeout=8000&expected=204"
        return JSONObject(get(path)).getInt("delay")
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

    private fun request(method: String, path: String): String {
        val conn = URL("http://127.0.0.1:${ConfigBuilder.CONTROLLER_PORT}$path")
            .openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 2_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Authorization", "Bearer $secret")
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

    companion object {
        private const val PROBE_URL = "https://www.gstatic.com/generate_204"

        /** "Igareck Node 001 | 🇫🇮 Finland， Helsinki | [BL]" -> "🇫🇮 Finland, Helsinki" */
        fun prettyName(raw: String): String {
            val parts = raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            val name = if (parts.size >= 2) parts[1] else raw
            return name.replace("，", ", ").replace(Regex("\\s+"), " ").take(40)
        }
    }
}

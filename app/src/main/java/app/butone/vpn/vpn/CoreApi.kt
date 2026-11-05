package app.butone.vpn.vpn

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal client for Mihomo's REST controller on 127.0.0.1. */
class CoreApi(private val secret: String) {

    data class Status(val node: String?, val delayMs: Int?, val alive: Int, val total: Int)

    /** [providerNames]: our subscription providers (Mihomo also lists a built-in "default"). */
    fun status(providerNames: Set<String>): Status {
        val group = JSONObject(get("/proxies/${enc(ConfigBuilder.AUTO_GROUP)}"))
        val now = group.optString("now").ifEmpty { null }

        // Provider nodes are not served by /proxies/{name} (404), so read their
        // ping history from the provider listing instead.
        var alive = 0
        var total = 0
        var delay: Int? = null
        val providers = JSONObject(get("/providers/proxies")).getJSONObject("providers")
        for (key in providers.keys()) {
            if (key !in providerNames) continue
            val proxies = providers.getJSONObject(key).optJSONArray("proxies") ?: continue
            for (i in 0 until proxies.length()) {
                val p = proxies.getJSONObject(i)
                val d = lastDelay(p) ?: 0
                total++
                // Mihomo marks untested nodes alive, so count only ones with a successful ping.
                if (d > 0) alive++
                if (delay == null && p.optString("name") == now) delay = d
            }
        }
        return Status(now, delay?.takeIf { it > 0 }, alive, total)
    }

    /** Makes a file provider re-read its file, then re-tests its nodes. */
    fun reloadProvider(name: String) {
        request("PUT", "/providers/proxies/${enc(name)}")
        get("/providers/proxies/${enc(name)}/healthcheck")
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
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        /** "Igareck Node 001 | 🇫🇮 Finland， Helsinki | [BL]" -> "🇫🇮 Finland, Helsinki" */
        fun prettyName(raw: String): String {
            val parts = raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            val name = if (parts.size >= 2) parts[1] else raw
            return name.replace("，", ", ").replace(Regex("\\s+"), " ").take(40)
        }
    }
}

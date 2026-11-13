package app.butone.vpn.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent, privacy-filtered event history. It survives process and phone restarts. */
class EventJournal(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "journal")
    private val prefs = app.getSharedPreferences("journal", Context.MODE_PRIVATE)

    init {
        dir.mkdirs()
        if (!prefs.contains(KEY_INSTALLED_AT)) {
            prefs.edit().putLong(KEY_INSTALLED_AT, System.currentTimeMillis()).apply()
        }
    }

    val installedAt: Long get() = prefs.getLong(KEY_INSTALLED_AT, System.currentTimeMillis())

    fun record(type: String, message: String, fields: Map<String, Any?> = emptyMap()) {
        runCatching {
            val now = System.currentTimeMillis()
            val item = JSONObject()
                .put("at", now)
                .put("type", clean(type, 40))
                .put("message", clean(message, 500))
                .put("network", networkKind(app))
            fields.forEach { (key, value) ->
                if (key.lowercase() !in SECRET_KEYS) {
                    item.put(clean(key, 40), clean(value?.toString().orEmpty(), 500))
                }
            }
            synchronized(FILE_LOCK) {
                fileFor(now).appendText(item.toString() + "\n", Charsets.UTF_8)
                trimIfNeeded()
            }
        }
    }

    /** Human-readable event history for a report. null means the whole retained history. */
    fun read(days: Int?): String = synchronized(FILE_LOCK) {
        val since = days?.let { System.currentTimeMillis() - it * DAY_MS } ?: Long.MIN_VALUE
        val output = StringBuilder()
        var included = 0
        files().forEach { file ->
            file.useLines(Charsets.UTF_8) { lines ->
                lines.forEach { line ->
                    runCatching { JSONObject(line) }.getOrNull()?.let { item ->
                        val at = item.optLong("at")
                        if (at >= since) {
                            included++
                            output.append(TIME_FORMAT.format(Date(at)))
                                .append("  ").append(item.optString("type"))
                                .append("  ").append(item.optString("message"))
                            val keys = item.keys().asSequence()
                                .filterNot { it in setOf("at", "type", "message") }
                                .toList()
                            if (keys.isNotEmpty()) {
                                output.append("  ")
                                output.append(keys.joinToString(", ") { "$it=${item.optString(it)}" })
                            }
                            output.appendLine()
                        }
                    }
                }
            }
        }
        "Events: $included\n" + output.toString().takeLast(MAX_REPORT_CHARS)
    }

    fun retainedBytes(): Long = synchronized(FILE_LOCK) { files().sumOf { it.length() } }

    private fun fileFor(at: Long) = File(dir, "events-${DAY_FORMAT.format(Date(at))}.jsonl")

    private fun files() = dir.listFiles { f -> f.name.startsWith("events-") && f.name.endsWith(".jsonl") }
        ?.sortedBy { it.name }
        .orEmpty()

    private fun trimIfNeeded() {
        val files = files().toMutableList()
        var total = files.sumOf { it.length() }
        while (total > MAX_BYTES && files.size > 1) {
            val oldest = files.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    companion object {
        private val FILE_LOCK = Any()
        private const val KEY_INSTALLED_AT = "installed_at"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MAX_BYTES = 50L * 1024 * 1024
        private const val MAX_REPORT_CHARS = 1_500_000
        private val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        private val SECRET_KEYS = setOf("uuid", "password", "secret", "token", "key")

        private fun clean(value: String, max: Int): String = value
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .take(max)

        fun networkKind(context: Context): String {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
            val caps = cm.allNetworks.asSequence()
                .mapNotNull { cm.getNetworkCapabilities(it) }
                .firstOrNull {
                    !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
                ?: cm.getNetworkCapabilities(cm.activeNetwork)
                ?: return "offline"
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        }
    }
}

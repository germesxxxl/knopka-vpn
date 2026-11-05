package app.knopka.vpn.data

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Keeps one cached YAML file per [Source] under `filesDir/mihomo/providers`.
 *
 * Connecting never waits for the network: on first run the snapshot bundled in
 * the APK (assets/subs) is used, afterwards the last downloaded copy. Fresh lists
 * are fetched in the background once connected — through Mihomo's local proxy,
 * so the download works even where GitHub and its mirrors are blocked.
 */
class SubscriptionRepository(private val context: Context) {

    val homeDir = File(context.filesDir, "mihomo")
    private val providerDir = File(homeDir, "providers")
    private val ruleDir = File(homeDir, "rules")

    fun providerFile(source: Source) = File(providerDir, "${source.id}.yaml")

    /** Prepares provider and rule files without touching the network. Returns usable sources. */
    fun prepareOffline(): List<Source> {
        providerDir.mkdirs()
        ruleDir.mkdirs()
        for (name in RULE_FILES) copyAsset("rules/$name", File(ruleDir, name), overwrite = true)

        return Sources.all.filter { source ->
            val file = providerFile(source)
            if (!isValid(file)) copyAsset("subs/${source.id}.yaml", file, overwrite = true)
            isValid(file)
        }
    }

    fun staleSources(maxAgeMs: Long = MAX_AGE_MS): List<Source> {
        val now = System.currentTimeMillis()
        return Sources.all.filter { now - providerFile(it).lastModified() > maxAgeMs }
    }

    /**
     * Downloads [source] trying each mirror, optionally through a local HTTP proxy.
     * Writes atomically and only if the content looks like a Clash config.
     */
    fun download(source: Source, proxyPort: Int? = null): Boolean {
        val proxy = proxyPort?.let { Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", it)) }
            ?: Proxy.NO_PROXY
        for (url in source.urls) {
            try {
                val conn = URL(url).openConnection(proxy) as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("User-Agent", "clash.meta")
                val body = try {
                    if (conn.responseCode != 200) continue
                    conn.inputStream.use { it.readBytes() }
                } finally {
                    conn.disconnect()
                }
                if (!looksLikeClash(body)) continue

                val target = providerFile(source)
                val tmp = File(target.path + ".tmp")
                tmp.writeBytes(body)
                if (tmp.renameTo(target)) return true
            } catch (e: Exception) {
                Log.w(TAG, "download ${source.id} from $url failed: $e")
            }
        }
        return false
    }

    private fun isValid(file: File) =
        file.exists() && file.length() > 512 && looksLikeClash(file.readBytes())

    private fun looksLikeClash(body: ByteArray): Boolean {
        if (body.size < 512) return false
        val text = String(body, Charsets.UTF_8)
        return text.contains("\nproxies:") || text.startsWith("proxies:")
    }

    private fun copyAsset(assetPath: String, target: File, overwrite: Boolean) {
        if (target.exists() && !overwrite) return
        try {
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "asset $assetPath missing: $e")
        }
    }

    companion object {
        private const val TAG = "Subscriptions"
        const val MAX_AGE_MS = 3 * 60 * 60 * 1000L
        val RULE_FILES = listOf("category-ru.mrs", "geoip-ru.mrs")
    }
}

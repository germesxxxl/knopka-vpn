package app.butone.vpn.data

import android.util.Log
import core.Core
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object SourceCatalog {
    private const val TAG = "SourceCatalog"
    private const val MAX_CATALOG_BYTES = 64 * 1024
    private const val PUBLIC_KEY = "DRqQ4zt38yWAIHMH8FnoaZeBJ2Bi0tsbB88L7qjS3+o="

    private val mirrors = listOf(
        "https://raw.githack.com/germesxxxl/knopka-vpn/main/catalog/catalog.json",
        "https://cdn.jsdelivr.net/gh/germesxxxl/knopka-vpn@main/catalog/catalog.json",
        "https://raw.githubusercontent.com/germesxxxl/knopka-vpn/main/catalog/catalog.json",
    )

    fun load(cache: File): List<Source> = runCatching {
        mergeBundled(parse(cache.readText(Charsets.UTF_8)))
    }.onFailure { Log.w(TAG, "cached catalog rejected: $it") }
        .getOrDefault(Sources.bundled)

    fun refresh(cache: File, proxyPort: Int?): List<Source>? {
        val proxy = proxyPort?.let { Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", it)) }
            ?: Proxy.NO_PROXY
        for (url in mirrors) {
            try {
                val body = get(url, proxy, MAX_CATALOG_BYTES)
                val signature = get("$url.sig", proxy, 256).toString(Charsets.UTF_8).trim()
                val text = body.toString(Charsets.UTF_8)
                Core.verifyEd25519(PUBLIC_KEY, text, signature)
                val sources = mergeBundled(parse(text))
                cache.parentFile?.mkdirs()
                val candidate = File(cache.path + ".download")
                candidate.writeBytes(body)
                Files.move(candidate.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return sources
            } catch (e: Exception) {
                Log.w(TAG, "catalog from $url rejected: $e")
            }
        }
        return null
    }

    private fun parse(text: String): List<Source> {
        val root = JSONObject(text)
        require(root.getInt("version") == 1) { "unsupported catalog version" }
        val array = root.getJSONArray("sources")
        require(array.length() in 1..20) { "catalog must contain 1..20 sources" }
        val ids = mutableSetOf<String>()
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            require(id.matches(Regex("[a-z0-9_]{1,32}")) && ids.add(id)) { "invalid source id" }
            val urlsJson = item.getJSONArray("urls")
            require(urlsJson.length() in 1..10) { "source must contain 1..10 URLs" }
            val urls = List(urlsJson.length()) { urlsJson.getString(it) }
            require(urls.all { URL(it).protocol == "https" }) { "only HTTPS source URLs are allowed" }
            Source(id, urls)
        }
    }

    /** Signed catalog may change mirrors, but APK-pinned reserve sources cannot be removed remotely. */
    private fun mergeBundled(remote: List<Source>): List<Source> {
        val byId = remote.associateBy { it.id }.toMutableMap()
        Sources.bundled.forEach { source -> byId.putIfAbsent(source.id, source) }
        return byId.values.toList()
    }

    private fun get(url: String, proxy: Proxy, limit: Int): ByteArray {
        val connection = URL(url).openConnection(proxy) as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "butone-android")
        return try {
            require(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4 * 1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= limit) { "response is too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}

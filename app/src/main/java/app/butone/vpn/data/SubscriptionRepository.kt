package app.butone.vpn.data

import android.content.Context
import android.util.Log
import core.Core
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Keeps one cached YAML file per [Source] under `filesDir/mihomo/providers`.
 *
 * Connecting never waits for the network: on first run the snapshot bundled in
 * the APK (assets/subs) is used, afterwards the last downloaded copy. Fresh lists
 * are fetched in the background once connected — through Mihomo's local proxy,
 * so the download works even where GitHub and its mirrors are blocked.
 */
class SubscriptionRepository(private val context: Context) {

    data class PendingUpdate(val source: Source, val candidate: File)
    data class InstalledUpdate(val source: Source, val backup: File?)

    val homeDir = File(context.filesDir, "mihomo")
    private val providerDir = File(homeDir, "providers")
    private val ruleDir = File(homeDir, "rules")
    private val catalogFile = File(homeDir, "source-catalog.json")
    private var sources = SourceCatalog.load(catalogFile)

    fun providerFile(source: Source) = File(providerDir, "${source.id}.yaml")
    private fun stampFile(source: Source) = File(providerDir, "${source.id}.updated")

    /** Prepares provider and rule files without touching the network. Returns usable sources. */
    fun prepareOffline(): List<Source> {
        providerDir.mkdirs()
        ruleDir.mkdirs()
        for (name in RULE_FILES) copyAsset("rules/$name", File(ruleDir, name), overwrite = true)

        return sources.filter { source ->
            val file = providerFile(source)
            if (!isValid(file)) {
                // An asset snapshot has no network freshness timestamp, so it
                // will be refreshed during this first active session.
                stampFile(source).delete()
                copyAsset("subs/${source.id}.yaml", file, overwrite = true)
            }
            isValid(file)
        }
    }

    fun staleSources(maxAgeMs: Long = MAX_AGE_MS): List<Source> {
        val now = System.currentTimeMillis()
        return sources.filter { source ->
            val stamp = stampFile(source)
            !stamp.exists() || now - stamp.lastModified() > maxAgeMs
        }
    }

    /** Downloads and authenticates the source list; the built-in list remains the fallback. */
    fun refreshCatalog(proxyPort: Int? = null): Boolean {
        val refreshed = SourceCatalog.refresh(catalogFile, proxyPort) ?: return false
        sources = refreshed
        return true
    }

    /**
     * Downloads [source] trying each mirror, optionally through a local HTTP proxy.
     * Downloads to a candidate file. The active file is replaced separately so
     * the caller can ask Mihomo to parse it and roll back on failure.
     */
    fun download(source: Source, proxyPort: Int? = null): PendingUpdate? {
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
                    conn.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_DOWNLOAD_BYTES) {
                                throw IOException("provider exceeds $MAX_DOWNLOAD_BYTES bytes")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                } finally {
                    conn.disconnect()
                }
                if (!isValid(body)) continue

                val candidate = File(providerDir, "${source.id}.download")
                candidate.writeBytes(body)
                return PendingUpdate(source, candidate)
            } catch (e: Exception) {
                Log.w(TAG, "download ${source.id} from $url failed: $e")
            }
        }
        return null
    }

    /** Replaces the active file while keeping a rollback copy. */
    fun install(update: PendingUpdate): InstalledUpdate {
        val target = providerFile(update.source)
        val backup = File(providerDir, "${update.source.id}.bak")
        backup.delete()
        val saved = if (target.exists()) {
            Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            backup
        } else {
            null
        }
        moveReplacing(update.candidate, target)
        return InstalledUpdate(update.source, saved)
    }

    fun commit(update: InstalledUpdate) {
        update.backup?.delete()
        val stamp = stampFile(update.source)
        stamp.writeText(System.currentTimeMillis().toString())
    }

    fun rollback(update: InstalledUpdate) {
        val target = providerFile(update.source)
        if (update.backup?.exists() == true) {
            moveReplacing(update.backup, target)
        } else {
            target.delete()
        }
    }

    fun discard(update: PendingUpdate) {
        update.candidate.delete()
    }

    private fun moveReplacing(from: File, to: File) {
        try {
            Files.move(
                from.toPath(), to.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isValid(file: File): Boolean {
        if (!file.exists() || file.length() <= 0 || file.length() > MAX_DOWNLOAD_BYTES.toLong()) return false
        return isValid(file.readBytes())
    }

    private fun isValid(body: ByteArray): Boolean = runCatching {
        Core.validateProvider(String(body, Charsets.UTF_8))
    }.isSuccess

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
        private const val MAX_DOWNLOAD_BYTES = 2 * 1024 * 1024
        const val MAX_AGE_MS = 3 * 60 * 60 * 1000L
        val RULE_FILES = listOf("category-ru.mrs", "geoip-ru.mrs")
    }
}

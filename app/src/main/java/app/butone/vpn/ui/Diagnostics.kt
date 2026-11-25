package app.butone.vpn.ui

import android.content.Context
import android.os.Build
import app.butone.vpn.BuildConfig
import app.butone.vpn.diagnostics.EventJournal
import app.butone.vpn.vpn.CoreApi
import app.butone.vpn.vpn.VpnState
import core.Core
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Creates privacy-filtered reports as files instead of pushing megabytes through ClipboardManager. */
object Diagnostics {

    data class GeneratedReport(val file: File, val preview: String)

    fun createReport(context: Context, days: Int? = 7): GeneratedReport {
        val directory = File(context.cacheDir, "reports").apply { mkdirs() }
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > DAY_MS }
            ?.forEach { it.delete() }
        val stamp = FILE_TIME_FORMAT.format(Date())
        val period = days?.let { "${it}d" } ?: "all"
        val file = File(directory, "butone-report-$period-$stamp.txt")
        file.bufferedWriter(Charsets.UTF_8).use { writer -> writeReport(context, days, writer) }
        val preview = file.useLines(Charsets.UTF_8) { lines ->
            lines.take(14).joinToString("\n")
        } + "\n\nФайл готов: ${file.name}\nРазмер: ${file.length() / 1024} КБ"
        return GeneratedReport(file, preview)
    }

    private fun writeReport(context: Context, days: Int?, writer: Writer) {
        val journal = EventJournal(context)
        writer.appendLine("butone ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        writer.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        writer.appendLine("Core: ${runCatching { Core.version() }.getOrDefault("?")}, running=${runCatching { Core.isRunning() }.getOrDefault(false)}")
        writer.appendLine("Status: ${VpnState.status.value.javaClass.simpleName}")
        writer.appendLine("Network: ${EventJournal.networkKind(context)}")
        writer.appendLine("Installed/journal since: ${Date(journal.installedAt)}")
        writer.appendLine("Journal size: ${journal.retainedBytes() / 1024} KB")
        writer.appendLine("Report period: ${days?.let { "$it days" } ?: "all retained history"}")
        writer.appendLine()

        val secret = VpnState.apiSecret
        if (secret != null && runCatching { Core.isRunning() }.getOrDefault(false)) {
            try {
                val connections = CoreApi(secret).connections()
                writer.appendLine(
                    "Traffic total: down=${kb(connections.optLong("downloadTotal"))} " +
                        "up=${kb(connections.optLong("uploadTotal"))}"
                )
                val list = connections.optJSONArray("connections")
                val count = list?.length() ?: 0
                val byType = mutableMapOf<String, Int>()
                for (index in 0 until count) {
                    val type = list!!.getJSONObject(index).optJSONObject("metadata")?.optString("type") ?: "?"
                    byType[type] = (byType[type] ?: 0) + 1
                }
                // Destinations and browsing domains are deliberately omitted from exported reports.
                writer.appendLine("Connections: $count by inbound $byType")
            } catch (e: Exception) {
                writer.appendLine("API error: ${e.javaClass.simpleName}")
            }
        } else {
            writer.appendLine("Core API: not running")
        }

        writer.appendLine()
        writer.appendLine("---- persistent event journal ----")
        journal.writeTo(writer, days)
    }

    private fun kb(bytes: Long) = "${bytes / 1024} KB"

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private val FILE_TIME_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
}

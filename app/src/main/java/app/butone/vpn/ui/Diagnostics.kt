package app.butone.vpn.ui

import android.content.Context
import android.os.Build
import app.butone.vpn.BuildConfig
import app.butone.vpn.vpn.CoreApi
import app.butone.vpn.vpn.VpnState
import core.Core

/**
 * Plain-text report the user can copy and send to the developer.
 * Key signal: connections with inbound type "Tun" prove that other apps'
 * traffic actually goes through the tunnel (pings bypass it).
 */
object Diagnostics {

    fun report(context: Context): String = buildString {
        appendLine("butone ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Core: ${runCatching { Core.version() }.getOrDefault("?")}, running=${runCatching { Core.isRunning() }.getOrDefault(false)}")
        appendLine("Status: ${VpnState.status.value}")
        appendLine()

        val secret = VpnState.apiSecret
        if (secret != null && runCatching { Core.isRunning() }.getOrDefault(false)) {
            try {
                val c = CoreApi(secret).connections()
                appendLine("Traffic total: down=${kb(c.optLong("downloadTotal"))} up=${kb(c.optLong("uploadTotal"))}")
                val list = c.optJSONArray("connections")
                val n = list?.length() ?: 0
                val byType = mutableMapOf<String, Int>()
                for (i in 0 until n) {
                    val t = list!!.getJSONObject(i).optJSONObject("metadata")?.optString("type") ?: "?"
                    byType[t] = (byType[t] ?: 0) + 1
                }
                appendLine("Connections: $n by inbound $byType")
                for (i in 0 until minOf(n, 25)) {
                    val o = list!!.getJSONObject(i)
                    val m = o.optJSONObject("metadata")
                    val host = m?.optString("host")?.ifEmpty { null } ?: m?.optString("destinationIP")
                    val chains = o.optJSONArray("chains")?.let { a ->
                        (0 until a.length()).joinToString(" < ") { a.getString(it) }
                    }
                    appendLine(
                        "  ${m?.optString("type")} ${m?.optString("network")} $host:${m?.optString("destinationPort")}" +
                            " rule=${o.optString("rule")} via=$chains down=${kb(o.optLong("download"))}"
                    )
                }
            } catch (e: Exception) {
                appendLine("API error: $e")
            }
        } else {
            appendLine("Core API: not running")
        }

        appendLine()
        appendLine("---- core log (last 120) ----")
        val log = runCatching { Core.logs() }.getOrDefault("")
        appendLine(log.lines().takeLast(120).joinToString("\n"))
    }

    private fun kb(bytes: Long) = "${bytes / 1024} KB"
}

package app.butone.vpn.vpn

import android.content.Context
import app.butone.vpn.diagnostics.EventJournal
import java.security.MessageDigest

/** Per-network circuit breaker: repeatedly bad nodes are temporarily skipped. */
class NodeReputation(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("node_reputation", Context.MODE_PRIVATE)

    fun isCoolingDown(node: String): Boolean = cooldownUntil(node) > System.currentTimeMillis()

    fun cooldownRemainingMs(node: String): Long =
        (cooldownUntil(node) - System.currentTimeMillis()).coerceAtLeast(0)

    fun recordFailure(node: String): Long {
        val key = key(node)
        val failures = prefs.getInt("${key}_failures", 0) + 1
        val pause = when (failures) {
            1 -> 2 * 60_000L
            2 -> 30 * 60_000L
            3 -> 6 * 60 * 60_000L
            else -> 24 * 60 * 60_000L
        }
        prefs.edit()
            .putInt("${key}_failures", failures)
            .putLong("${key}_until", System.currentTimeMillis() + pause)
            .apply()
        return pause
    }

    fun recordSuccess(node: String) {
        val key = key(node)
        prefs.edit().putInt("${key}_failures", 0).remove("${key}_until").apply()
    }

    private fun cooldownUntil(node: String) = prefs.getLong("${key(node)}_until", 0L)

    private fun key(node: String): String {
        val source = EventJournal.networkKind(app) + "|" + node
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}

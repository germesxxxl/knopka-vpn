package app.butone.vpn.vpn

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.butone.vpn.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick Settings tile: the same one button, from the notification shade. */
class ToggleTileService : TileService() {
    private var watcher: Job? = null

    override fun onStartListening() {
        watcher = CoroutineScope(Dispatchers.Main).launch {
            VpnState.status.collect { render(it) }
        }
    }

    override fun onStopListening() {
        watcher?.cancel()
        watcher = null
    }

    override fun onClick() {
        when {
            VpnState.isActive -> ButoneVpnService.stop(this)
            // VPN permission not granted yet: it can only be requested from an activity.
            VpnService.prepare(this) != null -> openApp()
            else -> ButoneVpnService.start(this)
        }
    }

    private fun render(status: VpnStatus) {
        val tile = qsTile ?: return
        tile.state = if (status is VpnStatus.Disconnected || status is VpnStatus.Error)
            Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (status) {
                is VpnStatus.Connected -> status.node ?: "Подбор сервера…"
                VpnStatus.Connecting -> "Подключение…"
                else -> null
            }
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

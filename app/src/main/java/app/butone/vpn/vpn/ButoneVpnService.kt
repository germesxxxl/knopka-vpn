package app.butone.vpn.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.butone.vpn.App
import app.butone.vpn.R
import app.butone.vpn.data.SubscriptionRepository
import app.butone.vpn.ui.MainActivity
import core.Core
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class ButoneVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var tun: ParcelFileDescriptor? = null
    private var background: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { lock.withLock { stopVpnLocked() } }
            return START_NOT_STICKY
        }
        // ACTION_START, or the system starting us for always-on VPN.
        startForegroundCompat(notification(getString(R.string.app_name), "Подключение…"))
        scope.launch { lock.withLock { startVpnLocked() } }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over, or the user revoked permission in settings.
        scope.launch { lock.withLock { stopVpnLocked() } }
    }

    override fun onDestroy() {
        background?.cancel()
        if (Core.isRunning()) Core.stop()
        tun?.close()
        tun = null
        if (VpnState.status.value !is VpnStatus.Error) VpnState.set(VpnStatus.Disconnected)
        scope.cancel()
        super.onDestroy()
    }

    private fun startVpnLocked() {
        if (tun != null) return
        VpnState.set(VpnStatus.Connecting)
        try {
            val repo = SubscriptionRepository(this)
            val sources = repo.prepareOffline()
            if (sources.isEmpty()) error("Нет списка серверов. Проверьте интернет и попробуйте ещё раз.")

            val pfd = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(1500)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("::", 0)
                .addDnsServer("172.19.0.2")
                // Mihomo runs in this process: exclude ourselves so its sockets bypass the tunnel.
                .addDisallowedApplication(packageName)
                .setConfigureIntent(openAppIntent())
                .apply { if (Build.VERSION.SDK_INT >= 29) setMetered(false) }
                .establish()
                ?: error("Нет разрешения на VPN")
            tun = pfd

            val secret = UUID.randomUUID().toString()
            VpnState.apiSecret = secret
            // Mihomo takes ownership of the duplicate and closes it on shutdown.
            val coreFd = pfd.dup().detachFd()
            Core.start(repo.homeDir.absolutePath, ConfigBuilder.build(coreFd, sources, secret))

            VpnState.set(VpnStatus.Connected())
            val names = sources.map { it.id }.toSet()
            background = scope.launch { monitor(CoreApi(secret), names) }
            scope.launch { refreshSubscriptions(repo, CoreApi(secret)) }
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            stopVpnLocked(VpnStatus.Error(e.message ?: e.toString()))
        }
    }

    private fun stopVpnLocked(finalStatus: VpnStatus = VpnStatus.Disconnected) {
        background?.cancel()
        background = null
        try {
            if (Core.isRunning()) Core.stop()
        } catch (e: Exception) {
            Log.w(TAG, "core stop", e)
        }
        tun?.close()
        tun = null
        VpnState.set(finalStatus)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Polls the core for the selected node and ping; updates UI state and notification. */
    private suspend fun monitor(api: CoreApi, providers: Set<String>) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        var since: Long? = null
        while (scope.isActive) {
            try {
                val s = api.status(providers)
                val pretty = s.node?.let { CoreApi.prettyName(it) }
                val ready = pretty != null && s.delayMs != null
                if (ready && since == null) since = System.currentTimeMillis()
                VpnState.set(
                    VpnStatus.Connected(
                        node = if (ready) pretty else null,
                        delayMs = s.delayMs,
                        alive = s.alive,
                        total = s.total,
                        since = since,
                    )
                )
                val text = if (ready) "Подключено · $pretty · ${s.delayMs} мс" else "Подбираем лучший сервер…"
                nm.notify(NOTIFICATION_ID, notification(getString(R.string.app_name), text))
            } catch (e: Exception) {
                Log.w(TAG, "status poll: $e")
            }
            delay(if (VpnState.status.value.let { it is VpnStatus.Connected && it.node != null }) 5_000L else 1_500L)
        }
    }

    /** Fetches stale lists through Mihomo's own proxy, so blocked mirrors still work. */
    private suspend fun refreshSubscriptions(repo: SubscriptionRepository, api: CoreApi) {
        delay(15_000L) // let the first node selection settle
        for (source in repo.staleSources()) {
            if (repo.download(source, proxyPort = ConfigBuilder.MIXED_PORT) ||
                repo.download(source, proxyPort = null)
            ) {
                try {
                    api.reloadProvider(source.id)
                } catch (e: Exception) {
                    Log.w(TAG, "reload ${source.id}: $e")
                }
            }
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(title: String, text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ButoneVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .addAction(0, "Отключить", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    companion object {
        private const val TAG = "ButoneVpn"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "app.butone.vpn.START"
        const val ACTION_STOP = "app.butone.vpn.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ButoneVpnService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ButoneVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}

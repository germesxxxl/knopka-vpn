package app.butone.vpn.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.butone.vpn.App
import app.butone.vpn.R
import app.butone.vpn.data.Source
import app.butone.vpn.data.SubscriptionRepository
import app.butone.vpn.ui.MainActivity
import core.Core
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class ButoneVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var tun: ParcelFileDescriptor? = null
    private var monitorJob: Job? = null
    private var refreshJob: Job? = null
    private var recoveryJob: Job? = null
    private var recoveryAttempts = 0

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
        monitorJob?.cancel()
        refreshJob?.cancel()
        scope.cancel()
        if (Core.isRunning()) Core.stop()
        tun?.close()
        tun = null
        VpnState.apiSecret = null
        if (VpnState.status.value !is VpnStatus.Error) VpnState.set(VpnStatus.Disconnected)
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

            launchCoreLocked(repo, sources)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            stopVpnLocked(VpnStatus.Error(e.message ?: e.toString()))
        }
    }

    private fun stopVpnLocked(finalStatus: VpnStatus = VpnStatus.Disconnected) {
        monitorJob?.cancel()
        refreshJob?.cancel()
        recoveryJob?.cancel()
        monitorJob = null
        refreshJob = null
        recoveryJob = null
        try {
            if (Core.isRunning()) Core.stop()
        } catch (e: Exception) {
            Log.w(TAG, "core stop", e)
        }
        tun?.close()
        tun = null
        VpnState.apiSecret = null
        VpnState.set(finalStatus)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun launchCoreLocked(repo: SubscriptionRepository, sources: List<Source>) {
        val pfd = tun ?: error("VPN interface is closed")
        val secret = UUID.randomUUID().toString()
        VpnState.apiSecret = secret
        // Go duplicates this descriptor only after validating the config;
        // sing-tun owns and closes that duplicate on shutdown.
        Core.start(repo.homeDir.absolutePath, ConfigBuilder.build(sources, secret), pfd.fd)

        val names = sources.map { it.id }.toSet()
        monitorJob = scope.launch { monitor(repo, CoreApi(secret), names) }
        refreshJob = scope.launch { refreshSubscriptions(repo, CoreApi(secret), names) }
    }

    /** Polls the core for the selected node and ping; updates UI state and notification. */
    private suspend fun monitor(
        repo: SubscriptionRepository,
        api: CoreApi,
        providers: Set<String>,
    ) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        var since: Long? = null
        var probedNode: String? = null
        var probeSucceeded = false
        var lastProbeAt = 0L
        var unavailableSince = SystemClock.elapsedRealtime()
        var pollFailures = 0
        var failedProbes = 0
        var lastTrafficTotal = 0L
        var lastTrafficMovedAt = 0L
        var healthCheckRequested = false

        while (currentCoroutineContext().isActive) {
            try {
                val s = api.status(providers)
                currentCoroutineContext().ensureActive()
                pollFailures = 0
                val now = SystemClock.elapsedRealtime()
                val trafficTotal = runCatching { api.trafficTotal() }.getOrDefault(lastTrafficTotal)
                if (trafficTotal > lastTrafficTotal) lastTrafficMovedAt = now
                lastTrafficTotal = maxOf(lastTrafficTotal, trafficTotal)
                val candidateReady = s.node != null && s.currentAlive && s.delayMs != null
                val probeInterval = if (probeSucceeded && probedNode == s.node) 60_000L else 10_000L
                if (candidateReady && (probedNode != s.node || now - lastProbeAt >= probeInterval)) {
                    probedNode = s.node
                    lastProbeAt = now
                    probeSucceeded = runCatching { api.probeSelected() > 0 }
                        .onFailure { Log.w(TAG, "outbound probe: $it") }
                        .getOrDefault(false)
                    if (probeSucceeded) failedProbes = 0 else failedProbes++
                    currentCoroutineContext().ensureActive()
                } else if (!candidateReady) {
                    probeSucceeded = false
                    probedNode = s.node
                }

                val pretty = s.node?.let { CoreApi.prettyName(it) }
                // A public node can fail our synthetic 204 request while real
                // application traffic is still flowing. Do not tear down a
                // working tunnel in that case.
                val trafficIsFlowing = now - lastTrafficMovedAt < TRAFFIC_GRACE_MS
                val ready = candidateReady && (probeSucceeded || trafficIsFlowing) && pretty != null
                if (ready) {
                    if (since == null) since = System.currentTimeMillis()
                    unavailableSince = 0L
                    healthCheckRequested = false
                    if (probeSucceeded) recoveryAttempts = 0
                } else {
                    if (unavailableSince == 0L) unavailableSince = now
                }
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
                if (!ready && !healthCheckRequested && failedProbes >= 2) {
                    healthCheckRequested = true
                    scope.launch {
                        providers.forEach { provider ->
                            runCatching { api.healthCheckProvider(provider) }
                                .onFailure { Log.w(TAG, "health check $provider: $it") }
                        }
                    }
                }
                if (!ready && now - unavailableSince >= RECOVERY_TIMEOUT_MS) {
                    requestRecovery(repo)
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pollFailures++
                Log.w(TAG, "status poll: $e")
                val now = SystemClock.elapsedRealtime()
                if (unavailableSince == 0L) unavailableSince = now
                if (pollFailures >= 2) {
                    since = null
                    probeSucceeded = false
                    VpnState.set(VpnStatus.Connecting)
                    nm.notify(
                        NOTIFICATION_ID,
                        notification(getString(R.string.app_name), "Проверяем соединение…"),
                    )
                }
                if (now - unavailableSince >= RECOVERY_TIMEOUT_MS) {
                    requestRecovery(repo)
                    return
                }
            }
            delay(if (VpnState.status.value.let { it is VpnStatus.Connected && it.node != null }) 5_000L else 1_500L)
        }
    }

    /** Restarts only Mihomo while retaining Android's VPN permission and TUN. */
    private fun requestRecovery(repo: SubscriptionRepository) {
        if (recoveryJob?.isActive == true || tun == null) return
        recoveryJob = scope.launch {
            val attempt = ++recoveryAttempts
            val pause = when (attempt) {
                1 -> 1_000L
                2 -> 5_000L
                3 -> 15_000L
                else -> 30_000L
            }
            lock.withLock {
                if (tun == null) return@withLock
                monitorJob?.cancel()
                refreshJob?.cancel()
                monitorJob = null
                refreshJob = null
                runCatching { if (Core.isRunning()) Core.stop() }
                    .onFailure { Log.w(TAG, "core stop before recovery", it) }
                VpnState.apiSecret = null
                VpnState.set(VpnStatus.Connecting)
                getSystemService(android.app.NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(getString(R.string.app_name), "Переподключение…"),
                )
            }

            delay(pause)
            var retry = false
            lock.withLock {
                if (tun == null) return@withLock
                try {
                    val sources = repo.prepareOffline()
                    if (sources.isEmpty()) error("Нет доступных списков серверов")
                    launchCoreLocked(repo, sources)
                    Log.i(TAG, "automatic recovery attempt $attempt started")
                } catch (e: Exception) {
                    Log.e(TAG, "automatic recovery attempt $attempt failed", e)
                    retry = true
                }
            }
            recoveryJob = null
            if (retry && tun != null) requestRecovery(repo)
        }
    }

    /** Fetches stale lists through Mihomo's own proxy, so blocked mirrors still work. */
    private suspend fun refreshSubscriptions(
        repo: SubscriptionRepository,
        api: CoreApi,
        activeProviders: Set<String>,
    ) {
        delay(15_000L) // let the first node selection settle
        while (currentCoroutineContext().isActive) {
            val proxyReady = runCatching { api.probeSelected() > 0 }.getOrDefault(false)
            if (proxyReady) {
                if (!repo.refreshCatalog(ConfigBuilder.MIXED_PORT)) repo.refreshCatalog(null)
            } else {
                if (!repo.refreshCatalog(null)) repo.refreshCatalog(ConfigBuilder.MIXED_PORT)
            }
            for (source in repo.staleSources()) {
                currentCoroutineContext().ensureActive()
                val proxyReady = runCatching { api.probeSelected() > 0 }.getOrDefault(false)
                val update = if (proxyReady) {
                    repo.download(source, proxyPort = ConfigBuilder.MIXED_PORT)
                        ?: repo.download(source, proxyPort = null)
                } else {
                    repo.download(source, proxyPort = null)
                        ?: repo.download(source, proxyPort = ConfigBuilder.MIXED_PORT)
                } ?: continue

                currentCoroutineContext().ensureActive()
                val installed = try {
                    repo.install(update)
                } catch (e: Exception) {
                    repo.discard(update)
                    Log.w(TAG, "install ${source.id}: $e")
                    continue
                }

                try {
                    if (source.id in activeProviders) api.reloadProvider(source.id)
                    currentCoroutineContext().ensureActive()
                    repo.commit(installed)
                    Log.i(TAG, "updated provider ${source.id}")
                } catch (e: CancellationException) {
                    repo.rollback(installed)
                    runCatching { api.reloadProvider(source.id) }
                    throw e
                } catch (e: Exception) {
                    repo.rollback(installed)
                    runCatching { api.reloadProvider(source.id) }
                    Log.w(TAG, "reload ${source.id}: $e")
                }
            }
            delay(REFRESH_RETRY_MS)
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
        private const val RECOVERY_TIMEOUT_MS = 35_000L
        private const val TRAFFIC_GRACE_MS = 20_000L
        private const val REFRESH_RETRY_MS = 30 * 60 * 1000L
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

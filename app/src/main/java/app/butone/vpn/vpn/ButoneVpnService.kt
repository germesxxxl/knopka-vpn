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
import app.butone.vpn.BuildConfig
import app.butone.vpn.R
import app.butone.vpn.data.Source
import app.butone.vpn.data.SubscriptionRepository
import app.butone.vpn.diagnostics.EventJournal
import app.butone.vpn.ui.MainActivity
import app.butone.vpn.ui.TvActivity
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
    /** Provider reloads and node selection must never race each other. */
    private val providerMutationLock = Mutex()
    private var tun: ParcelFileDescriptor? = null
    private var monitorJob: Job? = null
    private var refreshJob: Job? = null
    private var recoveryJob: Job? = null
    private var recoveryAttempts = 0
    private val journal by lazy { EventJournal(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            journal.record("vpn_stop", "Отключение запрошено пользователем")
            scope.launch { lock.withLock { stopVpnLocked() } }
            return START_NOT_STICKY
        }
        // ACTION_START, or the system starting us for always-on VPN.
        startForegroundCompat(notification(getString(R.string.app_name), "Подключение…"))
        journal.record("vpn_start", "Запуск VPN")
        scope.launch { lock.withLock { startVpnLocked() } }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over, or the user revoked permission in settings.
        journal.record("vpn_revoke", "Android отозвал разрешение VPN")
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
        val startedAt = System.currentTimeMillis()
        VpnState.set(
            VpnStatus.Connecting(
                ConnectionProgress(
                    stage = ConnectionStage.PREPARING,
                    detail = "Подготавливаем защищённое соединение",
                    startedAt = startedAt,
                )
            )
        )
        try {
            VpnState.set(
                VpnStatus.Connecting(
                    ConnectionProgress(
                        stage = ConnectionStage.LOADING_SERVERS,
                        detail = "Загружаем сохранённые списки серверов",
                        startedAt = startedAt,
                    )
                )
            )
            val repo = SubscriptionRepository(this)
            val sources = repo.prepareOffline()
            if (sources.isEmpty()) error("Нет списка серверов. Проверьте интернет и попробуйте ещё раз.")

            val pfd = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(1400)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("172.19.0.2")
                // Mihomo runs in this process: exclude ourselves so its sockets bypass the tunnel.
                .addDisallowedApplication(packageName)
                .setConfigureIntent(openAppIntent())
                .apply { if (Build.VERSION.SDK_INT >= 29) setMetered(false) }
                .establish()
                ?: error("Нет разрешения на VPN")
            tun = pfd

            VpnState.set(
                VpnStatus.Connecting(
                    ConnectionProgress(
                        stage = ConnectionStage.STARTING_CORE,
                        detail = "Запускаем защищённый туннель",
                        startedAt = startedAt,
                    )
                )
            )
            launchCoreLocked(repo, sources)
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            journal.record("vpn_error", "Не удалось запустить VPN", mapOf("error" to e.toString()))
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
        journal.record("vpn_stopped", "VPN остановлен", mapOf("status" to finalStatus.toString()))
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
        journal.record(
            "core_start",
            "VPN-ядро запущено",
            mapOf("core" to runCatching { Core.version() }.getOrDefault("?"), "providers" to sources.joinToString { it.id }),
        )

        val names = sources.map { it.id }.toSet()
        monitorJob = scope.launch { monitor(repo, CoreApi(secret), names) }
        refreshJob = scope.launch { refreshSubscriptions(repo, CoreApi(secret), names) }
    }

    /**
     * Selects and periodically re-validates a node using real service traffic.
     * Latency is only a shortlist signal; it can never mark the VPN ready alone.
     */
    private suspend fun monitor(
        repo: SubscriptionRepository,
        api: CoreApi,
        providers: Set<String>,
    ) {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        val reputation = NodeReputation(this)
        val checker = ServiceHealthChecker()
        var since: Long? = null
        var selected: Selection? = null
        var lastVerifiedAt = 0L
        var lastQuickProbeAt = 0L
        var quickProbeFailures = 0
        var unavailableSince = SystemClock.elapsedRealtime()
        var pollFailures = 0
        var failedSelectionRounds = 0
        var healthCheckRequested = false
        var network = EventJournal.networkKind(this)
        var searchStartedAt = (VpnState.status.value as? VpnStatus.Connecting)
            ?.progress?.startedAt ?: System.currentTimeMillis()
        var lastProgress = ConnectionProgress(
            stage = ConnectionStage.CHECKING_SERVERS,
            detail = "Оцениваем доступные серверы",
            startedAt = searchStartedAt,
        )

        while (currentCoroutineContext().isActive) {
            try {
                val currentNetwork = EventJournal.networkKind(this)
                if (currentNetwork != network) {
                    journal.record("network_change", "Сеть изменилась", mapOf("from" to network, "to" to currentNetwork))
                    network = currentNetwork
                    selected = null
                    lastVerifiedAt = 0L
                    lastQuickProbeAt = 0L
                    quickProbeFailures = 0
                    searchStartedAt = System.currentTimeMillis()
                }
                val s = api.status(providers)
                currentCoroutineContext().ensureActive()
                pollFailures = 0
                val now = SystemClock.elapsedRealtime()
                val stillSelected = selected?.candidate?.name == s.node && s.currentAlive
                if (stillSelected && now - lastQuickProbeAt >= QUICK_PROBE_INTERVAL_MS) {
                    lastQuickProbeAt = now
                    val quickOk = runCatching { api.probeSelected() > 0 }
                        .onFailure { Log.w(TAG, "quick outbound probe: $it") }
                        .getOrDefault(false)
                    if (quickOk) {
                        quickProbeFailures = 0
                    } else {
                        quickProbeFailures++
                        journal.record(
                            if (quickProbeFailures >= QUICK_FAILURES_BEFORE_RECHECK) "quick_probe_failed" else "quick_probe_warning",
                            if (quickProbeFailures >= QUICK_FAILURES_BEFORE_RECHECK)
                                "Две быстрые проверки не прошли; запускаем полную проверку"
                            else
                                "Быстрая проверка не прошла; ждём подтверждение",
                            mapOf(
                                "node" to selected?.candidate?.name?.let { CoreApi.prettyName(it) },
                                "attempt" to quickProbeFailures,
                            ),
                        )
                    }
                }
                val needsCheck = selected == null || !stillSelected ||
                    now - lastVerifiedAt >= SERVICE_RECHECK_MS ||
                    quickProbeFailures >= QUICK_FAILURES_BEFORE_RECHECK
                if (needsCheck) {
                    val preferred = selected?.candidate?.name
                    if (selected == null || !stillSelected) {
                        selected = null
                        if (lastVerifiedAt > 0L) searchStartedAt = System.currentTimeMillis()
                    }
                    lastProgress = ConnectionProgress(
                        stage = ConnectionStage.CHECKING_SERVERS,
                        detail = "Найдено ${s.alive} отвечающих серверов",
                        startedAt = searchStartedAt,
                        alive = s.alive,
                        total = s.total,
                    )
                    fun publishProgress(progress: ConnectionProgress) {
                        lastProgress = progress
                        val current = selected
                        VpnState.set(
                            if (current == null) VpnStatus.Connecting(progress)
                            else connectedStatus(current, s.alive, s.total, since, progress)
                        )
                    }
                    publishProgress(lastProgress)
                    selected = providerMutationLock.withLock {
                        findWorkingNode(
                            api = api,
                            providers = providers,
                            reputation = reputation,
                            checker = checker,
                            preferred = preferred,
                            startedAt = searchStartedAt,
                            alive = s.alive,
                            total = s.total,
                            onProgress = ::publishProgress,
                        )
                    }
                    lastVerifiedAt = SystemClock.elapsedRealtime()
                    lastQuickProbeAt = lastVerifiedAt
                    quickProbeFailures = 0
                    if (selected == null) failedSelectionRounds++ else failedSelectionRounds = 0
                }

                val ready = selected != null
                if (ready && since == null) since = System.currentTimeMillis()
                if (ready) {
                    if (unavailableSince != 0L) {
                        journal.record(
                            "vpn_ready",
                            "Найден рабочий сервер",
                            mapOf("node" to CoreApi.prettyName(selected!!.candidate.name), "pool" to poolName(selected!!.candidate.pool)),
                        )
                    }
                    unavailableSince = 0L
                    healthCheckRequested = false
                    recoveryAttempts = 0
                    failedSelectionRounds = 0
                } else if (unavailableSince == 0L) {
                    unavailableSince = now
                }

                val chosen = selected
                val pretty = chosen?.candidate?.name?.let { CoreApi.prettyName(it) }
                VpnState.set(
                    if (chosen == null) VpnStatus.Connecting(lastProgress)
                    else connectedStatus(chosen, s.alive, s.total, since)
                )
                val text = if (ready) "Подключено · $pretty · ${chosen?.candidate?.delayMs} мс" else "Подбираем лучший сервер…"
                nm.notify(NOTIFICATION_ID, notification(getString(R.string.app_name), text))
                if (!ready && !healthCheckRequested) {
                    healthCheckRequested = true
                    scope.launch {
                        providerMutationLock.withLock {
                            providers.forEach { provider ->
                                runCatching { api.healthCheckProvider(provider) }
                                    .onFailure { Log.w(TAG, "health check $provider: $it") }
                            }
                        }
                    }
                }
                if (!ready && failedSelectionRounds >= 2 && now - unavailableSince >= RECOVERY_TIMEOUT_MS) {
                    journal.record("recovery", "Перезапуск ядра: рабочий сервер не найден")
                    requestRecovery(repo)
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pollFailures++
                Log.w(TAG, "status poll: $e")
                journal.record("monitor_error", "Ошибка контроля соединения", mapOf("error" to e.toString()))
                val now = SystemClock.elapsedRealtime()
                if (unavailableSince == 0L) unavailableSince = now
                if (pollFailures >= 2) {
                    since = null
                    selected = null
                    searchStartedAt = System.currentTimeMillis()
                    lastProgress = ConnectionProgress(
                        stage = ConnectionStage.CHECKING_SERVERS,
                        detail = "Проверяем состояние туннеля",
                        startedAt = searchStartedAt,
                    )
                    VpnState.set(VpnStatus.Connecting(lastProgress))
                    nm.notify(
                        NOTIFICATION_ID,
                        notification(getString(R.string.app_name), "Проверяем соединение…"),
                    )
                }
                if (pollFailures >= 2 && now - unavailableSince >= RECOVERY_TIMEOUT_MS) {
                    requestRecovery(repo)
                    return
                }
            }
            delay(if (VpnState.status.value.let { it is VpnStatus.Connected && it.node != null }) 5_000L else 1_500L)
        }
    }

    private data class Selection(
        val candidate: CoreApi.Candidate,
        val report: ServiceHealthChecker.Report,
    )

    private fun connectedStatus(
        chosen: Selection,
        alive: Int,
        total: Int,
        since: Long?,
        progress: ConnectionProgress? = null,
    ) = VpnStatus.Connected(
        node = CoreApi.prettyName(chosen.candidate.name),
        delayMs = chosen.candidate.delayMs,
        alive = alive,
        total = total,
        serviceOk = chosen.report.okCount,
        serviceTotal = chosen.report.total,
        services = chosen.report.results.map {
            ServiceAvailability(it.service, it.ok, it.latencyMs)
        },
        pool = poolName(chosen.candidate.pool),
        since = since,
        progress = progress,
    )

    private suspend fun findWorkingNode(
        api: CoreApi,
        providers: Set<String>,
        reputation: NodeReputation,
        checker: ServiceHealthChecker,
        preferred: String?,
        startedAt: Long,
        alive: Int,
        total: Int,
        onProgress: (ConnectionProgress) -> Unit,
    ): Selection? {
        val all = api.candidates(providers)
        val available = all.filterNot { reputation.isCoolingDown(it.id) }
        val ordered = available.sortedWith(
            compareByDescending<CoreApi.Candidate> { it.name == preferred }
                .thenBy { it.pool }
                .thenBy { it.delayMs },
        )
        val candidates = ordered.take(MAX_SERVICE_CANDIDATES)
        for ((index, candidate) in candidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            val pretty = CoreApi.prettyName(candidate.name)
            onProgress(
                ConnectionProgress(
                    stage = ConnectionStage.CHECKING_SERVERS,
                    detail = "Выбираем сервер ${index + 1} из ${candidates.size}",
                    startedAt = startedAt,
                    candidateIndex = index + 1,
                    candidateTotal = candidates.size,
                    alive = alive,
                    total = total,
                    node = pretty,
                )
            )
            try {
                api.selectNode(candidate.name)
            } catch (e: Exception) {
                journal.record("node_select_error", "Не удалось выбрать сервер", mapOf("node" to CoreApi.prettyName(candidate.name), "error" to e.toString()))
                continue
            }
            delay(350L)
            val report = runCatching {
                checker.check { completed ->
                    onProgress(
                        ConnectionProgress(
                            stage = ConnectionStage.CHECKING_SERVICES,
                            detail = "Проверяем сервисы через $pretty",
                            startedAt = startedAt,
                            candidateIndex = index + 1,
                            candidateTotal = candidates.size,
                            alive = alive,
                            total = total,
                            node = pretty,
                            services = completed.map {
                                ServiceAvailability(it.service, it.ok, it.latencyMs)
                            },
                        )
                    )
                }
            }
                .onFailure { Log.w(TAG, "service check ${candidate.name}: $it") }
                .getOrNull()
                ?: ServiceHealthChecker.Report(emptyList())
            journal.record(
                "service_probe",
                if (report.usable) "Сервер прошёл проверку сервисов" else "Сервер не прошёл проверку сервисов",
                mapOf(
                    "node" to CoreApi.prettyName(candidate.name),
                    "provider" to candidate.provider,
                    "pool" to poolName(candidate.pool),
                    "latency" to candidate.delayMs,
                    "result" to report.compact(),
                ),
            )
            if (report.usable) {
                reputation.recordSuccess(candidate.id)
                return Selection(candidate, report)
            }
            val cooldown = reputation.recordFailure(candidate.id)
            journal.record(
                "node_cooldown",
                "Нерабочий сервер временно исключён",
                mapOf("node" to CoreApi.prettyName(candidate.name), "minutes" to cooldown / 60_000),
            )
            onProgress(
                ConnectionProgress(
                    stage = ConnectionStage.TRYING_NEXT,
                    detail = if (index + 1 < candidates.size)
                        "$pretty не подошёл — пробуем следующий"
                    else
                        "Подходящий сервер пока не найден",
                    startedAt = startedAt,
                    candidateIndex = index + 1,
                    candidateTotal = candidates.size,
                    alive = alive,
                    total = total,
                    node = pretty,
                    services = report.results.map {
                        ServiceAvailability(it.service, it.ok, it.latencyMs)
                    },
                )
            )
        }
        return null
    }

    private fun poolName(pool: Int): String = when (pool) {
        0 -> "обычный"
        1 -> "ограниченная сеть"
        else -> "резервный"
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
                VpnState.set(
                    VpnStatus.Connecting(
                        ConnectionProgress(
                            stage = ConnectionStage.RECOVERING,
                            detail = "Восстанавливаем соединение автоматически",
                        )
                    )
                )
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
                    journal.record("recovery_started", "Автоматическое переподключение запущено", mapOf("attempt" to attempt))
                } catch (e: Exception) {
                    Log.e(TAG, "automatic recovery attempt $attempt failed", e)
                    journal.record("recovery_error", "Автоматическое переподключение не удалось", mapOf("attempt" to attempt, "error" to e.toString()))
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
                    if (source.id in activeProviders) {
                        providerMutationLock.withLock { api.reloadProvider(source.id) }
                    }
                    currentCoroutineContext().ensureActive()
                    repo.commit(installed)
                    Log.i(TAG, "updated provider ${source.id}")
                    journal.record("source_update", "Список серверов обновлён", mapOf("provider" to source.id))
                } catch (e: CancellationException) {
                    repo.rollback(installed)
                    runCatching { providerMutationLock.withLock { api.reloadProvider(source.id) } }
                    throw e
                } catch (e: Exception) {
                    repo.rollback(installed)
                    runCatching { providerMutationLock.withLock { api.reloadProvider(source.id) } }
                    Log.w(TAG, "reload ${source.id}: $e")
                    journal.record("source_update_error", "Не удалось применить список серверов", mapOf("provider" to source.id, "error" to e.toString()))
                }
            }
            delay(REFRESH_RETRY_MS)
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, if (BuildConfig.IS_TV) TvActivity::class.java else MainActivity::class.java),
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
        private const val RECOVERY_TIMEOUT_MS = 3 * 60_000L
        private const val SERVICE_RECHECK_MS = 10 * 60_000L
        private const val QUICK_PROBE_INTERVAL_MS = 60_000L
        private const val QUICK_FAILURES_BEFORE_RECHECK = 2
        private const val MAX_SERVICE_CANDIDATES = 8
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

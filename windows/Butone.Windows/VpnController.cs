using System.Diagnostics;
using System.Net;
using System.Text;

namespace Butone.Windows;

internal sealed class VpnController : IAsyncDisposable
{
    private sealed record Selection(Candidate Candidate, IReadOnlyList<ServiceResult> Services);
    private sealed record Standby(Candidate Candidate, DateTimeOffset CheckedAt);
    private sealed record SourceDefinition(string Id, string[] Urls);

    private static readonly SourceDefinition[] Sources =
    [
        new("ru_mobile", Mirrors("igareck/vpn-configs-for-russia", "Export/Clash/RU_DIRECT/BLACK_VLESS_RUS_mobile_clash_rudirect.yaml")),
        new("ru_vless_full", Mirrors("igareck/vpn-configs-for-russia", "Export/Clash/RU_DIRECT/BLACK_VLESS_RUS_clash_rudirect.yaml")),
        new("ru_other", Mirrors("igareck/vpn-configs-for-russia", "Export/Clash/RU_DIRECT/BLACK_SS%2BAll_RUS_clash_rudirect.yaml")),
        new("ru_white", Mirrors("igareck/vpn-configs-for-russia", "Export/Clash/GLOBAL/WHITE-CIDR-RU-checked-clash-global.yaml")),
        new("global_reserve", Mirrors("0xRadikal/Free-v2ray-Configs", "fast/clash.yaml")),
    ];

    private readonly string _baseDirectory = AppContext.BaseDirectory;
    private readonly string _workDirectory;
    private readonly string _providerDirectory;
    private readonly string _ruleDirectory;
    private readonly EventJournal _journal;
    private readonly ServiceChecker _checker = new();
    private readonly SemaphoreSlim _lifecycle = new(1, 1);
    private readonly SemaphoreSlim _mutation = new(1, 1);
    private readonly object _logSync = new();
    private readonly Dictionary<string, (int Failures, int Penalty, DateTimeOffset Until)> _reputation = new();

    private CancellationTokenSource? _session;
    private Process? _core;
    private ProcessJob? _job;
    private CoreApi? _api;
    private Task? _monitorTask;
    private Task? _standbyTask;
    private Task? _refreshTask;
    private Selection? _selection;
    private Standby[] _standbys = [];
    private bool _standbyScanActive;
    private DateTimeOffset _lastStandbyScan;
    private DateTimeOffset? _connectedAt;
    private VpnSnapshot _state = VpnSnapshot.Off;

    public VpnController()
    {
        _workDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "butone-vpn");
        _providerDirectory = Path.Combine(_workDirectory, "providers");
        _ruleDirectory = Path.Combine(_workDirectory, "rules");
        _journal = new EventJournal(Path.Combine(_workDirectory, "logs"));
    }

    public event Action<VpnSnapshot>? StateChanged;
    public VpnSnapshot State => _state;
    public string ReportPath => _journal.Path;

    public async Task StartAsync()
    {
        await _lifecycle.WaitAsync();
        try
        {
            if (_session is not null) return;
            Publish(new VpnSnapshot(VpnPhase.Connecting, "Подготавливаем VPN", "Копируем списки серверов"));
            PrepareFiles();
            _session = new CancellationTokenSource();
            var cancellationToken = _session.Token;
            _journal.Record("vpn_start", "Запуск Windows VPN");

            await LaunchCoreAsync(cancellationToken);
            Publish(new VpnSnapshot(VpnPhase.Connecting, "Проверяем серверы", "Измеряем доступность из вашей сети"));
            await RefreshHealthAsync(includeReserve: false, cancellationToken);

            var selection = await FindWorkingNodeAsync([], cancellationToken);
            if (selection is null)
            {
                Publish(new VpnSnapshot(VpnPhase.Connecting, "Проверяем резерв", "Основной пул не подошёл"));
                await RefreshHealthAsync(includeReserve: true, cancellationToken);
                selection = await FindWorkingNodeAsync([], cancellationToken);
            }
            if (selection is null) throw new InvalidOperationException("Рабочий сервер пока не найден. Попробуйте ещё раз через несколько минут.");

            _selection = selection;
            _connectedAt = DateTimeOffset.Now;
            PublishConnected(selection);
            _journal.Record("vpn_ready", "Найден рабочий сервер", new
            {
                node = CoreApi.PrettyName(selection.Candidate.Name),
                selection.Candidate.Provider,
                selection.Candidate.DelayMs,
            });

            _monitorTask = Task.Run(() => MonitorAsync(cancellationToken), cancellationToken);
            _refreshTask = Task.Run(() => RefreshSourcesAsync(cancellationToken), cancellationToken);
        }
        catch (OperationCanceledException) when (_session?.IsCancellationRequested == true)
        {
            await StopInternalAsync();
            Publish(VpnSnapshot.Off);
        }
        catch (Exception error)
        {
            _journal.Record("vpn_error", "Не удалось запустить VPN", new { error = error.ToString() });
            await StopInternalAsync();
            Publish(new VpnSnapshot(VpnPhase.Error, "Не удалось подключиться", error.Message));
        }
        finally
        {
            _lifecycle.Release();
        }
    }

    public async Task StopAsync()
    {
        // Cancellation must be signalled before waiting for StartAsync's
        // lifecycle lock; otherwise a long server check could not be stopped.
        _session?.Cancel();
        await _lifecycle.WaitAsync();
        try
        {
            if (_session is null)
            {
                Publish(VpnSnapshot.Off);
                return;
            }
            Publish(new VpnSnapshot(VpnPhase.Reconnecting, "Отключаем VPN", "Восстанавливаем обычные маршруты Windows"));
            _journal.Record("vpn_stop", "Отключение запрошено пользователем");
            await StopInternalAsync();
            Publish(VpnSnapshot.Off);
        }
        finally
        {
            _lifecycle.Release();
        }
    }

    private async Task LaunchCoreAsync(CancellationToken cancellationToken)
    {
        var executable = Path.Combine(_baseDirectory, "mihomo.exe");
        if (!File.Exists(executable))
            throw new FileNotFoundException("Рядом с приложением отсутствует mihomo.exe. Распакуйте архив полностью.", executable);

        var secret = Guid.NewGuid().ToString("N");
        var configPath = Path.Combine(_workDirectory, "config.yaml");
        await File.WriteAllTextAsync(configPath, ConfigBuilder.Build(secret), new UTF8Encoding(false), cancellationToken);
        var logPath = Path.Combine(_workDirectory, "logs", "mihomo.log");

        _core = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = executable,
                Arguments = $"-d \"{_workDirectory}\" -f \"{configPath}\"",
                WorkingDirectory = _workDirectory,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            },
            EnableRaisingEvents = true,
        };
        _core.OutputDataReceived += (_, e) => AppendCoreLog(logPath, e.Data);
        _core.ErrorDataReceived += (_, e) => AppendCoreLog(logPath, e.Data);
        if (!_core.Start()) throw new InvalidOperationException("Не удалось запустить VPN-ядро");
        _core.BeginOutputReadLine();
        _core.BeginErrorReadLine();

        try
        {
            _job = new ProcessJob();
            _job.Add(_core);
        }
        catch (Exception error)
        {
            _job?.Dispose();
            _job = null;
            _journal.Record("job_warning", "Не удалось привязать ядро к процессу оболочки", new { error = error.Message });
        }

        _api = new CoreApi(secret);
        await _api.WaitUntilReadyAsync(() => _core?.HasExited != false, cancellationToken);
        _journal.Record("core_start", "Mihomo запущен", new { pid = _core.Id });
    }

    private async Task RefreshHealthAsync(bool includeReserve, CancellationToken cancellationToken)
    {
        var api = _api ?? throw new InvalidOperationException("Core API is unavailable");
        var providers = includeReserve
            ? ConfigBuilder.ProviderNames
            : ConfigBuilder.ProviderNames.Where(id => id is "ru_mobile" or "ru_other" or "ru_white").ToArray();
        var tasks = providers.Select(async provider =>
        {
            try
            {
                await api.HealthCheckProviderAsync(provider, cancellationToken);
            }
            catch (Exception error) when (error is not OperationCanceledException)
            {
                _journal.Record("provider_check_error", "Ошибка проверки списка", new { provider, error = error.Message });
            }
        });
        await Task.WhenAll(tasks);
    }

    private async Task<Selection?> FindWorkingNodeAsync(IReadOnlyList<string> preferredIds, CancellationToken cancellationToken)
    {
        var api = _api;
        if (api is null) return null;
        await _mutation.WaitAsync(cancellationToken);
        try
        {
            var preferred = preferredIds.Select((id, index) => (id, index)).ToDictionary(item => item.id, item => item.index);
            var candidates = (await api.CandidatesAsync(cancellationToken))
                .Where(candidate => !IsCoolingDown(candidate.Id))
                .OrderBy(candidate => preferred.TryGetValue(candidate.Id, out var rank) ? rank : int.MaxValue)
                .ThenBy(candidate => Penalty(candidate.Id))
                .ThenBy(candidate => candidate.Pool)
                .ThenBy(candidate => candidate.DelayMs)
                .Take(10)
                .ToArray();

            for (var index = 0; index < candidates.Length; index++)
            {
                cancellationToken.ThrowIfCancellationRequested();
                var candidate = candidates[index];
                var pretty = CoreApi.PrettyName(candidate.Name);
                Publish(new VpnSnapshot(
                    VpnPhase.Connecting,
                    "Выбираем сервер",
                    $"Сервер {index + 1} из {candidates.Length}: {pretty}",
                    pretty,
                    candidate.DelayMs));
                try
                {
                    await api.SelectNodeAsync(candidate.Name, cancellationToken);
                    await Task.Delay(350, cancellationToken);
                }
                catch (Exception error) when (error is not OperationCanceledException)
                {
                    _journal.Record("node_select_error", "Не удалось выбрать сервер", new { node = pretty, error = error.Message });
                    continue;
                }

                IReadOnlyList<ServiceResult> services = [];
                services = await _checker.CheckAsync(completed =>
                {
                    Publish(new VpnSnapshot(
                        VpnPhase.Connecting,
                        "Проверяем приложения",
                        $"{pretty}: готово {completed.Count} из 4",
                        pretty,
                        candidate.DelayMs,
                        completed));
                }, cancellationToken);
                var usable = services.Count(result => result.Available) >= 3;
                _journal.Record("service_probe", usable ? "Сервер прошёл проверку" : "Сервер не прошёл проверку", new
                {
                    node = pretty,
                    candidate.Provider,
                    candidate.DelayMs,
                    services,
                });
                if (usable)
                {
                    RecordSuccess(candidate.Id);
                    return new Selection(candidate, services);
                }
                RecordFailure(candidate.Id);
            }
            return null;
        }
        finally
        {
            _mutation.Release();
        }
    }

    private async Task MonitorAsync(CancellationToken cancellationToken)
    {
        var watchdog = new TrafficWatchdog();
        var lastProbe = DateTimeOffset.MinValue;
        var probeFailures = 0;
        var trafficSuspicion = false;

        try
        {
            while (!cancellationToken.IsCancellationRequested)
            {
                await Task.Delay(TimeSpan.FromSeconds(5), cancellationToken);
                if (_core?.HasExited != false) throw new InvalidOperationException("VPN-ядро неожиданно завершилось");
                var api = _api ?? throw new InvalidOperationException("Core API is unavailable");
                var current = _selection ?? throw new InvalidOperationException("Рабочий сервер не выбран");
                var now = DateTimeOffset.Now;

                try
                {
                    var traffic = await api.TrafficAsync(current.Candidate.Name, cancellationToken);
                    var evaluation = watchdog.Observe(traffic, now, _standbyScanActive);
                    if (evaluation.Suspect)
                    {
                        trafficSuspicion = true;
                        _journal.Record("traffic_stall_suspected", "Обнаружен исходящий трафик без ответа", new
                        {
                            node = CoreApi.PrettyName(current.Candidate.Name),
                            evaluation.Reason,
                            traffic.ActiveProxied,
                            traffic.UploadOnlyProxied,
                            traffic.StalledDnsConnections,
                        });
                    }
                }
                catch (Exception error) when (error is not OperationCanceledException)
                {
                    _journal.Record("traffic_read_error", "Не удалось прочитать счётчики трафика", new { error = error.Message });
                }

                var interval = trafficSuspicion
                    ? TimeSpan.FromSeconds(10)
                    : probeFailures > 0 ? TimeSpan.FromSeconds(30) : TimeSpan.FromMinutes(5);
                if (now - lastProbe >= interval)
                {
                    lastProbe = now;
                    var ok = await api.ProbeSelectedQuorumAsync(2, cancellationToken);
                    if (ok)
                    {
                        probeFailures = 0;
                        trafficSuspicion = false;
                        watchdog.Reset();
                    }
                    else
                    {
                        probeFailures++;
                        _journal.Record("quick_probe_warning", "Независимая проверка сервера не прошла", new
                        {
                            node = CoreApi.PrettyName(current.Candidate.Name),
                            attempt = probeFailures,
                            trafficSuspicion,
                        });
                    }
                }

                var required = trafficSuspicion ? 2 : 3;
                if (probeFailures >= required)
                {
                    await SwitchFromDeadNodeAsync(current, cancellationToken);
                    probeFailures = 0;
                    trafficSuspicion = false;
                    lastProbe = DateTimeOffset.Now;
                    watchdog.Reset();
                }

                if ((_standbyTask is null || _standbyTask.IsCompleted) && now - _lastStandbyScan >= TimeSpan.FromMinutes(3))
                {
                    _lastStandbyScan = now;
                    _standbyTask = Task.Run(() => ScanStandbysAsync(cancellationToken), cancellationToken);
                }
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            // Normal disconnect.
        }
        catch (Exception error)
        {
            _journal.Record("monitor_error", "Контроль соединения остановлен", new { error = error.ToString() });
            _ = Task.Run(() => CleanupAfterMonitorFailureAsync(error.Message));
        }
    }

    private async Task CleanupAfterMonitorFailureAsync(string message)
    {
        await _lifecycle.WaitAsync();
        try
        {
            await StopInternalAsync();
            Publish(new VpnSnapshot(VpnPhase.Error, "Соединение потеряно", message + ". Нажмите кнопку, чтобы подключиться заново."));
        }
        finally
        {
            _lifecycle.Release();
        }
    }

    private async Task SwitchFromDeadNodeAsync(Selection failed, CancellationToken cancellationToken)
    {
        var api = _api;
        if (api is null) return;
        var failedName = CoreApi.PrettyName(failed.Candidate.Name);
        RecordFailure(failed.Candidate.Id);
        var closed = await api.CloseConnectionsThroughAsync(failed.Candidate.Name, cancellationToken);
        _journal.Record("traffic_stall_confirmed", "Сервер перестал передавать ответы", new { node = failedName, closed });
        Publish(new VpnSnapshot(VpnPhase.Reconnecting, "Переключаем сервер", $"{failedName} перестал отвечать"));

        var preferred = _standbys
            .Where(item => DateTimeOffset.Now - item.CheckedAt < TimeSpan.FromMinutes(5))
            .Select(item => item.Candidate.Id)
            .ToArray();
        var replacement = await FindWorkingNodeAsync(preferred, cancellationToken);
        if (replacement is null)
        {
            await RefreshHealthAsync(includeReserve: true, cancellationToken);
            replacement = await FindWorkingNodeAsync(preferred, cancellationToken);
        }
        if (replacement is null) throw new InvalidOperationException("Не удалось найти резервный сервер");

        _selection = replacement;
        PublishConnected(replacement);
        _journal.Record("failover_ready", "Выполнено переключение на резерв", new
        {
            from = failedName,
            to = CoreApi.PrettyName(replacement.Candidate.Name),
        });
    }

    private async Task ScanStandbysAsync(CancellationToken cancellationToken)
    {
        var api = _api;
        var current = _selection;
        if (api is null || current is null) return;
        _standbyScanActive = true;
        try
        {
            var candidates = (await api.CandidatesAsync(cancellationToken))
                .Where(candidate => candidate.Id != current.Candidate.Id && !IsCoolingDown(candidate.Id))
                .OrderBy(candidate => candidate.Provider == current.Candidate.Provider)
                .ThenBy(candidate => Penalty(candidate.Id))
                .ThenBy(candidate => candidate.Pool)
                .ThenBy(candidate => candidate.DelayMs)
                .Take(6)
                .ToArray();
            var verified = new List<Standby>();
            foreach (var candidate in candidates)
            {
                if (await api.ProbeCandidateAsync(candidate, 2, cancellationToken))
                {
                    verified.Add(new Standby(candidate, DateTimeOffset.Now));
                    if (verified.Count >= 2) break;
                }
            }
            _standbys = verified.ToArray();
            _journal.Record("standby_ready", "Резервные серверы проверены", new
            {
                count = verified.Count,
                nodes = verified.Select(item => CoreApi.PrettyName(item.Candidate.Name)).ToArray(),
            });
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
        catch (Exception error)
        {
            _journal.Record("standby_error", "Ошибка фоновой проверки резервов", new { error = error.Message });
        }
        finally
        {
            _standbyScanActive = false;
        }
    }

    private async Task RefreshSourcesAsync(CancellationToken cancellationToken)
    {
        var api = _api;
        if (api is null) return;
        try
        {
            using var handler = new HttpClientHandler
            {
                Proxy = new WebProxy($"http://127.0.0.1:{ConfigBuilder.MixedPort}"),
                UseProxy = true,
            };
            using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(30) };
            client.DefaultRequestHeaders.UserAgent.ParseAdd("clash.meta");
            foreach (var source in Sources)
            {
                cancellationToken.ThrowIfCancellationRequested();
                string? text = null;
                foreach (var url in source.Urls)
                {
                    try
                    {
                        text = await client.GetStringAsync(url, cancellationToken);
                        if (ValidProvider(text)) break;
                        text = null;
                    }
                    catch (Exception error) when (error is not OperationCanceledException)
                    {
                        _journal.Record("source_mirror_error", "Зеркало списка недоступно", new { source.Id, url, error = error.Message });
                    }
                }
                if (text is null) continue;

                await _mutation.WaitAsync(cancellationToken);
                try
                {
                    var target = Path.Combine(_providerDirectory, source.Id + ".yaml");
                    var candidate = target + ".download";
                    var backup = target + ".bak";
                    await File.WriteAllTextAsync(candidate, text, new UTF8Encoding(false), cancellationToken);
                    if (File.Exists(target)) File.Copy(target, backup, true);
                    File.Move(candidate, target, true);
                    try
                    {
                        await api.ReloadProviderAsync(source.Id, cancellationToken);
                        if (File.Exists(backup)) File.Delete(backup);
                        _journal.Record("source_updated", "Список серверов обновлён", new { source.Id });
                    }
                    catch
                    {
                        if (File.Exists(backup)) File.Move(backup, target, true);
                        await api.ReloadProviderAsync(source.Id, cancellationToken);
                        throw;
                    }
                }
                finally
                {
                    _mutation.Release();
                }
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
        }
        catch (Exception error)
        {
            _journal.Record("source_update_error", "Фоновое обновление списков остановлено", new { error = error.ToString() });
        }
    }

    private void PrepareFiles()
    {
        Directory.CreateDirectory(_workDirectory);
        Directory.CreateDirectory(_providerDirectory);
        Directory.CreateDirectory(_ruleDirectory);
        Directory.CreateDirectory(Path.Combine(_workDirectory, "logs"));
        foreach (var id in ConfigBuilder.ProviderNames)
        {
            var source = Path.Combine(_baseDirectory, "bundle", "providers", id + ".yaml");
            var target = Path.Combine(_providerDirectory, id + ".yaml");
            if (!File.Exists(source)) throw new FileNotFoundException($"В архиве отсутствует список {id}", source);
            if (!File.Exists(target) || !ValidProvider(File.ReadAllText(target))) File.Copy(source, target, true);
        }
        foreach (var name in new[] { "category-ru.mrs", "geoip-ru.mrs" })
        {
            var source = Path.Combine(_baseDirectory, "bundle", "rules", name);
            var target = Path.Combine(_ruleDirectory, name);
            if (!File.Exists(source)) throw new FileNotFoundException($"В архиве отсутствует правило {name}", source);
            File.Copy(source, target, true);
        }
    }

    private async Task StopInternalAsync()
    {
        var session = _session;
        _session = null;
        session?.Cancel();
        var monitor = _monitorTask;
        var standby = _standbyTask;
        var refresh = _refreshTask;
        _monitorTask = null;
        _standbyTask = null;
        _refreshTask = null;
        if (monitor is not null)
            await Task.WhenAny(monitor, Task.Delay(2_000));
        var background = new[] { standby, refresh }.Where(task => task is not null).Cast<Task>().ToArray();
        if (background.Length > 0)
            await Task.WhenAny(Task.WhenAll(background), Task.Delay(5_000));

        try
        {
            if (_core is { HasExited: false })
            {
                _core.Kill(entireProcessTree: true);
                await _core.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5));
            }
        }
        catch (Exception error)
        {
            _journal.Record("core_stop_error", "Ошибка остановки ядра", new { error = error.Message });
        }
        _api?.Dispose();
        _api = null;
        _core?.Dispose();
        _core = null;
        _job?.Dispose();
        _job = null;
        session?.Dispose();
        _selection = null;
        _standbys = [];
        _connectedAt = null;
        _standbyScanActive = false;
        _lastStandbyScan = default;
        _journal.Record("vpn_stopped", "VPN остановлен");
    }

    private void PublishConnected(Selection selection)
    {
        Publish(new VpnSnapshot(
            VpnPhase.Connected,
            "VPN подключён",
            "Весь внешний трафик защищён · российские сервисы идут напрямую",
            CoreApi.PrettyName(selection.Candidate.Name),
            selection.Candidate.DelayMs,
            selection.Services,
            _connectedAt));
    }

    private void Publish(VpnSnapshot state)
    {
        _state = state;
        StateChanged?.Invoke(state);
    }

    private void AppendCoreLog(string path, string? line)
    {
        if (string.IsNullOrWhiteSpace(line)) return;
        lock (_logSync) File.AppendAllText(path, $"{DateTimeOffset.Now:O} {line}{Environment.NewLine}");
    }

    private bool IsCoolingDown(string id) =>
        _reputation.TryGetValue(id, out var value) && value.Until > DateTimeOffset.Now;

    private int Penalty(string id) => _reputation.TryGetValue(id, out var value) ? value.Penalty : 0;

    private void RecordFailure(string id)
    {
        var old = _reputation.GetValueOrDefault(id);
        var failures = old.Failures + 1;
        var pause = failures switch
        {
            1 => TimeSpan.FromMinutes(2),
            2 => TimeSpan.FromMinutes(30),
            3 => TimeSpan.FromHours(6),
            _ => TimeSpan.FromHours(24),
        };
        _reputation[id] = (failures, Math.Min(20, old.Penalty + 2), DateTimeOffset.Now + pause);
    }

    private void RecordSuccess(string id)
    {
        var old = _reputation.GetValueOrDefault(id);
        _reputation[id] = (0, Math.Max(0, old.Penalty - 1), DateTimeOffset.MinValue);
    }

    private static bool ValidProvider(string text) =>
        text.Length is > 0 and <= 2 * 1024 * 1024 &&
        text.Contains("proxies:", StringComparison.Ordinal) &&
        text.Contains("name:", StringComparison.Ordinal) &&
        text.Contains("type:", StringComparison.Ordinal);

    private static string[] Mirrors(string repository, string path) =>
    [
        $"https://raw.githack.com/{repository}/main/{path}",
        $"https://cdn.jsdelivr.net/gh/{repository}@main/{path}",
        $"https://raw.githubusercontent.com/{repository}/main/{path}",
    ];

    public async ValueTask DisposeAsync()
    {
        await StopAsync();
        _lifecycle.Dispose();
        _mutation.Dispose();
    }
}

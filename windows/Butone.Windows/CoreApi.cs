using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;

namespace Butone.Windows;

internal sealed class CoreApi : IDisposable
{
    private static readonly string[] ProbeUrls =
    [
        "https://www.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
        "https://connectivitycheck.gstatic.com/generate_204",
    ];

    private static readonly HashSet<string> DnsHosts = new(StringComparer.OrdinalIgnoreCase)
    {
        "dns.google",
        "cloudflare-dns.com",
        "dns.quad9.net",
    };

    private readonly HttpClient _client;

    public CoreApi(string secret)
    {
        _client = new HttpClient
        {
            BaseAddress = new Uri($"http://127.0.0.1:{ConfigBuilder.ControllerPort}"),
            Timeout = TimeSpan.FromSeconds(20),
        };
        _client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", secret);
    }

    public async Task WaitUntilReadyAsync(Func<bool> processExited, CancellationToken cancellationToken)
    {
        Exception? last = null;
        for (var attempt = 0; attempt < 80; attempt++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (processExited()) throw new InvalidOperationException("VPN-ядро завершилось во время запуска");
            try
            {
                await GetTextAsync("/version", cancellationToken);
                return;
            }
            catch (Exception error)
            {
                last = error;
                await Task.Delay(250, cancellationToken);
            }
        }
        throw new InvalidOperationException("VPN-ядро не запустило локальное управление", last);
    }

    public async Task<IReadOnlyList<Candidate>> CandidatesAsync(CancellationToken cancellationToken)
    {
        using var document = JsonDocument.Parse(await GetTextAsync("/providers/proxies", cancellationToken));
        var providers = document.RootElement.GetProperty("providers");
        var byName = new Dictionary<string, Candidate>(StringComparer.Ordinal);
        foreach (var provider in providers.EnumerateObject())
        {
            if (!ConfigBuilder.ProviderNames.Contains(provider.Name, StringComparer.Ordinal)) continue;
            if (!provider.Value.TryGetProperty("proxies", out var proxies)) continue;
            foreach (var proxy in proxies.EnumerateArray())
            {
                var name = proxy.TryGetProperty("name", out var nameValue) ? nameValue.GetString() ?? "" : "";
                if (name.Length == 0) continue;
                var delay = LastDelay(proxy);
                var alive = delay > 0 && (!proxy.TryGetProperty("alive", out var aliveValue) || aliveValue.GetBoolean());
                if (!alive) continue;
                var candidate = new Candidate(name, provider.Name, delay, Pool(provider.Name, name));
                if (!byName.TryGetValue(name, out var previous) || candidate.DelayMs < previous.DelayMs)
                    byName[name] = candidate;
            }
        }
        return byName.Values
            .OrderBy(candidate => candidate.Pool)
            .ThenBy(candidate => candidate.DelayMs)
            .ToArray();
    }

    public async Task<string?> SelectedNodeAsync(CancellationToken cancellationToken)
    {
        using var document = JsonDocument.Parse(await GetTextAsync($"/proxies/{Escape(ConfigBuilder.AutoGroup)}", cancellationToken));
        return document.RootElement.TryGetProperty("now", out var value) ? value.GetString() : null;
    }

    public async Task SelectNodeAsync(string name, CancellationToken cancellationToken)
    {
        var json = JsonSerializer.Serialize(new { name });
        await SendAsync(HttpMethod.Put, $"/proxies/{Escape(ConfigBuilder.AutoGroup)}", json, cancellationToken);
    }

    public async Task HealthCheckProviderAsync(string provider, CancellationToken cancellationToken)
    {
        await GetTextAsync($"/providers/proxies/{Escape(provider)}/healthcheck", cancellationToken);
    }

    public async Task ReloadProviderAsync(string provider, CancellationToken cancellationToken)
    {
        await SendAsync(HttpMethod.Put, $"/providers/proxies/{Escape(provider)}", null, cancellationToken);
    }

    public async Task<bool> ProbeSelectedQuorumAsync(int required, CancellationToken cancellationToken)
    {
        var successes = 0;
        foreach (var url in ProbeUrls)
        {
            try
            {
                var path = $"/proxies/{Escape(ConfigBuilder.AutoGroup)}/delay?url={Escape(url)}&timeout=5000&expected=204";
                using var document = JsonDocument.Parse(await GetTextAsync(path, cancellationToken));
                if (document.RootElement.GetProperty("delay").GetInt32() > 0) successes++;
                if (successes >= required) return true;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch
            {
                // Quorum deliberately tolerates a failed endpoint.
            }
        }
        return false;
    }

    public async Task<bool> ProbeCandidateAsync(Candidate candidate, int required, CancellationToken cancellationToken)
    {
        var successes = 0;
        foreach (var url in ProbeUrls)
        {
            try
            {
                var path = $"/providers/proxies/{Escape(candidate.Provider)}/{Escape(candidate.Name)}/healthcheck" +
                           $"?url={Escape(url)}&timeout=5000";
                using var document = JsonDocument.Parse(await GetTextAsync(path, cancellationToken));
                if (document.RootElement.GetProperty("delay").GetInt32() > 0) successes++;
                if (successes >= required) return true;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch
            {
                // A subscription can refresh while a background candidate is checked.
            }
        }
        return false;
    }

    public async Task<TrafficSnapshot> TrafficAsync(string? selectedNode, CancellationToken cancellationToken)
    {
        using var document = JsonDocument.Parse(await GetTextAsync("/connections", cancellationToken));
        var root = document.RootElement;
        var active = 0;
        var uploadOnly = 0;
        var stalledDns = 0;
        if (root.TryGetProperty("connections", out var connections))
        {
            foreach (var connection in connections.EnumerateArray())
            {
                if (!connection.TryGetProperty("chains", out var chains)) continue;
                var throughVpn = chains.EnumerateArray().Any(chain =>
                {
                    var value = chain.GetString();
                    return value == ConfigBuilder.AutoGroup || value == selectedNode;
                });
                if (!throughVpn) continue;
                active++;
                var upload = connection.TryGetProperty("upload", out var uploadValue) ? uploadValue.GetInt64() : 0;
                var download = connection.TryGetProperty("download", out var downloadValue) ? downloadValue.GetInt64() : 0;
                if (upload <= 0 || download != 0) continue;
                uploadOnly++;
                if (connection.TryGetProperty("metadata", out var metadata) &&
                    metadata.TryGetProperty("host", out var host) &&
                    DnsHosts.Contains(host.GetString() ?? ""))
                    stalledDns++;
            }
        }

        return new TrafficSnapshot(
            root.TryGetProperty("uploadTotal", out var uploadTotal) ? uploadTotal.GetInt64() : 0,
            root.TryGetProperty("downloadTotal", out var downloadTotal) ? downloadTotal.GetInt64() : 0,
            active,
            uploadOnly,
            stalledDns);
    }

    public async Task<int> CloseConnectionsThroughAsync(string node, CancellationToken cancellationToken)
    {
        using var document = JsonDocument.Parse(await GetTextAsync("/connections", cancellationToken));
        var ids = new List<string>();
        if (!document.RootElement.TryGetProperty("connections", out var connections)) return 0;
        foreach (var connection in connections.EnumerateArray())
        {
            if (!connection.TryGetProperty("chains", out var chains) ||
                !chains.EnumerateArray().Any(chain => chain.GetString() == node)) continue;
            if (connection.TryGetProperty("id", out var id) && !string.IsNullOrWhiteSpace(id.GetString()))
                ids.Add(id.GetString()!);
        }

        var closed = 0;
        foreach (var id in ids)
        {
            try
            {
                await SendAsync(HttpMethod.Delete, $"/connections/{Escape(id)}", null, cancellationToken);
                closed++;
            }
            catch
            {
                // The connection may have ended on its own.
            }
        }
        return closed;
    }

    private async Task<string> GetTextAsync(string path, CancellationToken cancellationToken) =>
        await SendAsync(HttpMethod.Get, path, null, cancellationToken);

    private async Task<string> SendAsync(HttpMethod method, string path, string? body, CancellationToken cancellationToken)
    {
        using var request = new HttpRequestMessage(method, path);
        if (body is not null) request.Content = new StringContent(body, Encoding.UTF8, "application/json");
        using var response = await _client.SendAsync(request, cancellationToken);
        var text = await response.Content.ReadAsStringAsync(cancellationToken);
        if (!response.IsSuccessStatusCode)
            throw new HttpRequestException($"Core API {(int)response.StatusCode}: {text[..Math.Min(text.Length, 300)]}");
        return text;
    }

    private static int LastDelay(JsonElement proxy)
    {
        if (!proxy.TryGetProperty("history", out var history) || history.GetArrayLength() == 0) return 0;
        var last = history[history.GetArrayLength() - 1];
        return last.TryGetProperty("delay", out var value) ? value.GetInt32() : 0;
    }

    private static int Pool(string provider, string name)
    {
        if (provider == "ru_white" || provider.Contains("white", StringComparison.Ordinal)) return 1;
        if (provider == "ru_other" && !HasCountryFlag(name)) return 2;
        if (provider is "ru_mobile" or "ru_other") return 0;
        return 2;
    }

    private static bool HasCountryFlag(string value) =>
        value.EnumerateRunes().Any(rune => rune.Value is >= 0x1F1E6 and <= 0x1F1FF);

    private static string Escape(string value) => Uri.EscapeDataString(value);

    public static string PrettyName(string raw)
    {
        var value = raw;
        if (value.StartsWith('['))
        {
            var end = value.IndexOf(']');
            if (end >= 0) value = value[(end + 1)..].TrimStart();
        }
        var parts = value.Split('|', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
        var name = parts.Length >= 2 ? parts[1] : value;
        return name.Replace("，", ", ").Length <= 48 ? name.Replace("，", ", ") : name.Replace("，", ", ")[..48];
    }

    public void Dispose() => _client.Dispose();
}

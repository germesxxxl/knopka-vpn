namespace Butone.Windows;

internal enum VpnPhase
{
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
    Error,
}

internal sealed record ServiceResult(string Name, bool Available, long LatencyMs, string Detail);

internal sealed record VpnSnapshot(
    VpnPhase Phase,
    string Title,
    string Detail = "",
    string? Node = null,
    int? DelayMs = null,
    IReadOnlyList<ServiceResult>? Services = null,
    DateTimeOffset? ConnectedAt = null)
{
    public static VpnSnapshot Off { get; } = new(VpnPhase.Disconnected, "VPN выключен", "Нажмите кнопку, чтобы подключиться");
}

internal sealed record Candidate(string Name, string Provider, int DelayMs, int Pool)
{
    public string Id => $"{Provider}|{Name}";
}

internal sealed record TrafficSnapshot(
    long UploadTotal,
    long DownloadTotal,
    int ActiveProxied,
    int UploadOnlyProxied,
    int StalledDnsConnections);

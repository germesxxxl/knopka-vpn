namespace Butone.Windows;

internal sealed class TrafficWatchdog
{
    private TrafficSnapshot? _previous;
    private DateTimeOffset _windowStarted;
    private long _uploaded;
    private long _downloaded;

    public (bool Suspect, string Reason) Observe(TrafficSnapshot snapshot, DateTimeOffset now, bool ignoreSample)
    {
        var before = _previous;
        _previous = snapshot;
        if (before is null || ignoreSample)
        {
            ResetWindow(now);
            return (false, "");
        }

        var uploaded = snapshot.UploadTotal - before.UploadTotal;
        var downloaded = snapshot.DownloadTotal - before.DownloadTotal;
        if (uploaded < 0 || downloaded < 0)
        {
            ResetWindow(now);
            return (false, "");
        }
        _uploaded += uploaded;
        _downloaded += downloaded;

        if (downloaded >= 4 * 1024)
        {
            ResetWindow(now);
            return (false, "");
        }
        if (now - _windowStarted < TimeSpan.FromSeconds(20)) return (false, "");

        var strongSignal = snapshot.UploadOnlyProxied >= 3 || snapshot.StalledDnsConnections >= 2;
        var demand = _uploaded >= 8 * 1024 || strongSignal;
        var noResponse = _downloaded < 1024 || strongSignal;
        var suspect = demand && noResponse;
        var reason = !suspect ? "" : snapshot.StalledDnsConnections >= 2
            ? "DNS-запросы уходят без ответа"
            : snapshot.UploadOnlyProxied >= 3
                ? "несколько соединений отправляют данные без ответа"
                : "исходящий трафик есть, входящий отсутствует";
        ResetWindow(now);
        return (suspect, reason);
    }

    public void Reset()
    {
        _previous = null;
        _windowStarted = default;
        _uploaded = 0;
        _downloaded = 0;
    }

    private void ResetWindow(DateTimeOffset now)
    {
        _windowStarted = now;
        _uploaded = 0;
        _downloaded = 0;
    }
}

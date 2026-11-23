using System.Text.Json;

namespace Butone.Windows;

internal sealed class EventJournal
{
    private readonly string _path;
    private readonly object _sync = new();

    public EventJournal(string directory)
    {
        Directory.CreateDirectory(directory);
        _path = System.IO.Path.Combine(directory, "butone-windows-report.jsonl");
    }

    public string Path => _path;

    public void Record(string type, string message, object? fields = null)
    {
        var entry = new
        {
            time = DateTimeOffset.Now,
            type,
            message,
            fields,
        };
        var line = JsonSerializer.Serialize(entry);
        lock (_sync)
        {
            File.AppendAllText(_path, line + Environment.NewLine);
        }
    }
}

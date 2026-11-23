using System.Diagnostics;
using System.Net;
using System.Net.Sockets;

namespace Butone.Windows;

internal sealed class ServiceChecker
{
    public async Task<IReadOnlyList<ServiceResult>> CheckAsync(
        Action<IReadOnlyList<ServiceResult>>? onProgress,
        CancellationToken cancellationToken)
    {
        var completed = new List<ServiceResult>();
        var sync = new object();

        async Task<ServiceResult> Tracked(Func<Task<ServiceResult>> check)
        {
            var result = await check();
            lock (sync)
            {
                completed.Add(result);
                onProgress?.Invoke(completed.ToArray());
            }
            return result;
        }

        var tasks = new[]
        {
            Tracked(() => HttpAsync("YouTube", "https://www.youtube.com/generate_204", 204, cancellationToken)),
            Tracked(() => HttpAsync("Instagram", "https://www.instagram.com/robots.txt", null, cancellationToken)),
            Tracked(() => TelegramAsync(cancellationToken)),
            Tracked(() => HttpAsync("WhatsApp", "https://web.whatsapp.com/favicon.ico", null, cancellationToken)),
        };
        return await Task.WhenAll(tasks);
    }

    private static async Task<ServiceResult> HttpAsync(
        string service,
        string address,
        int? exactStatus,
        CancellationToken cancellationToken)
    {
        var timer = Stopwatch.StartNew();
        try
        {
            using var handler = new HttpClientHandler
            {
                Proxy = new WebProxy($"http://127.0.0.1:{ConfigBuilder.MixedPort}"),
                UseProxy = true,
                AllowAutoRedirect = false,
                AutomaticDecompression = DecompressionMethods.None,
            };
            using var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(6) };
            using var request = new HttpRequestMessage(HttpMethod.Get, address);
            request.Headers.UserAgent.ParseAdd("Mozilla/5.0 (Windows NT 10.0; Win64; x64) butone-connectivity/1");
            request.Headers.Range = new System.Net.Http.Headers.RangeHeaderValue(0, 65535);
            using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            var bytes = 0;
            await using (var input = await response.Content.ReadAsStreamAsync(cancellationToken))
            {
                var buffer = new byte[8192];
                while (bytes < 64 * 1024)
                {
                    var count = await input.ReadAsync(buffer.AsMemory(0, Math.Min(buffer.Length, 64 * 1024 - bytes)), cancellationToken);
                    if (count == 0) break;
                    bytes += count;
                }
            }

            var status = (int)response.StatusCode;
            var statusOk = exactStatus.HasValue ? status == exactStatus : status is >= 200 and <= 499;
            var bodyOk = exactStatus == 204 || status is >= 300 and <= 499 || bytes > 0;
            return new ServiceResult(service, statusOk && bodyOk, timer.ElapsedMilliseconds, $"HTTP {status}");
        }
        catch (Exception error) when (error is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
        {
            return new ServiceResult(service, false, timer.ElapsedMilliseconds, error.GetType().Name);
        }
    }

    private static async Task<ServiceResult> TelegramAsync(CancellationToken cancellationToken)
    {
        var timer = Stopwatch.StartNew();
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(6));
        var checkToken = timeout.Token;
        try
        {
            using var socket = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp);
            await socket.ConnectAsync(IPAddress.Loopback, ConfigBuilder.MixedPort, checkToken);
            await using var stream = new NetworkStream(socket, ownsSocket: false);

            await stream.WriteAsync(new byte[] { 0x05, 0x01, 0x00 }, checkToken);
            var hello = new byte[2];
            await stream.ReadExactlyAsync(hello, checkToken);
            if (hello[0] != 0x05 || hello[1] != 0x00) throw new IOException("SOCKS authentication failed");

            var request = new byte[]
            {
                0x05, 0x01, 0x00, 0x01,
                149, 154, 167, 50,
                0x01, 0xBB,
            };
            await stream.WriteAsync(request, checkToken);
            var reply = new byte[4];
            await stream.ReadExactlyAsync(reply, checkToken);
            if (reply[0] != 0x05 || reply[1] != 0x00) throw new IOException($"SOCKS reply {reply[1]}");
            return new ServiceResult("Telegram", true, timer.ElapsedMilliseconds, "TCP 443");
        }
        catch (Exception error) when (error is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
        {
            return new ServiceResult("Telegram", false, timer.ElapsedMilliseconds, error.GetType().Name);
        }
    }
}

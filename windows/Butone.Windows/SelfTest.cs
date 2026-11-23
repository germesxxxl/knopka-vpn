using System.Diagnostics;
using System.Text;

namespace Butone.Windows;

internal static class SelfTest
{
    public static int Run()
    {
        var logPath = Path.Combine(Path.GetTempPath(), "butone-self-test.log");
        var workDirectory = Path.Combine(Path.GetTempPath(), "butone-self-test-" + Guid.NewGuid().ToString("N"));
        try
        {
            Directory.CreateDirectory(Path.Combine(workDirectory, "providers"));
            Directory.CreateDirectory(Path.Combine(workDirectory, "rules"));
            foreach (var provider in ConfigBuilder.ProviderNames)
            {
                File.Copy(
                    Path.Combine(AppContext.BaseDirectory, "bundle", "providers", provider + ".yaml"),
                    Path.Combine(workDirectory, "providers", provider + ".yaml"));
            }
            foreach (var rule in new[] { "category-ru.mrs", "geoip-ru.mrs" })
            {
                File.Copy(
                    Path.Combine(AppContext.BaseDirectory, "bundle", "rules", rule),
                    Path.Combine(workDirectory, "rules", rule));
            }

            var configPath = Path.Combine(workDirectory, "config.yaml");
            File.WriteAllText(configPath, ConfigBuilder.Build("self-test-secret"), new UTF8Encoding(false));
            var corePath = Path.Combine(AppContext.BaseDirectory, "mihomo.exe");
            using var process = new Process
            {
                StartInfo = new ProcessStartInfo
                {
                    FileName = corePath,
                    Arguments = $"-t -d \"{workDirectory}\" -f \"{configPath}\"",
                    WorkingDirectory = workDirectory,
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardOutput = true,
                    RedirectStandardError = true,
                },
            };
            process.Start();
            var output = process.StandardOutput.ReadToEndAsync();
            var error = process.StandardError.ReadToEndAsync();
            if (!process.WaitForExit(60_000))
            {
                process.Kill(entireProcessTree: true);
                throw new TimeoutException("Mihomo config test timed out");
            }
            Task.WaitAll(output, error);
            File.WriteAllText(logPath, output.Result + Environment.NewLine + error.Result);
            return process.ExitCode;
        }
        catch (Exception error)
        {
            File.WriteAllText(logPath, error.ToString());
            return 1;
        }
        finally
        {
            try { Directory.Delete(workDirectory, recursive: true); } catch { }
        }
    }
}

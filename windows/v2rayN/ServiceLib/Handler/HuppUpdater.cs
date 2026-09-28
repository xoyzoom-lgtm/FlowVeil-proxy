using System.Net;
using System.Reflection;
using System.Text.Json;

namespace ServiceLib.Handler;

public sealed record HuppUpdateInfo(int Build, string Tag, string Notes, string? SetupUrl, bool HasUpdate);

/// <summary>Checks the Hupp GitHub releases ("build-N" tags) and downloads the installer.</summary>
public static class HuppUpdater
{
    private const string Repo = "xoyzoom-lgtm/hupp-proxy";
    private const string SetupAsset = "Hupp-Setup.exe";

    /// <summary>Build number baked in by CI as InformationalVersion "build-N"; 0 for local builds.</summary>
    public static int CurrentBuild()
    {
        var info = Assembly.GetEntryAssembly()?
            .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
        return ParseBuild(info?.Split('+')[0]);
    }

    private static int ParseBuild(string? tag)
    {
        if (tag.IsNullOrEmpty())
        {
            return 0;
        }
        var digits = new string(tag.SkipWhile(c => !char.IsDigit(c)).TakeWhile(char.IsDigit).ToArray());
        return int.TryParse(digits, out var n) ? n : 0;
    }

    /// <summary>Returns null when GitHub cannot be reached (directly or through the running core).</summary>
    public static async Task<HuppUpdateInfo?> CheckAsync()
    {
        var url = $"https://api.github.com/repos/{Repo}/releases/latest";
        var json = await GetAsync(url, null) ?? await GetAsync(url, LocalProxy());
        if (json.IsNullOrEmpty())
        {
            return null;
        }

        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            var tag = root.GetProperty("tag_name").GetString() ?? string.Empty;
            var notes = root.TryGetProperty("body", out var b) ? b.GetString() ?? string.Empty : string.Empty;
            string? setup = null;
            foreach (var asset in root.GetProperty("assets").EnumerateArray())
            {
                if (asset.GetProperty("name").GetString() == SetupAsset)
                {
                    setup = asset.GetProperty("browser_download_url").GetString();
                }
            }
            var build = ParseBuild(tag);
            return new HuppUpdateInfo(build, tag, notes, setup, build > CurrentBuild() && setup != null);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(HuppUpdater), ex);
            return null;
        }
    }

    /// <summary>Downloads the installer to a temp file, reporting 0..100.</summary>
    public static async Task<string?> DownloadAsync(string url, IProgress<int> progress)
    {
        var path = Path.Combine(Path.GetTempPath(), SetupAsset);
        foreach (var proxy in new[] { null, LocalProxy() })
        {
            try
            {
                using var client = MakeClient(proxy, TimeSpan.FromMinutes(10));
                using var response = await client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead);
                response.EnsureSuccessStatusCode();
                var total = response.Content.Headers.ContentLength ?? 0;
                await using var input = await response.Content.ReadAsStreamAsync();
                await using var output = File.Create(path);
                var buffer = new byte[81920];
                long done = 0;
                int read;
                while ((read = await input.ReadAsync(buffer)) > 0)
                {
                    await output.WriteAsync(buffer.AsMemory(0, read));
                    done += read;
                    if (total > 0)
                    {
                        progress.Report((int)(done * 100 / total));
                    }
                }
                return path;
            }
            catch (Exception ex)
            {
                Logging.SaveLog(nameof(HuppUpdater), ex);
            }
        }
        return null;
    }

    private static IWebProxy? LocalProxy()
    {
        try
        {
            return new WebProxy($"socks5://{Global.Loopback}:{AppManager.Instance.GetLocalPort(EInboundProtocol.socks)}");
        }
        catch
        {
            return null;
        }
    }

    private static HttpClient MakeClient(IWebProxy? proxy, TimeSpan timeout)
    {
        var handler = new HttpClientHandler { AllowAutoRedirect = true };
        if (proxy != null)
        {
            handler.Proxy = proxy;
            handler.UseProxy = true;
        }
        var client = new HttpClient(handler) { Timeout = timeout };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("Hupp-updater");
        return client;
    }

    private static async Task<string?> GetAsync(string url, IWebProxy? proxy)
    {
        try
        {
            using var client = MakeClient(proxy, TimeSpan.FromSeconds(10));
            return await client.GetStringAsync(url);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(HuppUpdater), ex);
            return null;
        }
    }
}

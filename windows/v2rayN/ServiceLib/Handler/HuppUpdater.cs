using System.Net;
using System.Reflection;
using System.Text.Json;

namespace ServiceLib.Handler;

public sealed record HuppUpdateInfo(int Build, string Tag, string Notes, string? SetupUrl, bool HasUpdate, string? SumsUrl = null, string? AssetName = null, string? ReleaseUrl = null, bool Portable = false);

/// <summary>Checks the FlowVeil GitHub releases ("build-N" tags) and downloads the installer.</summary>
public static class HuppUpdater
{
    private const string Repo = "xoyzoom-lgtm/FlowVeil-proxy";
    private const string SetupAsset = "FlowVeil-Setup.exe";

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

    /// <summary>True when FlowVeil was put in place by the installer (it leaves an uninstaller next to the program); the portable zip has none.</summary>
    public static bool IsInstalled() => File.Exists(Path.Combine(AppContext.BaseDirectory, "unins000.exe"));

    public abstract record Fetched
    {
        public sealed record Ok(string Json, string? ETag) : Fetched;

        public sealed record NotModified : Fetched;

        public sealed record Failed : Fetched;
    }

    /// <summary>
    /// The latest releases. With an ETag from an earlier answer GitHub replies "304 Not Modified" (it does not count against the rate limit).
    /// A cold connection often misses the first request: direct first, then through the running core (GitHub may be unreachable
    /// without it), then direct again.
    /// </summary>
    public static async Task<Fetched> FetchReleasesAsync(string? etag = null)
    {
        // Newest build by number, not GitHub's "latest" flag (parallel builds can mark an older one).
        var url = $"https://api.github.com/repos/{Repo}/releases?per_page=10";
        foreach (var proxy in new[] { null, LocalProxy(), null })
        {
            try
            {
                using var client = MakeClient(proxy, TimeSpan.FromSeconds(20));
                using var request = new HttpRequestMessage(HttpMethod.Get, url);
                if (!string.IsNullOrEmpty(etag))
                {
                    request.Headers.TryAddWithoutValidation("If-None-Match", etag);
                }
                using var response = await client.SendAsync(request);
                if (response.StatusCode == HttpStatusCode.NotModified)
                {
                    return new Fetched.NotModified();
                }
                if (response.IsSuccessStatusCode)
                {
                    return new Fetched.Ok(await response.Content.ReadAsStringAsync(), response.Headers.ETag?.ToString());
                }
            }
            catch (Exception ex)
            {
                Logging.SaveLog(nameof(HuppUpdater), ex);
            }
        }
        return new Fetched.Failed();
    }

    public static List<ReleaseInfo> ParseReleases(string json)
    {
        var list = new List<ReleaseInfo>();
        using var doc = JsonDocument.Parse(json);
        foreach (var release in doc.RootElement.EnumerateArray())
        {
            var assets = new List<ReleaseAsset>();
            if (release.TryGetProperty("assets", out var arr))
            {
                foreach (var asset in arr.EnumerateArray())
                {
                    assets.Add(new ReleaseAsset(asset.GetProperty("name").GetString() ?? string.Empty, asset.GetProperty("browser_download_url").GetString() ?? string.Empty));
                }
            }
            list.Add(new ReleaseInfo(
                release.GetProperty("tag_name").GetString() ?? string.Empty,
                release.TryGetProperty("draft", out var d) && d.GetBoolean(),
                release.TryGetProperty("prerelease", out var pre) && pre.GetBoolean(),
                release.TryGetProperty("body", out var b) ? b.GetString() ?? string.Empty : string.Empty,
                assets));
        }
        return list;
    }

    /// <summary>The update for this installation (installer or portable zip), or null when there is none.</summary>
    public static UpdateCandidate? CandidateFrom(string json)
    {
        try
        {
            return UpdateLogic.Pick(ParseReleases(json), CurrentBuild(), [IsInstalled() ? UpdateLogic.InstallerAsset : UpdateLogic.PortableAsset]);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(HuppUpdater), ex);
            return null;
        }
    }

    /// <summary>The manual check: always fresh. Returns null when GitHub cannot be reached (directly or through the running core).</summary>
    public static async Task<HuppUpdateInfo?> CheckAsync()
    {
        if (await FetchReleasesAsync() is not Fetched.Ok ok)
        {
            return null;
        }
        var candidate = CandidateFrom(ok.Json);
        UpdateNotifier.Remember(candidate, ok.ETag);
        return candidate == null
            ? new HuppUpdateInfo(CurrentBuild(), string.Empty, string.Empty, null, false)
            : new HuppUpdateInfo(candidate.Build, candidate.Tag, ReleaseNotes.Plain(candidate.Notes), candidate.AssetUrl, true, candidate.SumsUrl, candidate.AssetName, candidate.ReleaseUrl, !IsInstalled());
    }

    /// <summary>Checks the downloaded file against SHA256SUMS.txt: true = matches, false = differs (do not run it), null = no sums or not listed (do not block).</summary>
    public static async Task<bool?> VerifyAsync(string path, string? assetName, string? sumsUrl)
    {
        if (assetName == null || sumsUrl == null)
        {
            return null;
        }
        var text = await GetAsync(sumsUrl, null) ?? await GetAsync(sumsUrl, LocalProxy());
        if (text == null)
        {
            return null;
        }
        await using var stream = File.OpenRead(path);
        var hash = Convert.ToHexString(await System.Security.Cryptography.SHA256.HashDataAsync(stream)).ToLowerInvariant();
        return Sha256Sums.Verify(Sha256Sums.Parse(text), assetName, hash);
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
        client.DefaultRequestHeaders.UserAgent.ParseAdd("FlowVeil-updater");
        return client;
    }

    private static async Task<string?> GetAsync(string url, IWebProxy? proxy)
    {
        try
        {
            using var client = MakeClient(proxy, TimeSpan.FromSeconds(20));
            return await client.GetStringAsync(url);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(HuppUpdater), ex);
            return null;
        }
    }
}

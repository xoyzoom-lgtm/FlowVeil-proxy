using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;

namespace ServiceLib.Handler;

/// <summary>
/// "Why does it not work?" on Windows: the checks one after another (each result is reported as it arrives), then the pure
/// <see cref="Diagnosis.Diagnose"/>. Reuses what exists: SubscriptionInfoStore (provider headers), SubscriptionIssues (last failure),
/// ElevatedTask (TUN rights), the local port of the core. Nothing is sent anywhere except the probe requests themselves.
/// </summary>
public static class DiagnosticsRunner
{
    private const string Gstatic = "https://www.gstatic.com/generate_204";
    private static readonly string[] Domestic = ["https://ya.ru", "https://vk.com", "https://mail.ru"];
    private static readonly string[] Foreign = [Gstatic, "https://cp.cloudflare.com/generate_204"];

    public static DiagInputs? LastInputs { get; private set; }

    public static async Task<DiagResult> RunAsync(Config config, bool running, Action<DiagStepId, DiagResult>? progress = null)
    {
        var inputs = new DiagInputs { Running = running, TunWanted = config.TunModeItem.EnableTun, IsAdmin = Utils.IsAdministrator(), ElevatedTaskExists = ElevatedTask.Exists() };
        void Step(DiagStepId id, Func<DiagInputs, DiagInputs> update)
        {
            inputs = update(inputs);
            progress?.Invoke(id, Diagnosis.Diagnose(inputs));
        }

        // 1-3. the real network, time (from the Date header), DNS. With the tunnel on, a "direct" probe is not direct: skip what cannot be judged.
        var networkUp = HasRealNetwork();
        var tunActive = running && config.TunModeItem.EnableTun;
        bool captive = false;
        long? skew = null;
        bool? dnsOk = null;
        if (networkUp)
        {
            var (status, date) = await Probe(null, Gstatic);
            captive = status is int code && (code is (>= 200 and < 400) && code != 204);
            if (date is DateTimeOffset serverTime)
            {
                skew = (long)(DateTimeOffset.UtcNow - serverTime).TotalMilliseconds;
            }
            dnsOk = await CanResolve("www.gstatic.com");
        }
        Step(DiagStepId.Network, i => i with { NetworkUp = networkUp, Captive = captive });
        Step(DiagStepId.Time, i => i with { ClockSkewMs = skew });
        Step(DiagStepId.Dns, i => i with { DnsOk = dnsOk });

        // 4. the subscription of the selected server
        ProfileItem? profile = string.IsNullOrEmpty(config.IndexId) ? null : await SQLiteHelper.Instance.TableAsync<ProfileItem>().FirstOrDefaultAsync(p => p.IndexId == config.IndexId);
        var issue = SubIssue.None;
        if (profile != null && profile.Subid.IsNotEmpty())
        {
            var names = (await SQLiteHelper.Instance.TableAsync<ProfileItem>().Where(p => p.Subid == profile.Subid).ToListAsync()).Select(p => p.Remarks ?? string.Empty).ToList();
            var info = SubscriptionInfoStore.Get(profile.Subid);
            var byInfo = info == null ? SubIssue.None
                : SubscriptionHealth.ByInfo(info.ExpireSeconds * 1000, info.Used, info.Total, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
            issue = SubscriptionHealth.Combine(SubscriptionHealth.ByServerNames(names), byInfo, SubscriptionIssues.Get(profile.Subid));
        }
        Step(DiagStepId.Subscription, i => i with { Sub = issue });

        // 5. the server itself (TCP only; UDP protocols cannot be judged by a connect)
        var tcpBased = profile != null && profile.ConfigType is EConfigType.VMess or EConfigType.VLESS or EConfigType.Trojan or EConfigType.Shadowsocks or EConfigType.SOCKS or EConfigType.HTTP;
        bool? reachable = tcpBased && profile!.Address.IsNotEmpty() && profile.Port > 0 && networkUp && !tunActive
            ? await CanConnect(profile.Address, profile.Port, 3000)
            : null;
        Step(DiagStepId.Server, i => i with { HasServer = profile != null, ServerReachable = reachable });

        // 6. end to end through the local port of the running core: gstatic must answer exactly 204
        var port = config.Inbound.FirstOrDefault()?.LocalPort ?? 0;
        ProbeResult? e2e = null;
        if (running && port > 0)
        {
            var (status, _) = await Probe(new WebProxy(new Uri($"socks5://127.0.0.1:{port}")), Gstatic);
            e2e = new ProbeResult(status);
        }
        Step(DiagStepId.EndToEnd, i => i with { EndToEnd = e2e });

        // 7. is the network itself restricted (domestic answers, foreign does not)? Not judgeable with a tunnel that intercepts everything.
        bool? domestic = null, foreign = null;
        if (networkUp && !captive && !tunActive && !(e2e?.Is204 ?? false))
        {
            domestic = (await Task.WhenAll(Domestic.Select(u => Probe(null, u)))).Any(r => r.Status != null);
            foreign = (await Task.WhenAll(Foreign.Select(u => Probe(null, u)))).Any(r => r.Status != null);
        }
        Step(DiagStepId.Restriction, i => i with { DomesticDirect = domestic, ForeignDirect = foreign });

        // 8. TUN adapter and rights
        bool? tunAdapter = config.TunModeItem.EnableTun && running ? HasTunAdapter() : null;
        Step(DiagStepId.Tun, i => i with { TunAdapterPresent = tunAdapter });

        // 9. system proxy set by someone else, other clients that fight for it
        bool? proxyMatches = running && !config.TunModeItem.EnableTun && config.SystemProxyItem.SysProxyType == ESysProxyType.ForcedChange ? SystemProxyPointsTo(port) : null;
        var otherClient = ConflictingSoftware.FindRunning().Count > 0;
        Step(DiagStepId.Proxy, i => i with { SystemProxyMatches = proxyMatches, OtherClientRunning = otherClient });

        var result = Diagnosis.Diagnose(inputs);
        LastInputs = inputs;
        Logging.SaveLog($"diag: cause={result.Cause.Id()} warnings={string.Join(",", result.Warnings.Select(w => w.Id()))} sub={issue} e2e={e2e?.Status}");
        return result;
    }

    /// <summary>The copied report: build, system, network, cores, cause codes, the tail of the log. Masked, no identifiers.</summary>
    public static async Task<string> ReportAsync(Config config, DiagResult result)
    {
        var sb = new StringBuilder();
        sb.AppendLine($"FlowVeil build {HuppUpdater.CurrentBuild()}");
        sb.AppendLine($"{RuntimeInformation.OSDescription} {RuntimeInformation.OSArchitecture}, admin: {(Utils.IsAdministrator() ? "yes" : "no")}");
        sb.AppendLine($"Mode: {(config.TunModeItem.EnableTun ? "TUN" : config.SystemProxyItem.SysProxyType == ESysProxyType.ForcedChange ? "system proxy" : "local")}");
        sb.AppendLine($"Cores: xray {await CoreVersion("xray.exe", "-version")}; sing-box {await CoreVersion("sing-box.exe", "version")}");
        var i = LastInputs;
        if (i != null)
        {
            sb.AppendLine($"Network: {(i.NetworkUp ? "up" : "down")}{(i.Captive ? ", login page" : string.Empty)}; clock skew: {(i.ClockSkewMs is { } s ? $"{s / 1000}s" : "?")}");
        }
        sb.AppendLine($"Verdict: {result.Cause.Id()}");
        if (result.Warnings.Count > 0)
        {
            sb.AppendLine($"Warnings: {string.Join(", ", result.Warnings.Select(w => w.Id()))}");
        }
        sb.AppendLine("Steps:");
        foreach (var step in result.Steps)
        {
            sb.AppendLine($"  {step.Id}: {step.Status}{(step.Cause is { } c ? $" ({c.Id()})" : string.Empty)}");
        }
        var tail = TailOfLog(30);
        if (tail.Count > 0)
        {
            sb.AppendLine("Log:");
            tail.ForEach(l => sb.AppendLine("  " + l));
        }
        return ReportMask.Apply(sb.ToString());
    }

    /// <summary>Does traffic really pass through the running core? Either of two well-known addresses must answer through the local port.</summary>
    public static async Task<bool> TrafficPassesAsync(int port)
    {
        var proxy = new WebProxy(new Uri($"socks5://127.0.0.1:{port}"));
        foreach (var url in Foreign)
        {
            var (status, _) = await Probe(proxy, url);
            if (status is 204 or 200)
            {
                return true;
            }
        }
        return false;
    }

    /// <summary>A physical adapter is up and has a gateway: when it is not, a silent tunnel is not the server's fault.</summary>
    public static bool NetworkUp() => HasRealNetwork();

    // ---- probes ----

    private static async Task<(int? Status, DateTimeOffset? Date)> Probe(IWebProxy? proxy, string url)
    {
        try
        {
            using var handler = new HttpClientHandler { UseProxy = proxy != null, Proxy = proxy, AllowAutoRedirect = false };
            using var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(6) };
            using var response = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead);
            return ((int)response.StatusCode, response.Headers.Date);
        }
        catch
        {
            return (null, null);
        }
    }

    private static async Task<bool> CanResolve(string host)
    {
        try
        {
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(4));
            return (await Dns.GetHostAddressesAsync(host, cts.Token)).Length > 0;
        }
        catch
        {
            return false;
        }
    }

    private static async Task<bool> CanConnect(string host, int port, int timeoutMs)
    {
        try
        {
            using var client = new TcpClient();
            using var cts = new CancellationTokenSource(timeoutMs);
            await client.ConnectAsync(host, port, cts.Token);
            return true;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>A physical adapter that is up and has a gateway (tunnels, virtual adapters and loopback do not count).</summary>
    private static bool HasRealNetwork()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces().Any(n =>
                n.OperationalStatus == OperationalStatus.Up
                && n.NetworkInterfaceType is not (NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel)
                && !PairServer.IsVirtualAdapter(n.Name, n.Description)
                && n.GetIPProperties().GatewayAddresses.Any(g => !g.Address.Equals(IPAddress.Any) && !g.Address.Equals(IPAddress.IPv6Any)));
        }
        catch
        {
            return true;
        }
    }

    private static bool HasTunAdapter()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces().Any(n => n.Name.Contains("singbox_tun", StringComparison.OrdinalIgnoreCase) || n.Name.Contains("xray_tun", StringComparison.OrdinalIgnoreCase));
        }
        catch
        {
            return true;
        }
    }

    /// <summary>The system proxy in the registry (or a PAC file) points at our local port; null when it cannot be read.</summary>
    private static bool? SystemProxyPointsTo(int port)
    {
        if (!OperatingSystem.IsWindows() || port <= 0)
        {
            return null;
        }
        try
        {
            using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Internet Settings");
            if (key == null)
            {
                return null;
            }
            var enabled = key.GetValue("ProxyEnable") is int v && v == 1;
            var server = key.GetValue("ProxyServer") as string ?? string.Empty;
            var pac = key.GetValue("AutoConfigURL") as string ?? string.Empty;
            return (enabled && server.Contains($":{port}")) || pac.Contains("127.0.0.1");
        }
        catch
        {
            return null;
        }
    }

    private static async Task<string> CoreVersion(string exe, string arg)
    {
        try
        {
            var path = Utils.GetBinPath(exe);
            if (!File.Exists(path))
            {
                return "n/a";
            }
            using var process = Process.Start(new ProcessStartInfo(path, arg) { RedirectStandardOutput = true, CreateNoWindow = true, UseShellExecute = false });
            if (process == null)
            {
                return "?";
            }
            using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(3));
            var output = await process.StandardOutput.ReadLineAsync(cts.Token);
            return output?.Split(' ').FirstOrDefault(p => p.Length > 0 && char.IsDigit(p[0])) ?? output ?? "?";
        }
        catch
        {
            return "?";
        }
    }

    private static List<string> TailOfLog(int lines)
    {
        try
        {
            var file = new DirectoryInfo(Utils.GetLogPath()).GetFiles("*.txt").OrderByDescending(f => f.LastWriteTimeUtc).FirstOrDefault();
            if (file == null)
            {
                return [];
            }
            using var stream = new FileStream(file.FullName, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            using var reader = new StreamReader(stream);
            return reader.ReadToEnd().Split('\n').Select(l => l.TrimEnd('\r')).Where(l => l.Length > 0).TakeLast(lines).ToList();
        }
        catch
        {
            return [];
        }
    }
}

using System.Text.RegularExpressions;

namespace ServiceLib.Handler;

/*
 * "Why does it not work?" — the pure part. The cause ids are the same as in the Android client
 * (android/.../net/Diagnosis.kt); Windows adds the ones that only exist here (TUN adapter, admin rights, proxy conflicts).
 * Keep the two decision functions in step: the tests on both sides describe the same table.
 */

public enum DiagCause
{
    Ok,
    NoNetwork,
    CaptivePortal,
    WrongTime,
    DnsFailed,
    SubExpired,
    SubTrafficOver,
    SubDeviceLimit,
    SubBlocked,
    SubAccessDenied,
    SubLinkUnknown,
    SubWebPage,
    SubHappCrypt,
    SubNoServers,
    SubRateLimited,
    SubProviderDown,
    SubUnreachable,
    NoServer,
    NotConnected,
    ServerDown,
    ServerNotPassing,
    MobileRestricted,
    TunNeedsAdmin,
    TunAdapterMissing,
    ProxyConflict,
    OtherClient,
}

public enum StepStatus
{
    Ok,
    Warn,
    Fail,
    Skipped,
}

public enum DiagStepId
{
    Network,
    Time,
    Dns,
    Subscription,
    Server,
    EndToEnd,
    Restriction,
    Tun,
    Proxy,
}

public sealed record DiagStep(DiagStepId Id, StepStatus Status, DiagCause? Cause = null);

public sealed record DiagResult(IReadOnlyList<DiagStep> Steps, DiagCause Cause, IReadOnlyList<DiagCause> Warnings);

public enum SubIssue
{
    None,
    Expired,
    TrafficOver,
    DeviceLimit,
    Blocked,
    AccessDenied,
    LinkUnknown,
    WebPage,
    HappCrypt,
    NoServers,
    RateLimited,
    ProviderDown,
    Unreachable,
}

/// <summary>Result of one HTTP probe: the status code, or null when nothing came back.</summary>
public sealed record ProbeResult(int? Status)
{
    public bool Is204 => Status == 204;
}

public static class DiagCauseExtensions
{
    /// <summary>Snake-case id shared with the Android client (SubTrafficOver → sub_traffic_over).</summary>
    public static string Id(this DiagCause cause) => Regex.Replace(cause.ToString(), "(?<!^)([A-Z])", "_$1").ToLowerInvariant();

    public static DiagCause? ToCause(this SubIssue issue) => issue switch
    {
        SubIssue.None => null,
        SubIssue.Expired => DiagCause.SubExpired,
        SubIssue.TrafficOver => DiagCause.SubTrafficOver,
        SubIssue.DeviceLimit => DiagCause.SubDeviceLimit,
        SubIssue.Blocked => DiagCause.SubBlocked,
        SubIssue.AccessDenied => DiagCause.SubAccessDenied,
        SubIssue.LinkUnknown => DiagCause.SubLinkUnknown,
        SubIssue.WebPage => DiagCause.SubWebPage,
        SubIssue.HappCrypt => DiagCause.SubHappCrypt,
        SubIssue.NoServers => DiagCause.SubNoServers,
        SubIssue.RateLimited => DiagCause.SubRateLimited,
        SubIssue.ProviderDown => DiagCause.SubProviderDown,
        _ => DiagCause.SubUnreachable,
    };
}

/// <summary>Reads the state of a subscription from the standard signals. Heuristics: unknown shapes stay <see cref="SubIssue.None"/> and are logged by the caller.</summary>
public static class SubscriptionHealth
{
    private static readonly (SubIssue Issue, string[] Words)[] Markers =
    [
        (SubIssue.DeviceLimit, ["device limit", "devices limit", "limit of devices", "hwid limit", "лимит устройств", "лимит девайсов", "превышен лимит", "limit reached", "too many devices"]),
        (SubIssue.Expired, ["expired", "subscription ended", "истёк", "истек", "закончилась", "срок действия"]),
        (SubIssue.TrafficOver, ["traffic limit", "traffic exhausted", "no traffic", "трафик закончился", "трафик исчерпан", "исчерпан трафик", "лимит трафика"]),
        (SubIssue.Blocked, ["blocked", "banned", "disabled", "заблокирован", "отключена", "отключён", "отключен", "приостановлена"]),
    ];

    /// <summary>A marker counts when the list is a notice (3 entries or fewer) or consists of markers only.</summary>
    public static SubIssue ByServerNames(IReadOnlyList<string> names)
    {
        if (names.Count == 0)
        {
            return SubIssue.None;
        }
        var matches = names.Select(n => Markers.FirstOrDefault(m => m.Words.Any(w => n.Contains(w, StringComparison.OrdinalIgnoreCase))).Issue).ToList();
        var first = matches.FirstOrDefault(m => m != SubIssue.None);
        if (first == SubIssue.None)
        {
            return SubIssue.None;
        }
        return names.Count <= 3 || matches.All(m => m != SubIssue.None) ? first : SubIssue.None;
    }

    /// <summary>From subscription-userinfo: expiry in unix milliseconds, bytes used and total (0/unknown = no limit).</summary>
    public static SubIssue ByInfo(long expireAtMs, long used, long total, long nowMs)
    {
        if (expireAtMs > 0 && expireAtMs < nowMs)
        {
            return SubIssue.Expired;
        }
        if (total > 0 && used >= total)
        {
            return SubIssue.TrafficOver;
        }
        return SubIssue.None;
    }

    public static SubIssue ByHttpStatus(int code) => code switch
    {
        404 or 410 => SubIssue.LinkUnknown,
        401 or 403 => SubIssue.AccessDenied,
        429 => SubIssue.RateLimited,
        >= 500 and <= 599 => SubIssue.ProviderDown,
        _ => SubIssue.Unreachable,
    };

    public static SubIssue ByBody(string body)
    {
        var text = body.TrimStart();
        if (text.StartsWith("happ://", StringComparison.OrdinalIgnoreCase))
        {
            return SubIssue.HappCrypt;
        }
        return text.StartsWith('<') ? SubIssue.WebPage : SubIssue.NoServers;
    }

    public static SubIssue Combine(SubIssue byNames, SubIssue byInfo, SubIssue lastError) =>
        new[] { byNames, byInfo, lastError }.FirstOrDefault(i => i != SubIssue.None);
}

public sealed record DiagInputs
{
    public bool NetworkUp { get; init; }
    public bool Captive { get; init; }
    public long? ClockSkewMs { get; init; }
    public bool? DnsOk { get; init; }
    public SubIssue Sub { get; init; } = SubIssue.None;
    public bool HasServer { get; init; } = true;
    public bool Running { get; init; }
    public bool? ServerReachable { get; init; }
    public ProbeResult? EndToEnd { get; init; }
    public bool? DomesticDirect { get; init; }
    public bool? ForeignDirect { get; init; }

    // Windows only
    public bool TunWanted { get; init; }
    public bool IsAdmin { get; init; } = true;
    public bool ElevatedTaskExists { get; init; }
    public bool? TunAdapterPresent { get; init; }
    public bool? SystemProxyMatches { get; init; }
    public bool OtherClientRunning { get; init; }
}

public static class Diagnosis
{
    public const long MaxClockSkewMs = 5 * 60_000L;

    public static DiagResult Diagnose(DiagInputs i)
    {
        var steps = new List<DiagStep>();

        var skewBad = i.ClockSkewMs is { } skew && Math.Abs(skew) > MaxClockSkewMs;
        var dnsBad = i.DnsOk == false;
        var restricted = i.DomesticDirect == true && i.ForeignDirect == false;
        var e2e = i.EndToEnd;
        var e2eBad = i.Running && e2e != null && !e2e.Is204;
        var tunNeedsAdmin = i.TunWanted && !i.IsAdmin && !i.ElevatedTaskExists;
        var tunMissing = i.TunWanted && i.Running && i.TunAdapterPresent == false;
        var proxyConflict = !i.TunWanted && i.Running && i.SystemProxyMatches == false;

        steps.Add(new(DiagStepId.Network, !i.NetworkUp || i.Captive ? StepStatus.Fail : StepStatus.Ok,
            !i.NetworkUp ? DiagCause.NoNetwork : i.Captive ? DiagCause.CaptivePortal : null));
        steps.Add(new(DiagStepId.Time, i.ClockSkewMs == null ? StepStatus.Skipped : skewBad ? StepStatus.Fail : StepStatus.Ok, skewBad ? DiagCause.WrongTime : null));
        steps.Add(new(DiagStepId.Dns, i.DnsOk == null ? StepStatus.Skipped : dnsBad ? StepStatus.Fail : StepStatus.Ok, dnsBad ? DiagCause.DnsFailed : null));
        steps.Add(new(DiagStepId.Subscription, i.Sub == SubIssue.None ? StepStatus.Ok : StepStatus.Fail, i.Sub.ToCause()));
        steps.Add(new(DiagStepId.Server,
            !i.HasServer ? StepStatus.Fail : i.ServerReachable == null ? StepStatus.Skipped : i.ServerReachable == true ? StepStatus.Ok : StepStatus.Fail,
            !i.HasServer ? DiagCause.NoServer : i.ServerReachable == false ? DiagCause.ServerDown : null));
        steps.Add(new(DiagStepId.EndToEnd,
            !i.Running || e2e == null ? StepStatus.Skipped : e2e.Is204 ? StepStatus.Ok : StepStatus.Fail,
            e2eBad ? (restricted ? DiagCause.MobileRestricted : DiagCause.ServerNotPassing) : null));
        steps.Add(new(DiagStepId.Restriction,
            i.DomesticDirect == null || i.ForeignDirect == null ? StepStatus.Skipped : restricted ? StepStatus.Warn : StepStatus.Ok,
            restricted ? DiagCause.MobileRestricted : null));
        steps.Add(new(DiagStepId.Tun,
            !i.TunWanted ? StepStatus.Skipped : tunNeedsAdmin || tunMissing ? StepStatus.Fail : StepStatus.Ok,
            tunNeedsAdmin ? DiagCause.TunNeedsAdmin : tunMissing ? DiagCause.TunAdapterMissing : null));
        steps.Add(new(DiagStepId.Proxy,
            i.TunWanted || !i.Running || i.SystemProxyMatches == null ? StepStatus.Skipped : proxyConflict ? StepStatus.Fail : StepStatus.Ok,
            proxyConflict ? DiagCause.ProxyConflict : null));

        var cause =
            !i.NetworkUp ? DiagCause.NoNetwork :
            i.Captive ? DiagCause.CaptivePortal :
            skewBad ? DiagCause.WrongTime :
            dnsBad ? DiagCause.DnsFailed :
            i.Sub != SubIssue.None ? i.Sub.ToCause()!.Value :
            !i.HasServer ? DiagCause.NoServer :
            i.ServerReachable == false ? DiagCause.ServerDown :
            tunNeedsAdmin ? DiagCause.TunNeedsAdmin :
            !i.Running ? DiagCause.NotConnected :
            tunMissing ? DiagCause.TunAdapterMissing :
            e2eBad ? (restricted ? DiagCause.MobileRestricted : DiagCause.ServerNotPassing) :
            proxyConflict ? DiagCause.ProxyConflict :
            DiagCause.Ok;

        var warnings = new List<DiagCause>();
        if (restricted && cause != DiagCause.MobileRestricted)
        {
            warnings.Add(DiagCause.MobileRestricted);
        }
        if (i.OtherClientRunning)
        {
            warnings.Add(DiagCause.OtherClient);
        }
        return new DiagResult(steps, cause, warnings);
    }
}

/// <summary>Hides what must not end up in a copied report: links, pairing tokens and keys, ids, IP addresses, Wi-Fi names.</summary>
public static partial class ReportMask
{
    [GeneratedRegex(@"\b(vless|vmess|trojan|ss|hysteria2|hy2|tuic|wireguard|anytls)://\S+", RegexOptions.IgnoreCase)]
    private static partial Regex ProxyLink();

    [GeneratedRegex(@"\bhttps?://\S+", RegexOptions.IgnoreCase)]
    private static partial Regex WebLink();

    [GeneratedRegex(@"#t=[^\s&]+(&k=[^\s&]+)?(&v=\d+)?")]
    private static partial Regex PairFragment();

    [GeneratedRegex(@"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")]
    private static partial Regex Uuid();

    [GeneratedRegex(@"(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])")]
    private static partial Regex Ipv4();

    [GeneratedRegex(@"\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{1,4}\b")]
    private static partial Regex Ipv6();

    [GeneratedRegex(@"\b(token|key|password|pass|secret|hwid|ssid)\s*[=:]\s*\S+", RegexOptions.IgnoreCase)]
    private static partial Regex KeyValue();

    public static string Apply(string text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return text;
        }
        text = ProxyLink().Replace(text, m => m.Groups[1].Value + "://***");
        text = PairFragment().Replace(text, "#***");
        text = WebLink().Replace(text, "https://***");
        text = Uuid().Replace(text, m => m.Value[..8] + "-****");
        text = Ipv6().Replace(text, "[ip]");
        text = Ipv4().Replace(text, "[ip]");
        return KeyValue().Replace(text, m => m.Groups[1].Value + "=***");
    }
}

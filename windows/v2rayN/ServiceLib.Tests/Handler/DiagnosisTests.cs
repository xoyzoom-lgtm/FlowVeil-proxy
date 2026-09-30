namespace ServiceLib.Tests.Handler;

public class DiagnosisTests
{
    private static DiagInputs Base(Func<DiagInputs, DiagInputs>? change = null)
    {
        var i = new DiagInputs
        {
            NetworkUp = true,
            Running = true,
            EndToEnd = new ProbeResult(204),
            ServerReachable = true,
            DomesticDirect = true,
            ForeignDirect = true,
            SystemProxyMatches = true,
        };
        return change?.Invoke(i) ?? i;
    }

    private static DiagCause Cause(DiagInputs i) => Diagnosis.Diagnose(i).Cause;

    [Test]
    public async Task AllGood() => await Cause(Base()).Should().BeEqualTo(DiagCause.Ok);

    [Test]
    public async Task NoNetwork_Captive_Time_Dns_Order()
    {
        await Cause(Base(i => i with { NetworkUp = false, Sub = SubIssue.Expired })).Should().BeEqualTo(DiagCause.NoNetwork);
        await Cause(Base(i => i with { Captive = true })).Should().BeEqualTo(DiagCause.CaptivePortal);
        await Cause(Base(i => i with { ClockSkewMs = 10 * 60_000L })).Should().BeEqualTo(DiagCause.WrongTime);
        await Cause(Base(i => i with { ClockSkewMs = -3_600_000L })).Should().BeEqualTo(DiagCause.WrongTime);
        await Cause(Base(i => i with { ClockSkewMs = 60_000L })).Should().BeEqualTo(DiagCause.Ok);
        await Cause(Base(i => i with { DnsOk = false, Sub = SubIssue.Expired })).Should().BeEqualTo(DiagCause.DnsFailed);
    }

    [Test]
    public async Task SubscriptionWinsOverServer()
    {
        await Cause(Base(i => i with { Sub = SubIssue.Expired, ServerReachable = false })).Should().BeEqualTo(DiagCause.SubExpired);
        await Cause(Base(i => i with { Sub = SubIssue.DeviceLimit })).Should().BeEqualTo(DiagCause.SubDeviceLimit);
    }

    [Test]
    public async Task Server_And_Connection()
    {
        await Cause(Base(i => i with { HasServer = false })).Should().BeEqualTo(DiagCause.NoServer);
        await Cause(Base(i => i with { Running = false, EndToEnd = null })).Should().BeEqualTo(DiagCause.NotConnected);
        await Cause(Base(i => i with { ServerReachable = false })).Should().BeEqualTo(DiagCause.ServerDown);
        await Cause(Base(i => i with { EndToEnd = new ProbeResult(null) })).Should().BeEqualTo(DiagCause.ServerNotPassing);
        await Cause(Base(i => i with { EndToEnd = new ProbeResult(200) })).Should().BeEqualTo(DiagCause.ServerNotPassing);
        await Cause(Base(i => i with { EndToEnd = new ProbeResult(null), DomesticDirect = true, ForeignDirect = false })).Should().BeEqualTo(DiagCause.MobileRestricted);
    }

    [Test]
    public async Task Tun_Cases()
    {
        await Cause(Base(i => i with { TunWanted = true, IsAdmin = false, ElevatedTaskExists = false, Running = false, EndToEnd = null })).Should().BeEqualTo(DiagCause.TunNeedsAdmin);
        await Cause(Base(i => i with { TunWanted = true, IsAdmin = false, ElevatedTaskExists = true, Running = false, EndToEnd = null })).Should().BeEqualTo(DiagCause.NotConnected);
        await Cause(Base(i => i with { TunWanted = true, TunAdapterPresent = false })).Should().BeEqualTo(DiagCause.TunAdapterMissing);
        await Cause(Base(i => i with { TunWanted = true, TunAdapterPresent = true })).Should().BeEqualTo(DiagCause.Ok);
    }

    [Test]
    public async Task ProxyConflict_And_OtherClient()
    {
        await Cause(Base(i => i with { SystemProxyMatches = false })).Should().BeEqualTo(DiagCause.ProxyConflict);
        var r = Diagnosis.Diagnose(Base(i => i with { OtherClientRunning = true }));
        await r.Cause.Should().BeEqualTo(DiagCause.Ok);
        await r.Warnings.Contains(DiagCause.OtherClient).Should().BeTrue();
    }

    [Test]
    public async Task EveryStepPresentOnce()
    {
        var ids = Diagnosis.Diagnose(Base()).Steps.Select(s => s.Id).ToList();
        await ids.Count.Should().BeEqualTo(Enum.GetValues<DiagStepId>().Length);
    }

    [Test]
    public async Task IdsMatchAndroidSnakeCase()
    {
        await DiagCause.SubTrafficOver.Id().Should().BeEqualTo("sub_traffic_over");
        await DiagCause.NoNetwork.Id().Should().BeEqualTo("no_network");
        await DiagCause.Ok.Id().Should().BeEqualTo("ok");
        await DiagCause.MobileRestricted.Id().Should().BeEqualTo("mobile_restricted");
        await DiagCause.SubHappCrypt.Id().Should().BeEqualTo("sub_happ_crypt");
    }

    [Test]
    public async Task SubscriptionHealth_Markers_Info_Http_Body()
    {
        await SubscriptionHealth.ByServerNames(["⚠️ Превышен лимит устройств"]).Should().BeEqualTo(SubIssue.DeviceLimit);
        await SubscriptionHealth.ByServerNames(["Subscription expired"]).Should().BeEqualTo(SubIssue.Expired);
        await SubscriptionHealth.ByServerNames([.. Enumerable.Range(0, 20).Select(i => $"Server {i}"), "device limit"]).Should().BeEqualTo(SubIssue.None);
        await SubscriptionHealth.ByServerNames([]).Should().BeEqualTo(SubIssue.None);

        const long now = 2_000_000_000_000L;
        await SubscriptionHealth.ByInfo(now - 1, 0, 0, now).Should().BeEqualTo(SubIssue.Expired);
        await SubscriptionHealth.ByInfo(0, 100, 100, now).Should().BeEqualTo(SubIssue.TrafficOver);
        await SubscriptionHealth.ByInfo(0, 100, 0, now).Should().BeEqualTo(SubIssue.None);

        await SubscriptionHealth.ByHttpStatus(404).Should().BeEqualTo(SubIssue.LinkUnknown);
        await SubscriptionHealth.ByHttpStatus(403).Should().BeEqualTo(SubIssue.AccessDenied);
        await SubscriptionHealth.ByHttpStatus(429).Should().BeEqualTo(SubIssue.RateLimited);
        await SubscriptionHealth.ByHttpStatus(503).Should().BeEqualTo(SubIssue.ProviderDown);
        await SubscriptionHealth.ByHttpStatus(418).Should().BeEqualTo(SubIssue.Unreachable);

        await SubscriptionHealth.ByBody("happ://crypt3/abc").Should().BeEqualTo(SubIssue.HappCrypt);
        await SubscriptionHealth.ByBody("  <!doctype html>").Should().BeEqualTo(SubIssue.WebPage);
        await SubscriptionHealth.ByBody("garbage").Should().BeEqualTo(SubIssue.NoServers);

        await SubscriptionHealth.Combine(SubIssue.DeviceLimit, SubIssue.Expired, SubIssue.LinkUnknown).Should().BeEqualTo(SubIssue.DeviceLimit);
        await SubscriptionHealth.Combine(SubIssue.None, SubIssue.None, SubIssue.LinkUnknown).Should().BeEqualTo(SubIssue.LinkUnknown);
    }

    [Test]
    public async Task ReportMask_HidesSecrets()
    {
        var text = "sub https://p.example/sub/SECRET?t=1 vless://11111111-2222-3333-4444-555555555555@h.example:443 " +
                   "pair http://192.168.1.5:5123/p/SID#t=TOK&k=KEYKEY&v=1 ip 8.8.8.8 v6 2001:db8:0:0:0:0:0:1 token=abc ssid: HomeWifi id 11111111-2222-3333-4444-555555555555";
        var masked = ReportMask.Apply(text);
        foreach (var secret in new[] { "SECRET", "p.example", "TOK", "KEYKEY", "192.168.1.5", "8.8.8.8", "2001:db8", "abc", "HomeWifi", "555555555555" })
        {
            await masked.Contains(secret).Should().BeFalse();
        }
        await masked.Should().Contain("vless://***");
        await ReportMask.Apply("Windows 11, build-92").Should().BeEqualTo("Windows 11, build-92");
    }
}

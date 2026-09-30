namespace ServiceLib.Tests.Handler;

public class UpdateLogicTests
{
    private static ReleaseInfo Rel(string tag, string[] assets, bool draft = false, bool pre = false, string body = "") =>
        new(tag, draft, pre, body, assets.Select(a => new ReleaseAsset(a, $"https://x/{tag}/{a}")).ToList());

    private static readonly string[] Wanted = [UpdateLogic.InstallerAsset];
    private const long Day = NotifyPolicy.DayMs;

    [Test]
    public async Task BuildOf()
    {
        await UpdateLogic.BuildOf("v0092").Should().BeEqualTo(92);
        await UpdateLogic.BuildOf("build-92").Should().BeEqualTo(92);
        await (UpdateLogic.BuildOf("latest") == null).Should().BeTrue();
    }

    [Test]
    public async Task PicksHighestBuildNotLatestFlag()
    {
        var list = new[] { Rel("v0090", Wanted), Rel("v0092", Wanted), Rel("v0091", Wanted) };
        await UpdateLogic.Pick(list, 80, Wanted)!.Build.Should().BeEqualTo(92);
    }

    [Test]
    public async Task IgnoresDraftPrereleaseAndNotNewer()
    {
        var list = new[] { Rel("v0095", Wanted, draft: true), Rel("v0094", Wanted, pre: true), Rel("v0080", Wanted) };
        await (UpdateLogic.Pick(list, 80, Wanted) == null).Should().BeTrue();
        await (UpdateLogic.Pick([Rel("v0070", Wanted)], 80, Wanted) == null).Should().BeTrue();
    }

    [Test]
    public async Task NeedsAFittingAsset_ElseOlderNewerOne_AndFindsSums()
    {
        var list = new[] { Rel("v0093", ["FlowVeil-android.apk"]), Rel("v0092", [UpdateLogic.InstallerAsset, UpdateLogic.SumsFile]) };
        var c = UpdateLogic.Pick(list, 80, Wanted)!;
        await c.Build.Should().BeEqualTo(92);
        await c.SumsUrl!.EndsWith("SHA256SUMS.txt").Should().BeTrue();
        await (UpdateLogic.Pick([Rel("v0093", ["FlowVeil-android.apk"])], 80, Wanted) == null).Should().BeTrue();
    }

    [Test]
    public async Task NotifiesOncePerVersion_ThenOneReminder()
    {
        var s = new NotifyState();
        await NotifyPolicy.ShouldNotify(s, 92, 90, 0).Should().BeTrue();
        s = NotifyPolicy.AfterNotified(s, 92, 0);
        await NotifyPolicy.ShouldNotify(s, 92, 90, 2 * Day).Should().BeFalse();
        await NotifyPolicy.ShouldNotify(s, 92, 90, 3 * Day).Should().BeTrue();
        s = NotifyPolicy.AfterNotified(s, 92, 3 * Day);
        await s.Reminded.Should().BeTrue();
        await NotifyPolicy.ShouldNotify(s, 92, 90, 30 * Day).Should().BeFalse();
        await NotifyPolicy.ShouldNotify(s, 93, 90, 31 * Day).Should().BeTrue();
    }

    [Test]
    public async Task Later_And_Skip_And_NoDowngrade()
    {
        var s = NotifyPolicy.AfterNotified(new NotifyState(), 92, 0);
        s = NotifyPolicy.AfterLater(s, Day);
        await NotifyPolicy.ShouldNotify(s, 92, 90, 3 * Day).Should().BeFalse();
        await NotifyPolicy.BannerVisible(s, 92, 90, 3 * Day).Should().BeFalse();
        await NotifyPolicy.ShouldNotify(s, 92, 90, 4 * Day).Should().BeTrue();

        var skipped = NotifyPolicy.AfterSkip(new NotifyState(), 92);
        await NotifyPolicy.ShouldNotify(skipped, 92, 90, 0).Should().BeFalse();
        await NotifyPolicy.ShouldNotify(skipped, 93, 90, 0).Should().BeTrue();
        await NotifyPolicy.ShouldNotify(new NotifyState(), 90, 90, 0).Should().BeFalse();
        await NotifyPolicy.ShouldNotify(new NotifyState(), 89, 90, 0).Should().BeFalse();
    }

    [Test]
    public async Task StateRoundTrip()
    {
        var s = new NotifyState(92, 5, true, 9, 91);
        await NotifyState.Decode(s.Encode()).Should().BeEqualTo(s);
        await NotifyState.Decode("garbage").Should().BeEqualTo(new NotifyState());
        await NotifyState.Decode(null).Should().BeEqualTo(new NotifyState());
    }

    [Test]
    public async Task Throttle()
    {
        await CheckThrottle.MayCheck(0, 0, CheckThrottle.MinIntervalMs).Should().BeTrue();
        await CheckThrottle.MayCheck(0, 0, CheckThrottle.MinIntervalMs - 1).Should().BeFalse();
        await (CheckThrottle.DelayAfterFailures(3) > CheckThrottle.DelayAfterFailures(1)).Should().BeTrue();
        await CheckThrottle.DelayAfterFailures(50).Should().BeEqualTo(CheckThrottle.MaxBackoffMs);
    }

    [Test]
    public async Task Sha256Sums()
    {
        var hex = new string('a', 64);
        var sums = ServiceLib.Handler.Sha256Sums.Parse($"{hex}  FlowVeil-Setup.exe\n{new string('B', 64)} *other.zip\nnot a line\n");
        await ServiceLib.Handler.Sha256Sums.Verify(sums, "FlowVeil-Setup.exe", hex).Should().BeEqualTo(true);
        await ServiceLib.Handler.Sha256Sums.Verify(sums, "FlowVeil-Setup.exe", new string('c', 64)).Should().BeEqualTo(false);
        await ServiceLib.Handler.Sha256Sums.Verify(sums, "other.zip", new string('b', 64)).Should().BeEqualTo(true);
        await (ServiceLib.Handler.Sha256Sums.Verify(sums, "missing", hex) == null).Should().BeTrue();
    }

    [Test]
    public async Task NotesAreSafePlainText()
    {
        var md = "## Что нового\n- **Пункт** с [ссылкой](https://evil.example/x)\n<script>alert(1)</script>\n![img](https://x/y.png)\n\n\n\nКонец";
        var plain = ReleaseNotes.Plain(md);
        await plain.Contains('<').Should().BeFalse();
        await plain.Contains("evil.example").Should().BeFalse();
        await plain.Contains("**").Should().BeFalse();
        await plain.Should().Contain("• Пункт с ссылкой");
        await plain.EndsWith("Конец").Should().BeTrue();
        await (ReleaseNotes.Plain(new string('x', 5000), 100).Length <= 101).Should().BeTrue();
    }
}

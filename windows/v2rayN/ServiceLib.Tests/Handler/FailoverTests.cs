namespace ServiceLib.Tests.Handler;

public class FailoverTests
{
    private static readonly DateTimeOffset T0 = new(2026, 1, 1, 12, 0, 0, TimeSpan.Zero);

    [Test]
    public async Task OneFailureOnlyRechecks_SecondSwitches()
    {
        var s = new FailoverState();
        await s.Observe(false, true, T0).Should().BeEqualTo(FailoverAction.Recheck);
        await s.Observe(false, true, T0.AddSeconds(5)).Should().BeEqualTo(FailoverAction.Switch);
    }

    [Test]
    public async Task SuccessResetsTheCount()
    {
        var s = new FailoverState();
        s.Observe(false, true, T0);
        await s.Observe(true, true, T0.AddSeconds(5)).Should().BeEqualTo(FailoverAction.None);
        await s.Observe(false, true, T0.AddSeconds(30)).Should().BeEqualTo(FailoverAction.Recheck);
    }

    [Test]
    public async Task OfflineComputerNeverSwitches()
    {
        var s = new FailoverState();
        for (var i = 0; i < 5; i++)
        {
            await s.Observe(false, false, T0.AddSeconds(i * 5)).Should().BeEqualTo(FailoverAction.None);
        }
    }

    [Test]
    public async Task CooldownAfterSwitch()
    {
        var s = new FailoverState();
        s.Observe(false, true, T0);
        s.Observe(false, true, T0.AddSeconds(5));
        s.Observe(false, true, T0.AddSeconds(10));
        await s.Observe(false, true, T0.AddSeconds(15)).Should().BeEqualTo(FailoverAction.None);
        await s.Observe(false, true, T0.AddSeconds(60)).Should().BeEqualTo(FailoverAction.Switch);
    }

    private static ServerFacts F(string id, string sub, string name, long delay, int order) => new(id, sub, name, delay, "VLESS", "", "", order);

    [Test]
    public async Task Plan_CurrentSubFirstThenOthers_SkipsCurrentRussianAndUnusable()
    {
        var all = new List<ServerFacts>
        {
            F("a1", "A", "Нидерланды", 120, 0), F("a2", "A", "Польша", 80, 1), F("a3", "A", "🇷🇺 Москва", 30, 2), F("a4", "A", "Германия", 0, 3), F("a5", "A", "Финляндия", -1, 4),
            F("b1", "B", "Франция", 50, 0), F("c1", "C", "Турция", 10, 0),
        };
        var groups = FailoverPlan.Groups(all, "a1", "A", ["A", "B", "C"], id => id != "C", n => n != null && n.Contains("Москва"));
        await groups.Count.Should().BeEqualTo(2);
        await groups[0].Should().BeEquivalentTo(new List<string> { "a2", "a4", "a5" });
        await groups[1].Should().BeEquivalentTo(new List<string> { "b1" });
    }

    [Test]
    public async Task Plan_OtherSubsWhenCurrentIsUnusable()
    {
        var all = new List<ServerFacts> { F("a1", "A", "x", 10, 0), F("b1", "B", "y", 20, 0) };
        var groups = FailoverPlan.Groups(all, "a1", "A", ["A", "B"], id => id == "B", _ => false);
        await groups.Count.Should().BeEqualTo(1);
        await groups[0].Should().BeEquivalentTo(new List<string> { "b1" });
    }
}

public class ConflictingSoftwareTests
{
    [Test]
    public async Task Finds_Zapret_AndLabelsLikeTheDialog()
    {
        var found = ConflictingSoftware.Find(n => n == "winws");
        await found.Count.Should().BeEqualTo(1);
        await ConflictingSoftware.Label(found[0]).Should().BeEqualTo("zapret (winws.exe)");
    }

    [Test]
    public async Task NothingRunning_NothingFound_AndOurCoresAreNotListed()
    {
        await ConflictingSoftware.Find(_ => false).Count.Should().BeEqualTo(0);
        await ConflictingSoftware.Find(n => n is "xray" or "sing-box" or "FlowVeil").Count.Should().BeEqualTo(0);
    }
}

public class DnsDefaultsTests
{
    [Test]
    public async Task ChineseDefaultsBecomeYandex_UserChoiceStays()
    {
        await DnsDefaults.Migrate("119.29.29.29").Should().BeEqualTo("77.88.8.8");
        await DnsDefaults.Migrate("223.5.5.5").Should().BeEqualTo("77.88.8.8");
        await DnsDefaults.Migrate(null).Should().BeEqualTo("77.88.8.8");
        await DnsDefaults.Migrate("1.1.1.1").Should().BeEqualTo("1.1.1.1");
        await DnsDefaults.Migrate("localhost").Should().BeEqualTo("localhost");
    }
}

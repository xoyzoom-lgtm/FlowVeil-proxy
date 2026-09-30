namespace ServiceLib.Tests.Handler;

public class SubsLogicTests
{
    private const long Now = 1_800_000_000L;
    private const long Day = 86400L;

    private static SubFacts Sub(string id, bool enabled = true, long expire = 0, long used = 0, long total = 0, SubIssue issue = SubIssue.None) =>
        new(id, id, enabled, expire, used, total, issue);

    private static ServerFacts Srv(string id, string sub, string name, long delay = 0, string protocol = "VLESS", string security = "reality", string network = "tcp", int order = 0) =>
        new(id, sub, name, delay, protocol, security, network, order);

    private static readonly IReadOnlySet<string> BothEnabled = new HashSet<string> { "a", "b" };
    private static readonly IReadOnlyList<string> Order = ["a", "b"];
    private static readonly IReadOnlySet<string> NoFav = new HashSet<string>();

    private static List<ServerFacts> Filter(IEnumerable<ServerFacts> all, SubSelection sel, string? q = null, QuickFilter quick = QuickFilter.None, ServerSort sort = ServerSort.Provider,
        IReadOnlySet<string>? enabled = null, IReadOnlySet<string>? fav = null, IReadOnlyList<string>? order = null) =>
        SubsLogic.Filter(all, sel, enabled ?? BothEnabled, order ?? Order, q, quick, fav ?? NoFav, sort);

    // ---- health ----

    [Test]
    public async Task Health()
    {
        await SubsLogic.HealthOf(Sub("a"), Now).Should().BeEqualTo(SubHealth.Ok);
        await SubsLogic.HealthOf(Sub("a", enabled: false), Now).Should().BeEqualTo(SubHealth.Disabled);
        await SubsLogic.HealthOf(Sub("a", expire: Now - 1), Now).Should().BeEqualTo(SubHealth.Expired);
        await SubsLogic.HealthOf(Sub("a", expire: Now + 2 * Day), Now).Should().BeEqualTo(SubHealth.ExpiresSoon);
        await SubsLogic.HealthOf(Sub("a", expire: Now + 10 * Day), Now).Should().BeEqualTo(SubHealth.Ok);
        await SubsLogic.HealthOf(Sub("a", used: 100, total: 100), Now).Should().BeEqualTo(SubHealth.TrafficOver);
        await SubsLogic.HealthOf(Sub("a", used: 100, total: 1L << 60), Now).Should().BeEqualTo(SubHealth.Ok);
        await SubsLogic.HealthOf(Sub("a", issue: SubIssue.ProviderDown), Now).Should().BeEqualTo(SubHealth.Error);
        await SubsLogic.HealthOf(Sub("a", issue: SubIssue.Expired), Now).Should().BeEqualTo(SubHealth.Expired);
        await SubsLogic.IsUsable(SubHealth.Expired).Should().BeFalse();
        await SubsLogic.IsUsable(SubHealth.TrafficOver).Should().BeFalse();
        await SubsLogic.IsUsable(SubHealth.Disabled).Should().BeFalse();
        await SubsLogic.IsUsable(SubHealth.Error).Should().BeTrue();
        await SubsLogic.IsUsable(SubHealth.ExpiresSoon).Should().BeTrue();
    }

    // ---- filter, group order, sort ----

    private static readonly ServerFacts[] Servers =
    [
        Srv("1", "a", "Германия", 80, order: 0),
        Srv("2", "a", "Польша | Игровой", 0, "HYSTERIA2", "tls", "tcp", 1),
        Srv("3", "b", "Финляндия", 40, "VLESS", "tls", "tcp", 0),
        Srv("4", "b", "Россия", 20, "VLESS", "reality", "tcp", 1),
        Srv("5", "c", "Эстония", 30, order: 0),
    ];

    [Test]
    public async Task All_HidesDisabledSubs_AndKeepsSubOrder()
    {
        var rows = Filter(Servers, SubSelection.AllServers);
        await rows.Select(r => r.IndexId).ToList().Should().BeEquivalentTo(new[] { "1", "2", "3", "4" }.ToList());
        await rows.Select(r => r.SubId).ToList().Should().BeEquivalentTo(new[] { "a", "a", "b", "b" }.ToList());
        var reversed = Filter(Servers, SubSelection.AllServers, order: ["b", "a"]);
        await reversed[0].SubId.Should().BeEqualTo("b");
    }

    [Test]
    public async Task OneSub_ShowsItsServers_EvenWhenSwitchedOff()
    {
        var rows = Filter(Servers, new SubSelection(ChipKind.Sub, "c"));
        await rows.Count.Should().BeEqualTo(1);
        await rows[0].IndexId.Should().BeEqualTo("5");
    }

    [Test]
    public async Task Favorites_OnlyFromEnabledSubs()
    {
        var fav = new HashSet<string> { "1", "5" };
        var rows = Filter(Servers, SubSelection.FavoriteServers, fav: fav);
        await rows.Select(r => r.IndexId).ToList().Should().BeEquivalentTo(new[] { "1" }.ToList());
    }

    [Test]
    public async Task Search_MatchesNameProtocolSecurity()
    {
        await Filter(Servers, SubSelection.AllServers, q: "герман").Count.Should().BeEqualTo(1);
        await Filter(Servers, SubSelection.AllServers, q: "hysteria").Count.Should().BeEqualTo(1);
        await Filter(Servers, SubSelection.AllServers, q: "reality").Count.Should().BeEqualTo(2);
        await Filter(Servers, SubSelection.AllServers, q: "нет такого").Count.Should().BeEqualTo(0);
        await Filter(Servers, SubSelection.AllServers, q: "  ").Count.Should().BeEqualTo(4);
    }

    [Test]
    public async Task QuickFilters_CombineWithAnd()
    {
        await Filter(Servers, SubSelection.AllServers, quick: QuickFilter.Alive).Count.Should().BeEqualTo(3);
        await Filter(Servers, SubSelection.AllServers, quick: QuickFilter.Reality).Count.Should().BeEqualTo(2);
        await Filter(Servers, SubSelection.AllServers, quick: QuickFilter.Udp).Count.Should().BeEqualTo(1);
        await Filter(Servers, SubSelection.AllServers, quick: QuickFilter.Alive | QuickFilter.Reality).Count.Should().BeEqualTo(2);
        await Filter(Servers, SubSelection.AllServers, quick: QuickFilter.Favorite, fav: new HashSet<string> { "3" }).Count.Should().BeEqualTo(1);
    }

    [Test]
    public async Task Sort_ByPing_PutsUnknownLast_WithinEachSub()
    {
        var rows = Filter(Servers, SubSelection.AllServers, sort: ServerSort.Ping);
        await rows.Select(r => r.IndexId).ToList().Should().BeEquivalentTo(new[] { "1", "2", "4", "3" }.ToList());
        var byName = Filter(Servers, SubSelection.AllServers, sort: ServerSort.Name);
        await byName.Where(r => r.SubId == "a").Select(r => r.Name).First().Should().BeEqualTo("Германия");
    }

    // ---- best, failover ----

    [Test]
    public async Task Best_SkipsRussian_Unusable_And_Unmeasured()
    {
        var usable = (string sub) => sub != "b";
        var russian = (string name) => name.Contains("Россия");
        await SubsLogic.Best(Servers, usable, russian)!.IndexId.Should().BeEqualTo("5");
        await SubsLogic.Best(Servers.Where(s => s.SubId == "b"), usable, russian).Should().BeNull();
        await SubsLogic.Best(Servers, _ => true, russian)!.IndexId.Should().BeEqualTo("5");
        await SubsLogic.Best(Servers.Where(s => s.IndexId == "4"), _ => true, russian).Should().BeNull();
        await SubsLogic.Best(Servers.Where(s => s.IndexId == "2"), _ => true, russian).Should().BeNull();
    }

    [Test]
    public async Task Failover_SameSubFirst_ThenOthers_NeverUnusable()
    {
        var usable = (string id) => id != "c";
        var order = SubsLogic.FailoverOrder("b", ["a", "b", "c", "d"], usable);
        await order.Should().BeEquivalentTo(new List<string> { "b", "a", "d" });
        var dead = SubsLogic.FailoverOrder("c", ["a", "b", "c"], usable);
        await dead.Should().BeEquivalentTo(new List<string> { "a", "b" });
    }

    // ---- order ----

    [Test]
    public async Task Order_SavedFirst_UnknownDropped_NewAppended()
    {
        await SubsLogic.ApplyOrder(["a", "b", "c"], ["c", "x", "a"]).Should().BeEquivalentTo(new List<string> { "c", "a", "b" });
        await SubsLogic.Move(["a", "b", "c"], "c", -1).Should().BeEquivalentTo(new List<string> { "a", "c", "b" });
        await SubsLogic.Move(["a", "b", "c"], "a", -5).Should().BeEquivalentTo(new List<string> { "a", "b", "c" });
        await SubsLogic.Move(["a", "b", "c"], "zz", 1).Should().BeEquivalentTo(new List<string> { "a", "b", "c" });
    }

    // ---- duplicates ----

    [Test]
    public async Task Duplicate_LinkWrittenDifferently()
    {
        var existing = new[] { ("id1", "https://Sub.Example.com:443/abc/?t=1") };
        await SubsLogic.FindDuplicate(existing, "https://sub.example.com/abc?t=1").Should().BeEqualTo("id1");
        await SubsLogic.FindDuplicate(existing, "https://sub.example.com/abc?t=2").Should().BeNull();
        await SubsLogic.FindDuplicate(existing, "https://other.example.com/abc?t=1").Should().BeNull();
    }

    // ---- state ----

    [Test]
    public async Task State_RoundTrip_And_Tolerant()
    {
        var state = new SubsUiState { Selected = "b", Sort = ServerSort.Ping, Quick = QuickFilter.Alive | QuickFilter.Reality, Order = ["b", "a"], Collapsed = ["a"], Favorites = ["9"], LocalNames = new() { ["a"] = "Моя" } };
        var back = SubsUiState.Parse(state.ToJson());
        await back.Selected.Should().BeEqualTo("b");
        await back.Sort.Should().BeEqualTo(ServerSort.Ping);
        await back.Quick.Should().BeEqualTo(QuickFilter.Alive | QuickFilter.Reality);
        await back.Order.Should().BeEquivalentTo(new List<string> { "b", "a" });
        await back.LocalNames["a"].Should().BeEqualTo("Моя");

        foreach (var bad in new[] { null, "", "   ", "{", "[]", "null", "{\"Sort\":99,\"Quick\":255,\"Selected\":\"\"}" })
        {
            var s = SubsUiState.Parse(bad);
            await s.Selected.Should().BeEqualTo("all");
            await Enum.IsDefined(s.Sort).Should().BeTrue();
        }
        await SubSelection.Decode("fav").Should().BeEqualTo(SubSelection.FavoriteServers);
        await SubSelection.Decode("xyz").Should().BeEqualTo(new SubSelection(ChipKind.Sub, "xyz"));
        await SubSelection.Decode(null).Should().BeEqualTo(SubSelection.AllServers);
        await new SubSelection(ChipKind.Sub, "xyz").Encode().Should().BeEqualTo("xyz");
    }
}

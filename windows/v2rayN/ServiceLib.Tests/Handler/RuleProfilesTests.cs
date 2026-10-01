namespace ServiceLib.Tests.Handler;

public class RuleProfilesTests
{
    [Test]
    public async Task EveryProfile_ParsesAndEndsWithACatchAllRule()
    {
        foreach (var profile in RuleProfiles.All)
        {
            var rules = JsonUtils.Deserialize<List<RulesItem>>(profile.RulesJson);
            await (rules != null && rules.Count > 0).Should().BeTrue();
            var last = rules![^1];
            await last.Port.Should().BeEqualTo("0-65535");
        }
    }

    [Test]
    public async Task Profiles_RouteAsDescribed()
    {
        var home = JsonUtils.Deserialize<List<RulesItem>>(RuleProfiles.Find(RuleProfiles.HomeId)!.RulesJson)!;
        await home[^1].OutboundTag.Should().BeEqualTo("proxy");
        await home.Any(r => r.OutboundTag == "direct" && r.Ip != null && r.Ip.Contains("geoip:ru")).Should().BeTrue();

        var all = JsonUtils.Deserialize<List<RulesItem>>(RuleProfiles.Find(RuleProfiles.AllId)!.RulesJson)!;
        await all[^1].OutboundTag.Should().BeEqualTo("proxy");
        await all.Any(r => r.Ip != null && r.Ip.Contains("geoip:ru")).Should().BeFalse();

        var direct = JsonUtils.Deserialize<List<RulesItem>>(RuleProfiles.Find(RuleProfiles.DirectId)!.RulesJson)!;
        await direct.Single().OutboundTag.Should().BeEqualTo("direct");
    }

    [Test]
    public async Task Ids_AreUniqueAndFindWorks()
    {
        await RuleProfiles.All.Select(p => p.Id).Distinct().Count().Should().BeEqualTo(RuleProfiles.All.Count);
        await (RuleProfiles.Find("nope") == null).Should().BeTrue();
    }
}

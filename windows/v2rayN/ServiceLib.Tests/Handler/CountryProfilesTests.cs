using System.Text.Json;

namespace ServiceLib.Tests.Handler;

public class CountryProfilesTests
{
    private static JsonElement Shared()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null && !File.Exists(Path.Combine(dir.FullName, "shared", "country-profiles.json")))
        {
            dir = dir.Parent;
        }
        return JsonDocument.Parse(File.ReadAllText(Path.Combine(dir!.FullName, "shared", "country-profiles.json"))).RootElement;
    }

    [Test]
    public async Task SameAsSharedJson()
    {
        var items = Shared().GetProperty("countries").EnumerateArray().ToList();
        await items.Count.Should().BeEqualTo(CountryProfiles.All.Count);
        for (var i = 0; i < items.Count; i++)
        {
            var o = items[i];
            var c = CountryProfiles.All[i];
            await c.Id.Should().BeEqualTo(o.GetProperty("id").GetString());
            await c.NameRu.Should().BeEqualTo(o.GetProperty("nameRu").GetString());
            await c.NameEn.Should().BeEqualTo(o.GetProperty("nameEn").GetString());
            await c.Flag.Should().BeEqualTo(o.GetProperty("flag").GetString());
            await c.GeoIp.Should().BeEqualTo(o.GetProperty("geoip").GetString());
            await string.Join(",", c.GeoSites).Should().BeEqualTo(string.Join(",", o.GetProperty("geosites").EnumerateArray().Select(e => e.GetString())));
            await string.Join(",", c.DomainSuffixes).Should().BeEqualTo(string.Join(",", o.GetProperty("domainSuffixes").EnumerateArray().Select(e => e.GetString())));
        }
    }

    [Test]
    public async Task RulesAfterTelegram()
    {
        var ru = CountryProfiles.ById("ru");
        var rules = ExtraRules.After(true, false, ru);
        await rules.Count.Should().BeEqualTo(4);
        await rules[2].OutboundTag.Should().BeEqualTo(Global.DirectTag);
        await rules[2].Domain![0].Should().BeEqualTo("geosite:category-ru");
        await rules[3].Ip![0].Should().BeEqualTo("geoip:ru");
        await ExtraRules.After(false, false, null).Count.Should().BeEqualTo(0);
        await ExtraRules.After(true, true, ru).Count.Should().BeEqualTo(0);
    }
}

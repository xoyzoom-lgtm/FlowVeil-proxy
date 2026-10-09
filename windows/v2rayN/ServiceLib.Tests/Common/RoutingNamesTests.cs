namespace ServiceLib.Tests.Common;

public class RoutingNamesTests
{
    [Test]
    public async Task UpstreamChineseNamesAreShownInRussian()
    {
        await Assert.That(RoutingNames.Display("V4-绕过大陆(Whitelist)")).IsEqualTo(RoutingNames.Whitelist);
        await Assert.That(RoutingNames.Display("V4-黑名单(Blacklist)")).IsEqualTo(RoutingNames.Blacklist);
        await Assert.That(RoutingNames.Display("V4-全局(Global)")).IsEqualTo(RoutingNames.Global);
    }

    [Test]
    public async Task OwnNamesStayAsTheyAre()
    {
        await Assert.That(RoutingNames.Display("Мой профиль")).IsEqualTo("Мой профиль");
        await Assert.That(RoutingNames.Display(null)).IsEqualTo(string.Empty);
    }
}

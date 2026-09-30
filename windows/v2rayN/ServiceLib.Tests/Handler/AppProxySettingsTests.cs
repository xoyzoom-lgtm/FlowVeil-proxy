namespace ServiceLib.Tests.Handler;

[NotInParallel]
public class AppProxySettingsTests
{
    private const string Discord = @"C:\Users\me\AppData\Local\Discord\app-1.0\Discord.exe";
    private const string Helper = @"C:\Users\me\AppData\Local\Discord\app-1.0\DiscordSystemHelper.exe";

    private static void Clean()
    {
        AppProxySettings.SetMode(AppProxySettings.ModeOff);
        AppProxySettings.Remove(Discord);
        AppProxySettings.Remove(Helper);
    }

    [Test]
    public async Task ProcessNames_GivesPathAndFileNameOnce()
    {
        var names = AppProxySettings.ProcessNames([new AppProxyApp { Name = "Discord", Path = Discord }, new AppProxyApp { Name = "Discord", Path = Discord.ToUpperInvariant() }]);
        await names.Should().Contain(Discord);
        await names.Should().Contain("Discord.exe");
        await names.Count.Should().BeEqualTo(2);
    }

    [Test]
    public async Task OffMeansNoRules_AndAnEmptyListMeansNoRulesInAnyMode()
    {
        Clean();
        try
        {
            await AppProxySettings.BuildRules(out var only).Count.Should().BeEqualTo(0);
            AppProxySettings.SetMode(AppProxySettings.ModeProxy);
            await AppProxySettings.BuildRules(out only).Count.Should().BeEqualTo(0);
            await only.Should().BeFalse();
        }
        finally
        {
            Clean();
        }
    }

    [Test]
    public async Task DirectMode_AddsOneDirectRuleAndKeepsUserRules()
    {
        Clean();
        try
        {
            await AppProxySettings.Add(Discord).Should().BeTrue();
            await AppProxySettings.Add(Discord).Should().BeFalse();
            await AppProxySettings.Add(@"C:\notes.txt").Should().BeFalse();
            AppProxySettings.SetMode(AppProxySettings.ModeDirect);
            var rules = AppProxySettings.BuildRules(out var only);
            await only.Should().BeFalse();
            await rules.Count.Should().BeEqualTo(1);
            await rules[0].OutboundTag.Should().BeEqualTo(Global.DirectTag);
            await rules[0].Process!.Should().Contain(Discord);
        }
        finally
        {
            Clean();
        }
    }

    [Test]
    public async Task ProxyOnlyMode_SendsSelectedThroughProxyAndEverythingElseDirect()
    {
        Clean();
        try
        {
            AppProxySettings.Add(Discord);
            AppProxySettings.Add(Helper);
            AppProxySettings.SetMode(AppProxySettings.ModeProxy);
            var rules = AppProxySettings.BuildRules(out var only);
            await only.Should().BeTrue();
            await rules.Count.Should().BeEqualTo(2);
            await rules[0].OutboundTag.Should().BeEqualTo(Global.ProxyTag);
            await rules[1].OutboundTag.Should().BeEqualTo(Global.DirectTag);
            await rules[1].Port.Should().BeEqualTo("0-65535");
        }
        finally
        {
            Clean();
        }
    }
}

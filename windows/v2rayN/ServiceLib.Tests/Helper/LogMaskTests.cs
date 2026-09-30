namespace ServiceLib.Tests.Helper;

public class LogMaskTests
{
    [Test]
    public async Task HidesProxyLinksSubscriptionTokensAndIds()
    {
        var line = "add vless://11111111-2222-3333-4444-555555555555@h.example:443?x=1#n and https://p.example/sub/SECRET?token=abc id 11111111-2222-3333-4444-555555555555";
        var masked = LogMask.Apply(line);
        await masked.Should().Contain("vless://***");
        await masked.Should().Contain("https://p.example/***");
        await masked.Should().Contain("11111111-****");
        await masked.Contains("SECRET").Should().BeFalse();
        await masked.Contains("555555555555").Should().BeFalse();
    }

    [Test]
    public async Task LeavesPlainTextAlone()
    {
        await LogMask.Apply("Подключено, пинг 42 мс").Should().BeEqualTo("Подключено, пинг 42 мс");
    }
}

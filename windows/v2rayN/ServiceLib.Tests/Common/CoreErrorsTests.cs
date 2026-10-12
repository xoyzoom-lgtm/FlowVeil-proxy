namespace ServiceLib.Tests.Common;

public class CoreErrorsTests
{
    [Test]
    public async Task PlainVlessRefusalIsRecognised()
    {
        var raw = "config error: infra/conf: vless without TLS or other encryption is prohibited unless the server address is a private IP or domain";
        await Assert.That(CoreErrors.Friendly(raw)).IsEqualTo(CoreErrors.PlainServer);
    }

    [Test]
    public async Task OtherLinesAreLeftAlone()
    {
        await Assert.That(CoreErrors.Friendly("Xray started")).IsNull();
        await Assert.That(CoreErrors.Friendly(null)).IsNull();
    }
}

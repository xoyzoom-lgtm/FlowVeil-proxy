namespace ServiceLib.Tests.Handler;

public class SubReceiverTests
{
    [Test]
    public async Task ValidLink_AcceptsOnlyOneWebLink()
    {
        await SubReceiver.ValidLink("https://provider.example/sub/abc").Should().BeTrue();
        await SubReceiver.ValidLink("http://10.0.0.5:2096/sub/x").Should().BeTrue();
        await SubReceiver.ValidLink("javascript:alert(1)").Should().BeFalse();
        await SubReceiver.ValidLink("https://a.example/x y").Should().BeFalse();
        await SubReceiver.ValidLink("vless://abc@host:443").Should().BeFalse();
        await SubReceiver.ValidLink("http://192.168.1.5:5000/?fv_tv=1&t=abc").Should().BeFalse();
    }

    [Test]
    public async Task ParseForm_DecodesFieldsAndSkipsFieldsWithoutValue()
    {
        var form = SubReceiver.ParseForm("t=abc&url=https%3A%2F%2Fp.example%2Fsub%3Fa%3D1&x");
        await form["t"].Should().BeEqualTo("abc");
        await form["url"].Should().BeEqualTo("https://p.example/sub?a=1");
        await form.ContainsKey("x").Should().BeFalse();
    }
}

namespace ServiceLib.Tests.Handler;

public class InviteLinkTests
{
    [Test]
    public async Task EncodedUrl()
    {
        var i = InviteLink.Parse("flowveil://add?url=https%3A%2F%2Fp.example%2Fsub%2Fabc");
        await i!.Link.Should().BeEqualTo("https://p.example/sub/abc");
        await (i.Name == null).Should().BeTrue();
    }

    [Test]
    public async Task NameParam()
    {
        var i = InviteLink.Parse("flowveil://add?name=%D0%9C%D0%BE%D0%B9%20%D0%BF%D1%80%D0%BE%D0%B2%D0%B0%D0%B9%D0%B4%D0%B5%D1%80&url=https%3A%2F%2Fp.example%2Fs");
        await i!.Name.Should().BeEqualTo("Мой провайдер");
        await i.Link.Should().BeEqualTo("https://p.example/s");
    }

    [Test]
    public async Task PathFormAndFragmentName()
    {
        await InviteLink.Parse("flowveil://add/https://p.example/sub/abc")!.Link.Should().BeEqualTo("https://p.example/sub/abc");
        await InviteLink.Parse("flowveil://add?url=https%3A%2F%2Fp.example%2Fs#My%20Net")!.Name.Should().BeEqualTo("My Net");
    }

    [Test]
    public async Task Nothing()
    {
        await (InviteLink.Parse("flowveil://add") == null).Should().BeTrue();
        await (InviteLink.Parse("flowveil://add?name=Only") == null).Should().BeTrue();
        await (InviteLink.Parse("hello") == null).Should().BeTrue();
    }

    [Test]
    public async Task ServerLinkKeepsFragment()
    {
        var i = InviteLink.Parse("flowveil://add?url=vless%3A%2F%2Fid%40h%3A443%3Ftype%3Dtcp%23My%20Server");
        await i!.Link.Should().BeEqualTo("vless://id@h:443?type=tcp#My Server");
        await (i.Name == null).Should().BeTrue();
    }

    [Test]
    public async Task NameIsCleaned()
    {
        await InviteLink.CleanName("  A\u0000\n   B  ").Should().BeEqualTo("A B");
        await (InviteLink.CleanName("   ") == null).Should().BeTrue();
        await InviteLink.CleanName(new string('x', 200))!.Length.Should().BeEqualTo(InviteLink.MaxName);
    }

    [Test]
    public async Task InfoHeadersRejectOtherSchemes()
    {
        var h = new Dictionary<string, string> { ["support-url"] = "file:///C:/Windows/notepad.exe", ["profile-web-page-url"] = "javascript:alert(1)" };
        var info = SubscriptionInfoStore.Parse(h);
        await (info?.SupportUrl == null).Should().BeTrue();
        await (info?.WebPageUrl == null).Should().BeTrue();
        h = new() { ["support-url"] = "https://t.me/x", ["profile-web-page-url"] = "https://p.example" };
        info = SubscriptionInfoStore.Parse(h);
        await info!.SupportUrl.Should().BeEqualTo("https://t.me/x");
        await info.WebPageUrl.Should().BeEqualTo("https://p.example");
    }
}

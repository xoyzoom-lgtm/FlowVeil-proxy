namespace ServiceLib.Tests.Handler;

/// <summary>Same cases as Android ImportSourceTest.</summary>
public class ImportSourceTests
{
    private static string? Text(string? raw) => (ImportSource.Normalize(raw) as ImportSource.Result.Text)?.Value;

    [Test]
    public async Task HappAddUnwrapped()
    {
        await Text("happ://add/https://sub.example.com/abc").Should().BeEqualTo("https://sub.example.com/abc");
        await Text("happ://add/https%3A%2F%2Fsub.example.com%2Fabc%3Fx%3D1").Should().BeEqualTo("https://sub.example.com/abc?x=1");
        await (ImportSource.Normalize("happ://add/") is ImportSource.Result.Empty).Should().BeTrue();
    }

    [Test]
    public async Task HappCryptNotOpened()
    {
        await (ImportSource.Normalize("happ://crypt/AAAA") is ImportSource.Result.HappEncrypted).Should().BeTrue();
        await (ImportSource.Normalize("HAPP://crypt3/xyz") is ImportSource.Result.HappEncrypted).Should().BeTrue();
    }

    [Test]
    public async Task V2rayngAndFlowveil()
    {
        await Text("v2rayng://install-sub?url=https%3A%2F%2Fa.example%2Fs").Should().BeEqualTo("https://a.example/s");
        // On Windows the provider's title travels separately (InviteLink.Invite.Name), the link stays clean.
        await Text("flowveil://add?url=https://a.example/s&name=Name").Should().BeEqualTo("https://a.example/s");
    }

    [Test]
    public async Task OthersUnchanged()
    {
        await Text("  vless://id@host:443#x \n").Should().BeEqualTo("vless://id@host:443#x");
        await Text("https://x.example/sub").Should().BeEqualTo("https://x.example/sub");
        await (ImportSource.Normalize("   ") is ImportSource.Result.Empty).Should().BeTrue();
        await (ImportSource.Normalize(null) is ImportSource.Result.Empty).Should().BeTrue();
    }

    [Test]
    public async Task OtherAppsWrappers()
    {
        const string link = "https://sub.example.com/abc/def";
        const string enc = "https%3A%2F%2Fsub.example.com%2Fabc%2Fdef";
        await Text("hiddify://import/" + link).Should().BeEqualTo(link);
        await Text("hiddify://import/" + link + "#Name").Should().BeEqualTo(link + "#Name");
        await Text("v2raytun://import/" + link).Should().BeEqualTo(link);
        await Text("clash://install-config?url=" + enc + "&name=x").Should().BeEqualTo(link);
        await Text("sing-box://import-remote-profile?url=" + enc + "#Name").Should().BeEqualTo(link);
        await Text("v2box://install-sub?url=" + enc + "&name=x").Should().BeEqualTo(link);
        await Text("someapp://import/" + enc).Should().BeEqualTo(link);
        await Text("sub://" + Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(link))).Should().BeEqualTo(link);
    }

    [Test]
    public async Task ServerLinksAndGarbageUntouched()
    {
        const string vless = "vless://id@host:443?security=tls&host=https://x.example#n";
        await Text(vless).Should().BeEqualTo(vless);
        await Text("myapp://nothing/here").Should().BeEqualTo("myapp://nothing/here");
        await Text("sub://@@@").Should().BeEqualTo("sub://@@@");
    }
}

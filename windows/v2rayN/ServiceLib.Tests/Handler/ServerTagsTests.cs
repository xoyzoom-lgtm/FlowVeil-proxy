namespace ServiceLib.Tests.Handler;

public class ServerTagsTests
{
    [Test]
    public async Task Tags_DropRepeatsTcpAndJson()
    {
        await ServerTags.From("HYSTERIA / HYSTERIA / TLS / JSON", false).Should().BeEquivalentTo(new List<string> { "HYSTERIA", "TLS" });
        await ServerTags.From("VLESS / TCP / REALITY / JSON", false).Should().BeEquivalentTo(new List<string> { "VLESS", "REALITY" });
        await ServerTags.From("VLESS / WS / TLS", false).Should().BeEquivalentTo(new List<string> { "VLESS", "WS", "TLS" });
        await ServerTags.From("VLESS / TCP / REALITY / JSON", true).Should().BeEquivalentTo(new List<string> { "VLESS", "REALITY", "JSON" });
        await ServerTags.From(null, false).Count.Should().BeEqualTo(0);
    }

    [Test]
    public async Task Sanitizer_KeepsTextAndDropsUndrawableSymbols()
    {
        await TextSanitizer.ForWpf("Серверa \"Белые списки\" - в конце списка стран").Should().Contain("Белые списки");
        await TextSanitizer.ForWpf("A️ B‍ C").Should().BeEqualTo("A B C");
        await TextSanitizer.ForWpf("\U0001FAAC Поддержка").Should().BeEqualTo("Поддержка");
        await TextSanitizer.ForWpf("\U0001F4F1 Телефон").Should().Contain("Телефон");
        await TextSanitizer.ForWpf(null).Should().BeEqualTo(string.Empty);
    }
}

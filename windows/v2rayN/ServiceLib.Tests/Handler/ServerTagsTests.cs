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

public class ThemeDefaultsTests
{
    [Test]
    public async Task DefaultThemes_AreValid_AndPickByMode()
    {
        foreach (var theme in new[] { HappThemes.FlowVeilDark, HappThemes.FlowVeilLight })
        {
            foreach (var color in new[] { theme.Background, theme.Card, theme.Accent, theme.OnAccent, theme.Text, theme.SubText })
            {
                await (HappTheme.Parse(color) != null).Should().BeTrue();
            }
        }
        await HappThemes.FlowVeilDark.IsLight.Should().BeFalse();
        await HappThemes.FlowVeilLight.IsLight.Should().BeTrue();
        await HappThemes.Resolve("Dark", true)!.IsLight.Should().BeFalse();
        await HappThemes.Resolve("Light", false)!.IsLight.Should().BeTrue();
        await HappThemes.Resolve("FollowSystem", true)!.IsLight.Should().BeTrue();
        await HappThemes.Resolve(null, false)!.IsLight.Should().BeFalse();
        await HappThemes.Resolve("Apple Pro Black", true)!.Name.Should().BeEqualTo("Apple Pro Black");
        await (HappThemes.Resolve("Aquatic", false) == null).Should().BeTrue();
    }
}

namespace ServiceLib.Tests.Handler;

/// <summary>Same cases as Android HomeCardTest, so both apps shorten names and pick colours alike.</summary>
public class ShortNameTests
{
    [Test]
    public async Task DropsEmojiAndCollapsesSpaces()
    {
        await ShortName.Of("🚀  Fast   Net 🇩🇪").Should().BeEqualTo("Fast Net");
        await ShortName.Of("Мой ✨ сервис").Should().BeEqualTo("Мой сервис");
    }

    [Test]
    public async Task FixesShouting()
    {
        await ShortName.Of("SUPERVPN PRO").Should().BeEqualTo("Supervpn pro");
        await ShortName.Of("AB").Should().BeEqualTo("Ab");
        await ShortName.Of("X").Should().BeEqualTo("X");
        await ShortName.Of("Net 24").Should().BeEqualTo("Net 24");
    }

    [Test]
    public async Task CutsLongNames()
    {
        var s = ShortName.Of("Очень длинное название подписки");
        await s.Length.Should().BeEqualTo(16);
        await s.EndsWith('…').Should().BeTrue();
        await ShortName.Of("Ровно16символов!").Should().BeEqualTo("Ровно16символов!");
    }

    [Test]
    public async Task OnlyEmojiKeepsOriginal()
    {
        await ShortName.Of("🚀🔥").Should().BeEqualTo("🚀🔥");
        await ShortName.Initial("🚀 flow").Should().BeEqualTo("F");
        await ShortName.Initial("🚀").Should().BeEqualTo("•");
    }

    [Test]
    public async Task PaletteMatchesAndroid()
    {
        await ShortName.PaletteIndex("").Should().BeEqualTo(0);
        await ShortName.PaletteIndex("a").Should().BeEqualTo(97 % 3);
        await ShortName.PaletteIndex("4dd0918faa9a2cca").Should().BeEqualTo(1);
    }

    [Test]
    public async Task DaysLeftRoundsUp()
    {
        await ShortName.DaysLeft(1000, 2000).Should().BeEqualTo(0L);
        await ShortName.DaysLeft(86_400 * 3 + 1, 1).Should().BeEqualTo(3L);
        await ShortName.DaysLeft(86_400 * 3 + 2, 1).Should().BeEqualTo(4L);
    }
}

namespace ServiceLib.Tests.Handler;

public class ServerIdentityTests
{
    private static string Fp(params string[] p) => ServerIdentity.Fingerprint(p);
    private static IdEntry E(string id, string fp, string name) => new(id, fp, ServerIdentity.NameKey(name));

    private static readonly string De = Fp("vless", "de.example", "443", "uuid-1", "tcp", "reality", "sni", "pk");
    private static readonly string Nl = Fp("vless", "nl.example", "443", "uuid-1", "tcp", "reality", "sni", "pk");
    private static readonly string Pl = Fp("hysteria2", "pl.example", "8443", "pass", "", "tls", "", "");

    [Test]
    public async Task SharedVector_SameAsAndroid()
    {
        // The Android test computes the same parts; both must produce this exact value.
        await Fp("vless", "A.example ", "443").Should().BeEqualTo(Fp("VLESS", "a.example", "443"));
        await Fp("vless", "a.example", "443").Should().BeEqualTo("4dd0918faa9a2cca");
    }

    [Test]
    public async Task SameServersKeepIds_WhateverTheOrder()
    {
        var old = new List<IdEntry> { E("o1", De, "Германия"), E("o2", Nl, "Нидерланды"), E("o3", Pl, "Польша | Игровой 🔥") };
        var fresh = new List<IdEntry> { E("n1", Pl, "Польша | Игровой 🔥"), E("n2", De, "Германия"), E("n3", Nl, "Нидерланды") };
        var r = ServerIdentity.Reuse(fresh, old);
        await r["n1"].Should().BeEqualTo("o3");
        await r["n2"].Should().BeEqualTo("o1");
        await r["n3"].Should().BeEqualTo("o2");
    }

    [Test]
    public async Task MovedServer_FoundByUniqueName_DuplicateNamesNotGuessed()
    {
        var moved = Fp("vless", "de2.example");
        var r = ServerIdentity.Reuse([E("n", moved, "🇩🇪 Германия")], [E("o", De, "Германия")]);
        await r["n"].Should().BeEqualTo("o");
        var r2 = ServerIdentity.Reuse([E("n1", Fp("c"), "X"), E("n2", Fp("d"), "X")], [E("o1", Fp("a"), "X"), E("o2", Fp("b"), "X")]);
        await r2.Count.Should().BeEqualTo(0);
    }

    [Test]
    public async Task NameKey_DropsDecorations()
    {
        await ServerIdentity.NameKey("🇩🇪  Германия | Игровой 🔥").Should().BeEqualTo("германия | игровой");
        await ServerIdentity.NameKey("💀АНТИЖАЛОСТИ.НЕТ💀").Should().BeEqualTo("антижалости.нет");
        await ServerIdentity.NameKey("🔥🔥").Should().BeEqualTo(string.Empty);
    }

    [Test]
    public async Task Favorites_AreRemappedToTheNewIds()
    {
        var map = new Dictionary<string, string> { ["new1"] = "old1" };
        await ServerIdentity.RemapIds(["old1", "gone", "other"], map).Should().BeEquivalentTo(new List<string> { "new1", "gone", "other" });
    }
}

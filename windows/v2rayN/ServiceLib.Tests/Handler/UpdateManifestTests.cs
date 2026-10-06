using System.Security.Cryptography;

namespace ServiceLib.Tests.Handler;

/// <summary>The same vectors and cases as Android UpdateManifestTest.</summary>
public class UpdateManifestTests
{
    private static string Dir()
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null && !Directory.Exists(Path.Combine(dir.FullName, "shared", "test-vectors", "update")))
        {
            dir = dir.Parent;
        }
        return Path.Combine(dir!.FullName, "shared", "test-vectors", "update");
    }

    private static readonly string V = Dir();
    private static readonly byte[] ManifestBytes = File.ReadAllBytes(Path.Combine(V, "manifest.json"));
    private static readonly byte[] Sig = File.ReadAllBytes(Path.Combine(V, "manifest.json.sig"));
    private static readonly byte[] OtherSig = File.ReadAllBytes(Path.Combine(V, "manifest.other.sig"));
    private static readonly byte[] Key = Convert.FromBase64String(File.ReadAllText(Path.Combine(V, "test-key.pub.b64")).Trim());
    private static readonly byte[] OtherKey = Convert.FromBase64String(File.ReadAllText(Path.Combine(V, "other-key.pub.b64")).Trim());
    private static readonly byte[] Payload = File.ReadAllBytes(Path.Combine(V, "payload.bin"));
    private static readonly string Sha = Convert.ToHexString(SHA256.HashData(Payload)).ToLowerInvariant();

    private static UpdateManifest.Result Check(byte[]? m = null, byte[]? s = null, byte[][]? keys = null, string name = "FlowVeil-android.apk",
        long? size = null, string? hash = null, int? tag = 127, int current = 126, bool strict = false, bool noManifest = false, bool noSig = false) =>
        UpdateManifest.Check(noManifest ? null : m ?? ManifestBytes, noSig ? null : s ?? Sig, keys ?? [Key], name, size ?? Payload.Length, hash ?? Sha, tag, current, strict);

    private static UpdateManifest.Reason? Why(UpdateManifest.Result r) => (r as UpdateManifest.Result.Rejected)?.Reason;

    [Test]
    public async Task GoodAndSpareKeys()
    {
        await ((Check() as UpdateManifest.Result.Verified)?.Signed).Should().BeEqualTo(true);
        await ((Check(keys: [OtherKey, Key]) as UpdateManifest.Result.Verified)?.Signed).Should().BeEqualTo(true);
    }

    [Test]
    public async Task BadSignatures()
    {
        await Why(Check(keys: [OtherKey])).Should().BeEqualTo(UpdateManifest.Reason.BadSignature);
        await Why(Check(s: OtherSig)).Should().BeEqualTo(UpdateManifest.Reason.BadSignature);
        await Why(Check(s: Sig[..^3])).Should().BeEqualTo(UpdateManifest.Reason.BadSignature);
        var changed = System.Text.Encoding.UTF8.GetBytes(System.Text.Encoding.UTF8.GetString(ManifestBytes).Replace("\"build\": 127", "\"build\": 128"));
        await Why(Check(m: changed, tag: 128)).Should().BeEqualTo(UpdateManifest.Reason.BadSignature);
    }

    [Test]
    public async Task UnsignedAndMissing()
    {
        await ((Check(s: []) as UpdateManifest.Result.Verified)?.Signed).Should().BeEqualTo(false);
        await Why(Check(s: [], strict: true)).Should().BeEqualTo(UpdateManifest.Reason.Unsigned);
        await (Check(noManifest: true, noSig: true) is UpdateManifest.Result.Missing).Should().BeTrue();
        await Why(Check(noManifest: true, noSig: true, strict: true)).Should().BeEqualTo(UpdateManifest.Reason.Unsigned);
        await ((Check(keys: []) as UpdateManifest.Result.Verified)?.Signed).Should().BeEqualTo(false);
    }

    [Test]
    public async Task WrongFile()
    {
        await Why(Check(name: "FlowVeil-android-arm64.apk")).Should().BeEqualTo(UpdateManifest.Reason.NotInManifest);
        await Why(Check(size: 1)).Should().BeEqualTo(UpdateManifest.Reason.SizeMismatch);
        await Why(Check(hash: new string('0', 64))).Should().BeEqualTo(UpdateManifest.Reason.HashMismatch);
        await Why(Check(tag: 126)).Should().BeEqualTo(UpdateManifest.Reason.BuildMismatch);
        await Why(Check(current: 127)).Should().BeEqualTo(UpdateManifest.Reason.Rollback);
        await Why(Check(current: 200)).Should().BeEqualTo(UpdateManifest.Reason.Rollback);
    }

    [Test]
    public async Task Malformed()
    {
        await Why(Check(m: "{}"u8.ToArray(), noSig: true)).Should().BeEqualTo(UpdateManifest.Reason.Malformed);
        var a = new string('a', 64);
        await (UpdateManifest.Parse($"{{\"schema\":2,\"tag\":\"v1\",\"build\":1,\"files\":[{{\"name\":\"a\",\"size\":1,\"sha256\":\"{a}\"}}]}}") == null).Should().BeTrue();
        await (UpdateManifest.Parse("{\"schema\":1,\"tag\":\"v1\",\"build\":1,\"files\":[{\"name\":\"a\",\"size\":1,\"sha256\":\"xyz\"}]}") == null).Should().BeTrue();
        await (UpdateManifest.Parse($"{{\"schema\":1,\"tag\":\"v1\",\"build\":1,\"minBuild\":5,\"files\":[{{\"name\":\"a\",\"size\":1,\"sha256\":\"{a}\"}}]}}") == null).Should().BeTrue();
        await (UpdateManifest.Parse($"{{\"schema\":1,\"tag\":\"v1\",\"build\":1,\"files\":[{{\"name\":\"a\",\"size\":1,\"sha256\":\"{a.ToUpperInvariant()}\"}}]}}") != null).Should().BeTrue();
    }
}

using System.Net;
using System.Net.Sockets;
using System.Text;
using ServiceLib.Handler;

namespace ServiceLib.Tests.Handler;

public class PairTests
{
    private sealed class Clock
    {
        public DateTime Now = new(2026, 1, 1, 12, 0, 0, DateTimeKind.Utc);
    }

    private static PairSession NewSession(TimeSpan? ttl = null, Clock? clock = null)
    {
        clock ??= new Clock();
        return new PairSession(ttl, () => clock.Now);
    }

    [Test]
    public async Task Token_WorksOnceThenIsUsed()
    {
        var s = NewSession();
        await s.Check(s.Token).Should().BeEqualTo(PairCheck.Ok);
        await s.TryConsume().Should().BeTrue();
        await s.TryConsume().Should().BeFalse();
        await s.Check(s.Token).Should().BeEqualTo(PairCheck.Used);
    }

    [Test]
    public async Task Session_ExpiresAfterTtl()
    {
        var clock = new Clock();
        var s = NewSession(TimeSpan.FromMinutes(5), clock);
        clock.Now = clock.Now.AddMinutes(4).AddSeconds(59);
        await s.Check(s.Token).Should().BeEqualTo(PairCheck.Ok);
        clock.Now = clock.Now.AddSeconds(2);
        await s.Check(s.Token).Should().BeEqualTo(PairCheck.Expired);
        await s.TryConsume().Should().BeFalse();
    }

    [Test]
    public async Task FiveWrongGuesses_LockTheSessionEvenForTheRightToken()
    {
        var s = NewSession();
        for (var i = 0; i < PairSession.MaxWrongAttempts - 1; i++)
        {
            await s.Check("nope").Should().BeEqualTo(PairCheck.Bad);
        }
        await s.Check("nope").Should().BeEqualTo(PairCheck.Locked);
        await s.Check(s.Token).Should().BeEqualTo(PairCheck.Locked);
    }

    [Test]
    public async Task Code_IsSixDigitsAndChecked()
    {
        var s = NewSession();
        await s.Code.Length.Should().BeEqualTo(6);
        await s.Code.All(char.IsDigit).Should().BeTrue();
        await s.Check(s.Code, true).Should().BeEqualTo(PairCheck.Ok);
        await s.Check("0000000", true).Should().BeEqualTo(PairCheck.Bad);
    }

    [Test]
    public async Task Qr_RoundTrips_AndRejectsForeignAddresses()
    {
        var s = NewSession();
        var url = PairQr.Build("192.168.1.10", 5123, s);
        var parsed = PairQr.Parse(url);
        await (parsed != null).Should().BeTrue();
        await parsed!.Host.Should().BeEqualTo("192.168.1.10");
        await parsed.Port.Should().BeEqualTo(5123);
        await parsed.Sid.Should().BeEqualTo(s.Sid);
        await parsed.Token.Should().BeEqualTo(s.Token);
        await PairQr.Parse("https://example.com/p/abc#t=1&k=2&v=1").Should().BeNull();
        await PairQr.Parse("http://192.168.1.10:5123/p/abc").Should().BeNull();
        await PairQr.Parse("http://192.168.1.10:5123/other/abc#t=1&k=AAAA&v=1").Should().BeNull();
        await PairQr.Parse("hello").Should().BeNull();
        // key of wrong length
        await PairQr.Parse("http://192.168.1.10:5123/p/abc#t=1&k=AAAA&v=1").Should().BeNull();
    }

    [Test]
    public async Task SchemeWhitelist()
    {
        await PairPayload.IsAllowedLink("https://p.example/sub/x").Should().BeTrue();
        await PairPayload.IsAllowedLink("vless://id@host:443?type=tcp#n").Should().BeTrue();
        await PairPayload.IsAllowedLink("flowveil://add?url=https%3A%2F%2Fp.example").Should().BeTrue();
        await PairPayload.IsAllowedLink("flowveil://other").Should().BeFalse();
        await PairPayload.IsAllowedLink("javascript:alert(1)").Should().BeFalse();
        await PairPayload.IsAllowedLink("file:///etc/passwd").Should().BeFalse();
        await PairPayload.IsAllowedLink("https://a.example/x y").Should().BeFalse();
        await PairPayload.IsAllowedLink("http://10.0.0.5:5123/p/abc#t=1&k=2&v=1").Should().BeFalse();
    }

    [Test]
    public async Task Payload_Limits()
    {
        await (PairPayload.Parse("{\"type\":\"subscription\",\"items\":[\"https://p.example/s\"],\"name\":\"n\"}", out _) != null).Should().BeTrue();
        await (PairPayload.Parse("{\"type\":\"subscription\",\"items\":[]}", out _) == null).Should().BeTrue();
        await (PairPayload.Parse("{\"type\":\"x\",\"items\":[\"https://p.example/s\"]}", out _) == null).Should().BeTrue();
        await (PairPayload.Parse("{\"type\":\"subscription\",\"items\":[\"ftp://p.example/s\"]}", out _) == null).Should().BeTrue();
        var many = string.Join(",", Enumerable.Range(0, PairPayload.MaxItems + 1).Select(i => $"\"https://p.example/{i}\""));
        await (PairPayload.Parse($"{{\"type\":\"subscription\",\"items\":[{many}]}}", out _) == null).Should().BeTrue();
        await (PairPayload.Parse(new string('x', PairPayload.MaxBodyBytes + 1), out _) == null).Should().BeTrue();
    }

    /// <summary>Same vector is checked in the Android unit test (PairCryptoTest).</summary>
    [Test]
    public async Task Crypto_SharedVector()
    {
        var key = Enumerable.Range(0, 32).Select(i => (byte)i).ToArray();
        var nonce = Enumerable.Range(0, 12).Select(i => (byte)(0xA0 + i)).ToArray();
        var wire = PairCrypto.Encrypt(key, "sid-test", "{\"type\":\"subscription\",\"items\":[\"https://p.example/s\"]}", nonce);
        await wire.Should().BeEqualTo(PairVectors.Wire);
        await PairCrypto.Decrypt(key, "sid-test", PairVectors.Wire).Should().BeEqualTo("{\"type\":\"subscription\",\"items\":[\"https://p.example/s\"]}");
        await (PairCrypto.Decrypt(key, "other-sid", PairVectors.Wire) == null).Should().BeTrue();
        var badKey = (byte[])key.Clone();
        badKey[0] ^= 1;
        await (PairCrypto.Decrypt(badKey, "sid-test", PairVectors.Wire) == null).Should().BeTrue();
    }

    [Test]
    public async Task Server_AcceptsOncePlainAndEncrypted_AndRejectsOversize()
    {
        using var server = new PairServer();
        PairPayload? got = null;
        server.Received += (p, _) => got = p;
        var url = server.Start(new PairAdapter("lo", IPAddress.Loopback));
        var qr = PairQr.Parse(url)!;
        var body = "{\"type\":\"subscription\",\"items\":[\"https://p.example/s\"],\"name\":\"Тест\"}";

        var wrong = await Post(qr.Port, $"/p/{qr.Sid}/send", "{\"t\":\"bad\",\"enc\":0,\"data\":" + body + "}");
        await wrong.Should().Contain(" 403 ");
        var ok = await Post(qr.Port, $"/p/{qr.Sid}/send", "{\"t\":\"" + qr.Token + "\",\"enc\":1,\"device\":\"Pixel\",\"data\":\"" + PairCrypto.Encrypt(server.Session!.Key, qr.Sid, body) + "\"}");
        await ok.Should().Contain(" 200 ");
        await (got != null && got.Items[0] == "https://p.example/s").Should().BeTrue();
    }

    [Test]
    public async Task Server_RejectsHugeBody()
    {
        using var server = new PairServer();
        var qr = PairQr.Parse(server.Start(new PairAdapter("lo", IPAddress.Loopback)))!;
        var resp = await Post(qr.Port, $"/p/{qr.Sid}/send", new string('a', PairPayload.MaxBodyBytes + 5000));
        await resp.Should().Contain(" 413 ");
    }

    [Test]
    public async Task Server_AnswersOnlyGetAndPost()
    {
        using var server = new PairServer();
        var qr = PairQr.Parse(server.Start(new PairAdapter("lo", IPAddress.Loopback)))!;
        var resp = await Raw(qr.Port, $"PUT /p/{qr.Sid}/send HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n");
        await resp.Should().Contain(" 405 ");
        var page = await Raw(qr.Port, $"GET /p/{qr.Sid} HTTP/1.1\r\nHost: x\r\n\r\n");
        await page.Should().Contain("FlowVeil");
    }

    private static Task<string> Post(int port, string path, string body) =>
        Raw(port, $"POST {path} HTTP/1.1\r\nHost: x\r\nContent-Length: {Encoding.UTF8.GetByteCount(body)}\r\n\r\n{body}");

    private static async Task<string> Raw(int port, string request)
    {
        using var client = new TcpClient();
        await client.ConnectAsync(IPAddress.Loopback, port);
        var stream = client.GetStream();
        await stream.WriteAsync(Encoding.UTF8.GetBytes(request));
        var buffer = new byte[65536];
        var sb = new StringBuilder();
        try
        {
            int n;
            while ((n = await stream.ReadAsync(buffer)) > 0)
            {
                sb.Append(Encoding.UTF8.GetString(buffer, 0, n));
            }
        }
        catch (IOException)
        {
        }
        return sb.ToString();
    }
}

internal static class PairVectors
{
    public const string Wire = "oKGio6SlpqeoqaqrnToIVDWuIIVAFvKxdBmytwDYMH_8lW5O9XpD6wyJT1rwHjOL31FpEnDsKq1xG-6JK35pO0CNZ9Ao1PBK-WF-h3Qw15V5oUo";
}

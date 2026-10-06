namespace ServiceLib.Handler;

/// <summary>The address inside the QR: http://ip:port/p/SID#t=TOKEN&k=KEY&v=1 (secrets live in the fragment, so they never travel in a request line).</summary>
public sealed record PairQr(string Host, int Port, string Sid, string Token, string Key)
{
    /// <summary>The plain address (also what a manual entry or an older phone understands).</summary>
    public static string BuildHttp(string host, int port, PairSession s) =>
        $"http://{host}:{port}/p/{s.Sid}#t={s.Token}&k={PairCrypto.B64.Encode(s.Key)}&v=1";

    /// <summary>What the QR shows: an app link, so a scanner that opens links offers FlowVeil, and FlowVeil's own scanner shows its menu.</summary>
    public static string Build(string host, int port, PairSession s) =>
        "flowveil://pair?q=" + Uri.EscapeDataString(BuildHttp(host, port, s));

    private static string? Unwrap(string? text)
    {
        var t = text?.Trim();
        if (t is null || !t.StartsWith("flowveil://pair", StringComparison.OrdinalIgnoreCase))
        {
            return t;
        }
        var i = t.IndexOf("?q=", StringComparison.Ordinal);
        if (i < 0)
        {
            return null;
        }
        var q = t[(i + 3)..];
        var amp = q.IndexOf('&');
        return Uri.UnescapeDataString(amp >= 0 ? q[..amp] : q);
    }

    public static PairQr? Parse(string? text)
    {
        text = Unwrap(text);
        if (string.IsNullOrWhiteSpace(text) || !Uri.TryCreate(text.Trim(), UriKind.Absolute, out var uri) || uri.Scheme != "http")
        {
            return null;
        }
        var segments = uri.AbsolutePath.Split('/', StringSplitOptions.RemoveEmptyEntries);
        if (segments.Length != 2 || segments[0] != "p" || uri.Port <= 0)
        {
            return null;
        }
        var fragment = uri.Fragment.TrimStart('#').Split('&').Select(p => p.Split('=', 2)).Where(p => p.Length == 2).ToDictionary(p => p[0], p => p[1]);
        if (!fragment.TryGetValue("t", out var t) || !fragment.TryGetValue("k", out var k) || fragment.GetValueOrDefault("v") != "1")
        {
            return null;
        }
        var key = PairCrypto.B64.Decode(k);
        return key is { Length: PairCrypto.KeySize } && t.Length > 0 ? new PairQr(uri.Host, uri.Port, segments[1], t, k) : null;
    }
}

namespace ServiceLib.Handler;

/// <summary>
/// Text from a QR code, the clipboard or a link, unwrapped from other clients' import wrappers (same rules and tests as
/// Android net/ImportSource.kt): flowveil:// and v2rayng://install-sub → the link inside; happ://add/&lt;link&gt; → the link;
/// happ://crypt… is encrypted for Happ only and is not opened; anything else is passed on unchanged.
/// </summary>
public static class ImportSource
{
    public const string HappEncryptedMessage = "Это зашифрованная ссылка Happ — открыть её может только Happ. Попросите у провайдера обычную ссылку подписки (начинается с https://).";

    public abstract record Result
    {
        public sealed record Text(string Value) : Result;

        public sealed record HappEncrypted : Result;

        public sealed record Empty : Result;
    }

    public static Result Normalize(string? raw)
    {
        var t = raw?.Trim() ?? string.Empty;
        if (t.Length == 0)
        {
            return new Result.Empty();
        }
        if (t.StartsWith("happ://crypt", StringComparison.OrdinalIgnoreCase))
        {
            return new Result.HappEncrypted();
        }
        if (t.StartsWith("happ://add/", StringComparison.OrdinalIgnoreCase))
        {
            var inner = t["happ://add/".Length..].Trim();
            if (inner.Contains("%3A", StringComparison.OrdinalIgnoreCase) || inner.Contains("%2F", StringComparison.OrdinalIgnoreCase))
            {
                try
                {
                    inner = Uri.UnescapeDataString(inner);
                }
                catch
                {
                    // keep as is
                }
            }
            return inner.Length == 0 ? new Result.Empty() : new Result.Text(inner);
        }
        if (t.StartsWith("flowveil://", StringComparison.OrdinalIgnoreCase) || t.StartsWith("v2rayng://install-sub", StringComparison.OrdinalIgnoreCase))
        {
            var invite = InviteLink.Parse(t);
            return new Result.Text(invite?.Link ?? t);
        }
        if (!t.Contains('\n'))
        {
            var inner = UnwrapGeneric(t);
            if (inner != null)
            {
                return new Result.Text(inner);
            }
        }
        return new Result.Text(t);
    }

    private static readonly HashSet<string> ServerSchemes =
    [
        "http", "https", "vless", "vmess", "trojan", "ss", "ssr", "shadowsocks", "hysteria", "hysteria2", "hy2", "tuic",
        "wireguard", "wg", "socks", "socks4", "socks5", "anytls", "naive", "juicity", "mieru",
    ];

    private static readonly string[] QueryKeys = ["url", "link", "config", "sub", "subscription", "uri", "profile"];

    private static string Decode(string v) => v.Contains('%') ? Uri.UnescapeDataString(v.Replace("+", "%2B")) : v;

    private static bool IsWebLink(string v) => v.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || v.StartsWith("https://", StringComparison.OrdinalIgnoreCase);

    /// <summary>Import links of other apps that carry an ordinary subscription address (hiddify://import/…, clash://install-config?url=…, sub://base64 …).</summary>
    private static string? UnwrapGeneric(string t)
    {
        var m = System.Text.RegularExpressions.Regex.Match(t, "^([A-Za-z][A-Za-z0-9+.-]*)://");
        if (!m.Success)
        {
            return null;
        }
        var scheme = m.Groups[1].Value.ToLowerInvariant();
        if (ServerSchemes.Contains(scheme))
        {
            return null;
        }
        var rest = t[(scheme.Length + 3)..];
        try
        {
            if (scheme == "sub")
            {
                var payload = rest.Split('#')[0].Split('?')[0].TrimEnd('/').Replace('-', '+').Replace('_', '/');
                payload = payload.PadRight(payload.Length + (4 - payload.Length % 4) % 4, '=');
                var link = System.Text.Encoding.UTF8.GetString(Convert.FromBase64String(payload)).Trim();
                if (IsWebLink(link) && !link.Any(char.IsControl))
                {
                    return link;
                }
            }
        }
        catch
        {
            // not base64: fall through
        }
        var qIndex = rest.IndexOf('?');
        if (qIndex >= 0)
        {
            foreach (var pair in rest[(qIndex + 1)..].Split('#')[0].Split('&'))
            {
                var eq = pair.IndexOf('=');
                if (eq > 0 && QueryKeys.Contains(pair[..eq].ToLowerInvariant()))
                {
                    var v = Decode(pair[(eq + 1)..]).Trim();
                    if (IsWebLink(v))
                    {
                        return v;
                    }
                }
            }
        }
        var slash = rest.IndexOf('/');
        if (slash >= 0)
        {
            var candidate = Decode(rest[(slash + 1)..]).Trim();
            if (IsWebLink(candidate))
            {
                return candidate;
            }
        }
        return null;
    }
}

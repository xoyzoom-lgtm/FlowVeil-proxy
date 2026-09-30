using System.Text.RegularExpressions;

namespace ServiceLib.Common;

/// <summary>Hides what must not end up in a copied log: proxy links, subscription addresses (the token is in the path) and ids.</summary>
public static partial class LogMask
{
    [GeneratedRegex(@"\b(vless|vmess|trojan|ss|hysteria2|hy2|tuic|wireguard|anytls)://\S+", RegexOptions.IgnoreCase)]
    private static partial Regex ProxyLink();

    [GeneratedRegex(@"\b(https?://[^/\s?#]+)[/?#]\S*", RegexOptions.IgnoreCase)]
    private static partial Regex WebLink();

    [GeneratedRegex(@"\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b")]
    private static partial Regex Uuid();

    public static string Apply(string text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return text;
        }
        text = ProxyLink().Replace(text, m => m.Groups[1].Value + "://***");
        text = WebLink().Replace(text, "$1/***");
        return Uuid().Replace(text, m => m.Value[..8] + "-****");
    }
}

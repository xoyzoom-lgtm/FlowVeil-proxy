using System.Text.Json;

namespace ServiceLib.Handler;

/// <summary>What the phone sends: a list of links (subscription addresses or single server links), checked against a scheme whitelist and size limits.</summary>
public sealed record PairPayload(string Type, string? Name, IReadOnlyList<string> Items)
{
    public const int MaxBodyBytes = 64 * 1024;
    public const int MaxItems = 200;
    public const int MaxItemLength = 4096;

    private static readonly string[] Schemes = ["http", "https", "vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "tuic", "wireguard"];

    public static bool IsAllowedLink(string link)
    {
        if (link.Length is < 8 or > MaxItemLength || link.Any(c => char.IsWhiteSpace(c) || char.IsControl(c)))
        {
            return false;
        }
        var i = link.IndexOf("://", StringComparison.Ordinal);
        if (i <= 0)
        {
            return false;
        }
        var scheme = link[..i].ToLowerInvariant();
        // our own pairing address is never a subscription
        if (link.Contains("#t=") && link.Contains("&k=") && link.Contains("/p/"))
        {
            return false;
        }
        if (scheme == "flowveil")
        {
            return link.StartsWith("flowveil://add", StringComparison.OrdinalIgnoreCase);
        }
        return Schemes.Contains(scheme);
    }

    /// <summary>The parsed payload, or null (with the reason for the log) when it is not what we accept.</summary>
    public static PairPayload? Parse(string json, out string error)
    {
        error = string.Empty;
        try
        {
            if (json.Length > MaxBodyBytes)
            {
                error = "too large";
                return null;
            }
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            var type = root.TryGetProperty("type", out var t) ? t.GetString() : null;
            if (type is not ("subscription" or "config") || !root.TryGetProperty("items", out var arr) || arr.ValueKind != JsonValueKind.Array)
            {
                error = "bad shape";
                return null;
            }
            var items = new List<string>();
            foreach (var e in arr.EnumerateArray())
            {
                var s = e.ValueKind == JsonValueKind.String ? e.GetString()?.Trim() : null;
                if (s == null || !IsAllowedLink(s))
                {
                    error = "link not allowed";
                    return null;
                }
                items.Add(s);
                if (items.Count > MaxItems)
                {
                    error = "too many items";
                    return null;
                }
            }
            if (items.Count == 0)
            {
                error = "empty";
                return null;
            }
            var name = root.TryGetProperty("name", out var n) && n.ValueKind == JsonValueKind.String ? n.GetString() : null;
            if (name != null && name.Length > 100)
            {
                name = name[..100];
            }
            return new PairPayload(type, name, items);
        }
        catch (JsonException)
        {
            error = "not json";
            return null;
        }
    }
}

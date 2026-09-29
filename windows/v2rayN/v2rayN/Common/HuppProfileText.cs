using System.Text.Json.Nodes;

namespace v2rayN.Common;

/// <summary>Happ-style display text for server rows: flag code, clean name and "VLESS / TCP / REALITY".</summary>
public static class HuppProfileText
{
    private const int RegionalIndicatorA = 0x1F1E6;
    private const int RegionalIndicatorZ = 0x1F1FF;
    private static readonly Dictionary<string, (DateTime Stamp, string Text)> _customCache = new();
    private static readonly object _lock = new();

    /// <summary>Splits a leading/embedded flag emoji out of the remarks: ("DE", "Germany").</summary>
    public static (string Code, string Name) SplitFlag(string? remarks)
    {
        if (remarks.IsNullOrEmpty())
        {
            return (string.Empty, string.Empty);
        }

        var runes = remarks.EnumerateRunes().ToList();
        for (var i = 0; i + 1 < runes.Count; i++)
        {
            if (IsRegional(runes[i]) && IsRegional(runes[i + 1]))
            {
                var code = $"{(char)('A' + runes[i].Value - RegionalIndicatorA)}{(char)('A' + runes[i + 1].Value - RegionalIndicatorA)}";
                var rest = new StringBuilder();
                for (var j = 0; j < runes.Count; j++)
                {
                    if (j != i && j != i + 1)
                    {
                        rest.Append(runes[j].ToString());
                    }
                }
                var name = rest.ToString().Trim(' ', '|', '-', '_', '·', '\t');
                return (code, name.IsNullOrEmpty() ? code : name);
            }
        }
        return (string.Empty, remarks.Trim());
    }

    /// <summary>
    /// Text that WPF can draw: drops flag pairs (shown as boxed letters), emoji joiners,
    /// variation selectors, skin tones, tags and other invisible or unrenderable code points.
    /// </summary>
    public static string CleanForDisplay(string? text)
    {
        if (text.IsNullOrEmpty())
        {
            return string.Empty;
        }
        var sb = new StringBuilder(text!.Length);
        foreach (var rune in text!.EnumerateRunes())
        {
            var v = rune.Value;
            var drop = IsRegional(rune)
                || v is 0x200D or 0xFE0E or 0xFE0F or 0x20E3 or 0xFFFD
                || v is >= 0x1F3FB and <= 0x1F3FF
                || v is >= 0xE0000 and <= 0xE007F
                || (Rune.GetUnicodeCategory(rune) == UnicodeCategory.Control && v is not (10 or 9))
                || Rune.GetUnicodeCategory(rune) is UnicodeCategory.PrivateUse or UnicodeCategory.Surrogate or UnicodeCategory.OtherNotAssigned;
            sb.Append(drop ? " " : rune.ToString());
        }
        // Collapse the gaps left behind, line by line.
        var lines = sb.ToString().Replace("\r", string.Empty).Split('\n')
            .Select(l => System.Text.RegularExpressions.Regex.Replace(l, "[ \t]{2,}", " ").Trim());
        return System.Text.RegularExpressions.Regex.Replace(string.Join("\n", lines), "\n{3,}", "\n\n").Trim();
    }

    /// <summary>
    /// Short protocol chips for a server: "VLESS", "REALITY" / "HY2", "TLS". The technical "JSON"
    /// marker of custom profiles is shown only in developer mode.
    /// </summary>
    public static IReadOnlyList<string> Chips(ProfileItemModel? item, bool devMode)
    {
        if (item == null)
        {
            return [];
        }
        switch (item.ConfigType)
        {
            case EConfigType.PolicyGroup:
                return ["GROUP"];
            case EConfigType.ProxyChain:
                return ["CHAIN"];
            case EConfigType.Custom:
                {
                    var parts = Describe(item).Split(" / ", StringSplitOptions.RemoveEmptyEntries).ToList();
                    return ShortChips(parts, devMode);
                }
        }
        var list = new List<string> { item.ConfigType.ToString().ToUpperInvariant() };
        if (item.Network.IsNotEmpty())
        {
            list.Add(item.Network.ToUpperInvariant());
        }
        if (item.StreamSecurity.IsNotEmpty())
        {
            list.Add(item.StreamSecurity.ToUpperInvariant());
        }
        if (item.ConfigType == EConfigType.Outbound)
        {
            list.Add("JSON");
        }
        return ShortChips(list, devMode);
    }

    private static IReadOnlyList<string> ShortChips(List<string> parts, bool devMode)
    {
        var result = new List<string>();
        foreach (var raw in parts)
        {
            var p = raw switch
            {
                "HYSTERIA2" or "HYSTERIA" => "HY2",
                "SHADOWSOCKS" => "SS",
                "WIREGUARD" => "WG",
                _ => raw,
            };
            // Plain TCP/RAW and QUIC-based "hysteria" transports say nothing to the user.
            if (p is "TCP" or "RAW" or "NONE" or "UDP" || (p == "HY2" && result.Contains("HY2")))
            {
                continue;
            }
            if (p == "JSON" && !devMode)
            {
                continue;
            }
            if (!result.Contains(p))
            {
                result.Add(p);
            }
        }
        return result.Take(devMode ? 4 : 3).ToList();
    }

    private static bool IsRegional(Rune rune) => rune.Value is >= RegionalIndicatorA and <= RegionalIndicatorZ;

    public static string Describe(ProfileItemModel? item)
    {
        if (item == null)
        {
            return string.Empty;
        }

        switch (item.ConfigType)
        {
            case EConfigType.Custom:
                return DescribeCustom(item.Address);

            case EConfigType.PolicyGroup:
                return "GROUP";

            case EConfigType.ProxyChain:
                return "CHAIN";
        }

        var parts = new List<string> { item.ConfigType.ToString().ToUpperInvariant() };
        if (item.Network.IsNotEmpty())
        {
            parts.Add(item.Network.ToUpperInvariant());
        }
        if (item.StreamSecurity.IsNotEmpty())
        {
            parts.Add(item.StreamSecurity.ToUpperInvariant());
        }
        if (item.ConfigType == EConfigType.Outbound)
        {
            parts.Add("JSON");
        }
        return string.Join(" / ", parts);
    }

    private static string DescribeCustom(string? fileName)
    {
        if (fileName.IsNullOrEmpty())
        {
            return "JSON";
        }

        try
        {
            var path = Utils.GetConfigPath(fileName);
            if (!File.Exists(path))
            {
                return "JSON";
            }

            var stamp = File.GetLastWriteTimeUtc(path);
            lock (_lock)
            {
                if (_customCache.TryGetValue(path, out var cached) && cached.Stamp == stamp)
                {
                    return cached.Text;
                }
            }

            var text = ParseCustom(File.ReadAllText(path));
            lock (_lock)
            {
                _customCache[path] = (stamp, text);
            }
            return text;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(HuppProfileText), ex);
            return "JSON";
        }
    }

    private static string ParseCustom(string json)
    {
        var root = JsonNode.Parse(json, documentOptions: new System.Text.Json.JsonDocumentOptions
        {
            CommentHandling = System.Text.Json.JsonCommentHandling.Skip,
            AllowTrailingCommas = true,
        });
        if (root?["outbounds"] is not JsonArray outbounds)
        {
            return "JSON";
        }

        foreach (var outbound in outbounds)
        {
            var protocol = outbound?["protocol"]?.GetValue<string>();
            if (protocol.IsNullOrEmpty() || protocol is "freedom" or "blackhole" or "dns" or "loopback")
            {
                continue;
            }

            var parts = new List<string> { protocol.ToUpperInvariant() };
            var stream = outbound?["streamSettings"];
            var network = stream?["network"]?.GetValue<string>();
            parts.Add((network.IsNullOrEmpty() ? "tcp" : network).ToUpperInvariant());
            var security = stream?["security"]?.GetValue<string>();
            if (security.IsNotEmpty() && security != "none")
            {
                parts.Add(security.ToUpperInvariant());
            }
            parts.Add("JSON");
            return string.Join(" / ", parts);
        }
        return "JSON";
    }
}

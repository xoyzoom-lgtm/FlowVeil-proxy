using System.Text;

namespace ServiceLib.Handler;

/// <summary>Small helpers for what a server row shows: tag chips instead of "HYSTERIA / HYSTERIA / TLS / JSON", and provider text that WPF can draw.</summary>
public static class ServerTags
{
    /// <summary>"VLESS / TCP / REALITY / JSON" → ["VLESS", "REALITY"]: repeated words and the default TCP are dropped, "JSON" only in developer mode.</summary>
    public static List<string> From(string? description, bool devMode)
    {
        var tags = new List<string>();
        foreach (var part in (description ?? string.Empty).Split('/', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var tag = part.ToUpperInvariant();
            if (tag == "TCP" || (tag == "JSON" && !devMode) || tags.Contains(tag))
            {
                continue;
            }
            tags.Add(tag);
        }
        return tags;
    }
}

/// <summary>WPF draws emoji in one colour and shows a square for symbols its fonts lack; this keeps the text readable.</summary>
public static class TextSanitizer
{
    /// <summary>Removes variation selectors and joiners, and characters newer than the emoji Windows 10 can draw (they show as squares); everything else stays.</summary>
    public static string ForWpf(string? text)
    {
        if (string.IsNullOrEmpty(text))
        {
            return string.Empty;
        }
        var sb = new StringBuilder(text.Length);
        foreach (var rune in text.EnumerateRunes())
        {
            var v = rune.Value;
            if (v is 0xFE0F or 0xFE0E or 0x200D or 0x20E3 or (>= 0x1F3FB and <= 0x1F3FF))
            {
                continue;
            }
            if (v >= 0x1FA70 || (v >= 0xE0000))
            {
                sb.Append(' ');
                continue;
            }
            sb.Append(rune.ToString());
        }
        // collapse the spaces the removal can leave
        var result = System.Text.RegularExpressions.Regex.Replace(sb.ToString(), @"[ \t]{2,}", " ");
        return result.Trim();
    }
}

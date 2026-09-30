using System.Text.RegularExpressions;

namespace ServiceLib.Handler;

/// <summary>
/// Invite links from providers: flowveil://add?url=&lt;link&gt;&amp;name=&lt;title&gt;, flowveil://add/&lt;link&gt;, flowveil://install-sub?url=...
/// The same rules as the Android app (net/InviteLink.kt).
/// </summary>
public static class InviteLink
{
    public const int MaxName = 60;

    public sealed record Invite(string Link, string? Name);

    public static Invite? Parse(string? raw)
    {
        if (raw.IsNullOrEmpty())
        {
            return null;
        }
        var full = raw.Trim();
        var schemeEnd = full.IndexOf("://", StringComparison.Ordinal);
        string afterScheme;
        if (schemeEnd >= 0)
        {
            afterScheme = full[(schemeEnd + 3)..];
        }
        else if (full.StartsWith("flowveil:", StringComparison.OrdinalIgnoreCase))
        {
            afterScheme = full["flowveil:".Length..].TrimStart('/');
        }
        else
        {
            return null;
        }
        if (afterScheme.Length == 0)
        {
            return null;
        }

        var hashAt = afterScheme.IndexOf('#');
        var outerFragment = hashAt >= 0 && hashAt < afterScheme.Length - 1 ? Decode(afterScheme[(hashAt + 1)..]) : null;
        var noFragment = hashAt >= 0 ? afterScheme[..hashAt] : afterScheme;
        var slash = noFragment.IndexOf('/');
        var question = noFragment.IndexOf('?');
        string? link = null;
        string? name = null;
        if (question >= 0 && (slash < 0 || question < slash))
        {
            foreach (var pair in noFragment[(question + 1)..].Split('&'))
            {
                var eq = pair.IndexOf('=');
                var key = (eq >= 0 ? pair[..eq] : pair).ToLowerInvariant();
                var value = eq >= 0 ? pair[(eq + 1)..] : string.Empty;
                if (key == "url" && link == null)
                {
                    link = DecodeLink(value);
                }
                else if (key == "name" && name == null)
                {
                    name = CleanName(Decode(value));
                }
            }
        }
        else if (slash >= 0)
        {
            link = DecodeLink(afterScheme[(slash + 1)..]);
        }
        if (link.IsNullOrEmpty())
        {
            return null;
        }
        // A single server link (vless://…#name) keeps its own fragment; only subscription addresses get a name.
        if (!link.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !link.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            return new Invite(outerFragment != null && !link.Contains('#') ? $"{link}#{outerFragment}" : link, null);
        }
        var linkHash = link.IndexOf('#');
        var hash = linkHash >= 0 ? link[(linkHash + 1)..] : null;
        var finalName = name ?? CleanName(outerFragment ?? (hash.IsNullOrEmpty() ? null : Decode(hash)));
        return new Invite(linkHash >= 0 ? link[..linkHash] : link, finalName);
    }

    /// <summary>A title from a link is shown to the user: no control characters, no extra blanks, at most <see cref="MaxName"/> characters.</summary>
    public static string? CleanName(string? value)
    {
        if (value == null)
        {
            return null;
        }
        var cleaned = Regex.Replace(new string(value.Where(c => !char.IsControl(c)).ToArray()), @"\s+", " ").Trim();
        if (cleaned.Length == 0)
        {
            return null;
        }
        return cleaned.Length > MaxName ? cleaned[..MaxName].TrimEnd() : cleaned;
    }

    private static string DecodeLink(string value)
    {
        var v = value.Trim();
        return v.Contains("%3A", StringComparison.OrdinalIgnoreCase) || v.Contains("%2F", StringComparison.OrdinalIgnoreCase) ? Decode(v) : v;
    }

    // "+" stays a plus: providers encode a space as %20.
    private static string Decode(string value)
    {
        try
        {
            return Uri.UnescapeDataString(value);
        }
        catch
        {
            return value;
        }
    }
}

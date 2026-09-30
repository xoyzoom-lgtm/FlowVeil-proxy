namespace ServiceLib.Common;

/// <summary>
/// Colour themes ported from the Happ theme-code format. Colours are "#RRGGBBAA" strings.
/// </summary>
public sealed record HappTheme(
    string Name,
    string Background,
    string Card,
    string Accent,
    string OnAccent,
    string Text,
    string SubText,
    string Secondary)
{
    /// <summary>Returns (r, g, b, a) bytes for a "#RRGGBB" or "#RRGGBBAA" colour, or null when malformed.</summary>
    public static (byte R, byte G, byte B, byte A)? Parse(string hex)
    {
        var h = hex.Trim().TrimStart('#');
        if (h.Length != 6 && h.Length != 8)
        {
            return null;
        }
        try
        {
            var r = Convert.ToByte(h[..2], 16);
            var g = Convert.ToByte(h[2..4], 16);
            var b = Convert.ToByte(h[4..6], 16);
            var a = h.Length == 8 ? Convert.ToByte(h[6..8], 16) : (byte)255;
            return (r, g, b, a);
        }
        catch (FormatException)
        {
            return null;
        }
    }

    public bool IsLight
    {
        get
        {
            var c = Parse(Background);
            if (c is null)
            {
                return false;
            }
            var (r, g, b, _) = c.Value;
            return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0 > 0.5;
        }
    }
}

public static class HappThemes
{
    public const string Prefix = "";

    // Theme names saved by older builds carried this prefix.
    private const string LegacyPrefix = "Happ · ";

    public static readonly IReadOnlyList<HappTheme> All =
    [
        new(Prefix + "iOS 27 Glass", "#EEF6FFFF", "#FFFFFFFF", "#007AFFFF", "#FFFFFFFF", "#111827FF", "#64748BFF", "#007AFFFF"),
        new(Prefix + "iOS Fog", "#E5E7EBFF", "#F9FAFBFF", "#111827FF", "#FFFFFFFF", "#111827FF", "#6B7280FF", "#111827FF"),
        new(Prefix + "Liquid Glass", "#F8FAFFFF", "#FFFFFFFF", "#6366F1FF", "#FFFFFFFF", "#111827FF", "#64748BFF", "#06B6D4FF"),
        new(Prefix + "Apple Pro Black", "#000000FF", "#1C1C1EFF", "#FFFFFFFF", "#000000FF", "#FFFFFFFF", "#8E8E93FF", "#0A84FFFF"),
        new(Prefix + "Dynamic Island", "#090014FF", "#170B2AFF", "#BF5AFFFF", "#FFFFFFFF", "#FFFFFFFF", "#B8A6CCFF", "#BF5AFFFF"),
        new(Prefix + "Pixel Material You", "#FDF6FFFF", "#FFFFFFFF", "#6750A4FF", "#FFFFFFFF", "#1D1B20FF", "#79747EFF", "#6750A4FF"),
        new(Prefix + "One UI Midnight", "#0F172AFF", "#111827FF", "#3B82F6FF", "#FFFFFFFF", "#FFFFFFFF", "#94A3B8FF", "#60A5FAFF"),
        new(Prefix + "Android Neon", "#12002BFF", "#100024FF", "#00FFAAFF", "#001A12FF", "#FFFFFFFF", "#C4B5FDFF", "#22D3EEFF"),
        new(Prefix + "Nothing OS 3", "#0A0A0AFF", "#111111FF", "#FFFFFFFF", "#000000FF", "#FFFFFFFF", "#8A8A8AFF", "#FF003CFF"),
        new(Prefix + "Oxygen OS", "#F8FAFCFF", "#FFFFFFFF", "#EB0029FF", "#FFFFFFFF", "#0F172AFF", "#64748BFF", "#EB0029FF"),
        new(Prefix + "Neural AI", "#10002BFF", "#120024FF", "#00F5FFFF", "#001414FF", "#FFFFFFFF", "#C4B5FDFF", "#E879F9FF"),
        new(Prefix + "Mr Robot", "#050505FF", "#080808FF", "#00FF41FF", "#000000FF", "#00FF41FF", "#008F11FF", "#00FF41FF"),
        new(Prefix + "Satellite Control", "#071A33FF", "#091A2FFF", "#38BDF8FF", "#001018FF", "#FFFFFFFF", "#93C5FDFF", "#38BDF8FF"),
        new(Prefix + "Alien Interface", "#10001FFF", "#0B0614FF", "#7CFF00FF", "#071000FF", "#F0FFE8FF", "#A6C98AFF", "#C026D3FF"),
        new(Prefix + "Quantum Core", "#0F172AFF", "#111827FF", "#22D3EEFF", "#001018FF", "#FFFFFFFF", "#A5B4FCFF", "#A78BFAFF"),
        new(Prefix + "Roblox Studio", "#252526FF", "#2D2D30FF", "#00A2FFFF", "#FFFFFFFF", "#FFFFFFFF", "#A0A0A0FF", "#00A2FFFF"),
        new(Prefix + "PlayStation HUD", "#07152EFF", "#0B1833FF", "#0070CCFF", "#FFFFFFFF", "#FFFFFFFF", "#93C5FDFF", "#00A8FFFF"),
    ];

    public const string DefaultName = Prefix + "iOS 27 Glass";

    public static string Normalize(string name) =>
        name.StartsWith(LegacyPrefix, StringComparison.Ordinal) ? name[LegacyPrefix.Length..] : name;

    public static HappTheme? Find(string? name) =>
        name.IsNullOrEmpty() ? null : All.FirstOrDefault(t => t.Name == Normalize(name!));

    /// <summary>The plain dark look, for people who just want "dark": calm blue-black surfaces, one clear blue accent.</summary>
    public static readonly HappTheme FlowVeilDark = new("FlowVeil Тёмная", "#0E1117FF", "#171B24FF", "#4C8DFFFF", "#FFFFFFFF", "#F3F5F9FF", "#8B93A7FF", "#4C8DFFFF");

    /// <summary>The plain light look: soft grey page, white cards, the same blue accent.</summary>
    public static readonly HappTheme FlowVeilLight = new("FlowVeil Светлая", "#F1F4F9FF", "#FFFFFFFF", "#2F6BFFFF", "#FFFFFFFF", "#141821FF", "#6B7385FF", "#2F6BFFFF");

    /// <summary>
    /// The palette for a saved theme name: one of the named themes, or (for "follow the system", "Dark" and "Light", which used to be
    /// the flat grey Material defaults) our own dark / light palette. Null for anything else.
    /// </summary>
    public static HappTheme? Resolve(string? name, bool systemUsesLight)
    {
        var named = Find(name);
        if (named != null)
        {
            return named;
        }
        return name switch
        {
            nameof(ETheme.Dark) => FlowVeilDark,
            nameof(ETheme.Light) => FlowVeilLight,
            null or "" or nameof(ETheme.FollowSystem) => systemUsesLight ? FlowVeilLight : FlowVeilDark,
            _ => null,
        };
    }
}

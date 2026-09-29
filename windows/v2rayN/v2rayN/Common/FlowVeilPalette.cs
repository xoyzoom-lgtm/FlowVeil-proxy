using System.Windows.Media;

namespace v2rayN.Common;

/// <summary>
/// FlowVeil design tokens (brushes "FV.Brush.*") derived from the active colour theme, so all
/// 17 themes (light and dark) recolour the new UI. Surface-2, borders and overlays are mixed
/// from the theme's own background/card/text colours instead of being hard-coded.
/// Defaults for the very first frame live in Themes/FlowVeilStyles.xaml.
/// </summary>
public static class FlowVeilPalette
{
    public static void Apply(ResourceDictionary res, Color window, Color surface, Color text, Color textSecondary, Color accent, Color onAccent, bool dark)
    {
        var surface2 = Mix(surface, text, dark ? 0.07 : 0.045);
        var border = Mix(surface, text, dark ? 0.13 : 0.11);
        var text3 = Mix(textSecondary, surface, 0.35);
        // Hover/press overlays: lighten on dark themes, darken on light ones.
        var overlay = dark ? Colors.White : Colors.Black;

        Set(res, "FV.Brush.Window", window);
        Set(res, "FV.Brush.Surface", surface);
        Set(res, "FV.Brush.Surface2", surface2);
        Set(res, "FV.Brush.Border", border);
        Set(res, "FV.Brush.Text", text);
        Set(res, "FV.Brush.Text2", textSecondary);
        Set(res, "FV.Brush.Text3", text3);
        Set(res, "FV.Brush.Accent", accent);
        Set(res, "FV.Brush.OnAccent", onAccent);
        Set(res, "FV.Brush.AccentSoft", Color.FromArgb(0x2E, accent.R, accent.G, accent.B));
        Set(res, "FV.Brush.Overlay", overlay);
        res["FV.Color.Accent"] = accent;
    }

    /// <summary>Defaults for the built-in Light/Dark/system themes (no colour theme chosen).</summary>
    public static void ApplyBuiltIn(ResourceDictionary res, bool dark, Color accent, Color onAccent)
    {
        if (dark)
        {
            Apply(res, Rgb(0x0B, 0x0C, 0x10), Rgb(0x14, 0x16, 0x1C), Rgb(0xF2, 0xF4, 0xF8), Rgb(0x9A, 0xA3, 0xB2), accent, onAccent, true);
        }
        else
        {
            Apply(res, Rgb(0xF3, 0xF4, 0xF7), Rgb(0xFF, 0xFF, 0xFF), Rgb(0x11, 0x18, 0x27), Rgb(0x5B, 0x64, 0x74), accent, onAccent, false);
        }
    }

    private static Color Rgb(byte r, byte g, byte b) => Color.FromRgb(r, g, b);

    /// <summary>Linear mix of two opaque colours; <paramref name="amount"/> is the share of <paramref name="b"/>.</summary>
    public static Color Mix(Color a, Color b, double amount)
    {
        var t = Math.Clamp(amount, 0d, 1d);
        return Color.FromRgb(Lerp(a.R, b.R, t), Lerp(a.G, b.G, t), Lerp(a.B, b.B, t));
    }

    private static byte Lerp(byte from, byte to, double t)
    {
        var value = Math.Round(from + ((to - from) * t));
        return (byte)Math.Clamp(value, 0d, 255d);
    }

    private static void Set(ResourceDictionary res, string key, Color color)
    {
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        res[key] = brush;
    }
}

/// <summary>
/// Animation timings for the XAML storyboards. When Windows animations are off
/// (SystemParameters.ClientAreaAnimation) every transition becomes instant.
/// </summary>
public static class FvMotion
{
    private static readonly bool Enabled = SafeAnimationsEnabled();

    public static Duration Fast { get; } = new(TimeSpan.FromMilliseconds(Enabled ? 160 : 0));
    public static Duration Normal { get; } = new(TimeSpan.FromMilliseconds(Enabled ? 240 : 0));
    public static Duration Spin { get; } = new(TimeSpan.FromMilliseconds(1100));

    /// <summary>The connecting arc spins while animations are on; otherwise it just stands still.</summary>
    public static System.Windows.Media.Animation.RepeatBehavior SpinRepeat { get; } =
        Enabled ? System.Windows.Media.Animation.RepeatBehavior.Forever : new System.Windows.Media.Animation.RepeatBehavior(1);

    public static bool AnimationsEnabled => Enabled;

    private static bool SafeAnimationsEnabled()
    {
        try
        {
            return SystemParameters.ClientAreaAnimation;
        }
        catch
        {
            return true;
        }
    }
}

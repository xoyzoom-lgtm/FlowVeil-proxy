using System.Text;
using System.Text.RegularExpressions;

namespace ServiceLib.Handler;

/// <summary>
/// Short subscription name and card rules of the v4 home screen. Mirror of Android net/ShortName.kt and
/// net/HomeCard.kt; keep both in step (same tests on both sides).
/// </summary>
public static class ShortName
{
    public const int Max = 16;

    private static bool IsEmoji(int cp) => cp is >= 0x1F000 and <= 0x1FFFF or >= 0x2600 and <= 0x27BF or 0xFE0F or 0x200D;

    public static string Of(string? name)
    {
        name ??= string.Empty;
        var sb = new StringBuilder();
        for (var i = 0; i < name.Length; i += char.IsSurrogatePair(name, i) ? 2 : 1)
        {
            var cp = char.ConvertToUtf32(name, i);
            if (!IsEmoji(cp))
            {
                sb.Append(char.ConvertFromUtf32(cp));
            }
        }
        var s = Regex.Replace(sb.ToString(), @"\s+", " ").Trim();
        if (s.Length == 0)
        {
            return name.Trim();
        }
        var letters = new string(s.Where(char.IsLetter).ToArray());
        if (letters.Length > 1 && letters == letters.ToUpperInvariant() && letters != letters.ToLowerInvariant())
        {
            s = s[..1] + s[1..].ToLowerInvariant();
        }
        var count = CodePoints(s);
        if (count > Max)
        {
            s = TakeCodePoints(s, Max - 1).TrimEnd() + "…";
        }
        return s;
    }

    public static string Initial(string? name)
    {
        var c = Of(name).FirstOrDefault(char.IsLetterOrDigit);
        return c == default ? "•" : char.ToUpperInvariant(c).ToString();
    }

    /// <summary>Card gradient 0..2, the same hash as Android so a subscription has one colour everywhere.</summary>
    public static int PaletteIndex(string? id)
    {
        var h = 0;
        foreach (var c in id ?? string.Empty)
        {
            h = unchecked(h * 31 + c) & 0x7fffffff;
        }
        return h % 3;
    }

    /// <summary>Whole days left, rounded up; 0 when over.</summary>
    public static long DaysLeft(long expireSeconds, long nowSeconds)
    {
        var left = expireSeconds - nowSeconds;
        return left <= 0 ? 0 : (left + 86_399) / 86_400;
    }

    private static int CodePoints(string s)
    {
        var n = 0;
        for (var i = 0; i < s.Length; i += char.IsSurrogatePair(s, i) ? 2 : 1)
        {
            n++;
        }
        return n;
    }

    private static string TakeCodePoints(string s, int n)
    {
        var i = 0;
        while (n-- > 0 && i < s.Length)
        {
            i += char.IsSurrogatePair(s, i) ? 2 : 1;
        }
        return s[..i];
    }
}

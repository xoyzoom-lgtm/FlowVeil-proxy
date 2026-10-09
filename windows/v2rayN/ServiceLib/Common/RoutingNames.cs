namespace ServiceLib.Common;

/// <summary>
/// The built-in rule sets came from upstream with Chinese names ("V4-绕过大陆(Whitelist)" …). They are shown, and new ones created,
/// under plain Russian names; a name the user typed is shown as it is.
/// </summary>
public static class RoutingNames
{
    public const string Whitelist = "Местные сайты напрямую";
    public const string Blacklist = "Только выбранное через сервер";
    public const string Global = "Всё через сервер";

    public static string Display(string? remarks)
    {
        if (remarks.IsNullOrEmpty())
        {
            return string.Empty;
        }
        var r = remarks!;
        if (r.Contains("绕过大陆") || r.EndsWith("(Whitelist)", StringComparison.Ordinal))
        {
            return Whitelist;
        }
        if (r.Contains("黑名单") || r.EndsWith("(Blacklist)", StringComparison.Ordinal))
        {
            return Blacklist;
        }
        if (r.Contains("全局") || r.EndsWith("(Global)", StringComparison.Ordinal))
        {
            return Global;
        }
        return r;
    }
}

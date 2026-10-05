namespace ServiceLib.Handler;

/// <summary>The direct and bootstrap DNS: the upstream defaults are Chinese public resolvers. The same rule as Android (net/NetLogic.kt DirectDns).</summary>
public static class DnsDefaults
{
    public const string Default = "77.88.8.8";
    private static readonly HashSet<string> OldDefaults = ["223.5.5.5", "223.6.6.6", "119.29.29.29"];

    public static string? Migrate(string? stored)
    {
        var v = stored?.Trim() ?? string.Empty;
        return v.Length == 0 || OldDefaults.Contains(v) ? Default : stored;
    }
}

namespace ServiceLib.Handler;

/// <summary>
/// "Быстрый режим": fewer extras while connected; protection and DNS are not touched. Same rules and tests as Android net/FastMode.kt.
/// The switch is a marker file in the config folder; off by default.
/// </summary>
public static class FastMode
{
    private const string FileName = "fast_mode_on";

    public static bool Enabled => File.Exists(Utils.GetConfigPath(FileName));

    public static void Set(bool on)
    {
        try
        {
            var path = Utils.GetConfigPath(FileName);
            if (on)
            {
                File.WriteAllText(path, "on");
            }
            else
            {
                File.Delete(path);
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(FastMode), ex);
        }
    }

    /// <summary>Log level for the core: in fast mode never more than "warning".</summary>
    public static string LogLevel(bool fast, string? userLevel)
    {
        var level = string.IsNullOrWhiteSpace(userLevel) ? "warning" : userLevel;
        return fast && level is "debug" or "info" ? "warning" : level;
    }

    public static TimeSpan CheckInterval(TimeSpan baseInterval, bool fast) => fast ? baseInterval * 2 : baseInterval;
}

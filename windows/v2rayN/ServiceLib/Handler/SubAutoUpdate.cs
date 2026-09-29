namespace ServiceLib.Handler;

/// <summary>
/// One automatic-update interval for all subscriptions, chosen in FlowVeil settings
/// (every 6 hours unless changed, 0 = off). New subscriptions get it too.
/// </summary>
public static class SubAutoUpdate
{
    public const int DefaultMinutes = 360;
    private const string FileName = "sub_update_interval";

    public static readonly IReadOnlyList<(int Minutes, string Title)> Options =
    [
        (0, "Выключено"),
        (60, "Каждый час"),
        (180, "Каждые 3 часа"),
        (360, "Каждые 6 часов"),
        (720, "Каждые 12 часов"),
        (1440, "Раз в день"),
    ];

    public static int Get()
    {
        try
        {
            var path = Utils.GetConfigPath(FileName);
            if (File.Exists(path) && int.TryParse(File.ReadAllText(path).Trim(), out var minutes))
            {
                return minutes;
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(SubAutoUpdate), ex);
        }
        return DefaultMinutes;
    }

    public static string Title(int minutes) =>
        Options.FirstOrDefault(o => o.Minutes == minutes).Title ?? $"Каждые {minutes} мин";

    /// <summary>Saves the interval and applies it to every subscription with a link.</summary>
    public static async Task SetAsync(int minutes)
    {
        try
        {
            File.WriteAllText(Utils.GetConfigPath(FileName), minutes.ToString());
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(SubAutoUpdate), ex);
        }
        await ApplyAsync(minutes, onlyUnset: false);
    }

    /// <summary>First run: subscriptions that never had an interval refresh every 6 hours.</summary>
    public static async Task EnsureDefaultAsync()
    {
        if (File.Exists(Utils.GetConfigPath(FileName)))
        {
            return;
        }
        await SetAsyncUnsetOnly(DefaultMinutes);
    }

    private static async Task SetAsyncUnsetOnly(int minutes)
    {
        try
        {
            File.WriteAllText(Utils.GetConfigPath(FileName), minutes.ToString());
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(SubAutoUpdate), ex);
        }
        await ApplyAsync(minutes, onlyUnset: true);
    }

    private static async Task ApplyAsync(int minutes, bool onlyUnset)
    {
        var subs = await AppManager.Instance.SubItems() ?? [];
        foreach (var sub in subs.Where(s => s.Url.IsNotEmpty()))
        {
            if (onlyUnset && sub.AutoUpdateInterval > 0)
            {
                continue;
            }
            sub.AutoUpdateInterval = minutes;
            await SQLiteHelper.Instance.UpdateAsync(sub);
        }
    }
}

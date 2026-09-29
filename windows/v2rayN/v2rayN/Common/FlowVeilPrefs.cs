namespace v2rayN.Common;

/// <summary>Developer mode: technical details stay hidden for everyday users until turned on.</summary>
public static class DevMode
{
    private static bool? _cached;

    private static string FilePath => Utils.GetConfigPath("dev_mode");

    public static bool IsOn => _cached ??= File.Exists(FilePath);

    public static void Set(bool on)
    {
        try
        {
            if (on)
            {
                File.WriteAllText(FilePath, "1");
            }
            else if (File.Exists(FilePath))
            {
                File.Delete(FilePath);
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(DevMode), ex);
        }
        _cached = on;
    }
}

/// <summary>Servers the user starred in the FlowVeil list (one IndexId per line in guiConfigs/favorites.txt).</summary>
public static class FavoriteServers
{
    private static HashSet<string>? _ids;
    private static readonly object _lock = new();

    private static string FilePath => Utils.GetConfigPath("favorites.txt");

    private static HashSet<string> Ids
    {
        get
        {
            if (_ids != null)
            {
                return _ids;
            }
            try
            {
                _ids = File.Exists(FilePath)
                    ? File.ReadAllLines(FilePath).Select(l => l.Trim()).Where(l => l.Length > 0).ToHashSet()
                    : [];
            }
            catch (Exception ex)
            {
                Logging.SaveLog(nameof(FavoriteServers), ex);
                _ids = [];
            }
            return _ids;
        }
    }

    public static bool IsFavorite(string? indexId)
    {
        if (indexId.IsNullOrEmpty())
        {
            return false;
        }
        lock (_lock)
        {
            return Ids.Contains(indexId!);
        }
    }

    /// <summary>Toggles the star; returns the new state.</summary>
    public static bool Toggle(string indexId)
    {
        lock (_lock)
        {
            bool now;
            if (Ids.Remove(indexId))
            {
                now = false;
            }
            else
            {
                Ids.Add(indexId);
                now = true;
            }
            try
            {
                File.WriteAllLines(FilePath, Ids);
            }
            catch (Exception ex)
            {
                Logging.SaveLog(nameof(FavoriteServers), ex);
            }
            return now;
        }
    }
}

namespace ServiceLib.Handler;

/// <summary>One program in the per-application list.</summary>
public sealed class AppProxyApp
{
    public string Name { get; set; } = string.Empty;
    public string Path { get; set; } = string.Empty;
}

/// <summary>
/// "Proxy for applications": which programs go direct, or the only ones that go through the proxy.
/// Stored next to the other settings (app_proxy.json) and turned into ordinary routing rules when a
/// core config is built, ahead of the user's own rules, so a routing preset never removes it.
/// Matching a program needs the connection to be tied to it, which the TUN mode does.
/// </summary>
public static class AppProxySettings
{
    public const string ModeOff = "off";

    /// <summary>The selected programs bypass the proxy; everything else follows the usual rules.</summary>
    public const string ModeDirect = "direct";

    /// <summary>Only the selected programs use the proxy; everything else goes direct.</summary>
    public const string ModeProxy = "proxy";

    private sealed class Stored
    {
        public string Mode { get; set; } = ModeOff;
        public List<AppProxyApp> Apps { get; set; } = [];
    }

    private static readonly object Lock = new();

    private static string FilePath => Utils.GetConfigPath("app_proxy.json");

    private static Stored Read()
    {
        lock (Lock)
        {
            try
            {
                if (File.Exists(FilePath))
                {
                    var stored = JsonUtils.Deserialize<Stored>(File.ReadAllText(FilePath));
                    if (stored != null)
                    {
                        stored.Apps ??= [];
                        if (stored.Mode is not (ModeDirect or ModeProxy))
                        {
                            stored.Mode = ModeOff;
                        }
                        return stored;
                    }
                }
            }
            catch (Exception ex)
            {
                Logging.SaveLog(nameof(AppProxySettings), ex);
            }
            return new Stored();
        }
    }

    private static void Write(Stored stored)
    {
        lock (Lock)
        {
            try
            {
                File.WriteAllText(FilePath, JsonUtils.Serialize(stored));
            }
            catch (Exception ex)
            {
                Logging.SaveLog(nameof(AppProxySettings), ex);
            }
        }
    }

    public static string Mode => Read().Mode;

    public static List<AppProxyApp> Apps => Read().Apps;

    public static void SetMode(string mode)
    {
        var stored = Read();
        stored.Mode = mode is ModeDirect or ModeProxy ? mode : ModeOff;
        Write(stored);
    }

    /// <summary>Adds a program (by its file); false when it is already there or is not an .exe.</summary>
    public static bool Add(string path)
    {
        if (path.IsNullOrEmpty() || !path.EndsWith(".exe", StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }
        var stored = Read();
        if (stored.Apps.Any(a => string.Equals(a.Path, path, StringComparison.OrdinalIgnoreCase)))
        {
            return false;
        }
        stored.Apps.Add(new AppProxyApp { Name = FileName(path).Replace(".exe", string.Empty, StringComparison.OrdinalIgnoreCase), Path = path });
        Write(stored);
        return true;
    }

    public static void Remove(string path)
    {
        var stored = Read();
        stored.Apps.RemoveAll(a => string.Equals(a.Path, path, StringComparison.OrdinalIgnoreCase));
        Write(stored);
    }

    /// <summary>The file name of a Windows path (either kind of slash), on any platform.</summary>
    private static string FileName(string path) => path[(path.LastIndexOfAny(['\\', '/']) + 1)..];

    /// <summary>What to tell a core: a program is named by its path and by its file name, whichever the core understands.</summary>
    public static List<string> ProcessNames(IEnumerable<AppProxyApp> apps)
    {
        var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var app in apps)
        {
            if (!app.Path.IsNullOrEmpty())
            {
                set.Add(app.Path);
                set.Add(FileName(app.Path));
            }
        }
        return [.. set];
    }

    /// <summary>
    /// The routing rules for the current settings, to put first. [onlySelected] is true in the mode
    /// where the user's own rules must NOT follow: the selected programs use the proxy and the
    /// catch-all rule sends everything else direct.
    /// </summary>
    public static List<RulesItem> BuildRules(out bool onlySelected)
    {
        onlySelected = false;
        var stored = Read();
        var names = ProcessNames(stored.Apps);
        if (stored.Mode == ModeOff || names.Count == 0)
        {
            return [];
        }
        if (stored.Mode == ModeDirect)
        {
            return [new RulesItem { Enabled = true, Remarks = "FlowVeil: apps direct", Process = names, OutboundTag = Global.DirectTag }];
        }
        onlySelected = true;
        return
        [
            new RulesItem { Enabled = true, Remarks = "FlowVeil: apps via proxy", Process = names, OutboundTag = Global.ProxyTag },
            new RulesItem { Enabled = true, Remarks = "FlowVeil: everything else direct", Port = "0-65535", OutboundTag = Global.DirectTag },
        ];
    }
}

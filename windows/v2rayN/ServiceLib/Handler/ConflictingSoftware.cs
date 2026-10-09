using System.Diagnostics;

namespace ServiceLib.Handler;

/// <summary>A running program that can disturb FlowVeil: its name for the user and the process it runs as.</summary>
public sealed record ConflictItem(string Title, string Process);

/// <summary>
/// Programs that fight FlowVeil for the network: DPI-bypass tools that hook packets (zapret, GoodbyeDPI, ByeDPI, SpoofDPI, Discord-fix tools
/// based on them) and other proxy/VPN clients that set the system proxy or create their own TUN adapter. Our own cores are never listed.
/// </summary>
public static class ConflictingSoftware
{
    private static readonly (string Process, string Title)[] Known =
    [
        ("winws", "zapret"),
        ("goodbyedpi", "GoodbyeDPI"),
        ("ciadpi", "ByeDPI"),
        ("byedpi", "ByeDPI"),
        ("spoofdpi", "SpoofDPI"),
        ("v2rayN", "v2rayN"),
        ("Happ", "Happ"),
        ("clash-verge", "Clash Verge"),
        ("Clash for Windows", "Clash for Windows"),
        ("nekoray", "NekoRay"),
        ("nekobox", "NekoBox"),
        ("Hiddify", "Hiddify"),
        ("hiddify-next", "Hiddify"),
        ("Throne", "Throne"),
    ];

    private const string OffFile = "conflict_warn_off";

    /// <summary>The warning is on by default; one file in the config folder turns it off.</summary>
    public static bool WarnEnabled => !File.Exists(Utils.GetConfigPath(OffFile));

    public static void SetWarnEnabled(bool on)
    {
        try
        {
            var path = Utils.GetConfigPath(OffFile);
            if (on)
            {
                File.Delete(path);
            }
            else
            {
                File.WriteAllText(path, "off");
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(ConflictingSoftware), ex);
        }
    }

    /// <summary>The known programs among the running ones; [isRunning] answers for a process name without ".exe".</summary>
    public static List<ConflictItem> Find(Func<string, bool> isRunning) =>
        Known.Where(k => isRunning(k.Process)).Select(k => new ConflictItem(k.Title, k.Process)).ToList();

    public static List<ConflictItem> FindRunning() =>
        Find(name =>
        {
            try
            {
                // FlowVeil itself may run as v2rayN.exe (portable copy): never count our own process.
                var self = Environment.ProcessId;
                return Process.GetProcessesByName(name).Any(p => p.Id != self);
            }
            catch
            {
                return false;
            }
        });

    /// <summary>"zapret (winws.exe)" as in the dialog.</summary>
    public static string Label(ConflictItem item) => $"{item.Title} ({item.Process}.exe)";

    /// <summary>Closes the processes; the names that could not be closed (usually: they run as administrator and FlowVeil does not).</summary>
    public static List<ConflictItem> Unload(IEnumerable<ConflictItem> items)
    {
        var failed = new List<ConflictItem>();
        foreach (var item in items)
        {
            var closedAll = true;
            try
            {
                foreach (var p in Process.GetProcessesByName(item.Process).Where(p => p.Id != Environment.ProcessId))
                {
                    try
                    {
                        p.Kill(true);
                        p.WaitForExit(2000);
                    }
                    catch (Exception ex)
                    {
                        Logging.SaveLog(nameof(ConflictingSoftware), ex);
                        closedAll = false;
                    }
                }
            }
            catch (Exception ex)
            {
                Logging.SaveLog(nameof(ConflictingSoftware), ex);
                closedAll = false;
            }
            if (!closedAll)
            {
                failed.Add(item);
            }
        }
        return failed;
    }
}

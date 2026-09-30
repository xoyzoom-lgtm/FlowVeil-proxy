namespace ServiceLib.Handler;

public enum FailoverAction
{
    None,
    /// <summary>The first failure: look again in a few seconds before doing anything.</summary>
    Recheck,
    /// <summary>The server failed twice in a row while the computer itself is online: switch.</summary>
    Switch,
}

/// <summary>
/// Decides when a dead server means "switch": two failed checks in a row (a few seconds apart), only while the computer itself is
/// online (no network at all is not the server's fault), and not again within <see cref="Cooldown"/> after a switch.
/// The same rules as the Android watchdog.
/// </summary>
public sealed class FailoverState
{
    public static readonly TimeSpan Cooldown = TimeSpan.FromSeconds(45);
    private int _fails;
    private DateTimeOffset _lastSwitch = DateTimeOffset.MinValue;

    public FailoverAction Observe(bool serverWorks, bool networkUp, DateTimeOffset now)
    {
        if (serverWorks || !networkUp)
        {
            _fails = 0;
            return FailoverAction.None;
        }
        _fails++;
        if (_fails < 2)
        {
            return FailoverAction.Recheck;
        }
        if (now - _lastSwitch < Cooldown)
        {
            // Just switched: give the new server time, then judge it.
            _fails = 1;
            return FailoverAction.None;
        }
        _fails = 0;
        _lastSwitch = now;
        return FailoverAction.Switch;
    }

    public void Reset() => _fails = 0;
}

public static class FailoverPlan
{
    public const int PerGroup = 8;

    /// <summary>
    /// Candidates in the order they are tried, one group per subscription: the current subscription first, then the others in the user's
    /// order (unusable ones never). Inside a group: servers that answered before, fastest first; untested ones in provider order; servers that
    /// failed the last test at the end. The current server and servers in Russia are never offered.
    /// </summary>
    public static List<List<string>> Groups(IReadOnlyList<ServerFacts> all, string currentIndexId, string currentSubId, IReadOnlyList<string> subOrder,
        Func<string, bool> subUsable, Func<string?, bool> isRussianName, int perGroup = PerGroup)
    {
        var groups = new List<List<string>>();
        foreach (var subId in SubsLogic.FailoverOrder(currentSubId, subOrder, subUsable))
        {
            var ids = all
                .Where(s => s.SubId == subId && s.IndexId != currentIndexId && !isRussianName(s.Name))
                .OrderBy(s => s.Delay > 0 ? 0 : s.Delay == 0 ? 1 : 2)
                .ThenBy(s => s.Delay > 0 ? s.Delay : 0)
                .ThenBy(s => s.ProviderOrder)
                .Take(perGroup)
                .Select(s => s.IndexId)
                .ToList();
            if (ids.Count > 0)
            {
                groups.Add(ids);
            }
        }
        return groups;
    }
}

/// <summary>"Switch to another server when one stops working": on by default, one file in the config folder when it is off.</summary>
public static class FailoverSettings
{
    private const string OffFile = "failover_off";

    public static bool IsEnabled => !File.Exists(Utils.GetConfigPath(OffFile));

    public static void SetEnabled(bool on)
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
            Logging.SaveLog(nameof(FailoverSettings), ex);
        }
    }
}

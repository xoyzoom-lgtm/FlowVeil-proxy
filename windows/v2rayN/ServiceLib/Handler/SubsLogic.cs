using System.Text.Json;

namespace ServiceLib.Handler;

/*
 * Several subscriptions on the main screen, the pure part: health of a subscription, what a chip shows, the filtered and sorted
 * server list, "the best in the current filter", the failover order, duplicate links, the saved state. No UI and no database here,
 * so all of it is unit-tested. The Android client keeps the same rules in net/SubsLogic.kt.
 */

public enum SubHealth
{
    Ok,
    ExpiresSoon,
    Expired,
    TrafficOver,
    Error,
    Disabled,
}

public sealed record SubFacts(string Id, string Name, bool Enabled, long ExpireSeconds, long Used, long Total, SubIssue LastIssue);

public enum ChipKind
{
    All,
    Sub,
    Favorites,
}

/// <summary>What the strip above the list has selected.</summary>
public sealed record SubSelection(ChipKind Kind, string? SubId = null)
{
    public static readonly SubSelection AllServers = new(ChipKind.All);
    public static readonly SubSelection FavoriteServers = new(ChipKind.Favorites);

    public string Encode() => Kind switch { ChipKind.All => "all", ChipKind.Favorites => "fav", _ => SubId ?? "all" };

    public static SubSelection Decode(string? text) => text switch
    {
        null or "" or "all" => AllServers,
        "fav" => FavoriteServers,
        _ => new SubSelection(ChipKind.Sub, text),
    };
}

public sealed record ServerFacts(string IndexId, string SubId, string Name, long Delay, string Protocol, string Security, string Network, int ProviderOrder);

public enum ServerSort
{
    Provider,
    Ping,
    Name,
}

[Flags]
public enum QuickFilter
{
    None = 0,
    Alive = 1,
    Reality = 2,
    Udp = 4,
    Favorite = 8,
}

public static class SubsLogic
{
    public const int SoonDays = 3;

    public static SubHealth HealthOf(SubFacts f, long nowSeconds)
    {
        if (!f.Enabled)
        {
            return SubHealth.Disabled;
        }
        var info = SubscriptionHealth.ByInfo(f.ExpireSeconds * 1000, f.Used, f.Total is > 0 and < 1L << 50 ? f.Total : 0, nowSeconds * 1000);
        if (info == SubIssue.Expired || f.LastIssue == SubIssue.Expired)
        {
            return SubHealth.Expired;
        }
        if (info == SubIssue.TrafficOver || f.LastIssue == SubIssue.TrafficOver)
        {
            return SubHealth.TrafficOver;
        }
        if (f.LastIssue != SubIssue.None)
        {
            return SubHealth.Error;
        }
        return f.ExpireSeconds > 0 && f.ExpireSeconds - nowSeconds <= SoonDays * 86400L ? SubHealth.ExpiresSoon : SubHealth.Ok;
    }

    /// <summary>Servers of a subscription may be used automatically ("Best", failover): not expired, not out of traffic, not switched off. An update error alone does not exclude it: the old servers still work.</summary>
    public static bool IsUsable(SubHealth health) => health is SubHealth.Ok or SubHealth.ExpiresSoon or SubHealth.Error;

    // ---- the list ----

    public static bool IsUdpBased(ServerFacts s) =>
        s.Protocol.Contains("HYSTERIA", StringComparison.OrdinalIgnoreCase) || s.Protocol.Contains("TUIC", StringComparison.OrdinalIgnoreCase)
        || s.Protocol.Contains("WIREGUARD", StringComparison.OrdinalIgnoreCase) || s.Network.Equals("kcp", StringComparison.OrdinalIgnoreCase)
        || s.Network.Equals("quic", StringComparison.OrdinalIgnoreCase)
        || s.Name.Contains("игров", StringComparison.OrdinalIgnoreCase) || s.Name.Contains("gaming", StringComparison.OrdinalIgnoreCase)
        || s.Name.Contains("game", StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// The servers to show: chip selection, then search, quick filters and sort. "All" hides subscriptions that are switched off
    /// (they are kept, not deleted); a chosen subscription shows its own servers even when it is switched off.
    /// <paramref name="subOrder"/> is the order of subscriptions (for "All" and for the provider order).
    /// </summary>
    public static List<ServerFacts> Filter(
        IEnumerable<ServerFacts> all,
        SubSelection selection,
        IReadOnlySet<string> enabledSubIds,
        IReadOnlyList<string> subOrder,
        string? query,
        QuickFilter quick,
        IReadOnlySet<string> favorites,
        ServerSort sort)
    {
        IEnumerable<ServerFacts> rows = selection.Kind switch
        {
            ChipKind.Sub => all.Where(s => s.SubId == selection.SubId),
            ChipKind.Favorites => all.Where(s => enabledSubIds.Contains(s.SubId) && favorites.Contains(s.IndexId)),
            _ => all.Where(s => enabledSubIds.Contains(s.SubId)),
        };
        var q = query?.Trim();
        if (!string.IsNullOrEmpty(q))
        {
            rows = rows.Where(s => s.Name.Contains(q, StringComparison.OrdinalIgnoreCase) || s.Protocol.Contains(q, StringComparison.OrdinalIgnoreCase)
                                   || s.Security.Contains(q, StringComparison.OrdinalIgnoreCase));
        }
        if (quick.HasFlag(QuickFilter.Alive))
        {
            rows = rows.Where(s => s.Delay > 0);
        }
        if (quick.HasFlag(QuickFilter.Reality))
        {
            rows = rows.Where(s => s.Security.Equals("reality", StringComparison.OrdinalIgnoreCase));
        }
        if (quick.HasFlag(QuickFilter.Udp))
        {
            rows = rows.Where(IsUdpBased);
        }
        if (quick.HasFlag(QuickFilter.Favorite))
        {
            rows = rows.Where(s => favorites.Contains(s.IndexId));
        }

        var rank = subOrder.Select((id, i) => (id, i)).ToDictionary(x => x.id, x => x.i);
        int SubRank(ServerFacts s) => rank.TryGetValue(s.SubId, out var i) ? i : int.MaxValue;
        var ordered = rows.OrderBy(SubRank);
        return (sort switch
        {
            ServerSort.Ping => ordered.ThenBy(s => s.Delay > 0 ? 0 : 1).ThenBy(s => s.Delay > 0 ? s.Delay : long.MaxValue).ThenBy(s => s.ProviderOrder),
            ServerSort.Name => ordered.ThenBy(s => s.Name, StringComparer.CurrentCultureIgnoreCase).ThenBy(s => s.ProviderOrder),
            _ => ordered.ThenBy(s => s.ProviderOrder),
        }).ToList();
    }

    /// <summary>"Connect to the best" among the given (already filtered) servers: the lowest ping, never a Russian server, never one of an unusable subscription.</summary>
    public static ServerFacts? Best(IEnumerable<ServerFacts> scope, Func<string, bool> subUsable, Func<string, bool> isRussianName) =>
        scope.Where(s => s.Delay > 0 && subUsable(s.SubId) && !isRussianName(s.Name)).OrderBy(s => s.Delay).ThenBy(s => s.ProviderOrder).FirstOrDefault();

    /// <summary>Which subscriptions to try when the connection breaks: the current one first, then the others in the user's order; unusable ones never.</summary>
    public static List<string> FailoverOrder(string currentSubId, IReadOnlyList<string> subOrder, Func<string, bool> subUsable)
    {
        var result = new List<string>();
        if (subUsable(currentSubId))
        {
            result.Add(currentSubId);
        }
        result.AddRange(subOrder.Where(id => id != currentSubId && subUsable(id)));
        return result;
    }

    // ---- order of subscriptions ----

    /// <summary>The saved order first (only subscriptions that still exist), new ones after it in their default order.</summary>
    public static List<string> ApplyOrder(IReadOnlyList<string> defaultIds, IReadOnlyList<string> savedOrder)
    {
        var result = savedOrder.Where(defaultIds.Contains).Distinct().ToList();
        result.AddRange(defaultIds.Where(id => !result.Contains(id)));
        return result;
    }

    public static List<string> Move(IReadOnlyList<string> order, string id, int delta)
    {
        var list = order.ToList();
        var i = list.IndexOf(id);
        if (i < 0)
        {
            return list;
        }
        var target = Math.Clamp(i + delta, 0, list.Count - 1);
        list.RemoveAt(i);
        list.Insert(target, id);
        return list;
    }

    // ---- duplicate links ----

    /// <summary>The same link written differently (case of the host, a trailing slash, the default port) is the same subscription.</summary>
    public static string NormalizeUrl(string url)
    {
        var text = url.Trim();
        if (!Uri.TryCreate(text, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https"))
        {
            return text;
        }
        var port = uri.IsDefaultPort ? string.Empty : $":{uri.Port}";
        var path = uri.AbsolutePath.TrimEnd('/');
        return $"{uri.Scheme}://{uri.IdnHost.ToLowerInvariant()}{port}{path}{uri.Query}";
    }

    /// <summary>The id of an existing subscription with the same link, or null.</summary>
    public static string? FindDuplicate(IEnumerable<(string Id, string Url)> existing, string newUrl)
    {
        var wanted = NormalizeUrl(newUrl);
        return existing.FirstOrDefault(e => NormalizeUrl(e.Url) == wanted).Id;
    }
}

/// <summary>What the main screen remembers between runs (guiConfigs/subs_ui.json). A missing or damaged file gives the defaults.</summary>
public sealed class SubsUiState
{
    public string Selected { get; set; } = "all";
    public ServerSort Sort { get; set; } = ServerSort.Provider;
    public QuickFilter Quick { get; set; } = QuickFilter.None;
    public List<string> Order { get; set; } = [];
    public List<string> Collapsed { get; set; } = [];
    public List<string> Favorites { get; set; } = [];
    public Dictionary<string, string> LocalNames { get; set; } = [];

    public static SubsUiState Parse(string? json)
    {
        if (string.IsNullOrWhiteSpace(json))
        {
            return new SubsUiState();
        }
        try
        {
            var state = JsonSerializer.Deserialize<SubsUiState>(json) ?? new SubsUiState();
            state.Order ??= [];
            state.Collapsed ??= [];
            state.Favorites ??= [];
            state.LocalNames ??= [];
            state.Selected = string.IsNullOrEmpty(state.Selected) ? "all" : state.Selected;
            if (!Enum.IsDefined(state.Sort))
            {
                state.Sort = ServerSort.Provider;
            }
            state.Quick &= QuickFilter.Alive | QuickFilter.Reality | QuickFilter.Udp | QuickFilter.Favorite;
            return state;
        }
        catch (JsonException)
        {
            return new SubsUiState();
        }
    }

    public string ToJson() => JsonSerializer.Serialize(this);
}

public static class SubsUiStateStore
{
    private const string FileName = "subs_ui.json";

    public static SubsUiState Load()
    {
        try
        {
            var path = Utils.GetConfigPath(FileName);
            return SubsUiState.Parse(File.Exists(path) ? File.ReadAllText(path) : null);
        }
        catch
        {
            return new SubsUiState();
        }
    }

    /// <summary>Raised after <see cref="RemapIds"/> with new id → old id: a screen that holds the state in memory applies it too.</summary>
    public static event Action<IReadOnlyDictionary<string, string>>? IdsRemapped;

    /// <summary>A subscription update gave servers new ids: favorites follow them (stored file and any open screen).</summary>
    public static void RemapIds(IReadOnlyDictionary<string, string> newToOld)
    {
        var state = Load();
        state.Favorites = ServerIdentity.RemapIds(state.Favorites, newToOld);
        Save(state);
        IdsRemapped?.Invoke(newToOld);
    }

    public static void Save(SubsUiState state)
    {
        try
        {
            var path = Utils.GetConfigPath(FileName);
            var temp = path + ".tmp";
            File.WriteAllText(temp, state.ToJson());
            File.Move(temp, path, true);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(SubsUiStateStore), ex);
        }
    }
}

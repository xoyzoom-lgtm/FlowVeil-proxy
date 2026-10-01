namespace ServiceLib.Handler;

public sealed record RuleProfile(string Id, string Title, string Hint, string RulesJson);

/// <summary>
/// "Профили правил": three ready sets of routing rules that are switched with one click instead of digging into routing settings.
/// They are ordinary routing items (so the tray menu and the advanced routing window see them too); the rules are ours,
/// with fixed ids, and never overwrite an item the user has already changed.
/// </summary>
public static class RuleProfiles
{
    public const string HomeId = "fv-profile-home";
    public const string AllId = "fv-profile-all";
    public const string DirectId = "fv-profile-direct";

    private const string Private = """
        { "remarks": "Локальная сеть", "outboundTag": "direct", "ip": ["geoip:private"] },
        { "remarks": "Локальные адреса", "outboundTag": "direct", "domain": ["geosite:private"] }
        """;

    public static readonly IReadOnlyList<RuleProfile> All =
    [
        new(HomeId, "РФ напрямую", "Российские сайты и сервисы напрямую, всё остальное через сервер. Подходит для повседневной работы",
            "[" + Private + """
            ,
            { "remarks": "Российские домены", "outboundTag": "direct", "domain": ["domain:ru", "domain:su", "domain:xn--p1ai"] },
            { "remarks": "Российские адреса", "outboundTag": "direct", "ip": ["geoip:ru"] },
            { "remarks": "Остальное через сервер", "port": "0-65535", "outboundTag": "proxy" }
            ]
            """),
        new(AllId, "Всё через сервер", "Весь трафик идёт через сервер, кроме домашней сети. Для чужого Wi-Fi и когда нужно наверняка",
            "[" + Private + """
            ,
            { "remarks": "Всё через сервер", "port": "0-65535", "outboundTag": "proxy" }
            ]
            """),
        new(DirectId, "Всё напрямую", "Сервер не используется: всё идёт как без FlowVeil. Быстро проверить, что мешает: сервер или сеть",
            """
            [
            { "remarks": "Всё напрямую", "port": "0-65535", "outboundTag": "direct" }
            ]
            """),
    ];

    public static RuleProfile? Find(string? id) => All.FirstOrDefault(p => p.Id == id);

    /// <summary>The profile that is switched on now, or null when the user runs their own rules.</summary>
    public static async Task<RuleProfile?> ActiveAsync()
    {
        var item = await SQLiteHelper.Instance.TableAsync<RoutingItem>().FirstOrDefaultAsync(t => t.IsActive == true);
        return Find(item?.Id);
    }

    /// <summary>Creates the profile's routing item if it does not exist yet and makes it the active one.</summary>
    public static async Task<bool> ActivateAsync(Config config, string id)
    {
        var profile = Find(id);
        if (profile == null)
        {
            return false;
        }
        var items = await AppManager.Instance.RoutingItems();
        var item = items.FirstOrDefault(t => t.Id == id);
        if (item == null)
        {
            item = new RoutingItem { Id = id, Remarks = profile.Title, Sort = items.Count + 1, Enabled = true, Url = string.Empty };
            if (await ConfigHandler.AddBatchRoutingRules(item, profile.RulesJson) != 0)
            {
                return false;
            }
        }
        await ConfigHandler.SetDefaultRouting(config, item);
        await ConfigHandler.SaveConfig(config);
        return true;
    }
}

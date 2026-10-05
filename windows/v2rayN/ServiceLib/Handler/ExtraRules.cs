namespace ServiceLib.Handler;

/// <summary>
/// Two switches on top of any routing profile: ad blocking (the category-ads-all list → blocked) and "Telegram always through the
/// server" (Telegram's domains and addresses → proxy, also for calls). Both lists ship with the core's geo files. The same
/// rules as the Android app (handler/ExtraRules.kt). Stored as marker files in the config folder; both off by default.
/// </summary>
public static class ExtraRules
{
    private const string AdsFile = "adblock_on";
    private const string TelegramFile = "telegram_proxy_on";

    public static bool AdBlock => File.Exists(Utils.GetConfigPath(AdsFile));
    public static bool TelegramProxy => File.Exists(Utils.GetConfigPath(TelegramFile));

    public static void SetAdBlock(bool on) => Set(AdsFile, on);
    public static void SetTelegramProxy(bool on) => Set(TelegramFile, on);

    private static void Set(string file, bool on)
    {
        try
        {
            var path = Utils.GetConfigPath(file);
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
            Logging.SaveLog(nameof(ExtraRules), ex);
        }
    }

    /// <summary>Rules that go before everything else (ad blocking wins over any "direct").</summary>
    public static List<RulesItem> Before(bool adBlock) => adBlock
        ? [new RulesItem { Id = "fv-adblock", Remarks = "Блокировка рекламы", OutboundTag = Global.BlockTag, Domain = ["geosite:category-ads-all"] }]
        : [];

    /// <summary>Rules that go after the per-app rules and before the profile (an app the user sent direct stays direct).</summary>
    public static List<RulesItem> After(bool telegram, bool appsOnly) => telegram && !appsOnly
        ?
        [
            new RulesItem { Id = "fv-telegram-domains", Remarks = "Telegram через сервер", OutboundTag = Global.ProxyTag, Domain = ["geosite:telegram"] },
            new RulesItem { Id = "fv-telegram-ips", Remarks = "Telegram через сервер", OutboundTag = Global.ProxyTag, Ip = ["geoip:telegram"] },
        ]
        : [];
}

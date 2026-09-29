using System.Globalization;

namespace ServiceLib.Handler;

/// <summary>Provider metadata read from subscription response headers (Remnawave/Marzban conventions).</summary>
public sealed class SubscriptionInfo
{
    public string? Title { get; set; }
    public long Upload { get; set; }
    public long Download { get; set; }
    public long Total { get; set; }
    public long Expire { get; set; }
    public string? Announce { get; set; }
    public string? SupportUrl { get; set; }
    public string? WebPageUrl { get; set; }
    public int UpdateIntervalHours { get; set; }
    public long UpdatedAt { get; set; }

    // Saturating: some panels send absurd numbers, and the build throws on overflow.
    public long Used => Upload > long.MaxValue - Download ? long.MaxValue : Upload + Download;

    /// <summary>Expiry as unix seconds; panels that send milliseconds are converted.</summary>
    public long ExpireSeconds => Expire > 100_000_000_000L ? Expire / 1000 : Expire;
}

/// <summary>Stores <see cref="SubscriptionInfo"/> per subscription id in guiConfigs/sub_info.json.</summary>
public static class SubscriptionInfoStore
{
    private const string FileName = "sub_info.json";
    private static readonly object _lock = new();
    private static Dictionary<string, SubscriptionInfo>? _cache;

    public static event Action? Changed;

    public static SubscriptionInfo? Get(string? subId)
    {
        if (subId.IsNullOrEmpty())
        {
            return null;
        }
        lock (_lock)
        {
            return Load().TryGetValue(subId, out var info) ? info : null;
        }
    }

    public static void Remove(string subId)
    {
        lock (_lock)
        {
            if (!Load().Remove(subId))
            {
                return;
            }
            try
            {
                File.WriteAllText(Utils.GetConfigPath(FileName), JsonUtils.Serialize(Load()));
            }
            catch (Exception ex)
            {
                Logging.SaveLog(nameof(SubscriptionInfoStore), ex);
            }
        }
        Changed?.Invoke();
    }

    public static void Save(string subId, SubscriptionInfo info)
    {
        lock (_lock)
        {
            var all = Load();
            all[subId] = info;
            try
            {
                File.WriteAllText(Utils.GetConfigPath(FileName), JsonUtils.Serialize(all));
            }
            catch (Exception ex)
            {
                Logging.SaveLog(nameof(SubscriptionInfoStore), ex);
            }
        }
        Changed?.Invoke();
    }

    private static Dictionary<string, SubscriptionInfo> Load()
    {
        if (_cache != null)
        {
            return _cache;
        }
        try
        {
            var path = Utils.GetConfigPath(FileName);
            if (File.Exists(path))
            {
                _cache = JsonUtils.Deserialize<Dictionary<string, SubscriptionInfo>>(File.ReadAllText(path));
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(SubscriptionInfoStore), ex);
        }
        return _cache ??= new Dictionary<string, SubscriptionInfo>();
    }

    /// <summary>Parses provider headers; returns null when none of the known headers are present.</summary>
    public static SubscriptionInfo? Parse(IReadOnlyDictionary<string, string>? headers)
    {
        if (headers == null || headers.Count == 0)
        {
            return null;
        }
        string? H(string name) => headers.TryGetValue(name, out var v) && v.IsNotEmpty() ? v.Trim() : null;

        var info = new SubscriptionInfo
        {
            Title = DecodeMaybeBase64(H("profile-title")),
            Announce = DecodeMaybeBase64(H("announce")),
            SupportUrl = H("support-url"),
            WebPageUrl = H("profile-web-page-url"),
            UpdatedAt = DateTimeOffset.Now.ToUnixTimeSeconds(),
        };
        if (int.TryParse(H("profile-update-interval"), out var hours))
        {
            info.UpdateIntervalHours = hours;
        }

        var userInfo = H("subscription-userinfo");
        if (userInfo != null)
        {
            foreach (var part in userInfo.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
            {
                var kv = part.Split('=', 2, StringSplitOptions.TrimEntries);
                if (kv.Length != 2 || !double.TryParse(kv[1], NumberStyles.Float, CultureInfo.InvariantCulture, out var number)
                    || double.IsNaN(number) || number < 0)
                {
                    continue;
                }
                // "Unlimited" is sometimes sent as a huge number; a plain cast overflows (checked build).
                var value = number >= 9.2e18 ? long.MaxValue / 4 : (long)number;
                switch (kv[0].ToLowerInvariant())
                {
                    case "upload": info.Upload = value; break;
                    case "download": info.Download = value; break;
                    case "total": info.Total = value; break;
                    case "expire": info.Expire = value; break;
                }
            }
        }

        var any = info.Title != null || info.Announce != null || info.SupportUrl != null || userInfo != null;
        return any ? info : null;
    }

    private static string? DecodeMaybeBase64(string? value)
    {
        if (value == null)
        {
            return null;
        }
        if (value.StartsWith("base64:", StringComparison.OrdinalIgnoreCase))
        {
            try
            {
                return Encoding.UTF8.GetString(Convert.FromBase64String(value[7..].Trim())).Trim();
            }
            catch (FormatException)
            {
                return null;
            }
        }
        // Header values are transported as Latin-1; recover UTF-8 text sent raw by some panels.
        try
        {
            var bytes = Encoding.Latin1.GetBytes(value);
            var utf8 = Encoding.UTF8.GetString(bytes);
            return utf8.Contains('�') ? value : utf8;
        }
        catch
        {
            return value;
        }
    }
}

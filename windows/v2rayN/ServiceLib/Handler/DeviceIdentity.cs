using System.Security.Cryptography;

namespace ServiceLib.Handler;

/// <summary>
/// Per-install device identifier sent to subscription providers that enforce device limits
/// (the "x-hwid" convention of Remnawave-style panels). Random, not derived from hardware,
/// stored next to the app config so it stays stable across restarts.
/// </summary>
public static class DeviceIdentity
{
    private const string FileName = "hwid.txt";
    private static readonly object _lock = new();
    private static string? _cached;

    public static string Hwid()
    {
        lock (_lock)
        {
            if (_cached != null)
            {
                return _cached;
            }
            var path = Utils.GetConfigPath(FileName);
            try
            {
                if (File.Exists(path))
                {
                    var saved = File.ReadAllText(path).Trim();
                    if (saved.Length > 0)
                    {
                        return _cached = saved;
                    }
                }
                var generated = Convert.ToHexString(RandomNumberGenerator.GetBytes(8));
                File.WriteAllText(path, generated);
                return _cached = generated;
            }
            catch (Exception ex)
            {
                Logging.SaveLog(nameof(DeviceIdentity), ex);
                return _cached = Convert.ToHexString(RandomNumberGenerator.GetBytes(8));
            }
        }
    }

    /// <summary>Default headers for subscription requests; user-configured headers override them.</summary>
    public static Dictionary<string, string> SubscriptionHeaders()
    {
        return new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["x-hwid"] = Hwid(),
            ["x-device-os"] = "Windows",
            ["x-ver-os"] = Environment.OSVersion.Version.ToString(),
            ["x-device-model"] = "PC",
        };
    }
}

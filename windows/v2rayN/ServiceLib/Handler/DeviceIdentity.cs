using System.Security.Cryptography;

namespace ServiceLib.Handler;

/// <summary>
/// Device identifier sent to subscription providers that enforce device limits
/// (the "x-hwid" convention of Remnawave-style panels). A one-way hash of the Windows
/// MachineGuid, so a reinstall or a second (portable) copy is not counted as a new device.
/// Saved next to the app config; an ID saved by an older build is kept as is.
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
                var generated = FromMachineGuid() ?? Convert.ToHexString(RandomNumberGenerator.GetBytes(8));
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

    private static string? FromMachineGuid()
    {
        try
        {
            if (!OperatingSystem.IsWindows())
            {
                return null;
            }
            using var key = Microsoft.Win32.RegistryKey.OpenBaseKey(Microsoft.Win32.RegistryHive.LocalMachine, Microsoft.Win32.RegistryView.Registry64)
                .OpenSubKey(@"SOFTWARE\Microsoft\Cryptography");
            var guid = key?.GetValue("MachineGuid") as string;
            if (guid.IsNullOrEmpty())
            {
                return null;
            }
            var hash = SHA256.HashData(System.Text.Encoding.UTF8.GetBytes("flowveil:" + guid));
            return Convert.ToHexString(hash, 0, 8);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(DeviceIdentity), ex);
            return null;
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

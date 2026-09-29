// End-to-end check of "paste a subscription link -> servers appear", run in CI on Windows.
// Uses public test subscriptions from this repository, never a real user's link.
using ServiceLib;
using ServiceLib.Common;
using ServiceLib.Handler;
using ServiceLib.Manager;
using ServiceLib.Services;

var baseUrl = args.Length > 0 ? args[0] : "https://raw.githubusercontent.com/xoyzoom-lgtm/hupp-proxy/main/windows/selftest";
var failures = 0;

if (!AppManager.Instance.InitApp() || !AppManager.Instance.InitComponents())
{
    Console.WriteLine("FAIL: app init");
    return 1;
}
var config = AppManager.Instance.Config;
// Same startup step as MainWindowViewModel.Init: HTTPS downloads need the certificate policy.
await CertPemManager.Instance.Init(config);

// 1. DNS-over-HTTPS resolver used when the ISP hides the provider's domain.
try
{
    var ips = await SecureDns.ResolveAsync("raw.githubusercontent.com", CancellationToken.None);
    Console.WriteLine($"OK   secure DNS: {string.Join(", ", ips.Select(i => i.ToString()))}");
}
catch (Exception ex)
{
    Console.WriteLine($"FAIL secure DNS: {ex.Message}");
    failures++;
}

// 2. Paste each subscription link and update it, exactly like the "Добавить" button.
foreach (var file in new[] { "sub-base64.txt", "sub-xray-json.json" })
{
    var url = $"{baseUrl}/{file}";
    var before = (await AppManager.Instance.SubItems()).Select(s => s.Id).ToHashSet();
    var added = await ConfigHandler.AddBatchServers(config, url + "\r\n", config.SubIndexId, false);
    var sub = (await AppManager.Instance.SubItems()).FirstOrDefault(s => !before.Contains(s.Id));
    if (added <= 0 || sub == null)
    {
        Console.WriteLine($"FAIL {file}: link not recognised as a subscription (added={added})");
        failures++;
        continue;
    }

    await SubscriptionHandler.UpdateProcess(config, sub.Id, false, (ok, msg) =>
    {
        Console.WriteLine($"     [{file}] {msg}");
        return Task.CompletedTask;
    });
    var servers = await AppManager.Instance.ProfileItems(sub.Id) ?? [];
    if (servers.Count == 2)
    {
        Console.WriteLine($"OK   {file}: {servers.Count} servers: {string.Join(" | ", servers.Select(s => $"{s.ConfigType} {s.Remarks}"))}");
    }
    else
    {
        Console.WriteLine($"FAIL {file}: {servers.Count} servers, reason: {SubscriptionHandler.LastError}");
        failures++;
    }
}

Console.WriteLine(failures == 0 ? "ALL OK" : $"{failures} FAILED");
return failures == 0 ? 0 : 1;

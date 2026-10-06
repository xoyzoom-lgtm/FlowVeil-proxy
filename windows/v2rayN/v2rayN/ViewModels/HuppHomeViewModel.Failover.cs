using ServiceLib.Services;
using System.Collections.Concurrent;

namespace v2rayN.ViewModels;

/// <summary>
/// Keeps the connection alive: while connected, every ~20 s traffic is checked through the local port of the core. When the server fails twice in
/// a row while the computer itself is online, FlowVeil tests other servers (the same subscription first, then the other usable ones) and switches
/// to the fastest one that works, then says so. The decisions are the pure <see cref="FailoverState"/> and <see cref="FailoverPlan"/>.
/// </summary>
public partial class HuppHomeViewModel
{
    private static readonly TimeSpan FailoverInterval = TimeSpan.FromSeconds(20);
    private readonly FailoverState _failover = new();
    private bool _failoverBusy;
    private DateTimeOffset _lastNoServerNotice = DateTimeOffset.MinValue;

    /// <summary>A switch (or a failed search) happened; the window shows it as a tray notification. Title, text.</summary>
    public event Action<string, string>? FailoverNotice;

    private void StartFailoverLoop() => _ = FailoverLoopAsync();

    private async Task FailoverLoopAsync()
    {
        while (true)
        {
            await Task.Delay(FastMode.CheckInterval(FailoverInterval, FastMode.Enabled));
            try
            {
                await FailoverTickAsync();
            }
            catch (Exception ex)
            {
                Logging.SaveLog(nameof(HuppHomeViewModel), ex);
            }
        }
    }

    private async Task FailoverTickAsync()
    {
        if (!IsConnected || !FailoverSettings.IsEnabled || IsBusy || _failoverBusy || _config.IndexId.IsNullOrEmpty())
        {
            _failover.Reset();
            return;
        }
        var port = _config.Inbound.FirstOrDefault()?.LocalPort ?? 0;
        if (port <= 0)
        {
            return;
        }
        _failoverBusy = true;
        try
        {
            var action = await CheckOnceAsync(port);
            if (action == FailoverAction.Recheck)
            {
                await Task.Delay(TimeSpan.FromSeconds(5));
                if (!IsConnected)
                {
                    return;
                }
                action = await CheckOnceAsync(port);
            }
            if (action == FailoverAction.Switch)
            {
                await SwitchAwayAsync();
            }
        }
        finally
        {
            _failoverBusy = false;
        }
    }

    private async Task<FailoverAction> CheckOnceAsync(int port)
    {
        var works = await DiagnosticsRunner.TrafficPassesAsync(port);
        var networkUp = works || DiagnosticsRunner.NetworkUp();
        return _failover.Observe(works, networkUp, DateTimeOffset.UtcNow);
    }

    private async Task SwitchAwayAsync()
    {
        var current = await AppManager.Instance.GetProfileItem(_config.IndexId);
        var subs = await AppManager.Instance.SubItems() ?? [];
        var exes = (await ProfileExManager.Instance.GetProfileExs()).ToDictionary(e => e.IndexId, e => (long)e.Delay);
        var facts = new List<ServerFacts>();
        foreach (var sub in subs.Where(s => s.Id.IsNotEmpty()))
        {
            var items = await AppManager.Instance.ProfileItems(sub.Id) ?? [];
            for (var i = 0; i < items.Count; i++)
            {
                var p = items[i];
                facts.Add(new ServerFacts(p.IndexId, p.Subid ?? string.Empty, p.Remarks ?? string.Empty, exes.GetValueOrDefault(p.IndexId),
                    p.ConfigType.ToString().ToUpperInvariant(), p.StreamSecurity ?? string.Empty, p.Network ?? string.Empty, i));
            }
        }
        var order = subs.Where(s => s.Id.IsNotEmpty()).Select(s => s.Id).ToList();
        order = SubsLogic.ApplyOrder(order, _state.Order);
        if (!FailoverSettings.AcrossSubscriptions)
        {
            // Only the subscription of the server in use, unless the user allowed the others.
            order = order.Where(id => id == current?.Subid).ToList();
        }
        var groups = FailoverPlan.Groups(facts, _config.IndexId, current?.Subid ?? string.Empty, order, SubUsable, IsRussianServer);
        var oldName = current?.Remarks ?? string.Empty;
        Logging.SaveLog($"Failover: «{oldName}» does not answer, {groups.Sum(g => g.Count)} candidates in {groups.Count} groups");

        foreach (var group in groups)
        {
            var delays = await TestGroupAsync(group);
            var best = delays.Where(d => d.Value > 0).OrderBy(d => d.Value).Select(d => d.Key).FirstOrDefault();
            if (best == null)
            {
                continue;
            }
            var target = facts.First(f => f.IndexId == best);
            await Profiles.SetDefaultServer(best);
            Logging.SaveLog($"Failover: switched to «{target.Name}» ({delays[best]} ms)");
            PingText = $"Переключился: {target.Name}";
            FailoverNotice?.Invoke("Сервер не отвечал — переключился", $"«{oldName}» → «{target.Name}»");
            // The core restarts: do not judge the new server before it is up.
            await Task.Delay(TimeSpan.FromSeconds(8));
            return;
        }
        Logging.SaveLog("Failover: no working server found");
        if (DateTimeOffset.UtcNow - _lastNoServerNotice > TimeSpan.FromMinutes(10))
        {
            _lastNoServerNotice = DateTimeOffset.UtcNow;
            FailoverNotice?.Invoke("Сервер не отвечает", "Рабочей замены не нашёл. Нажмите «Почему не работает?» в окне FlowVeil");
        }
    }

    /// <summary>Real delay of each server through its own short-lived core (like the ping button); id → ms, -1 when it failed. At most a minute.</summary>
    private async Task<Dictionary<string, int>> TestGroupAsync(List<string> group)
    {
        var results = new ConcurrentDictionary<string, int>();
        var items = await AppManager.Instance.GetProfileItemsByIndexIds(group);
        var service = new SpeedtestService(_config, result =>
        {
            if (result.IndexId.IsNotEmpty() && int.TryParse(result.Delay, out var ms))
            {
                results[result.IndexId!] = ms;
            }
            return Task.CompletedTask;
        });
        var run = service.RunLoop(ESpeedActionType.Realping, items);
        if (await Task.WhenAny(run, Task.Delay(TimeSpan.FromSeconds(60))) != run)
        {
            service.ExitLoop();
        }
        return new Dictionary<string, int>(results);
    }
}

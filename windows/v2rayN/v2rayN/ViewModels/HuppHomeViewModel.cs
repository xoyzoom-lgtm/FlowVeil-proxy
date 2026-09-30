using System.Collections.ObjectModel;
using System.Collections.Specialized;
using System.Runtime.CompilerServices;

namespace v2rayN.ViewModels;

public abstract class HuppObservable : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    protected bool Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value))
        {
            return false;
        }
        field = value;
        Raise(name);
        return true;
    }

    protected void Raise(string? name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
}

/// <summary>One subscription card on the Happ-style home screen.</summary>
public sealed class HuppSubCard : HuppObservable
{
    public required SubItem Sub { get; init; }
    public string Title { get; init; } = string.Empty;
    public string TrafficText { get; init; } = string.Empty;
    public string LeftText { get; init; } = string.Empty;
    public string TrafficDetail { get; init; } = string.Empty;
    public string ExpireDetail { get; init; } = string.Empty;
    public string ExpireText { get; init; } = string.Empty;
    public string Announce { get; init; } = string.Empty;
    public string SupportUrl { get; init; } = string.Empty;
    public double Progress { get; init; }
    public bool HasProgress { get; init; }

    private bool _isSelected;
    public bool IsSelected { get => _isSelected; set => Set(ref _isSelected, value); }

    public SubHealth Health { get; init; }

    /// <summary>What is wrong with the subscription, in words; empty when all is well.</summary>
    public string HealthText { get; init; } = string.Empty;

    /// <summary>Expired or out of traffic: a red banner with what to do.</summary>
    public bool IsBlocking { get; init; }

    /// <summary>The provider's announcement is long: it is folded to two lines with "Показать полностью".</summary>
    public bool AnnounceIsLong { get; init; }

    private bool _announceExpanded;
    public bool AnnounceExpanded { get => _announceExpanded; set { if (Set(ref _announceExpanded, value)) { Raise(nameof(AnnounceMaxHeight)); Raise(nameof(AnnounceToggleText)); } } }
    /// <summary>Two lines of 18 px while folded (WPF has no MaxLines).</summary>
    public double AnnounceMaxHeight => AnnounceExpanded || !AnnounceIsLong ? double.PositiveInfinity : 36;
    public string AnnounceToggleText => AnnounceExpanded ? "Свернуть" : "Показать полностью";
}

/// <summary>
/// Home screen state: subscription cards, the server list and a single connect button.
/// "Connected" means traffic is routed through the core, via system proxy or TUN.
/// </summary>
public sealed partial class HuppHomeViewModel : HuppObservable
{
    public const string ModeProxy = "proxy";
    public const string ModeTunSingbox = "tun-singbox";
    public const string ModeTunGvisor = "tun-gvisor";
    public const string ModeTunXray = "tun-xray";
    public const string ModeLocal = "local";

    /// <summary>Transport modes shown in the Happ-style picker; Group is the section header.</summary>
    public static readonly IReadOnlyList<(string Id, string Group, string Title)> Modes =
    [
        (ModeProxy, "Прокси", "Системный прокси"),
        (ModeTunSingbox, "TUN", "sing-box"),
        (ModeTunGvisor, "TUN", "sing-box (gVisor)"),
        (ModeTunXray, "TUN", "Xray TUN"),
        (ModeLocal, "Другое", "Только локальный порт"),
    ];

    public static bool IsTunMode(string mode) => mode.StartsWith("tun-", StringComparison.Ordinal);

    private static readonly string ModeFile = "hupp_mode.txt";
    private readonly Config _config;
    private readonly DispatcherTimer _timer;
    private DateTime? _connectedSince;
    private bool _localConnected;

    public ProfilesViewModel Profiles { get; }
    public StatusBarViewModel Status { get; }
    public ObservableCollection<HuppSubCard> Cards { get; } = [];

    public HuppHomeViewModel(ProfilesViewModel profiles, StatusBarViewModel status)
    {
        _config = AppManager.Instance.Config;
        Profiles = profiles;
        Status = status;
        _mode = LoadMode();

        InitSubs();
        Profiles.SubItems.CollectionChanged += (_, _) =>
        {
            RebuildCards();
            RebuildListSoon();
        };
        Profiles.ProfileItems.CollectionChanged += OnProfilesChanged;
        Profiles.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(ProfilesViewModel.SelectedSub))
            {
                UpdateCardSelection();
                SyncSelectionFromProfiles();
            }
        };
        Status.PropertyChanged += OnStatusChanged;
        SubscriptionInfoStore.Changed += () => Application.Current?.Dispatcher.BeginInvoke(new Action(RebuildCards));

        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _timer.Tick += (_, _) => UpdateTimer();

        RebuildCards();
        UpdateConnection();
        UpdateSelectedServer();
    }

    #region Bindable state

    private bool _isConnected;
    public bool IsConnected { get => _isConnected; private set => Set(ref _isConnected, value); }

    private string _statusText = "Отключено";
    public string StatusText { get => _statusText; private set => Set(ref _statusText, value); }

    private string _timerText = "00:00:00";
    public string TimerText { get => _timerText; private set => Set(ref _timerText, value); }

    private string _mode;
    public string Mode
    {
        get => _mode;
        set
        {
            if (value.IsNullOrEmpty() || !Set(ref _mode, value))
            {
                return;
            }
            SaveMode(value);
            Raise(nameof(ModeTitle));
            if (IsConnected)
            {
                _ = ConnectAsync();
            }
        }
    }

    public string ModeTitle
    {
        get
        {
            var mode = Modes.FirstOrDefault(m => m.Id == _mode);
            return mode.Id == null ? string.Empty : mode.Group == "TUN" ? $"TUN · {mode.Title}" : mode.Title;
        }
    }

    private string _serverCode = string.Empty;
    public string ServerCode { get => _serverCode; private set => Set(ref _serverCode, value); }

    private string _serverName = "Сервер не выбран";
    public string ServerName { get => _serverName; private set => Set(ref _serverName, value); }

    private string _serverDescription = string.Empty;
    public string ServerDescription { get => _serverDescription; private set => Set(ref _serverDescription, value); }

    private string _hintText = string.Empty;
    public string HintText { get => _hintText; private set => Set(ref _hintText, value); }

    private bool _hasServer;

    private void UpdateHint()
    {
        HintText = !_hasServer
            ? "Сначала добавьте подписку — кнопка «Добавить» слева"
            : IsConnected ? string.Empty : "Нажмите на кнопку, чтобы подключиться";
    }

    private string _pingText = string.Empty;
    public string PingText { get => _pingText; private set => Set(ref _pingText, value); }

    private string _speedText = string.Empty;
    public string SpeedText { get => _speedText; private set => Set(ref _speedText, value); }

    private bool _isBusy;
    public bool IsBusy { get => _isBusy; private set => Set(ref _isBusy, value); }

    #endregion Bindable state

    #region Actions

    public async Task ToggleAsync()
    {
        if (IsConnected)
        {
            await DisconnectAsync();
        }
        else
        {
            await ConnectAsync();
        }
    }

    public async Task ConnectAsync()
    {
        if (IsTunMode(_mode))
        {
            // TUN engine: sing-box handles the TUN device ("legacy protect"), or Xray does it itself.
            var tun = _config.TunModeItem;
            var legacy = _mode != ModeTunXray;
            var stack = _mode == ModeTunGvisor ? "gvisor" : _mode == ModeTunSingbox ? "system" : tun.Stack;
            var changed = tun.EnableLegacyProtect != legacy || tun.Stack != stack;
            tun.EnableLegacyProtect = legacy;
            tun.Stack = stack;
            _localConnected = false;
            Status.SystemProxySelected = (int)ESysProxyType.ForcedClear;
            if (changed)
            {
                await ConfigHandler.SaveConfig(_config);
            }
            if (Status.EnableTun && changed)
            {
                Status.ReloadRequested.Publish();
            }
            Status.EnableTun = true;
        }
        else if (_mode == ModeLocal)
        {
            // Core already listens on the local port; just make sure nothing else is redirected.
            Status.EnableTun = false;
            Status.SystemProxySelected = (int)ESysProxyType.ForcedClear;
            _localConnected = true;
        }
        else
        {
            _localConnected = false;
            Status.EnableTun = false;
            Status.SystemProxySelected = (int)ESysProxyType.ForcedChange;
        }
        UpdateConnection();
    }

    public async Task DisconnectAsync()
    {
        _localConnected = false;
        Status.EnableTun = false;
        Status.SystemProxySelected = (int)ESysProxyType.ForcedClear;
        UpdateConnection();
        await Task.CompletedTask;
    }

    public async Task SelectServerAsync(ProfileItemModel? item)
    {
        if (item == null || item.IndexId.IsNullOrEmpty() || item.IndexId == _config.IndexId)
        {
            return;
        }
        await Profiles.SetDefaultServer(item.IndexId);
    }

    public void SelectCard(HuppSubCard? card)
    {
        if (card == null)
        {
            return;
        }
        Profiles.SelectedSub = card.Sub;
    }

    /// <summary>
    /// Tests the servers of the current chip (one subscription, "Все" = all switched-on ones, "Избранное"), then selects the fastest and connects.
    /// Russian servers are never "the best" (they unblock nothing); expired, out-of-traffic and switched-off subscriptions are excluded always.
    /// </summary>
    public async Task ConnectBestAsync()
    {
        if (IsBusy || Profiles.ProfileItems.Count == 0)
        {
            return;
        }
        IsBusy = true;
        PingText = $"Ищу лучший: {ScopeTitle}…";
        try
        {
            var scope = ScopeFacts();
            var finished = new TaskCompletionSource();
            void OnFinished() => finished.TrySetResult();
            Profiles.SpeedtestFinished += OnFinished;
            try
            {
                await Profiles.ServerSpeedtestSubset(scope.Where(s => SubUsable(s.SubId)).Select(s => s.IndexId).ToHashSet());
                await Task.WhenAny(finished.Task, Task.Delay(TimeSpan.FromMinutes(3)));
            }
            finally
            {
                Profiles.SpeedtestFinished -= OnFinished;
            }
            // Delays changed: read the scope again.
            var best = SubsLogic.Best(ScopeFacts(), SubUsable, IsRussianServer);
            var model = best == null ? null : Profiles.ProfileItems.FirstOrDefault(t => t.IndexId == best.IndexId);
            if (model == null)
            {
                PingText = "Рабочий сервер не найден";
                return;
            }
            await SelectServerAsync(model);
            if (!IsConnected)
            {
                await ConnectAsync();
            }
            PingText = $"Лучший: {model.Remarks} · {model.Delay} мс";
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(HuppHomeViewModel), ex);
            PingText = "Ошибка";
        }
        finally
        {
            IsBusy = false;
        }
    }

    private static readonly string[] RussianMarkers =
    [
        "🇷🇺", "россия", "russia", "москва", "moscow", "санкт-петербург", "петербург", "спб",
        "st. petersburg", "saint petersburg", "новосибирск", "екатеринбург",
    ];

    private static bool IsRussianServer(string? remarks)
    {
        if (remarks.IsNullOrEmpty())
        {
            return false;
        }
        var name = remarks!.ToLowerInvariant();
        return RussianMarkers.Any(m => name.Contains(m, StringComparison.Ordinal))
            || System.Text.RegularExpressions.Regex.IsMatch(name, "(^|[^a-z])ru([^a-z]|$)");
    }

    /// <summary>Tests the servers of the current chip; a second press stops the run. The list stays usable meanwhile.</summary>
    public async Task PingAllAsync()
    {
        if (IsPinging)
        {
            Profiles.ServerSpeedtestStop();
            IsPinging = false;
            return;
        }
        IsPinging = true;
        await Profiles.ServerSpeedtestSubset(ScopeFacts().Select(s => s.IndexId).ToHashSet());
    }

    /// <summary>Tests the servers of one subscription (the group button), whatever chip is open.</summary>
    public async Task PingSubscriptionAsync(string subId)
    {
        if (IsPinging)
        {
            Profiles.ServerSpeedtestStop();
            IsPinging = false;
            return;
        }
        IsPinging = true;
        await Profiles.ServerSpeedtestSubset(FactsOf(Profiles.ProfileItems.ToList()).Where(s => s.SubId == subId).Select(s => s.IndexId).ToHashSet());
    }

    /// <summary>Just the number: "123 мс", or a dash when there is no answer.</summary>
    private static string FormatPing(long ms) => ms > 0 ? $"{ms} мс" : "—";

    private int _pingTick;
    private bool _pingBusy;

    /// <summary>Measures the connected server without any prompt and shows the number.</summary>
    private async void RefreshPingQuietly()
    {
        if (_pingBusy || IsBusy || !IsConnected)
        {
            return;
        }
        _pingBusy = true;
        try
        {
            var result = await Status.TestServerAvailability();
            PingText = FormatPing(result?.Time ?? 0);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(HuppHomeViewModel), ex);
        }
        finally
        {
            _pingBusy = false;
        }
    }

    private readonly HashSet<string> _autoPinged = [];
    private bool _autoPingScheduled;

    /// <summary>New servers (first start, after a subscription update) get their ping without a click.</summary>
    private async void ScheduleAutoPingAll()
    {
        if (_autoPingScheduled || IsBusy)
        {
            return;
        }
        var fresh = Profiles.ProfileItems.Where(p => !_autoPinged.Contains(p.IndexId)).ToList();
        if (fresh.Count == 0)
        {
            return;
        }
        _autoPingScheduled = true;
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(2.5));
            fresh = Profiles.ProfileItems.Where(p => !_autoPinged.Contains(p.IndexId)).ToList();
            if (fresh.Count == 0)
            {
                return;
            }
            foreach (var p in fresh)
            {
                _autoPinged.Add(p.IndexId);
            }
            await PingAllAsync();
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(HuppHomeViewModel), ex);
        }
        finally
        {
            _autoPingScheduled = false;
        }
    }

    public async Task TestCurrentAsync()
    {
        if (IsBusy)
        {
            return;
        }
        IsBusy = true;
        PingText = "Проверяю…";
        try
        {
            var result = await Status.TestServerAvailability();
            PingText = FormatPing(result?.Time ?? 0);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(HuppHomeViewModel), ex);
            PingText = "Ошибка";
        }
        finally
        {
            IsBusy = false;
        }
    }

    #endregion Actions

    #region Updates

    private void OnStatusChanged(object? sender, PropertyChangedEventArgs e)
    {
        switch (e.PropertyName)
        {
            case nameof(StatusBarViewModel.BlSystemProxySet):
            case nameof(StatusBarViewModel.BlSystemProxyPac):
            case nameof(StatusBarViewModel.EnableTun):
                UpdateConnection();
                break;

            case nameof(StatusBarViewModel.SpeedProxyDisplay):
                SpeedText = Status.SpeedProxyDisplay ?? string.Empty;
                break;

            case nameof(StatusBarViewModel.RunningServerDisplay):
                UpdateSelectedServer();
                RefreshActiveFlags();
                break;
        }
    }

    private void OnProfilesChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        UpdateSelectedServer();
        ScheduleAutoPingAll();
        RebuildListSoon();
    }

    private void UpdateConnection()
    {
        var connected = Status.EnableTun || Status.BlSystemProxySet || Status.BlSystemProxyPac || _localConnected;
        if (connected && _connectedSince == null)
        {
            _connectedSince = DateTime.Now;
        }
        else if (!connected)
        {
            _connectedSince = null;
        }
        var justConnected = connected && !IsConnected;
        IsConnected = connected;
        StatusText = connected ? "Подключено" : "Отключено";
        if (justConnected)
        {
            _pingTick = 0;
            PingText = string.Empty;
            _ = Task.Delay(TimeSpan.FromSeconds(3)).ContinueWith(_ => Application.Current?.Dispatcher.BeginInvoke(new Action(RefreshPingQuietly)));
        }
        else if (!connected)
        {
            PingText = string.Empty;
        }
        if (connected && Status.EnableTun && !IsTunMode(_mode))
        {
            // TUN switched on elsewhere (classic view, hotkey): show it without reconnecting.
            _mode = _config.TunModeItem.EnableLegacyProtect ? ModeTunSingbox : ModeTunXray;
            Raise(nameof(Mode));
            Raise(nameof(ModeTitle));
        }
        UpdateHint();
        if (connected)
        {
            _timer.Start();
        }
        else
        {
            _timer.Stop();
        }
        UpdateTimer();
    }

    private void UpdateTimer()
    {
        if (_connectedSince != null && ++_pingTick % 30 == 0)
        {
            RefreshPingQuietly();
        }
        var elapsed = _connectedSince is { } since ? DateTime.Now - since : TimeSpan.Zero;
        TimerText = $"{(int)elapsed.TotalHours:00}:{elapsed.Minutes:00}:{elapsed.Seconds:00}";
    }

    private async void UpdateSelectedServer()
    {
        try
        {
            var model = Profiles.ProfileItems.FirstOrDefault(t => t.IndexId == _config.IndexId);
            if (model == null)
            {
                var item = await ConfigHandler.GetDefaultServer(_config);
                if (item != null)
                {
                    model = new ProfileItemModel
                    {
                        IndexId = item.IndexId,
                        ConfigType = item.ConfigType,
                        Remarks = item.Remarks,
                        Address = item.Address,
                        Network = item.Network,
                        StreamSecurity = item.StreamSecurity,
                    };
                }
            }

            _hasServer = model != null;
            UpdateHint();
            if (model == null)
            {
                ServerCode = string.Empty;
                ServerName = "Сервер не выбран";
                ServerDescription = "Выберите сервер в списке";
                return;
            }

            var (code, name) = HuppProfileText.SplitFlag(model.Remarks);
            ServerCode = code;
            ServerName = name;
            ServerDescription = HuppProfileText.Describe(model);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(HuppHomeViewModel), ex);
        }
    }

    private void RebuildCards()
    {
        Cards.Clear();
        foreach (var sub in Profiles.SubItems.Where(t => t.Id.IsNotEmpty()))
        {
            Cards.Add(MakeCard(sub));
        }
        UpdateCardSelection();
        UpdateSelectedCard();
    }

    private void UpdateCardSelection()
    {
        var selectedId = Profiles.SelectedSub?.Id;
        foreach (var card in Cards)
        {
            card.IsSelected = card.Sub.Id == selectedId;
        }
    }

    private HuppSubCard MakeCard(SubItem sub)
    {
        var info = SubscriptionInfoStore.Get(sub.Id);
        var title = LocalTitle(sub, info);
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var facts = MakeFacts(sub, now);
        var health = SubsLogic.HealthOf(facts, now);
        var healthText = HealthText(health, facts, now);
        var blocking = health is SubHealth.Expired or SubHealth.TrafficOver;
        if (info == null)
        {
            return new HuppSubCard { Sub = sub, Title = title, Health = health, HealthText = healthText, IsBlocking = blocking };
        }

        var traffic = string.Empty;
        var trafficDetail = string.Empty;
        var progress = 0d;
        // A "total" beyond ~1 PB is a panel's way of saying unlimited.
        if (info.Total > 0 && info.Total < 1L << 50)
        {
            traffic = $"{Utils.HumanFy(info.Used / 1024)} из {Utils.HumanFy(info.Total / 1024)}";
            trafficDetail = $"осталось {Utils.HumanFy(Math.Max(0, info.Total - info.Used) / 1024)}";
            progress = Math.Clamp((info.Total - info.Used) * 100d / info.Total, 0, 100);
        }
        else if (info.Used > 0 || info.Expire > 0)
        {
            traffic = $"{Utils.HumanFy(info.Used / 1024)} из ∞";
            trafficDetail = "безлимит";
        }
        var left = string.Empty;

        var expire = string.Empty;
        var expireDetail = string.Empty;
        if (info.ExpireSeconds is > 0 and < 253_402_300_799L)
        {
            var date = DateTimeOffset.FromUnixTimeSeconds(info.ExpireSeconds).LocalDateTime;
            expire = $"до {date:d MMM}";
            var days = (date - DateTime.Now).TotalDays;
            expireDetail = $"осталось {(days <= 0 ? 0 : days > 36500 ? 36500 : (int)days)} дн.";
        }

        return new HuppSubCard
        {
            Sub = sub,
            Title = title,
            TrafficText = traffic,
            TrafficDetail = trafficDetail,
            ExpireDetail = expireDetail,
            LeftText = left,
            ExpireText = expire,
            Announce = TextSanitizer.ForWpf(info.Announce),
            AnnounceIsLong = TextSanitizer.ForWpf(info.Announce).Length > 110 || TextSanitizer.ForWpf(info.Announce).Contains('\n'),
            SupportUrl = info.SupportUrl ?? string.Empty,
            Progress = progress,
            HasProgress = info.Total > 0 && info.Total < 1L << 50,
            Health = health,
            HealthText = healthText,
            IsBlocking = blocking,
        };
    }

    #endregion Updates

    private static string LoadMode()
    {
        try
        {
            var path = Utils.GetConfigPath(ModeFile);
            if (File.Exists(path))
            {
                var saved = File.ReadAllText(path).Trim();
                if (Modes.Any(m => m.Id == saved))
                {
                    return saved;
                }
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(HuppHomeViewModel), ex);
        }
        var tun = AppManager.Instance.Config.TunModeItem;
        return tun.EnableTun ? (tun.EnableLegacyProtect ? ModeTunSingbox : ModeTunXray) : ModeProxy;
    }

    private static void SaveMode(string mode)
    {
        try
        {
            File.WriteAllText(Utils.GetConfigPath(ModeFile), mode);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(HuppHomeViewModel), ex);
        }
    }
}

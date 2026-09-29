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

    /// <summary>Traffic value line, e.g. "12.3 GB / 100 GB" or "12.3 GB · безлимит".</summary>
    public string TrafficValue { get; init; } = string.Empty;
    public bool HasTraffic => TrafficValue.Length > 0;
    public bool HasExpire => ExpireText.Length > 0;
    public bool HasMetrics => HasTraffic || HasExpire;
    /// <summary>"ok", "warn" (3 days or less) or "bad" (expired).</summary>
    public string ExpireState { get; init; } = "ok";
    /// <summary>Long announcements start folded to two lines.</summary>
    public bool AnnounceIsLong { get; init; }

    private bool _isSelected;
    public bool IsSelected { get => _isSelected; set => Set(ref _isSelected, value); }
}

/// <summary>
/// Home screen state: subscription cards, the server list and a single connect button.
/// "Connected" means traffic is routed through the core, via system proxy or TUN.
/// </summary>
public sealed class HuppHomeViewModel : HuppObservable
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
        if (IsTunMode(_mode))
        {
            _lastTunMode = _mode;
        }

        Profiles.SubItems.CollectionChanged += (_, _) => RebuildCards();
        Profiles.ProfileItems.CollectionChanged += OnProfilesChanged;
        Profiles.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(ProfilesViewModel.SelectedSub))
            {
                UpdateCardSelection();
            }
        };
        Status.PropertyChanged += OnStatusChanged;
        SubscriptionInfoStore.Changed += () => Application.Current?.Dispatcher.BeginInvoke(new Action(RebuildCards));

        _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _timer.Tick += (_, _) => UpdateTimer();

        RebuildCards();
        UpdateConnection();
        OnProfilesChanged(null, new NotifyCollectionChangedEventArgs(NotifyCollectionChangedAction.Reset));
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
            if (IsTunMode(value))
            {
                _lastTunMode = value;
            }
            RaiseModeProperties();
            if (IsConnected)
            {
                _ = ConnectAsync();
            }
        }
    }

    private void RaiseModeProperties()
    {
        Raise(nameof(ModeTitle));
        Raise(nameof(IsModeProxy));
        Raise(nameof(IsModeTun));
        Raise(nameof(IsModeLocal));
        Raise(nameof(ModeHint));
        Raise(nameof(TunEngineTitle));
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

    /// <summary>Power button state: "Off", "Connecting", "On" or "Error".</summary>
    private string _powerState = "Off";
    public string PowerState { get => _powerState; private set => Set(ref _powerState, value); }

    private ProfileItemModel? _activeServer;
    /// <summary>The selected server row (its DelayVal drives the ping pill on the connect pane).</summary>
    public ProfileItemModel? ActiveServer { get => _activeServer; private set => Set(ref _activeServer, value); }

    private string _bestCode = string.Empty;
    public string BestCode { get => _bestCode; private set => Set(ref _bestCode, value); }

    private string _bestLine = string.Empty;
    /// <summary>"Казахстан | Игровой · 50 мс" under the Best button; empty until a search ran.</summary>
    public string BestLine { get => _bestLine; private set => Set(ref _bestLine, value); }

    public string SpeedDownText => Status.SpeedDownText ?? string.Empty;
    public string SpeedUpText => Status.SpeedUpText ?? string.Empty;
    public bool HasSpeed => IsConnected && SpeedDownText.Length > 0;

    public bool IsModeProxy
    {
        get => _mode == ModeProxy;
        set { if (value) { Mode = ModeProxy; } }
    }

    public bool IsModeTun
    {
        get => IsTunMode(_mode);
        set { if (value && !IsTunMode(_mode)) { Mode = _lastTunMode; } }
    }

    public bool IsModeLocal
    {
        get => _mode == ModeLocal;
        set { if (value) { Mode = ModeLocal; } }
    }

    private string _lastTunMode = ModeTunSingbox;

    public string ModeHint => _mode switch
    {
        ModeLocal => "Только локальный порт: программы подключаются к нему сами",
        _ when IsTunMode(_mode) => "Весь трафик компьютера, включая игры и мессенджеры",
        _ => "Браузеры и большинство программ через системный прокси",
    };

    public string TunEngineTitle => Modes.FirstOrDefault(m => m.Id == (IsTunMode(_mode) ? _mode : _lastTunMode)).Title ?? "sing-box";

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
        PowerState = "Connecting";
        StatusText = "Подключение…";
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
        await VerifyConnectionAsync();
    }

    private int _verifyRun;

    /// <summary>
    /// After switching on, one real request through the server decides the button colour:
    /// "On" when it answers, "Error" (red ring, text) when it does not. The connection itself
    /// stays up either way, the user decides.
    /// </summary>
    private async Task VerifyConnectionAsync()
    {
        var run = ++_verifyRun;
        // The core restarts on a mode change; give it a moment before the first request.
        for (var attempt = 0; attempt < 2; attempt++)
        {
            await Task.Delay(attempt == 0 ? 800 : 1500);
            if (run != _verifyRun || !IsConnected)
            {
                return;
            }
            AvailabilityCheckResult? result = null;
            try
            {
                result = await Status.TestServerAvailability();
            }
            catch (Exception ex)
            {
                Logging.SaveLog(nameof(HuppHomeViewModel), ex);
            }
            if (run != _verifyRun || !IsConnected)
            {
                return;
            }
            if (result is { Time: > 0 })
            {
                PowerState = "On";
                StatusText = "Подключено";
                PingText = $"Работает · {result.Time} мс";
                return;
            }
        }
        PowerState = "Error";
        StatusText = "Сервер не отвечает";
        PingText = "Нет соединения — выберите другой сервер";
    }

    public async Task DisconnectAsync()
    {
        _verifyRun++;
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

    /// <summary>Tests the servers of the open subscription, selects the fastest one and connects.</summary>
    public async Task ConnectBestAsync()
    {
        if (IsBusy || Profiles.ProfileItems.Count == 0)
        {
            return;
        }
        IsBusy = true;
        PingText = "Ищу лучший сервер…";
        BestLine = string.Empty;
        try
        {
            var finished = new TaskCompletionSource();
            void OnFinished() => finished.TrySetResult();
            Profiles.SpeedtestFinished += OnFinished;
            try
            {
                await Profiles.ServerSpeedtest(ESpeedActionType.FastRealping);
                await Task.WhenAny(finished.Task, Task.Delay(TimeSpan.FromMinutes(3)));
            }
            finally
            {
                Profiles.SpeedtestFinished -= OnFinished;
            }
            // Servers in Russia answer fastest but unblock nothing, so they are never "the best".
            var best = Profiles.ProfileItems.Where(t => t.Delay > 0 && !IsRussianServer(t.Remarks)).OrderBy(t => t.Delay).FirstOrDefault();
            if (best == null)
            {
                PingText = "Рабочий сервер не найден";
                return;
            }
            var (bestCode, bestName) = HuppProfileText.SplitFlag(best.Remarks);
            BestCode = bestCode;
            BestLine = $"{HuppProfileText.CleanForDisplay(bestName)} · {best.Delay} мс";
            PingText = string.Empty;
            await SelectServerAsync(best);
            if (!IsConnected)
            {
                await ConnectAsync();
            }
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

    public async Task PingAllAsync()
    {
        await Profiles.ServerSpeedtest(ESpeedActionType.FastRealping);
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
            PingText = result == null
                ? "Нет сервера"
                : result.Time > 0 ? $"Работает · {result.Time} мс" : "Нет соединения";
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

            case nameof(StatusBarViewModel.SpeedDownText):
            case nameof(StatusBarViewModel.SpeedUpText):
                Raise(nameof(SpeedDownText));
                Raise(nameof(SpeedUpText));
                Raise(nameof(HasSpeed));
                break;

            case nameof(StatusBarViewModel.RunningServerDisplay):
                UpdateSelectedServer();
                break;
        }
    }

    private void OnProfilesChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        // Bulk refreshes arrive as Reset without NewItems: mark the whole list then.
        var changed = e.NewItems?.OfType<ProfileItemModel>() ?? Profiles.ProfileItems;
        foreach (var item in changed)
        {
            item.IsFavorite = FavoriteServers.IsFavorite(item.IndexId);
        }
        UpdateSelectedServer();
    }

    public void ToggleFavorite(ProfileItemModel? item)
    {
        if (item?.IndexId.IsNullOrEmpty() != false)
        {
            return;
        }
        item.IsFavorite = FavoriteServers.Toggle(item.IndexId);
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
        IsConnected = connected;
        Raise(nameof(HasSpeed));
        if (!connected)
        {
            PowerState = "Off";
            StatusText = "Отключено";
        }
        else if (PowerState == "Off")
        {
            // Switched on elsewhere (tray, hotkey): no verification running, trust the flags.
            PowerState = "On";
            StatusText = "Подключено";
        }
        if (connected && Status.EnableTun && !IsTunMode(_mode))
        {
            // TUN switched on elsewhere (classic view, hotkey): show it without reconnecting.
            _mode = _config.TunModeItem.EnableLegacyProtect ? ModeTunSingbox : ModeTunXray;
            Raise(nameof(Mode));
            RaiseModeProperties();
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
        var elapsed = _connectedSince is { } since ? DateTime.Now - since : TimeSpan.Zero;
        TimerText = $"{(int)elapsed.TotalHours:00}:{elapsed.Minutes:00}:{elapsed.Seconds:00}";
    }

    private async void UpdateSelectedServer()
    {
        try
        {
            var model = Profiles.ProfileItems.FirstOrDefault(t => t.IndexId == _config.IndexId);
            ActiveServer = model;
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
            ServerName = HuppProfileText.CleanForDisplay(name);
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
    }

    private void UpdateCardSelection()
    {
        var selectedId = Profiles.SelectedSub?.Id;
        foreach (var card in Cards)
        {
            card.IsSelected = card.Sub.Id == selectedId;
        }
    }

    private static HuppSubCard MakeCard(SubItem sub)
    {
        var info = SubscriptionInfoStore.Get(sub.Id);
        var title = HuppProfileText.CleanForDisplay(info?.Title.IsNotEmpty() == true ? info.Title! : sub.Remarks);
        if (info == null)
        {
            return new HuppSubCard { Sub = sub, Title = title };
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
        var expireState = "ok";
        if (info.ExpireSeconds is > 0 and < 253_402_300_799L)
        {
            var date = DateTimeOffset.FromUnixTimeSeconds(info.ExpireSeconds).LocalDateTime;
            expire = $"до {date:d MMM}";
            var days = (date - DateTime.Now).TotalDays;
            var whole = days <= 0 ? 0 : days > 36500 ? 36500 : (int)days;
            expireDetail = days <= 0 ? "истекла" : $"осталось {whole} дн.";
            expireState = days <= 0 ? "bad" : days <= 3 ? "warn" : "ok";
        }
        var limited = info.Total > 0 && info.Total < 1L << 50;
        var trafficValue = limited
            ? $"{Utils.HumanFy(info.Used / 1024)} / {Utils.HumanFy(info.Total / 1024)}"
            : info.Used > 0 || info.Expire > 0 ? $"{Utils.HumanFy(info.Used / 1024)} · безлимит" : string.Empty;
        var announce = HuppProfileText.CleanForDisplay(info.Announce);

        return new HuppSubCard
        {
            Sub = sub,
            Title = title,
            TrafficText = traffic,
            TrafficDetail = trafficDetail,
            ExpireDetail = expireDetail,
            LeftText = left,
            ExpireText = expire,
            Announce = announce,
            AnnounceIsLong = announce.Length > 110 || announce.Count(c => c == '\n') >= 2,
            TrafficValue = trafficValue,
            ExpireState = expireState,
            SupportUrl = info.SupportUrl ?? string.Empty,
            Progress = progress,
            HasProgress = info.Total > 0 && info.Total < 1L << 50,
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

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
            : IsConnected ? string.Empty : "Нажмите на кнопку, чтобы включить VPN";
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

            case nameof(StatusBarViewModel.RunningServerDisplay):
                UpdateSelectedServer();
                break;
        }
    }

    private void OnProfilesChanged(object? sender, NotifyCollectionChangedEventArgs e)
    {
        UpdateSelectedServer();
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
        StatusText = connected ? "Подключено" : "Отключено";
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
        var title = info?.Title.IsNotEmpty() == true ? info.Title! : sub.Remarks;
        if (info == null)
        {
            return new HuppSubCard { Sub = sub, Title = title };
        }

        var traffic = string.Empty;
        var trafficDetail = string.Empty;
        var progress = 0d;
        if (info.Total > 0)
        {
            traffic = $"{Utils.HumanFy(info.Used)} из {Utils.HumanFy(info.Total)}";
            trafficDetail = $"осталось {Utils.HumanFy(Math.Max(0, info.Total - info.Used))}";
            progress = Math.Clamp((info.Total - info.Used) * 100d / info.Total, 0, 100);
        }
        else if (info.Used > 0 || info.Expire > 0)
        {
            traffic = $"{Utils.HumanFy(info.Used)} из ∞";
            trafficDetail = "безлимит";
        }
        var left = string.Empty;

        var expire = string.Empty;
        var expireDetail = string.Empty;
        if (info.Expire > 0)
        {
            var date = DateTimeOffset.FromUnixTimeSeconds(info.Expire).LocalDateTime;
            expire = $"до {date:d MMM}";
            expireDetail = $"осталось {Math.Max(0, (int)(date - DateTime.Now).TotalDays)} дн.";
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
            Announce = info.Announce ?? string.Empty,
            SupportUrl = info.SupportUrl ?? string.Empty,
            Progress = progress,
            HasProgress = info.Total > 0,
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

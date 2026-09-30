using System.Windows.Controls;
using System.Windows.Media;
using MaterialDesignThemes.Wpf;
using v2rayN.Base;
using v2rayN.Manager;

namespace v2rayN.Views;

public partial class MainWindow
{
    private static Config _config;
    private readonly SingleReplaceableDisposable _layoutBindingsDisposable = new();
    private BackupAndRestoreView? _backupAndRestoreView;
    private AddPageView? _addPage;
    private bool _tunnelAdvancedOpen;

    public MainWindow()
    {
        InitializeComponent();

        _config = AppManager.Instance.Config;
        ThreadPool.RegisterWaitForSingleObject(App.ProgramStarted, OnProgramStarted, null, -1, false);

        App.Current.SessionEnding += Current_SessionEnding;
        Closing += MainWindow_Closing;
        PreviewKeyDown += MainWindow_PreviewKeyDown;
        menuSettingsSetUWP.Click += MenuSettingsSetUWP_Click;
        menuClose.Click += MenuClose_Click;
        menuCheckUpdate.Click += MenuCheckUpdate_Click;
        btnNewUpdate.Click += MenuCheckUpdate_Click;
        menuBackupAndRestore.Click += MenuBackupAndRestore_Click;

        pbTheme.Content ??= new ThemeSettingView();

        InitNav();
        UpdateNotifier.Changed += () => Dispatcher.BeginInvoke(new Action(RefreshUpdateIndicators));
        UpdateNotifier.NotifyRequested += candidate => Dispatcher.BeginInvoke(new Action(() => ShowUpdateBalloon(candidate)));
        navHome.IsChecked = true;

        this.WhenActivated(disposables =>
        {
            if (homeView.HomeViewModel == null && ViewModel != null)
            {
                homeView.Attach(ViewModel);
                BuildSettingsPage(ViewModel);
                _addPage = new AddPageView(ViewModel, GoToServers);
                addPage.Content = _addPage;
                statsPage.Content = new StatsPageView(ViewModel);
                logsPage.Content = new LogsPageView();
                _ = OpenStartPageAsync();
                UpdateDot();
            }

            //servers
            this.BindCommand(ViewModel, vm => vm.AddVmessServerCmd, v => v.menuAddVmessServer).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddVlessServerCmd, v => v.menuAddVlessServer).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddShadowsocksServerCmd, v => v.menuAddShadowsocksServer).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddSocksServerCmd, v => v.menuAddSocksServer).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddHttpServerCmd, v => v.menuAddHttpServer).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddTrojanServerCmd, v => v.menuAddTrojanServer).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddHysteria2ServerCmd, v => v.menuAddHysteria2Server).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddTuicServerCmd, v => v.menuAddTuicServer).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddWireguardServerCmd, v => v.menuAddWireguardServer).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddAnytlsServerCmd, v => v.menuAddAnytlsServer).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddNaiveServerCmd, v => v.menuAddNaiveServer).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddCustomServerCmd, v => v.menuAddCustomServer).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddCustomOutboundServerCmd, v => v.menuAddCustomOutboundServer).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddPolicyGroupServerCmd, v => v.menuAddPolicyGroupServer).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddProxyChainServerCmd, v => v.menuAddProxyChainServer).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddServerViaClipboardCmd, v => v.menuAddServerViaClipboard).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddServerViaScanCmd, v => v.menuAddServerViaScan).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.AddServerViaImageCmd, v => v.menuAddServerViaImage).DisposeWith(disposables);

            //sub
            this.BindCommand(ViewModel, vm => vm.SubSettingCmd, v => v.menuSubSetting).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.SubUpdateCmd, v => v.menuSubUpdate).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.SubUpdateViaProxyCmd, v => v.menuSubUpdateViaProxy).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.SubGroupUpdateCmd, v => v.menuSubGroupUpdate).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.SubGroupUpdateViaProxyCmd, v => v.menuSubGroupUpdateViaProxy).DisposeWith(disposables);

            //setting
            this.BindCommand(ViewModel, vm => vm.OptionSettingCmd, v => v.menuOptionSetting).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.RoutingSettingCmd, v => v.menuRoutingSetting).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.DNSSettingCmd, v => v.menuDNSSetting).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.FullConfigTemplateCmd, v => v.menuFullConfigTemplate).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.GlobalHotkeySettingCmd, v => v.menuGlobalHotkeySetting).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.RebootAsAdminCmd, v => v.menuRebootAsAdmin).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.ClearServerStatisticsCmd, v => v.menuClearServerStatistics).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.OpenTheFileLocationCmd, v => v.menuOpenTheFileLocation).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.RegionalPresetDefaultCmd, v => v.menuRegionalPresetsDefault).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.RegionalPresetRussiaCmd, v => v.menuRegionalPresetsRussia).DisposeWith(disposables);
            this.BindCommand(ViewModel, vm => vm.RegionalPresetIranCmd, v => v.menuRegionalPresetsIran).DisposeWith(disposables);

            this.BindCommand(ViewModel, vm => vm.ReloadCmd, v => v.menuReload).DisposeWith(disposables);
            this.OneWayBind(ViewModel, vm => vm.BlReloadEnabled, v => v.menuReload.IsEnabled).DisposeWith(disposables);

            this.OneWayBind(ViewModel, vm => vm.BlNewUpdate, v => v.btnNewUpdate.Visibility).DisposeWith(disposables);

            _layoutBindingsDisposable.DisposeWith(disposables);

            this.WhenAnyValue(v => v.ViewModel.MainGirdOrientation)
                .ObserveOn(RxSchedulers.MainThreadScheduler)
                .Subscribe(UpdateLayout)
                .DisposeWith(disposables);

            this.WhenAnyValue(v => v.ViewModel.StatusBarViewModel)
                .Subscribe(vm => ViewHost.Show(contentStatusBarView, vm))
                .DisposeWith(disposables);

            ViewModel.ReadTextFromClipboardInteraction.RegisterHandler(interaction =>
            {
                var clipboardData = WindowsUtils.GetClipboardData();
                interaction.SetOutput(clipboardData);
            }).DisposeWith(disposables);

            ViewModel.ScanScreenInteraction.RegisterHandler(interaction =>
            {
                ShowHideWindow(false);
                if (Application.Current?.MainWindow is { } window)
                {
                    var bytes = QRCodeWindowsUtils.CaptureScreen(window);
                    interaction.SetOutput(bytes);
                }
                ShowHideWindow(true);
            }).DisposeWith(disposables);

            ViewModel.BrowseImageFileInteraction.RegisterHandler(interaction =>
            {
                if (UI.OpenFileDialog(out var fileName, "PNG|*.png|All|*.*") != true)
                {
                    interaction.SetOutput(null);
                    return;
                }
                interaction.SetOutput(fileName);
            }).DisposeWith(disposables);

            ViewModel.ShowHideWindowInteraction.RegisterHandler(interaction =>
            {
                ShowHideWindow(interaction.Input);
                interaction.SetOutput(RxVoid.Default);
            }).DisposeWith(disposables);

            AppEvents.SendSnackMsgRequested
              .AsObservable()
              .ObserveOn(RxSchedulers.MainThreadScheduler)
              .Subscribe(async content => await DelegateSnackMsg(content))
              .DisposeWith(disposables);

            AppEvents.AppExitRequested
              .AsObservable()
              .ObserveOn(RxSchedulers.MainThreadScheduler)
              .Subscribe(_ => StorageUI())
              .DisposeWith(disposables);

            AppEvents.ShutdownRequested
             .AsObservable()
             .ObserveOn(RxSchedulers.MainThreadScheduler)
             .Subscribe(Shutdown)
             .DisposeWith(disposables);
        });

        Title = $"FlowVeil {BuildName()} - {(Utils.IsAdministrator() ? ResUI.RunAsAdmin : ResUI.NotRunAsAdmin)}";
        if (_config.UiItem.AutoHideStartup)
        {
            WindowState = WindowState.Minimized;
        }

        if (!_config.GuiItem.EnableHWA)
        {
            RenderOptions.ProcessRenderMode = RenderMode.SoftwareOnly;
        }

        AddHelpMenuItem();
        WindowsManager.Instance.RegisterGlobalHotkey(_config, OnHotkeyHandler, null);
    }

    #region Event

    private void OnProgramStarted(object state, bool timeout)
    {
        Application.Current?.Dispatcher.Invoke(() =>
        {
            ShowHideWindow(true);
            _ = ImportPendingLinkAsync();
        });
    }

    private async Task DelegateSnackMsg(string content)
    {
        MainSnackbar.MessageQueue?.Enqueue(content);
        await Task.CompletedTask;
    }

    private void OnHotkeyHandler(EGlobalHotkey e)
    {
        switch (e)
        {
            case EGlobalHotkey.ShowForm:
                ShowHideWindow(null);
                break;

            case EGlobalHotkey.SystemProxyClear:
            case EGlobalHotkey.SystemProxySet:
            case EGlobalHotkey.SystemProxyUnchanged:
            case EGlobalHotkey.SystemProxyPac:
                AppEvents.SysProxyChangeRequested.Publish((ESysProxyType)((int)e - 1));
                break;
        }
    }

    private void MainWindow_Closing(object? sender, CancelEventArgs e)
    {
        e.Cancel = true;
        ShowHideWindow(false);
    }

    private async void Current_SessionEnding(object sender, SessionEndingCancelEventArgs e)
    {
        Logging.SaveLog("Current_SessionEnding");
        StorageUI();
        await AppManager.Instance.AppExitAsync(false);
    }

    private void Shutdown(bool obj)
    {
        Application.Current.Shutdown();
    }

    private void MainWindow_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (Keyboard.IsKeyDown(Key.LeftCtrl) || Keyboard.IsKeyDown(Key.RightCtrl))
        {
            switch (e.Key)
            {
                case Key.V:
                    if (Keyboard.FocusedElement is TextBox)
                    {
                        return;
                    }
                    AddServerViaClipboardAsync().ContinueWith(_ => { });

                    break;

                case Key.S:
                    ScanScreenTaskAsync().ContinueWith(_ => { });
                    break;

                case >= Key.D1 and <= Key.D5:
                    e.Handled = NavigateByIndex(e.Key - Key.D0);
                    break;

                case >= Key.NumPad1 and <= Key.NumPad5:
                    e.Handled = NavigateByIndex(e.Key - Key.NumPad0);
                    break;
            }
        }
        else
        {
            if (e.Key == Key.F5)
            {
                ViewModel?.Reload();
            }
        }
    }

    private void MenuClose_Click(object sender, RoutedEventArgs e)
    {
        StorageUI();
        ShowHideWindow(false);
    }

    private void MenuSettingsSetUWP_Click(object sender, RoutedEventArgs e)
    {
        ProcUtils.ProcessStart(Utils.GetBinPath("EnableLoopback.exe"));
    }

    /// <summary>Opens the "Добавить" page; [prefill] is a link to put into the field.</summary>
    public void ShowAddSubscription(string? prefill = null)
    {
        GoToAdd();
        if (prefill != null)
        {
            _addPage?.Prefill(prefill);
        }
    }

    /// <summary>The update dialog (notes, update, later, skip); the same one the manual check opens.</summary>
    public void ShowUpdate() => MenuCheckUpdate_Click(this, new RoutedEventArgs());

    private void UpdateDot() =>
        navSettingsDot.Visibility = UpdateNotifier.BannerCandidate() != null ? Visibility.Visible : Visibility.Collapsed;

    private void RefreshUpdateIndicators()
    {
        UpdateDot();
        if (ViewModel != null)
        {
            BuildSettingsPage(ViewModel);
        }
    }

    /// <summary>One tray notification per version (and one reminder); clicking it opens the update dialog. Never installs anything.</summary>
    private void ShowUpdateBalloon(UpdateCandidate candidate)
    {
        var first = ReleaseNotes.Plain(candidate.Notes, 140).Split('\n').Select(l => l.Trim('•', ' ')).FirstOrDefault(l => l.Length > 0) ?? "Нажмите, чтобы посмотреть, что нового";
        (contentStatusBarView.Content as StatusBarView)?.ShowBalloon($"Вышла версия build-{candidate.Build}", first, () =>
        {
            ShowHideWindow(true);
            ShowUpdate();
        });
    }

    /// <summary>The "Почему не работает?" dialog; also reachable from the settings page.</summary>
    public void ShowDiagnosis()
    {
        if (ViewModel == null)
        {
            return;
        }
        _ = DialogHost.Show(new DiagnosisView(ViewModel, homeView.HomeViewModel?.IsConnected == true), "RootDialog");
    }

    public async Task AddServerViaClipboardAsync()
    {
        var clipboardData = WindowsUtils.GetClipboardData();
        if (clipboardData.IsNotEmpty() && ViewModel != null)
        {
            await ViewModel.AddServerViaClipboardAsync(clipboardData);
        }
    }

    private async Task ScanScreenTaskAsync()
    {
        ShowHideWindow(false);

        if (Application.Current?.MainWindow is Window window)
        {
            var bytes = QRCodeWindowsUtils.CaptureScreen(window);
            await ViewModel?.ScanScreenResult(bytes);
        }

        ShowHideWindow(true);
    }

    private void MenuCheckUpdate_Click(object sender, RoutedEventArgs e)
    {
        DialogHost.Show(new HuppUpdateView(), "RootDialog");
        AppEvents.HasUpdateNotified.Publish(false);
    }

    private void MenuBackupAndRestore_Click(object sender, RoutedEventArgs e)
    {
        _backupAndRestoreView ??= new BackupAndRestoreView();
        _backupAndRestoreView.ViewModel = ViewModel?.BackupAndRestoreViewModel;
        DialogHost.Show(_backupAndRestoreView, "RootDialog");
    }

    /// <summary>CI build tag (e.g. "build-5") so it is obvious which version is running.</summary>
    private static string BuildName()
    {
        var info = System.Reflection.Assembly.GetEntryAssembly()?
            .GetCustomAttributes(typeof(System.Reflection.AssemblyInformationalVersionAttribute), false)
            .OfType<System.Reflection.AssemblyInformationalVersionAttribute>()
            .FirstOrDefault()?.InformationalVersion;
        return info.IsNullOrEmpty() ? $"V{Utils.GetVersionInfo()}" : info.Split('+')[0];
    }

    public ViewModels.HuppHomeViewModel? HomeViewModel => homeView.HomeViewModel;

    /// <summary>
    /// Settings as grouped rows (icon, title, hint, chevron) like Happ: everyday items on top,
    /// technical ones in "Для опытных".
    /// </summary>
    private void OpenPingUrlMenu(MainWindowViewModel vm)
    {
        var config = AppManager.Instance.Config;
        var current = config.SpeedTestItem.SpeedPingTestUrl;
        var menu = new ContextMenu();
        foreach (var url in Global.SpeedPingTestUrls)
        {
            var item = new MenuItem { Header = url, IsCheckable = true, IsChecked = url == current };
            var chosen = url;
            item.Click += async (_, _) =>
            {
                config.SpeedTestItem.SpeedPingTestUrl = chosen;
                await ConfigHandler.SaveConfig(config);
                BuildSettingsPage(vm);
            };
            menu.Items.Add(item);
        }
        menu.IsOpen = true;
    }

    /// <summary>Settings back to defaults (the config file is removed and FlowVeil restarts); the database with subscriptions and servers is not touched.</summary>
    private async void ResetSettings()
    {
        if (UI.ShowYesNo("Сбросить настройки к исходным? Подписки и серверы останутся, программа перезапустится.") != MessageBoxResult.Yes)
        {
            return;
        }
        try
        {
            await AppManager.Instance.AppExitAsync(false);
            var file = Utils.GetConfigPath(Global.ConfigFileName);
            if (File.Exists(file))
            {
                File.Copy(file, file + ".bak", true);
                File.Delete(file);
            }
            ProcUtils.RebootAsAdmin(false);
            AppManager.Instance.Shutdown(true);
        }
        catch (Exception ex)
        {
            Logging.SaveLog("Reset", ex);
            NoticeManager.Instance.Enqueue("Не получилось сбросить настройки");
        }
    }

    private void BuildSettingsPage(MainWindowViewModel vm)
    {
        settingsList.Children.Clear();

        StackPanel Section(string title)
        {
            settingsList.Children.Add(new TextBlock
            {
                Text = title.ToUpperInvariant(),
                Margin = new Thickness(8, settingsList.Children.Count == 0 ? 0 : 20, 0, 6),
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                Foreground = (Brush)FindResource("MaterialDesign.Brush.ForegroundLight"),
            });
            var rows = new StackPanel();
            var card = new Border { CornerRadius = new CornerRadius(18), Padding = new Thickness(6), Child = rows };
            card.SetResourceReference(Border.BackgroundProperty, "MaterialDesign.Brush.Card.Background");
            settingsList.Children.Add(card);
            return rows;
        }

        void Row(StackPanel section, PackIconKind icon, string title, string hint, Action action)
        {
            var grid = new Grid();
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            var iconBox = new Border { Width = 36, Height = 36, CornerRadius = new CornerRadius(10), Margin = new Thickness(0, 0, 12, 0) };
            iconBox.SetResourceReference(Border.BackgroundProperty, "MaterialDesign.Brush.Background");
            var packIcon = new PackIcon { Kind = icon, Width = 20, Height = 20, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
            packIcon.SetResourceReference(ForegroundProperty, "MaterialDesign.Brush.Primary");
            iconBox.Child = packIcon;
            grid.Children.Add(iconBox);

            var texts = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
            var titleBlock = new TextBlock { Text = title, FontSize = 14, FontWeight = FontWeights.SemiBold };
            titleBlock.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.Foreground");
            texts.Children.Add(titleBlock);
            if (hint.IsNotEmpty())
            {
                var hintBlock = new TextBlock { Text = hint, FontSize = 12, TextTrimming = TextTrimming.CharacterEllipsis };
                hintBlock.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
                texts.Children.Add(hintBlock);
            }
            Grid.SetColumn(texts, 1);
            grid.Children.Add(texts);

            var chevron = new PackIcon { Kind = PackIconKind.ChevronRight, VerticalAlignment = VerticalAlignment.Center };
            chevron.SetResourceReference(ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
            Grid.SetColumn(chevron, 2);
            grid.Children.Add(chevron);

            var row = new Border
            {
                Child = grid,
                Padding = new Thickness(10, 8, 10, 8),
                CornerRadius = new CornerRadius(12),
                Background = Brushes.Transparent,
                Cursor = Cursors.Hand,
            };
            row.MouseEnter += (_, _) => row.SetResourceReference(Border.BackgroundProperty, "MaterialDesign.Brush.Background");
            row.MouseLeave += (_, _) => row.Background = Brushes.Transparent;
            row.MouseLeftButtonUp += (_, _) =>
            {
                try
                {
                    action();
                }
                catch (Exception ex)
                {
                    Logging.SaveLog("SettingsPage", ex);
                }
            };
            section.Children.Add(row);
        }

        void Exec(ICommand command) => command.Execute(null);

        var main = Section("Подписки");
        Row(main, PackIconKind.Refresh, "Обновить все подписки", "Скачать свежий список серверов", () => Exec(vm.SubUpdateCmd));
        Row(main, PackIconKind.Stethoscope, "Почему не работает?", "Проверит сеть, время, подписку и сервер и подскажет, что делать", () => ShowDiagnosis());
        Row(main, PackIconKind.Autorenew, "Автообновление подписок", SubAutoUpdate.Title(SubAutoUpdate.Get()), () => OpenSubUpdateMenu(vm));

        var settings = AppManager.Instance.Config;
        var tunnel = Section("Туннель и сеть");
        void Toggle(PackIconKind icon, string title, string tip, Func<bool> get, Action<bool> set)
        {
            var on = get();
            Row(tunnel, icon, $"{title}: {(on ? "включено" : "выключено")}", tip, () =>
            {
                set(!on);
                _ = ConfigHandler.SaveConfig(settings);
                NoticeManager.Instance.Enqueue("Применится при следующем подключении");
                BuildSettingsPage(vm);
            });
        }
        Toggle(PackIconKind.LanConnect, "Подключения из локальной сети",
            "Другие устройства дома смогут использовать прокси этого компьютера (адрес и порт в «Параметры ядра и портов»). Включайте только в доверенной сети",
            () => settings.Inbound.FirstOrDefault()?.AllowLANConn == true,
            v =>
            {
                if (settings.Inbound.FirstOrDefault() is { } inbound)
                {
                    inbound.AllowLANConn = v;
                }
            });
        var appMode = AppProxySettings.Mode;
        var appCount = AppProxySettings.Apps.Count;
        Row(tunnel, PackIconKind.ApplicationOutline, "Прокси для приложений",
            appMode == AppProxySettings.ModeOff ? "Выключено: все приложения по общим правилам"
                : appMode == AppProxySettings.ModeDirect ? $"Напрямую: {appCount} прилож."
                : $"Через прокси только: {appCount} прилож.",
            () => _ = DialogHost.Show(new AppProxyView(() => NoticeManager.Instance.Enqueue("Применится при следующем подключении")), "RootDialog").ContinueWith(_ => Dispatcher.BeginInvoke(new Action(() => BuildSettingsPage(vm)))));
        var pingUrl = settings.SpeedTestItem.SpeedPingTestUrl.IsNullOrEmpty() ? Global.SpeedPingTestUrls[0] : settings.SpeedTestItem.SpeedPingTestUrl;
        Row(tunnel, PackIconKind.Speedometer, "Адрес для пинга", pingUrl, () => OpenPingUrlMenu(vm));
        Row(tunnel, _tunnelAdvancedOpen ? PackIconKind.ChevronUp : PackIconKind.ChevronDown, "Дополнительно", "Шумы и фрагментация: для случаев, когда соединение блокируют", () =>
        {
            _tunnelAdvancedOpen = !_tunnelAdvancedOpen;
            BuildSettingsPage(vm);
        });
        if (_tunnelAdvancedOpen)
        {
            Toggle(PackIconKind.Waves, "Шумы (мусорный трафик)",
                "Случайные пакеты перед соединением, чтобы запутать анализ трафика. Работает с UDP-серверами (Hysteria2, mKCP), чуть увеличивает трафик",
                () => settings.CoreBasicItem.EnableNoise, v => settings.CoreBasicItem.EnableNoise = v);
            Toggle(PackIconKind.ContentCut, "Фрагментация",
                "Разбивает начало соединения на части, чтобы его труднее было опознать. Для серверов с TLS и Reality; может немного снизить скорость",
                () => settings.CoreBasicItem.EnableFragment, v => settings.CoreBasicItem.EnableFragment = v);
        }

        var app = Section("Приложение");
        var pendingUpdate = UpdateNotifier.BannerCandidate();
        Row(app, PackIconKind.Update, "Проверить обновления",
            pendingUpdate == null ? $"Сейчас: build {HuppUpdater.CurrentBuild()}" : $"Доступна новая версия build-{pendingUpdate.Build}",
            () => MenuCheckUpdate_Click(this, new RoutedEventArgs()));
        var notifyOn = UpdateNotifier.IsEnabled;
        Row(app, PackIconKind.BellOutline, $"Сообщать о новых версиях: {(notifyOn ? "включено" : "выключено")}",
            "Проверяет GitHub примерно раз в 6 часов. Это единственный запрос, который приложение делает само, без идентификаторов устройства и аккаунта. Ничего не ставится без вашего подтверждения",
            () =>
            {
                UpdateNotifier.SetEnabled(!notifyOn);
                BuildSettingsPage(vm);
            });
        if (pendingUpdate != null)
        {
            Row(app, PackIconKind.SkipNextOutline, "Пропустить эту версию", $"build-{pendingUpdate.Build}: не напоминать, пока не выйдет следующая", () => UpdateNotifier.Skip(pendingUpdate.Build));
        }
        Row(app, PackIconKind.BackupRestore, "Резервная копия", "Сохранить или восстановить настройки и подписки", () => MenuBackupAndRestore_Click(this, new RoutedEventArgs()));
        Row(app, PackIconKind.TrayArrowDown, "Свернуть в трей", "FlowVeil продолжит работать у часов", () => MenuClose_Click(this, new RoutedEventArgs()));
        Row(app, PackIconKind.Restore, "Сбросить настройки", "Вернёт настройки к исходным. Подписки и серверы останутся", () => ResetSettings());

        var about = Section("О приложении");
        Row(about, PackIconKind.InformationOutline, "Версия", $"FlowVeil {BuildName()}. Нажмите, чтобы скопировать", () =>
        {
            WindowsUtils.SetClipboardData($"FlowVeil {BuildName()}");
            NoticeManager.Instance.Enqueue("Версия скопирована");
        });
        Row(about, PackIconKind.HelpCircleOutline, "Частые вопросы", "Что такое подписка, как поставить, как проверить файл", () => ProcUtils.ProcessStart("https://xoyzoom-lgtm.github.io/FlowVeil-proxy/#faq"));
        Row(about, PackIconKind.Link, "Ссылки-приглашения", "flowveil://add?url=… добавляет подписку в один клик; ссылку и QR-код собирает страница проекта", () => UI.Show("Ссылка вида flowveil://add?url=<адрес подписки> открывает FlowVeil и сразу добавляет подписку. Провайдеры могут собрать такую ссылку и QR-код на странице проекта, в разделе «Для провайдеров»."));
        Row(about, PackIconKind.Send, "Автор", "Telegram @GxoyzoomG", () => ProcUtils.ProcessStart("https://t.me/GxoyzoomG"));
        Row(about, PackIconKind.ShieldLockOutline, "Политика конфиденциальности", "Какие данные есть у приложения и куда уходят", () => ProcUtils.ProcessStart("https://github.com/xoyzoom-lgtm/FlowVeil-proxy/blob/main/PRIVACY.md"));
        Row(about, PackIconKind.Github, "Исходный код", "github.com/xoyzoom-lgtm/FlowVeil-proxy", () => ProcUtils.ProcessStart("https://github.com/xoyzoom-lgtm/FlowVeil-proxy"));
        Row(about, PackIconKind.ScaleBalance, "Лицензия", "Основано на v2rayN (GPL-3.0)", () => ProcUtils.ProcessStart("https://github.com/2dust/v2rayN"));

        var devMode = IsDevMode();
        var developer = Section("Для разработчиков");
        Row(developer, PackIconKind.CodeBraces,
            devMode ? "Режим разработчика: включён" : "Режим разработчика: выключен",
            devMode ? "Нажмите, чтобы скрыть технические настройки" : "Показать маршрутизацию, DNS, ядро, ручное добавление серверов",
            () =>
            {
                SetDevMode(!devMode);
                BuildSettingsPage(vm);
            });
        if (!devMode)
        {
            return;
        }

        var expert = Section("Для опытных");
        Row(expert, PackIconKind.Directions, "Маршрутизация", "Какие сайты открывать через сервер, а какие напрямую", () => Exec(vm.RoutingSettingCmd));
        Row(expert, PackIconKind.Dns, "DNS", "Серверы для поиска адресов сайтов", () => Exec(vm.DNSSettingCmd));
        Row(expert, PackIconKind.Tune, "Параметры ядра и портов", "Порты, автозапуск, TUN, звук и прочее", () => Exec(vm.OptionSettingCmd));
        Row(expert, PackIconKind.ServerPlus, "Добавить сервер вручную", "VLESS, VMess, Trojan, Shadowsocks, JSON…", () => OpenAddServerMenu(vm));
        Row(expert, PackIconKind.FormatListBulleted, "Все подписки и группы", "Таблица подписок, фильтры, User-Agent", () => Exec(vm.SubSettingCmd));
        Row(expert, PackIconKind.ViewList, "Расширенный режим", "Таблица серверов и журнал ядра", () =>
        {
            navAdvanced.Visibility = Visibility.Visible;
            navAdvanced.IsChecked = true;
        });
        Row(expert, PackIconKind.Keyboard, "Горячие клавиши", "", () => Exec(vm.GlobalHotkeySettingCmd));
        Row(expert, PackIconKind.CodeJson, "Шаблон конфигурации", "", () => Exec(vm.FullConfigTemplateCmd));
        Row(expert, PackIconKind.RestartAlert, "Перезапустить ядро", "F5", () => Exec(vm.ReloadCmd));
        Row(expert, PackIconKind.FolderOpen, "Открыть папку программы", "", () => Exec(vm.OpenTheFileLocationCmd));
    }

    private void OpenSubUpdateMenu(MainWindowViewModel vm)
    {
        var current = SubAutoUpdate.Get();
        var menu = new ContextMenu();
        foreach (var (minutes, title) in SubAutoUpdate.Options)
        {
            var item = new MenuItem { Header = title, IsCheckable = true, IsChecked = minutes == current };
            item.Click += async (_, _) =>
            {
                await SubAutoUpdate.SetAsync(minutes);
                NoticeManager.Instance.Enqueue(minutes > 0 ? $"Подписки будут обновляться: {title.ToLowerInvariant()}" : "Автообновление подписок выключено");
                BuildSettingsPage(vm);
            };
            menu.Items.Add(item);
        }
        menu.IsOpen = true;
    }

    private static string DevModeFile => Utils.GetConfigPath("dev_mode");

    /// <summary>Developer mode: technical settings stay hidden for everyday users until turned on.</summary>
    private static bool IsDevMode() => File.Exists(DevModeFile);

    private static void SetDevMode(bool on)
    {
        try
        {
            if (on)
            {
                File.WriteAllText(DevModeFile, "1");
            }
            else if (File.Exists(DevModeFile))
            {
                File.Delete(DevModeFile);
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(SetDevMode), ex);
        }
    }

    private void OpenAddServerMenu(MainWindowViewModel vm)
    {
        MenuItem Item(string header, ICommand command) => new() { Header = header, Command = command };
        var menu = new ContextMenu();
        menu.Items.Add(Item("QR из картинки", vm.AddServerViaImageCmd));
        menu.Items.Add(new Separator());
        menu.Items.Add(Item("VLESS", vm.AddVlessServerCmd));
        menu.Items.Add(Item("VMess", vm.AddVmessServerCmd));
        menu.Items.Add(Item("Trojan", vm.AddTrojanServerCmd));
        menu.Items.Add(Item("Shadowsocks", vm.AddShadowsocksServerCmd));
        menu.Items.Add(Item("Hysteria2", vm.AddHysteria2ServerCmd));
        menu.Items.Add(Item("TUIC", vm.AddTuicServerCmd));
        menu.Items.Add(Item("WireGuard", vm.AddWireguardServerCmd));
        menu.Items.Add(Item("SOCKS", vm.AddSocksServerCmd));
        menu.Items.Add(Item("HTTP", vm.AddHttpServerCmd));
        menu.Items.Add(Item("Свой конфиг (JSON)", vm.AddCustomServerCmd));
        menu.IsOpen = true;
    }

    #endregion Event

    #region UI

    public void ShowHideWindow(bool? blShow)
    {
        var bl = blShow ?? !AppManager.Instance.ShowInTaskbar;
        if (bl)
        {
            this?.Show();
            if (this?.WindowState == WindowState.Minimized)
            {
                WindowState = WindowState.Normal;
            }
            this?.Activate();
            this?.Focus();
        }
        else
        {
            this?.Hide();
        }
        AppManager.Instance.ShowInTaskbar = bl;
    }

    protected override void OnLoaded(object? sender, RoutedEventArgs e)
    {
        base.OnLoaded(sender, e);
        if (_config.UiItem.AutoHideStartup)
        {
            ShowHideWindow(false);
        }
        RestoreUI();
        _ = ImportPendingLinkAsync();
        _ = SubAutoUpdate.EnsureDefaultAsync();
    }

    /// <summary>Start page: «Серверы», or «Добавить» when there is nothing to show yet.</summary>
    private async Task OpenStartPageAsync()
    {
        try
        {
            var subs = await AppManager.Instance.SubItems();
            if (subs == null || subs.Count == 0)
            {
                await Dispatcher.InvokeAsync(() => navAdd.IsChecked = true);
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(MainWindow), ex);
        }
    }

    /// <summary>Adds the subscription from a flowveil:// link that opened the app.</summary>
    private async Task ImportPendingLinkAsync()
    {
        var link = DeepLink.TakePending();
        if (link == null || ViewModel == null)
        {
            return;
        }
        ShowHideWindow(true);
        await ViewModel.AddServerViaClipboardAsync(link);
        GoToServers();
    }

    private void RestoreUI()
    {
        if (_config.UiItem.MainGirdHeight1 > 0 && _config.UiItem.MainGirdHeight2 > 0)
        {
            if (_config.UiItem.MainGirdOrientation == EGirdOrientation.Horizontal)
            {
                gridMain.ColumnDefinitions[0].Width = new GridLength(_config.UiItem.MainGirdHeight1, GridUnitType.Star);
                gridMain.ColumnDefinitions[2].Width = new GridLength(_config.UiItem.MainGirdHeight2, GridUnitType.Star);
            }
            else if (_config.UiItem.MainGirdOrientation == EGirdOrientation.Vertical)
            {
                gridMain1.RowDefinitions[0].Height = new GridLength(_config.UiItem.MainGirdHeight1, GridUnitType.Star);
                gridMain1.RowDefinitions[2].Height = new GridLength(_config.UiItem.MainGirdHeight2, GridUnitType.Star);
            }
        }
    }

    private void StorageUI()
    {
        ConfigHandler.SaveWindowSizeItem(_config, GetType().Name, Width, Height);

        if (_config.UiItem.MainGirdOrientation == EGirdOrientation.Horizontal)
        {
            ConfigHandler.SaveMainGirdHeight(_config, gridMain.ColumnDefinitions[0].ActualWidth, gridMain.ColumnDefinitions[2].ActualWidth);
        }
        else if (_config.UiItem.MainGirdOrientation == EGirdOrientation.Vertical)
        {
            ConfigHandler.SaveMainGirdHeight(_config, gridMain1.RowDefinitions[0].ActualHeight, gridMain1.RowDefinitions[2].ActualHeight);
        }
    }

    private void UpdateLayout(EGirdOrientation orientation)
    {
        var currentLayoutDisposables = new MultipleDisposable();
        _layoutBindingsDisposable.Create(currentLayoutDisposables);

        gridMain.Visibility = orientation == EGirdOrientation.Horizontal ? Visibility.Visible : Visibility.Collapsed;
        gridMain1.Visibility = orientation == EGirdOrientation.Vertical ? Visibility.Visible : Visibility.Collapsed;
        gridMain2.Visibility = orientation == EGirdOrientation.Tab ? Visibility.Visible : Visibility.Collapsed;

        switch (orientation)
        {
            case EGirdOrientation.Horizontal:
                this.WhenAnyValue(v => v.ViewModel.ProfilesViewModel)
                    .Subscribe(vm => ViewHost.Show(tabProfiles, vm))
                    .DisposeWith(currentLayoutDisposables);
                this.WhenAnyValue(v => v.ViewModel.MsgViewModel)
                    .Subscribe(vm => ViewHost.Show(tabMsgView, vm))
                    .DisposeWith(currentLayoutDisposables);
                this.WhenAnyValue(v => v.ViewModel.ClashProxiesViewModel)
                    .Subscribe(vm => ViewHost.Show(tabClashProxies, vm))
                    .DisposeWith(currentLayoutDisposables);
                this.WhenAnyValue(v => v.ViewModel.ClashConnectionsViewModel)
                    .Subscribe(vm => ViewHost.Show(tabClashConnections, vm))
                    .DisposeWith(currentLayoutDisposables);
                this.OneWayBind(ViewModel, vm => vm.ShowClashUI, v => v.tabMsgView.Visibility).DisposeWith(currentLayoutDisposables);
                this.OneWayBind(ViewModel, vm => vm.ShowClashUI, v => v.tabClashProxies.Visibility).DisposeWith(currentLayoutDisposables);
                this.OneWayBind(ViewModel, vm => vm.ShowClashUI, v => v.tabClashConnections.Visibility).DisposeWith(currentLayoutDisposables);
                this.Bind(ViewModel, vm => vm.TabMainSelectedIndex, v => v.tabMain.SelectedIndex).DisposeWith(currentLayoutDisposables);
                break;

            case EGirdOrientation.Vertical:
                this.WhenAnyValue(v => v.ViewModel.ProfilesViewModel)
                    .Subscribe(vm => ViewHost.Show(tabProfiles1, vm))
                    .DisposeWith(currentLayoutDisposables);
                this.WhenAnyValue(v => v.ViewModel.MsgViewModel)
                    .Subscribe(vm => ViewHost.Show(tabMsgView1, vm))
                    .DisposeWith(currentLayoutDisposables);
                this.WhenAnyValue(v => v.ViewModel.ClashProxiesViewModel)
                    .Subscribe(vm => ViewHost.Show(tabClashProxies1, vm))
                    .DisposeWith(currentLayoutDisposables);
                this.WhenAnyValue(v => v.ViewModel.ClashConnectionsViewModel)
                    .Subscribe(vm => ViewHost.Show(tabClashConnections1, vm))
                    .DisposeWith(currentLayoutDisposables);
                this.OneWayBind(ViewModel, vm => vm.ShowClashUI, v => v.tabMsgView1.Visibility).DisposeWith(currentLayoutDisposables);
                this.OneWayBind(ViewModel, vm => vm.ShowClashUI, v => v.tabClashProxies1.Visibility).DisposeWith(currentLayoutDisposables);
                this.OneWayBind(ViewModel, vm => vm.ShowClashUI, v => v.tabClashConnections1.Visibility).DisposeWith(currentLayoutDisposables);
                this.Bind(ViewModel, vm => vm.TabMainSelectedIndex, v => v.tabMain1.SelectedIndex).DisposeWith(currentLayoutDisposables);
                break;

            case EGirdOrientation.Tab:
            default:
                this.WhenAnyValue(v => v.ViewModel.ProfilesViewModel)
                    .Subscribe(vm => ViewHost.Show(tabProfiles2, vm))
                    .DisposeWith(currentLayoutDisposables);
                this.WhenAnyValue(v => v.ViewModel.MsgViewModel)
                    .Subscribe(vm => ViewHost.Show(tabMsgView2, vm))
                    .DisposeWith(currentLayoutDisposables);
                this.WhenAnyValue(v => v.ViewModel.ClashProxiesViewModel)
                    .Subscribe(vm => ViewHost.Show(tabClashProxies2, vm))
                    .DisposeWith(currentLayoutDisposables);
                this.WhenAnyValue(v => v.ViewModel.ClashConnectionsViewModel)
                    .Subscribe(vm => ViewHost.Show(tabClashConnections2, vm))
                    .DisposeWith(currentLayoutDisposables);
                this.OneWayBind(ViewModel, vm => vm.ShowClashUI, v => v.tabClashProxies2.Visibility).DisposeWith(currentLayoutDisposables);
                this.OneWayBind(ViewModel, vm => vm.ShowClashUI, v => v.tabClashConnections2.Visibility).DisposeWith(currentLayoutDisposables);
                this.Bind(ViewModel, vm => vm.TabMainSelectedIndex, v => v.tabMain2.SelectedIndex).DisposeWith(currentLayoutDisposables);
                break;
        }

        RestoreUI();
    }

    private void AddHelpMenuItem()
    {
        var coreInfo = CoreInfoManager.Instance.GetCoreInfo();
        foreach (var it in coreInfo
            .Where(t => t.CoreType is not ECoreType.v2fly
                        and not ECoreType.hysteria))
        {
            var item = new MenuItem()
            {
                Tag = it.Url.Replace(@"/releases", ""),
                Header = string.Format(ResUI.menuWebsiteItem, it.CoreType.ToString().Replace("_", " ")).UpperFirstChar()
            };
            item.Click += MenuItem_Click;
            menuHelp.Items.Add(item);
        }
    }

    private void MenuItem_Click(object sender, RoutedEventArgs e)
    {
        if (sender is MenuItem item)
        {
            ProcUtils.ProcessStart(item.Tag.ToString());
        }
    }

    #endregion UI
}

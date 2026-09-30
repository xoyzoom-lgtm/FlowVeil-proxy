using MaterialDesignThemes.Wpf;
using System.Windows.Controls;
using System.Windows.Media;
using v2rayN.Manager;
using v2rayN.ViewModels;

namespace v2rayN.Views;

public partial class StatusBarView
{
    private static Config _config;

    public StatusBarView()
    {
        InitializeComponent();
        _config = AppManager.Instance.Config;

        trayMenu.Opened += (_, _) => BuildTrayMenu();
        BuildTrayMenu();
        txtRunningServerDisplay.PreviewMouseDown += txtRunningInfoDisplay_MouseDoubleClick;
        txtRunningInfoDisplay.PreviewMouseDown += txtRunningInfoDisplay_MouseDoubleClick;

        this.WhenActivated(disposables =>
        {
            this.OneWayBind(ViewModel, vm => vm.RunningServerToolTipText, v => v.tbNotify.ToolTipText).DisposeWith(disposables);
            this.OneWayBind(ViewModel, vm => vm.NotifyLeftClickCmd, v => v.tbNotify.LeftClickCommand).DisposeWith(disposables);

            //status bar
            this.OneWayBind(ViewModel, vm => vm.InboundDisplay, v => v.txtInboundDisplay.Text).DisposeWith(disposables);
            this.OneWayBind(ViewModel, vm => vm.InboundLanDisplay, v => v.txtInboundLanDisplay.Text).DisposeWith(disposables);
            this.OneWayBind(ViewModel, vm => vm.RunningServerDisplay, v => v.txtRunningServerDisplay.Text).DisposeWith(disposables);
            this.OneWayBind(ViewModel, vm => vm.RunningInfoDisplay, v => v.txtRunningInfoDisplay.Text).DisposeWith(disposables);
            this.OneWayBind(ViewModel, vm => vm.SpeedProxyDisplay, v => v.txtSpeedProxyDisplay.Text).DisposeWith(disposables);
            this.OneWayBind(ViewModel, vm => vm.SpeedDirectDisplay, v => v.txtSpeedDirectDisplay.Text).DisposeWith(disposables);
            this.Bind(ViewModel, vm => vm.EnableTun, v => v.togEnableTun.IsChecked).DisposeWith(disposables);

            this.Bind(ViewModel, vm => vm.SystemProxySelected, v => v.cmbSystemProxy.SelectedIndex).DisposeWith(disposables);
            this.OneWayBind(ViewModel, vm => vm.RoutingItems, v => v.cmbRoutings2.ItemsSource).DisposeWith(disposables);
            this.Bind(ViewModel, vm => vm.SelectedRouting, v => v.cmbRoutings2.SelectedItem).DisposeWith(disposables);
            this.OneWayBind(ViewModel, vm => vm.BlRouting, v => v.cmbRoutings2.Visibility).DisposeWith(disposables);

            ViewModel.SetClipboardDataInteraction.RegisterHandler(interaction =>
            {
                var strData = interaction.Input;
                WindowsUtils.SetClipboardData(strData);
                interaction.SetOutput(RxVoid.Default);
            }).DisposeWith(disposables);

            ViewModel.DispatcherRefreshIconInteraction.RegisterHandler(interaction =>
            {
                Application.Current?.Dispatcher.Invoke(async () => await RefreshIcon(), DispatcherPriority.Normal);
                interaction.SetOutput(RxVoid.Default);
            }).DisposeWith(disposables);
        });

        _ = RefreshIcon();
    }

    /// <summary>A tray balloon (Windows shows it as a toast); no packaging or app id needed. [onClick] runs when the user clicks it.</summary>
    public void ShowBalloon(string title, string text, Action? onClick)
    {
        void Handler(object? sender, RoutedEventArgs e)
        {
            tbNotify.TrayBalloonTipClicked -= Handler;
            onClick?.Invoke();
        }
        tbNotify.TrayBalloonTipClicked += Handler;
        tbNotify.ShowNotification(title, text, H.NotifyIcon.Core.NotificationIcon.Info);
    }

    private async Task RefreshIcon()
    {
        tbNotify.Icon = await WindowsManager.Instance.GetNotifyIcon(_config);
        Application.Current.MainWindow?.Icon = WindowsManager.Instance.GetAppIcon(_config);
    }

    /// <summary>Happ-style tray menu: status, connect toggle, server / mode / routing pickers.</summary>
    private void BuildTrayMenu()
    {
        trayMenu.Items.Clear();
        var vm = ViewModel;
        var home = (Application.Current?.MainWindow as MainWindow)?.HomeViewModel;

        MenuItem Item(string header, Action? onClick = null, bool isChecked = false, bool enabled = true, PackIconKind? icon = null)
        {
            var item = new MenuItem
            {
                Header = header,
                IsCheckable = false,
                IsChecked = isChecked,
                IsEnabled = enabled,
                Height = 38,
                Padding = new Thickness(12, 0, 12, 0),
                FontSize = 13.5,
            };
            if (icon is { } kind)
            {
                var packIcon = new PackIcon { Kind = kind, Width = 18, Height = 18 };
                packIcon.SetResourceReference(ForegroundProperty, "MaterialDesign.Brush.Primary");
                item.Icon = packIcon;
            }
            if (onClick != null)
            {
                item.Click += (_, _) => onClick();
            }
            return item;
        }

        Separator Divider() => new() { Margin = new Thickness(12, 4, 12, 4), Opacity = 0.5 };

        var connected = home?.IsConnected == true;
        var server = home?.ServerName ?? string.Empty;

        // Status header: coloured dot, state and server; not clickable but not greyed out.
        var dot = new System.Windows.Shapes.Ellipse
        {
            Width = 10,
            Height = 10,
            Margin = new Thickness(2, 0, 12, 0),
            VerticalAlignment = VerticalAlignment.Center,
            Fill = new SolidColorBrush(connected ? Color.FromRgb(0x3B, 0xE0, 0xB0) : Color.FromRgb(0x8A, 0x94, 0xA6)),
        };
        var state = new TextBlock { Text = connected ? "Подключено" : "Отключено", FontWeight = FontWeights.SemiBold, FontSize = 14 };
        state.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.Foreground");
        var texts = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        texts.Children.Add(state);
        if (server.IsNotEmpty())
        {
            var serverText = new TextBlock { Text = server, FontSize = 12, TextTrimming = TextTrimming.CharacterEllipsis, MaxWidth = 220 };
            serverText.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
            texts.Children.Add(serverText);
        }
        var headerPanel = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(10, 8, 10, 8) };
        headerPanel.Children.Add(dot);
        headerPanel.Children.Add(texts);
        trayMenu.Items.Add(new MenuItem { Header = headerPanel, IsHitTestVisible = false, Focusable = false, Height = double.NaN, Padding = new Thickness(0) });

        trayMenu.Items.Add(Item(connected ? "Отключиться" : "Подключиться", () => _ = home?.ToggleAsync(), icon: PackIconKind.Power));
        trayMenu.Items.Add(Divider());

        if (vm != null)
        {
            var servers = Item("Сменить сервер", icon: PackIconKind.Earth);
            if (vm.BlServers && vm.Servers.Count > 0)
            {
                foreach (var it in vm.Servers)
                {
                    var target = it;
                    var name = TrayServerName(it.Text);
                    servers.Items.Add(Item(name, () => vm.SelectedServer = target, it.ID == vm.SelectedServer?.ID));
                }
            }
            else
            {
                servers.Items.Add(Item("Открыть список серверов", () => ((ICommand)vm.ShowWindowCmd).Execute(null)));
            }
            trayMenu.Items.Add(servers);

            var mode = Item("Режим подключения", icon: PackIconKind.SwapHorizontal);
            if (home != null)
            {
                foreach (var (id, group, title) in HuppHomeViewModel.Modes)
                {
                    var target = id;
                    var header = group == "TUN" ? $"TUN · {title}" : title;
                    mode.Items.Add(Item(header, () => home.Mode = target, home.Mode == target));
                }
            }
            else
            {
                mode.Items.Add(Item("TUN", () => vm.EnableTun = !vm.EnableTun, vm.EnableTun));
            }
            trayMenu.Items.Add(mode);

            if (vm.RoutingItems.Count > 0)
            {
                var routing = Item("Маршрутизация", icon: PackIconKind.Directions);
                foreach (var it in vm.RoutingItems)
                {
                    var target = it;
                    routing.Items.Add(Item(it.Remarks, () => vm.SelectedRouting = target, it.Id == vm.SelectedRouting?.Id));
                }
                trayMenu.Items.Add(routing);
            }

            trayMenu.Items.Add(Divider());
            trayMenu.Items.Add(Item("Вставить подписку из буфера", () => ((ICommand)vm.AddServerViaClipboardCmd).Execute(null), icon: PackIconKind.ContentPaste));
            trayMenu.Items.Add(Item("Обновить подписки", () => ((ICommand)vm.SubUpdateCmd).Execute(null), icon: PackIconKind.Refresh));
            trayMenu.Items.Add(Divider());
            trayMenu.Items.Add(Item("Открыть FlowVeil", () => ((ICommand)vm.ShowWindowCmd).Execute(null), icon: PackIconKind.WindowMaximize));
        }
        trayMenu.Items.Add(Item("Выйти", () => menuExit_Click(this, new RoutedEventArgs()), icon: PackIconKind.ExitToApp));
    }

    /// <summary>"[VLESS] 🇩🇪 Germany(1.2.3.4:443)" → "Germany".</summary>
    private static string TrayServerName(string? summary)
    {
        var text = summary ?? string.Empty;
        if (text.StartsWith('[') && text.IndexOf("] ", StringComparison.Ordinal) is var close and > 0)
        {
            text = text[(close + 2)..];
        }
        if (text.EndsWith(')') && text.LastIndexOf('(') is var open and > 0)
        {
            text = text[..open];
        }
        return HuppProfileText.SplitFlag(text).Name;
    }

    private async void menuExit_Click(object sender, RoutedEventArgs e)
    {
        tbNotify.Dispose();
        await AppManager.Instance.AppExitAsync(true);
    }

    private void txtRunningInfoDisplay_MouseDoubleClick(object sender, MouseButtonEventArgs e)
    {
        ViewModel?.TestServerAvailability();
    }
}

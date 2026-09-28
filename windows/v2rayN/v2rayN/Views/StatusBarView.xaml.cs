using System.Windows.Controls;
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

        MenuItem Item(string header, Action? onClick = null, bool isChecked = false, bool enabled = true)
        {
            var item = new MenuItem
            {
                Header = header,
                IsCheckable = false,
                IsChecked = isChecked,
                IsEnabled = enabled,
                Height = double.NaN,
            };
            if (onClick != null)
            {
                item.Click += (_, _) => onClick();
            }
            return item;
        }

        var connected = home?.IsConnected == true;
        var server = home?.ServerName ?? string.Empty;
        trayMenu.Items.Add(Item(connected ? $"Подключено · {server}" : "Отключено", enabled: false));
        trayMenu.Items.Add(Item(connected ? "Отключиться" : "Подключиться", () => _ = home?.ToggleAsync()));
        trayMenu.Items.Add(new Separator());

        if (vm != null)
        {
            var servers = Item("Сменить сервер");
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

            var mode = Item("Режим транспорта");
            var tun = vm.EnableTun;
            mode.Items.Add(Item("Системный прокси", () =>
            {
                if (home != null)
                {
                    home.ModeIndex = HuppHomeViewModel.ModeProxy;
                }
                else
                {
                    vm.EnableTun = false;
                }
            }, !tun && home?.ModeIndex != HuppHomeViewModel.ModeTun));
            mode.Items.Add(Item("TUN (весь трафик)", () =>
            {
                if (home != null)
                {
                    home.ModeIndex = HuppHomeViewModel.ModeTun;
                }
                else
                {
                    vm.EnableTun = true;
                }
            }, tun || home?.ModeIndex == HuppHomeViewModel.ModeTun));
            trayMenu.Items.Add(mode);

            if (vm.RoutingItems.Count > 0)
            {
                var routing = Item("Маршрутизация");
                foreach (var it in vm.RoutingItems)
                {
                    var target = it;
                    routing.Items.Add(Item(it.Remarks, () => vm.SelectedRouting = target, it.Id == vm.SelectedRouting?.Id));
                }
                trayMenu.Items.Add(routing);
            }

            trayMenu.Items.Add(new Separator());
            trayMenu.Items.Add(Item("Импорт из буфера обмена", () => ((ICommand)vm.AddServerViaClipboardCmd).Execute(null)));
            trayMenu.Items.Add(Item("Обновить подписки", () => ((ICommand)vm.SubUpdateCmd).Execute(null)));
            trayMenu.Items.Add(Item("Скопировать команду прокси", () => ((ICommand)vm.CopyProxyCmdToClipboardCmd).Execute(null)));
            trayMenu.Items.Add(new Separator());
            trayMenu.Items.Add(Item("Показать окно", () => ((ICommand)vm.ShowWindowCmd).Execute(null)));
        }
        trayMenu.Items.Add(Item("Выйти", () => menuExit_Click(this, new RoutedEventArgs())));
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

using System.Collections.Specialized;
using System.Windows.Controls;
using System.Windows.Media;
using ServiceLib.Handler.Fmt;
using v2rayN.ViewModels;

namespace v2rayN.Views;

/// <summary>Happ-style home screen: subscription cards, server list and a connect button.</summary>
public partial class HuppHomeView : UserControl
{
    private HuppHomeViewModel? _vm;
    private MainWindowViewModel? _main;

    public HuppHomeView()
    {
        InitializeComponent();

        btnPower.Click += async (_, _) => await Run(() => _vm?.ToggleAsync());
        btnTestPing.Click += async (_, _) => await Run(() => _vm?.TestCurrentAsync());
        btnPingAll.Click += async (_, _) => await Run(() => _vm?.PingAllAsync());
        btnUpdateSubs.Click += async (_, _) => await UpdateSubsAsync("");
        btnAdd.Click += async (_, _) => await PasteAsync();
        btnEmptyPaste.Click += async (_, _) => await PasteAsync();
        btnMode.Click += (_, _) => OpenModeMenu();
    }

    public void Attach(MainWindowViewModel main)
    {
        _main = main;
        _vm = new HuppHomeViewModel(main.ProfilesViewModel, main.StatusBarViewModel);
        DataContext = _vm;
        _vm.Profiles.ProfileItems.CollectionChanged += OnProfilesChanged;
        UpdateEmptyState();
    }

    public HuppHomeViewModel? HomeViewModel => _vm;

    private void OnProfilesChanged(object? sender, NotifyCollectionChangedEventArgs e) => UpdateEmptyState();

    private void UpdateEmptyState()
    {
        var empty = _vm == null || (_vm.Profiles.ProfileItems.Count == 0 && _vm.Profiles.ServerFilter.IsNullOrEmpty());
        panelEmpty.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
        lstServers.Visibility = empty ? Visibility.Collapsed : Visibility.Visible;
    }

    private static async Task Run(Func<Task?> action)
    {
        try
        {
            var task = action();
            if (task != null)
            {
                await task;
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(HuppHomeView), ex);
        }
    }

    private async Task UpdateSubsAsync(string subId)
    {
        if (_main == null || _vm == null)
        {
            return;
        }
        NoticeManager.Instance.Enqueue("Обновляю подписки…");
        await Run(() => _main.UpdateSubscriptionProcess(subId, false));
        var servers = await AppManager.Instance.ProfileItems(subId);
        NoticeManager.Instance.Enqueue(servers is { Count: > 0 }
            ? $"Готово! Серверов: {servers.Count}"
            : "Не удалось загрузить серверы. Проверьте ссылку и интернет.");
    }

    private async Task PasteAsync()
    {
        var data = WindowsUtils.GetClipboardData();
        if (data.IsNullOrEmpty() || _main == null)
        {
            NoticeManager.Instance.Enqueue("Сначала скопируйте ссылку на подписку (Ctrl+C), потом нажмите «Добавить»");
            return;
        }
        await Run(() => _main.AddServerViaClipboardAsync(data));
    }

    /// <summary>Grouped mode list like Happ: Прокси / TUN (sing-box, gVisor, Xray) / Другое.</summary>
    private void OpenModeMenu()
    {
        if (_vm == null)
        {
            return;
        }
        var menu = new ContextMenu
        {
            PlacementTarget = btnMode,
            Placement = System.Windows.Controls.Primitives.PlacementMode.Bottom,
            MinWidth = btnMode.ActualWidth,
        };
        string? group = null;
        foreach (var (id, itemGroup, title) in HuppHomeViewModel.Modes)
        {
            if (itemGroup != group)
            {
                if (group != null)
                {
                    menu.Items.Add(new Separator());
                }
                group = itemGroup;
                menu.Items.Add(new MenuItem { Header = itemGroup, IsEnabled = false, FontWeight = FontWeights.Bold });
            }
            var item = new MenuItem { Header = title, IsChecked = _vm.Mode == id };
            var mode = id;
            item.Click += (_, _) => _vm.Mode = mode;
            menu.Items.Add(item);
        }
        menu.IsOpen = true;
    }

    #region Subscription cards

    private static HuppSubCard? CardOf(object sender) => (sender as FrameworkElement)?.DataContext as HuppSubCard;

    private void Card_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is DependencyObject source && FindAncestor<Button>(source) != null)
        {
            return;
        }
        _vm?.SelectCard(CardOf(sender));
    }

    private async void CardUpdate_Click(object sender, RoutedEventArgs e)
    {
        var card = CardOf(sender);
        if (card != null)
        {
            await UpdateSubsAsync(card.Sub.Id);
        }
    }

    private async void CardPing_Click(object sender, RoutedEventArgs e)
    {
        var card = CardOf(sender);
        if (card == null || _vm == null)
        {
            return;
        }
        if (!card.IsSelected)
        {
            _vm.SelectCard(card);
            // Let the server list switch to this subscription before pinging it.
            await Task.Delay(300);
        }
        await Run(() => _vm.PingAllAsync());
    }

    private void CardCopy_Click(object sender, RoutedEventArgs e)
    {
        var card = CardOf(sender);
        if (card?.Sub.Url.IsNotEmpty() == true)
        {
            WindowsUtils.SetClipboardData(card.Sub.Url);
            NoticeManager.Instance.Enqueue("Ссылка скопирована");
        }
    }

    private void CardSettings_Click(object sender, RoutedEventArgs e)
    {
        ((ICommand?)_main?.SubSettingCmd)?.Execute(null);
    }

    private void CardSupport_Click(object sender, RoutedEventArgs e)
    {
        var url = CardOf(sender)?.SupportUrl;
        if (url.IsNotEmpty())
        {
            ProcUtils.ProcessStart(url);
        }
    }

    private void CardMenu_Click(object sender, RoutedEventArgs e)
    {
        if (sender is DependencyObject d && FindAncestor<Border>(d, b => b.ContextMenu != null) is { } border)
        {
            border.ContextMenu.PlacementTarget = sender as UIElement;
            border.ContextMenu.DataContext = border.DataContext;
            border.ContextMenu.IsOpen = true;
        }
    }

    #endregion Subscription cards

    #region Servers

    private static ProfileItemModel? ServerOf(object sender) => (sender as FrameworkElement)?.DataContext as ProfileItemModel;

    private async void Server_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        await Run(() => _vm?.SelectServerAsync(ServerOf(sender)));
    }

    private async void ServerConnect_Click(object sender, RoutedEventArgs e)
    {
        await Run(() => _vm?.SelectServerAsync(ServerOf(sender)));
    }

    private async void ServerEdit_Click(object sender, RoutedEventArgs e)
    {
        var model = ServerOf(sender);
        if (model == null || _vm == null)
        {
            return;
        }
        _vm.Profiles.SelectedProfile = model;
        await Run(() => _vm.Profiles.EditServerAsync());
    }

    private async void ServerShare_Click(object sender, RoutedEventArgs e)
    {
        var model = ServerOf(sender);
        if (model == null)
        {
            return;
        }
        var item = await AppManager.Instance.GetProfileItem(model.IndexId);
        var url = item == null ? null : FmtHandler.GetShareUri(item);
        if (url.IsNullOrEmpty())
        {
            NoticeManager.Instance.Enqueue(ResUI.OperationFailed);
            return;
        }
        WindowsUtils.SetClipboardData(url);
        NoticeManager.Instance.Enqueue("Ссылка скопирована");
    }

    private async void ServerDelete_Click(object sender, RoutedEventArgs e)
    {
        var model = ServerOf(sender);
        if (model == null || _vm == null || _main == null)
        {
            return;
        }
        if (UI.ShowYesNo(ResUI.RemoveServer) != MessageBoxResult.Yes)
        {
            return;
        }
        var item = await AppManager.Instance.GetProfileItem(model.IndexId);
        if (item == null)
        {
            return;
        }
        var config = AppManager.Instance.Config;
        var wasActive = item.IndexId == config.IndexId;
        await ConfigHandler.RemoveServers(config, [item]);
        await _vm.Profiles.RefreshServers();
        if (wasActive)
        {
            await Run(() => _main.Reload());
        }
    }

    #endregion Servers

    private static T? FindAncestor<T>(DependencyObject? d, Func<T, bool>? match = null) where T : DependencyObject
    {
        while (d != null)
        {
            if (d is T t && (match == null || match(t)))
            {
                return t;
            }
            d = d is Visual or System.Windows.Media.Media3D.Visual3D ? VisualTreeHelper.GetParent(d) : LogicalTreeHelper.GetParent(d);
        }
        return null;
    }
}

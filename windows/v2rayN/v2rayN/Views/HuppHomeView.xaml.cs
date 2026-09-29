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
        btnConnectBest.Click += async (_, _) => await Run(() => _vm?.ConnectBestAsync());
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
        // Old servers stay after a failed update, so the error must win over the server count.
        var error = SubscriptionHandler.LastError;
        if (error.IsNotEmpty())
        {
            NoticeManager.Instance.Enqueue($"Не удалось обновить: {error}");
            return;
        }
        NoticeManager.Instance.Enqueue(servers is { Count: > 0 }
            ? $"Готово! Серверов: {servers.Count}"
            : "Не удалось загрузить серверы: неизвестная ошибка");
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

    /// <summary>Everything you can do with a subscription, same list as on the phone.</summary>
    private void CardMenu_Click(object sender, RoutedEventArgs e)
    {
        var card = CardOf(sender);
        if (card == null || _vm == null || _main == null)
        {
            return;
        }
        e.Handled = true;

        MenuItem Item(string header, MaterialDesignThemes.Wpf.PackIconKind icon, Func<Task> action)
        {
            var item = new MenuItem
            {
                Header = header,
                Icon = new MaterialDesignThemes.Wpf.PackIcon { Kind = icon },
            };
            item.Click += async (_, _) => await Run(action);
            return item;
        }

        var menu = new ContextMenu { PlacementTarget = sender as UIElement };
        menu.Items.Add(Item("Обновить подписку", MaterialDesignThemes.Wpf.PackIconKind.Refresh, () => UpdateSubsAsync(card.Sub.Id)));
        menu.Items.Add(Item("Проверить серверы", MaterialDesignThemes.Wpf.PackIconKind.Speedometer, async () =>
        {
            await SelectCardAsync(card);
            await _vm.PingAllAsync();
        }));
        menu.Items.Add(Item("Отсортировать по пингу", MaterialDesignThemes.Wpf.PackIconKind.SortAscending, async () =>
        {
            await SelectCardAsync(card);
            await _vm.Profiles.SortServer(nameof(EServerColName.DelayVal));
        }));
        menu.Items.Add(new Separator());
        menu.Items.Add(Item("Изменить подписку", MaterialDesignThemes.Wpf.PackIconKind.Pencil, async () =>
        {
            var item = await AppManager.Instance.GetSubItem(card.Sub.Id);
            if (item != null && await AppManager.Instance.WindowDialog.ShowDialogAsync(new SubEditViewModel(item)) == true)
            {
                await _vm.Profiles.RefreshSubscriptions();
            }
        }));
        menu.Items.Add(Item("Скопировать ссылку", MaterialDesignThemes.Wpf.PackIconKind.ContentCopy, () =>
        {
            WindowsUtils.SetClipboardData(card.Sub.Url);
            NoticeManager.Instance.Enqueue("Ссылка скопирована");
            return Task.CompletedTask;
        }));
        if (card.SupportUrl.IsNotEmpty())
        {
            menu.Items.Add(Item("Поддержка провайдера", MaterialDesignThemes.Wpf.PackIconKind.Send, () =>
            {
                ProcUtils.ProcessStart(card.SupportUrl);
                return Task.CompletedTask;
            }));
        }
        menu.Items.Add(new Separator());
        menu.Items.Add(Item("Удалить дубликаты", MaterialDesignThemes.Wpf.PackIconKind.LayersRemove, async () =>
        {
            var (before, kept) = await ConfigHandler.DedupServerList(AppManager.Instance.Config, card.Sub.Id);
            var removed = before - kept;
            await _vm.Profiles.RefreshServers();
            NoticeManager.Instance.Enqueue($"Удалено дубликатов: {removed}");
        }));
        menu.Items.Add(Item("Удалить нерабочие серверы", MaterialDesignThemes.Wpf.PackIconKind.CloseCircleOutline, async () =>
        {
            var removed = await ConfigHandler.RemoveInvalidServerResult(AppManager.Instance.Config, card.Sub.Id);
            await _vm.Profiles.RefreshServers();
            NoticeManager.Instance.Enqueue($"Удалено нерабочих: {removed}");
        }));
        menu.Items.Add(Item("Удалить подписку", MaterialDesignThemes.Wpf.PackIconKind.DeleteOutline, async () =>
        {
            if (UI.ShowYesNo($"Удалить подписку «{card.Title}» и все её серверы?") != MessageBoxResult.Yes)
            {
                return;
            }
            await ConfigHandler.DeleteSubItem(AppManager.Instance.Config, card.Sub.Id);
            SubscriptionInfoStore.Remove(card.Sub.Id);
            await _vm.Profiles.RefreshSubscriptions();
            await _vm.Profiles.RefreshServers();
            await _main.Reload();
        }));
        menu.Items.Add(new Separator());
        menu.Items.Add(Item("Добавить ещё подписку", MaterialDesignThemes.Wpf.PackIconKind.Plus, PasteAsync));
        menu.IsOpen = true;
    }

    private async Task SelectCardAsync(HuppSubCard card)
    {
        if (!card.IsSelected)
        {
            _vm?.SelectCard(card);
            // Let the server list switch to this subscription first.
            await Task.Delay(300);
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

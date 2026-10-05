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
        btnTestPing.Click += async (_, _) => await Run(() => _vm?.PingAllAsync());
        btnPingAll.Click += async (_, _) => await Run(() => _vm?.PingAllAsync());
        btnConnectBest.Click += async (_, _) => await Run(() => _vm?.ConnectBestAsync());
        btnUpdateSubs.Click += async (_, _) => await UpdateSubsAsync("");
        btnAdd.Click += async (_, _) => await PasteAsync();
        btnEmptyPaste.Click += async (_, _) => await PasteAsync();
        btnMode.Click += (_, _) => OpenModeMenu();
        btnSort.Click += (_, _) => OpenSortMenu();
        btnResetFilters.Click += (_, _) => _vm?.ResetFilters();
        btnChipsLeft.Click += (_, _) => chipsScroll.ScrollToHorizontalOffset(chipsScroll.HorizontalOffset - 120);
        btnChipsRight.Click += (_, _) => chipsScroll.ScrollToHorizontalOffset(chipsScroll.HorizontalOffset + 120);
        chipsScroll.ScrollChanged += (_, _) => UpdateChipArrows();
        PreviewKeyDown += View_PreviewKeyDown;
        PreviewMouseDown += (_, _) => KeyboardNav = false;
        HuppHomeViewModel.DiagnosisTextOf = cause => DiagnosisTexts.Of(cause).Title;
        btnDiagnose.Click += (_, _) => (Window.GetWindow(this) as MainWindow)?.ShowDiagnosis();
        btnUpdateNow.Click += (_, _) => (Window.GetWindow(this) as MainWindow)?.ShowUpdate();
        btnUpdateInfo.Click += (_, _) => (Window.GetWindow(this) as MainWindow)?.ShowUpdate();
        btnUpdateLater.Click += (_, _) => UpdateNotifier.Later();
        // A quiet card on top of the list when a newer build is out (not skipped, not snoozed); follows changes from the notifier.
        UpdateNotifier.Changed += () => Dispatcher.BeginInvoke(new Action(RefreshUpdateBanner));
        Loaded += (_, _) => RefreshUpdateBanner();
    }

    public void Attach(MainWindowViewModel main)
    {
        _main = main;
        _vm = new HuppHomeViewModel(main.ProfilesViewModel, main.StatusBarViewModel);
        DataContext = _vm;
        _vm.Profiles.ProfileItems.CollectionChanged += OnProfilesChanged;
        _vm.AddRequested += () => (Window.GetWindow(this) as MainWindow)?.ShowAddSubscription();
        UpdateEmptyState();
    }

    public HuppHomeViewModel? HomeViewModel => _vm;

    private void RefreshUpdateBanner()
    {
        var candidate = UpdateNotifier.BannerCandidate();
        updateBanner.Visibility = candidate == null ? Visibility.Collapsed : Visibility.Visible;
        if (candidate != null)
        {
            txtUpdateBanner.Text = $"Доступно обновление build-{candidate.Build}";
        }
    }

    private void OnProfilesChanged(object? sender, NotifyCollectionChangedEventArgs e) => UpdateEmptyState();

    private void UpdateEmptyState()
    {
        var empty = _vm == null || (_vm.Profiles.ProfileItems.Count == 0 && _vm.Profiles.ServerFilter.IsNullOrEmpty());
        var noSubs = _vm == null || _vm.Profiles.SubItems.Count(t => t.Id.IsNotEmpty()) == 0;
        var nothing = empty && noSubs;
        panelEmpty.Visibility = nothing ? Visibility.Visible : Visibility.Collapsed;
        lstServers.Visibility = nothing ? Visibility.Collapsed : Visibility.Visible;
        chipsBar.Visibility = nothing ? Visibility.Collapsed : Visibility.Visible;
        txtSearch.Visibility = nothing ? Visibility.Collapsed : Visibility.Visible;
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

    /// <summary>Opens the add-subscription dialog (a link from the clipboard is already in it).</summary>
    private Task PasteAsync()
    {
        (Window.GetWindow(this) as MainWindow)?.ShowAddSubscription();
        return Task.CompletedTask;
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

    /// <summary>Everything you can do with a subscription (the "⋮" of the card, and a right or middle click on its chip).</summary>
    private void CardMenu_Click(object sender, RoutedEventArgs e)
    {
        var card = CardOf(sender);
        if (card == null)
        {
            return;
        }
        e.Handled = true;
        OpenSubMenu(card, sender as UIElement);
    }

    /// <summary>The plain https link as a QR code (any client can scan it), after a warning that it is as good as a password.</summary>
    private static async Task ShowSubQrAsync(string? url)
    {
        if (url.IsNullOrEmpty())
        {
            return;
        }
        var panel = new StackPanel { Width = 360, Margin = new Thickness(24) };
        panel.Children.Add(new TextBlock { Text = "QR-код подписки", FontSize = 20, FontWeight = FontWeights.SemiBold });
        panel.Children.Add(new TextBlock
        {
            Text = "Ссылка подписки — как пароль: любой, кто отсканирует код, получит доступ. Показывайте только своим устройствам. Код читают и другие приложения.",
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 8, 0, 12),
        });
        var image = new Image { Width = 280, Height = 280, Source = QRCodeWindowsUtils.GetQRCode(url), Visibility = Visibility.Collapsed };
        var show = AddPageView.Flat("Показать код", () => { });
        show.Click += (_, _) =>
        {
            image.Visibility = Visibility.Visible;
            show.Visibility = Visibility.Collapsed;
        };
        panel.Children.Add(image);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 12, 0, 0) };
        buttons.Children.Add(show);
        buttons.Children.Add(AddPageView.Flat("Закрыть", () => MaterialDesignThemes.Wpf.DialogHost.Close("RootDialog")));
        panel.Children.Add(buttons);
        await MaterialDesignThemes.Wpf.DialogHost.Show(panel, "RootDialog");
    }

    private void OpenSubMenu(HuppSubCard card, UIElement? target)
    {
        if (_vm == null || _main == null)
        {
            return;
        }

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

        var enabled = _vm.IsSubscriptionEnabled(card.Sub.Id);
        var menu = new ContextMenu { PlacementTarget = target };
        menu.Items.Add(Item("Обновить подписку", MaterialDesignThemes.Wpf.PackIconKind.Refresh, () => UpdateSubsAsync(card.Sub.Id)));
        menu.Items.Add(Item("Проверить пинг серверов", MaterialDesignThemes.Wpf.PackIconKind.Speedometer, () => _vm.PingSubscriptionAsync(card.Sub.Id)));
        menu.Items.Add(new Separator());
        menu.Items.Add(Item("Переименовать", MaterialDesignThemes.Wpf.PackIconKind.RenameBox, async () =>
        {
            var name = await PromptAsync("Название подписки", _vm.LocalNameOf(card.Sub.Id) ?? card.Title, "Пусто = как у провайдера. Ссылка и данные подписки не меняются");
            if (name != null)
            {
                _vm.RenameSubscription(card.Sub.Id, name);
            }
        }));
        menu.Items.Add(Item(enabled ? "Выключить" : "Включить", enabled ? MaterialDesignThemes.Wpf.PackIconKind.EyeOff : MaterialDesignThemes.Wpf.PackIconKind.Eye,
            () => _vm.SetSubscriptionEnabledAsync(card.Sub, !enabled)));
        menu.Items.Add(Item("Выше в списке", MaterialDesignThemes.Wpf.PackIconKind.ArrowUp, () =>
        {
            _vm.MoveSubscription(card.Sub.Id, -1);
            return Task.CompletedTask;
        }));
        menu.Items.Add(Item("Ниже в списке", MaterialDesignThemes.Wpf.PackIconKind.ArrowDown, () =>
        {
            _vm.MoveSubscription(card.Sub.Id, 1);
            return Task.CompletedTask;
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
        menu.Items.Add(Item("Показать QR-код", MaterialDesignThemes.Wpf.PackIconKind.Qrcode, () => ShowSubQrAsync(card.Sub.Url)));
        if (card.SupportUrl.IsNotEmpty())
        {
            menu.Items.Add(Item("Поддержка провайдера", MaterialDesignThemes.Wpf.PackIconKind.Send, () =>
            {
                ProcUtils.ProcessStart(card.SupportUrl);
                return Task.CompletedTask;
            }));
        }
        menu.Items.Add(new Separator());
        menu.Items.Add(Item("Удалить дубликаты серверов", MaterialDesignThemes.Wpf.PackIconKind.LayersRemove, async () =>
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
            var count = _vm.ServerCountOf(card.Sub.Id);
            if (UI.ShowYesNo($"Удалить подписку «{card.Title}» и её серверы ({count})?") != MessageBoxResult.Yes)
            {
                return;
            }
            await ConfigHandler.DeleteSubItem(AppManager.Instance.Config, card.Sub.Id);
            SubscriptionInfoStore.Remove(card.Sub.Id);
            _vm.ForgetSubscription(card.Sub.Id);
            await _vm.Profiles.RefreshSubscriptions();
            await _vm.Profiles.RefreshServers();
            await _main.Reload();
        }));
        menu.Items.Add(new Separator());
        menu.Items.Add(Item("Добавить ещё подписку", MaterialDesignThemes.Wpf.PackIconKind.Plus, PasteAsync));
        menu.IsOpen = true;
    }

    /// <summary>A one-line text prompt in a dialog; null when cancelled.</summary>
    private static async Task<string?> PromptAsync(string title, string initial, string hint)
    {
        var box = new TextBox { Text = initial, MinWidth = 320, Margin = new Thickness(0, 12, 0, 4) };
        var panel = new StackPanel { Margin = new Thickness(24) };
        panel.Children.Add(new TextBlock { Text = title, FontSize = 18, FontWeight = FontWeights.SemiBold });
        panel.Children.Add(box);
        var note = new TextBlock { Text = hint, FontSize = 12, TextWrapping = TextWrapping.Wrap, MaxWidth = 320 };
        note.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
        panel.Children.Add(note);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 14, 0, 0) };
        var cancel = new Button { Content = "Отмена", Margin = new Thickness(0, 0, 8, 0) };
        var ok = new Button { Content = "Сохранить" };
        if (Application.Current?.TryFindResource("MaterialDesignFlatButton") is Style flat)
        {
            cancel.Style = flat;
        }
        cancel.Click += (_, _) => MaterialDesignThemes.Wpf.DialogHost.Close("RootDialog", false);
        ok.Click += (_, _) => MaterialDesignThemes.Wpf.DialogHost.Close("RootDialog", true);
        buttons.Children.Add(cancel);
        buttons.Children.Add(ok);
        panel.Children.Add(buttons);
        return await MaterialDesignThemes.Wpf.DialogHost.Show(panel, "RootDialog") is true ? box.Text.Trim() : null;
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

    private static HuppServerRow? RowOf(object sender) => (sender as FrameworkElement)?.DataContext as HuppServerRow;

    private static ProfileItemModel? ServerOf(object sender) => RowOf(sender)?.Model;

    private void ServerStar_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if (RowOf(sender) is { } row)
        {
            _vm?.ToggleFavorite(row);
        }
    }

    private void ServerFavorite_Click(object sender, RoutedEventArgs e)
    {
        if (RowOf(sender) is { } row)
        {
            _vm?.ToggleFavorite(row);
        }
    }

    private async void Server_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is DependencyObject source && FindAncestor<Button>(source) != null)
        {
            return;
        }
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

    #region Chips, groups, sort, keyboard

    public static readonly DependencyProperty KeyboardNavProperty =
        DependencyProperty.Register(nameof(KeyboardNav), typeof(bool), typeof(HuppHomeView), new PropertyMetadata(false));

    /// <summary>True while the keyboard drives the list: the focus ring shows only then (a mouse click hides it).</summary>
    public bool KeyboardNav
    {
        get => (bool)GetValue(KeyboardNavProperty);
        set => SetValue(KeyboardNavProperty, value);
    }

    private static SubChip? ChipOf(object sender) => (sender as FrameworkElement)?.DataContext as SubChip;

    private HuppSubCard? CardOfChip(SubChip? chip) =>
        chip is { IsSub: true } ? _vm?.Cards.FirstOrDefault(c => c.Sub.Id == chip.Selection.SubId) : null;

    private void Chip_MouseLeftButtonUp(object sender, MouseButtonEventArgs e) => _vm?.SelectChip(ChipOf(sender));

    /// <summary>Middle click opens the management menu of a subscription chip, like a right click.</summary>
    private void Chip_MouseDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton == MouseButton.Middle && CardOfChip(ChipOf(sender)) is { } card)
        {
            e.Handled = true;
            OpenSubMenu(card, sender as UIElement);
        }
    }

    private void Chip_MouseRightButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (CardOfChip(ChipOf(sender)) is { } card)
        {
            e.Handled = true;
            OpenSubMenu(card, sender as UIElement);
        }
    }

    /// <summary>The wheel over the strip scrolls it sideways.</summary>
    private void Chips_MouseWheel(object sender, MouseWheelEventArgs e)
    {
        chipsScroll.ScrollToHorizontalOffset(chipsScroll.HorizontalOffset - e.Delta);
        e.Handled = true;
    }

    private void UpdateChipArrows()
    {
        var overflow = chipsScroll.ExtentWidth > chipsScroll.ViewportWidth + 1;
        btnChipsLeft.Visibility = btnChipsRight.Visibility = overflow ? Visibility.Visible : Visibility.Collapsed;
        btnChipsLeft.IsEnabled = chipsScroll.HorizontalOffset > 0;
        btnChipsRight.IsEnabled = chipsScroll.HorizontalOffset < chipsScroll.ScrollableWidth - 1;
    }

    private void Header_MouseLeftButtonUp(object sender, MouseButtonEventArgs e)
    {
        if (e.OriginalSource is DependencyObject source && FindAncestor<Button>(source) != null)
        {
            return;
        }
        if ((sender as FrameworkElement)?.DataContext is HuppHeaderRow header)
        {
            _vm?.SelectChip(_vm.Chips.FirstOrDefault(c => c.IsSub && c.Selection.SubId == header.SubId));
        }
    }

    private void Header_MouseRightButtonUp(object sender, MouseButtonEventArgs e)
    {
        if ((sender as FrameworkElement)?.DataContext is HuppHeaderRow header && _vm?.Cards.FirstOrDefault(c => c.Sub.Id == header.SubId) is { } card)
        {
            e.Handled = true;
            OpenSubMenu(card, sender as UIElement);
        }
    }

    private void HeaderToggle_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if ((sender as FrameworkElement)?.DataContext is HuppHeaderRow header)
        {
            _vm?.ToggleGroup(header);
        }
    }

    private async void HeaderPing_Click(object sender, RoutedEventArgs e)
    {
        e.Handled = true;
        if ((sender as FrameworkElement)?.DataContext is HuppHeaderRow header && _vm != null)
        {
            await Run(() => _vm.PingSubscriptionAsync(header.SubId));
        }
    }

    private void AnnounceToggle_Click(object sender, RoutedEventArgs e)
    {
        if (CardOf(sender) is { } card)
        {
            card.AnnounceExpanded = !card.AnnounceExpanded;
        }
    }

    private void OpenSortMenu()
    {
        if (_vm == null)
        {
            return;
        }
        var menu = new ContextMenu { PlacementTarget = btnSort, Placement = System.Windows.Controls.Primitives.PlacementMode.Bottom };
        foreach (var (sort, title) in new[] { (ServerSort.Provider, "Как у провайдера"), (ServerSort.Ping, "По пингу"), (ServerSort.Name, "По названию") })
        {
            var item = new MenuItem { Header = title, IsChecked = _vm.Sort == sort };
            var chosen = sort;
            item.Click += (_, _) => _vm.Sort = chosen;
            menu.Items.Add(item);
        }
        menu.IsOpen = true;
    }

    /// <summary>Ctrl+F search, Ctrl+Tab / Ctrl+Shift+Tab next / previous subscription, Enter connects to the selected server, Del removes a favourite.</summary>
    private void View_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key is Key.Tab or Key.Up or Key.Down or Key.Enter or Key.PageUp or Key.PageDown or Key.Home or Key.End)
        {
            KeyboardNav = true;
        }
        var ctrl = (Keyboard.Modifiers & ModifierKeys.Control) != 0;
        if (ctrl && e.Key == Key.F)
        {
            txtSearch.Focus();
            txtSearch.SelectAll();
            e.Handled = true;
        }
        else if (ctrl && e.Key == Key.Tab)
        {
            _vm?.StepChip((Keyboard.Modifiers & ModifierKeys.Shift) != 0 ? -1 : 1);
            e.Handled = true;
        }
        else if (e.Key == Key.Escape && txtSearch.IsKeyboardFocused && txtSearch.Text.Length > 0)
        {
            txtSearch.Clear();
            e.Handled = true;
        }
    }

    private async void Servers_KeyDown(object sender, KeyEventArgs e)
    {
        if (_vm == null || lstServers.SelectedItem is not HuppServerRow row)
        {
            return;
        }
        if (e.Key == Key.Enter)
        {
            e.Handled = true;
            await Run(async () =>
            {
                await _vm.SelectServerAsync(row.Model);
                if (!_vm.IsConnected)
                {
                    await _vm.ConnectAsync();
                }
            });
        }
        else if (e.Key == Key.Delete && row.IsFavorite)
        {
            e.Handled = true;
            _vm.ToggleFavorite(row);
        }
    }

    #endregion Chips, groups, sort, keyboard

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

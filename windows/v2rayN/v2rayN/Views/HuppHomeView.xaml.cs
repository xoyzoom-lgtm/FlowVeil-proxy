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
        btnUpdateSubs.Click += async (_, _) => await Run(() => _main?.UpdateSubscriptionProcess("", false));
        btnAdd.Click += async (_, _) => await PasteAsync();
        btnEmptyPaste.Click += async (_, _) => await PasteAsync();
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

    private async Task PasteAsync()
    {
        var data = WindowsUtils.GetClipboardData();
        if (data.IsNullOrEmpty() || _main == null)
        {
            NoticeManager.Instance.Enqueue("Буфер обмена пуст");
            return;
        }
        await Run(() => _main.AddServerViaClipboardAsync(data));
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
            await Run(() => _main?.UpdateSubscriptionProcess(card.Sub.Id, false));
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

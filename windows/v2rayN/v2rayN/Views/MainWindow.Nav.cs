using System.Windows.Media.Animation;
using System.Windows.Controls;
using MaterialDesignThemes.Wpf;

namespace v2rayN.Views;

/// <summary>Expandable navigation panel: 200 px with names, 72 px icons only. State lives in a tiny file next to the config.</summary>
public partial class MainWindow
{
    private const double NavExpanded = 200;
    private const double NavCollapsed = 72;
    private const double NavAutoCollapseWidth = 1000;
    private const string NavStateFile = "nav_collapsed";

    private bool _navCollapsed;
    private bool _navUserChoice;
    private bool _navAutoApplied;
    private FrameworkElement[] _pages = [];

    private IEnumerable<(RadioButton Item, TextBlock Label, string Title)> NavItems() =>
    [
        (navAdd, navAddLabel, "Добавить"),
        (navHome, navHomeLabel, "Серверы"),
        (navSettings, navSettingsLabel, "Настройки"),
        (navStats, navStatsLabel, "Статистика"),
        (navLogs, navLogsLabel, "Логи"),
        (navAdvanced, navAdvancedLabel, "Детально"),
    ];

    private void InitNav()
    {
        _pages = [addPage, homeView, settingsPage, statsPage, logsPage, advancedPanel];

        navAdd.Checked += (_, _) => ShowPage(addPage);
        navHome.Checked += (_, _) => ShowPage(homeView);
        navSettings.Checked += (_, _) => ShowPage(settingsPage);
        navStats.Checked += (_, _) => ShowPage(statsPage);
        navLogs.Checked += (_, _) => ShowPage(logsPage);
        navAdvanced.Checked += (_, _) => ShowPage(advancedPanel);
        navHome.Checked += (_, _) => navAdvanced.Visibility = Visibility.Collapsed;

        btnNavToggle.Click += (_, _) =>
        {
            _navUserChoice = true;
            SetNavCollapsed(!_navCollapsed, true);
            SaveNavState();
        };

        var saved = LoadNavState();
        _navUserChoice = saved != null;
        _navCollapsed = saved ?? false;
        ApplyNav(false);
        SizeChanged += (_, e) => AutoCollapseByWidth(e.NewSize.Width);
        Loaded += (_, _) => AutoCollapseByWidth(ActualWidth);
    }

    /// <summary>Narrow window collapses the panel; a saved choice made in a wide window wins as soon as there is room.</summary>
    private void AutoCollapseByWidth(double width)
    {
        if (width <= 0)
        {
            return;
        }

        if (width < NavAutoCollapseWidth)
        {
            if (!_navCollapsed)
            {
                _navAutoApplied = true;
                SetNavCollapsed(true, true);
            }
        }
        else if (_navAutoApplied)
        {
            _navAutoApplied = false;
            SetNavCollapsed(_navUserChoice && LoadNavState() == true, true);
        }
    }

    private void SetNavCollapsed(bool collapsed, bool animate)
    {
        _navCollapsed = collapsed;
        ApplyNav(animate);
    }

    private void ApplyNav(bool animate)
    {
        var target = _navCollapsed ? NavCollapsed : NavExpanded;
        if (animate)
        {
            var duration = TimeSpan.FromMilliseconds(180);
            navPanel.BeginAnimation(WidthProperty, new DoubleAnimation(target, duration) { EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut } });
        }
        else
        {
            navPanel.BeginAnimation(WidthProperty, null);
            navPanel.Width = target;
        }

        var fadeTo = _navCollapsed ? 0.0 : 1.0;
        foreach (var (item, label, title) in NavItems())
        {
            if (animate)
            {
                label.BeginAnimation(OpacityProperty, new DoubleAnimation(fadeTo, TimeSpan.FromMilliseconds(120)));
            }
            else
            {
                label.BeginAnimation(OpacityProperty, null);
                label.Opacity = fadeTo;
            }
            item.ToolTip = _navCollapsed ? title : null;
        }

        navBrand.BeginAnimation(OpacityProperty, animate ? new DoubleAnimation(fadeTo, TimeSpan.FromMilliseconds(120)) : null);
        if (!animate)
        {
            navBrand.Opacity = fadeTo;
        }

        navToggleIcon.Kind = _navCollapsed ? PackIconKind.ArrowRight : PackIconKind.ArrowLeft;
        btnNavToggle.ToolTip = _navCollapsed ? "Развернуть панель" : "Свернуть панель";
    }

    private static bool? LoadNavState()
    {
        try
        {
            var path = Utils.GetConfigPath(NavStateFile);
            return File.Exists(path) ? File.ReadAllText(path).Trim() == "1" : null;
        }
        catch
        {
            return null;
        }
    }

    private void SaveNavState()
    {
        try
        {
            File.WriteAllText(Utils.GetConfigPath(NavStateFile), _navCollapsed ? "1" : "0");
        }
        catch
        {
            // not persisted: the panel just starts expanded next time
        }
    }

    private void ShowPage(FrameworkElement page)
    {
        // Hidden (not Collapsed) keeps the classic views loaded so their bindings stay active.
        foreach (var candidate in _pages)
        {
            candidate.Visibility = candidate == page ? Visibility.Visible : Visibility.Hidden;
        }
    }

    /// <summary>Opens a page by its position in the rail (Ctrl+1…5).</summary>
    private bool NavigateByIndex(int index)
    {
        var target = index switch { 1 => navAdd, 2 => navHome, 3 => navSettings, 4 => navStats, 5 => navLogs, _ => null };
        if (target == null)
        {
            return false;
        }
        target.IsChecked = true;
        target.Focus();
        return true;
    }

    public void GoToServers() => navHome.IsChecked = true;

    public void GoToAdd() => navAdd.IsChecked = true;
}

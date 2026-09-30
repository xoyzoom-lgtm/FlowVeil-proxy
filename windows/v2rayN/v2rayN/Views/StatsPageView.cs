using System.Windows.Controls;
using System.Windows.Threading;
using MaterialDesignThemes.Wpf;
using ServiceLib.Models.Entities;

namespace v2rayN.Views;

/// <summary>
/// "Статистика": traffic counted by the core per server (today and in total), summed per subscription.
/// This is all the core provides: no per-app or per-connection history. Counting is off until the switch is turned on.
/// </summary>
public sealed class StatsPageView : ScrollViewer
{
    private readonly MainWindowViewModel _vm;
    private readonly StackPanel _column = new() { MaxWidth = 760, Margin = new Thickness(24, 20, 24, 24), HorizontalAlignment = HorizontalAlignment.Center };
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(3) };

    public StatsPageView(MainWindowViewModel vm)
    {
        _vm = vm;
        VerticalScrollBarVisibility = ScrollBarVisibility.Auto;
        Content = _column;
        _timer.Tick += async (_, _) => await RefreshAsync();
        IsVisibleChanged += async (_, e) =>
        {
            if ((bool)e.NewValue)
            {
                await RefreshAsync();
                _timer.Start();
            }
            else
            {
                _timer.Stop();
            }
        };
    }

    private static string Bytes(long value) => Utils.HumanFy(value / 1024);

    private async Task RefreshAsync()
    {
        try
        {
            _column.Children.Clear();
            _column.Children.Add(new TextBlock { Text = "Статистика", FontSize = 24, FontWeight = FontWeights.Bold });
            var config = AppManager.Instance.Config;
            if (!config.GuiItem.EnableStatistics)
            {
                AddEmpty("Подсчёт трафика выключен", "Включите его, чтобы видеть, сколько скачано и отправлено через каждый сервер и подписку. Подсчёт начнётся после переподключения",
                    "Включить подсчёт", async () =>
                    {
                        config.GuiItem.EnableStatistics = true;
                        await ConfigHandler.SaveConfig(config);
                        _vm.Reload();
                        NoticeManager.Instance.Enqueue("Подсчёт включён, данные появятся после подключения");
                    });
                return;
            }

            var stats = StatisticsManager.Instance.ServerStat?.ToList() ?? [];
            var profiles = await ServiceLib.Helper.SQLiteHelper.Instance.TableAsync<ProfileItem>().ToListAsync();
            var subs = await ServiceLib.Helper.SQLiteHelper.Instance.TableAsync<SubItem>().ToListAsync();
            var byId = profiles.ToDictionary(p => p.IndexId);
            var rows = stats.Where(s => byId.ContainsKey(s.IndexId)).Select(s => (Stat: s, Profile: byId[s.IndexId])).ToList();

            if (rows.Count == 0 || rows.All(r => r.Stat.TotalDown + r.Stat.TotalUp == 0))
            {
                AddEmpty("Пока нет данных", "Подключитесь к серверу и пользуйтесь интернетом: цифры появятся здесь", null, null);
                return;
            }

            var summary = new System.Windows.Controls.Primitives.UniformGrid { Columns = 2, Margin = new Thickness(0, 12, 0, 12) };
            summary.Children.Add(Tile("Сегодня", $"↓ {Bytes(rows.Sum(r => r.Stat.TodayDown))}   ↑ {Bytes(rows.Sum(r => r.Stat.TodayUp))}"));
            summary.Children.Add(Tile("Всего", $"↓ {Bytes(rows.Sum(r => r.Stat.TotalDown))}   ↑ {Bytes(rows.Sum(r => r.Stat.TotalUp))}"));
            _column.Children.Add(summary);

            var perSub = rows.GroupBy(r => r.Profile.Subid).Select(g => (Name: subs.FirstOrDefault(s => s.Id == g.Key)?.Remarks ?? "Без подписки",
                Today: g.Sum(r => r.Stat.TodayDown + r.Stat.TodayUp), Total: g.Sum(r => r.Stat.TotalDown + r.Stat.TotalUp),
                Down: g.Sum(r => r.Stat.TotalDown), Up: g.Sum(r => r.Stat.TotalUp))).OrderByDescending(x => x.Total).ToList();
            _column.Children.Add(Header("По подпискам"));
            _column.Children.Add(ListCard(perSub.Select(x => (x.Name, $"↓ {Bytes(x.Down)}   ↑ {Bytes(x.Up)}   ·   сегодня {Bytes(x.Today)}"))));

            var top = rows.OrderByDescending(r => r.Stat.TotalDown + r.Stat.TotalUp).Take(10)
                .Select(r => (r.Profile.Remarks, $"↓ {Bytes(r.Stat.TotalDown)}   ↑ {Bytes(r.Stat.TotalUp)}   ·   сегодня {Bytes(r.Stat.TodayDown + r.Stat.TodayUp)}"));
            _column.Children.Add(Header("Серверы (10 самых нагруженных)"));
            _column.Children.Add(ListCard(top));

            var reset = new Button { Content = "Сбросить статистику", Margin = new Thickness(0, 12, 0, 0), HorizontalAlignment = HorizontalAlignment.Left };
            reset.Style = (Style)Application.Current.FindResource("MaterialDesignFlatButton");
            reset.Click += async (_, _) =>
            {
                if (UI.ShowYesNo("Обнулить счётчики трафика?") == MessageBoxResult.Yes)
                {
                    await StatisticsManager.Instance.ClearAllServerStatistics();
                    await RefreshAsync();
                }
            };
            _column.Children.Add(reset);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(StatsPageView), ex);
        }
    }

    private void AddEmpty(string title, string hint, string? button, Func<Task>? action)
    {
        var box = new StackPanel { Margin = new Thickness(0, 60, 0, 0), HorizontalAlignment = HorizontalAlignment.Center };
        var icon = new PackIcon { Kind = PackIconKind.ChartLine, Width = 48, Height = 48, HorizontalAlignment = HorizontalAlignment.Center };
        icon.SetResourceReference(ForegroundProperty, "MaterialDesign.Brush.Primary");
        box.Children.Add(icon);
        box.Children.Add(new TextBlock { Text = title, FontSize = 18, FontWeight = FontWeights.SemiBold, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 12, 0, 4) });
        var hintBlock = new TextBlock { Text = hint, TextWrapping = TextWrapping.Wrap, MaxWidth = 420, TextAlignment = TextAlignment.Center };
        hintBlock.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
        box.Children.Add(hintBlock);
        if (button != null && action != null)
        {
            var b = new Button { Content = button, Margin = new Thickness(0, 16, 0, 0), HorizontalAlignment = HorizontalAlignment.Center };
            b.Style = (Style)Application.Current.FindResource("MaterialDesignRaisedButton");
            b.Click += async (_, _) =>
            {
                await action();
                await RefreshAsync();
            };
            box.Children.Add(b);
        }
        _column.Children.Add(box);
    }

    private static TextBlock Header(string text)
    {
        var t = new TextBlock { Text = text.ToUpperInvariant(), FontSize = 12, FontWeight = FontWeights.SemiBold, Margin = new Thickness(8, 12, 0, 6) };
        t.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
        return t;
    }

    private static Border Tile(string title, string value)
    {
        var caption = new TextBlock { Text = title, FontSize = 12 };
        caption.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
        var tile = new Border { CornerRadius = new CornerRadius(18), Padding = new Thickness(16), Margin = new Thickness(0, 0, 8, 0), Child = new StackPanel { Children = { caption, new TextBlock { Text = value, FontSize = 18, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 4, 0, 0) } } } };
        tile.SetResourceReference(Border.BackgroundProperty, "MaterialDesign.Brush.Card.Background");
        return tile;
    }

    private static Border ListCard(IEnumerable<(string Title, string Value)> items)
    {
        var rows = new StackPanel();
        foreach (var (title, value) in items)
        {
            var line = new StackPanel { Margin = new Thickness(8, 6, 8, 6) };
            line.Children.Add(new TextBlock { Text = title, FontWeight = FontWeights.SemiBold, TextTrimming = TextTrimming.CharacterEllipsis });
            var v = new TextBlock { Text = value, FontSize = 12 };
            v.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
            line.Children.Add(v);
            rows.Children.Add(line);
        }
        var card = new Border { CornerRadius = new CornerRadius(18), Padding = new Thickness(6), Child = rows };
        card.SetResourceReference(Border.BackgroundProperty, "MaterialDesign.Brush.Card.Background");
        return card;
    }
}

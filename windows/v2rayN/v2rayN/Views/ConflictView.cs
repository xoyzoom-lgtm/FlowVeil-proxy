using System.Windows.Controls;
using MaterialDesignThemes.Wpf;

namespace v2rayN.Views;

/// <summary>
/// "Обнаружено конфликтующее ПО": programs that hook the network (zapret, GoodbyeDPI, other proxy clients) are running and can break FlowVeil.
/// Offers to close them; asks again only when something new shows up in the same run.
/// </summary>
public sealed class ConflictView : StackPanel
{
    private static readonly HashSet<string> Dismissed = [];
    private static bool _open;

    private readonly List<ConflictItem> _items;
    private readonly TextBlock _result = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 12, 0, 0), Visibility = Visibility.Collapsed };
    private readonly Button _unload;

    private ConflictView(List<ConflictItem> items)
    {
        _items = items;
        Width = 460;
        Margin = new Thickness(24);
        Children.Add(new TextBlock { Text = "Обнаружено конфликтующее ПО", FontSize = 20, FontWeight = FontWeights.SemiBold });
        Children.Add(new TextBlock
        {
            Text = "Следующие программы запущены и могут мешать работе FlowVeil:",
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 12, 0, 8),
        });
        foreach (var item in items)
        {
            Children.Add(new TextBlock { Text = "•  " + ConflictingSoftware.Label(item), Margin = new Thickness(8, 2, 0, 2) });
        }
        Children.Add(new TextBlock
        {
            Text = "Рекомендуем закрыть эти процессы перед использованием FlowVeil.",
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 12, 0, 0),
        });
        Children.Add(_result);

        _unload = new Button { Content = "Выгрузить процессы", Margin = new Thickness(0, 0, 8, 0) };
        _unload.SetResourceReference(StyleProperty, "MaterialDesignRaisedButton");
        _unload.Click += async (_, _) => await UnloadAsync();
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
        buttons.Children.Add(_unload);
        buttons.Children.Add(AddPageView.Flat("Закрыть", () => DialogHost.Close("RootDialog")));
        Children.Add(buttons);
    }

    private async Task UnloadAsync()
    {
        _unload.IsEnabled = false;
        var failed = await Task.Run(() => ConflictingSoftware.Unload(_items));
        _result.Visibility = Visibility.Visible;
        if (failed.Count == 0)
        {
            _result.Text = "Готово: процессы закрыты.";
            _result.Foreground = System.Windows.Media.Brushes.MediumSeaGreen;
            _unload.Visibility = Visibility.Collapsed;
        }
        else
        {
            _result.Text = "Не удалось закрыть: " + string.Join(", ", failed.Select(ConflictingSoftware.Label))
                + ". Скорее всего, они запущены от администратора: закройте их вручную (значок в трее или Диспетчер задач) либо запустите FlowVeil от администратора.";
            _result.Foreground = System.Windows.Media.Brushes.Orange;
            _unload.IsEnabled = true;
        }
    }

    /// <summary>Shows the window when a conflicting program is running that the user has not already seen (and closed) in this run.</summary>
    public static async Task ShowIfAnyAsync()
    {
        if (_open || !ConflictingSoftware.WarnEnabled)
        {
            return;
        }
        var found = await Task.Run(ConflictingSoftware.FindRunning);
        var fresh = found.Where(f => !Dismissed.Contains(f.Process)).ToList();
        if (fresh.Count == 0)
        {
            return;
        }
        _open = true;
        try
        {
            foreach (var item in found)
            {
                Dismissed.Add(item.Process);
            }
            await DialogHost.Show(new ConflictView(found), "RootDialog");
        }
        finally
        {
            _open = false;
        }
    }
}

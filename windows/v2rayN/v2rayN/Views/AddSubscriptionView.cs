using System.Windows.Controls;
using MaterialDesignThemes.Wpf;
using Microsoft.Win32;

namespace v2rayN.Views;

/// <summary>
/// "Add subscription": the way to connect it is chosen here (link, name, how often to refresh, which
/// format to ask the panel for), plus the other ways in: a file, a QR code on the screen or in a
/// picture, a QR code shown by FlowVeil for a phone. Built in code like the settings page.
/// </summary>
public sealed class AddSubscriptionView : StackPanel
{
    private static readonly (string Title, string UserAgent)[] Formats =
    [
        ("По умолчанию", string.Empty),
        ("Clash / Mihomo", "clash.meta"),
        ("sing-box", "sing-box"),
    ];

    private readonly MainWindowViewModel _vm;
    private readonly TextBox _link = new();
    private readonly TextBox _name = new();
    private readonly ComboBox _interval = new();
    private readonly ComboBox _format = new();
    private readonly TextBlock _error = new();
    private readonly Action _showReceiveQr;

    public AddSubscriptionView(MainWindowViewModel vm, string? prefill, Action showReceiveQr)
    {
        _vm = vm;
        _showReceiveQr = showReceiveQr;
        Width = 460;
        Margin = new Thickness(24);

        Children.Add(new TextBlock { Text = "Добавить подписку", FontSize = 20, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 14) });

        Style(_link, "MaterialDesignOutlinedTextBox");
        HintAssist.SetHint(_link, "Ссылка подписки (https://…)");
        _link.Text = prefill ?? string.Empty;
        var paste = Flat("Вставить", () => _link.Text = ReadLinkFromClipboard() ?? _link.Text);
        var linkRow = new DockPanel { LastChildFill = true };
        DockPanel.SetDock(paste, Dock.Right);
        linkRow.Children.Add(paste);
        linkRow.Children.Add(_link);
        Children.Add(linkRow);

        Style(_name, "MaterialDesignOutlinedTextBox");
        HintAssist.SetHint(_name, "Название (необязательно)");
        _name.Margin = new Thickness(0, 12, 0, 0);
        Children.Add(_name);

        FillCombo(_interval, SubAutoUpdate.Options.Select(o => o.Title), Math.Max(0, SubAutoUpdate.Options.ToList().FindIndex(o => o.Minutes == SubAutoUpdate.Get())), "Обновлять");
        Children.Add(_interval);
        FillCombo(_format, Formats.Select(f => f.Title), 0, "Какой формат просить у сервиса");
        Children.Add(_format);

        _error.Foreground = System.Windows.Media.Brushes.IndianRed;
        _error.TextWrapping = TextWrapping.Wrap;
        _error.Margin = new Thickness(0, 8, 0, 0);
        Children.Add(_error);

        var others = new WrapPanel { Margin = new Thickness(0, 14, 0, 0) };
        others.Children.Add(Flat("Из файла…", FromFile));
        others.Children.Add(Flat("QR с экрана", () => Close(() => ((ICommand)_vm.AddServerViaScanCmd).Execute(null))));
        others.Children.Add(Flat("QR из картинки…", () => Close(() => ((ICommand)_vm.AddServerViaImageCmd).Execute(null))));
        others.Children.Add(Flat("QR для телефона", () => Close(_showReceiveQr)));
        Children.Add(others);

        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
        buttons.Children.Add(Flat("Отмена", () => Close(null)));
        var add = new Button { Content = "Добавить", Margin = new Thickness(8, 0, 0, 0) };
        Style(add, "MaterialDesignRaisedButton");
        add.Click += async (_, _) => await AddAsync();
        buttons.Children.Add(add);
        Children.Add(buttons);
    }

    private static void Style(FrameworkElement element, string key)
    {
        if (Application.Current?.TryFindResource(key) is Style style)
        {
            element.Style = style;
        }
    }

    private static Button Flat(string text, Action click)
    {
        var button = new Button { Content = text, Margin = new Thickness(0, 0, 4, 0) };
        Style(button, "MaterialDesignFlatButton");
        button.Click += (_, _) => click();
        return button;
    }

    private static void FillCombo(ComboBox combo, IEnumerable<string> items, int selected, string hint)
    {
        Style(combo, "MaterialDesignOutlinedComboBox");
        HintAssist.SetHint(combo, hint);
        combo.Margin = new Thickness(0, 12, 0, 0);
        foreach (var item in items)
        {
            combo.Items.Add(item);
        }
        combo.SelectedIndex = selected;
    }

    private static string? ReadLinkFromClipboard()
    {
        var text = WindowsUtils.GetClipboardData();
        return text.IsNullOrEmpty() ? null : text.Trim();
    }

    private static void Close(Action? then)
    {
        DialogHost.Close("RootDialog");
        then?.Invoke();
    }

    private void FromFile()
    {
        var dialog = new OpenFileDialog { Filter = "Текст, JSON, YAML|*.txt;*.json;*.yaml;*.yml|Все файлы|*.*" };
        if (dialog.ShowDialog() != true)
        {
            return;
        }
        try
        {
            var text = File.ReadAllText(dialog.FileName);
            Close(() => _ = _vm.AddServerViaClipboardAsync(text));
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(AddSubscriptionView), ex);
            _error.Text = "Не получилось прочитать файл";
        }
    }

    private async Task AddAsync()
    {
        _error.Text = string.Empty;
        var link = _link.Text.Trim();
        // An invite link (flowveil://add?url=…) carries the address inside.
        link = DeepLink.Parse([link]) ?? link;
        if (link.IsNullOrEmpty())
        {
            _error.Text = "Вставьте ссылку подписки";
            return;
        }
        if (!link.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !link.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            // A share link of one server (vless://, vmess://…): the usual import handles it.
            Close(() => _ = _vm.AddServerViaClipboardAsync(link));
            return;
        }
        var uri = Utils.TryUri(link);
        if (uri == null)
        {
            _error.Text = "Это не похоже на ссылку. Пример: https://provider.example/sub/…";
            return;
        }
        var exists = await ServiceLib.Helper.SQLiteHelper.Instance.TableAsync<ServiceLib.Models.Entities.SubItem>().CountAsync(e => e.Url == link) > 0;
        if (exists)
        {
            _error.Text = "Такая подписка уже добавлена";
            return;
        }
        var minutes = SubAutoUpdate.Options[Math.Max(0, _interval.SelectedIndex)].Minutes;
        var subItem = new ServiceLib.Models.Entities.SubItem
        {
            Id = string.Empty,
            Url = link,
            Remarks = _name.Text.Trim().NullIfEmpty() ?? uri.Host,
            AutoUpdateInterval = minutes,
            UserAgent = Formats[Math.Max(0, _format.SelectedIndex)].UserAgent,
        };
        if (await ConfigHandler.AddSubItem(AppManager.Instance.Config, subItem) != 0)
        {
            _error.Text = "Не получилось добавить подписку";
            return;
        }
        Close(() => ((ICommand)_vm.SubUpdateCmd).Execute(null));
        NoticeManager.Instance.Enqueue("Подписка добавлена, загружаю серверы…");
    }
}

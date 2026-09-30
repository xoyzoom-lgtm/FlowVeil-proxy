using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using MaterialDesignThemes.Wpf;
using Microsoft.Win32;
using ServiceLib.Models.Entities;

namespace v2rayN.Views;

/// <summary>
/// The "Добавить" page: every way to get a subscription into FlowVeil as a card. All of them end in the same place:
/// servers are loaded, the app switches to "Серверы" and says how many were added. Built in code, colours come from theme resources.
/// </summary>
public sealed class AddPageView : ScrollViewer
{
    private static readonly (string Title, string UserAgent)[] Formats =
    [
        ("По умолчанию", string.Empty),
        ("Clash / Mihomo", "clash.meta"),
        ("sing-box", "sing-box"),
    ];

    private readonly MainWindowViewModel _vm;
    private readonly Action _goToServers;
    private readonly TextBox _link = new();
    private readonly TextBox _name = new();
    private readonly ComboBox _interval = new();
    private readonly ComboBox _format = new();
    private readonly TextBlock _error = new();
    private readonly ProgressBar _busy = new() { IsIndeterminate = true, Visibility = Visibility.Collapsed, Height = 3, Margin = new Thickness(0, 0, 0, 12) };
    private readonly Button _addButton = new() { Content = "Добавить" };
    private readonly Border _clipHint = new();
    private readonly TextBlock _clipText = new();
    private string? _clipLink;
    private readonly PairPanel _pair;

    public AddPageView(MainWindowViewModel vm, Action goToServers)
    {
        _vm = vm;
        _goToServers = goToServers;
        VerticalScrollBarVisibility = ScrollBarVisibility.Auto;
        Focusable = false;

        var column = new StackPanel { MaxWidth = 760, Margin = new Thickness(24, 20, 24, 24), HorizontalAlignment = HorizontalAlignment.Center };
        Content = column;
        column.Children.Add(new TextBlock { Text = "Добавить", FontSize = 24, FontWeight = FontWeights.Bold });
        var sub = new TextBlock { Text = "Выберите, откуда взять подписку. Серверы загрузятся сами.", Margin = new Thickness(0, 4, 0, 14) };
        sub.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
        column.Children.Add(sub);
        column.Children.Add(_busy);

        column.Children.Add(BuildLinkCard());
        _pair = new PairPanel(OnPairReceived);
        column.Children.Add(Card(PackIconKind.CellphoneArrowDown, "С телефона", "Как на телевизоре: покажем QR-код, телефон отправит ссылку сюда. Оба устройства в одной сети", _pair));
        column.Children.Add(BuildFileCard());
        column.Children.Add(Card(PackIconKind.FolderSearchOutline, "Из v2rayN на этом компьютере", "Найдём его подписки сами. Ничего не удаляется и не заменяется",
            Buttons(Primary("Найти и добавить", () => _ = RunMigrationAsync(SubMigration.FindV2rayNDb())))));
        column.Children.Add(Card(PackIconKind.DatabaseImportOutline, "Из файла guiNDB.db", "Если v2rayN лежит в другом месте: папка guiConfigs внутри него",
            Buttons(Flat("Выбрать файл…", () =>
            {
                if (UI.OpenFileDialog(out var fileName, "v2rayN database|guiNDB.db|All|*.*") == true)
                {
                    _ = RunMigrationAsync(fileName);
                }
            }))));
        column.Children.Add(Card(PackIconKind.ContentPaste, "Из Happ и других приложений",
            "Скопируйте ссылку подписки в том приложении (в Happ: «Поделиться подпиской») и вставьте сюда. Зашифрованные ссылки (happ://crypt…) открыть нельзя: попросите у провайдера обычную ссылку",
            Buttons(Flat("Вставить из буфера", () => _ = AddFromTextAsync(ReadClipboard())))));

        IsVisibleChanged += (_, e) =>
        {
            if ((bool)e.NewValue)
            {
                RefreshClipboardHint();
            }
            else
            {
                _pair.Stop();
            }
        };
    }

    /// <summary>Puts a link into the field (from the header button or a clipboard suggestion).</summary>
    public void Prefill(string link)
    {
        _link.Text = link;
        _link.Focus();
    }

    /// <summary>Adds what an invite link carries (opened from outside the app).</summary>
    public Task<bool> AddInviteAsync(string link, string? name) => AddFromTextAsync(link, name, showInline: false);

    // ---------- cards ----------

    private UIElement BuildLinkCard()
    {
        Style(_link, "MaterialDesignOutlinedTextBox");
        HintAssist.SetHint(_link, "Ссылка подписки (https://…)");
        _link.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Enter)
            {
                _ = AddFromFieldAsync();
            }
        };
        var paste = Flat("Вставить", () =>
        {
            var text = ReadClipboard();
            if (text != null)
            {
                _link.Text = text;
            }
        });
        var row = new DockPanel { LastChildFill = true };
        DockPanel.SetDock(paste, Dock.Right);
        row.Children.Add(paste);
        row.Children.Add(_link);

        _clipText.TextTrimming = TextTrimming.CharacterEllipsis;
        var use = Flat("Использовать", () =>
        {
            if (_clipLink != null)
            {
                _link.Text = _clipLink;
            }
        });
        var clipRow = new DockPanel { LastChildFill = true };
        DockPanel.SetDock(use, Dock.Right);
        clipRow.Children.Add(use);
        clipRow.Children.Add(_clipText);
        _clipHint.Child = clipRow;
        _clipHint.CornerRadius = new CornerRadius(10);
        _clipHint.Padding = new Thickness(10, 2, 2, 2);
        _clipHint.Margin = new Thickness(0, 8, 0, 0);
        _clipHint.Visibility = Visibility.Collapsed;
        _clipHint.SetResourceReference(Border.BackgroundProperty, "MaterialDesign.Brush.Background");

        Style(_name, "MaterialDesignOutlinedTextBox");
        HintAssist.SetHint(_name, "Название (необязательно)");
        FillCombo(_interval, SubAutoUpdate.Options.Select(o => o.Title), Math.Max(0, SubAutoUpdate.Options.ToList().FindIndex(o => o.Minutes == SubAutoUpdate.Get())), "Обновлять");
        FillCombo(_format, Formats.Select(f => f.Title), 0, "Какой формат просить у сервиса");
        _name.Margin = new Thickness(0, 4, 0, 0);
        var expander = new Expander { Header = "Параметры", Margin = new Thickness(0, 8, 0, 0), Content = new StackPanel { Children = { _name, _interval, _format } } };

        _error.Foreground = Brushes.IndianRed;
        _error.TextWrapping = TextWrapping.Wrap;
        _error.Margin = new Thickness(0, 6, 0, 0);
        Style(_addButton, "MaterialDesignRaisedButton");
        _addButton.HorizontalAlignment = HorizontalAlignment.Right;
        _addButton.Margin = new Thickness(0, 10, 0, 0);
        _addButton.Click += async (_, _) => await AddFromFieldAsync();

        return Card(PackIconKind.LinkVariant, "По ссылке", "Вставьте адрес подписки, который дал провайдер",
            new StackPanel { Children = { row, _clipHint, expander, _error, _addButton } });
    }

    private UIElement BuildFileCard()
    {
        var buttons = Buttons(
            Flat("Файл…", FromFile),
            Flat("QR из картинки…", () => Exec(_vm.AddServerViaImageCmd)),
            Flat("QR с экрана", ScanScreen));
        var note = new TextBlock { Text = "«QR с экрана»: FlowVeil свернётся на секунду, посмотрит экран и вернётся. Сначала откройте код в другом окне", TextWrapping = TextWrapping.Wrap, FontSize = 12, Margin = new Thickness(0, 4, 0, 0) };
        note.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
        return Card(PackIconKind.FileImportOutline, "Из файла или QR-картинки", "Файл с подпиской (txt, json, yaml) или картинка с QR-кодом",
            new StackPanel { Children = { buttons, note } });
    }

    private static Border Card(PackIconKind icon, string title, string hint, UIElement content)
    {
        var head = new Grid();
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        head.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var iconBox = new Border { Width = 36, Height = 36, CornerRadius = new CornerRadius(10), Margin = new Thickness(0, 0, 12, 0), VerticalAlignment = VerticalAlignment.Top };
        iconBox.SetResourceReference(Border.BackgroundProperty, "MaterialDesign.Brush.Background");
        var packIcon = new PackIcon { Kind = icon, Width = 20, Height = 20, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        packIcon.SetResourceReference(ForegroundProperty, "MaterialDesign.Brush.Primary");
        iconBox.Child = packIcon;
        head.Children.Add(iconBox);
        var texts = new StackPanel();
        texts.Children.Add(new TextBlock { Text = title, FontSize = 15, FontWeight = FontWeights.SemiBold });
        var hintBlock = new TextBlock { Text = hint, FontSize = 12, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 2, 0, 0) };
        hintBlock.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
        texts.Children.Add(hintBlock);
        Grid.SetColumn(texts, 1);
        head.Children.Add(texts);

        content.SetValue(MarginProperty, new Thickness(48, 10, 0, 0));
        var card = new Border { CornerRadius = new CornerRadius(18), Padding = new Thickness(16), Margin = new Thickness(0, 0, 0, 12), Child = new StackPanel { Children = { head, content } } };
        card.SetResourceReference(Border.BackgroundProperty, "MaterialDesign.Brush.Card.Background");
        return card;
    }

    private static WrapPanel Buttons(params Button[] buttons)
    {
        var panel = new WrapPanel();
        foreach (var b in buttons)
        {
            panel.Children.Add(b);
        }
        return panel;
    }

    private static void Style(FrameworkElement element, string key)
    {
        if (Application.Current?.TryFindResource(key) is Style style)
        {
            element.Style = style;
        }
    }

    internal static Button Flat(string text, Action click)
    {
        var button = new Button { Content = text, Margin = new Thickness(0, 0, 4, 0) };
        Style(button, "MaterialDesignFlatButton");
        button.Click += (_, _) => click();
        return button;
    }

    private static Button Primary(string text, Action click)
    {
        var button = new Button { Content = text, Margin = new Thickness(0, 0, 4, 0) };
        Style(button, "MaterialDesignRaisedButton");
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

    private static void Exec(System.Windows.Input.ICommand command) => command.Execute(null);

    private static string? ReadClipboard()
    {
        var text = WindowsUtils.GetClipboardData();
        return text.IsNullOrEmpty() ? null : text.Trim();
    }

    private void RefreshClipboardHint()
    {
        var text = ReadClipboard();
        var looksLikeLink = text != null && text.Length < 2000 && !text.Contains('\n')
                            && (text.StartsWith("http", StringComparison.OrdinalIgnoreCase) || text.StartsWith("flowveil:", StringComparison.OrdinalIgnoreCase));
        _clipLink = looksLikeLink ? text : null;
        _clipHint.Visibility = looksLikeLink ? Visibility.Visible : Visibility.Collapsed;
        if (looksLikeLink)
        {
            _clipText.Text = "В буфере есть ссылка: " + Shorten(text!);
        }
    }

    private static string Shorten(string link) => Utils.TryUri(link) is { } uri ? uri.Host : link[..Math.Min(30, link.Length)] + "…";

    // ---------- actions ----------

    private void SetBusy(bool busy)
    {
        _busy.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
        _addButton.IsEnabled = !busy;
    }

    private async Task AddFromFieldAsync()
    {
        _error.Text = string.Empty;
        var text = _link.Text.Trim();
        if (text.IsNullOrEmpty())
        {
            _error.Text = "Вставьте ссылку подписки";
            return;
        }
        var ok = await AddFromTextAsync(text, _name.Text.Trim().NullIfEmpty(), showInline: true);
        if (ok)
        {
            _link.Text = string.Empty;
            _name.Text = string.Empty;
        }
    }

    /// <summary>One entry point for a pasted text: a subscription address, an invite link or share links of single servers.</summary>
    private async Task<bool> AddFromTextAsync(string? text, string? name = null, bool showInline = false)
    {
        void Fail(string message)
        {
            if (showInline)
            {
                _error.Text = message;
            }
            else
            {
                NoticeManager.Instance.Enqueue(message);
            }
        }

        if (text.IsNullOrEmpty())
        {
            Fail("В буфере пусто");
            return false;
        }
        var invite = DeepLink.ParseInvite([text]);
        name ??= invite?.Name;
        text = invite?.Link ?? text.Trim();
        if (!text.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !text.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        {
            // share links of single servers (vless://, vmess://…), or a whole list
            if (text.Contains("happ://crypt", StringComparison.OrdinalIgnoreCase))
            {
                Fail("Это зашифрованная ссылка Happ, открыть её нельзя. Попросите у провайдера обычную ссылку подписки");
                return false;
            }
            SetBusy(true);
            try
            {
                await _vm.AddServerViaClipboardAsync(text);
            }
            finally
            {
                SetBusy(false);
            }
            _goToServers();
            return true;
        }

        var uri = Utils.TryUri(text);
        if (uri == null)
        {
            Fail("Это не похоже на ссылку. Пример: https://provider.example/sub/…");
            return false;
        }
        var minutes = SubAutoUpdate.Options[Math.Max(0, _interval.SelectedIndex)].Minutes;
        var ua = Formats[Math.Max(0, _format.SelectedIndex)].UserAgent;
        var result = await AddSubscriptionAsync(text, name ?? uri.Host, minutes, ua);
        if (result.Error != null)
        {
            Fail(result.Error);
            return false;
        }
        if (!result.Existing)
        {
            Finish(result.Servers, result.Name);
        }
        return true;
    }

    /// <summary>Adds one subscription and downloads its servers; the number of servers, the new subscription's id and name, or an error text.</summary>
    private async Task<(int Servers, string? Error, string? SubId, string? Name, bool Existing)> AddSubscriptionAsync(string link, string name, int minutes, string userAgent)
    {
        SetBusy(true);
        try
        {
            var existing = await ServiceLib.Helper.SQLiteHelper.Instance.TableAsync<SubItem>().ToListAsync();
            var duplicate = SubsLogic.FindDuplicate(existing.Select(e => (e.Id, e.Url ?? string.Empty)), link);
            if (duplicate != null)
            {
                // The same subscription written slightly differently: offer to open the one that is already here instead of a silent second copy.
                var known = existing.First(e => e.Id == duplicate);
                await _vm.SelectSubscriptionAsync(duplicate);
                NoticeManager.Instance.Enqueue($"Такая подписка уже есть: «{known.Remarks}». Открыл её");
                _goToServers();
                return (0, null, duplicate, known.Remarks, true);
            }
            var subItem = new SubItem { Id = string.Empty, Url = link, Remarks = name, AutoUpdateInterval = minutes, UserAgent = userAgent };
            if (await ConfigHandler.AddSubItem(AppManager.Instance.Config, subItem) != 0)
            {
                return (0, "Не получилось добавить подписку", null, null, false);
            }
            var saved = await ServiceLib.Helper.SQLiteHelper.Instance.TableAsync<SubItem>().FirstOrDefaultAsync(e => e.Url == link);
            if (saved == null)
            {
                return (0, null, null, null, false);
            }
            // The list of subscriptions on the main screen is a snapshot: refresh it, or the new one has no chip until the next start.
            await _vm.RefreshSubscriptionsAsync();
            await _vm.UpdateSubscriptionProcess(saved.Id, false);
            await _vm.SelectSubscriptionAsync(saved.Id);
            var count = await ServiceLib.Helper.SQLiteHelper.Instance.TableAsync<ProfileItem>().CountAsync(p => p.Subid == saved.Id);
            return (count, null, saved.Id, saved.Remarks, false);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(AddPageView), ex);
            return (0, "Не получилось добавить подписку", null, null, false);
        }
        finally
        {
            SetBusy(false);
        }
    }

    private void Finish(int servers, string? name = null)
    {
        NoticeManager.Instance.Enqueue(servers > 0
            ? $"Добавлено {servers} {Plural(servers, "сервер", "сервера", "серверов")}" + (name.IsNotEmpty() ? $" в «{name}»" : string.Empty)
            : "Подписка добавлена, но серверов в ней пока нет. Проверьте ссылку или обновите позже");
        _goToServers();
    }

    private static string Plural(int n, string one, string few, string many)
    {
        var m100 = n % 100;
        var m10 = n % 10;
        return m100 is >= 11 and <= 14 ? many : m10 == 1 ? one : m10 is >= 2 and <= 4 ? few : many;
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
            if (new FileInfo(dialog.FileName).Length > 4 * 1024 * 1024)
            {
                NoticeManager.Instance.Enqueue("Файл слишком большой для подписки");
                return;
            }
            var text = File.ReadAllText(dialog.FileName);
            _ = AddFromTextAsync(text);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(AddPageView), ex);
            NoticeManager.Instance.Enqueue("Не получилось прочитать файл");
        }
    }

    private void ScanScreen()
    {
        _pair.Stop();
        Exec(_vm.AddServerViaScanCmd);
    }

    private async Task RunMigrationAsync(string? dbPath)
    {
        try
        {
            if (dbPath.IsNullOrEmpty())
            {
                NoticeManager.Instance.Enqueue("v2rayN на этом компьютере не найден. Выберите файл guiNDB.db вручную");
                return;
            }
            SetBusy(true);
            var (found, added) = await SubMigration.ImportSubscriptions(AppManager.Instance.Config, dbPath!);
            if (found == 0)
            {
                NoticeManager.Instance.Enqueue("В этой базе нет ссылок на подписки");
            }
            else if (added == 0)
            {
                NoticeManager.Instance.Enqueue($"Подписок найдено: {found}, все они уже есть");
            }
            else
            {
                NoticeManager.Instance.Enqueue($"Подписок найдено: {found}, добавлено новых: {added}. Загружаю серверы…");
                await _vm.RefreshSubscriptionsAsync();
                await _vm.UpdateSubscriptionProcess(string.Empty, false);
                _goToServers();
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog("Migration", ex);
            NoticeManager.Instance.Enqueue("Не получилось прочитать файл");
        }
        finally
        {
            SetBusy(false);
        }
    }

    // ---------- Pair ----------

    /// <summary>The phone sent links. Nothing is added until the user agrees here; nothing connects by itself.</summary>
    private async void OnPairReceived(PairPayload payload, string device)
    {
        var list = new StackPanel { Width = 420, Margin = new Thickness(24) };
        list.Children.Add(new TextBlock { Text = "Добавить с телефона?", FontSize = 18, FontWeight = FontWeights.SemiBold });
        list.Children.Add(new TextBlock { Text = $"Устройство: {device}", Margin = new Thickness(0, 6, 0, 8) });
        foreach (var item in payload.Items.Take(6))
        {
            list.Children.Add(new TextBlock { Text = "• " + PairPanel.Describe(item), TextTrimming = TextTrimming.CharacterEllipsis });
        }
        if (payload.Items.Count > 6)
        {
            list.Children.Add(new TextBlock { Text = $"…и ещё {payload.Items.Count - 6}" });
        }
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
        var no = Flat("Отклонить", () => DialogHost.Close("RootDialog", false));
        var yes = new Button { Content = "Добавить", Margin = new Thickness(8, 0, 0, 0) };
        Style(yes, "MaterialDesignRaisedButton");
        yes.Click += (_, _) => DialogHost.Close("RootDialog", true);
        buttons.Children.Add(no);
        buttons.Children.Add(yes);
        list.Children.Add(buttons);

        if (await DialogHost.Show(list, "RootDialog") is not true)
        {
            NoticeManager.Instance.Enqueue("Отклонено, ничего не добавлено");
            return;
        }
        var total = 0;
        var addedAny = false;
        var singles = new List<string>();
        foreach (var item in payload.Items)
        {
            var link = item.StartsWith("flowveil:", StringComparison.OrdinalIgnoreCase) ? DeepLink.Parse([item]) ?? item : item;
            if (link.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || link.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            {
                var uri = Utils.TryUri(link);
                var result = await AddSubscriptionAsync(link, payload.Items.Count == 1 && payload.Name.IsNotEmpty() ? payload.Name! : uri?.Host ?? "Подписка", SubAutoUpdate.Get(), string.Empty);
                if (result.Error != null)
                {
                    NoticeManager.Instance.Enqueue(result.Error);
                }
                total += result.Servers;
                addedAny |= !result.Existing;
            }
            else
            {
                singles.Add(link);
            }
        }
        if (singles.Count > 0)
        {
            await _vm.AddServerViaClipboardAsync(string.Join("\n", singles));
        }
        if (addedAny || singles.Count > 0)
        {
            Finish(total);
        }
    }
}

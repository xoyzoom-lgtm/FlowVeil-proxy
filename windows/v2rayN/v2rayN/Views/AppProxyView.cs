using System.Windows.Controls;
using System.Windows.Media;
using MaterialDesignThemes.Wpf;
using Microsoft.Win32;

namespace v2rayN.Views;

/// <summary>
/// "Proxy for applications": three modes as radio choices with a plain explanation each, and the list
/// of chosen programs (icon, name, path, remove). "Add" offers the picker of running / installed
/// programs or a file dialog. Every change is saved at once; it applies at the next connection.
/// </summary>
public sealed class AppProxyView : StackPanel
{
    private readonly StackPanel _apps = new();
    private readonly TextBlock _note = new();
    private readonly Action _changed;

    public AppProxyView(Action changed)
    {
        _changed = changed;
        Width = 560;
        Margin = new Thickness(24);

        var head = new DockPanel { Margin = new Thickness(0, 0, 0, 12) };
        var close = new Button { Content = "Готово", Margin = new Thickness(12, 0, 0, 0) };
        Style(close, "MaterialDesignFlatButton");
        close.Click += (_, _) => DialogHost.Close("RootDialog");
        DockPanel.SetDock(close, Dock.Right);
        head.Children.Add(close);
        head.Children.Add(new TextBlock { Text = "Прокси для приложений", FontSize = 20, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center });
        Children.Add(head);

        Children.Add(Caption("Режим маршрутизации трафика"));
        var current = AppProxySettings.Mode;
        Children.Add(Radio(AppProxySettings.ModeOff, "Системные настройки", "Маршрутизация по приложениям выключена. Ко всем приложениям применяются общие правила.", current));
        Children.Add(Radio(AppProxySettings.ModeDirect, "Напрямую для выбранных приложений", "Трафик выбранных приложений идёт напрямую в интернет, минуя прокси. Остальные приложения идут по обычным правилам.", current));
        Children.Add(Radio(AppProxySettings.ModeProxy, "Через прокси только выбранные приложения", "Через прокси идёт только трафик выбранных приложений. Все остальные подключаются напрямую.", current));

        Children.Add(Caption("Выбранные приложения"));
        var scroll = new ScrollViewer { MaxHeight = 260, VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Content = _apps };
        Children.Add(scroll);

        var add = new Button { Content = "Добавить…", HorizontalAlignment = HorizontalAlignment.Left, Margin = new Thickness(0, 10, 0, 0) };
        Style(add, "MaterialDesignOutlinedButton");
        add.Click += (_, _) => OpenAddMenu(add);
        Children.Add(add);

        _note.TextWrapping = TextWrapping.Wrap;
        _note.Opacity = 0.7;
        _note.FontSize = 12;
        _note.Margin = new Thickness(0, 12, 0, 0);
        Children.Add(_note);

        Rebuild();
    }

    private static void Style(FrameworkElement element, string key)
    {
        if (Application.Current?.TryFindResource(key) is Style style)
        {
            element.Style = style;
        }
    }

    private static TextBlock Caption(string text) =>
        new() { Text = text, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 14, 0, 6) };

    private UIElement Radio(string mode, string title, string description, string current)
    {
        var body = new StackPanel();
        body.Children.Add(new TextBlock { Text = title, FontSize = 15 });
        body.Children.Add(new TextBlock { Text = description, FontSize = 12, Opacity = 0.65, TextWrapping = TextWrapping.Wrap, Width = 470 });
        var radio = new RadioButton { GroupName = "appproxy-mode", Content = body, IsChecked = mode == current, Margin = new Thickness(0, 4, 0, 4) };
        Style(radio, "MaterialDesignRadioButton");
        radio.Checked += (_, _) =>
        {
            AppProxySettings.SetMode(mode);
            Rebuild();
            _changed();
        };
        return radio;
    }

    private void OpenAddMenu(FrameworkElement anchor)
    {
        var menu = new ContextMenu { PlacementTarget = anchor, Placement = System.Windows.Controls.Primitives.PlacementMode.Bottom };
        var fromList = new MenuItem { Header = "Из списка программ" };
        fromList.Click += (_, _) =>
        {
            var picker = new AppPickerWindow();
            if (picker.ShowDialog() == true && picker.Chosen != null)
            {
                AddPath(picker.Chosen);
            }
        };
        var fromFile = new MenuItem { Header = "Выбрать файл…" };
        fromFile.Click += (_, _) =>
        {
            var dialog = new OpenFileDialog { Filter = "Программы (*.exe)|*.exe", Title = "Выберите исполняемый файл", Multiselect = true };
            if (dialog.ShowDialog() == true)
            {
                foreach (var file in dialog.FileNames)
                {
                    AddPath(file);
                }
            }
        };
        menu.Items.Add(fromList);
        menu.Items.Add(fromFile);
        menu.IsOpen = true;
    }

    private void AddPath(string path)
    {
        if (AppProxySettings.Add(path))
        {
            Rebuild();
            _changed();
        }
    }

    private void Rebuild()
    {
        _apps.Children.Clear();
        var apps = AppProxySettings.Apps;
        if (apps.Count == 0)
        {
            _apps.Children.Add(new TextBlock { Text = "Пока ничего не выбрано. Нажмите «Добавить…».", Opacity = 0.6, Margin = new Thickness(4, 6, 0, 6) });
        }
        foreach (var app in apps)
        {
            _apps.Children.Add(Row(app));
        }
        var mode = AppProxySettings.Mode;
        _note.Text = mode != AppProxySettings.ModeOff && apps.Count == 0
            ? "Режим не действует, пока не выбрано ни одного приложения."
            : "Изменения применяются при следующем подключении. Приложения различаются в режиме TUN; в режиме системного прокси это работает не для всех программ.";
    }

    private UIElement Row(AppProxyApp app)
    {
        var row = new DockPanel { Margin = new Thickness(0, 4, 0, 4) };
        var remove = new Button { Content = new PackIcon { Kind = PackIconKind.Close, Width = 16, Height = 16 }, ToolTip = "Убрать из списка", Width = 32, Height = 32, Padding = new Thickness(0) };
        Style(remove, "MaterialDesignFlatButton");
        remove.Click += (_, _) =>
        {
            AppProxySettings.Remove(app.Path);
            Rebuild();
            _changed();
        };
        DockPanel.SetDock(remove, Dock.Right);
        row.Children.Add(remove);

        var image = new System.Windows.Controls.Image { Width = 32, Height = 32, Margin = new Thickness(0, 0, 12, 0), Source = IconOf(app.Path) };
        DockPanel.SetDock(image, Dock.Left);
        row.Children.Add(image);

        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        text.Children.Add(new TextBlock { Text = app.Name, FontSize = 15 });
        text.Children.Add(new TextBlock { Text = app.Path, FontSize = 11, Opacity = 0.6, TextTrimming = TextTrimming.CharacterEllipsis });
        row.Children.Add(text);
        return row;
    }

    private static ImageSource? IconOf(string path)
    {
        try
        {
            using var icon = System.Drawing.Icon.ExtractAssociatedIcon(path);
            if (icon != null)
            {
                var source = System.Windows.Interop.Imaging.CreateBitmapSourceFromHIcon(icon.Handle, Int32Rect.Empty, System.Windows.Media.Imaging.BitmapSizeOptions.FromEmptyOptions());
                source.Freeze();
                return source;
            }
        }
        catch
        {
            // the file may be gone
        }
        return null;
    }
}

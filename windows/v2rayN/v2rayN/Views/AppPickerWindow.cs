using System.Collections.ObjectModel;
using System.Drawing;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace v2rayN.Views;

/// <summary>One program in the picker.</summary>
public sealed class PickerApp
{
    public string Name { get; init; } = string.Empty;
    public string Path { get; init; } = string.Empty;
    public string Group { get; init; } = string.Empty;
    public ImageSource? Icon { get; set; }
}

/// <summary>
/// Choose a program: open windows first, then other running processes, then what the Start menu
/// lists (installed programs). A search box filters by name or path; one click picks.
/// </summary>
public sealed class AppPickerWindow : Window
{
    private static readonly Dictionary<string, ImageSource?> IconCache = new(StringComparer.OrdinalIgnoreCase);

    private readonly List<PickerApp> _all = [];
    private readonly ListBox _list = new();
    private readonly TextBox _search = new();
    private readonly TextBlock _status = new();

    public string? Chosen { get; private set; }

    public AppPickerWindow()
    {
        Title = "Выберите приложение";
        Width = 600;
        Height = 680;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        Owner = Application.Current?.MainWindow;
        SetResourceReference(BackgroundProperty, "MaterialDesign.Brush.Background");

        var root = new DockPanel { Margin = new Thickness(16) };

        if (Application.Current?.TryFindResource("MaterialDesignOutlinedTextBox") is Style tbStyle)
        {
            _search.Style = tbStyle;
        }
        MaterialDesignThemes.Wpf.HintAssist.SetHint(_search, "Поиск по имени или пути…");
        _search.Margin = new Thickness(0, 0, 0, 10);
        _search.TextChanged += (_, _) => Refresh();
        DockPanel.SetDock(_search, Dock.Top);
        root.Children.Add(_search);

        _status.Text = "Загружаю список…";
        _status.Margin = new Thickness(2, 0, 0, 8);
        DockPanel.SetDock(_status, Dock.Bottom);

        var cancel = new Button { Content = "Отмена", HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 10, 0, 0) };
        if (Application.Current?.TryFindResource("MaterialDesignOutlinedButton") is Style btnStyle)
        {
            cancel.Style = btnStyle;
        }
        cancel.Click += (_, _) => Close();
        DockPanel.SetDock(cancel, Dock.Bottom);
        root.Children.Add(cancel);
        root.Children.Add(_status);

        _list.ItemTemplate = BuildTemplate();
        _list.HorizontalContentAlignment = HorizontalAlignment.Stretch;
        _list.SelectionChanged += (_, _) =>
        {
            if (_list.SelectedItem is PickerApp app)
            {
                Chosen = app.Path;
                DialogResult = true;
            }
        };
        root.Children.Add(_list);
        Content = root;

        Loaded += async (_, _) =>
        {
            _search.Focus();
            var items = await Task.Run(Collect);
            _all.AddRange(items);
            Refresh();
            _ = LoadIconsAsync();
        };
    }

    private static DataTemplate BuildTemplate()
    {
        var factory = new FrameworkElementFactory(typeof(DockPanel));
        factory.SetValue(FrameworkElement.MarginProperty, new Thickness(2, 5, 2, 5));
        var image = new FrameworkElementFactory(typeof(System.Windows.Controls.Image));
        image.SetValue(FrameworkElement.WidthProperty, 32.0);
        image.SetValue(FrameworkElement.HeightProperty, 32.0);
        image.SetValue(FrameworkElement.MarginProperty, new Thickness(0, 0, 12, 0));
        image.SetBinding(System.Windows.Controls.Image.SourceProperty, new System.Windows.Data.Binding(nameof(PickerApp.Icon)));
        factory.AppendChild(image);
        var stack = new FrameworkElementFactory(typeof(StackPanel));
        var name = new FrameworkElementFactory(typeof(TextBlock));
        name.SetBinding(TextBlock.TextProperty, new System.Windows.Data.Binding(nameof(PickerApp.Name)));
        name.SetValue(TextBlock.FontSizeProperty, 15.0);
        var path = new FrameworkElementFactory(typeof(TextBlock));
        path.SetBinding(TextBlock.TextProperty, new System.Windows.Data.Binding(nameof(PickerApp.Path)));
        path.SetValue(TextBlock.FontSizeProperty, 11.0);
        path.SetValue(TextBlock.OpacityProperty, 0.6);
        path.SetValue(TextBlock.TextTrimmingProperty, TextTrimming.CharacterEllipsis);
        stack.AppendChild(name);
        stack.AppendChild(path);
        factory.AppendChild(stack);
        return new DataTemplate { VisualTree = factory };
    }

    private void Refresh()
    {
        var q = _search.Text.Trim();
        var shown = _all
            .Where(a => q.Length == 0 || a.Name.Contains(q, StringComparison.OrdinalIgnoreCase) || a.Path.Contains(q, StringComparison.OrdinalIgnoreCase))
            .Take(400)
            .ToList();
        _list.ItemsSource = shown;
        var view = System.Windows.Data.CollectionViewSource.GetDefaultView(_list.ItemsSource);
        view.GroupDescriptions.Clear();
        view.GroupDescriptions.Add(new System.Windows.Data.PropertyGroupDescription(nameof(PickerApp.Group)));
        _list.GroupStyle.Clear();
        _list.GroupStyle.Add(new GroupStyle
        {
            HeaderTemplate = GroupHeader(),
        });
        _status.Text = shown.Count == 0 ? "Ничего не найдено. Можно выбрать файл вручную." : $"Найдено: {shown.Count}";
    }

    private static DataTemplate GroupHeader()
    {
        var text = new FrameworkElementFactory(typeof(TextBlock));
        text.SetBinding(TextBlock.TextProperty, new System.Windows.Data.Binding("Name"));
        text.SetValue(TextBlock.FontWeightProperty, FontWeights.SemiBold);
        text.SetValue(TextBlock.MarginProperty, new Thickness(2, 12, 0, 4));
        return new DataTemplate { VisualTree = text };
    }

    private async Task LoadIconsAsync()
    {
        foreach (var app in _all.ToList())
        {
            app.Icon = await Task.Run(() => IconOf(app.Path));
        }
        Refresh();
    }

    private static ImageSource? IconOf(string path)
    {
        lock (IconCache)
        {
            if (IconCache.TryGetValue(path, out var cached))
            {
                return cached;
            }
        }
        ImageSource? result = null;
        try
        {
            using var icon = System.Drawing.Icon.ExtractAssociatedIcon(path);
            if (icon != null)
            {
                var source = System.Windows.Interop.Imaging.CreateBitmapSourceFromHIcon(icon.Handle, Int32Rect.Empty, BitmapSizeOptions.FromEmptyOptions());
                source.Freeze();
                result = source;
            }
        }
        catch
        {
            // no icon for this file
        }
        lock (IconCache)
        {
            IconCache[path] = result;
        }
        return result;
    }

    private static List<PickerApp> Collect()
    {
        var result = new List<PickerApp>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var own = Environment.ProcessPath;

        Process[] processes;
        try
        {
            processes = Process.GetProcesses();
        }
        catch
        {
            processes = [];
        }
        var withWindow = new List<PickerApp>();
        var others = new List<PickerApp>();
        foreach (var p in processes)
        {
            string? path = null;
            try
            {
                path = p.MainModule?.FileName;
            }
            catch
            {
                // no access to a system or elevated process: skip it
            }
            if (path.IsNullOrEmpty() || string.Equals(path, own, StringComparison.OrdinalIgnoreCase) || !seen.Add(path!))
            {
                continue;
            }
            var hasWindow = false;
            try
            {
                hasWindow = !p.MainWindowTitle.IsNullOrEmpty();
            }
            catch
            {
                // ignore
            }
            var item = new PickerApp { Name = System.IO.Path.GetFileName(path), Path = path!, Group = hasWindow ? "Открытые окна" : "Запущенные процессы" };
            (hasWindow ? withWindow : others).Add(item);
        }
        result.AddRange(withWindow.OrderBy(a => a.Name, StringComparer.OrdinalIgnoreCase));
        result.AddRange(others.OrderBy(a => a.Name, StringComparer.OrdinalIgnoreCase));

        // Installed programs, as the Start menu lists them.
        try
        {
            var type = Type.GetTypeFromProgID("WScript.Shell");
            if (type != null)
            {
                dynamic shell = Activator.CreateInstance(type)!;
                var roots = new[]
                {
                    Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu),
                    Environment.GetFolderPath(Environment.SpecialFolder.StartMenu),
                };
                var installed = new List<PickerApp>();
                foreach (var root in roots.Where(Directory.Exists))
                {
                    foreach (var lnk in Directory.EnumerateFiles(Path.Combine(root, "Programs"), "*.lnk", SearchOption.AllDirectories).Take(1500))
                    {
                        try
                        {
                            string target = shell.CreateShortcut(lnk).TargetPath;
                            if (!target.IsNullOrEmpty() && target.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) && File.Exists(target) && seen.Add(target))
                            {
                                installed.Add(new PickerApp { Name = Path.GetFileNameWithoutExtension(lnk), Path = target, Group = "Установленные программы" });
                            }
                        }
                        catch
                        {
                            // a broken shortcut
                        }
                    }
                }
                result.AddRange(installed.OrderBy(a => a.Name, StringComparer.OrdinalIgnoreCase));
            }
        }
        catch
        {
            // the Start menu list is a convenience only
        }
        return result;
    }
}

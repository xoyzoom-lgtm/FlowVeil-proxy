using MaterialDesignColors;
using MaterialDesignColors.ColorManipulation;
using MaterialDesignThemes.Wpf;
using Microsoft.Win32;

namespace v2rayN.ViewModels;

public partial class ThemeSettingViewModel : MyReactiveObject
{
    private readonly PaletteHelper _paletteHelper = new();

    public BulkObservableCollection<Swatch> Swatches { get; } = [];

    [Reactive]
    public partial Swatch SelectedSwatch { get; set; }

    [Reactive] public partial string CurrentTheme { get; set; }

    [Reactive] public partial int CurrentFontSize { get; set; }

    [Reactive] public partial string CurrentLanguage { get; set; }

    public ThemeSettingViewModel()
    {
        _config = AppManager.Instance.Config;

        RegisterSystemColorSet(_config, ModifyTheme);

        BindingUI();
        RestoreUI();
    }

    private void RestoreUI()
    {
        ModifyTheme();
        ModifyFontSize();
        if (HappThemes.Find(CurrentTheme) == null && !_config.UiItem.ColorPrimaryName.IsNullOrEmpty())
        {
            var swatch = new SwatchesProvider().Swatches.FirstOrDefault(t => t.Name == _config.UiItem.ColorPrimaryName);
            if (swatch?.ExemplarHue?.Color is not null)
            {
                ChangePrimaryColor(swatch.ExemplarHue.Color);
            }
        }
    }

    private void BindingUI()
    {
        Swatches.AddRange(new SwatchesProvider().Swatches);
        if (!_config.UiItem.ColorPrimaryName.IsNullOrEmpty())
        {
            SelectedSwatch = Swatches.FirstOrDefault(t => t.Name == _config.UiItem.ColorPrimaryName);
        }
        CurrentTheme = _config.UiItem.CurrentTheme;
        CurrentFontSize = _config.UiItem.CurrentFontSize;
        CurrentLanguage = _config.UiItem.CurrentLanguage;

        this.WhenAnyValue(x => x.CurrentTheme)
            .Where(y => y != null && !y.IsNullOrEmpty())
            .SubscribeAsync(async _ =>
             {
                 if (_config.UiItem.CurrentTheme != CurrentTheme)
                 {
                     _config.UiItem.CurrentTheme = CurrentTheme;
                     ModifyTheme();
                     await ConfigHandler.SaveConfig(_config);
                 }
             });

        this.WhenAnyValue(x => x.SelectedSwatch)
             .Where(y => y != null && !y.Name.IsNullOrEmpty())
             .SubscribeAsync(async _ =>
             {
                 if (SelectedSwatch == null
                 || SelectedSwatch.Name.IsNullOrEmpty()
                 || SelectedSwatch.ExemplarHue == null
                 || SelectedSwatch.ExemplarHue?.Color == null)
                 {
                     return;
                 }
                 if (_config.UiItem.ColorPrimaryName != SelectedSwatch?.Name)
                 {
                     _config.UiItem.ColorPrimaryName = SelectedSwatch?.Name;
                     ChangePrimaryColor(SelectedSwatch.ExemplarHue.Color);
                     await ConfigHandler.SaveConfig(_config);
                 }
             });

        this.WhenAnyValue(x => x.CurrentFontSize)
              .Where(y => y > 0)
              .SubscribeAsync(async _ =>
              {
                  if (_config.UiItem.CurrentFontSize != CurrentFontSize)
                  {
                      _config.UiItem.CurrentFontSize = CurrentFontSize;
                      ModifyFontSize();
                      await ConfigHandler.SaveConfig(_config);
                  }
              });

        this.WhenAnyValue(x => x.CurrentLanguage)
            .Where(y => y != null && !y.IsNullOrEmpty())
            .SubscribeAsync(async _ =>
            {
                if (CurrentLanguage.IsNotEmpty() && _config.UiItem.CurrentLanguage != CurrentLanguage)
                {
                    _config.UiItem.CurrentLanguage = CurrentLanguage;
                    Thread.CurrentThread.CurrentUICulture = new(CurrentLanguage);
                    await ConfigHandler.SaveConfig(_config);
                    NoticeManager.Instance.Enqueue(ResUI.NeedRebootTips);
                }
            });
    }

    private static readonly string[] HappBrushKeys =
    [
        "MaterialDesign.Brush.Background",
        "MaterialDesign.Brush.Card.Background",
        "MaterialDesign.Brush.Foreground",
        "MaterialDesign.Brush.ForegroundLight",
        "MaterialDesignPaper",
        "MaterialDesignCardBackground",
        "MaterialDesignBody",
        "MaterialDesignBodyLight",
    ];

    public void ModifyTheme()
    {
        var happ = HappThemes.Find(CurrentTheme);
        var baseTheme = happ != null
            ? (happ.IsLight ? BaseTheme.Light : BaseTheme.Dark)
            : CurrentTheme switch
            {
                nameof(ETheme.Dark) => BaseTheme.Dark,
                nameof(ETheme.Light) => BaseTheme.Light,
                _ => BaseTheme.Inherit,
            };

        var theme = _paletteHelper.GetTheme();
        theme.SetBaseTheme(baseTheme);
        if (happ != null)
        {
            var accent = ToColor(happ.Accent);
            var secondary = ToColor(happ.Secondary);
            var onAccent = ToColor(happ.OnAccent);
            theme.PrimaryLight = new ColorPair(accent.Lighten(), onAccent);
            theme.PrimaryMid = new ColorPair(accent, onAccent);
            theme.PrimaryDark = new ColorPair(accent.Darken(), onAccent);
            theme.SecondaryLight = new ColorPair(secondary.Lighten());
            theme.SecondaryMid = new ColorPair(secondary);
            theme.SecondaryDark = new ColorPair(secondary.Darken());
        }
        _paletteHelper.SetTheme(theme);

        ApplyHappBrushes(happ);
        WindowsUtils.SetDarkBorder(Application.Current.MainWindow, CurrentTheme);
    }

    private static void ApplyHappBrushes(HappTheme? happ)
    {
        var resources = Application.Current.Resources;
        foreach (var key in HappBrushKeys)
        {
            resources.Remove(key);
        }
        if (happ == null)
        {
            return;
        }

        var background = MakeBrush(happ.Background);
        var card = MakeBrush(happ.Card);
        var text = MakeBrush(happ.Text);
        var subText = MakeBrush(happ.SubText);
        resources["MaterialDesign.Brush.Background"] = background;
        resources["MaterialDesignPaper"] = background;
        resources["MaterialDesign.Brush.Card.Background"] = card;
        resources["MaterialDesignCardBackground"] = card;
        resources["MaterialDesign.Brush.Foreground"] = text;
        resources["MaterialDesignBody"] = text;
        resources["MaterialDesign.Brush.ForegroundLight"] = subText;
        resources["MaterialDesignBodyLight"] = subText;
    }

    private static System.Windows.Media.Color ToColor(string hex)
    {
        var c = HappTheme.Parse(hex) ?? ((byte)0, (byte)0, (byte)0, (byte)255);
        // Window surfaces must be opaque; translucent Happ colours are flattened.
        return System.Windows.Media.Color.FromRgb(c.R, c.G, c.B);
    }

    private static System.Windows.Media.SolidColorBrush MakeBrush(string hex)
    {
        var brush = new System.Windows.Media.SolidColorBrush(ToColor(hex));
        brush.Freeze();
        return brush;
    }

    private void ModifyFontSize()
    {
        double size = CurrentFontSize;
        if (size < Global.MinFontSize)
        {
            return;
        }

        Application.Current.Resources["StdFontSize"] = size;
        Application.Current.Resources["StdFontSize1"] = size + 1;
        Application.Current.Resources["StdFontSize-1"] = size - 1;
    }

    public void ChangePrimaryColor(System.Windows.Media.Color color)
    {
        var theme = _paletteHelper.GetTheme();

        theme.PrimaryLight = new ColorPair(color.Lighten());
        theme.PrimaryMid = new ColorPair(color);
        theme.PrimaryDark = new ColorPair(color.Darken());

        _paletteHelper.SetTheme(theme);
    }

    public static void RegisterSystemColorSet(Config config, Action updateFunc)
    {
        SystemEvents.UserPreferenceChanged += (s, e) =>
        {
            if ((e.Category == UserPreferenceCategory.Color || e.Category == UserPreferenceCategory.General)
                && config.UiItem.CurrentTheme == nameof(ETheme.FollowSystem))
            {
                updateFunc?.Invoke();
            }
        };
    }
}

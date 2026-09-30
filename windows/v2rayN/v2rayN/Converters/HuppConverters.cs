using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace v2rayN.Converters;

/// <summary>Visible when the bound string is not empty.</summary>
public sealed class NotEmptyToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        value is string s && s.Length > 0 ? Visibility.Visible : Visibility.Collapsed;

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => null;
}

/// <summary>Server name without the flag emoji.</summary>
public sealed class ProfileNameConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        HuppProfileText.SplitFlag(value as string).Name;

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => null;
}

/// <summary>Two-letter country code taken from the flag emoji in the remarks.</summary>
public sealed class ProfileCodeConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        HuppProfileText.SplitFlag(value as string).Code;

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => null;
}

/// <summary>"VLESS / TCP / REALITY" for a <see cref="ProfileItemModel"/>.</summary>
public sealed class ProfileDescriptionConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        HuppProfileText.Describe(value as ProfileItemModel);

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => null;
}

/// <summary>
/// Flag picture for a country code. Windows does not draw flag emoji, so a small PNG is fetched
/// once per code; while it loads (or offline) the code text underneath stays visible.
/// </summary>
public sealed class FlagImageConverter : IValueConverter
{
    private static readonly Dictionary<string, BitmapImage> _cache = new(StringComparer.OrdinalIgnoreCase);

    public object? Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        if (value is not string code || code.Length != 2)
        {
            return null;
        }
        if (_cache.TryGetValue(code, out var cached))
        {
            return cached;
        }
        try
        {
            // Flags shipped next to the app (added by the build) work offline and right after start;
            // the web copy is only a fallback, and a failed download is retried next time.
            var local = Path.Combine(Utils.StartupPath(), "flags", code.ToLowerInvariant() + ".png");
            var fromDisk = File.Exists(local);
            var image = new BitmapImage();
            image.BeginInit();
            image.UriSource = fromDisk ? new Uri(local, UriKind.Absolute) : new Uri($"https://flagcdn.com/w80/{code.ToLowerInvariant()}.png");
            image.CacheOption = BitmapCacheOption.OnLoad;
            image.CreateOptions = BitmapCreateOptions.IgnoreColorProfile;
            image.EndInit();
            if (!fromDisk)
            {
                image.DownloadFailed += (_, _) => _cache.Remove(code);
            }
            if (image.CanFreeze && fromDisk)
            {
                image.Freeze();
            }
            _cache[code] = image;
            return image;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(FlagImageConverter), ex);
            return null;
        }
    }

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => null;
}

/// <summary>Ping pill text from DelayVal: "123 мс", "нет" for failures, empty when untested.</summary>
public sealed class DelayTextConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var text = value as string;
        if (text.IsNullOrEmpty())
        {
            return string.Empty;
        }
        return int.TryParse(text, out var delay) && delay > 0 ? $"{delay} мс" : "—";
    }

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => null;
}

/// <summary>Green / orange / red colour for the ping pill, matching the phone app.</summary>
public sealed class DelayBrushConverter : IValueConverter
{
    private static readonly SolidColorBrush Good = Freeze(Color.FromRgb(0x22, 0xC5, 0x5E));
    private static readonly SolidColorBrush Medium = Freeze(Color.FromRgb(0xF5, 0x9E, 0x0B));
    private static readonly SolidColorBrush Bad = Freeze(Color.FromRgb(0xEF, 0x44, 0x44));
    private static readonly SolidColorBrush Muted = Freeze(Color.FromRgb(0x9C, 0xA3, 0xAF));

    /// <summary>Below 100 ms good, up to 250 medium, above that bad; no answer is drawn muted (a dead server is not an alarm).</summary>
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var delay = int.TryParse(value as string, out var d) ? d : 0;
        return delay switch
        {
            <= 0 => Muted,
            < 100 => Good,
            < 250 => Medium,
            _ => Bad,
        };
    }

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => null;

    private static SolidColorBrush Freeze(Color color)
    {
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        return brush;
    }
}

/// <summary>The opposite of the usual bool → visibility.</summary>
public sealed class InverseBoolToVisibilityConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        value is true ? Visibility.Collapsed : Visibility.Visible;

    public object? ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => null;
}

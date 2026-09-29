using System.Windows.Media;

namespace v2rayN.Converters;

public class MaterialDesignFonts
{
    public static FontFamily MyFont { get; }

    static MaterialDesignFonts()
    {
        try
        {
            var fontFamily = AppManager.Instance.Config.UiItem.CurrentFontFamily;
            if (fontFamily.IsNotEmpty())
            {
                var fontPath = Utils.GetFontsPath();
                MyFont = new FontFamily(new Uri(@$"file:///{fontPath}\"), $"./#{fontFamily}");
            }
        }
        catch
        {
        }
        // Segoe UI renders Cyrillic properly; Segoe UI Emoji keeps provider emoji from turning into boxes.
        MyFont ??= new FontFamily("Segoe UI Variable Text, Segoe UI, Segoe UI Emoji, Segoe UI Symbol, Microsoft YaHei");
    }
}

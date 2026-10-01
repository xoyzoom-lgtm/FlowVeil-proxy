using System.Windows.Controls;
using MaterialDesignThemes.Wpf;

namespace v2rayN.Views;

/// <summary>Dialog with the ready rule profiles; a click switches the profile on and closes it.</summary>
public sealed class ProfilePickerView : StackPanel
{
    public ProfilePickerView(string? activeId, Action<RuleProfile> chosen)
    {
        Width = 460;
        Margin = new Thickness(24);
        Children.Add(new TextBlock { Text = "Профиль правил", FontSize = 20, FontWeight = FontWeights.SemiBold });
        var hint = new TextBlock { Text = "Что идёт через сервер, а что напрямую. Переключается сразу; подключение перезапустится.", TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 4, 0, 12) };
        hint.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
        Children.Add(hint);

        foreach (var profile in RuleProfiles.All)
        {
            var on = profile.Id == activeId;
            var texts = new StackPanel { Margin = new Thickness(0, 0, 8, 0) };
            texts.Children.Add(new TextBlock { Text = profile.Title, FontWeight = FontWeights.SemiBold, FontSize = 15 });
            var h = new TextBlock { Text = profile.Hint, TextWrapping = TextWrapping.Wrap, FontSize = 12, Margin = new Thickness(0, 2, 0, 0) };
            h.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
            texts.Children.Add(h);
            var row = new DockPanel { LastChildFill = true };
            var check = new PackIcon { Kind = on ? PackIconKind.RadioboxMarked : PackIconKind.RadioboxBlank, Width = 22, Height = 22, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 12, 0) };
            check.SetResourceReference(Control.ForegroundProperty, on ? "MaterialDesign.Brush.Primary" : "MaterialDesign.Brush.ForegroundLight");
            DockPanel.SetDock(check, Dock.Left);
            row.Children.Add(check);
            row.Children.Add(texts);
            var card = new Button { Content = row, HorizontalContentAlignment = HorizontalAlignment.Stretch, Height = double.NaN, Padding = new Thickness(12), Margin = new Thickness(0, 0, 0, 8) };
            if (Application.Current?.TryFindResource("MaterialDesignOutlinedButton") is Style style)
            {
                card.Style = style;
            }
            var chosenProfile = profile;
            card.Click += (_, _) =>
            {
                DialogHost.Close("RootDialog");
                chosen(chosenProfile);
            };
            Children.Add(card);
        }
    }
}

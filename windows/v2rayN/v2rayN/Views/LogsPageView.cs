using System.Windows.Controls;
using ServiceLib.ViewModels;

namespace v2rayN.Views;

/// <summary>"Логи": the same message view as the classic layout (filter, pause, copy, clear, autoscroll) on its own page, plus a button for the folder with log files.</summary>
public sealed class LogsPageView : DockPanel
{
    public LogsPageView()
    {
        var head = new DockPanel { Margin = new Thickness(24, 20, 24, 0) };
        DockPanel.SetDock(head, Dock.Top);
        var open = AddPageView.Flat("Открыть папку логов", () => ProcUtils.ProcessStart(Utils.GetLogPath()));
        DockPanel.SetDock(open, Dock.Right);
        head.Children.Add(open);
        head.Children.Add(new TextBlock { Text = "Логи", FontSize = 24, FontWeight = FontWeights.Bold, VerticalAlignment = VerticalAlignment.Center });
        Children.Add(head);
        var hint = new TextBlock { Text = "Ссылки серверов, адреса подписок и идентификаторы скрыты. «Автообновление» = пауза.", FontSize = 12, Margin = new Thickness(24, 2, 24, 4) };
        hint.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
        DockPanel.SetDock(hint, Dock.Top);
        Children.Add(hint);

        var host = new ContentControl { Margin = new Thickness(16, 0, 16, 8) };
        v2rayN.Base.ViewHost.Show(host, new MsgViewModel());
        Children.Add(host);
    }
}

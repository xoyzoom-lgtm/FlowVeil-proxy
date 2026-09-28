using System.Windows.Controls;
using MaterialDesignThemes.Wpf;

namespace v2rayN.Views;

/// <summary>In-app updater: checks the Hupp releases, downloads Hupp-Setup.exe and runs it.</summary>
public partial class HuppUpdateView : UserControl
{
    private HuppUpdateInfo? _info;

    public HuppUpdateView()
    {
        InitializeComponent();
        btnClose.Click += (_, _) => DialogHost.Close("RootDialog");
        btnInstall.Click += async (_, _) => await InstallAsync();
        Loaded += async (_, _) => await CheckAsync();
    }

    private async Task CheckAsync()
    {
        btnInstall.Visibility = Visibility.Collapsed;
        progress.IsIndeterminate = true;
        txtStatus.Text = "Проверяю обновления…";
        txtVersion.Text = $"Сейчас установлена сборка {HuppUpdater.CurrentBuild()}";

        _info = await HuppUpdater.CheckAsync();
        progress.IsIndeterminate = false;
        progress.Value = 0;
        if (_info == null)
        {
            txtStatus.Text = "Не удалось проверить обновления. Проверьте интернет и попробуйте ещё раз.";
        }
        else if (_info.HasUpdate)
        {
            txtStatus.Text = $"Есть новая версия ({_info.Tag}). Нажмите кнопку — Hupp скачает её и обновится сам, настройки сохранятся.";
            btnInstall.Visibility = Visibility.Visible;
        }
        else
        {
            txtStatus.Text = "У вас последняя версия Hupp.";
        }
    }

    private async Task InstallAsync()
    {
        if (_info?.SetupUrl == null)
        {
            return;
        }
        btnInstall.IsEnabled = false;
        btnClose.IsEnabled = false;
        txtStatus.Text = "Скачиваю обновление…";
        var report = new Progress<int>(p => progress.Value = p);
        var path = await HuppUpdater.DownloadAsync(_info.SetupUrl, report);
        if (path == null)
        {
            txtStatus.Text = "Не удалось скачать обновление. Попробуйте ещё раз.";
            btnInstall.IsEnabled = true;
            btnClose.IsEnabled = true;
            return;
        }

        txtStatus.Text = "Устанавливаю… Hupp перезапустится сам.";
        try
        {
            // The installer closes this instance, updates files in place and starts Hupp again.
            Process.Start(new ProcessStartInfo(path, "/SILENT /NORESTART") { UseShellExecute = true });
            await AppManager.Instance.AppExitAsync(true);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(HuppUpdateView), ex);
            txtStatus.Text = "Не удалось запустить установщик. Файл лежит здесь: " + path;
            btnClose.IsEnabled = true;
        }
    }
}

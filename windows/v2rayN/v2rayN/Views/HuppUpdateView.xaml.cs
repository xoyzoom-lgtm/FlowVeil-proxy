using System.Windows.Controls;
using MaterialDesignThemes.Wpf;

namespace v2rayN.Views;

/// <summary>In-app updater: checks the FlowVeil releases, downloads FlowVeil-Setup.exe and runs it.</summary>
public partial class HuppUpdateView : UserControl
{
    private HuppUpdateInfo? _info;

    public HuppUpdateView()
    {
        InitializeComponent();
        // "Later" (the close button) puts the reminder off for 3 days when an update was on offer.
        btnClose.Click += (_, _) =>
        {
            if (_info is { HasUpdate: true })
            {
                UpdateNotifier.Later();
            }
            DialogHost.Close("RootDialog");
        };
        btnSkip.Click += (_, _) =>
        {
            if (_info is { HasUpdate: true })
            {
                UpdateNotifier.Skip(_info.Build);
            }
            DialogHost.Close("RootDialog");
        };
        btnInstall.Click += async (_, _) => await InstallAsync();
        Loaded += async (_, _) => await CheckAsync();
    }

    private async Task CheckAsync()
    {
        btnInstall.Visibility = Visibility.Collapsed;
        btnSkip.Visibility = Visibility.Collapsed;
        notesScroll.Visibility = Visibility.Collapsed;
        btnClose.Content = "Закрыть";
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
            txtNotes.Text = _info.Notes;
            notesScroll.Visibility = _info.Notes.IsNullOrEmpty() ? Visibility.Collapsed : Visibility.Visible;
            btnSkip.Visibility = Visibility.Visible;
            if (_info.Portable)
            {
                // The portable copy has no installer to update it in place: the release page has the new archive.
                txtStatus.Text = $"Есть новая версия ({_info.Tag}). У вас портативная версия: скачайте архив на странице релиза и распакуйте поверх.";
                btnInstall.Content = "Открыть страницу релиза";
            }
            else
            {
                txtStatus.Text = $"Есть новая версия ({_info.Tag}). Нажмите кнопку — FlowVeil скачает её и обновится сам, настройки сохранятся.";
                btnInstall.Content = "Скачать и установить";
            }
            btnInstall.Visibility = Visibility.Visible;
            btnClose.Content = "Позже";
        }
        else
        {
            txtStatus.Text = "У вас последняя версия FlowVeil.";
        }
    }

    private async Task InstallAsync()
    {
        if (_info?.SetupUrl == null)
        {
            return;
        }
        if (_info.Portable)
        {
            ProcUtils.ProcessStart(_info.ReleaseUrl ?? "https://github.com/xoyzoom-lgtm/FlowVeil-proxy/releases");
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

        // false = the file differs from the published sum: never run it. null = no sums published: do not block.
        if (await HuppUpdater.VerifyAsync(path, _info.AssetName, _info.SumsUrl) == false)
        {
            try
            {
                File.Delete(path);
            }
            catch
            {
                // a leftover temp file is harmless
            }
            txtStatus.Text = "Файл обновления повреждён или изменён, установка отменена. Попробуйте ещё раз позже.";
            btnInstall.IsEnabled = true;
            btnClose.IsEnabled = true;
            return;
        }

        var check = await HuppUpdater.CheckManifestAsync(path, _info);
        if (check is UpdateManifest.Result.Rejected rejected)
        {
            Logging.SaveLog($"Update refused: {rejected.Reason}");
            TryDelete(path);
            txtStatus.Text = rejected.Reason switch
            {
                UpdateManifest.Reason.BadSignature => "Подпись обновления не сходится. Файл не запущен — скачайте его со страницы релиза.",
                UpdateManifest.Reason.Rollback => "Это не более новая версия. Установка отменена.",
                UpdateManifest.Reason.Unsigned => "Обновление не подписано. Установка отменена.",
                _ => "Файл обновления не совпадает с описанием релиза, установка отменена. Скачайте его со страницы релиза.",
            };
            btnInstall.IsEnabled = true;
            btnClose.IsEnabled = true;
            return;
        }
        if (check is not UpdateManifest.Result.Verified { Signed: true })
        {
            var answer = MessageBox.Show(
                "У этого релиза пока нет подписи FlowVeil, поэтому программа не может подтвердить, что файл настоящий. Установить всё равно?",
                "Обновление не подписано", MessageBoxButton.YesNo, MessageBoxImage.Warning, MessageBoxResult.No);
            if (answer != MessageBoxResult.Yes)
            {
                TryDelete(path);
                txtStatus.Text = "Установка отменена.";
                btnInstall.IsEnabled = true;
                btnClose.IsEnabled = true;
                return;
            }
        }
        // The file must still be the one that was checked.
        var expected = (check as UpdateManifest.Result.Verified)?.Entry.Sha256;
        if (expected != null && !string.Equals(await HuppUpdater.Sha256Async(path), expected, StringComparison.OrdinalIgnoreCase))
        {
            TryDelete(path);
            txtStatus.Text = "Файл обновления изменился после проверки, установка отменена.";
            btnInstall.IsEnabled = true;
            btnClose.IsEnabled = true;
            return;
        }

        txtStatus.Text = "Устанавливаю… FlowVeil перезапустится сам.";
        try
        {
            // The installer closes this instance, updates files in place and starts FlowVeil again.
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

    private static void TryDelete(string path)
    {
        try
        {
            File.Delete(path);
        }
        catch
        {
            // a leftover file in the updates folder is harmless
        }
    }
}

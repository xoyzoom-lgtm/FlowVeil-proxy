using System.Windows.Controls;
using System.Windows.Media;
using MaterialDesignThemes.Wpf;

namespace v2rayN.Views;

/// <summary>Russian texts for the diagnosis: one sentence about what is wrong and one about what to do.</summary>
public static class DiagnosisTexts
{
    public static (string Title, string Fix) Of(DiagCause cause) => cause switch
    {
        DiagCause.Ok => ("Всё работает", "Интернет, подписка и сервер в порядке. Если какой-то сайт не открывается, попробуйте другой сервер."),
        DiagCause.NoNetwork => ("Нет интернета", "Проверьте Wi-Fi или кабель и запустите проверку снова."),
        DiagCause.CaptivePortal => ("Сеть ждёт входа", "Эта сеть просит войти через страницу (кафе, отель, вокзал). Откройте браузер, войдите и повторите."),
        DiagCause.WrongTime => ("Неверное время на компьютере", "Из-за этого не устанавливаются защищённые соединения. Включите автоматическую установку даты и времени."),
        DiagCause.DnsFailed => ("Не находятся адреса сайтов (DNS)", "Смените сеть или перезапустите роутер. Если сеть та же, попробуйте другой DNS в настройках адаптера."),
        DiagCause.SubExpired => ("Подписка закончилась", "Продлите подписку у провайдера и обновите её здесь."),
        DiagCause.SubTrafficOver => ("Трафик закончился", "Провайдер ограничил трафик. Дождитесь нового периода или добавьте трафик у провайдера."),
        DiagCause.SubDeviceLimit => ("Достигнут лимит устройств у провайдера", "К подписке подключено слишком много устройств. Отключите лишнее в личном кабинете провайдера или напишите в его поддержку."),
        DiagCause.SubBlocked => ("Подписка отключена провайдером", "Напишите в поддержку провайдера: только он может её включить."),
        DiagCause.SubAccessDenied => ("Провайдер закрыл доступ (403)", "Подписка отключена или превышен лимит устройств. Проверьте личный кабинет провайдера."),
        DiagCause.SubLinkUnknown => ("Ссылка подписки больше не действует (404)", "Скорее всего, ключ сброшен или превышен лимит устройств. Возьмите у провайдера новую ссылку."),
        DiagCause.SubWebPage => ("Ссылка открывает сайт, а не подписку", "Возьмите у провайдера ссылку, предназначенную для приложения."),
        DiagCause.SubHappCrypt => ("Зашифрованная ссылка Happ", "Такие ссылки открывает только приложение Happ. Попросите у провайдера обычную ссылку подписки."),
        DiagCause.SubNoServers => ("В подписке нет серверов", "Формат не поддерживается или превышен лимит устройств. Проверьте ссылку у провайдера."),
        DiagCause.SubRateLimited => ("Провайдер просит подождать", "Слишком много запросов. Попробуйте через пару минут."),
        DiagCause.SubProviderDown => ("Сбой у провайдера", "Сервер подписки не отвечает. Попробуйте позже."),
        DiagCause.SubUnreachable => ("Сервер подписки недоступен", "Проверьте интернет и ссылку. Если ссылка старая, возьмите новую у провайдера."),
        DiagCause.NoServer => ("Сервер не выбран", "Добавьте подписку на странице «Добавить» и выберите сервер на странице «Серверы»."),
        DiagCause.NotConnected => ("FlowVeil не подключён", "Нажмите кнопку подключения на странице «Серверы». Если ошибка останется, запустите проверку ещё раз."),
        DiagCause.ServerDown => ("Сервер не отвечает", "Выберите другой сервер или обновите подписку. Если не помогает, напишите провайдеру."),
        DiagCause.ServerNotPassing => ("Соединение есть, но данные не идут", "Выберите другой сервер. Если так у всех, проверьте время на компьютере и обновите подписку."),
        DiagCause.MobileRestricted => ("Сеть ограничена", "Российские сайты открываются напрямую, а зарубежные через сервер нет. Попробуйте «Подключиться к лучшему». Обычная домашняя сеть, как правило, без ограничений."),
        DiagCause.TunNeedsAdmin => ("Для режима TUN нужны права администратора", "Перезапустите FlowVeil от администратора: после этого права запоминаются, и запрос больше не появится."),
        DiagCause.TunAdapterMissing => ("Не появился адаптер TUN", "Перезапустите подключение. Если адаптера нет, антивирус мог заблокировать драйвер: добавьте FlowVeil в исключения или выберите режим «Прокси»."),
        DiagCause.ProxyConflict => ("Системный прокси изменён", "Другая программа сменила прокси Windows, поэтому трафик идёт мимо FlowVeil. Закройте другие клиенты и переподключитесь."),
        DiagCause.OtherClient => ("Запущен другой клиент", "Несколько клиентов одновременно могут мешать друг другу. Закройте лишние."),
        _ => (cause.ToString(), string.Empty),
    };

    public static string Step(DiagStepId id) => id switch
    {
        DiagStepId.Network => "Сеть",
        DiagStepId.Time => "Время на компьютере",
        DiagStepId.Dns => "Адреса сайтов (DNS)",
        DiagStepId.Subscription => "Подписка",
        DiagStepId.Server => "Сервер",
        DiagStepId.EndToEnd => "Проход через сервер",
        DiagStepId.Restriction => "Ограничения сети",
        DiagStepId.Tun => "Режим TUN",
        DiagStepId.Proxy => "Системный прокси",
        _ => id.ToString(),
    };
}

/// <summary>"Почему не работает?": checks one after another with a green/red mark each, then a verdict and what to do. Built in code like the other pages.</summary>
public sealed class DiagnosisView : StackPanel
{
    private static readonly DiagCause[] SubCauses =
    [
        DiagCause.SubExpired, DiagCause.SubTrafficOver, DiagCause.SubDeviceLimit, DiagCause.SubBlocked, DiagCause.SubAccessDenied,
        DiagCause.SubLinkUnknown, DiagCause.SubWebPage, DiagCause.SubHappCrypt, DiagCause.SubNoServers, DiagCause.SubRateLimited,
        DiagCause.SubProviderDown, DiagCause.SubUnreachable,
    ];

    private readonly MainWindowViewModel _vm;
    private readonly bool _running;
    private readonly ProgressBar _busy = new() { IsIndeterminate = true, Height = 3, Margin = new Thickness(0, 8, 0, 8) };
    private readonly StackPanel _steps = new();
    private readonly StackPanel _verdict = new();
    private readonly Button _retry;
    private readonly Button _copy;
    private DiagResult? _result;

    public DiagnosisView(MainWindowViewModel vm, bool running)
    {
        _vm = vm;
        _running = running;
        Width = 480;
        Margin = new Thickness(24);
        Children.Add(new TextBlock { Text = "Почему не работает?", FontSize = 20, FontWeight = FontWeights.SemiBold });
        Children.Add(_busy);
        Children.Add(_steps);
        Children.Add(_verdict);

        _retry = Flat("Повторить", () => _ = RunAsync());
        _copy = Flat("Скопировать отчёт", () => _ = CopyReportAsync());
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
        buttons.Children.Add(_retry);
        buttons.Children.Add(_copy);
        buttons.Children.Add(Flat("Закрыть", () => DialogHost.Close("RootDialog")));
        Children.Add(buttons);
        Loaded += (_, _) => _ = RunAsync();
    }

    private async Task RunAsync()
    {
        _busy.Visibility = Visibility.Visible;
        _retry.IsEnabled = _copy.IsEnabled = false;
        _steps.Children.Clear();
        _verdict.Children.Clear();
        try
        {
            var result = await Task.Run(() => DiagnosticsRunner.RunAsync(AppManager.Instance.Config, _running, (id, partial) =>
                Dispatcher.BeginInvoke(new Action(() => ShowSteps(partial.Steps.Where(s => (int)s.Id <= (int)id))))));
            _result = result;
            ShowSteps(result.Steps);
            ShowVerdict(result);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(DiagnosisView), ex);
            _verdict.Children.Add(new TextBlock { Text = "Не удалось выполнить проверку", Foreground = Brushes.IndianRed });
        }
        _busy.Visibility = Visibility.Collapsed;
        _retry.IsEnabled = true;
        _copy.IsEnabled = _result != null;
    }

    private void ShowSteps(IEnumerable<DiagStep> steps)
    {
        _steps.Children.Clear();
        foreach (var step in steps.Where(s => s.Status != StepStatus.Skipped))
        {
            var (glyph, color) = step.Status switch
            {
                StepStatus.Ok => ("✓", Brushes.MediumSeaGreen),
                StepStatus.Warn => ("!", Brushes.Orange),
                _ => ("✕", Brushes.IndianRed),
            };
            var row = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 2, 0, 2) };
            row.Children.Add(new TextBlock { Text = glyph, Foreground = color, FontWeight = FontWeights.Bold, Width = 22 });
            row.Children.Add(new TextBlock { Text = DiagnosisTexts.Step(step.Id) });
            _steps.Children.Add(row);
        }
    }

    private void ShowVerdict(DiagResult result)
    {
        var (title, fix) = DiagnosisTexts.Of(result.Cause);
        _verdict.Children.Add(new TextBlock
        {
            Text = title,
            FontSize = 16,
            FontWeight = FontWeights.SemiBold,
            Margin = new Thickness(0, 14, 0, 0),
            Foreground = result.Cause == DiagCause.Ok ? Brushes.MediumSeaGreen : Brushes.IndianRed,
        });
        _verdict.Children.Add(new TextBlock { Text = fix, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 4, 0, 0) });
        foreach (var warning in result.Warnings)
        {
            var (wt, wf) = DiagnosisTexts.Of(warning);
            _verdict.Children.Add(new TextBlock { Text = $"Ещё: {wt}", FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 10, 0, 0) });
            _verdict.Children.Add(new TextBlock { Text = wf, TextWrapping = TextWrapping.Wrap, FontSize = 12 });
        }

        var actions = new WrapPanel { Margin = new Thickness(0, 10, 0, 0) };
        if (SubCauses.Contains(result.Cause))
        {
            actions.Children.Add(Flat("Обновить подписку", () =>
            {
                DialogHost.Close("RootDialog");
                ((System.Windows.Input.ICommand)_vm.SubUpdateCmd).Execute(null);
            }));
            var support = SupportUrl();
            if (support != null)
            {
                actions.Children.Add(Flat("Поддержка", () => ProcUtils.ProcessStart(support)));
            }
        }
        if (result.Cause == DiagCause.WrongTime)
        {
            actions.Children.Add(Flat("Дата и время", () => ProcUtils.ProcessStart("ms-settings:dateandtime")));
        }
        if (result.Cause == DiagCause.TunNeedsAdmin)
        {
            actions.Children.Add(Flat("Перезапустить от администратора", () =>
            {
                DialogHost.Close("RootDialog");
                ((System.Windows.Input.ICommand)_vm.RebootAsAdminCmd).Execute(null);
            }));
        }
        if (actions.Children.Count > 0)
        {
            _verdict.Children.Add(actions);
        }
    }

    private static string? SupportUrl()
    {
        var config = AppManager.Instance.Config;
        var subId = config.IndexId.IsNullOrEmpty() ? null : ServiceLib.Helper.SQLiteHelper.Instance.TableAsync<ServiceLib.Models.Entities.ProfileItem>().FirstOrDefaultAsync(p => p.IndexId == config.IndexId).GetAwaiter().GetResult()?.Subid;
        var url = SubscriptionInfoStore.Get(subId)?.SupportUrl;
        return url.IsNotEmpty() && (url!.StartsWith("http://") || url.StartsWith("https://") || url.StartsWith("tg://")) ? url : null;
    }

    private async Task CopyReportAsync()
    {
        if (_result == null)
        {
            return;
        }
        var text = await DiagnosticsRunner.ReportAsync(AppManager.Instance.Config, _result);
        WindowsUtils.SetClipboardData(text);
        NoticeManager.Instance.Enqueue("Отчёт скопирован. В нём нет ссылок, ключей и адресов");
    }

    private static Button Flat(string text, Action click)
    {
        var button = new Button { Content = text, Margin = new Thickness(0, 0, 4, 0) };
        if (Application.Current?.TryFindResource("MaterialDesignFlatButton") is Style style)
        {
            button.Style = style;
        }
        button.Click += (_, _) => click();
        return button;
    }
}

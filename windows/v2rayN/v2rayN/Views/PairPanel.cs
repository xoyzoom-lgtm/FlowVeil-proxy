using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;

namespace v2rayN.Views;

/// <summary>The "С телефона" card: big QR, three steps, a 6-digit code for manual entry, a countdown and the live state of the session.</summary>
public sealed class PairPanel : StackPanel
{
    private readonly PairServer _server = new();
    private readonly Action<PairPayload, string> _onReceived;
    private readonly Button _start;
    private readonly Button _refresh;
    private readonly StackPanel _body = new() { Visibility = Visibility.Collapsed };
    private readonly Image _qr = new() { Width = 220, Height = 220, Stretch = Stretch.Uniform };
    private readonly Border _qrFrame = new();
    private readonly TextBlock _code = new() { FontSize = 26, FontWeight = FontWeights.Bold, FontFamily = new FontFamily("Consolas") };
    private readonly TextBlock _address = new() { FontSize = 12 };
    private readonly TextBlock _status = new() { TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 8, 0, 0) };
    private readonly TextBlock _timer = new() { FontSize = 12 };
    private readonly ComboBox _adapters = new() { Visibility = Visibility.Collapsed, Margin = new Thickness(0, 8, 0, 0) };
    private readonly DispatcherTimer _tick = new() { Interval = TimeSpan.FromSeconds(1) };
    private bool _updating;

    public PairPanel(Action<PairPayload, string> onReceived)
    {
        _onReceived = onReceived;
        _start = AddPageView.Flat("Показать QR-код", () => StartSession());
        _refresh = AddPageView.Flat("Обновить код", () => StartSession());
        _refresh.Visibility = Visibility.Collapsed;
        Children.Add(_start);

        _qr.SetValue(RenderOptions.BitmapScalingModeProperty, BitmapScalingMode.NearestNeighbor);
        _qrFrame.Background = Brushes.White;
        _qrFrame.CornerRadius = new CornerRadius(12);
        // white frame = the quiet zone a scanner needs, whatever the theme is
        _qrFrame.Padding = new Thickness(16);
        _qrFrame.HorizontalAlignment = HorizontalAlignment.Left;
        _qrFrame.Child = _qr;

        var steps = new TextBlock
        {
            Text = "1. Откройте FlowVeil на телефоне: «Добавить» → «Отправить на компьютер».\n2. Наведите камеру на этот QR-код (или откройте его обычной камерой, если приложения нет).\n3. Подтвердите добавление здесь, на компьютере.",
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 12, 0, 0),
        };
        var manual = new TextBlock { Text = "Не читается? Введите на телефоне адрес и код:", Margin = new Thickness(0, 12, 0, 0), FontSize = 12 };
        manual.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
        _address.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
        _timer.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");
        var firewall = new TextBlock
        {
            Text = "Если Windows спросит про доступ к сети, разрешите его для частной сети: без этого телефон не достучится.",
            TextWrapping = TextWrapping.Wrap,
            FontSize = 12,
            Margin = new Thickness(0, 8, 0, 0),
        };
        firewall.SetResourceReference(TextBlock.ForegroundProperty, "MaterialDesign.Brush.ForegroundLight");

        _adapters.SelectionChanged += (_, _) =>
        {
            if (!_updating && _adapters.SelectedItem is PairAdapter)
            {
                StartSession(_adapters.SelectedItem as PairAdapter);
            }
        };

        _body.Children.Add(_qrFrame);
        _body.Children.Add(_timer);
        _body.Children.Add(steps);
        _body.Children.Add(manual);
        _body.Children.Add(_code);
        _body.Children.Add(_address);
        _body.Children.Add(_adapters);
        _body.Children.Add(_status);
        _body.Children.Add(firewall);
        _body.Children.Add(_refresh);
        Children.Add(_body);

        _server.StateChanged += (state, device) => Dispatcher.BeginInvoke(new Action(() => OnState(state, device)));
        _server.Received += (payload, device) => Dispatcher.BeginInvoke(new Action(() => _onReceived(payload, device)));
        _tick.Tick += (_, _) => UpdateTimer();
    }

    /// <summary>Stops listening (the page is left or the window is hidden): the port is open only while the card is on screen.</summary>
    public void Stop()
    {
        _tick.Stop();
        _server.Dispose();
        _body.Visibility = Visibility.Collapsed;
        _start.Visibility = Visibility.Visible;
    }

    /// <summary>Host name of a link without its secrets, for the confirmation window.</summary>
    public static string Describe(string link)
    {
        var scheme = link[..link.IndexOf("://", StringComparison.Ordinal)];
        if (Utils.TryUri(link) is { } uri && !string.IsNullOrEmpty(uri.Host))
        {
            return scheme is "http" or "https" ? $"подписка {uri.Host}" : $"сервер {scheme}://{uri.Host}";
        }
        return scheme + "://…";
    }

    private void StartSession(PairAdapter? adapter = null)
    {
        try
        {
            var adapters = PairServer.Adapters();
            if (adapters.Count == 0)
            {
                Show(false);
                _status.Text = "Компьютер не подключён к домашней сети (Wi-Fi или кабель). Подключите его к той же сети, что и телефон";
                return;
            }
            var chosen = adapter ?? adapters[0];
            var url = _server.Start(chosen);
            if (url == null)
            {
                Show(false);
                _status.Text = "Не получилось открыть приём. Проверьте сеть";
                return;
            }
            _updating = true;
            _adapters.ItemsSource = adapters;
            _adapters.SelectedItem = adapters.FirstOrDefault(a => a.Address.Equals(chosen.Address));
            _adapters.Visibility = adapters.Count > 1 ? Visibility.Visible : Visibility.Collapsed;
            _updating = false;

            _qr.Source = QRCodeWindowsUtils.GetQRCode(url);
            _qrFrame.Opacity = 1;
            _code.Text = _server.Session!.Code.Insert(3, " ");
            _address.Text = $"Адрес: {_server.HostPort}";
            _status.Text = "Ждём телефон…";
            _refresh.Visibility = Visibility.Collapsed;
            Show(true);
            UpdateTimer();
            _tick.Start();
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(PairPanel), ex);
            Show(false);
            _status.Text = "Не получилось открыть приём. Возможно, доступ к сети закрыт брандмауэром";
        }
    }

    private void Show(bool qr)
    {
        _body.Visibility = Visibility.Visible;
        _start.Visibility = Visibility.Collapsed;
        _qrFrame.Visibility = qr ? Visibility.Visible : Visibility.Collapsed;
        _code.Visibility = _address.Visibility = _timer.Visibility = qr ? Visibility.Visible : Visibility.Collapsed;
        _refresh.Visibility = qr ? Visibility.Collapsed : Visibility.Visible;
    }

    private void UpdateTimer()
    {
        var left = _server.Session?.Remaining ?? TimeSpan.Zero;
        _timer.Text = left > TimeSpan.Zero ? $"Код действует ещё {(int)left.TotalMinutes}:{left.Seconds:D2}" : "Время вышло";
    }

    private void OnState(PairState state, string? device)
    {
        switch (state)
        {
            case PairState.DeviceSeen:
                _status.Text = "Телефон нашёл компьютер, ждём ссылку…";
                break;
            case PairState.Received:
                _status.Text = $"Получено от «{device ?? "устройства"}». Подтвердите добавление";
                Finished();
                break;
            case PairState.Expired:
                _status.Text = "Время вышло. Нажмите «Обновить код»";
                Finished();
                break;
            case PairState.Locked:
                _status.Text = "Слишком много неверных попыток, приём закрыт. Нажмите «Обновить код»";
                Finished();
                break;
        }
    }

    private void Finished()
    {
        _tick.Stop();
        _qrFrame.Opacity = 0.25;
        _refresh.Visibility = Visibility.Visible;
    }
}

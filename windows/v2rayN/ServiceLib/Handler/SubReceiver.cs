using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;

namespace ServiceLib.Handler;

/// <summary>
/// "Add by QR": FlowVeil shows a QR code that leads to a tiny page on the home network; the other
/// device (phone, or another app's browser) opens it, the subscription link is pasted there and
/// arrives here. Small on purpose: it listens only on this computer's own LAN address, only while
/// the dialog is open (10 minutes at most), every request needs the random token that is in the QR,
/// bodies are capped and the only thing accepted is one http(s) link. The link crosses the home
/// network as plain HTTP, like a code typed on a remote; not for public Wi-Fi.
/// </summary>
public sealed class SubReceiver : IDisposable
{
    /// <summary>Marks our own address, so a scan of the QR by a scanner is not taken for a subscription.</summary>
    public const string Marker = "fv_tv";

    private const int MaxHead = 8 * 1024;
    private const int MaxBody = 4 * 1024;

    private readonly Action<string> _onLink;
    private readonly string _token = NewToken();
    private readonly CancellationTokenSource _cts = new();
    private TcpListener? _listener;

    public SubReceiver(Action<string> onLink)
    {
        _onLink = onLink;
    }

    /// <summary>The address for the QR code; null when this computer is on no local network.</summary>
    public string? Start()
    {
        var ip = LanAddress();
        if (ip == null)
        {
            return null;
        }
        _listener = new TcpListener(ip, 0);
        _listener.Start(4);
        _cts.CancelAfter(TimeSpan.FromMinutes(10));
        _ = Task.Run(() => Loop(_listener));
        var port = ((IPEndPoint)_listener.LocalEndpoint).Port;
        return $"http://{ip}:{port}/?{Marker}=1&t={_token}";
    }

    public void Dispose()
    {
        _cts.Cancel();
        try
        {
            _listener?.Stop();
        }
        catch
        {
            // already stopped
        }
    }

    private async Task Loop(TcpListener listener)
    {
        while (!_cts.IsCancellationRequested)
        {
            try
            {
                using var client = await listener.AcceptTcpClientAsync(_cts.Token);
                client.ReceiveTimeout = 5000;
                client.SendTimeout = 5000;
                await Handle(client);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (Exception ex)
            {
                Logging.SaveLog(nameof(SubReceiver), ex);
            }
        }
    }

    private async Task Handle(TcpClient client)
    {
        using var stream = client.GetStream();
        var head = await ReadHead(stream);
        if (head == null)
        {
            return;
        }
        var lines = head.Split("\r\n");
        var first = lines[0].Split(' ');
        var method = first.Length > 0 ? first[0] : string.Empty;
        var target = first.Length > 1 ? first[1] : string.Empty;
        var length = lines.Where(l => l.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase))
            .Select(l => int.TryParse(l[(l.IndexOf(':') + 1)..].Trim(), out var n) ? n : 0).FirstOrDefault();

        var path = target.Split('?')[0];
        if (method == "GET" && path == "/")
        {
            var query = ParseForm(target.Contains('?') ? target[(target.IndexOf('?') + 1)..] : string.Empty);
            if (!TokenOk(query.GetValueOrDefault("t")))
            {
                await Reply(stream, 403, Page("Ссылка устарела. Покажите QR-код в FlowVeil заново."));
            }
            else
            {
                await Reply(stream, 200, Form());
            }
        }
        else if (method == "POST" && target == "/add" && length is > 0 and <= MaxBody)
        {
            var body = new byte[length];
            var read = 0;
            while (read < length)
            {
                var n = await stream.ReadAsync(body.AsMemory(read, length - read));
                if (n <= 0)
                {
                    break;
                }
                read += n;
            }
            var form = ParseForm(System.Text.Encoding.UTF8.GetString(body, 0, read));
            var link = form.GetValueOrDefault("url")?.Trim() ?? string.Empty;
            if (!TokenOk(form.GetValueOrDefault("t")))
            {
                await Reply(stream, 403, Page("Ссылка устарела. Покажите QR-код в FlowVeil заново."));
            }
            else if (!ValidLink(link))
            {
                await Reply(stream, 400, Form("Нужна ссылка вида https://…"));
            }
            else
            {
                _onLink(link);
                await Reply(stream, 200, Page("Готово. Подписка отправлена в FlowVeil, смотрите на экран."));
            }
        }
        else
        {
            await Reply(stream, 404, Page("Не найдено"));
        }
    }

    private bool TokenOk(string? given) =>
        given != null && CryptographicOperations.FixedTimeEquals(System.Text.Encoding.UTF8.GetBytes(given), System.Text.Encoding.UTF8.GetBytes(_token));

    private static async Task<string?> ReadHead(NetworkStream stream)
    {
        var sb = new System.Text.StringBuilder();
        var one = new byte[1];
        while (sb.Length < MaxHead)
        {
            if (await stream.ReadAsync(one) <= 0)
            {
                return null;
            }
            sb.Append((char)one[0]);
            if (sb.Length >= 4 && sb.ToString(sb.Length - 4, 4) == "\r\n\r\n")
            {
                return sb.ToString(0, sb.Length - 4);
            }
        }
        return null;
    }

    private static async Task Reply(NetworkStream stream, int code, string html)
    {
        var bytes = System.Text.Encoding.UTF8.GetBytes(html);
        var status = code switch { 200 => "OK", 400 => "Bad Request", 403 => "Forbidden", _ => "Not Found" };
        var head = $"HTTP/1.1 {code} {status}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {bytes.Length}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        await stream.WriteAsync(System.Text.Encoding.ASCII.GetBytes(head));
        await stream.WriteAsync(bytes);
        await stream.FlushAsync();
    }

    private static string Esc(string s) => s.Replace("&", "&amp;").Replace("<", "&lt;").Replace(">", "&gt;");

    private static string Page(string text) =>
        "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
        $"<body style=\"font:18px system-ui;margin:24px\"><h2>FlowVeil</h2><p>{Esc(text)}</p></body>";

    private string Form(string? error = null) =>
        "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
        "<body style=\"font:18px system-ui;margin:24px\"><h2>Подписка в FlowVeil</h2><p>Вставьте ссылку подписки, она придёт в FlowVeil.</p>" +
        (error == null ? string.Empty : $"<p style=color:#c00>{Esc(error)}</p>") +
        $"<form method=post action=/add><input type=hidden name=t value={_token}>" +
        "<input name=url type=url required autofocus placeholder=\"https://…\" style=\"width:100%;font-size:18px;padding:12px;box-sizing:border-box\">" +
        "<p><button style=\"font-size:18px;padding:12px 24px\">Отправить</button></p></form></body>";

    public static bool ValidLink(string link) =>
        link.Length is >= 8 and <= 2000 && !link.Any(char.IsWhiteSpace)
        && (link.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || link.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
        && !link.Contains($"{Marker}=1");

    public static Dictionary<string, string> ParseForm(string text)
    {
        var result = new Dictionary<string, string>();
        foreach (var pair in text.Split('&'))
        {
            var i = pair.IndexOf('=');
            if (i <= 0)
            {
                continue;
            }
            try
            {
                result[Uri.UnescapeDataString(pair[..i].Replace('+', ' '))] = Uri.UnescapeDataString(pair[(i + 1)..].Replace('+', ' '));
            }
            catch (UriFormatException)
            {
                // a broken escape: skip this field
            }
        }
        return result;
    }

    private static string NewToken()
    {
        const string alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
        return new string(Enumerable.Range(0, 12).Select(_ => alphabet[RandomNumberGenerator.GetInt32(alphabet.Length)]).ToArray());
    }

    /// <summary>An address on the home network (Wi-Fi or cable); null when there is none.</summary>
    private static IPAddress? LanAddress()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up
                            && n.NetworkInterfaceType is NetworkInterfaceType.Wireless80211 or NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet)
                .SelectMany(n => n.GetIPProperties().UnicastAddresses)
                .Select(a => a.Address)
                .FirstOrDefault(a => a.AddressFamily == AddressFamily.InterNetwork && IsPrivate(a));
        }
        catch
        {
            return null;
        }
    }

    private static bool IsPrivate(IPAddress a)
    {
        var b = a.GetAddressBytes();
        return b[0] == 10 || (b[0] == 172 && b[1] is >= 16 and <= 31) || (b[0] == 192 && b[1] == 168);
    }
}

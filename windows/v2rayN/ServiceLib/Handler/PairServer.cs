using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace ServiceLib.Handler;

public enum PairState
{
    Waiting,
    DeviceSeen,
    Received,
    Expired,
    Locked,
    Stopped,
}

public sealed record PairAdapter(string Name, IPAddress Address)
{
    public override string ToString() => $"{Name} ({Address})";
}

/// <summary>
/// "FlowVeil Pair": the other device (phone) sends subscription links to this computer over the home network.
/// A session lives 5 minutes, works once, and the listener exists only while the session is alive: it binds
/// to one private LAN address on a random port, serves one connection at a time with short timeouts and hard size limits,
/// never writes to disk and never logs tokens, keys or links. Only GET and POST are answered.
/// </summary>
public sealed class PairServer : IDisposable
{
    private const int MaxHead = 8 * 1024;
    private const int MaxBody = PairPayload.MaxBodyBytes + 4096;
    private static readonly TimeSpan ConnectionTimeout = TimeSpan.FromSeconds(8);

    private readonly object _lock = new();
    private TcpListener? _listener;
    private CancellationTokenSource? _cts;
    private int _generation;

    public PairSession? Session { get; private set; }
    public PairAdapter? Adapter { get; private set; }
    public int Port { get; private set; }
    public PairState State { get; private set; } = PairState.Stopped;

    /// <summary>Raised on any thread when the state changes; the argument is the phone's name when known.</summary>
    public event Action<PairState, string?>? StateChanged;

    /// <summary>Raised on any thread with a validated payload and the phone's name. Nothing is added before the user confirms.</summary>
    public event Action<PairPayload, string>? Received;

    private static readonly string[] VirtualNames = ["virtual", "vmware", "vbox", "hyper-v", "vethernet", "tap", "tun", "wintun", "wireguard", "tailscale", "zerotier", "loopback", "vpn", "npcap", "pseudo", "bluetooth", "docker", "wsl"];

    /// <summary>True for tunnel, virtual and VPN adapters (by name), which are never "the real network".</summary>
    public static bool IsVirtualAdapter(string name, string description) => VirtualNames.Any((name + " " + description).ToLowerInvariant().Contains);

    /// <summary>Wi-Fi and cable adapters with a private IPv4 address; virtual, tunnel and VPN adapters are left out.</summary>
    public static List<PairAdapter> Adapters()
    {
        var list = new List<PairAdapter>();
        try
        {
            foreach (var n in NetworkInterface.GetAllNetworkInterfaces())
            {
                var text = (n.Name + " " + n.Description).ToLowerInvariant();
                if (n.OperationalStatus != OperationalStatus.Up
                    || n.NetworkInterfaceType is not (NetworkInterfaceType.Wireless80211 or NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet)
                    || VirtualNames.Any(text.Contains))
                {
                    continue;
                }
                var props = n.GetIPProperties();
                var hasGateway = props.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any));
                foreach (var a in props.UnicastAddresses)
                {
                    if (a.Address.AddressFamily == AddressFamily.InterNetwork && IsPrivate(a.Address))
                    {
                        list.Add(new PairAdapter(n.Name, a.Address));
                    }
                }
                // adapters with a gateway (a real network) go first
                if (hasGateway && list.Count > 1)
                {
                    var last = list[^1];
                    list.RemoveAt(list.Count - 1);
                    list.Insert(0, last);
                }
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(PairServer), ex);
        }
        return list;
    }

    public static bool IsPrivate(IPAddress a)
    {
        var b = a.GetAddressBytes();
        return a.AddressFamily == AddressFamily.InterNetwork
               && (b[0] == 10 || (b[0] == 172 && b[1] is >= 16 and <= 31) || (b[0] == 192 && b[1] == 168));
    }

    /// <summary>Starts a new session (stopping the previous one) and returns the address for the QR; null when there is no suitable network.</summary>
    public string? Start(PairAdapter? adapter = null, TimeSpan? ttl = null)
    {
        Stop(PairState.Stopped);
        adapter ??= Adapters().FirstOrDefault();
        if (adapter == null)
        {
            return null;
        }
        var session = new PairSession(ttl);
        var listener = new TcpListener(adapter.Address, 0);
        listener.Start(4);
        var cts = new CancellationTokenSource(session.Remaining);
        int generation;
        lock (_lock)
        {
            generation = ++_generation;
            _listener = listener;
            _cts = cts;
            Session = session;
            Adapter = adapter;
            Port = ((IPEndPoint)listener.LocalEndpoint).Port;
        }
        SetState(PairState.Waiting, null);
        _ = Task.Run(() => Loop(listener, session, cts, generation));
        return PairQr.Build(adapter.Address.ToString(), Port, session);
    }

    public string HostPort => Adapter == null ? string.Empty : $"{Adapter.Address}:{Port}";

    public void Dispose() => Stop(PairState.Stopped);

    private void Stop(PairState state)
    {
        lock (_lock)
        {
            _generation++;
            try
            {
                _cts?.Cancel();
                _listener?.Stop();
            }
            catch
            {
                // already stopped
            }
            _listener = null;
            _cts = null;
        }
        if (state != PairState.Stopped || State != PairState.Stopped)
        {
            SetState(state, null);
        }
    }

    private void SetState(PairState state, string? device)
    {
        State = state;
        StateChanged?.Invoke(state, device);
    }

    private async Task Loop(TcpListener listener, PairSession session, CancellationTokenSource cts, int generation)
    {
        try
        {
            while (!cts.IsCancellationRequested && !session.Used && !session.IsLocked)
            {
                using var client = await listener.AcceptTcpClientAsync(cts.Token);
                using var perConnection = CancellationTokenSource.CreateLinkedTokenSource(cts.Token);
                perConnection.CancelAfter(ConnectionTimeout);
                try
                {
                    if (client.Client.RemoteEndPoint is IPEndPoint remote && (IsPrivate(remote.Address) || IPAddress.IsLoopback(remote.Address)))
                    {
                        await Handle(client, session, generation, perConnection.Token);
                    }
                }
                catch (OperationCanceledException) when (!cts.IsCancellationRequested)
                {
                    // slow client: drop it, keep serving
                }
                catch (IOException)
                {
                    // client went away
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (ObjectDisposedException)
        {
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(PairServer), ex);
        }
        finally
        {
            lock (_lock)
            {
                if (generation == _generation)
                {
                    try
                    {
                        listener.Stop();
                    }
                    catch
                    {
                        // already stopped
                    }
                }
            }
            if (generation == _generation)
            {
                if (session.Used)
                {
                    // Received was already reported
                }
                else if (session.IsLocked)
                {
                    SetState(PairState.Locked, null);
                }
                else
                {
                    SetState(PairState.Expired, null);
                }
            }
        }
    }

    private async Task Handle(TcpClient client, PairSession session, int generation, CancellationToken ct)
    {
        var stream = client.GetStream();
        var head = await ReadHead(stream, ct);
        if (head == null)
        {
            return;
        }
        var lines = head.Split("\r\n");
        var first = lines[0].Split(' ');
        var method = first.Length > 0 ? first[0] : string.Empty;
        var target = first.Length > 1 ? first[1] : string.Empty;
        var path = target.Split('?')[0].TrimEnd('/');
        if (path.Length == 0)
        {
            path = "/";
        }

        if (method == "GET")
        {
            if (path == "/")
            {
                await Reply(stream, 200, "text/html", Page(string.Empty), ct);
            }
            else if (path == $"/p/{session.Sid}")
            {
                await Reply(stream, 200, "text/html", Page(session.Sid), ct);
            }
            else if (path == $"/p/{session.Sid}/info")
            {
                var info = JsonSerializer.Serialize(new { v = 1, app = "FlowVeil", device = Environment.MachineName, ttl = (int)session.Remaining.TotalSeconds, enc = true });
                await Reply(stream, 200, "application/json", info, ct);
                SetState(PairState.DeviceSeen, null);
            }
            else
            {
                await Reply(stream, 404, "application/json", Err("not_found"), ct);
            }
            return;
        }

        var isSend = path == $"/p/{session.Sid}/send";
        var isCode = path == "/c";
        if (method != "POST" || !(isSend || isCode))
        {
            await Reply(stream, method is "GET" or "POST" ? 404 : 405, "application/json", Err("not_found"), ct);
            return;
        }

        var length = lines.Where(l => l.StartsWith("Content-Length:", StringComparison.OrdinalIgnoreCase))
            .Select(l => int.TryParse(l[(l.IndexOf(':') + 1)..].Trim(), out var n) ? n : -1).FirstOrDefault(-1);
        if (length is <= 0 or > MaxBody)
        {
            await Reply(stream, 413, "application/json", Err("too_large"), ct);
            return;
        }
        var body = new byte[length];
        var read = 0;
        while (read < length)
        {
            var n = await stream.ReadAsync(body.AsMemory(read, length - read), ct);
            if (n <= 0)
            {
                return;
            }
            read += n;
        }

        string? token, device = null, plaintext = null;
        int enc;
        JsonElement data;
        try
        {
            using var doc = JsonDocument.Parse(body);
            var root = doc.RootElement;
            token = root.TryGetProperty(isCode ? "code" : "t", out var t) ? t.GetString() : null;
            enc = root.TryGetProperty("enc", out var e) && e.TryGetInt32(out var ei) ? ei : 0;
            device = root.TryGetProperty("device", out var d) && d.ValueKind == JsonValueKind.String ? Clean(d.GetString()) : null;
            if (!root.TryGetProperty("data", out data))
            {
                await Reply(stream, 400, "application/json", Err("bad_request"), ct);
                return;
            }
            var check = session.Check(token, isCode);
            if (check != PairCheck.Ok)
            {
                await ReplyCheck(stream, check, ct);
                return;
            }
            if (isCode && enc != 0)
            {
                await Reply(stream, 400, "application/json", Err("bad_request"), ct);
                return;
            }
            plaintext = enc == 1
                ? (data.ValueKind == JsonValueKind.String ? PairCrypto.Decrypt(session.Key, session.Sid, data.GetString()!) : null)
                : data.GetRawText();
        }
        catch (JsonException)
        {
            await Reply(stream, 400, "application/json", Err("bad_request"), ct);
            return;
        }

        var payload = plaintext == null ? null : PairPayload.Parse(plaintext, out _);
        if (payload == null)
        {
            await Reply(stream, 400, "application/json", Err(plaintext == null ? "decrypt_failed" : "bad_payload"), ct);
            return;
        }
        if (!session.TryConsume())
        {
            await ReplyCheck(stream, PairCheck.Used, ct);
            return;
        }
        await Reply(stream, 200, "application/json", "{\"ok\":true}", ct);
        SetState(PairState.Received, device);
        Received?.Invoke(payload, device ?? "Устройство");
    }

    private static string? Clean(string? s) => s == null ? null : new string(s.Where(c => !char.IsControl(c)).Take(60).ToArray());

    private static string Err(string code) => $"{{\"ok\":false,\"error\":\"{code}\"}}";

    private static Task ReplyCheck(NetworkStream stream, PairCheck check, CancellationToken ct) => check switch
    {
        PairCheck.Bad => Reply(stream, 403, "application/json", Err("bad_token"), ct),
        PairCheck.Locked => Reply(stream, 429, "application/json", Err("locked"), ct),
        PairCheck.Used => Reply(stream, 410, "application/json", Err("used"), ct),
        _ => Reply(stream, 410, "application/json", Err("expired"), ct),
    };

    private static async Task<string?> ReadHead(NetworkStream stream, CancellationToken ct)
    {
        var sb = new StringBuilder();
        var one = new byte[1];
        while (sb.Length < MaxHead)
        {
            if (await stream.ReadAsync(one, ct) <= 0)
            {
                return null;
            }
            sb.Append((char)one[0]);
            if (sb.Length >= 4 && sb[^1] == '\n' && sb[^2] == '\r' && sb[^3] == '\n' && sb[^4] == '\r')
            {
                return sb.ToString(0, sb.Length - 4);
            }
        }
        return null;
    }

    private static async Task Reply(NetworkStream stream, int code, string type, string body, CancellationToken ct)
    {
        var bytes = Encoding.UTF8.GetBytes(body);
        var status = code switch { 200 => "OK", 400 => "Bad Request", 403 => "Forbidden", 404 => "Not Found", 405 => "Method Not Allowed", 410 => "Gone", 413 => "Payload Too Large", 429 => "Too Many Requests", _ => "Error" };
        var head = $"HTTP/1.1 {code} {status}\r\nContent-Type: {type}; charset=utf-8\r\nContent-Length: {bytes.Length}\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'\r\nConnection: close\r\n\r\n";
        await stream.WriteAsync(Encoding.ASCII.GetBytes(head), ct);
        await stream.WriteAsync(bytes, ct);
        await stream.FlushAsync(ct);
    }

    /// <summary>The page for a phone browser (iPhone, other apps). No external resources; the secret is read from the address fragment by the page itself.</summary>
    private static string Page(string sid)
    {
        var safeSid = sid.Replace("&", "&amp;").Replace("\"", "&quot;").Replace("<", "&lt;");
        return """
<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>FlowVeil</title>
<style>body{font:17px system-ui,sans-serif;margin:0;padding:24px;background:#111;color:#eee}main{max-width:460px;margin:auto}
h1{font-size:22px}input,button{font:inherit;width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid #555;background:#1c1c1c;color:#eee;margin:6px 0}
button{background:#5b8cff;border:0;color:#fff;font-weight:600}p.s{color:#9a9a9a;font-size:14px}#m{min-height:24px}</style>
<main data-sid="__SID__"><h1>Отправить на компьютер</h1>
<p>Вставьте ссылку подписки — она придёт в FlowVeil на компьютере, там нужно будет подтвердить.</p>
<input id="u" type="url" placeholder="https://…" autocomplete="off">
<input id="c" inputmode="numeric" maxlength="6" placeholder="Код с экрана компьютера" hidden>
<button id="b">Отправить</button><p id="m"></p>
<p class="s">Ссылка уходит по вашей домашней сети напрямую на компьютер, наружу не выходит. Не используйте в общественном Wi-Fi.</p></main>
<script>
var main=document.querySelector('main'),sid=main.dataset.sid,h=new URLSearchParams(location.hash.slice(1)),t=h.get('t');
var u=document.getElementById('u'),c=document.getElementById('c'),m=document.getElementById('m'),msgs={bad_token:'Неверный код',expired:'Время вышло. Покажите новый код на компьютере',used:'Этот код уже использован',locked:'Слишком много попыток. Покажите новый код на компьютере',bad_payload:'Нужна ссылка вида https://…',too_large:'Слишком длинная ссылка'};
if(!sid||!t){c.hidden=false}
document.getElementById('b').onclick=function(){
 var link=u.value.trim();if(!link){m.textContent='Вставьте ссылку';return}
 var data={type:'subscription',items:[link]},req=sid&&t?{t:t,enc:0,device:'Браузер',data:data}:{code:c.value.trim(),enc:0,device:'Браузер',data:data};
 fetch(sid&&t?'/p/'+sid+'/send':'/c',{method:'POST',body:JSON.stringify(req)}).then(function(r){return r.json()}).then(function(j){
  m.textContent=j.ok?'Готово. Подтвердите добавление на компьютере.':(msgs[j.error]||'Не получилось отправить')}).catch(function(){m.textContent='Нет связи с компьютером'})}
</script></html>
""".Replace("__SID__", safeSid);
    }
}

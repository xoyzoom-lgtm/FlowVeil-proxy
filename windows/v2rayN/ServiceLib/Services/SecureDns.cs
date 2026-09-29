using System.Net;
using System.Net.Sockets;
using System.Text.Json;

namespace ServiceLib.Services;

/// <summary>
/// Resolves host names over HTTPS (Cloudflare, then Google) by IP address, so a subscription
/// can still be fetched when the ISP's DNS hides or poisons the provider's domain.
/// </summary>
public static class SecureDns
{
    private static readonly string[] Resolvers =
    [
        "https://1.1.1.1/dns-query?name={0}&type=A",
        "https://8.8.8.8/resolve?name={0}&type=A",
    ];

    public static async Task<IPAddress[]> ResolveAsync(string host, CancellationToken ct)
    {
        if (IPAddress.TryParse(host, out var literal))
        {
            return [literal];
        }
        using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(6) };
        client.DefaultRequestHeaders.Accept.ParseAdd("application/dns-json");
        foreach (var resolver in Resolvers)
        {
            try
            {
                var json = await client.GetStringAsync(string.Format(resolver, Uri.EscapeDataString(host)), ct);
                using var doc = JsonDocument.Parse(json);
                if (!doc.RootElement.TryGetProperty("Answer", out var answers))
                {
                    continue;
                }
                var ips = answers.EnumerateArray()
                    .Where(a => a.TryGetProperty("type", out var t) && t.GetInt32() == 1)
                    .Select(a => IPAddress.TryParse(a.GetProperty("data").GetString(), out var ip) ? ip : null)
                    .OfType<IPAddress>()
                    .ToArray();
                if (ips.Length > 0)
                {
                    return ips;
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                Logging.SaveLog(nameof(SecureDns), ex);
            }
        }
        throw new HttpRequestException($"Не удалось найти адрес {host}");
    }

    /// <summary>ConnectCallback for SocketsHttpHandler that uses <see cref="ResolveAsync"/>.</summary>
    public static async ValueTask<Stream> ConnectAsync(SocketsHttpConnectionContext context, CancellationToken ct)
    {
        var ips = await ResolveAsync(context.DnsEndPoint.Host, ct);
        var socket = new Socket(SocketType.Stream, ProtocolType.Tcp) { NoDelay = true };
        try
        {
            await socket.ConnectAsync(ips, context.DnsEndPoint.Port, ct);
            return new NetworkStream(socket, ownsSocket: true);
        }
        catch
        {
            socket.Dispose();
            throw;
        }
    }
}

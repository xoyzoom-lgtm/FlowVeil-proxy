namespace ServiceLib.Handler;

/// <summary>
/// Many subscription panels answer by the client name in the User-Agent. When our own name is refused, the same address is
/// asked again as other well-known clients, in this order. Same list and rules as Android net/ClientAgents.kt.
/// </summary>
public static class ClientAgents
{
    public static readonly IReadOnlyList<string> Fallback =
        ["Happ/2.7.0", "v2rayN/7.25.2", "clash-verge/v2.2.3", "HiddifyNext/2.5.7", "sing-box/1.12.0"];

    /// <summary>True when the provider refused or sent us elsewhere (3xx, 4xx except 404/410, 5xx), not "no network".</summary>
    public static bool Retryable(int? status) =>
        status is int s && (s is >= 300 and < 400 || s is >= 400 and < 500 && s != 404 && s != 410 || s is >= 500 and < 600);
}

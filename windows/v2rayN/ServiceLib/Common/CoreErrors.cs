namespace ServiceLib.Common;

/// <summary>
/// Xray (since v26.7) refuses a VLESS or Trojan server without TLS / REALITY / encryption unless its address is private.
/// The raw line in the core log is English gibberish for most people; this says it in plain words.
/// </summary>
public static class CoreErrors
{
    public const string PlainServer =
        "Сервер настроен без шифрования (VLESS или Trojan без TLS и REALITY). Ядро Xray отказывается его запускать ради вашей безопасности: пароль и трафик шли бы открытым текстом. Выберите другой сервер или попросите у провайдера серверы с TLS или REALITY.";

    /// <summary>The plain-words message when the core line is that refusal, otherwise null.</summary>
    public static string? Friendly(string? coreLine)
    {
        if (coreLine.IsNullOrEmpty())
        {
            return null;
        }
        var t = coreLine!.ToLowerInvariant();
        if (t.Contains("prohibited") && (t.Contains("vless without tls") || t.Contains("trojan without tls")))
        {
            return PlainServer;
        }
        return null;
    }
}

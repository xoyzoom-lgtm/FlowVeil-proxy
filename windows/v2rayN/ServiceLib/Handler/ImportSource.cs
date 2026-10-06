namespace ServiceLib.Handler;

/// <summary>
/// Text from a QR code, the clipboard or a link, unwrapped from other clients' import wrappers (same rules and tests as
/// Android net/ImportSource.kt): flowveil:// and v2rayng://install-sub → the link inside; happ://add/&lt;link&gt; → the link;
/// happ://crypt… is encrypted for Happ only and is not opened; anything else is passed on unchanged.
/// </summary>
public static class ImportSource
{
    public const string HappEncryptedMessage = "Это зашифрованная ссылка Happ — открыть её может только Happ. Попросите у провайдера обычную ссылку подписки (начинается с https://).";

    public abstract record Result
    {
        public sealed record Text(string Value) : Result;

        public sealed record HappEncrypted : Result;

        public sealed record Empty : Result;
    }

    public static Result Normalize(string? raw)
    {
        var t = raw?.Trim() ?? string.Empty;
        if (t.Length == 0)
        {
            return new Result.Empty();
        }
        if (t.StartsWith("happ://crypt", StringComparison.OrdinalIgnoreCase))
        {
            return new Result.HappEncrypted();
        }
        if (t.StartsWith("happ://add/", StringComparison.OrdinalIgnoreCase))
        {
            var inner = t["happ://add/".Length..].Trim();
            if (inner.Contains("%3A", StringComparison.OrdinalIgnoreCase) || inner.Contains("%2F", StringComparison.OrdinalIgnoreCase))
            {
                try
                {
                    inner = Uri.UnescapeDataString(inner);
                }
                catch
                {
                    // keep as is
                }
            }
            return inner.Length == 0 ? new Result.Empty() : new Result.Text(inner);
        }
        if (t.StartsWith("flowveil://", StringComparison.OrdinalIgnoreCase) || t.StartsWith("v2rayng://install-sub", StringComparison.OrdinalIgnoreCase))
        {
            var invite = InviteLink.Parse(t);
            return new Result.Text(invite?.Link ?? t);
        }
        return new Result.Text(t);
    }
}

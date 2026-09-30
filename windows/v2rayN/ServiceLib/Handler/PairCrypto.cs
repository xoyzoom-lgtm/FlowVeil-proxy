using System.Security.Cryptography;
using System.Text;

namespace ServiceLib.Handler;

/// <summary>
/// FlowVeil Pair payload encryption: AES-256-GCM, key = the 32 random bytes from the QR fragment (no KDF: it is already random),
/// AAD = the session id (ASCII), wire form = base64url(nonce[12] || ciphertext || tag[16]).
/// The Android side implements the same thing with javax.crypto; both are checked against one shared test vector.
/// </summary>
public static class PairCrypto
{
    public const int KeySize = 32;
    public const int NonceSize = 12;
    public const int TagSize = 16;

    public static string Encrypt(byte[] key, string sid, string plaintext, byte[]? nonce = null)
    {
        nonce ??= RandomNumberGenerator.GetBytes(NonceSize);
        var data = Encoding.UTF8.GetBytes(plaintext);
        var cipher = new byte[data.Length];
        var tag = new byte[TagSize];
        using var gcm = new AesGcm(key, TagSize);
        gcm.Encrypt(nonce, data, cipher, tag, Encoding.ASCII.GetBytes(sid));
        var all = new byte[NonceSize + cipher.Length + TagSize];
        nonce.CopyTo(all, 0);
        cipher.CopyTo(all, NonceSize);
        tag.CopyTo(all, NonceSize + cipher.Length);
        return B64.Encode(all);
    }

    /// <summary>The plaintext, or null when the data is broken, the key is wrong or the session id does not match.</summary>
    public static string? Decrypt(byte[] key, string sid, string wire)
    {
        try
        {
            var all = B64.Decode(wire);
            if (all == null || all.Length < NonceSize + TagSize)
            {
                return null;
            }
            var cipherLen = all.Length - NonceSize - TagSize;
            var plain = new byte[cipherLen];
            using var gcm = new AesGcm(key, TagSize);
            gcm.Decrypt(all.AsSpan(0, NonceSize), all.AsSpan(NonceSize, cipherLen), all.AsSpan(NonceSize + cipherLen, TagSize), plain, Encoding.ASCII.GetBytes(sid));
            return Encoding.UTF8.GetString(plain);
        }
        catch (CryptographicException)
        {
            return null;
        }
        catch (FormatException)
        {
            return null;
        }
    }

    public static class B64
    {
        public static string Encode(byte[] data) => Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

        public static byte[]? Decode(string text)
        {
            try
            {
                var s = text.Replace('-', '+').Replace('_', '/');
                s = s.PadRight(s.Length + (4 - s.Length % 4) % 4, '=');
                return Convert.FromBase64String(s);
            }
            catch (FormatException)
            {
                return null;
            }
        }
    }
}

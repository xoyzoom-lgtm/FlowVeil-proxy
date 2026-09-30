using System.Security.Cryptography;
using System.Text;

namespace ServiceLib.Handler;

public enum PairCheck
{
    Ok,
    Bad,
    Expired,
    Used,
    Locked,
}

/// <summary>One pairing attempt: ids and secrets, a lifetime, a single use and a cap on wrong guesses. Pure logic, no network.</summary>
public sealed class PairSession
{
    public const int MaxWrongAttempts = 5;
    public static readonly TimeSpan DefaultTtl = TimeSpan.FromMinutes(5);

    private readonly Func<DateTime> _now;
    private readonly object _lock = new();

    public string Sid { get; }
    public string Token { get; }
    public byte[] Key { get; }
    public string Code { get; }
    public DateTime ExpiresAt { get; }
    public bool Used { get; private set; }
    public int WrongAttempts { get; private set; }

    public PairSession(TimeSpan? ttl = null, Func<DateTime>? now = null)
    {
        _now = now ?? (() => DateTime.UtcNow);
        Sid = PairCrypto.B64.Encode(RandomNumberGenerator.GetBytes(16));
        Token = PairCrypto.B64.Encode(RandomNumberGenerator.GetBytes(16));
        Key = RandomNumberGenerator.GetBytes(PairCrypto.KeySize);
        Code = RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
        ExpiresAt = _now() + (ttl ?? DefaultTtl);
    }

    public bool IsLocked => WrongAttempts >= MaxWrongAttempts;

    public TimeSpan Remaining => ExpiresAt > _now() ? ExpiresAt - _now() : TimeSpan.Zero;

    /// <summary>Checks the token from the QR (or the typed code) and counts a wrong guess. It does not mark the session as used.</summary>
    public PairCheck Check(string? given, bool isCode = false)
    {
        lock (_lock)
        {
            if (Used)
            {
                return PairCheck.Used;
            }
            if (_now() >= ExpiresAt)
            {
                return PairCheck.Expired;
            }
            if (IsLocked)
            {
                return PairCheck.Locked;
            }
            var expected = Encoding.UTF8.GetBytes(isCode ? Code : Token);
            var actual = Encoding.UTF8.GetBytes(given ?? string.Empty);
            if (!CryptographicOperations.FixedTimeEquals(expected, actual))
            {
                WrongAttempts++;
                return IsLocked ? PairCheck.Locked : PairCheck.Bad;
            }
            return PairCheck.Ok;
        }
    }

    /// <summary>Marks the session as used once; false when someone else was faster.</summary>
    public bool TryConsume()
    {
        lock (_lock)
        {
            if (Used || _now() >= ExpiresAt || IsLocked)
            {
                return false;
            }
            Used = true;
            return true;
        }
    }

    public bool SidMatches(string? sid) =>
        sid != null && CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(Sid), Encoding.UTF8.GetBytes(sid));
}

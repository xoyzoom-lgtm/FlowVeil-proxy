using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace ServiceLib.Handler;

/// <summary>One server as the identity matching sees it: its id, a fingerprint of what makes it the server, and its name key.</summary>
public sealed record IdEntry(string Id, string Fingerprint, string NameKey);

/// <summary>
/// A subscription update writes every server again under new ids, and everything that points at ids (favorites, the measured
/// ping) loses its server. The new servers are matched to the old ones: first by fingerprint (protocol, address, port, user
/// id, transport, TLS/Reality, never the name), then what is left by a name that is unique on both sides.
/// The same rule as the Android app (net/ServerIdentity.kt), with the same test vectors.
/// </summary>
public static class ServerIdentity
{
    private const string Punct = "|-_.,:;()[]/#+&";

    /// <summary>16 hex characters of SHA-256 over the parts (trimmed, lower case, joined with '|').</summary>
    public static string Fingerprint(IEnumerable<string?> parts)
    {
        var text = string.Join("|", parts.Select(p => (p ?? string.Empty).Trim().ToLowerInvariant()));
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(text));
        return Convert.ToHexString(hash, 0, 8).ToLowerInvariant();
    }

    public static string NameKey(string? name)
    {
        if (string.IsNullOrWhiteSpace(name))
        {
            return string.Empty;
        }
        var sb = new StringBuilder();
        for (var i = 0; i < name.Length; i++)
        {
            var cp = char.ConvertToUtf32(name, i);
            if (char.IsSurrogatePair(name, i))
            {
                i++;
            }
            var s = char.ConvertFromUtf32(cp);
            var c = s[0];
            if (s.Length == 1 && (char.IsLetterOrDigit(c) || char.IsWhiteSpace(c) || Punct.Contains(c)))
            {
                sb.Append(char.ToLowerInvariant(c));
            }
        }
        return Regex.Replace(sb.ToString(), @"\s+", " ").Trim();
    }

    public static string FingerprintOf(ProfileItem p) => p.ConfigType == EConfigType.Custom
        ? Fingerprint(["custom", p.Address, p.Remarks])
        : Fingerprint([p.ConfigType.ToString(), p.Address, p.Port.ToString(System.Globalization.CultureInfo.InvariantCulture), p.Id, p.Password, p.Username,
            p.Network, p.HeaderType, p.RequestHost, p.Path, p.StreamSecurity, p.Sni, p.Flow, p.PublicKey, p.ShortId]);

    /// <summary>new id → old id. Each old id is used at most once; order decides between equal fingerprints.</summary>
    public static Dictionary<string, string> Reuse(IReadOnlyList<IdEntry> fresh, IReadOnlyList<IdEntry> old)
    {
        var result = new Dictionary<string, string>();
        var freeOld = old.DistinctBy(o => o.Id).ToList();
        foreach (var n in fresh)
        {
            var i = freeOld.FindIndex(o => o.Fingerprint.Length > 0 && o.Fingerprint == n.Fingerprint);
            if (i >= 0)
            {
                result[n.Id] = freeOld[i].Id;
                freeOld.RemoveAt(i);
            }
        }
        var leftNew = fresh.Where(n => !result.ContainsKey(n.Id) && n.NameKey.Length > 0).ToList();
        var newCount = leftNew.GroupBy(n => n.NameKey).ToDictionary(g => g.Key, g => g.Count());
        var oldCount = freeOld.Where(o => o.NameKey.Length > 0).GroupBy(o => o.NameKey).ToDictionary(g => g.Key, g => g.Count());
        foreach (var n in leftNew)
        {
            if (newCount.GetValueOrDefault(n.NameKey) != 1 || oldCount.GetValueOrDefault(n.NameKey) != 1)
            {
                continue;
            }
            var i = freeOld.FindIndex(o => o.NameKey == n.NameKey);
            if (i >= 0)
            {
                result[n.Id] = freeOld[i].Id;
                freeOld.RemoveAt(i);
            }
        }
        return result;
    }

    /// <summary>The favorites with every old id replaced by the id its server has now (order kept, ids without a match kept as they are).</summary>
    public static List<string> RemapIds(IEnumerable<string> ids, IReadOnlyDictionary<string, string> newToOld)
    {
        var oldToNew = newToOld.ToDictionary(kv => kv.Value, kv => kv.Key);
        return ids.Select(id => oldToNew.GetValueOrDefault(id, id)).Distinct().ToList();
    }
}

using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace ServiceLib.Handler;

/// <summary>
/// Release manifest check before an update is run (the same rules as Android net/UpdateManifest.kt, same test vectors in
/// shared/test-vectors/update). FlowVeil-manifest.json lists tag, build and files (size, SHA-256); the owner signs it offline
/// with ECDSA P-256 + SHA-256 (DER signature in FlowVeil-manifest.json.sig).
/// While <see cref="RequireSigned"/> is false an unsigned or missing manifest is not refused but the user is asked (default "No");
/// a bad signature or a wrong file is always refused.
/// </summary>
public static class UpdateManifest
{
    public const string ManifestFile = "FlowVeil-manifest.json";
    public const string SignatureFile = "FlowVeil-manifest.json.sig";

    /// <summary>Turned on two versions after the first signed release (OWNER-TODO).</summary>
    public const bool RequireSigned = false;

    public sealed record FileEntry(string Name, long Size, string Sha256);

    public sealed record Manifest(int Schema, string Tag, int Build, int MinBuild, List<FileEntry> Files);

    public enum Reason { Malformed, BadSignature, Unsigned, NotInManifest, SizeMismatch, HashMismatch, BuildMismatch, Rollback }

    public abstract record Result
    {
        public sealed record Verified(FileEntry Entry, bool Signed) : Result;

        public sealed record Rejected(Reason Reason) : Result;

        public sealed record Missing : Result;
    }

    private static string? Str(string text, string key)
    {
        var m = Regex.Match(text, $"\"{key}\"\\s*:\\s*\"([^\"]*)\"");
        return m.Success ? m.Groups[1].Value : null;
    }

    private static long? Num(string text, string key)
    {
        var m = Regex.Match(text, $"\"{key}\"\\s*:\\s*(\\d{{1,18}})");
        return m.Success && long.TryParse(m.Groups[1].Value, out var n) ? n : null;
    }

    public static Manifest? Parse(string text)
    {
        if (text.Length > 64 * 1024)
        {
            return null;
        }
        var schema = Num(text, "schema");
        var tag = Str(text, "tag");
        var build = Num(text, "build");
        var minBuild = Num(text, "minBuild") ?? 0;
        var block = Regex.Match(text, "\"files\"\\s*:\\s*\\[(.*)]", RegexOptions.Singleline);
        if (schema == null || tag == null || build == null || !block.Success)
        {
            return null;
        }
        var files = new List<FileEntry>();
        foreach (Match m in Regex.Matches(block.Groups[1].Value, "\\{[^{}]*}"))
        {
            var name = Str(m.Value, "name");
            var size = Num(m.Value, "size");
            var sha = Str(m.Value, "sha256")?.ToLowerInvariant();
            if (name == null || size == null || sha == null || !Regex.IsMatch(sha, "^[0-9a-f]{64}$"))
            {
                return null;
            }
            files.Add(new FileEntry(name, size.Value, sha));
        }
        if (schema != 1 || files.Count == 0 || minBuild > build || files.Select(f => f.Name).Distinct().Count() != files.Count)
        {
            return null;
        }
        return new Manifest((int)schema, tag, (int)build, (int)minBuild, files);
    }

    /// <summary>True when the DER <paramref name="signature"/> over the exact manifest bytes was made by one of the keys (SubjectPublicKeyInfo).</summary>
    public static bool SignatureOk(byte[] manifest, byte[] signature, IEnumerable<byte[]> publicKeys)
    {
        foreach (var key in publicKeys)
        {
            try
            {
                using var ecdsa = ECDsa.Create();
                ecdsa.ImportSubjectPublicKeyInfo(key, out _);
                if (ecdsa.VerifyData(manifest, signature, HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence))
                {
                    return true;
                }
            }
            catch
            {
                // a broken key or signature is just "not this key"
            }
        }
        return false;
    }

    public static Result Check(byte[]? manifest, byte[]? signature, IReadOnlyList<byte[]> publicKeys, string assetName, long size, string sha256,
        int? tagBuild, int currentBuild, bool requireSigned = RequireSigned)
    {
        if (manifest == null)
        {
            return requireSigned ? new Result.Rejected(Reason.Unsigned) : new Result.Missing();
        }
        var parsed = Parse(Encoding.UTF8.GetString(manifest));
        if (parsed == null)
        {
            return new Result.Rejected(Reason.Malformed);
        }
        var signed = false;
        if (signature is { Length: > 0 } && publicKeys.Count > 0)
        {
            if (!SignatureOk(manifest, signature, publicKeys))
            {
                return new Result.Rejected(Reason.BadSignature);
            }
            signed = true;
        }
        if (!signed && requireSigned)
        {
            return new Result.Rejected(Reason.Unsigned);
        }
        if (tagBuild != null && parsed.Build != tagBuild)
        {
            return new Result.Rejected(Reason.BuildMismatch);
        }
        if (parsed.Build <= currentBuild)
        {
            return new Result.Rejected(Reason.Rollback);
        }
        var entry = parsed.Files.FirstOrDefault(f => f.Name == assetName);
        if (entry == null)
        {
            return new Result.Rejected(Reason.NotInManifest);
        }
        if (entry.Size != size)
        {
            return new Result.Rejected(Reason.SizeMismatch);
        }
        if (!string.Equals(entry.Sha256, sha256, StringComparison.OrdinalIgnoreCase))
        {
            return new Result.Rejected(Reason.HashMismatch);
        }
        return new Result.Verified(entry, signed);
    }
}

/// <summary>
/// Public keys that sign release manifests (ECDSA P-256 SubjectPublicKeyInfo, Base64): the current key and a spare. Empty until
/// the owner makes the keys (OWNER-TODO.md); then updates count as unsigned and the user is asked. Same list as Android UpdateKeys.kt.
/// </summary>
public static class UpdateKeys
{
    public static readonly string[] Keys =
    [
    ];

    public static IReadOnlyList<byte[]> PublicKeys() => Keys.Select(k =>
    {
        try
        {
            return Convert.FromBase64String(k);
        }
        catch
        {
            return null;
        }
    }).OfType<byte[]>().ToList();
}

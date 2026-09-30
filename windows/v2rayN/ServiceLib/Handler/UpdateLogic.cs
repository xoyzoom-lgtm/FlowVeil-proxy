using System.Globalization;
using System.Text.RegularExpressions;

namespace ServiceLib.Handler;

/*
 * Update notifications, the pure part. The Android client has the same logic in net/UpdateLogic.kt; the tests on both sides
 * describe the same table: which release is "the newest for this device" (highest build number, never GitHub's "latest"
 * flag), when to bother the user (once per version, one reminder after 3 days, "Later", "Skip this version"), release notes as
 * safe plain text, SHA-256 sums, check throttling and back-off.
 */

public sealed record ReleaseAsset(string Name, string Url);

public sealed record ReleaseInfo(string Tag, bool Draft, bool Prerelease, string Body, IReadOnlyList<ReleaseAsset> Assets);

public sealed record UpdateCandidate(int Build, string Tag, string Notes, string AssetName, string AssetUrl, string? SumsUrl, string ReleaseUrl);

public static class UpdateLogic
{
    public const string SumsFile = "SHA256SUMS.txt";
    public const string InstallerAsset = "FlowVeil-Setup.exe";
    public const string PortableAsset = "FlowVeil-windows-portable.zip";

    /// <summary>"v0092" → 92, "build-92" → 92; null when the tag has no digits.</summary>
    public static int? BuildOf(string tag)
    {
        var digits = new string(tag.Where(char.IsDigit).ToArray());
        return digits.Length > 0 && int.TryParse(digits, NumberStyles.None, CultureInfo.InvariantCulture, out var n) ? n : null;
    }

    /// <summary>The newest published release that has one of <paramref name="wantedAssets"/>, if it is newer than the running build.</summary>
    public static UpdateCandidate? Pick(IEnumerable<ReleaseInfo> releases, int currentBuild, IReadOnlyList<string> wantedAssets, string repoUrl = "https://github.com/xoyzoom-lgtm/FlowVeil-proxy")
    {
        UpdateCandidate? best = null;
        foreach (var release in releases)
        {
            if (release.Draft || release.Prerelease || BuildOf(release.Tag) is not { } build)
            {
                continue;
            }
            if (build <= currentBuild || (best != null && build <= best.Build))
            {
                continue;
            }
            var asset = wantedAssets.Select(w => release.Assets.FirstOrDefault(a => a.Name == w)).FirstOrDefault(a => a != null);
            if (asset == null)
            {
                continue;
            }
            best = new UpdateCandidate(build, release.Tag, release.Body, asset.Name, asset.Url,
                release.Assets.FirstOrDefault(a => a.Name == SumsFile)?.Url, $"{repoUrl}/releases/tag/{release.Tag}");
        }
        return best;
    }
}

/// <summary>What we remember about notifying the user. Times are unix milliseconds.</summary>
public sealed record NotifyState(int LastNotifiedBuild = 0, long LastNotifiedAt = 0, bool Reminded = false, long SnoozedUntil = 0, int SkippedBuild = 0)
{
    public string Encode() => string.Join('|', LastNotifiedBuild, LastNotifiedAt, Reminded ? 1 : 0, SnoozedUntil, SkippedBuild);

    public static NotifyState Decode(string? text)
    {
        var p = text?.Split('|');
        if (p is not { Length: 5 })
        {
            return new NotifyState();
        }
        return new NotifyState(
            int.TryParse(p[0], out var a) ? a : 0,
            long.TryParse(p[1], out var b) ? b : 0,
            p[2] == "1",
            long.TryParse(p[3], out var c) ? c : 0,
            int.TryParse(p[4], out var d) ? d : 0);
    }
}

public static class NotifyPolicy
{
    public const long DayMs = 24L * 60 * 60 * 1000;
    public const long RemindAfterMs = 3 * DayMs;

    /// <summary>A quiet banner inside the app: any newer, not skipped, not snoozed version.</summary>
    public static bool BannerVisible(NotifyState s, int candidateBuild, int currentBuild, long now) =>
        candidateBuild > currentBuild && s.SkippedBuild != candidateBuild && now >= s.SnoozedUntil;

    /// <summary>A system notification: once per version, then one reminder after 3 days; never a downgrade, never a skipped or snoozed version.</summary>
    public static bool ShouldNotify(NotifyState s, int candidateBuild, int currentBuild, long now)
    {
        if (!BannerVisible(s, candidateBuild, currentBuild, now))
        {
            return false;
        }
        if (s.LastNotifiedBuild != candidateBuild)
        {
            return true;
        }
        return !s.Reminded && now - s.LastNotifiedAt >= RemindAfterMs;
    }

    public static NotifyState AfterNotified(NotifyState s, int build, long now) => s with { LastNotifiedBuild = build, LastNotifiedAt = now, Reminded = s.LastNotifiedBuild == build };

    public static NotifyState AfterLater(NotifyState s, long now) => s with { SnoozedUntil = now + RemindAfterMs };

    public static NotifyState AfterSkip(NotifyState s, int build) => s with { SkippedBuild = build };
}

/// <summary>How often to ask GitHub: at most once per interval, and after failures wait longer (silently).</summary>
public static class CheckThrottle
{
    public const long MinIntervalMs = 6L * 60 * 60 * 1000;
    public const long MaxBackoffMs = 24L * 60 * 60 * 1000;

    public static long DelayAfterFailures(int failures)
    {
        if (failures <= 0)
        {
            return MinIntervalMs;
        }
        var factor = 1L << Math.Min(failures, 4);
        return Math.Min(MinIntervalMs / 2 * factor, MaxBackoffMs);
    }

    public static bool MayCheck(long lastAttemptAt, int failures, long now) => now - lastAttemptAt >= DelayAfterFailures(failures);
}

/// <summary><c>SHA256SUMS.txt</c>: "&lt;hex&gt;  &lt;file&gt;" or "&lt;hex&gt; *&lt;file&gt;" per line.</summary>
public static partial class Sha256Sums
{
    [GeneratedRegex(@"^([0-9a-fA-F]{64})\s+\*?(\S.*?)\s*$")]
    private static partial Regex Line();

    public static Dictionary<string, string> Parse(string text)
    {
        var map = new Dictionary<string, string>();
        foreach (var line in text.Split('\n'))
        {
            var m = Line().Match(line.Trim());
            if (m.Success)
            {
                map[m.Groups[2].Value] = m.Groups[1].Value.ToLowerInvariant();
            }
        }
        return map;
    }

    /// <summary>true = matches, false = differs (do not install), null = the file is not listed (do not block).</summary>
    public static bool? Verify(IReadOnlyDictionary<string, string> sums, string fileName, string actualHex) =>
        sums.TryGetValue(fileName, out var expected) ? expected == actualHex.ToLowerInvariant() : null;
}

/// <summary>The release notes shown as plain text: no HTML, no links that could be pressed, bounded length.</summary>
public static partial class ReleaseNotes
{
    [GeneratedRegex("<!--.*?-->", RegexOptions.Singleline)]
    private static partial Regex Comment();

    [GeneratedRegex("<[^>]*>")]
    private static partial Regex Tag();

    [GeneratedRegex(@"!\[[^\]]*\]\([^)]*\)")]
    private static partial Regex Image();

    [GeneratedRegex(@"\[([^\]]*)\]\([^)]*\)")]
    private static partial Regex Link();

    [GeneratedRegex(@"^\s{0,3}#{1,6}\s*", RegexOptions.Multiline)]
    private static partial Regex Heading();

    [GeneratedRegex(@"^\s*[-*]\s+", RegexOptions.Multiline)]
    private static partial Regex Bullet();

    [GeneratedRegex("\n{3,}")]
    private static partial Regex ManyBlank();

    public static string Plain(string body, int maxChars = 700)
    {
        var text = body.Replace("\r\n", "\n");
        text = Comment().Replace(text, string.Empty);
        text = Tag().Replace(text, string.Empty);
        text = Image().Replace(text, string.Empty);
        text = Link().Replace(text, "$1");
        text = text.Replace("**", string.Empty).Replace("__", string.Empty).Replace("`", string.Empty);
        text = Heading().Replace(text, string.Empty);
        text = Bullet().Replace(text, "• ");
        text = string.Join('\n', text.Split('\n').Select(l => l.TrimEnd()));
        text = ManyBlank().Replace(text, "\n\n").Trim();
        if (text.Length <= maxChars)
        {
            return text;
        }
        var cut = text[..maxChars];
        var lineEnd = cut.LastIndexOf('\n');
        return (lineEnd > maxChars / 2 ? cut[..lineEnd] : cut).TrimEnd() + "…";
    }
}

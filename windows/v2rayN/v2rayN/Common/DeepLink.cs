using ServiceLib.Handler;

namespace v2rayN.Common;

/// <summary>
/// flowveil:// invite links: flowveil://add?url=&lt;encoded link&gt;, flowveil://add/&lt;link&gt;,
/// flowveil://install-sub?url=... The installer registers the scheme for the current user.
/// A second copy started by a link hands it to the running one through a small file.
/// </summary>
public static class DeepLink
{
    private const string PendingFile = "pending_import.txt";

    /// <summary>The invite (subscription or config link and the provider's title) inside a flowveil:// argument, or null.</summary>
    public static InviteLink.Invite? ParseInvite(IEnumerable<string> args)
    {
        var arg = args.FirstOrDefault(a => a.StartsWith("flowveil:", StringComparison.OrdinalIgnoreCase));
        return arg == null ? null : InviteLink.Parse(arg);
    }

    /// <summary>The subscription or config link inside a flowveil:// argument, or null.</summary>
    public static string? Parse(IEnumerable<string> args) => ParseInvite(args)?.Link;

    /// <summary>Keeps the whole flowveil:// argument so the title survives the hand-over.</summary>
    public static void SavePending(string arg)
    {
        try
        {
            File.WriteAllText(Utils.GetConfigPath(PendingFile), arg);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(DeepLink), ex);
        }
    }

    public static InviteLink.Invite? TakePending()
    {
        try
        {
            var path = Utils.GetConfigPath(PendingFile);
            if (!File.Exists(path))
            {
                return null;
            }
            var text = File.ReadAllText(path).Trim();
            File.Delete(path);
            return text.IsNullOrEmpty() ? null : InviteLink.Parse(text) ?? new InviteLink.Invite(text, null);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(DeepLink), ex);
            return null;
        }
    }
}

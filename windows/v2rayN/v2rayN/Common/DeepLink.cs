namespace v2rayN.Common;

/// <summary>
/// flowveil:// invite links: flowveil://add?url=&lt;encoded link&gt;, flowveil://add/&lt;link&gt;,
/// flowveil://install-sub?url=... The installer registers the scheme for the current user.
/// A second copy started by a link hands it to the running one through a small file.
/// </summary>
public static class DeepLink
{
    private const string PendingFile = "pending_import.txt";

    /// <summary>The subscription or config link inside a flowveil:// argument, or null.</summary>
    public static string? Parse(IEnumerable<string> args)
    {
        var arg = args.FirstOrDefault(a => a.StartsWith("flowveil:", StringComparison.OrdinalIgnoreCase));
        if (arg == null)
        {
            return null;
        }
        try
        {
            var rest = arg["flowveil:".Length..].TrimStart('/');
            var slash = rest.IndexOf('/');
            var question = rest.IndexOf('?');
            string? link = null;
            if (question >= 0 && (slash < 0 || question < slash))
            {
                var query = rest[(question + 1)..];
                foreach (var pair in query.Split('&'))
                {
                    if (pair.StartsWith("url=", StringComparison.OrdinalIgnoreCase))
                    {
                        link = Uri.UnescapeDataString(pair[4..]);
                        break;
                    }
                }
            }
            else if (slash >= 0)
            {
                link = rest[(slash + 1)..];
                if (link.Contains("%3A", StringComparison.OrdinalIgnoreCase))
                {
                    link = Uri.UnescapeDataString(link);
                }
            }
            return link.IsNullOrEmpty() ? null : link!.Trim();
        }
        catch
        {
            return null;
        }
    }

    public static void SavePending(string link)
    {
        try
        {
            File.WriteAllText(Utils.GetConfigPath(PendingFile), link);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(DeepLink), ex);
        }
    }

    public static string? TakePending()
    {
        try
        {
            var path = Utils.GetConfigPath(PendingFile);
            if (!File.Exists(path))
            {
                return null;
            }
            var link = File.ReadAllText(path).Trim();
            File.Delete(path);
            return link.IsNullOrEmpty() ? null : link;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(DeepLink), ex);
            return null;
        }
    }
}

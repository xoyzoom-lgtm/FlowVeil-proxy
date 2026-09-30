namespace ServiceLib.Handler;

/// <summary>The last failure of each subscription as a code (guiConfigs/sub_issue.json), for the diagnosis. The readable text stays in <see cref="SubscriptionHandler.LastError"/>.</summary>
public static class SubscriptionIssues
{
    private const string FileName = "sub_issue.json";
    private static readonly object Lock = new();
    private static Dictionary<string, string>? _cache;

    public static SubIssue Get(string? subId)
    {
        if (subId.IsNullOrEmpty())
        {
            return SubIssue.None;
        }
        lock (Lock)
        {
            return Load().TryGetValue(subId, out var name) && Enum.TryParse<SubIssue>(name, out var issue) ? issue : SubIssue.None;
        }
    }

    public static void Set(string subId, SubIssue issue)
    {
        lock (Lock)
        {
            var all = Load();
            if (issue == SubIssue.None)
            {
                if (!all.Remove(subId))
                {
                    return;
                }
            }
            else
            {
                all[subId] = issue.ToString();
            }
            try
            {
                File.WriteAllText(Utils.GetConfigPath(FileName), JsonUtils.Serialize(all));
            }
            catch (Exception ex)
            {
                Logging.SaveLog(nameof(SubscriptionIssues), ex);
            }
        }
    }

    private static Dictionary<string, string> Load()
    {
        if (_cache != null)
        {
            return _cache;
        }
        try
        {
            var path = Utils.GetConfigPath(FileName);
            _cache = File.Exists(path) ? JsonUtils.Deserialize<Dictionary<string, string>>(File.ReadAllText(path)) ?? [] : [];
        }
        catch
        {
            _cache = [];
        }
        return _cache;
    }
}

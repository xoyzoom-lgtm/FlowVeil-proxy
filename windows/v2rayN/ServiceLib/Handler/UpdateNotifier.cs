namespace ServiceLib.Handler;

/// <summary>
/// Tells the user about a new FlowVeil build without nagging: a check on start and every ~6 h while the app runs (also when
/// it sits in the tray), one tray notification per version, one reminder after 3 days, "Later" and "Skip this version". The decisions
/// are the pure <see cref="NotifyPolicy"/>/<see cref="CheckThrottle"/>. This is the only request the app makes on its own, only to the
/// GitHub releases API, without any device or account identifier; the switch in Settings turns it off. Nothing is installed without the
/// user pressing "Update".
/// </summary>
public static class UpdateNotifier
{
    private const string OffFile = "update_notify_off";
    private const string StateFile = "update_state";
    private const string CandidateFile = "update_candidate.json";
    private const string MetaFile = "update_meta";
    private static readonly SemaphoreSlim Gate = new(1, 1);

    /// <summary>Raised when the known update changed (banner, dot); on any thread.</summary>
    public static event Action? Changed;

    /// <summary>Raised once per version (and once more as a reminder) when a tray notification should be shown; on any thread.</summary>
    public static event Action<UpdateCandidate>? NotifyRequested;

    public static bool IsEnabled => !File.Exists(Utils.GetConfigPath(OffFile));

    public static void SetEnabled(bool on)
    {
        try
        {
            var path = Utils.GetConfigPath(OffFile);
            if (on)
            {
                File.Delete(path);
            }
            else
            {
                File.WriteAllText(path, "1");
            }
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(UpdateNotifier), ex);
        }
        Changed?.Invoke();
    }

    public static NotifyState State() => NotifyState.Decode(Read(StateFile));

    private static void SaveState(NotifyState state) => Write(StateFile, state.Encode());

    /// <summary>The newest known update for this installation, or null (also when the running build already is that new).</summary>
    public static UpdateCandidate? Cached()
    {
        var json = Read(CandidateFile);
        if (json.IsNullOrEmpty())
        {
            return null;
        }
        try
        {
            var candidate = JsonUtils.Deserialize<UpdateCandidate>(json);
            return candidate != null && candidate.Build > HuppUpdater.CurrentBuild() ? candidate : null;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>The update to show in the banner and the dot: newer, not skipped, not snoozed, and the switch is on.</summary>
    public static UpdateCandidate? BannerCandidate()
    {
        if (!IsEnabled)
        {
            return null;
        }
        var candidate = Cached();
        return candidate != null && NotifyPolicy.BannerVisible(State(), candidate.Build, HuppUpdater.CurrentBuild(), Now()) ? candidate : null;
    }

    public static void Remember(UpdateCandidate? candidate, string? etag)
    {
        Write(CandidateFile, candidate == null ? string.Empty : JsonUtils.Serialize(candidate, false));
        if (etag != null)
        {
            var (attempt, failures, _) = Meta();
            SaveMeta(attempt, failures, etag);
        }
        Changed?.Invoke();
    }

    public static void Later()
    {
        SaveState(NotifyPolicy.AfterLater(State(), Now()));
        Changed?.Invoke();
    }

    public static void Skip(int build)
    {
        SaveState(NotifyPolicy.AfterSkip(State(), build));
        Changed?.Invoke();
    }

    /// <summary>Asks GitHub when it is time (or <paramref name="force"/>) and asks for a tray notification when the policy says so. Silent on failure.</summary>
    public static async Task<UpdateCandidate?> CheckIfDueAsync(bool force = false)
    {
        if (!IsEnabled && !force)
        {
            return null;
        }
        if (!await Gate.WaitAsync(0))
        {
            return Cached();
        }
        try
        {
            var now = Now();
            var (lastAttempt, failures, etag) = Meta();
            if (!force && !CheckThrottle.MayCheck(lastAttempt, failures, now))
            {
                return Cached();
            }
            UpdateCandidate? candidate;
            switch (await HuppUpdater.FetchReleasesAsync(etag))
            {
                case HuppUpdater.Fetched.Ok ok:
                    candidate = HuppUpdater.CandidateFrom(ok.Json);
                    SaveMeta(now, 0, ok.ETag ?? etag);
                    Write(CandidateFile, candidate == null ? string.Empty : JsonUtils.Serialize(candidate, false));
                    break;

                case HuppUpdater.Fetched.NotModified:
                    SaveMeta(now, 0, etag);
                    candidate = Cached();
                    break;

                default:
                    SaveMeta(now, failures + 1, etag);
                    return Cached();
            }
            Changed?.Invoke();
            if (candidate != null)
            {
                var state = State();
                if (NotifyPolicy.ShouldNotify(state, candidate.Build, HuppUpdater.CurrentBuild(), now))
                {
                    SaveState(NotifyPolicy.AfterNotified(state, candidate.Build, now));
                    NotifyRequested?.Invoke(candidate);
                }
            }
            return candidate;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(UpdateNotifier), ex);
            return null;
        }
        finally
        {
            Gate.Release();
        }
    }

    private static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    private static (long LastAttempt, int Failures, string? ETag) Meta()
    {
        var p = Read(MetaFile)?.Split('|', 3);
        if (p is not { Length: 3 })
        {
            return (0, 0, null);
        }
        return (long.TryParse(p[0], out var a) ? a : 0, int.TryParse(p[1], out var f) ? f : 0, p[2].IsNullOrEmpty() ? null : p[2]);
    }

    private static void SaveMeta(long lastAttempt, int failures, string? etag) => Write(MetaFile, $"{lastAttempt}|{failures}|{etag}");

    private static string? Read(string name)
    {
        try
        {
            var path = Utils.GetConfigPath(name);
            return File.Exists(path) ? File.ReadAllText(path) : null;
        }
        catch
        {
            return null;
        }
    }

    private static void Write(string name, string content)
    {
        try
        {
            File.WriteAllText(Utils.GetConfigPath(name), content);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(UpdateNotifier), ex);
        }
    }
}

namespace ServiceLib.Handler;

/// <summary>
/// Moves subscription links from a v2rayN on the same computer: its database is opened read-only
/// (a copy of it, so a running v2rayN is not disturbed) and only the links are taken. Servers,
/// settings and everything of FlowVeil stay as they are. Happ keeps its data in a format we do not
/// read, and its encrypted links are not opened: those come in by link.
/// </summary>
public static class SubMigration
{
    private const string DbName = "guiNDB.db";

    /// <summary>The database of another v2rayN: a running one first, then the usual places. Null when none is found.</summary>
    public static string? FindV2rayNDb()
    {
        var own = SafeFull(Utils.GetConfigPath(DbName));
        foreach (var dir in CandidateDirs())
        {
            try
            {
                var file = Path.Combine(dir, "guiConfigs", DbName);
                if (File.Exists(file) && !string.Equals(SafeFull(file), own, StringComparison.OrdinalIgnoreCase))
                {
                    return file;
                }
            }
            catch
            {
                // an unreadable folder is simply skipped
            }
        }
        return null;
    }

    private static string SafeFull(string path)
    {
        try
        {
            return Path.GetFullPath(path);
        }
        catch
        {
            return path;
        }
    }

    private static IEnumerable<string> CandidateDirs()
    {
        foreach (var name in new[] { "v2rayN", "v2rayN.Desktop" })
        {
            Process[] processes;
            try
            {
                processes = Process.GetProcessesByName(name);
            }
            catch
            {
                continue;
            }
            foreach (var p in processes)
            {
                string? dir = null;
                try
                {
                    dir = Path.GetDirectoryName(p.MainModule?.FileName);
                }
                catch
                {
                    // no access to another process: skip it
                }
                if (!dir.IsNullOrEmpty())
                {
                    yield return dir!;
                }
            }
        }

        var profile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var roots = new List<string>
        {
            Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
            Path.Combine(profile, "Downloads"),
            Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
            @"C:\",
            @"D:\",
        };
        foreach (var root in roots.Where(r => !r.IsNullOrEmpty() && Directory.Exists(r)).Distinct())
        {
            yield return root;
            foreach (var level1 in Sub(root))
            {
                yield return level1;
                foreach (var level2 in Sub(level1))
                {
                    yield return level2;
                }
            }
        }
    }

    private static IEnumerable<string> Sub(string dir)
    {
        try
        {
            return Directory.EnumerateDirectories(dir, "*", new EnumerationOptions { IgnoreInaccessible = true, RecurseSubdirectories = false }).Take(300).ToList();
        }
        catch
        {
            return [];
        }
    }

    /// <summary>Subscription links (http/https, no repeats) stored in the v2rayN database at [dbPath].</summary>
    public static List<string> ReadUrls(string dbPath)
    {
        var copy = Path.Combine(Path.GetTempPath(), $"flowveil-migrate-{Guid.NewGuid():N}.db");
        try
        {
            File.Copy(dbPath, copy, true);
            foreach (var extra in new[] { "-wal", "-shm" })
            {
                if (File.Exists(dbPath + extra))
                {
                    File.Copy(dbPath + extra, copy + extra, true);
                }
            }
            using var db = new SQLiteConnection(copy, SQLiteOpenFlags.ReadOnly);
            return db.Query<SubItem>("SELECT * FROM SubItem")
                .Select(s => s.Url?.Trim() ?? string.Empty)
                .Where(u => u.StartsWith("http", StringComparison.OrdinalIgnoreCase))
                .Distinct()
                .ToList();
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(SubMigration), ex);
            return [];
        }
        finally
        {
            foreach (var f in new[] { copy, copy + "-wal", copy + "-shm" })
            {
                try
                {
                    File.Delete(f);
                }
                catch
                {
                    // a leftover temp file is harmless
                }
            }
        }
    }

    /// <summary>Adds the links not yet present. Returns how many were found and how many are new.</summary>
    public static async Task<(int Found, int Added)> ImportSubscriptions(Config config, string dbPath)
    {
        var urls = ReadUrls(dbPath);
        var added = 0;
        foreach (var url in urls)
        {
            var exists = await SQLiteHelper.Instance.TableAsync<SubItem>().CountAsync(e => e.Url == url) > 0;
            if (exists)
            {
                continue;
            }
            if (await ConfigHandler.AddSubItem(config, url) == 0)
            {
                added++;
            }
        }
        return (urls.Count, added);
    }
}

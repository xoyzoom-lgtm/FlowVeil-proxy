namespace ServiceLib.Common;

/// <summary>
/// TUN needs administrator rights on Windows (it creates a network adapter). Like apps that ask
/// once and never again, FlowVeil registers itself once in Task Scheduler with the highest
/// privileges the first time it runs elevated; afterwards it restarts elevated through that task
/// without another UAC prompt.
/// </summary>
public static class ElevatedTask
{
    private const string TaskName = "FlowVeil (TUN)";
    private const string TunPendingFile = "tun_pending";

    private static string Schtasks => Path.Combine(Environment.SystemDirectory, "schtasks.exe");

    public static bool Exists() => Utils.IsWindows() && Run($"/query /tn \"{TaskName}\"") == 0;

    /// <summary>Starts FlowVeil elevated through the task; false when the task is missing or failed.</summary>
    public static bool Start() => Exists() && Run($"/run /tn \"{TaskName}\"") == 0;

    /// <summary>Creates or refreshes the task for the current exe; only works while elevated.</summary>
    public static void EnsureRegistered()
    {
        if (!Utils.IsWindows() || !Utils.IsAdministrator())
        {
            return;
        }
        var xmlPath = Path.Combine(Path.GetTempPath(), "flowveil-task.xml");
        try
        {
            var exe = System.Security.SecurityElement.Escape(Utils.GetExePath());
            var dir = System.Security.SecurityElement.Escape(Utils.StartupPath());
            var xml = $"""
                <?xml version="1.0" encoding="UTF-16"?>
                <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
                  <RegistrationInfo><Description>Starts FlowVeil with the rights TUN mode needs, without asking every time.</Description></RegistrationInfo>
                  <Principals><Principal id="Author"><LogonType>InteractiveToken</LogonType><RunLevel>HighestAvailable</RunLevel></Principal></Principals>
                  <Settings>
                    <MultipleInstancesPolicy>Parallel</MultipleInstancesPolicy>
                    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
                    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
                    <AllowStartOnDemand>true</AllowStartOnDemand>
                    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
                    <Priority>5</Priority>
                  </Settings>
                  <Actions Context="Author"><Exec><Command>{exe}</Command><Arguments>{Global.RebootAs}</Arguments><WorkingDirectory>{dir}</WorkingDirectory></Exec></Actions>
                </Task>
                """;
            File.WriteAllText(xmlPath, xml, Encoding.Unicode);
            Run($"/create /f /tn \"{TaskName}\" /xml \"{xmlPath}\"");
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(ElevatedTask), ex);
        }
        finally
        {
            try { File.Delete(xmlPath); } catch { }
        }
    }

    /// <summary>Remembers that the user switched TUN on, so the elevated restart continues in TUN.</summary>
    public static void MarkTunPending()
    {
        try { File.WriteAllText(Utils.GetConfigPath(TunPendingFile), "1"); } catch { }
    }

    public static bool TakeTunPending()
    {
        var path = Utils.GetConfigPath(TunPendingFile);
        if (!File.Exists(path))
        {
            return false;
        }
        try { File.Delete(path); } catch { }
        return true;
    }

    private static int Run(string args)
    {
        try
        {
            using var process = Process.Start(new ProcessStartInfo(Schtasks, args)
            {
                CreateNoWindow = true,
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            });
            if (process == null)
            {
                return -1;
            }
            process.WaitForExit(10_000);
            return process.HasExited ? process.ExitCode : -1;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(ElevatedTask), ex);
            return -1;
        }
    }
}

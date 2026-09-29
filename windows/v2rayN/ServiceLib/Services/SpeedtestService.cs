using System.Net.Sockets;
using System.Text.Json.Nodes;
using ServiceLib.UdpTest;

namespace ServiceLib.Services;

public class SpeedtestService(Config config, Func<SpeedTestResult, Task> updateFunc)
{
    private static readonly string _tag = "SpeedtestService";
    private readonly Config? _config = config;
    private readonly Func<SpeedTestResult, Task>? _updateFunc = updateFunc;
    private readonly Lock _runLock = new();
    private readonly List<CancellationTokenSource> _runCtsList = [];
    private readonly int _speedTestPageSize = config.SpeedTestItem.SpeedTestPageSize ?? Global.SpeedTestPageSize;
    private readonly TimeSpan _delayInterval = TimeSpan.FromSeconds(config.SpeedTestItem.SpeedTestDelayInterval ?? 1);

    public Task RunLoop(ESpeedActionType actionType, List<ProfileItem> selecteds, CancellationToken ct = default)
    {
        CancellationTokenSource runCts;

        lock (_runLock)
        {
            runCts = CancellationTokenSource.CreateLinkedTokenSource(ct);

            _runCtsList.Add(runCts);
        }

        return RunLoopAsync(actionType, selecteds, runCts);
    }

    public void ExitLoop()
    {
        var counter = 0;
        List<CancellationTokenSource> listToCancel;

        lock (_runLock)
        {
            listToCancel = _runCtsList.ToList();
            counter = listToCancel.Count;
        }

        foreach (var cts in listToCancel)
        {
            try
            {
                cts.Cancel();
            }
            catch (ObjectDisposedException)
            {
                // Ignored
            }
        }

        if (counter > 0)
        {
            _ = UpdateFunc("", ResUI.SpeedtestingStop);
        }
    }

    private async Task RunLoopAsync(ESpeedActionType actionType, List<ProfileItem> selecteds, CancellationTokenSource runCts)
    {
        try
        {
            await RunAsync(actionType, selecteds, runCts.Token);
        }
        catch (OperationCanceledException) when (runCts.IsCancellationRequested)
        {
            // Ignored
        }
        finally
        {
            try
            {
                await ProfileExManager.Instance.SaveTo();
            }
            finally
            {
                await UpdateFunc("", ResUI.SpeedtestingCompleted);
            }

            lock (_runLock)
            {
                _runCtsList.Remove(runCts);
            }

            runCts.Dispose();
        }
    }

    private async Task RunAsync(ESpeedActionType actionType, List<ProfileItem> selecteds, CancellationToken ct = default)
    {
        var lstSelected = await GetClearItem(actionType, selecteds);
        var completedIds = new ConcurrentDictionary<string, byte>();
        // FlowVeil: full JSON (custom) profiles are tested on their own, the batch code skips them.
        var customs = actionType is ESpeedActionType.Realping or ESpeedActionType.Mixedtest or ESpeedActionType.Speedtest
            ? selecteds.Where(it => it.ConfigType == EConfigType.Custom && it.IndexId.IsNotEmpty()).ToList()
            : [];

        try
        {
            if (customs.Count > 0)
            {
                foreach (var it in customs)
                {
                    await UpdateFunc(it.IndexId, ResUI.Speedtesting);
                }
                await RunCustomRealPingAsync(customs, completedIds, ct);
            }
            if (lstSelected.Count == 0)
            {
                return;
            }
            switch (actionType)
            {
                case ESpeedActionType.Tcping:
                    {
                        // Hysteria 2 and TUIC listen on UDP: a TCP connect would time out on a healthy server.
                        var quicBased = lstSelected.Where(it => it.ConfigType is EConfigType.Hysteria2 or EConfigType.TUIC).ToList();
                        var tcpBased = lstSelected.Where(it => !quicBased.Contains(it)).ToList();
                        if (tcpBased.Count > 0)
                        {
                            await RunTcpingAsync(tcpBased, completedIds, ct);
                        }
                        if (quicBased.Count > 0)
                        {
                            await RunRealPingBatchAsync(quicBased, completedIds, 0, ct);
                        }
                        break;
                    }

                case ESpeedActionType.Realping:
                    await RunRealPingBatchAsync(lstSelected, completedIds, 0, ct);
                    break;

                case ESpeedActionType.UdpTest:
                    await RunUdpTestBatchAsync(lstSelected, completedIds, 0, ct);
                    break;

                case ESpeedActionType.Speedtest:
                    await RunMixedTestAsync(lstSelected, completedIds, 1, true, ct);
                    break;

                case ESpeedActionType.Mixedtest:
                    await RunMixedTestAsync(lstSelected, completedIds, _config.SpeedTestItem.MixedConcurrencyCount, true,
                        ct);
                    break;
            }
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            _ = UpdateFunc("", ResUI.SpeedtestingStop);
            await SetTestResultAsync(lstSelected.Where(it => !completedIds.ContainsKey(it.IndexId)).ToList(),
                actionType, ResUI.SpeedtestingSkip).ConfigureAwait(false);
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
            _ = UpdateFunc("", ex.Message);
        }
    }

    private async Task<List<ServerTestItem>> GetClearItem(ESpeedActionType actionType, List<ProfileItem> selecteds)
    {
        var lstSelected = new List<ServerTestItem>(selecteds.Count);
        var ids = selecteds.Where(it => !it.IndexId.IsNullOrEmpty()
            && it.ConfigType != EConfigType.Custom
            && (it.ConfigType.IsComplexType() || it.Port > 0))
            .Select(it => it.IndexId)
            .ToList();
        var profileMap = await AppManager.Instance.GetProfileItemsByIndexIdsAsMap(ids);
        for (var i = 0; i < selecteds.Count; i++)
        {
            var it = selecteds[i];
            if (it.ConfigType == EConfigType.Custom)
            {
                continue;
            }

            if (!it.ConfigType.IsComplexType() && it.Port <= 0)
            {
                continue;
            }

            var profile = profileMap.GetValueOrDefault(it.IndexId, it);
            lstSelected.Add(new ServerTestItem()
            {
                IndexId = it.IndexId,
                Address = it.Address,
                Port = it.Port,
                ConfigType = it.ConfigType,
                QueueNum = i,
                Profile = profile,
                CoreType = AppManager.Instance.GetCoreType(profile, it.ConfigType),
            });
        }

        //clear test result
        await SetTestResultAsync(lstSelected, actionType, ResUI.Speedtesting).ConfigureAwait(false);

        if (lstSelected.Count > 1 && (actionType == ESpeedActionType.Speedtest || actionType == ESpeedActionType.Mixedtest))
        {
            NoticeManager.Instance.Enqueue(ResUI.SpeedtestingPressEscToExit);
        }

        return lstSelected;
    }

    private async Task SetTestResultAsync(List<ServerTestItem> lstSelected, ESpeedActionType actionType, string message)
    {
        foreach (var it in lstSelected)
        {
            switch (actionType)
            {
                case ESpeedActionType.Tcping:
                case ESpeedActionType.Realping:
                case ESpeedActionType.UdpTest:
                    await UpdateFunc(it.IndexId, message, "");
                    break;

                case ESpeedActionType.Speedtest:
                    await UpdateFunc(it.IndexId, "", message);
                    break;

                case ESpeedActionType.Mixedtest:
                    await UpdateFunc(it.IndexId, message, message);
                    break;
            }
        }
    }

    private async Task RunTcpingAsync(List<ServerTestItem> selecteds,
        ConcurrentDictionary<string, byte> completedIds, CancellationToken ct = default)
    {
        var pageSize = Math.Min(selecteds.Count, _speedTestPageSize);
        var lstBatch = GetTestBatchItem(selecteds, pageSize);

        foreach (var lst in lstBatch)
        {
            ct.ThrowIfCancellationRequested();

            var parallelOptions = new ParallelOptions
            {
                MaxDegreeOfParallelism = lst.Count,
                CancellationToken = ct,
            };

            await Parallel.ForEachAsync(lst, parallelOptions, async (item, innerCt) =>
            {
                try
                {
                    var responseTime = await GetTcpingTime(item.Address, item.Port, innerCt);

                    ProfileExManager.Instance.SetTestDelay(item.IndexId, responseTime);
                    await UpdateFunc(item.IndexId, responseTime.ToString());
                    completedIds.TryAdd(item.IndexId, 0);
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested)
                {
                    throw;
                }
                catch (Exception ex)
                {
                    Logging.SaveLog(_tag, ex);
                }
            });

            await Task.Delay(_delayInterval, ct);
        }
    }

    private async Task RunRealPingBatchAsync(List<ServerTestItem> lstSelected,
        ConcurrentDictionary<string, byte> completedIds, int pageSize = 0, CancellationToken ct = default)
    {
        if (pageSize <= 0)
        {
            pageSize = Math.Min(lstSelected.Count, _speedTestPageSize);
        }
        var lstTest = GetTestBatchItem(lstSelected, pageSize);

        List<ServerTestItem> lstFailed = [];
        foreach (var lst in lstTest)
        {
            var ret = await RunRealPingAsync(lst, completedIds, ct);
            if (ret == false)
            {
                lstFailed.AddRange(lst);
            }
            await Task.Delay(_delayInterval, ct);
        }

        //Retest the failed part
        var pageSizeNext = pageSize / 2;
        if (lstFailed.Count > 0 && pageSizeNext > 0)
        {
            ct.ThrowIfCancellationRequested();

            await UpdateFunc("", string.Format(ResUI.SpeedtestingTestFailedPart, lstFailed.Count));

            if (pageSizeNext > _config.SpeedTestItem.MixedConcurrencyCount)
            {
                await RunRealPingBatchAsync(lstFailed, completedIds, pageSizeNext, ct);
            }
            else
            {
                await RunMixedTestAsync(lstSelected, completedIds, _config.SpeedTestItem.MixedConcurrencyCount, false, ct);
            }
        }
    }

    private async Task<bool> RunRealPingAsync(List<ServerTestItem> selecteds,
        ConcurrentDictionary<string, byte> completedIds, CancellationToken ct = default)
    {
        ProcessService processService = null;
        try
        {
            processService = await CoreManager.Instance.LoadCoreConfigSpeedtest(selecteds);
            if (processService is null)
            {
                return false;
            }
            await Task.Delay(1000, ct);

            var parallelOptions = new ParallelOptions
            {
                MaxDegreeOfParallelism = selecteds.Count,
                CancellationToken = ct,
            };

            await Parallel.ForEachAsync(selecteds, parallelOptions, async (it, innerCt) =>
            {
                if (!it.AllowTest)
                {
                    await UpdateFunc(it.IndexId, ResUI.SpeedtestingSkip);
                    completedIds.TryAdd(it.IndexId, 0);
                    return;
                }

                try
                {
                    await DoRealPing(it, completedIds, innerCt);
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested)
                {
                    throw;
                }
                catch (Exception ex)
                {
                    Logging.SaveLog(_tag, ex);
                }
            });
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
        finally
        {
            if (processService != null)
            {
                await processService?.StopAsync();
            }
        }
        return true;
    }

    private async Task RunUdpTestBatchAsync(List<ServerTestItem> lstSelected,
        ConcurrentDictionary<string, byte> completedIds, int pageSize = 0, CancellationToken ct = default)
    {
        if (pageSize <= 0)
        {
            pageSize = Math.Min(lstSelected.Count, _speedTestPageSize);
        }
        var lstTest = GetTestBatchItem(lstSelected, pageSize);

        List<ServerTestItem> lstFailed = [];
        foreach (var lst in lstTest)
        {
            var ret = await RunUdpTestAsync(lst, completedIds, ct);
            if (ret == false)
            {
                lstFailed.AddRange(lst);
            }
            await Task.Delay(_delayInterval, ct);
        }

        //Retest the failed part
        if (lstFailed.Count > 0)
        {
            ct.ThrowIfCancellationRequested();

            await UpdateFunc("", string.Format(ResUI.SpeedtestingTestFailedPart, lstFailed.Count));

            await RunUdpTestAsync(lstFailed, completedIds, ct);
        }
    }

    private async Task<bool> RunUdpTestAsync(List<ServerTestItem> selecteds,
        ConcurrentDictionary<string, byte> completedIds, CancellationToken ct = default)
    {
        ProcessService processService = null;
        try
        {
            processService = await CoreManager.Instance.LoadCoreConfigSpeedtest(selecteds);
            if (processService is null)
            {
                return false;
            }
            await Task.Delay(1000, ct);

            var parallelOptions = new ParallelOptions
            {
                MaxDegreeOfParallelism = selecteds.Count,
                CancellationToken = ct,
            };

            await Parallel.ForEachAsync(selecteds, parallelOptions, async (it, innerCt) =>
            {
                if (!it.AllowTest)
                {
                    await UpdateFunc(it.IndexId, ResUI.SpeedtestingSkip);
                    completedIds.TryAdd(it.IndexId, 0);
                    return;
                }

                try
                {
                    await DoUdpTest(it, completedIds, innerCt);
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested)
                {
                    throw;
                }
                catch (Exception ex)
                {
                    Logging.SaveLog(_tag, ex);
                }
            });
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            throw;
        }
        catch (Exception ex)
        {
            Logging.SaveLog(_tag, ex);
        }
        finally
        {
            if (processService != null)
            {
                await processService?.StopAsync();
            }
        }
        return true;
    }

    private async Task RunMixedTestAsync(List<ServerTestItem> selecteds,
        ConcurrentDictionary<string, byte> completedIds, int concurrencyCount, bool blSpeedTest,
        CancellationToken ct = default)
    {
        var downloadHandle = new DownloadService();

        var parallelOptions = new ParallelOptions
        {
            MaxDegreeOfParallelism = concurrencyCount,
            CancellationToken = ct,
        };

        await Parallel.ForEachAsync(selecteds, parallelOptions, async (it, innerCt) =>
        {
            innerCt.ThrowIfCancellationRequested();

            ProcessService processService = null;
            try
            {
                processService = await CoreManager.Instance.LoadCoreConfigSpeedtest(it);
                if (processService is null)
                {
                    await UpdateFunc(it.IndexId, "", ResUI.FailedToRunCore);
                    return;
                }

                await Task.Delay(1000, innerCt);

                var delay = await DoRealPing(it, completedIds, innerCt);
                if (blSpeedTest)
                {
                    if (delay > 0)
                    {
                        await DoSpeedTest(downloadHandle, it, completedIds, innerCt);
                    }
                    else
                    {
                        await UpdateFunc(it.IndexId, "", ResUI.SpeedtestingSkip);
                    }
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                Logging.SaveLog(_tag, ex);
            }
            finally
            {
                if (processService != null)
                {
                    await processService.StopAsync();
                }
            }
        });
    }

    /// <summary>
    /// Real ping for custom JSON profiles: only their outbounds (main proxy first) are started
    /// behind a local SOCKS inbound on a free port, a few profiles at a time.
    /// </summary>
    private async Task RunCustomRealPingAsync(List<ProfileItem> customs, ConcurrentDictionary<string, byte> completedIds, CancellationToken ct)
    {
        var parallelOptions = new ParallelOptions { MaxDegreeOfParallelism = 4, CancellationToken = ct };
        await Parallel.ForEachAsync(customs, parallelOptions, async (item, innerCt) =>
        {
            ProcessService? process = null;
            string? fileName = null;
            try
            {
                var profile = await AppManager.Instance.GetProfileItem(item.IndexId) ?? item;
                var port = FreeLocalPort();
                var (json, coreType) = BuildCustomSpeedtestConfig(profile, port);
                if (json == null)
                {
                    await UpdateFunc(item.IndexId, ResUI.SpeedtestingSkip);
                    return;
                }
                fileName = string.Format(Global.CoreSpeedtestConfigFileName, Utils.GetGuid(false));
                await File.WriteAllTextAsync(Utils.GetBinConfigPath(fileName), json, innerCt);
                process = await CoreManager.Instance.RunSpeedtestConfigFile(fileName, coreType);
                if (process == null)
                {
                    await UpdateFunc(item.IndexId, ResUI.FailedToRunCore);
                    return;
                }
                await Task.Delay(1000, innerCt);
                await DoRealPing(new ServerTestItem { IndexId = item.IndexId, Port = port, ConfigType = EConfigType.Custom, AllowTest = true, Profile = profile, CoreType = coreType }, completedIds, innerCt);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex)
            {
                Logging.SaveLog(_tag, ex);
                await UpdateFunc(item.IndexId, "-1");
            }
            finally
            {
                if (process != null)
                {
                    await process.StopAsync();
                }
                if (fileName != null)
                {
                    try { File.Delete(Utils.GetBinConfigPath(fileName)); } catch { }
                }
            }
        });
    }

    private static int FreeLocalPort()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        listener.Stop();
        return port;
    }

    /// <summary>Xray or sing-box config with the profile's outbounds and a SOCKS inbound on <paramref name="port"/>.</summary>
    private static (string? Json, ECoreType CoreType) BuildCustomSpeedtestConfig(ProfileItem profile, int port)
    {
        var path = profile.Address;
        if (path.IsNullOrEmpty())
        {
            return (null, ECoreType.Xray);
        }
        if (!File.Exists(path))
        {
            path = Utils.GetConfigPath(path);
        }
        if (!File.Exists(path) || JsonNode.Parse(File.ReadAllText(path)) is not JsonObject root || root["outbounds"] is not JsonArray outbounds)
        {
            return (null, ECoreType.Xray);
        }
        var all = outbounds.OfType<JsonObject>().ToList();
        var isSingBox = all.Any(o => o["type"] != null) && !all.Any(o => o["protocol"] != null);
        string Kind(JsonObject o) => (o[isSingBox ? "type" : "protocol"]?.GetValue<string>() ?? "").ToLowerInvariant();
        var service = new HashSet<string> { "freedom", "blackhole", "dns", "loopback", "direct", "block", "selector", "urltest" };
        var proxies = all.Where(o => !service.Contains(Kind(o))).ToList();
        var main = proxies.FirstOrDefault(o => o["tag"]?.GetValue<string>() == "proxy") ?? proxies.FirstOrDefault();
        if (main == null)
        {
            return (null, ECoreType.Xray);
        }
        var ordered = new JsonArray(main.DeepClone());
        foreach (var o in all.Where(o => !ReferenceEquals(o, main)))
        {
            ordered.Add(o.DeepClone());
        }
        JsonObject config;
        if (isSingBox)
        {
            config = new JsonObject
            {
                ["log"] = new JsonObject { ["disabled"] = true },
                ["inbounds"] = new JsonArray(new JsonObject { ["type"] = "socks", ["tag"] = "socks-in", ["listen"] = "127.0.0.1", ["listen_port"] = port }),
                ["outbounds"] = ordered,
            };
        }
        else
        {
            config = new JsonObject
            {
                ["log"] = new JsonObject { ["loglevel"] = "none" },
                ["inbounds"] = new JsonArray(new JsonObject
                {
                    ["tag"] = "socks-in",
                    ["listen"] = "127.0.0.1",
                    ["port"] = port,
                    ["protocol"] = "socks",
                    ["settings"] = new JsonObject { ["udp"] = true },
                }),
                ["outbounds"] = ordered,
            };
        }
        return (config.ToJsonString(), isSingBox ? ECoreType.sing_box : ECoreType.Xray);
    }

    private async Task<int> DoRealPing(ServerTestItem it,
        ConcurrentDictionary<string, byte> completedIds, CancellationToken ct = default)
    {
        var webProxy = new WebProxy($"socks5://{Global.Loopback}:{it.Port}");
        var responseTime = await ConnectionHandler.GetRealPingTime(webProxy, ct);

        ProfileExManager.Instance.SetTestDelay(it.IndexId, responseTime);
        await UpdateFunc(it.IndexId, responseTime.ToString());

        if (!_config.UiItem.HideColumnIpInfo && responseTime > 0)
        {
            var ipInfo = await ConnectionHandler.GetIPInfo(webProxy, ct);
            var ipStr = ipInfo?.ToString() ?? Global.None;
            ProfileExManager.Instance.SetTestIpInfo(it.IndexId, ipStr);
            await UpdateIpInfoFunc(it.IndexId, ipStr);
        }
        else
        {
            await UpdateIpInfoFunc(it.IndexId, ResUI.SpeedtestingSkip);
        }

        completedIds.TryAdd(it.IndexId, 0);
        return responseTime;
    }

    private async Task DoSpeedTest(DownloadService downloadHandle, ServerTestItem it,
        ConcurrentDictionary<string, byte> completedIds, CancellationToken ct = default)
    {
        await UpdateFunc(it.IndexId, "", ResUI.Speedtesting);

        var webProxy = new WebProxy($"socks5://{Global.Loopback}:{it.Port}");
        var url = _config.SpeedTestItem.SpeedTestUrl;
        var timeout = _config.SpeedTestItem.SpeedTestTimeout;
        using var timeoutCts = new CancellationTokenSource(TimeSpan.FromSeconds(timeout));
        using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(ct, timeoutCts.Token);
        var linkedCt = linkedCts.Token;
        await downloadHandle.DownloadDataAsync(url, webProxy, async (success, msg) =>
        {
            decimal.TryParse(msg, out var dec);
            if (dec > 0)
            {
                ProfileExManager.Instance.SetTestSpeed(it.IndexId, dec);
            }
            await UpdateFunc(it.IndexId, "", msg);
        }, linkedCt);
        completedIds.TryAdd(it.IndexId, 0);
    }

    private async Task<int> DoUdpTest(ServerTestItem it,
        ConcurrentDictionary<string, byte> completedIds, CancellationToken ct = default)
    {
        var udpService = UdpTestService.CreateFromTarget(_config?.SpeedTestItem.UdpTestTarget, out var udpTestUrl);
        var responseTime = (int)(await udpService.SendUdpRequestAsync(udpTestUrl, it.Port, ct)).TotalMilliseconds;

        ProfileExManager.Instance.SetTestDelay(it.IndexId, responseTime);
        await UpdateFunc(it.IndexId, responseTime.ToString());
        completedIds.TryAdd(it.IndexId, 0);
        return responseTime;
    }

    private async Task<int> GetTcpingTime(string? url, int port, CancellationToken ct = default)
    {
        var responseTime = -1;

        if (url.IsNullOrEmpty() || port <= 0)
        {
            return responseTime;
        }

        if (!IPAddress.TryParse(url, out var ipAddress))
        {
            var ipHostInfo = await Dns.GetHostEntryAsync(url, ct);
            ipAddress = ipHostInfo.AddressList.First();
        }

        IPEndPoint endPoint = new(ipAddress, port);
        using Socket clientSocket = new(endPoint.AddressFamily, SocketType.Stream, ProtocolType.Tcp);

        var timer = Stopwatch.StartNew();
        try
        {
            using var timeoutCts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            using var linkedCts = CancellationTokenSource.CreateLinkedTokenSource(ct, timeoutCts.Token);
            await clientSocket.ConnectAsync(endPoint, linkedCts.Token).ConfigureAwait(false);
            responseTime = (int)timer.ElapsedMilliseconds;
        }
        catch
        {
            // Ignore
        }
        finally
        {
            timer.Stop();
        }
        return responseTime;
    }

    private List<List<ServerTestItem>> GetTestBatchItem(List<ServerTestItem> lstSelected, int pageSize)
    {
        List<List<ServerTestItem>> lstTest = [];
        // An empty list gives pageSize 0, and 0/0 -> NaN -> (int) threw OverflowException.
        if (pageSize <= 0 || lstSelected.Count == 0)
        {
            return lstTest;
        }
        var lst1 = lstSelected.Where(t => t.CoreType == ECoreType.Xray).ToList();
        var lst2 = lstSelected.Where(t => t.CoreType == ECoreType.sing_box).ToList();

        for (var num = 0; num < (int)Math.Ceiling(lst1.Count * 1.0 / pageSize); num++)
        {
            lstTest.Add(lst1.Skip(num * pageSize).Take(pageSize).ToList());
        }
        for (var num = 0; num < (int)Math.Ceiling(lst2.Count * 1.0 / pageSize); num++)
        {
            lstTest.Add(lst2.Skip(num * pageSize).Take(pageSize).ToList());
        }

        return lstTest;
    }

    private async Task UpdateFunc(string indexId, string delay, string speed = "")
    {
        await _updateFunc?.Invoke(new() { IndexId = indexId, Delay = delay, Speed = speed });
        if (indexId.IsNotEmpty() && speed.IsNotEmpty())
        {
            ProfileExManager.Instance.SetTestMessage(indexId, speed);
        }
    }

    private async Task UpdateIpInfoFunc(string indexId, string ip)
    {
        await _updateFunc?.Invoke(new() { IndexId = indexId, IpInfo = ip });
    }
}

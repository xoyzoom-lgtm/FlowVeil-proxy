using System.Collections.ObjectModel;
using ServiceLib.Helper;

namespace v2rayN.ViewModels;

/// <summary>A chip in the strip above the list: "Все", one subscription, "Избранное" or the "+" button.</summary>
public sealed class SubChip : HuppObservable
{
    public SubSelection Selection { get; init; } = SubSelection.AllServers;
    public bool IsAdd { get; init; }
    public string Id => IsAdd ? "+" : Selection.Encode();
    public bool IsSub => !IsAdd && Selection.Kind == ChipKind.Sub;
    public bool IsFavorites => !IsAdd && Selection.Kind == ChipKind.Favorites;

    private string _title = string.Empty;
    public string Title { get => _title; set => Set(ref _title, value); }

    private int _count;
    public int Count { get => _count; set { if (Set(ref _count, value)) { Raise(nameof(CountText)); } } }
    public string CountText => IsAdd || Count <= 0 ? string.Empty : Count.ToString();

    private SubHealth _health;
    public SubHealth Health { get => _health; set { if (Set(ref _health, value)) { Raise(nameof(HealthKey)); } } }

    /// <summary>ok / soon / bad / error / off: the indicator dot picks its colour from it.</summary>
    public string HealthKey => Health switch
    {
        SubHealth.Ok => "ok",
        SubHealth.ExpiresSoon => "soon",
        SubHealth.Expired or SubHealth.TrafficOver => "bad",
        SubHealth.Error => "error",
        _ => "off",
    };

    private string _tooltip = string.Empty;
    public string Tooltip { get => _tooltip; set => Set(ref _tooltip, value); }

    private bool _isSelected;
    public bool IsSelected { get => _isSelected; set => Set(ref _isSelected, value); }

    private bool _isBusy;
    public bool IsBusy { get => _isBusy; set => Set(ref _isBusy, value); }
}

/// <summary>A line of the flat server list: either a group header (in "Все") or a server.</summary>
public abstract class HuppListRow : HuppObservable
{
}

public sealed class HuppHeaderRow : HuppListRow
{
    public required string SubId { get; init; }
    public string Title { get; set; } = string.Empty;
    public string Summary { get; set; } = string.Empty;
    public string CountText { get; set; } = string.Empty;
    public SubHealth Health { get; set; }
    public string HealthKey => Health switch
    {
        SubHealth.Ok => "ok",
        SubHealth.ExpiresSoon => "soon",
        SubHealth.Expired or SubHealth.TrafficOver => "bad",
        SubHealth.Error => "error",
        _ => "off",
    };
    public string HealthText { get; set; } = string.Empty;

    private bool _isCollapsed;
    public bool IsCollapsed { get => _isCollapsed; set => Set(ref _isCollapsed, value); }
}

public sealed class HuppServerRow : HuppListRow
{
    public required ProfileItemModel Model { get; init; }
    public string Name { get; set; } = string.Empty;
    public string Code { get; set; } = string.Empty;
    public List<string> Tags { get; set; } = [];

    private bool _isFavorite;
    public bool IsFavorite { get => _isFavorite; set => Set(ref _isFavorite, value); }

    private bool _isActive;
    public bool IsActive { get => _isActive; set => Set(ref _isActive, value); }

    private bool _unavailable;
    public bool Unavailable { get => _unavailable; set => Set(ref _unavailable, value); }
}

public sealed partial class HuppHomeViewModel
{
    private SubsUiState _state = new();
    private SubSelection _selection = SubSelection.AllServers;
    private readonly DispatcherTimer _listTimer = new() { Interval = TimeSpan.FromMilliseconds(90) };
    private readonly Dictionary<string, HuppServerRow> _rowCache = [];
    private readonly Dictionary<string, HuppHeaderRow> _headerCache = [];
    private Dictionary<string, SubFacts> _facts = [];
    private Dictionary<string, int> _counts = [];
    private HashSet<string> _existingServerIds = [];
    private bool _selectionApplied;

    public ObservableCollection<SubChip> Chips { get; } = [];
    public ObservableCollection<HuppListRow> Items { get; } = [];

    /// <summary>Raised when the "+" chip is pressed (the view opens the "Добавить" page).</summary>
    public event Action? AddRequested;

    private void InitSubs()
    {
        _state = SubsUiStateStore.Load();
        _selection = SubSelection.Decode(_state.Selected);
        _listTimer.Tick += async (_, _) =>
        {
            _listTimer.Stop();
            await RefreshAllAsync();
        };
        Profiles.SpeedtestFinished += () =>
        {
            Application.Current?.Dispatcher.BeginInvoke(new Action(() =>
            {
                IsPinging = false;
                RebuildListSoon();
            }));
        };
        InitSpark();
        RebuildListSoon();
    }

    /// <summary>Everything that depends on subscriptions, servers and the chosen chip is recomputed together, a moment after the last change.</summary>
    private void RebuildListSoon()
    {
        _listTimer.Stop();
        _listTimer.Start();
    }

    private async Task RefreshAllAsync()
    {
        try
        {
            await RefreshFactsAsync();
            RebuildChips();
            if (!_selectionApplied && Profiles.SubItems.Count > 0)
            {
                _selectionApplied = true;
                ApplySelectionToProfiles();
            }
            UpdateSelectedCard();
            RebuildList();
        }
        catch (Exception ex)
        {
            Logging.SaveLog(nameof(HuppHomeViewModel), ex);
        }
    }

    private SubFacts MakeFacts(SubItem sub, long now)
    {
        var info = SubscriptionInfoStore.Get(sub.Id);
        return new SubFacts(sub.Id, LocalTitle(sub, info), sub.Enabled, info?.ExpireSeconds ?? 0, info?.Used ?? 0, info?.Total ?? 0, SubscriptionIssues.Get(sub.Id));
    }

    private string LocalTitle(SubItem sub, SubscriptionInfo? info) =>
        _state.LocalNames.TryGetValue(sub.Id, out var local) && local.IsNotEmpty() ? local : info?.Title.IsNotEmpty() == true ? info.Title! : sub.Remarks;

    private async Task RefreshFactsAsync()
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var subs = Profiles.SubItems.Where(t => t.Id.IsNotEmpty()).ToList();
        _facts = subs.ToDictionary(s => s.Id, s => MakeFacts(s, now));
        var all = await SQLiteHelper.Instance.TableAsync<ProfileItem>().ToListAsync();
        _counts = all.Where(p => p.Subid.IsNotEmpty()).GroupBy(p => p.Subid).ToDictionary(g => g.Key, g => g.Count());
        _existingServerIds = all.Select(p => p.IndexId).ToHashSet();
    }

    private static string HealthText(SubHealth health, SubFacts facts, long now) => health switch
    {
        SubHealth.Ok => string.Empty,
        SubHealth.ExpiresSoon => $"скоро закончится, осталось {Math.Max(0, (int)Math.Ceiling((facts.ExpireSeconds - now) / 86400d))} дн.",
        SubHealth.Expired => "подписка закончилась",
        SubHealth.TrafficOver => "трафик закончился",
        SubHealth.Disabled => "выключена",
        _ => facts.LastIssue.ToCause() is { } cause ? DiagnosisTextOf(cause) : "ошибка обновления",
    };

    /// <summary>Set by the view layer (the Russian texts of the diagnosis live there).</summary>
    public static Func<DiagCause, string> DiagnosisTextOf { get; set; } = c => c.ToString();

    private HashSet<string> EnabledIds() => _facts.Values.Where(f => f.Enabled).Select(f => f.Id).ToHashSet();

    private List<string> SubOrder() => SubsLogic.ApplyOrder(Profiles.SubItems.Where(t => t.Id.IsNotEmpty()).Select(t => t.Id).ToList(), _state.Order);

    private SubHealth HealthOf(string subId) =>
        _facts.TryGetValue(subId, out var f) ? SubsLogic.HealthOf(f, DateTimeOffset.UtcNow.ToUnixTimeSeconds()) : SubHealth.Ok;

    private bool SubUsable(string subId) => SubsLogic.IsUsable(HealthOf(subId));

    private void RebuildChips()
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        var order = SubOrder();
        var favCount = _state.Favorites.Count(f => _existingServerIds.Contains(f));
        var chips = new List<SubChip>
        {
            Find(SubSelection.AllServers, "Все", _facts.Values.Where(f => f.Enabled).Sum(f => _counts.GetValueOrDefault(f.Id)), SubHealth.Ok, "Все серверы включённых подписок"),
        };
        foreach (var id in order)
        {
            if (!_facts.TryGetValue(id, out var f))
            {
                continue;
            }
            var health = SubsLogic.HealthOf(f, now);
            var text = HealthText(health, f, now);
            chips.Add(Find(new SubSelection(ChipKind.Sub, id), f.Name, _counts.GetValueOrDefault(id), health, text.IsNullOrEmpty() ? f.Name : $"{f.Name}: {text}"));
        }
        chips.Add(Find(SubSelection.FavoriteServers, "Избранное", favCount, SubHealth.Ok, "Серверы, отмеченные звёздочкой"));
        chips.Add(new SubChip { IsAdd = true, Title = "+", Tooltip = "Добавить подписку" });

        SubChip Find(SubSelection selection, string title, int count, SubHealth health, string tooltip)
        {
            var existing = Chips.FirstOrDefault(c => !c.IsAdd && c.Selection == selection);
            var chip = existing ?? new SubChip { Selection = selection };
            chip.Title = title;
            chip.Count = count;
            chip.Health = health;
            chip.Tooltip = tooltip;
            return chip;
        }

        // A deleted subscription that was selected falls back to "Все".
        if (_selection.Kind == ChipKind.Sub && chips.All(c => c.Selection != _selection))
        {
            _selection = SubSelection.AllServers;
            _state.Selected = _selection.Encode();
            SubsUiStateStore.Save(_state);
            ApplySelectionToProfiles();
        }
        foreach (var chip in chips)
        {
            chip.IsSelected = !chip.IsAdd && chip.Selection == _selection;
        }
        if (!chips.SequenceEqual(Chips))
        {
            Chips.Clear();
            foreach (var chip in chips)
            {
                Chips.Add(chip);
            }
        }
        Raise(nameof(ScopeTitle));
        Raise(nameof(BestCaption));
    }

    private void UpdateChipSelection()
    {
        foreach (var chip in Chips)
        {
            chip.IsSelected = !chip.IsAdd && chip.Selection == _selection;
        }
        Raise(nameof(ScopeTitle));
        Raise(nameof(BestCaption));
        Raise(nameof(IsAllMode));
    }

    public bool IsAllMode => _selection.Kind == ChipKind.All;

    public string ScopeTitle => _selection.Kind switch
    {
        ChipKind.All => "все подписки",
        ChipKind.Favorites => "избранное",
        _ => Chips.FirstOrDefault(c => c.IsSelected)?.Title ?? "подписка",
    };

    public string BestCaption => $"Лучший в: {ScopeTitle}";

    // ---- chip selection ----

    public void SelectChip(SubChip? chip)
    {
        if (chip == null)
        {
            return;
        }
        if (chip.IsAdd)
        {
            AddRequested?.Invoke();
            return;
        }
        _selection = chip.Selection;
        _state.Selected = chip.Id;
        SubsUiStateStore.Save(_state);
        ApplySelectionToProfiles();
        UpdateChipSelection();
        UpdateSelectedCard();
        RebuildListSoon();
    }

    /// <summary>Moves to the next / previous chip (Ctrl+Tab); the "+" chip is skipped.</summary>
    public void StepChip(int delta)
    {
        var real = Chips.Where(c => !c.IsAdd).ToList();
        if (real.Count == 0)
        {
            return;
        }
        var i = Math.Max(0, real.FindIndex(c => c.IsSelected));
        SelectChip(real[(i + delta + real.Count) % real.Count]);
    }

    private void ApplySelectionToProfiles()
    {
        var target = _selection.Kind == ChipKind.Sub
            ? Profiles.SubItems.FirstOrDefault(t => t.Id == _selection.SubId)
            : Profiles.SubItems.FirstOrDefault(t => t.Id.IsNullOrEmpty());
        if (target != null && Profiles.SelectedSub?.Id != target.Id)
        {
            Profiles.SelectedSub = target;
        }
    }

    /// <summary>The classic view (or a command) changed the current group: follow it.</summary>
    private void SyncSelectionFromProfiles()
    {
        var id = Profiles.SelectedSub?.Id;
        if (id.IsNotEmpty() && (_selection.Kind != ChipKind.Sub || _selection.SubId != id))
        {
            _selection = new SubSelection(ChipKind.Sub, id);
            _state.Selected = _selection.Encode();
            SubsUiStateStore.Save(_state);
        }
        else if (id.IsNullOrEmpty() && _selection.Kind == ChipKind.Sub)
        {
            _selection = SubSelection.AllServers;
            _state.Selected = _selection.Encode();
            SubsUiStateStore.Save(_state);
        }
        UpdateChipSelection();
        UpdateSelectedCard();
        RebuildListSoon();
    }

    // ---- the one detailed card ----

    private HuppSubCard? _selectedCard;
    public HuppSubCard? SelectedCard { get => _selectedCard; private set { if (Set(ref _selectedCard, value)) { Raise(nameof(HasSelectedCard)); } } }
    public bool HasSelectedCard => _selectedCard != null;

    private void UpdateSelectedCard() =>
        SelectedCard = _selection.Kind == ChipKind.Sub ? Cards.FirstOrDefault(c => c.Sub.Id == _selection.SubId) : null;

    // ---- search, sort, quick filters ----

    private string _searchText = string.Empty;
    public string SearchText { get => _searchText; set { if (Set(ref _searchText, value ?? string.Empty)) { RebuildListSoon(); } } }

    public ServerSort Sort
    {
        get => _state.Sort;
        set
        {
            if (_state.Sort == value)
            {
                return;
            }
            _state.Sort = value;
            SubsUiStateStore.Save(_state);
            Raise(nameof(Sort));
            Raise(nameof(SortTitle));
            RebuildListSoon();
        }
    }

    public string SortTitle => Sort switch { ServerSort.Ping => "По пингу", ServerSort.Name => "По названию", _ => "Как у провайдера" };

    public bool FilterAlive { get => Quick.HasFlag(QuickFilter.Alive); set => SetQuick(QuickFilter.Alive, value); }
    public bool FilterReality { get => Quick.HasFlag(QuickFilter.Reality); set => SetQuick(QuickFilter.Reality, value); }
    public bool FilterUdp { get => Quick.HasFlag(QuickFilter.Udp); set => SetQuick(QuickFilter.Udp, value); }
    public bool FilterFavorite { get => Quick.HasFlag(QuickFilter.Favorite); set => SetQuick(QuickFilter.Favorite, value); }

    private QuickFilter Quick => _state.Quick;

    private void SetQuick(QuickFilter flag, bool on)
    {
        var next = on ? _state.Quick | flag : _state.Quick & ~flag;
        if (next == _state.Quick)
        {
            return;
        }
        _state.Quick = next;
        SubsUiStateStore.Save(_state);
        Raise(nameof(FilterAlive));
        Raise(nameof(FilterReality));
        Raise(nameof(FilterUdp));
        Raise(nameof(FilterFavorite));
        Raise(nameof(HasActiveFilters));
        RebuildListSoon();
    }

    public bool HasActiveFilters => Quick != QuickFilter.None || _searchText.Trim().Length > 0;

    public void ResetFilters()
    {
        _state.Quick = QuickFilter.None;
        SubsUiStateStore.Save(_state);
        _searchText = string.Empty;
        Raise(nameof(SearchText));
        Raise(nameof(FilterAlive));
        Raise(nameof(FilterReality));
        Raise(nameof(FilterUdp));
        Raise(nameof(FilterFavorite));
        Raise(nameof(HasActiveFilters));
        RebuildListSoon();
    }

    // ---- the flat list ----

    private bool _noResults;
    public bool NoResults { get => _noResults; private set => Set(ref _noResults, value); }

    private bool _isPinging;
    public bool IsPinging { get => _isPinging; private set { if (Set(ref _isPinging, value)) { Raise(nameof(PingAllCaption)); } } }
    public string PingAllCaption => IsPinging ? "Остановить проверку" : "Обновить пинг";

    private List<ServerFacts> FactsOf(IReadOnlyList<ProfileItemModel> models) =>
        models.Select((m, i) => new ServerFacts(m.IndexId, m.Subid ?? string.Empty, m.Remarks ?? string.Empty, m.Delay,
            m.ConfigType.ToString().ToUpperInvariant(), m.StreamSecurity ?? string.Empty, m.Network ?? string.Empty, i)).ToList();

    private void RebuildList()
    {
        var models = Profiles.ProfileItems.ToList();
        var byId = new Dictionary<string, ProfileItemModel>();
        foreach (var m in models.Where(m => m.IndexId.IsNotEmpty()))
        {
            byId[m.IndexId] = m;
        }
        var favorites = _state.Favorites.ToHashSet();
        var order = SubOrder();
        var filtered = SubsLogic.Filter(FactsOf(models), _selection, EnabledIds(), order, _searchText, Quick, favorites, Sort);
        var dev = IsDevMode();

        var rows = new List<HuppListRow>();
        HuppServerRow RowOf(ServerFacts f)
        {
            var model = byId[f.IndexId];
            if (!_rowCache.TryGetValue(f.IndexId, out var row) || !ReferenceEquals(row.Model, model))
            {
                row = new HuppServerRow { Model = model };
                _rowCache[f.IndexId] = row;
            }
            var (code, name) = HuppProfileText.SplitFlag(model.Remarks);
            row.Code = code;
            row.Name = name;
            row.Tags = ServerTags.From(HuppProfileText.Describe(model), dev);
            row.IsFavorite = favorites.Contains(f.IndexId);
            row.IsActive = model.IndexId == _config.IndexId;
            row.Unavailable = !SubUsable(f.SubId);
            return row;
        }

        var grouped = _selection.Kind == ChipKind.All && _facts.Values.Count(f => f.Enabled) > 1;
        if (grouped)
        {
            var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            foreach (var subId in order)
            {
                var inGroup = filtered.Where(f => f.SubId == subId).ToList();
                if (inGroup.Count == 0 || !_facts.TryGetValue(subId, out var facts))
                {
                    continue;
                }
                if (!_headerCache.TryGetValue(subId, out var header))
                {
                    header = new HuppHeaderRow { SubId = subId, IsCollapsed = _state.Collapsed.Contains(subId) };
                    _headerCache[subId] = header;
                }
                var health = SubsLogic.HealthOf(facts, now);
                var card = Cards.FirstOrDefault(c => c.Sub.Id == subId);
                header.Title = facts.Name;
                header.Health = health;
                header.HealthText = HealthText(health, facts, now);
                header.CountText = $"{inGroup.Count}";
                header.Summary = string.Join(" · ", new[] { card?.TrafficText, card?.ExpireText }.Where(t => t.IsNotEmpty()));
                rows.Add(header);
                if (!header.IsCollapsed)
                {
                    rows.AddRange(inGroup.Select(RowOf));
                }
            }
        }
        else
        {
            rows.AddRange(filtered.Select(RowOf));
        }

        if (!rows.SequenceEqual(Items))
        {
            Items.Clear();
            foreach (var row in rows)
            {
                Items.Add(row);
            }
        }
        NoResults = filtered.Count == 0 && models.Count > 0 && HasActiveFilters;
        Raise(nameof(HasNothingHere));
        Raise(nameof(HasActiveFilters));
    }

    /// <summary>The chosen subscription (or favorites) simply has no servers yet.</summary>
    public bool HasNothingHere => Items.Count == 0 && !NoResults && Profiles.ProfileItems.Count == 0 && Profiles.SubItems.Count > 1;

    private static bool IsDevMode() => File.Exists(Utils.GetConfigPath("dev_mode"));

    /// <summary>Called when the connected server changes: the accent bar follows it without rebuilding the list.</summary>
    private void RefreshActiveFlags()
    {
        foreach (var row in _rowCache.Values)
        {
            row.IsActive = row.Model.IndexId == _config.IndexId;
        }
    }

    // ---- favorites, collapse, order, names, on/off ----

    public void ToggleFavorite(HuppServerRow row)
    {
        var id = row.Model.IndexId;
        if (_state.Favorites.Remove(id))
        {
            row.IsFavorite = false;
        }
        else
        {
            _state.Favorites.Add(id);
            row.IsFavorite = true;
        }
        SubsUiStateStore.Save(_state);
        RebuildListSoon();
    }

    public void ToggleGroup(HuppHeaderRow header)
    {
        header.IsCollapsed = !header.IsCollapsed;
        if (header.IsCollapsed)
        {
            _state.Collapsed.Add(header.SubId);
        }
        else
        {
            _state.Collapsed.Remove(header.SubId);
        }
        SubsUiStateStore.Save(_state);
        RebuildList();
    }

    public void MoveSubscription(string subId, int delta)
    {
        _state.Order = SubsLogic.Move(SubOrder(), subId, delta);
        SubsUiStateStore.Save(_state);
        RebuildListSoon();
    }

    public void RenameSubscription(string subId, string? name)
    {
        if (name.IsNullOrEmpty())
        {
            _state.LocalNames.Remove(subId);
        }
        else
        {
            _state.LocalNames[subId] = name.Trim();
        }
        SubsUiStateStore.Save(_state);
        RebuildCards();
        RebuildListSoon();
    }

    /// <summary>Switch a subscription off (hidden from "Все" and from automatic choice, kept) or on again.</summary>
    public async Task SetSubscriptionEnabledAsync(SubItem sub, bool enabled)
    {
        sub.Enabled = enabled;
        await ConfigHandler.AddSubItem(_config, sub);
        await Profiles.RefreshSubscriptions();
        RebuildListSoon();
    }

    public bool IsSubscriptionEnabled(string subId) => !_facts.TryGetValue(subId, out var f) || f.Enabled;

    // ---- "best" and "ping" in the scope of the current chip ----

    /// <summary>Servers of the current chip only (no search or quick filters): what "Best" and "Update ping" work on.</summary>
    private List<ServerFacts> ScopeFacts() =>
        SubsLogic.Filter(FactsOf(Profiles.ProfileItems.ToList()), _selection, EnabledIds(), SubOrder(), null, QuickFilter.None, _state.Favorites.ToHashSet(), ServerSort.Provider);

    // ---- deleting, counts ----

    public int ServerCountOf(string subId) => _counts.GetValueOrDefault(subId);

    /// <summary>Forget what the screen remembers about a deleted subscription.</summary>
    public void ForgetSubscription(string subId)
    {
        _state.Order.Remove(subId);
        _state.Collapsed.Remove(subId);
        _state.LocalNames.Remove(subId);
        if (_selection.Kind == ChipKind.Sub && _selection.SubId == subId)
        {
            _selection = SubSelection.AllServers;
            _state.Selected = _selection.Encode();
        }
        SubsUiStateStore.Save(_state);
        SubscriptionIssues.Set(subId, SubIssue.None);
        RebuildListSoon();
    }

    public string? LocalNameOf(string subId) => _state.LocalNames.GetValueOrDefault(subId);

    // ---- speed of this session ----

    private readonly List<long> _downSamples = [];
    private System.Windows.Media.PointCollection _sparkPoints = [];
    public System.Windows.Media.PointCollection SparkPoints { get => _sparkPoints; private set => Set(ref _sparkPoints, value); }
    public bool HasSpark => IsConnected && _downSamples.Count >= 5;

    private void InitSpark()
    {
        AppEvents.DispatcherStatisticsRequested
            .AsObservable()
            .ObserveOn(RxSchedulers.MainThreadScheduler)
            .Subscribe(update => AddSample(update.ProxyDown));
        PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(IsConnected) && !IsConnected)
            {
                _downSamples.Clear();
                SparkPoints = [];
                Raise(nameof(HasSpark));
            }
        };
    }

    private void AddSample(long bytesPerSecond)
    {
        if (!IsConnected)
        {
            return;
        }
        _downSamples.Add(Math.Max(0, bytesPerSecond));
        if (_downSamples.Count > 60)
        {
            _downSamples.RemoveAt(0);
        }
        var max = Math.Max(1, _downSamples.Max());
        var points = new System.Windows.Media.PointCollection();
        for (var i = 0; i < _downSamples.Count; i++)
        {
            points.Add(new Point(i * 280d / 59d, 44 - 40d * _downSamples[i] / max));
        }
        SparkPoints = points;
        Raise(nameof(HasSpark));
    }
}

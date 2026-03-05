using System;
using System.Collections.ObjectModel;
using System.Collections.Generic;
using System.ComponentModel;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Threading;
using GSMRelayDesktop.Helpers;
using GSMRelayDesktop.Models;
using GSMRelayDesktop.Services;
using Microsoft.Win32;

namespace GSMRelayDesktop.ViewModels;

public class MainViewModel : INotifyPropertyChanged
{
    private const int MaxRelayChannels = 999;
    private readonly ApiClient _apiClient = new();
    private bool _suppressConfigSave;
    private readonly HashSet<string> _manualLocations = new(StringComparer.Ordinal);

    public ObservableCollection<Relay> Relays { get; } = new();
    public ObservableCollection<string> Locations { get; } = new();
    public ObservableCollection<Relay> LocationRelays { get; } = new();
    public ObservableCollection<LocationRelaySelectionItem> LocationRelaySelections { get; } = new();
    public ObservableCollection<RelayUser> VisibleUsers { get; } = new();
    public ObservableCollection<CommandHistory> History { get; } = new();
    public ObservableCollection<RelayEvent> Events { get; } = new();
    public ObservableCollection<AppNotification> Notifications { get; } = new();
    public ObservableCollection<CommandQueueItem> Commands { get; } = new();
    public ObservableCollection<CommandHistory> RelayHistory { get; } = new();
    public ObservableCollection<RelayEvent> RelayEvents { get; } = new();
    public ObservableCollection<CommandQueueItem> RelayCommands { get; } = new();
    public ObservableCollection<CommandQueueItem> LocationCommands { get; } = new();
    public ObservableCollection<RelayEvent> LocationEvents { get; } = new();
    public ObservableCollection<LogEntry> Logs { get; } = new();

    private bool _setupStateVisible;
    public bool SetupStateVisible
    {
        get => _setupStateVisible;
        private set
        {
            if (_setupStateVisible == value) return;
            _setupStateVisible = value;
            OnPropertyChanged();
        }
    }

    private string _setupStateTitle = "";
    public string SetupStateTitle
    {
        get => _setupStateTitle;
        private set
        {
            if (_setupStateTitle == value) return;
            _setupStateTitle = value;
            OnPropertyChanged();
        }
    }

    private string _setupStateDetails = "";
    public string SetupStateDetails
    {
        get => _setupStateDetails;
        private set
        {
            if (_setupStateDetails == value) return;
            _setupStateDetails = value;
            OnPropertyChanged();
        }
    }

    private string _setupWaitingMarker = "";
    public string SetupWaitingMarker
    {
        get => _setupWaitingMarker;
        private set
        {
            if (_setupWaitingMarker == value) return;
            _setupWaitingMarker = value;
            OnPropertyChanged();
        }
    }

    private string _setupElapsed = "";
    public string SetupElapsed
    {
        get => _setupElapsed;
        private set
        {
            if (_setupElapsed == value) return;
            _setupElapsed = value;
            OnPropertyChanged();
        }
    }

    private string _setupLastSms = "";
    public string SetupLastSms
    {
        get => _setupLastSms;
        private set
        {
            if (_setupLastSms == value) return;
            _setupLastSms = value;
            OnPropertyChanged();
        }
    }

    private readonly DispatcherTimer _pollTimer;
    private bool _isPolling;
    private bool _allowTransientRelayNull;
    private string _lastSelectedLocation = "";
    private string _lastSelectedRelayPhoneKey = "";
    public bool IsRelayEditorOpen { get; set; }

    private string? _selectedLocation;
    public string? SelectedLocation
    {
        get => _selectedLocation;
        set
        {
            if (_selectedLocation == value) return;
            _selectedLocation = value;
            if (!string.IsNullOrWhiteSpace(_selectedLocation))
            {
                _lastSelectedLocation = _selectedLocation;
            }
            OnPropertyChanged();
            NewLocationName = _selectedLocation ?? "";
            OnPropertyChanged(nameof(NewLocationName));
            RefreshLocationScopedData();
            if (SelectedRelay != null && !IsRelayInSelectedLocation(SelectedRelay))
            {
                SelectedRelay = LocationRelays.FirstOrDefault();
            }
            else if (SelectedRelay == null && LocationRelays.Count > 0)
            {
                SelectedRelay = LocationRelays[0];
            }
            RefreshCommandStates();
        }
    }

    private Relay? _selectedRelay;
    public Relay? SelectedRelay
    {
        get => _selectedRelay;
        set
        {
            // During auto-refresh the list is rebuilt; ignore transient nulls
            // so UI actions do not lose relay context mid-operation.
            if (value == null && (_isPolling || IsBusy) && !_allowTransientRelayNull)
            {
                return;
            }
            if (_selectedRelay == value) return;
            _selectedRelay = value;
            OnPropertyChanged();
            if (_selectedRelay != null)
            {
                _lastSelectedRelayPhoneKey = RelayPhoneKey(_selectedRelay.PhoneNumber);
                var relayLocation = NormalizeLocation(_selectedRelay.Location);
                if (!_isPolling && !string.Equals(_selectedLocation, relayLocation, StringComparison.Ordinal))
                {
                    _selectedLocation = relayLocation;
                    _lastSelectedLocation = relayLocation;
                    OnPropertyChanged(nameof(SelectedLocation));
                    RefreshLocationScopedData();
                }
                if (!IsRelayEditorOpen)
                {
                    NewRelayName = _selectedRelay.Name;
                    NewRelayPhone = _selectedRelay.PhoneNumber;
                    NewRelayPassword = _selectedRelay.Password;
                    NewRelayLocation = _selectedRelay.Location;
                    OnPropertyChanged(nameof(NewRelayName));
                    OnPropertyChanged(nameof(NewRelayPhone));
                    OnPropertyChanged(nameof(NewRelayPassword));
                    OnPropertyChanged(nameof(NewRelayLocation));
                }
            }
            RefreshVisibleUsers();
            RefreshRelayScopedData();
            RefreshCommandStates();
        }
    }

    private string _statusMessage = "Conecteaza-te la server";
    public string StatusMessage
    {
        get => _statusMessage;
        set
        {
            if (_statusMessage == value) return;
            _statusMessage = value;
            OnPropertyChanged();
            AddLog(value);
        }
    }

    private bool _isBusy;
    public bool IsBusy
    {
        get => _isBusy;
        set { _isBusy = value; OnPropertyChanged(); RefreshCommandStates(); }
    }

    private DateTime? _eventStartDate;
    public DateTime? EventStartDate
    {
        get => _eventStartDate;
        set { _eventStartDate = value; OnPropertyChanged(); RefreshCommandStates(); }
    }

    private DateTime? _eventEndDate;
    public DateTime? EventEndDate
    {
        get => _eventEndDate;
        set { _eventEndDate = value; OnPropertyChanged(); RefreshCommandStates(); }
    }

    private string _eventStartTime = "00:00";
    public string EventStartTime
    {
        get => _eventStartTime;
        set { _eventStartTime = value; OnPropertyChanged(); RefreshCommandStates(); }
    }

    private string _eventEndTime = "23:59";
    public string EventEndTime
    {
        get => _eventEndTime;
        set { _eventEndTime = value; OnPropertyChanged(); RefreshCommandStates(); }
    }

    public ServerConfig ServerConfig { get; } = new();

    public string NewRelayName { get; set; } = "";
    public string NewRelayPhone { get; set; } = "";
    public string NewRelayPassword { get; set; } = "2005";
    public string NewRelayLocation { get; set; } = "";
    public string NewLocationName { get; set; } = "";

    public int NewUserId { get; set; } = 1;
    public string NewUserPhone { get; set; } = "";
    public string NewUserName { get; set; } = "";
    public string NewUserGroup { get; set; } = "general";

    public int QueryStart { get; set; } = 1;
    public int QueryEnd { get; set; } = MaxRelayChannels;
    public int OnboardingQueryStart { get; set; } = 1;
    public int OnboardingQueryEnd { get; set; } = MaxRelayChannels;
    public bool OnboardingForcePasswordReset { get; set; } = true;
    public bool OnboardingSetDateTime { get; set; } = true;
    public bool OnboardingSetMaster { get; set; } = true;
    public bool OnboardingConfirmOn { get; set; } = true;
    public bool OnboardingConfirmOff { get; set; } = true;
    public bool OnboardingQueryUsers { get; set; } = true;
    public bool OnboardingAutoAddAdmins { get; set; } = true;
    public string NewPassword { get; set; } = "";
    public int TimerSeconds { get; set; } = 5;
    public string SelectedLocationRelaySummary => $"Selectate: {LocationRelaySelections.Count(x => x.IsSelected)} / {LocationRelaySelections.Count}";

    public RelayCommand RefreshCommand { get; }
    public RelayCommand UploadSnapshotCommand { get; }
    public RelayCommand AddLocationCommand { get; }
    public RelayCommand SelectLocationCommand { get; }
    public RelayCommand EditLocationCommand { get; }
    public RelayCommand DeleteLocationFromListCommand { get; }
    public RelayCommand RenameLocationCommand { get; }
    public RelayCommand DeleteLocationCommand { get; }
    public RelayCommand AddRelayCommand { get; }
    public RelayCommand UpdateRelayCommand { get; }
    public RelayCommand DeleteRelayCommand { get; }
    public RelayCommand SelectRelayCommand { get; }
    public RelayCommand EditRelayCommand { get; }
    public RelayCommand DeleteRelayFromListCommand { get; }
    public RelayCommand AddUserCommand { get; }
    public RelayCommand AddUserToLocationCommand { get; }
    public RelayCommand SelectAllLocationRelaysCommand { get; }
    public RelayCommand DeleteUserCommand { get; }
    public RelayCommand DeleteAllUsersCommand { get; }
    public RelayCommand ClearRelayDatabaseCommand { get; }
    public RelayCommand StopRelayQueueCommand { get; }
    public RelayCommand ImportCsvCommand { get; }
    public RelayCommand ExportCsvCommand { get; }
    public RelayCommand ExportEventsCommand { get; }
    public RelayCommand ExportLocationEventsCommand { get; }
    public RelayCommand ScrapeEventsCommand { get; }
    public RelayCommand SyncSmsCommand { get; }
    public RelayCommand QueryUsersCommand { get; }
    public RelayCommand ChangePasswordCommand { get; }
    public RelayCommand TimerCommand { get; }
    public RelayCommand AllowAllCommand { get; }
    public RelayCommand AllowAuthorizedCommand { get; }

    public MainViewModel()
    {
        LoadServerConfig();
        ServerConfig.PropertyChanged += (_, _) =>
        {
            if (_suppressConfigSave) return;
            ConfigStore.SaveServerConfig(ServerConfig);
        };

        RefreshCommand = new RelayCommand(async _ => await RefreshAllAsync(true), _ => ServerConfig.IsValid() && !IsBusy);
        UploadSnapshotCommand = new RelayCommand(async _ => await UploadSnapshotAsync(), _ => ServerConfig.IsValid() && !IsBusy);
        AddLocationCommand = new RelayCommand(async _ => await AddLocationAsync(), _ => !IsBusy);
        SelectLocationCommand = new RelayCommand(obj =>
        {
            if (obj is string location) SelectedLocation = location;
        }, _ => !IsBusy);
        EditLocationCommand = new RelayCommand(async obj =>
        {
            if (obj is string location)
            {
                await PromptRenameLocationAsync(location);
            }
        }, _ => !IsBusy);
        DeleteLocationFromListCommand = new RelayCommand(async obj =>
        {
            if (obj is string location) await DeleteLocationAsync(location);
        }, _ => !IsBusy);
        RenameLocationCommand = new RelayCommand(async _ => await PromptRenameLocationAsync(SelectedLocation), _ => !IsBusy && !string.IsNullOrWhiteSpace(SelectedLocation));
        DeleteLocationCommand = new RelayCommand(async _ => await DeleteLocationAsync(), _ => !IsBusy && !string.IsNullOrWhiteSpace(SelectedLocation));
        AddRelayCommand = new RelayCommand(async _ => await AddRelayAsync(), _ => !IsBusy);
        UpdateRelayCommand = new RelayCommand(async _ => await UpdateRelayAsync(), _ => !IsBusy && SelectedRelay != null);
        DeleteRelayCommand = new RelayCommand(async _ => await DeleteRelayAsync(), _ => !IsBusy && SelectedRelay != null);
        SelectRelayCommand = new RelayCommand(obj =>
        {
            if (obj is Relay relay) SelectedRelay = relay;
        });
        EditRelayCommand = new RelayCommand(async obj =>
        {
            if (obj is Relay relay)
            {
                await PromptEditRelayAsync(relay);
            }
        });
        DeleteRelayFromListCommand = new RelayCommand(async obj =>
        {
            if (obj is Relay relay) await DeleteRelayAsync(relay);
        }, _ => ServerConfig.IsValid() && !IsBusy);
        AddUserCommand = new RelayCommand(async _ => await AddUserAsync(), _ => SelectedRelay != null && !IsBusy);
        AddUserToLocationCommand = new RelayCommand(
            async _ => await AddUserToLocationAsync(),
            _ => ServerConfig.IsValid() && !IsBusy && LocationRelaySelections.Any(x => x.IsSelected)
        );
        SelectAllLocationRelaysCommand = new RelayCommand(
            _ => SelectAllLocationRelays(),
            _ => LocationRelaySelections.Count > 0
        );
        DeleteUserCommand = new RelayCommand(async obj => await DeleteUserAsync(obj as RelayUser), _ => SelectedRelay != null && !IsBusy);
        DeleteAllUsersCommand = new RelayCommand(async _ => await DeleteAllUsersAsync(), _ => SelectedRelay != null && !IsBusy);
        ClearRelayDatabaseCommand = new RelayCommand(
            async _ => await ClearRelayDatabaseAsync(),
            _ => SelectedRelay != null && !IsBusy && (
                SelectedRelay.Users.Any(u => !string.IsNullOrWhiteSpace(u.Phone))
                || RelayCommands.Any(c => IsActiveQueueStatus(c.Status))
            )
        );
        StopRelayQueueCommand = new RelayCommand(
            async _ => await StopRelayQueueAsync(),
            _ => SelectedRelay != null && !IsBusy && RelayCommands.Any(c => IsActiveQueueStatus(c.Status))
        );
        ImportCsvCommand = new RelayCommand(async _ => await ImportCsvAsync(), _ => SelectedRelay != null && !IsBusy);
        ExportCsvCommand = new RelayCommand(_ => ExportCsv(), _ => SelectedRelay != null);
        ExportEventsCommand = new RelayCommand(_ => ExportEventsCsv(), _ => Events.Count > 0);
        ExportLocationEventsCommand = new RelayCommand(_ => ExportLocationEventsCsv(), _ => LocationEvents.Count > 0);
        ScrapeEventsCommand = new RelayCommand(async _ => await ScrapeEventsAsync(), _ => CanScrapeEvents());
        SyncSmsCommand = new RelayCommand(async _ => await SyncSmsAsync(), _ => SelectedRelay != null && ServerConfig.IsValid() && !IsBusy);
        QueryUsersCommand = new RelayCommand(async _ => await SendCommandAsync(BuildQueryCommand(), "Interogare utilizatori"), _ => SelectedRelay != null);
        ChangePasswordCommand = new RelayCommand(async _ => await SendCommandAsync(BuildPasswordCommand(), "Schimbare parola"), _ => SelectedRelay != null);
        TimerCommand = new RelayCommand(async _ => await SendCommandAsync(BuildTimerCommand(), "Setare temporizare"), _ => SelectedRelay != null);
        AllowAllCommand = new RelayCommand(async _ => await SendCommandAsync(BuildSimpleCommand("ALL#"), "Acces permis tuturor"), _ => SelectedRelay != null);
        AllowAuthorizedCommand = new RelayCommand(async _ => await SendCommandAsync(BuildSimpleCommand("AUT#"), "Acces permis doar autorizati"), _ => SelectedRelay != null);

        _pollTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromSeconds(10)
        };
        _pollTimer.Tick += async (_, _) => await PollAsync();
        _pollTimer.Start();

        var now = DateTime.Now;
        EventEndDate = now.Date;
        EventStartDate = now.Date.AddDays(-1);
    }

    public async Task InitializeOnStartupAsync()
    {
        if (!ServerConfig.IsValid()) return;
        await RefreshAllAsync(true);
    }

    private void LoadServerConfig()
    {
        _suppressConfigSave = true;
        var loaded = ConfigStore.LoadServerConfig();
        if (loaded != null)
        {
            ServerConfig.BaseUrl = loaded.BaseUrl ?? "";
            ServerConfig.Username = loaded.Username ?? "";
            ServerConfig.Password = loaded.Password ?? "";
            ServerConfig.GatewayId = loaded.GatewayId ?? "";
            ServerConfig.MasterPhone = loaded.MasterPhone ?? "";
        }
        if (string.IsNullOrWhiteSpace(ServerConfig.BaseUrl))
        {
            ServerConfig.BaseUrl = "http://86.120.150.58:5174";
            ServerConfig.Username = "admin";
            ServerConfig.Password = "admin1316";
        }
        _suppressConfigSave = false;
        ConfigStore.SaveServerConfig(ServerConfig);
    }

    private void RefreshCommandStates()
    {
        RefreshCommand.RaiseCanExecuteChanged();
        UploadSnapshotCommand.RaiseCanExecuteChanged();
        AddLocationCommand.RaiseCanExecuteChanged();
        RenameLocationCommand.RaiseCanExecuteChanged();
        DeleteLocationCommand.RaiseCanExecuteChanged();
        AddRelayCommand.RaiseCanExecuteChanged();
        UpdateRelayCommand.RaiseCanExecuteChanged();
        DeleteRelayCommand.RaiseCanExecuteChanged();
        DeleteRelayFromListCommand.RaiseCanExecuteChanged();
        AddUserCommand.RaiseCanExecuteChanged();
        AddUserToLocationCommand.RaiseCanExecuteChanged();
        SelectAllLocationRelaysCommand.RaiseCanExecuteChanged();
        DeleteUserCommand.RaiseCanExecuteChanged();
        DeleteAllUsersCommand.RaiseCanExecuteChanged();
        ClearRelayDatabaseCommand.RaiseCanExecuteChanged();
        StopRelayQueueCommand.RaiseCanExecuteChanged();
        ImportCsvCommand.RaiseCanExecuteChanged();
        ExportCsvCommand.RaiseCanExecuteChanged();
        ExportEventsCommand.RaiseCanExecuteChanged();
        ExportLocationEventsCommand.RaiseCanExecuteChanged();
        ScrapeEventsCommand.RaiseCanExecuteChanged();
        SyncSmsCommand.RaiseCanExecuteChanged();
        QueryUsersCommand.RaiseCanExecuteChanged();
        ChangePasswordCommand.RaiseCanExecuteChanged();
        TimerCommand.RaiseCanExecuteChanged();
        AllowAllCommand.RaiseCanExecuteChanged();
        AllowAuthorizedCommand.RaiseCanExecuteChanged();
    }

    private async Task RefreshAllAsync(bool showBusy)
    {
        await RefreshSnapshotAsync(showBusy);
        await RefreshCommandsAsync(showBusy);
    }

    private async Task PollAsync()
    {
        if (_isPolling || IsBusy) return;
        if (!ServerConfig.IsValid()) return;
        _isPolling = true;
        try
        {
            await RefreshAllAsync(false);
        }
        finally
        {
            _isPolling = false;
        }
    }

    private async Task RefreshSnapshotAsync(bool showBusy)
    {
        if (!ServerConfig.IsValid()) return;
        var previouslySelectedLocation = SelectedLocation;
        var previouslySelectedRelayId = SelectedRelay?.Id ?? 0;
        var previouslySelectedRelayPhone = SelectedRelay?.PhoneNumber ?? "";
        var previouslySelectedRelayPhoneKey = !string.IsNullOrWhiteSpace(_lastSelectedRelayPhoneKey)
            ? _lastSelectedRelayPhoneKey
            : RelayPhoneKey(previouslySelectedRelayPhone);
        var preferredLocation = !string.IsNullOrWhiteSpace(previouslySelectedLocation)
            ? previouslySelectedLocation
            : _lastSelectedLocation;
        if (showBusy)
        {
            IsBusy = true;
            StatusMessage = "Se incarca snapshot...";
        }
        var snapshot = await _apiClient.GetSnapshotAsync(ServerConfig);
        if (snapshot == null)
        {
            if (showBusy)
            {
                StatusMessage = "Nu s-a putut citi snapshot-ul";
                IsBusy = false;
            }
            return;
        }

        Relays.Clear();
        foreach (var relay in snapshot.Relays)
        {
            NormalizeRelayUsers(relay);
            Relays.Add(relay);
        }
        _manualLocations.Clear();
        foreach (var location in snapshot.Locations
                     .Select(NormalizeLocation)
                     .Where(location => !string.Equals(location, "Fara locatie", StringComparison.Ordinal))
                     .Distinct(StringComparer.Ordinal))
        {
            _manualLocations.Add(location);
        }
        RefreshLocations();
        if (!string.IsNullOrWhiteSpace(preferredLocation) &&
            Locations.Contains(preferredLocation))
        {
            try
            {
                _allowTransientRelayNull = true;
                SelectedLocation = preferredLocation;
            }
            finally
            {
                _allowTransientRelayNull = false;
            }
        }

        var scopedLocation = SelectedLocation;

        Relay? restoredRelay = null;
        if (previouslySelectedRelayId != 0)
        {
            restoredRelay = Relays.FirstOrDefault(r => r.Id == previouslySelectedRelayId);
        }
        if (restoredRelay == null && !string.IsNullOrWhiteSpace(previouslySelectedRelayPhone))
        {
            restoredRelay = Relays.FirstOrDefault(r => IsSameRelay(r.PhoneNumber, previouslySelectedRelayPhone));
        }
        if (restoredRelay == null && !string.IsNullOrWhiteSpace(previouslySelectedRelayPhoneKey))
        {
            restoredRelay = Relays.FirstOrDefault(r =>
                string.Equals(RelayPhoneKey(r.PhoneNumber), previouslySelectedRelayPhoneKey, StringComparison.Ordinal));
        }
        if (restoredRelay != null
            && (string.IsNullOrWhiteSpace(scopedLocation)
                || string.Equals(NormalizeLocation(restoredRelay.Location), scopedLocation, StringComparison.Ordinal)))
        {
            SelectedRelay = restoredRelay;
        }
        else if (Relays.Count == 0)
        {
            SelectedRelay = null;
        }
        else if (!string.IsNullOrWhiteSpace(scopedLocation))
        {
            var firstInScopedLocation = Relays
                .Where(r => string.Equals(NormalizeLocation(r.Location), scopedLocation, StringComparison.Ordinal))
                .OrderBy(r => r.DisplayName)
                .FirstOrDefault();
            if (firstInScopedLocation != null)
            {
                SelectedRelay = firstInScopedLocation;
            }
            else
            {
                try
                {
                    _allowTransientRelayNull = true;
                    SelectedRelay = null;
                }
                finally
                {
                    _allowTransientRelayNull = false;
                }
            }
        }

        History.Clear();
        foreach (var item in snapshot.History)
        {
            History.Add(item);
        }
        Events.Clear();
        foreach (var ev in snapshot.Events)
        {
            Events.Add(ev);
        }
        ExportEventsCommand.RaiseCanExecuteChanged();
        Notifications.Clear();
        RefreshLocationScopedData();
        RefreshRelayScopedData();
        if (showBusy)
        {
            StatusMessage = $"Relays: {Relays.Count}, History: {History.Count}, Events: {Events.Count}";
            IsBusy = false;
        }
    }

    private async Task RefreshCommandsAsync(bool showBusy)
    {
        if (!ServerConfig.IsValid()) return;
        if (showBusy)
        {
            IsBusy = true;
            StatusMessage = "Se incarca comenzi...";
        }
        var commands = await _apiClient.GetCommandsAsync(ServerConfig, "", 1000);
        Commands.Clear();
        foreach (var item in commands.OrderByDescending(c => c.CreatedAt))
        {
            Commands.Add(item);
        }
        RefreshLocationScopedData();
        RefreshRelayScopedData();
        if (showBusy)
        {
            StatusMessage = $"Comenzi: {Commands.Count}";
            IsBusy = false;
        }
    }

    private async Task UploadSnapshotAsync()
    {
        if (!ServerConfig.IsValid()) return;
        IsBusy = true;
        StatusMessage = "Se upload-eaza snapshot...";
        var snapshot = new ServerSnapshot
        {
            Relays = Relays.ToList(),
            History = History.ToList(),
            Events = Events.ToList(),
            Locations = BuildSnapshotLocations()
        };
        var ok = await _apiClient.UploadSnapshotAsync(ServerConfig, snapshot);
        StatusMessage = ok ? "Snapshot upload reusit" : "Snapshot upload esuat";
        IsBusy = false;
    }

    private async Task AddLocationAsync()
    {
        var raw = (NewLocationName ?? string.Empty).Trim();
        if (string.IsNullOrWhiteSpace(raw))
        {
            StatusMessage = "Nume locatie lipsa";
            return;
        }
        var name = NormalizeLocation(raw);
        _manualLocations.Add(name);
        RefreshLocations();
        SelectedLocation = name;
        await UploadSnapshotAsync();
        StatusMessage = $"Locatie \"{name}\" adaugata";
    }

    private async Task RenameLocationAsync()
    {
        await RenameLocationAsync(SelectedLocation);
    }

    private async Task RenameLocationAsync(string? sourceLocation)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var source = NormalizeLocation(sourceLocation);
        if (string.IsNullOrWhiteSpace(source))
        {
            StatusMessage = "Selecteaza o locatie";
            return;
        }
        if (!string.Equals(SelectedLocation, source, StringComparison.Ordinal))
        {
            SelectedLocation = source;
        }
        var rawTarget = (NewLocationName ?? string.Empty).Trim();
        if (string.IsNullOrWhiteSpace(rawTarget))
        {
            StatusMessage = "Nume locatie nou lipsa";
            return;
        }
        var target = NormalizeLocation(rawTarget);
        if (string.Equals(source, target, StringComparison.Ordinal))
        {
            StatusMessage = "Locatia are deja acest nume";
            return;
        }

        var changed = false;
        for (var i = 0; i < Relays.Count; i++)
        {
            var relay = Relays[i];
            if (!string.Equals(NormalizeLocation(relay.Location), source, StringComparison.Ordinal)) continue;
            Relays[i] = new Relay
            {
                Id = relay.Id,
                Name = relay.Name,
                PhoneNumber = relay.PhoneNumber,
                Password = relay.Password,
                Location = target,
                Users = relay.Users.ToList(),
                LastSync = now,
                CloudBackup = relay.CloudBackup
            };
            changed = true;
        }

        var changedManual = false;
        if (_manualLocations.Remove(source))
        {
            _manualLocations.Add(target);
            changedManual = true;
        }
        else if (!Relays.Any(r => string.Equals(NormalizeLocation(r.Location), target, StringComparison.Ordinal)))
        {
            _manualLocations.Add(target);
            changedManual = true;
        }

        RefreshLocations();
        SelectedLocation = target;
        if (changed || changedManual)
        {
            await UploadSnapshotAsync();
        }
        StatusMessage = $"Locatie redenumita in \"{target}\"";
    }

    private async Task PromptRenameLocationAsync(string? sourceLocation)
    {
        var source = NormalizeLocation(sourceLocation);
        if (string.IsNullOrWhiteSpace(source))
        {
            StatusMessage = "Selecteaza o locatie";
            return;
        }
        var dialog = new RenameDialogWindow(
            "Rename locatie",
            "Nume nou pentru locatie:",
            source
        );
        var ok = dialog.ShowDialog() == true;
        var target = ok ? dialog.ResultText : "";
        if (string.IsNullOrWhiteSpace(target))
        {
            StatusMessage = "Rename locatie anulat";
            return;
        }
        NewLocationName = target;
        OnPropertyChanged(nameof(NewLocationName));
        await RenameLocationAsync(source);
    }

    private async Task DeleteLocationAsync()
    {
        await DeleteLocationAsync(SelectedLocation);
    }

    private async Task DeleteLocationAsync(string? sourceLocation)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var source = NormalizeLocation(sourceLocation);
        if (string.IsNullOrWhiteSpace(source))
        {
            StatusMessage = "Selecteaza o locatie";
            return;
        }
        var confirm = MessageBox.Show(
            $"Esti sigur ca vrei sa stergi locatia \"{source}\"?\nToate releele vor fi mutate in \"Fara locatie\".",
            "Confirmare stergere locatie",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning
        );
        if (confirm != MessageBoxResult.Yes)
        {
            StatusMessage = "Stergere locatie anulata";
            return;
        }
        if (!string.Equals(SelectedLocation, source, StringComparison.Ordinal))
        {
            SelectedLocation = source;
        }
        var changed = false;
        for (var i = 0; i < Relays.Count; i++)
        {
            var relay = Relays[i];
            if (!string.Equals(NormalizeLocation(relay.Location), source, StringComparison.Ordinal)) continue;
            Relays[i] = new Relay
            {
                Id = relay.Id,
                Name = relay.Name,
                PhoneNumber = relay.PhoneNumber,
                Password = relay.Password,
                Location = "",
                Users = relay.Users.ToList(),
                LastSync = now,
                CloudBackup = relay.CloudBackup
            };
            changed = true;
        }
        var changedManual = _manualLocations.Remove(source);
        RefreshLocations();
        SelectedRelay = LocationRelays.FirstOrDefault();
        if (changed || changedManual)
        {
            await UploadSnapshotAsync();
        }
        StatusMessage = $"Locatie \"{source}\" stearsa";
    }

    private async Task AddRelayAsync()
    {
        if (!ServerConfig.IsValid()) return;
        if (string.IsNullOrWhiteSpace(NewRelayName) || string.IsNullOrWhiteSpace(NewRelayPhone))
        {
            StatusMessage = "Numele si numarul sunt obligatorii";
            return;
        }

        var relay = new Relay
        {
            Id = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            Name = NewRelayName.Trim(),
            PhoneNumber = NewRelayPhone.Trim(),
            Password = "2005",
            Location = string.IsNullOrWhiteSpace(NewRelayLocation) ? (SelectedLocation ?? "") : NewRelayLocation.Trim(),
            Users = CreateEmptyUsers(),
            LastSync = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()
        };
        Relays.Add(relay);
        RefreshLocations();
        SelectedLocation = NormalizeLocation(relay.Location);
        SelectedRelay = relay;
        await UploadSnapshotAsync();
        var setupOptions = new RelayOnboardingOptions(
            QueryStart: OnboardingQueryStart,
            QueryEnd: OnboardingQueryEnd,
            ForcePasswordReset: OnboardingForcePasswordReset,
            SetDateTime: OnboardingSetDateTime,
            SetMaster: OnboardingSetMaster,
            ConfirmOn: OnboardingConfirmOn,
            ConfirmOff: OnboardingConfirmOff,
            QueryUsers: OnboardingQueryUsers,
            AutoAddAdmins: OnboardingAutoAddAdmins
        );
        await SetupRelayAsync(relay, setupOptions);
        StatusMessage = $"Releu {relay.Name} adaugat";
    }

    private async Task UpdateRelayAsync()
    {
        if (SelectedRelay == null) return;
        if (string.IsNullOrWhiteSpace(NewRelayName) || string.IsNullOrWhiteSpace(NewRelayPhone))
        {
            StatusMessage = "Numele si numarul sunt obligatorii";
            return;
        }

        var updated = new Relay
        {
            Id = SelectedRelay.Id,
            Name = NewRelayName.Trim(),
            PhoneNumber = NewRelayPhone.Trim(),
            Password = string.IsNullOrWhiteSpace(NewRelayPassword) ? "2005" : NewRelayPassword.Trim(),
            Location = NewRelayLocation.Trim(),
            Users = SelectedRelay.Users.ToList(),
            LastSync = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            CloudBackup = SelectedRelay.CloudBackup
        };

        ReplaceRelayInCollection(updated);
        RefreshLocations();
        SelectedRelay = updated;
        await UploadSnapshotAsync();
        StatusMessage = $"Releu {updated.Name} actualizat";
    }

    private async Task DeleteRelayAsync()
    {
        await DeleteRelayAsync(SelectedRelay);
    }

    private async Task DeleteRelayAsync(Relay? relay)
    {
        if (relay == null) return;
        var confirm = MessageBox.Show(
            $"Esti sigur ca vrei sa stergi releul \"{relay.DisplayName}\"?",
            "Confirmare stergere releu",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning
        );
        if (confirm != MessageBoxResult.Yes)
        {
            StatusMessage = "Stergere releu anulata";
            return;
        }

        if (!ServerConfig.IsValid())
        {
            StatusMessage = "Setari server incomplete";
            ShowErrorPopup("Stergere releu", "Setari server incomplete");
            return;
        }

        var deleteResult = await _apiClient.DeleteRelayDataAsync(ServerConfig, relay.PhoneNumber);
        if (!deleteResult.Ok)
        {
            StatusMessage = deleteResult.StatusCode == 404
                ? "Serverul nu are endpoint-ul de stergere releu (actualizeaza serverul)"
                : $"Stergere releu respinsa (HTTP {deleteResult.StatusCode})";
            var details = deleteResult.StatusCode == 404
                ? "Serverul nu are endpoint-ul /api/relays/{relayPhone}. Actualizeaza serverul."
                : $"Serverul a respins stergerea releului (HTTP {deleteResult.StatusCode}).";
            ShowErrorPopup("Stergere releu esuata", details);
            return;
        }

        RemoveRelayDataFromLocalCollections(relay.PhoneNumber);

        var relayName = relay.Name;
        var nextRelay = LocationRelays.FirstOrDefault(r => r.Id != relay.Id);

        var index = Relays
            .Select((r, idx) => new { r, idx })
            .FirstOrDefault(x => x.r.Id == relay.Id)?.idx ?? -1;
        if (index >= 0)
        {
            Relays.RemoveAt(index);
        }

        if (SelectedRelay?.Id == relay.Id)
        {
            SelectedRelay = null;
        }
        RefreshLocations();
        if (SelectedRelay == null)
        {
            SelectedRelay = nextRelay ?? LocationRelays.FirstOrDefault();
        }
        await UploadSnapshotAsync();
        StatusMessage = $"Releu {relayName} sters";
    }

    private void RemoveRelayDataFromLocalCollections(string relayPhone)
    {
        for (var i = Commands.Count - 1; i >= 0; i--)
        {
            if (IsSameRelay(Commands[i].RelayPhone, relayPhone))
            {
                Commands.RemoveAt(i);
            }
        }

        for (var i = History.Count - 1; i >= 0; i--)
        {
            if (IsSameRelay(History[i].RelayPhone, relayPhone))
            {
                History.RemoveAt(i);
            }
        }

        for (var i = Events.Count - 1; i >= 0; i--)
        {
            if (IsSameRelay(Events[i].RelayPhone, relayPhone))
            {
                Events.RemoveAt(i);
            }
        }
    }

    private async Task PromptEditRelayAsync(Relay? relay)
    {
        if (relay == null)
        {
            StatusMessage = "Selecteaza un releu";
            return;
        }
        var dialog = new EditRelayDialogWindow(
            relay.Name,
            relay.PhoneNumber,
            relay.Password,
            relay.Location
        );
        var ok = dialog.ShowDialog() == true;
        if (!ok)
        {
            StatusMessage = "Edit releu anulat";
            return;
        }

        var newName = dialog.RelayName.Trim();
        var newPhone = dialog.RelayPhone.Trim();
        var newPassword = dialog.RelayPassword.Trim();
        var newLocation = dialog.RelayLocation.Trim();
        if (string.Equals(newLocation, "Fara locatie", StringComparison.OrdinalIgnoreCase))
        {
            newLocation = "";
        }

        var updated = new Relay
        {
            Id = relay.Id,
            Name = newName,
            PhoneNumber = newPhone,
            Password = string.IsNullOrWhiteSpace(newPassword) ? relay.Password : newPassword,
            Location = newLocation,
            Users = relay.Users.ToList(),
            LastSync = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            CloudBackup = relay.CloudBackup
        };

        ReplaceRelayInCollection(updated);
        RefreshLocations();
        SelectedRelay = updated;
        await UploadSnapshotAsync();
        StatusMessage = $"Releu \"{newName}\" actualizat";
    }

    private async Task AddUserAsync()
    {
        if (SelectedRelay == null) return;
        if (string.IsNullOrWhiteSpace(NewUserPhone))
        {
            StatusMessage = "Telefon lipsa";
            return;
        }
        var requestedId = NewUserId;
        if (requestedId < 1 || requestedId > MaxRelayChannels)
        {
            StatusMessage = $"Pozitie invalida (1-{MaxRelayChannels})";
            return;
        }
        await RefreshCommandsAsync(false);
        var relay = SelectedRelay;
        if (relay == null) return;

        var reservedUserIds = GetReservedUserIdsForRelay(relay);
        var userId = requestedId;
        var slot = relay.Users.FirstOrDefault(u => u.Id == requestedId);
        var slotOccupied = slot != null && !string.IsNullOrWhiteSpace(slot.Phone);
        var slotReservedInQueue = reservedUserIds.Contains(requestedId);
        if (slotOccupied || slotReservedInQueue)
        {
            var nextFree = FindNextFreeUserId(relay, requestedId + 1, reservedUserIds);
            if (nextFree == null)
            {
                StatusMessage = slotReservedInQueue
                    ? $"Pozitia {requestedId} este deja rezervata in coada si nu exista locuri libere"
                    : $"Pozitia {requestedId} este ocupata si nu exista locuri libere";
                return;
            }
            userId = nextFree.Value;
        }

        var command = $"{relay.Password}A{userId:000}#{NewUserPhone.Trim()}#";
        var ok = await SendCommandToRelayAsync(relay, command, $"Adauga user {NewUserName} ({NewUserGroup})", refreshCommands: true, showErrorPopup: true);
        if (!ok)
        {
            var message = "Utilizatorul nu a putut fi adaugat in coada";
            StatusMessage = message;
            ShowErrorPopup("Eroare adaugare utilizator", message);
            return;
        }
        UpdateRelayUser(
            relay,
            userId,
            NewUserPhone.Trim(),
            NewUserName.Trim(),
            NewUserGroup.Trim(),
            true
        );
        await UploadSnapshotAsync();
        StatusMessage = $"Utilizator adaugat cu succes in coada (pozitia {userId})";
        reservedUserIds.Add(userId);
        relay = SelectedRelay ?? relay;
        var nextAfter = FindNextFreeUserId(relay, userId + 1, reservedUserIds) ?? FindNextFreeUserId(relay, 1, reservedUserIds);
        if (nextAfter != null)
        {
            NewUserId = nextAfter.Value;
            OnPropertyChanged(nameof(NewUserId));
        }
    }

    private void SelectAllLocationRelays()
    {
        foreach (var item in LocationRelaySelections)
        {
            item.IsSelected = true;
        }
        OnPropertyChanged(nameof(SelectedLocationRelaySummary));
        RefreshCommandStates();
    }

    private async Task AddUserToLocationAsync()
    {
        if (!ServerConfig.IsValid())
        {
            StatusMessage = "Setari server incomplete";
            return;
        }
        if (string.IsNullOrWhiteSpace(ServerConfig.GatewayId))
        {
            StatusMessage = "Gateway ID lipsa";
            return;
        }
        if (string.IsNullOrWhiteSpace(NewUserPhone))
        {
            StatusMessage = "Telefon lipsa";
            return;
        }

        var selectedRelays = LocationRelaySelections
            .Where(x => x.IsSelected)
            .Select(x => x.Relay)
            .ToList();
        if (selectedRelays.Count == 0)
        {
            StatusMessage = "Nu ai selectat relee in locatie";
            return;
        }
        await RefreshCommandsAsync(false);

        var okCount = 0;
        var errCount = 0;
        var errorDetails = new List<string>();

        foreach (var relay in selectedRelays)
        {
            var reservedUserIds = GetReservedUserIdsForRelay(relay);
            var slot = FindNextFreeSlot(relay, reservedUserIds);
            if (slot == null)
            {
                errCount++;
                errorDetails.Add($"{relay.Name}: nu exista canal liber (liber + nerezervat) in intervalul 1-{MaxRelayChannels}");
                continue;
            }

            var userId = slot.Id;
            var command = $"{relay.Password}A{userId:000}#{NewUserPhone.Trim()}#";
            var sent = await SendCommandToRelayAsync(
                relay,
                command,
                $"Adauga user locatie {NewUserName} ({NewUserGroup}) la pozitia {userId}",
                refreshCommands: false
            );

            if (!sent)
            {
                errCount++;
                errorDetails.Add($"{relay.Name}: comanda nu a fost acceptata in coada");
                continue;
            }

            var updatedRelay = ApplyRelayUserUpdate(
                relay,
                userId,
                NewUserPhone.Trim(),
                NewUserName.Trim(),
                NewUserGroup.Trim(),
                true
            );
            ReplaceRelayInCollection(updatedRelay);
            okCount++;
        }

        RefreshLocations();
        RefreshLocationScopedData();
        RefreshVisibleUsers();
        await UploadSnapshotAsync();
        await RefreshCommandsAsync(false);
        if (errCount > 0)
        {
            var shortDetails = string.Join("; ", errorDetails.Take(3));
            if (errorDetails.Count > 3) shortDetails += $" (+{errorDetails.Count - 3} alte erori)";
            StatusMessage = $"Adaugare locatie: {okCount} in coada, {errCount} erori. {shortDetails}";
            ShowErrorPopup(
                "Eroare adaugare pe locatie",
                $"Au aparut {errCount} erori:\n- {string.Join("\n- ", errorDetails)}"
            );
        }
        else
        {
            StatusMessage = $"Adaugare locatie: {okCount} utilizatori adaugati cu succes in coada";
        }
    }

    private async Task DeleteUserAsync(RelayUser? user)
    {
        if (SelectedRelay == null || user == null) return;
        var command = $"{SelectedRelay.Password}A{user.Id:000}##";
        var ok = await SendCommandAsync(command, $"Sterge user pozitia {user.Id}");
        if (!ok)
        {
            StatusMessage = "Comanda nu a fost acceptata de server";
            return;
        }
        UpdateRelayUser(SelectedRelay, user.Id, "", "", "general", false);
        await UploadSnapshotAsync();
        StatusMessage = $"Comanda stergere user {user.Id} trimisa";
    }

    private async Task DeleteAllUsersAsync()
    {
        if (SelectedRelay == null) return;
        var relay = SelectedRelay;
        var usersToDelete = relay.Users
            .Where(u => !string.IsNullOrWhiteSpace(u.Phone))
            .OrderBy(u => u.Id)
            .ToList();
        if (usersToDelete.Count == 0)
        {
            StatusMessage = "Nu exista utilizatori de sters pe releul selectat";
            return;
        }

        var confirmation = MessageBox.Show(
            $"Stergi toti utilizatorii ({usersToDelete.Count}) de pe releul selectat?",
            "Confirmare stergere totala",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning
        );
        if (confirmation != MessageBoxResult.Yes) return;

        var updatedRelay = relay;
        var okCount = 0;
        var failedIds = new List<int>();
        foreach (var user in usersToDelete)
        {
            var command = $"{relay.Password}A{user.Id:000}##";
            var sent = await SendCommandToRelayAsync(
                relay,
                command,
                $"Sterge user pozitia {user.Id}",
                refreshCommands: false,
                showErrorPopup: false
            );
            if (!sent)
            {
                failedIds.Add(user.Id);
                continue;
            }

            updatedRelay = ApplyRelayUserUpdate(updatedRelay, user.Id, "", "", "general", false);
            okCount++;
        }

        if (okCount > 0)
        {
            ApplyRelayUpdate(updatedRelay);
            await UploadSnapshotAsync();
        }
        await RefreshCommandsAsync(false);

        if (failedIds.Count > 0)
        {
            var listed = string.Join(", ", failedIds.Take(15));
            if (failedIds.Count > 15) listed += $" ... (+{failedIds.Count - 15})";
            StatusMessage = $"Stergere totala partiala: {okCount}/{usersToDelete.Count} comenzi trimise";
            ShowErrorPopup(
                "Stergere utilizatori incompleta",
                $"Nu s-au putut trimite {failedIds.Count} comenzi.\nPozitii: {listed}"
            );
            return;
        }

        StatusMessage = $"Stergere totala finalizata: {okCount} utilizatori";
    }

    private async Task StopRelayQueueAsync()
    {
        if (SelectedRelay == null) return;
        if (!ServerConfig.IsValid())
        {
            StatusMessage = "Setari server incomplete";
            return;
        }

        var relayPhone = SelectedRelay.PhoneNumber;
        var activeItems = RelayCommands
            .Where(c => IsSameRelay(c.RelayPhone, relayPhone) && IsActiveQueueStatus(c.Status))
            .ToList();
        if (activeItems.Count == 0)
        {
            StatusMessage = "Nu exista comenzi active in coada pentru releul selectat";
            return;
        }

        var confirm = MessageBox.Show(
            $"Opresti coada pentru releul selectat? Vor fi marcate ca esuate {activeItems.Count} comenzi active.",
            "Confirmare oprire coada",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning
        );
        if (confirm != MessageBoxResult.Yes) return;

        var okCount = 0;
        var failCount = 0;
        foreach (var item in activeItems)
        {
            var ok = await _apiClient.UpdateCommandStatusAsync(
                ServerConfig,
                item.Id,
                "failed",
                "Oprit manual din desktop"
            );
            if (ok) okCount++;
            else failCount++;
        }

        await RefreshCommandsAsync(false);
        if (failCount > 0)
        {
            StatusMessage = $"Oprire coada partiala: {okCount}/{activeItems.Count} comenzi oprite";
            ShowErrorPopup(
                "Oprire coada incompleta",
                $"Nu s-au putut opri {failCount} comenzi din {activeItems.Count}."
            );
            return;
        }

        StatusMessage = $"Coada releu oprita: {okCount} comenzi marcate ca esuate";
    }

    private async Task ClearRelayDatabaseAsync()
    {
        if (SelectedRelay == null) return;
        if (!ServerConfig.IsValid())
        {
            StatusMessage = "Setari server incomplete";
            return;
        }

        var relay = SelectedRelay;
        var usersWithPhone = relay.Users
            .Where(u => !string.IsNullOrWhiteSpace(u.Phone))
            .OrderBy(u => u.Id)
            .ToList();
        var activeItems = RelayCommands
            .Where(c => IsSameRelay(c.RelayPhone, relay.PhoneNumber) && IsActiveQueueStatus(c.Status))
            .ToList();
        if (usersWithPhone.Count == 0 && activeItems.Count == 0)
        {
            StatusMessage = "Nu exista date active de sters pentru releul selectat";
            return;
        }

        var confirm = MessageBox.Show(
            $"Stergi din baza datele releului selectat?\n\n" +
            $"- utilizatori in DB: {usersWithPhone.Count}\n" +
            $"- comenzi active in coada: {activeItems.Count}\n\n" +
            "Actiunea NU trimite comenzi catre releu.",
            "Confirmare stergere baza",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning
        );
        if (confirm != MessageBoxResult.Yes) return;

        var clearResult = await _apiClient.ClearRelayDatabaseAsync(ServerConfig, relay.PhoneNumber);
        if (!clearResult.Ok)
        {
            StatusMessage = clearResult.StatusCode == 404
                ? "Serverul nu are endpoint-ul de stergere baza releu (actualizeaza serverul)"
                : $"Stergere baza respinsa (HTTP {clearResult.StatusCode})";
            var details = clearResult.StatusCode == 404
                ? "Serverul nu are endpoint-ul /api/relays/{relayPhone}/clear-db. Actualizeaza serverul."
                : $"Serverul a respins stergerea bazei releului (HTTP {clearResult.StatusCode}).";
            ShowErrorPopup("Stergere baza esuata", details);
            return;
        }

        var updatedRelay = relay;
        foreach (var user in usersWithPhone)
        {
            updatedRelay = ApplyRelayUserUpdate(updatedRelay, user.Id, "", "", "general", false);
        }

        RemoveRelayDataFromLocalCollections(relay.PhoneNumber);
        ApplyRelayUpdate(updatedRelay);
        await UploadSnapshotAsync();
        await RefreshCommandsAsync(false);
        StatusMessage = $"Stergere baza finalizata: {usersWithPhone.Count} useri resetati, date releu curatate complet";
    }

    private async Task ImportCsvAsync()
    {
        if (SelectedRelay == null) return;
        var relay = SelectedRelay;
        var dialog = new OpenFileDialog
        {
            Filter = "CSV files (*.csv)|*.csv|All files (*.*)|*.*"
        };
        if (dialog.ShowDialog() != true) return;

        IsBusy = true;
        try
        {
            var text = File.ReadAllText(dialog.FileName, Encoding.UTF8);
            var lines = text.Split(new[] { "\r\n", "\n" }, StringSplitOptions.RemoveEmptyEntries);
            if (lines.Length == 0)
            {
                StatusMessage = "Fisier CSV gol sau fara continut util";
                return;
            }

            var delimiter = DetectCsvDelimiter(lines[0]);
            var firstRow = ParseCsvRow(lines[0], delimiter)
                .Select(v => v.Trim())
                .ToList();
            var headers = firstRow
                .Select(NormalizeCsvHeader)
                .ToList();
            var hasHeader = headers.Any(IsPhoneCsvHeader)
                || headers.Any(h => h is "id" or "pozitie" or "canal" or "channel" or "nume" or "name" or "grup" or "group");
            var firstDataRow = hasHeader ? 1 : 0;
            if (lines.Length <= firstDataRow)
            {
                StatusMessage = "Fisier CSV gol sau fara continut util";
                return;
            }

            int ok = 0;
            int err = 0;
            int skippedDuplicates = 0;
            var updatedRelay = relay;
            NormalizeRelayUsers(updatedRelay);
            var errorDetails = new List<string>();
            await RefreshCommandsAsync(false);
            var reservedUserIds = GetReservedUserIdsForRelay(updatedRelay);
            var existingPhoneKeys = new HashSet<string>(
                updatedRelay.Users
                    .Select(u => UserPhoneKey(u.Phone))
                    .Where(k => !string.IsNullOrWhiteSpace(k)),
                StringComparer.Ordinal
            );
            foreach (var key in GetReservedPhoneKeysForRelay(updatedRelay))
            {
                existingPhoneKeys.Add(key);
            }
            var importedPhoneKeys = new HashSet<string>(StringComparer.Ordinal);

            for (var i = firstDataRow; i < lines.Length; i++)
            {
                var values = ParseCsvRow(lines[i], delimiter)
                    .Select(v => v.Trim())
                    .ToList();
                var phone = ExtractPhoneFromImportRow(values, hasHeader ? headers : null);
                var normalizedPhone = NormalizeImportedPhone(phone);
                var phoneKey = UserPhoneKey(normalizedPhone);
                if (string.IsNullOrWhiteSpace(phoneKey))
                {
                    err++;
                    errorDetails.Add($"Linia {i + 1}: telefon invalid");
                    continue;
                }

                if (!importedPhoneKeys.Add(phoneKey) || existingPhoneKeys.Contains(phoneKey))
                {
                    skippedDuplicates++;
                    continue;
                }

                var slot = FindNextFreeSlot(updatedRelay, reservedUserIds);
                if (slot == null)
                {
                    err++;
                    errorDetails.Add($"Linia {i + 1}: nu mai exista pozitie libera in intervalul 1-{MaxRelayChannels}");
                    continue;
                }

                var userId = slot.Id;
                var command = $"{relay.Password}A{userId:000}#{normalizedPhone}#";
                var sent = await SendCommandToRelayAsync(
                    updatedRelay,
                    command,
                    $"Import CSV: {normalizedPhone}",
                    refreshCommands: false,
                    showErrorPopup: false
                );
                if (sent)
                {
                    updatedRelay = ApplyRelayUserUpdate(updatedRelay, userId, normalizedPhone, "", "general", true);
                    reservedUserIds.Add(userId);
                    existingPhoneKeys.Add(phoneKey);
                    ok++;
                    await Task.Delay(400);
                }
                else
                {
                    err++;
                    errorDetails.Add($"Linia {i + 1}: comanda nu a fost acceptata in coada");
                }
            }

            ApplyRelayUpdate(updatedRelay);
            await UploadSnapshotAsync();
            if (err > 0)
            {
                var shortDetails = string.Join("; ", errorDetails.Take(3));
                if (errorDetails.Count > 3) shortDetails += $" (+{errorDetails.Count - 3} alte erori)";
                StatusMessage = $"Import CSV: {ok} in coada, {skippedDuplicates} duplicate sarite, {err} erori. {shortDetails}";
                ShowErrorPopup(
                    "Eroare import CSV",
                    $"Importul a avut {err} erori:\n- {string.Join("\n- ", errorDetails)}"
                );
            }
            else
            {
                StatusMessage = $"Import CSV: {ok} utilizatori adaugati, {skippedDuplicates} duplicate sarite";
            }
        }
        catch (Exception ex)
        {
            StatusMessage = $"Eroare import CSV: {ex.Message}";
            ShowErrorPopup(
                "Eroare import CSV",
                $"Aplicatia a intampinat o eroare la import:\n{ex.Message}"
            );
        }
        finally
        {
            IsBusy = false;
        }
    }

    private static string NormalizeCsvHeader(string value)
    {
        return (value ?? string.Empty)
            .Trim()
            .TrimStart('\uFEFF')
            .ToLowerInvariant();
    }

    private static bool IsPhoneCsvHeader(string header)
    {
        return header is "telefon" or "phone" or "numar" or "numar_telefon" or "phone_number" or "telefon1" or "telefon2";
    }

    private static string ExtractPhoneFromImportRow(IReadOnlyList<string> values, IReadOnlyList<string>? headers)
    {
        if (values.Count == 0) return "";

        if (headers != null && headers.Count > 0)
        {
            for (var i = 0; i < headers.Count && i < values.Count; i++)
            {
                if (IsPhoneCsvHeader(headers[i]) && !string.IsNullOrWhiteSpace(values[i]))
                {
                    return values[i];
                }
            }
        }

        foreach (var value in values)
        {
            if (string.IsNullOrWhiteSpace(value)) continue;
            var digits = new string(value.Where(char.IsDigit).ToArray());
            if (digits.Length >= 6)
            {
                return value;
            }
        }

        return values[0];
    }

    private static string NormalizeImportedPhone(string? phone)
    {
        var raw = (phone ?? string.Empty).Trim();
        if (string.IsNullOrWhiteSpace(raw)) return "";
        var digits = new string(raw.Where(char.IsDigit).ToArray());
        return digits.Length >= 6 ? digits : raw;
    }

    private static char DetectCsvDelimiter(string headerLine)
    {
        var commaCount = 0;
        var semicolonCount = 0;
        var inQuotes = false;
        foreach (var ch in headerLine ?? string.Empty)
        {
            if (ch == '"')
            {
                inQuotes = !inQuotes;
                continue;
            }
            if (inQuotes) continue;
            if (ch == ',') commaCount++;
            if (ch == ';') semicolonCount++;
        }
        return semicolonCount > commaCount ? ';' : ',';
    }

    private static List<string> ParseCsvRow(string line, char delimiter)
    {
        var result = new List<string>();
        var current = new StringBuilder();
        var inQuotes = false;
        for (var i = 0; i < line.Length; i++)
        {
            var ch = line[i];
            if (ch == '"')
            {
                if (inQuotes && i + 1 < line.Length && line[i + 1] == '"')
                {
                    current.Append('"');
                    i++;
                    continue;
                }
                inQuotes = !inQuotes;
                continue;
            }
            if (ch == delimiter && !inQuotes)
            {
                result.Add(current.ToString());
                current.Clear();
                continue;
            }
            current.Append(ch);
        }
        result.Add(current.ToString());
        return result;
    }

    private void ExportCsv()
    {
        if (SelectedRelay == null) return;
        var dialog = new SaveFileDialog
        {
            Filter = "CSV files (*.csv)|*.csv",
            FileName = $"{SelectedRelay.Name}_utilizatori.csv"
        };
        if (dialog.ShowDialog() != true) return;

        var sb = new StringBuilder();
        sb.AppendLine("ID,Telefon,Nume,Grup,Data_Adaugare");
        foreach (var user in SelectedRelay.Users.Where(u => !string.IsNullOrWhiteSpace(u.Phone)))
        {
            var date = user.AddedDate.HasValue
                ? DateTimeOffset.FromUnixTimeMilliseconds(user.AddedDate.Value).ToString("dd.MM.yyyy", CultureInfo.InvariantCulture)
                : "";
            sb.AppendLine($"{user.Id},{user.Phone},{user.Name},{user.Group},{date}");
        }
        File.WriteAllText(dialog.FileName, sb.ToString(), Encoding.UTF8);
        StatusMessage = "Export CSV finalizat";
    }

    private void ExportEventsCsv()
    {
        var dialog = new SaveFileDialog
        {
            Filter = "CSV files (*.csv)|*.csv",
            FileName = "events.csv"
        };
        if (dialog.ShowDialog() != true) return;

        var sb = new StringBuilder();
        sb.AppendLine("Timp,Releu,Telefon Releu,Operat de,Mesaj");
        foreach (var ev in Events.OrderByDescending(e => e.Timestamp))
        {
            var time = DateTimeOffset.FromUnixTimeMilliseconds(ev.Timestamp)
                .ToLocalTime()
                .ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture);
            sb.AppendLine($"{time},{ev.RelayName},{ev.RelayPhone},{ev.OperatorPhone},{ev.Message}");
        }
        File.WriteAllText(dialog.FileName, sb.ToString(), Encoding.UTF8);
        StatusMessage = "Export evenimente finalizat";
    }

    private void ExportLocationEventsCsv()
    {
        if (LocationEvents.Count == 0)
        {
            StatusMessage = "Nu exista evenimente pentru locatia selectata";
            return;
        }

        var locationLabel = string.IsNullOrWhiteSpace(SelectedLocation) ? "locatie" : SelectedLocation!.Replace(' ', '_');
        var dialog = new SaveFileDialog
        {
            Filter = "CSV files (*.csv)|*.csv",
            FileName = $"events_{locationLabel}.csv"
        };
        if (dialog.ShowDialog() != true) return;

        var sb = new StringBuilder();
        sb.AppendLine("Timp,Releu,Telefon Releu,Operat de,Mesaj");
        foreach (var ev in LocationEvents.OrderByDescending(e => e.Timestamp))
        {
            var time = DateTimeOffset.FromUnixTimeMilliseconds(ev.Timestamp)
                .ToLocalTime()
                .ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture);
            sb.AppendLine($"{time},{ev.RelayName},{ev.RelayPhone},{ev.OperatorPhone},{ev.Message}");
        }
        File.WriteAllText(dialog.FileName, sb.ToString(), Encoding.UTF8);
        StatusMessage = "Export evenimente locatie finalizat";
    }

    private bool CanScrapeEvents()
    {
        if (IsBusy) return false;
        if (!ServerConfig.IsValid()) return false;
        if (string.IsNullOrWhiteSpace(ServerConfig.GatewayId)) return false;
        if (SelectedRelay == null) return false;
        return TryBuildScrapeRange(out _, out _);
    }

    private async Task ScrapeEventsAsync()
    {
        if (SelectedRelay == null) return;
        if (!TryBuildScrapeRange(out var start, out var end))
        {
            StatusMessage = "Interval invalid pentru scraping";
            return;
        }
        var cmd = $"SCRAPE_EVENTS|{start}|{end}";
        var result = await _apiClient.CreateCommandAsync(
            ServerConfig,
            SelectedRelay.PhoneNumber,
            cmd,
            "Scrape events",
            "desktop"
        );
        if (!result.Ok)
        {
            StatusMessage = result.StatusCode == 404
                ? "Serverul nu are /api/commands"
                : $"Cerere respinsa (HTTP {result.StatusCode})";
            return;
        }
        StatusMessage = "Cerere scraping trimisa catre gateway";
        await RefreshCommandsAsync(false);
    }

    private async Task SyncSmsAsync()
    {
        if (SelectedRelay == null)
        {
            StatusMessage = "Selecteaza un releu";
            return;
        }
        if (!ServerConfig.IsValid())
        {
            StatusMessage = "Setari server incomplete";
            return;
        }
        if (string.IsNullOrWhiteSpace(ServerConfig.GatewayId))
        {
            StatusMessage = "Gateway ID lipsa";
            return;
        }

        var result = await _apiClient.CreateCommandAsync(
            ServerConfig,
            SelectedRelay.PhoneNumber,
            "SYNC_SMS",
            "Sincronizare SMS releu",
            "desktop_sync"
        );

        if (!result.Ok)
        {
            StatusMessage = result.StatusCode == 404
                ? "Serverul nu are /api/commands"
                : $"Cerere respinsa (HTTP {result.StatusCode})";
            return;
        }

        StatusMessage = "Cerere Sync SMS trimisa catre gateway";
        await RefreshCommandsAsync(false);
    }

    private bool TryBuildScrapeRange(out long start, out long end)
    {
        start = 0;
        end = 0;
        if (EventStartDate == null || EventEndDate == null) return false;
        if (!TimeSpan.TryParse(EventStartTime, out var startTime)) return false;
        if (!TimeSpan.TryParse(EventEndTime, out var endTime)) return false;
        var startDt = EventStartDate.Value.Date + startTime;
        var endDt = EventEndDate.Value.Date + endTime;
        if (startDt > endDt) return false;
        start = new DateTimeOffset(startDt).ToUnixTimeMilliseconds();
        end = new DateTimeOffset(endDt).ToUnixTimeMilliseconds();
        return true;
    }

    private async Task<bool> SendCommandAsync(string command, string description)
    {
        if (SelectedRelay == null)
        {
            StatusMessage = "Selecteaza un releu";
            return false;
        }
        return await SendCommandToRelayAsync(SelectedRelay, command, description, refreshCommands: true, showErrorPopup: true, source: "desktop");
    }

    private async Task<bool> SendCommandToRelayAsync(
        Relay relay,
        string command,
        string description,
        bool refreshCommands,
        bool showErrorPopup = true,
        string source = "desktop"
    )
    {
        if (!ServerConfig.IsValid())
        {
            StatusMessage = "Setari server incomplete";
            if (showErrorPopup) ShowErrorPopup("Eroare configurare", "Setari server incomplete");
            return false;
        }
        if (string.IsNullOrWhiteSpace(ServerConfig.GatewayId))
        {
            StatusMessage = "Gateway ID lipsa";
            if (showErrorPopup) ShowErrorPopup("Eroare configurare", "Gateway ID lipsa");
            return false;
        }
        if (string.IsNullOrWhiteSpace(command)) return false;
        ApiClient.CommandCreateResult result;
        try
        {
            result = await _apiClient.CreateCommandAsync(ServerConfig, relay.PhoneNumber, command, description, source);
        }
        catch (Exception ex)
        {
            StatusMessage = $"Eroare trimitere comanda: {ex.Message}";
            if (showErrorPopup) ShowErrorPopup("Eroare trimitere comanda", ex.Message);
            return false;
        }
        if (!result.Ok)
        {
            StatusMessage = result.StatusCode == 404
                ? "Serverul nu are /api/commands (actualizeaza serverul)"
                : $"Comanda respinsa (HTTP {result.StatusCode})";
            if (showErrorPopup)
            {
                var details = result.StatusCode == 404
                    ? "Serverul nu are endpoint-ul /api/commands. Actualizeaza serverul."
                    : $"Comanda a fost respinsa de server (HTTP {result.StatusCode}).";
                ShowErrorPopup("Eroare trimitere comanda", details);
            }
            return false;
        }
        StatusMessage = "Comanda trimisa cu succes in coada";
        if (refreshCommands)
        {
            await RefreshCommandsAsync(false);
        }
        return true;
    }

    private static void ShowErrorPopup(string title, string details)
    {
        MessageBox.Show(details, title, MessageBoxButton.OK, MessageBoxImage.Error);
    }

    private async Task SetupRelayAsync(Relay relay, RelayOnboardingOptions options)
    {
        if (!ServerConfig.IsValid()) return;
        if (string.IsNullOrWhiteSpace(ServerConfig.GatewayId))
        {
            StatusMessage = "Gateway ID lipsa";
            return;
        }
        const string setupPassword = "2005";
        var queryStart = Math.Clamp(options.QueryStart, 1, MaxRelayChannels);
        var queryEnd = Math.Clamp(options.QueryEnd, 1, MaxRelayChannels);
        if (queryStart > queryEnd)
        {
            (queryStart, queryEnd) = (queryEnd, queryStart);
        }
        var timeStamp = DateTime.Now.ToString("ddMMyyHHmm", CultureInfo.InvariantCulture);
        var commands = new List<(string Command, string Description, int DelayAfterMs, string Source)>();
        if (options.ForcePasswordReset)
        {
            commands.Add(("1234P2005", "Setare parola standard 2005", 0, "desktop_setup"));
        }
        if (options.SetDateTime)
        {
            commands.Add(($"{setupPassword}T{timeStamp}", $"Setare data/ora {timeStamp}", 0, "desktop_setup"));
        }
        if (options.ConfirmOn)
        {
            commands.Add(($"{setupPassword}GON10#RIDICARE/DESCHIDERE#", "Setare confirmare deschidere", 0, "desktop_setup"));
        }
        if (options.ConfirmOff)
        {
            commands.Add(($"{setupPassword}GOFF##", "Anulare confirmare inchidere", 0, "desktop_setup"));
        }
        if (options.QueryUsers)
        {
            var querySource = options.AutoAddAdmins ? "desktop_setup" : "desktop_no_auto_admin";
            commands.Add(($"{setupPassword}AL{queryStart:000}#{queryEnd:000}#", $"Interogare utilizatori {queryStart}-{queryEnd}", 0, querySource));
        }

        var masterPhone = (ServerConfig.MasterPhone ?? string.Empty).Trim();
        if (options.SetMaster && !string.IsNullOrWhiteSpace(masterPhone))
        {
            var insertIndex = Math.Min(commands.Count, 2);
            commands.Insert(insertIndex, ($"{setupPassword}A001#{masterPhone}#", "Setare master 001", 0, "desktop_setup"));
        }

        StatusMessage = "Trimit comenzi initializare...";
        var okCount = 0;
        var failCount = 0;
        foreach (var item in commands)
        {
            var ok = false;
            try
            {
                ok = await SendCommandToRelayAsync(relay, item.Command, item.Description, refreshCommands: false, showErrorPopup: false, source: item.Source);
            }
            catch
            {
                ok = false;
            }
            if (ok)
            {
                okCount++;
            }
            else
            {
                failCount++;
            }
            if (item.DelayAfterMs > 0)
            {
                await Task.Delay(item.DelayAfterMs);
            }
        }
        try
        {
            await RefreshCommandsAsync(false);
        }
        catch
        {
            // Keep setup flow resilient even if command list refresh fails.
        }
        StatusMessage = $"Initializare trimisa ({okCount}/{commands.Count} comenzi, esuate: {failCount})";
    }

    private string BuildQueryCommand()
    {
        if (SelectedRelay == null) return "";
        var start = Math.Clamp(QueryStart, 1, MaxRelayChannels);
        var end = Math.Clamp(QueryEnd, 1, MaxRelayChannels);
        return $"{SelectedRelay.Password}AL{start:000}#{end:000}#";
    }

    private string BuildPasswordCommand()
    {
        if (SelectedRelay == null) return "";
        if (string.IsNullOrWhiteSpace(NewPassword)) return "";
        return $"{SelectedRelay.Password}P{NewPassword.Trim()}";
    }

    private string BuildTimerCommand()
    {
        if (SelectedRelay == null) return "";
        var seconds = Math.Clamp(TimerSeconds, 0, 999);
        return $"{SelectedRelay.Password}GOT{seconds}#";
    }

    private string BuildSimpleCommand(string suffix)
    {
        return SelectedRelay == null ? "" : $"{SelectedRelay.Password}{suffix}";
    }

    private void NormalizeRelayUsers(Relay relay)
    {
        if (relay.Users.Count == MaxRelayChannels) return;
        var users = new List<RelayUser>();
        var existing = relay.Users.ToDictionary(u => u.Id, u => u);
        for (var i = 1; i <= MaxRelayChannels; i++)
        {
            if (existing.TryGetValue(i, out var user))
            {
                users.Add(user);
            }
            else
            {
                users.Add(new RelayUser { Id = i });
            }
        }
        relay.Users = users;
    }

    private List<RelayUser> CreateEmptyUsers()
    {
        var users = new List<RelayUser>();
        for (var i = 1; i <= MaxRelayChannels; i++)
        {
            users.Add(new RelayUser { Id = i });
        }
        return users;
    }

    private void RefreshVisibleUsers()
    {
        VisibleUsers.Clear();
        if (SelectedRelay == null) return;
        foreach (var user in SelectedRelay.Users.Where(u => !string.IsNullOrWhiteSpace(u.Phone)))
        {
            VisibleUsers.Add(user);
        }
    }

    private static int? FindNextFreeUserId(Relay relay, int startId, ISet<int>? reservedUserIds = null)
    {
        if (startId < 1) startId = 1;
        for (var i = startId; i <= MaxRelayChannels; i++)
        {
            var slot = relay.Users.FirstOrDefault(u => u.Id == i);
            var isReserved = reservedUserIds != null && reservedUserIds.Contains(i);
            if ((slot == null || string.IsNullOrWhiteSpace(slot.Phone)) && !isReserved)
            {
                return i;
            }
        }
        return null;
    }

    private static RelayUser? FindNextFreeSlot(Relay relay, ISet<int>? reservedUserIds = null)
    {
        return relay.Users
            .Where(u => u.Known && string.IsNullOrWhiteSpace(u.Phone) && (reservedUserIds == null || !reservedUserIds.Contains(u.Id)))
            .OrderBy(u => u.Id)
            .FirstOrDefault()
            ?? relay.Users
                .Where(u => string.IsNullOrWhiteSpace(u.Phone) && (reservedUserIds == null || !reservedUserIds.Contains(u.Id)))
                .OrderBy(u => u.Id)
                .FirstOrDefault();
    }

    private static bool IsSlotFreeForAssignment(Relay relay, int userId, ISet<int>? reservedUserIds = null)
    {
        if (userId < 1 || userId > MaxRelayChannels) return false;
        if (reservedUserIds != null && reservedUserIds.Contains(userId)) return false;
        var slot = relay.Users.FirstOrDefault(u => u.Id == userId);
        return slot == null || string.IsNullOrWhiteSpace(slot.Phone);
    }

    private HashSet<int> GetReservedUserIdsForRelay(Relay relay)
    {
        var relayKey = RelayPhoneKey(relay.PhoneNumber);
        var reserved = new HashSet<int>();
        if (string.IsNullOrWhiteSpace(relayKey))
        {
            return reserved;
        }

        foreach (var item in Commands)
        {
            if (!string.Equals(RelayPhoneKey(item.RelayPhone), relayKey, StringComparison.Ordinal)) continue;
            if (!IsActiveQueueStatus(item.Status)) continue;
            if (!TryParseUserAssignmentCommand(item.Command, out var slotId, out var phonePayload)) continue;
            if (string.IsNullOrWhiteSpace(phonePayload)) continue;
            reserved.Add(slotId);
        }

        return reserved;
    }

    private HashSet<string> GetReservedPhoneKeysForRelay(Relay relay)
    {
        var relayKey = RelayPhoneKey(relay.PhoneNumber);
        var reserved = new HashSet<string>(StringComparer.Ordinal);
        if (string.IsNullOrWhiteSpace(relayKey))
        {
            return reserved;
        }

        foreach (var item in Commands)
        {
            if (!string.Equals(RelayPhoneKey(item.RelayPhone), relayKey, StringComparison.Ordinal)) continue;
            if (!IsActiveQueueStatus(item.Status)) continue;
            if (!TryParseUserAssignmentCommand(item.Command, out _, out var phonePayload)) continue;
            var key = UserPhoneKey(phonePayload);
            if (!string.IsNullOrWhiteSpace(key))
            {
                reserved.Add(key);
            }
        }

        return reserved;
    }

    private static bool IsActiveQueueStatus(string? status)
    {
        return string.Equals(status, "pending", StringComparison.OrdinalIgnoreCase)
            || string.Equals(status, "sent_waiting", StringComparison.OrdinalIgnoreCase);
    }

    private static bool TryParseUserAssignmentCommand(string? command, out int userId, out string phonePayload)
    {
        userId = 0;
        phonePayload = "";
        if (string.IsNullOrWhiteSpace(command)) return false;

        var text = command.Trim();
        for (var i = 0; i <= text.Length - 5; i++)
        {
            if (char.ToUpperInvariant(text[i]) != 'A') continue;
            if (!char.IsDigit(text[i + 1]) || !char.IsDigit(text[i + 2]) || !char.IsDigit(text[i + 3])) continue;
            if (text[i + 4] != '#') continue;
            if (!int.TryParse(text.Substring(i + 1, 3), out userId)) return false;

            var payloadStart = i + 5;
            var payloadEnd = text.IndexOf('#', payloadStart);
            if (payloadEnd < 0) return false;
            phonePayload = text.Substring(payloadStart, payloadEnd - payloadStart).Trim();
            return true;
        }

        return false;
    }

    private void RefreshRelayScopedData()
    {
        RelayHistory.Clear();
        RelayEvents.Clear();
        RelayCommands.Clear();
        if (SelectedRelay == null)
        {
            ClearSetupState();
            return;
        }
        var relayPhone = SelectedRelay.PhoneNumber;
        foreach (var item in History.Where(h => IsSameRelay(h.RelayPhone, relayPhone)))
        {
            RelayHistory.Add(item);
        }
        foreach (var item in Events.Where(e => IsSameRelay(e.RelayPhone, relayPhone)))
        {
            RelayEvents.Add(item);
        }
        foreach (var item in Commands.Where(c => IsSameRelay(c.RelayPhone, relayPhone)))
        {
            RelayCommands.Add(item);
        }
        RefreshSetupState();
    }

    private sealed record SetupStep(string Label, string MarkerHint, Func<string, bool> Matches);
    private sealed record RelayOnboardingOptions(
        int QueryStart,
        int QueryEnd,
        bool ForcePasswordReset,
        bool SetDateTime,
        bool SetMaster,
        bool ConfirmOn,
        bool ConfirmOff,
        bool QueryUsers,
        bool AutoAddAdmins
    );

    private void RefreshSetupState()
    {
        if (SelectedRelay == null)
        {
            ClearSetupState();
            return;
        }

        var relayCommands = RelayCommands
            .OrderBy(c => c.CreatedAt)
            .ToList();

        var setupCommands = relayCommands
            .Where(c => IsSetupCommand(c.Command))
            .ToList();

        if (setupCommands.Count == 0)
        {
            ClearSetupState();
            return;
        }

        var onboardingSetupCommands = setupCommands
            .Where(IsOnboardingSetupCommand)
            .ToList();
        var setupCandidates = onboardingSetupCommands.Count > 0 ? onboardingSetupCommands : setupCommands;
        var latestStart = ResolveSetupBatchStart(setupCandidates);

        var batch = setupCandidates
            .Where(c => c.CreatedAt >= latestStart)
            .ToList();

        var setupSteps = BuildSetupStepsForBatch(batch);
        if (setupSteps.Count == 0)
        {
            ClearSetupState();
            return;
        }

        var stepMatches = setupSteps
            .Select(step => batch.LastOrDefault(c => step.Matches(c.Command)))
            .ToList();

        var effectiveDone = new bool[setupSteps.Count];
        for (var i = 0; i < stepMatches.Count; i++)
        {
            var current = stepMatches[i];
            if (current == null) continue;
            if (IsDoneStatus(current.Status))
            {
                effectiveDone[i] = true;
                continue;
            }

            // Pasul 1 nu are marker SMS. Daca deja exista pasi ulteriori in acelasi batch,
            // consideram resetul finalizat implicit si continuam progresul vizual.
            if (i == 0 && IsForcePasswordSetupCommand(current.Command) && !IsFailedStatus(current.Status))
            {
                var hasLaterStep = stepMatches.Skip(i + 1).Any(match => match != null);
                if (hasLaterStep)
                {
                    effectiveDone[i] = true;
                }
            }
        }

        var doneCount = effectiveDone.Count(x => x);
        var failedIndex = stepMatches.FindIndex(c => c != null && IsFailedStatus(c.Status));
        var activeIndex = -1;
        for (var i = 0; i < stepMatches.Count; i++)
        {
            var item = stepMatches[i];
            if (item != null && IsWaitingStatus(item.Status) && !effectiveDone[i])
            {
                activeIndex = i;
                break;
            }
        }
        if (activeIndex < 0)
        {
            for (var i = 0; i < stepMatches.Count; i++)
            {
                if (stepMatches[i] == null && !effectiveDone[i])
                {
                    activeIndex = i;
                    break;
                }
            }
        }

        SetupStateVisible = true;

        if (failedIndex >= 0)
        {
            var failedStep = setupSteps[failedIndex];
            SetupStateTitle = "Setup blocat";
            SetupStateDetails = $"{failedStep.Label} a esuat";
            SetupWaitingMarker = $"Marker asteptat: {failedStep.MarkerHint}";
            SetupElapsed = "";
        }
        else if (doneCount == setupSteps.Count)
        {
            SetupStateTitle = "Setup finalizat";
            SetupStateDetails = $"{doneCount}/{setupSteps.Count} pasi confirmati";
            SetupWaitingMarker = "Nu exista marker in asteptare";
            SetupElapsed = "";
        }
        else if (activeIndex >= 0)
        {
            var activeStep = setupSteps[activeIndex];
            var activeCommand = stepMatches[activeIndex];
            SetupStateTitle = "Setup in curs";
            SetupStateDetails = $"{activeStep.Label} ({doneCount}/{setupSteps.Count} confirmati)";
            SetupWaitingMarker = $"Marker asteptat: {activeStep.MarkerHint}";
            SetupElapsed = activeCommand == null ? "" : $"In asteptare de {FormatElapsed(activeCommand.CreatedAt)}";
        }
        else
        {
            SetupStateTitle = "Setup in curs";
            SetupStateDetails = $"{doneCount}/{setupSteps.Count} pasi confirmati";
            SetupWaitingMarker = "";
            SetupElapsed = "";
        }

        var lastSms = RelayEvents
            .OrderByDescending(e => e.Timestamp)
            .Select(e => e.Message)
            .FirstOrDefault(msg => !string.IsNullOrWhiteSpace(msg)) ?? "";
        SetupLastSms = string.IsNullOrWhiteSpace(lastSms) ? "" : $"Ultimul SMS: {TrimText(lastSms, 120)}";
    }

    private static List<SetupStep> BuildSetupStepsForBatch(List<CommandQueueItem> batch)
    {
        var steps = new List<(string Name, string MarkerHint, Func<string, bool> Matches)>();

        if (batch.Any(c => IsForcePasswordSetupCommand(c.Command)))
        {
            steps.Add((
                "reset parola la 2005",
                "Timeout fix 15s (fara marker SMS)",
                cmd => IsForcePasswordSetupCommand(cmd)
            ));
        }

        if (batch.Any(c => c.Command.StartsWith("2005T", StringComparison.OrdinalIgnoreCase)))
        {
            steps.Add((
                "setare data/ora",
                "Set Time OK",
                cmd => cmd.StartsWith("2005T", StringComparison.OrdinalIgnoreCase)
            ));
        }

        if (batch.Any(c => c.Command.StartsWith("2005A001#", StringComparison.OrdinalIgnoreCase)))
        {
            steps.Add((
                "setare admin master (001)",
                "001:",
                cmd => cmd.StartsWith("2005A001#", StringComparison.OrdinalIgnoreCase)
            ));
        }

        if (batch.Any(c => string.Equals(c.Command, "2005GON10#RIDICARE/DESCHIDERE#", StringComparison.OrdinalIgnoreCase)))
        {
            steps.Add((
                "mesaj confirmare ON",
                "Relay ON will return SMS",
                cmd => string.Equals(cmd, "2005GON10#RIDICARE/DESCHIDERE#", StringComparison.OrdinalIgnoreCase)
            ));
        }

        if (batch.Any(c => string.Equals(c.Command, "2005GOFF##", StringComparison.OrdinalIgnoreCase)))
        {
            steps.Add((
                "mesaj confirmare OFF",
                "Relay OFF will not return SMS",
                cmd => string.Equals(cmd, "2005GOFF##", StringComparison.OrdinalIgnoreCase)
            ));
        }

        if (batch.Any(c => Regex.IsMatch(c.Command, "^2005AL\\d{3}#\\d{3}#$", RegexOptions.IgnoreCase)))
        {
            var latestQuery = batch
                .LastOrDefault(c => Regex.IsMatch(c.Command, "^2005AL\\d{3}#\\d{3}#$", RegexOptions.IgnoreCase));
            var queryMarker = ExtractQueryEndMarker(latestQuery?.Command);
            var markerHint = queryMarker == null
                ? "Ultimul slot interogat (ex: 999:)"
                : $"Ultimul slot interogat (ex: {queryMarker}:)";

            steps.Add((
                "query utilizatori",
                markerHint,
                cmd => Regex.IsMatch(cmd, "^2005AL\\d{3}#\\d{3}#$", RegexOptions.IgnoreCase)
            ));
        }

        var total = steps.Count;
        return steps
            .Select((step, index) => new SetupStep(
                $"Pas {index + 1}/{total}: {step.Name}",
                step.MarkerHint,
                step.Matches
            ))
            .ToList();
    }

    private static long ResolveSetupBatchStart(List<CommandQueueItem> setupCommands)
    {
        const string resetCommand = "1234P2005";
        var nowUnixMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var staleIsolatedResetMs = (long)TimeSpan.FromMinutes(30).TotalMilliseconds;

        var resetStarts = setupCommands
            .Where(c => string.Equals(c.Command, resetCommand, StringComparison.OrdinalIgnoreCase))
            .Select(c => c.CreatedAt)
            .OrderBy(ts => ts)
            .ToList();

        if (resetStarts.Count == 0)
        {
            return setupCommands[0].CreatedAt;
        }

        for (var i = resetStarts.Count - 1; i >= 0; i--)
        {
            var start = resetStarts[i];
            var hasProgressAfterReset = setupCommands.Any(c =>
                c.CreatedAt > start
                && !string.Equals(c.Command, resetCommand, StringComparison.OrdinalIgnoreCase));
            if (hasProgressAfterReset)
            {
                return start;
            }
        }

        var latestReset = resetStarts[^1];
        var isRecentIsolatedReset = latestReset > 0 && (nowUnixMs - latestReset) <= staleIsolatedResetMs;
        if (isRecentIsolatedReset)
        {
            return latestReset;
        }

        var lastNonReset = setupCommands
            .Where(c => !string.Equals(c.Command, resetCommand, StringComparison.OrdinalIgnoreCase))
            .OrderByDescending(c => c.CreatedAt)
            .FirstOrDefault();
        if (lastNonReset != null)
        {
            return lastNonReset.CreatedAt;
        }

        return latestReset;
    }

    private static bool IsOnboardingSetupCommand(CommandQueueItem item)
    {
        if (item == null) return false;
        if (!IsSetupCommand(item.Command)) return false;
        return string.Equals(item.Source, "desktop_setup", StringComparison.OrdinalIgnoreCase)
            || string.Equals(item.Source, "desktop_no_auto_admin", StringComparison.OrdinalIgnoreCase);
    }

    private void ClearSetupState()
    {
        SetupStateVisible = false;
        SetupStateTitle = "";
        SetupStateDetails = "";
        SetupWaitingMarker = "";
        SetupElapsed = "";
        SetupLastSms = "";
    }

    private static bool IsSetupCommand(string command)
    {
        if (string.IsNullOrWhiteSpace(command)) return false;
        if (IsForcePasswordSetupCommand(command)) return true;
        if (command.StartsWith("2005T", StringComparison.OrdinalIgnoreCase)) return true;
        if (command.StartsWith("2005A001#", StringComparison.OrdinalIgnoreCase)) return true;
        if (string.Equals(command, "2005GON10#RIDICARE/DESCHIDERE#", StringComparison.OrdinalIgnoreCase)) return true;
        if (string.Equals(command, "2005GOFF##", StringComparison.OrdinalIgnoreCase)) return true;
        if (Regex.IsMatch(command, "^2005AL\\d{3}#\\d{3}#$", RegexOptions.IgnoreCase)) return true;
        return false;
    }

    private static bool IsForcePasswordSetupCommand(string command)
    {
        return string.Equals(command, "1234P2005", StringComparison.OrdinalIgnoreCase);
    }

    private static string? ExtractQueryEndMarker(string? command)
    {
        if (string.IsNullOrWhiteSpace(command)) return null;
        var match = Regex.Match(command, "^2005AL\\d{3}#(\\d{3})#$", RegexOptions.IgnoreCase);
        if (!match.Success || match.Groups.Count < 2) return null;
        return match.Groups[1].Value;
    }

    private static bool IsDoneStatus(string? status)
    {
        return string.Equals(status, "done", StringComparison.OrdinalIgnoreCase);
    }

    private static bool IsFailedStatus(string? status)
    {
        return string.Equals(status, "failed", StringComparison.OrdinalIgnoreCase);
    }

    private static bool IsWaitingStatus(string? status)
    {
        return string.Equals(status, "pending", StringComparison.OrdinalIgnoreCase)
            || string.Equals(status, "sent_waiting", StringComparison.OrdinalIgnoreCase)
            || string.Equals(status, "sent", StringComparison.OrdinalIgnoreCase)
            || string.Equals(status, "processing", StringComparison.OrdinalIgnoreCase);
    }

    private static string FormatElapsed(long createdAtUnixMs)
    {
        if (createdAtUnixMs <= 0) return "";
        var created = DateTimeOffset.FromUnixTimeMilliseconds(createdAtUnixMs);
        var delta = DateTimeOffset.UtcNow - created.ToUniversalTime();
        if (delta.TotalSeconds < 60) return $"{Math.Max(1, (int)delta.TotalSeconds)} sec";
        if (delta.TotalMinutes < 60) return $"{(int)delta.TotalMinutes} min";
        return $"{(int)delta.TotalHours}h {(delta.Minutes)}m";
    }

    private static string TrimText(string value, int maxLength)
    {
        if (string.IsNullOrWhiteSpace(value)) return "";
        var compact = value.Replace('\r', ' ').Replace('\n', ' ').Trim();
        if (compact.Length <= maxLength) return compact;
        return compact[..maxLength].TrimEnd() + "...";
    }

    private void RefreshLocations()
    {
        var existing = new HashSet<string>(Locations, StringComparer.Ordinal);
        var source = Relays
            .Select(r => NormalizeLocation(r.Location))
            .Concat(_manualLocations.Select(NormalizeLocation))
            .Distinct(StringComparer.Ordinal)
            .OrderBy(x => x)
            .ToList();

        Locations.Clear();
        foreach (var location in source)
        {
            Locations.Add(location);
        }

        if (Locations.Count == 0)
        {
            SelectedLocation = null;
            return;
        }

        if (SelectedLocation == null || !source.Contains(SelectedLocation))
        {
            if (!string.IsNullOrWhiteSpace(_lastSelectedLocation) && source.Contains(_lastSelectedLocation))
            {
                SelectedLocation = _lastSelectedLocation;
            }
            else
            {
                SelectedLocation = source[0];
            }
        }
        else if (!existing.Contains(SelectedLocation))
        {
            OnPropertyChanged(nameof(SelectedLocation));
        }
    }

    private void RefreshLocationScopedData()
    {
        var previousSelectionById = LocationRelaySelections
            .GroupBy(x => x.Relay.Id)
            .ToDictionary(g => g.Key, g => g.Last().IsSelected);
        var previousSelectionByPhoneKey = LocationRelaySelections
            .Select(x => new { Key = RelayPhoneKey(x.Relay.PhoneNumber), x.IsSelected })
            .Where(x => !string.IsNullOrWhiteSpace(x.Key))
            .GroupBy(x => x.Key, StringComparer.Ordinal)
            .ToDictionary(g => g.Key, g => g.Last().IsSelected, StringComparer.Ordinal);
        var previousSelectedRelayId = SelectedRelay?.Id ?? 0;
        var previousSelectedRelayPhoneKey = SelectedRelay == null ? "" : RelayPhoneKey(SelectedRelay.PhoneNumber);

        foreach (var item in LocationRelaySelections)
        {
            item.PropertyChanged -= OnLocationRelaySelectionChanged;
        }

        LocationRelaySelections.Clear();
        LocationRelays.Clear();
        LocationCommands.Clear();
        LocationEvents.Clear();
        if (string.IsNullOrWhiteSpace(SelectedLocation))
        {
            OnPropertyChanged(nameof(SelectedLocationRelaySummary));
            RefreshCommandStates();
            return;
        }

        var scopedRelays = Relays
            .Where(r => string.Equals(NormalizeLocation(r.Location), SelectedLocation, StringComparison.Ordinal))
            .OrderBy(r => r.DisplayName)
            .ToList();

        foreach (var relay in scopedRelays)
        {
            LocationRelays.Add(relay);
            var relayPhoneKey = RelayPhoneKey(relay.PhoneNumber);
            var selected = previousSelectionById.TryGetValue(relay.Id, out var wasSelectedById)
                ? wasSelectedById
                : previousSelectionByPhoneKey.TryGetValue(relayPhoneKey, out var wasSelectedByPhone)
                    ? wasSelectedByPhone
                    : true;
            var item = new LocationRelaySelectionItem(relay, selected);
            item.PropertyChanged += OnLocationRelaySelectionChanged;
            LocationRelaySelections.Add(item);
        }

        if (SelectedRelay != null)
        {
            var reboundRelay = scopedRelays.FirstOrDefault(r => r.Id == previousSelectedRelayId);
            if (reboundRelay == null && !string.IsNullOrWhiteSpace(previousSelectedRelayPhoneKey))
            {
                reboundRelay = scopedRelays.FirstOrDefault(r =>
                    string.Equals(RelayPhoneKey(r.PhoneNumber), previousSelectedRelayPhoneKey, StringComparison.Ordinal));
            }
            if (reboundRelay != null && !ReferenceEquals(reboundRelay, SelectedRelay))
            {
                SelectedRelay = reboundRelay;
            }
        }

        var phoneKeys = scopedRelays
            .Select(r => RelayPhoneKey(r.PhoneNumber))
            .Where(k => !string.IsNullOrWhiteSpace(k))
            .ToHashSet(StringComparer.Ordinal);

        foreach (var cmd in Commands
                     .Where(c => phoneKeys.Contains(RelayPhoneKey(c.RelayPhone)))
                     .OrderByDescending(c => c.CreatedAt))
        {
            LocationCommands.Add(cmd);
        }

        foreach (var ev in Events
                     .Where(e => phoneKeys.Contains(RelayPhoneKey(e.RelayPhone)))
                     .OrderByDescending(e => e.Timestamp))
        {
            LocationEvents.Add(ev);
        }
        OnPropertyChanged(nameof(SelectedLocationRelaySummary));
        RefreshCommandStates();
    }

    private void OnLocationRelaySelectionChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName != nameof(LocationRelaySelectionItem.IsSelected)) return;
        OnPropertyChanged(nameof(SelectedLocationRelaySummary));
        RefreshCommandStates();
    }

    private bool IsRelayInSelectedLocation(Relay relay)
    {
        if (string.IsNullOrWhiteSpace(SelectedLocation)) return true;
        return string.Equals(NormalizeLocation(relay.Location), SelectedLocation, StringComparison.Ordinal);
    }

    private static string NormalizeLocation(string? location)
    {
        return string.IsNullOrWhiteSpace(location) ? "Fara locatie" : location.Trim();
    }

    private List<string> BuildSnapshotLocations()
    {
        return Relays
            .Select(r => NormalizeLocation(r.Location))
            .Concat(_manualLocations.Select(NormalizeLocation))
            .Where(location => !string.Equals(location, "Fara locatie", StringComparison.Ordinal))
            .Distinct(StringComparer.Ordinal)
            .OrderBy(location => location)
            .ToList();
    }

    private static string RelayPhoneKey(string? phone)
    {
        if (string.IsNullOrWhiteSpace(phone)) return "";
        var digits = new string(phone.Where(char.IsDigit).ToArray());
        if (digits.Length > 8) digits = digits[^8..];
        return digits;
    }

    private static bool IsSameRelay(string a, string b)
    {
        var aDigits = new string(a.Where(char.IsDigit).ToArray());
        var bDigits = new string(b.Where(char.IsDigit).ToArray());
        if (aDigits.Length > 8) aDigits = aDigits[^8..];
        if (bDigits.Length > 8) bDigits = bDigits[^8..];
        return !string.IsNullOrWhiteSpace(aDigits) && aDigits == bDigits;
    }

    private static string UserPhoneKey(string? phone)
    {
        if (string.IsNullOrWhiteSpace(phone)) return "";
        var digits = new string(phone.Where(char.IsDigit).ToArray());
        if (digits.Length > 8) digits = digits[^8..];
        if (!string.IsNullOrWhiteSpace(digits)) return digits;
        return phone.Trim().ToLowerInvariant();
    }

    private void AddLog(string message)
    {
        if (string.IsNullOrWhiteSpace(message)) return;
        Logs.Insert(0, new LogEntry
        {
            Timestamp = DateTimeOffset.Now.ToUnixTimeMilliseconds(),
            Message = message.Trim()
        });
        while (Logs.Count > 200)
        {
            Logs.RemoveAt(Logs.Count - 1);
        }
    }

    private void UpdateRelayUser(Relay relay, int userId, string phone, string name, string group, bool known)
    {
        var updatedRelay = ApplyRelayUserUpdate(relay, userId, phone, name, group, known);
        ApplyRelayUpdate(updatedRelay);
    }

    private Relay ApplyRelayUserUpdate(Relay relay, int userId, string phone, string name, string group, bool known)
    {
        var updatedUsers = relay.Users
            .Select(u => u.Id == userId
                ? new RelayUser
                {
                    Id = userId,
                    Phone = phone,
                    Name = name,
                    Group = string.IsNullOrWhiteSpace(group) ? "general" : group,
                    AddedDate = known ? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() : null,
                    Known = known
                }
                : u)
            .ToList();
        return new Relay
        {
            Id = relay.Id,
            Name = relay.Name,
            PhoneNumber = relay.PhoneNumber,
            Password = relay.Password,
            Location = relay.Location,
            Users = updatedUsers,
            LastSync = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            CloudBackup = relay.CloudBackup
        };
    }

    private void ApplyRelayUpdate(Relay updatedRelay)
    {
        ReplaceRelayInCollection(updatedRelay);
        RefreshLocations();
        SelectedRelay = updatedRelay;
        RefreshVisibleUsers();
    }

    private void ReplaceRelayInCollection(Relay updatedRelay)
    {
        var index = Relays
            .Select((relay, idx) => new { relay, idx })
            .FirstOrDefault(x => x.relay.Id == updatedRelay.Id)?.idx ?? -1;
        if (index >= 0)
        {
            Relays[index] = updatedRelay;
        }
    }

    public void ResetNewRelayOnboardingOptions()
    {
        OnboardingQueryStart = 1;
        OnboardingQueryEnd = MaxRelayChannels;
        OnboardingForcePasswordReset = true;
        OnboardingSetDateTime = true;
        OnboardingSetMaster = true;
        OnboardingConfirmOn = true;
        OnboardingConfirmOff = true;
        OnboardingQueryUsers = true;
        OnboardingAutoAddAdmins = true;
        OnPropertyChanged(nameof(OnboardingQueryStart));
        OnPropertyChanged(nameof(OnboardingQueryEnd));
        OnPropertyChanged(nameof(OnboardingForcePasswordReset));
        OnPropertyChanged(nameof(OnboardingSetDateTime));
        OnPropertyChanged(nameof(OnboardingSetMaster));
        OnPropertyChanged(nameof(OnboardingConfirmOn));
        OnPropertyChanged(nameof(OnboardingConfirmOff));
        OnPropertyChanged(nameof(OnboardingQueryUsers));
        OnPropertyChanged(nameof(OnboardingAutoAddAdmins));
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnPropertyChanged([CallerMemberName] string? name = null)
    {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}

using System.ComponentModel;
using System.Runtime.CompilerServices;
using HOSC.Localization;

namespace HOSC.Models;

public enum HeartRateConnectionState
{
    None,
    Connecting,
    Connected,
    Error
}

public sealed class HeartRateDevice : INotifyPropertyChanged
{
    private string _name = string.Empty;
    private int? _bpm;
    private int _rssi;
    private bool _isOnline;
    private bool _hasHeartRateService;
    private HeartRateConnectionState _connectionState;
    private DateTime _lastSeenUtc = DateTime.UtcNow;

    public HeartRateDevice(ulong address)
    {
        Address = address;
    }

    public ulong Address { get; }

    public string DisplayName =>
        string.IsNullOrWhiteSpace(_name) ? AppResources.Instance["DeviceNoName"] : _name;

    public string BpmText => _bpm.HasValue ? _bpm.Value.ToString() : "--";

    public string SignalText => $"{_rssi} dBm";

    public string StatusText
    {
        get
        {
            if (!_isOnline)
            {
                return AppResources.Instance["DeviceOffline"];
            }

            return _connectionState switch
            {
                HeartRateConnectionState.Connected => AppResources.Instance["DeviceConnected"],
                HeartRateConnectionState.Connecting => AppResources.Instance["DeviceConnecting"],
                HeartRateConnectionState.Error => AppResources.Instance["DeviceConnectError"],
                _ => AppResources.Instance["DeviceBroadcasting"]
            };
        }
    }

    public string LastSeenText => _lastSeenUtc.ToLocalTime().ToString("HH:mm:ss");

    public string AddressText => FormatAddress(Address);

    public bool IsOnline => _isOnline;

    public bool HasHeartRateService => _hasHeartRateService;

    public HeartRateConnectionState ConnectionState => _connectionState;

    public int? Bpm => _bpm;

    public DateTime LastSeenUtc => _lastSeenUtc;

    public event PropertyChangedEventHandler? PropertyChanged;

    public void MarkSeen(string? name, int? advertisedBpm, int rssi, DateTimeOffset timestamp)
    {
        if (!string.IsNullOrWhiteSpace(name) && !string.Equals(_name, name, StringComparison.Ordinal))
        {
            _name = name!;
            OnPropertyChanged(nameof(DisplayName));
        }

        if (advertisedBpm.HasValue && _bpm != advertisedBpm.Value)
        {
            _bpm = advertisedBpm.Value;
            OnPropertyChanged(nameof(Bpm));
            OnPropertyChanged(nameof(BpmText));
        }

        if (_rssi != rssi)
        {
            _rssi = rssi;
            OnPropertyChanged(nameof(SignalText));
        }

        _lastSeenUtc = timestamp.UtcDateTime;
        if (!_isOnline)
        {
            _isOnline = true;
            OnPropertyChanged(nameof(IsOnline));
            OnPropertyChanged(nameof(StatusText));
        }

        OnPropertyChanged(nameof(LastSeenText));
    }

    public void SetHasHeartRateService()
    {
        if (_hasHeartRateService)
        {
            return;
        }

        _hasHeartRateService = true;
        OnPropertyChanged(nameof(HasHeartRateService));
    }

    public void SetConnectedBpm(int bpm)
    {
        if (_bpm != bpm)
        {
            _bpm = bpm;
            OnPropertyChanged(nameof(Bpm));
            OnPropertyChanged(nameof(BpmText));
        }

        _lastSeenUtc = DateTime.UtcNow;
        if (!_isOnline)
        {
            _isOnline = true;
            OnPropertyChanged(nameof(IsOnline));
        }

        OnPropertyChanged(nameof(LastSeenText));
    }

    public void SetConnectionState(HeartRateConnectionState state)
    {
        if (_connectionState == state)
        {
            return;
        }

        _connectionState = state;
        OnPropertyChanged(nameof(ConnectionState));
        OnPropertyChanged(nameof(StatusText));
    }

    public void MarkOffline()
    {
        if (!_isOnline)
        {
            return;
        }

        _isOnline = false;
        OnPropertyChanged(nameof(IsOnline));
        OnPropertyChanged(nameof(StatusText));
    }

    public void RefreshLocalizedTexts()
    {
        OnPropertyChanged(nameof(DisplayName));
        OnPropertyChanged(nameof(StatusText));
    }

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null)
    {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }

    private static string FormatAddress(ulong address)
    {
        var bytes = new byte[6];
        for (var i = 0; i < 6; i++)
        {
            bytes[5 - i] = (byte)(address >> (8 * i));
        }

        return string.Join(":", bytes.Select(b => b.ToString("X2")));
    }
}

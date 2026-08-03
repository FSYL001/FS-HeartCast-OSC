using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using HOSC.Localization;
using HOSC.Models;
using HOSC.Services;
using Windows.Devices.Bluetooth;

namespace HOSC;

public partial class MainWindow : Window
{
    private readonly ObservableCollection<HeartRateDevice> _devices = new();
    private readonly BluetoothHeartRateScanner _scanner = new();
    private readonly GattHeartRateService _gatt = new();
    private readonly OscService _osc = new();
    private readonly DispatcherTimer _maintenanceTimer;
    private HeartRateDevice? _activeDevice;
    private HeartRateDevice? _connectedDevice;
    private bool _isScanning;
    private bool _scanFailed;
    private bool _initializingUi = true;

    public MainWindow()
    {
        InitializeComponent();
        Icon = (ImageSource)FindResource("AppLogo");

        DevicesGrid.ItemsSource = _devices;
        _scanner.AdvertisementReceived += OnAdvertisementReceived;
        _scanner.Stopped += OnScannerStopped;
        _gatt.HeartRateChanged += OnGattHeartRate;
        _gatt.Disconnected += OnGattDisconnected;

        Topmost = SettingsService.Current.AlwaysOnTop;
        LoadSettingsIntoUi();
        _initializingUi = false;

        _maintenanceTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };
        _maintenanceTimer.Tick += MaintenanceTick;
        _maintenanceTimer.Start();

        UpdateNoDevices();
        RefreshUiTexts();
        if (SettingsService.Current.AutoStartScan)
        {
            StartScanning();
        }
        else
        {
            _scanFailed = false;
            _isScanning = false;
            RefreshUiTexts();
        }
    }

    private string HrAddress =>
        string.IsNullOrWhiteSpace(OscHrAddressTextBox.Text)
            ? "/avatar/parameters/HR"
            : OscHrAddressTextBox.Text.Trim();

    private string BeatAddress =>
        string.IsNullOrWhiteSpace(OscBeatAddressTextBox.Text)
            ? "/avatar/parameters/isHRBeat"
            : OscBeatAddressTextBox.Text.Trim();

    private static string Loc(string key) => AppResources.Instance[key];

    private void LoadSettingsIntoUi()
    {
        var settings = SettingsService.Current;
        OscEnabledCheckBox.IsChecked = settings.OscEnabled;
        OscAddressTextBox.Text = settings.OscAddress;
        OscPortTextBox.Text = settings.OscPort.ToString();
        OscHrAddressTextBox.Text = settings.OscHrAddress;
        OscBeatAddressTextBox.Text = settings.OscBeatAddress;
    }

    private void RefreshUiTexts()
    {
        ScanToggleButton.Content = Loc(_isScanning ? "ScanStop" : "ScanStart");
        ScanStatusText.Text = _scanFailed
            ? Loc("BluetoothOff")
            : Loc(_isScanning ? "Scanning" : "ScanStopped");
        ScanStatusText.Foreground = _scanFailed
            ? (Brush)FindResource("DangerBrush")
            : _isScanning
                ? (Brush)FindResource("AccentBrush")
                : (Brush)FindResource("TextSecondaryBrush");

        foreach (var device in _devices)
        {
            device.RefreshLocalizedTexts();
        }

        UpdateActiveDeviceText();
        UpdateConnectButton();
    }

    private void StartScanning()
    {
        _scanFailed = false;
        _scanner.Start();
        _isScanning = true;
        RefreshUiTexts();
    }

    private void StopScanning()
    {
        _scanner.Stop();
        _isScanning = false;
        RefreshUiTexts();
    }

    private void OnAdvertisementReceived(BluetoothAdvertisementResult result)
    {
        if (Dispatcher.HasShutdownStarted)
        {
            return;
        }

        Dispatcher.Invoke(() =>
        {
            var device = _devices.FirstOrDefault(d => d.Address == result.Address);
            if (device == null)
            {
                device = new HeartRateDevice(result.Address);
                _devices.Add(device);
            }

            device.MarkSeen(result.LocalName, result.AdvertisedBpm, result.Rssi, result.Timestamp);
            if (result.HasHeartRateService)
            {
                device.SetHasHeartRateService();
            }

            UpdateNoDevices();

            if (_activeDevice == null || !_activeDevice.IsOnline)
            {
                var candidate = _devices.FirstOrDefault(d => d.IsOnline);
                if (candidate != null)
                {
                    DevicesGrid.SelectedItem = candidate;
                }
            }

            UpdateActiveDeviceText();
            UpdateConnectButton();

            if (ReferenceEquals(_activeDevice, device)
                && !ReferenceEquals(_connectedDevice, device))
            {
                _ = EnsureConnectedAsync(device);
            }

            if (ReferenceEquals(_activeDevice, device)
                && !ReferenceEquals(_connectedDevice, device)
                && OscEnabledCheckBox.IsChecked == true
                && device.Bpm.HasValue
                && device.ConnectionState != HeartRateConnectionState.Connecting)
            {
                SendHeartRateToOsc(device.Bpm.Value);
            }
        });
    }

    private void OnScannerStopped(BluetoothError error)
    {
        if (Dispatcher.HasShutdownStarted)
        {
            return;
        }

        Dispatcher.Invoke(() =>
        {
            _isScanning = false;
            _scanFailed = error != BluetoothError.Success;
            RefreshUiTexts();
        });
    }

    private async Task EnsureConnectedAsync(HeartRateDevice device, bool force = false)
    {
        if (device == null || !device.IsOnline)
        {
            return;
        }

        if (ReferenceEquals(_connectedDevice, device) && _gatt.IsConnected)
        {
            return;
        }

        if (device.ConnectionState == HeartRateConnectionState.Connecting)
        {
            return;
        }

        if (!force && device.ConnectionState == HeartRateConnectionState.Error)
        {
            return;
        }

        _connectedDevice = device;
        device.SetConnectionState(HeartRateConnectionState.Connecting);
        UpdateConnectButton();

        var connected = await _gatt.ConnectAsync(device.Address);
        if (!ReferenceEquals(_connectedDevice, device))
        {
            return;
        }

        if (connected)
        {
            device.SetConnectionState(HeartRateConnectionState.Connected);
            SendConnectedStateToOsc(true);
        }
        else
        {
            device.SetConnectionState(HeartRateConnectionState.Error);
            _connectedDevice = null;
        }

        UpdateConnectButton();
    }

    private async Task DisconnectActiveAsync()
    {
        var device = _connectedDevice;
        if (device == null)
        {
            return;
        }

        _connectedDevice = null;
        device.SetConnectionState(HeartRateConnectionState.None);
        SendConnectedStateToOsc(false);
        await _gatt.DisconnectAsync();
        UpdateConnectButton();
    }

    private void OnGattHeartRate(int bpm)
    {
        if (Dispatcher.HasShutdownStarted)
        {
            return;
        }

        Dispatcher.Invoke(() =>
        {
            var device = _connectedDevice;
            if (device == null)
            {
                return;
            }

            device.SetConnectedBpm(bpm);
            UpdateActiveDeviceText();

            if (OscEnabledCheckBox.IsChecked == true && ReferenceEquals(_activeDevice, device))
            {
                SendHeartRateToOsc(bpm);
            }
        });
    }

    private void OnGattDisconnected()
    {
        if (Dispatcher.HasShutdownStarted)
        {
            return;
        }

        Dispatcher.Invoke(() =>
        {
            var device = _connectedDevice;
            _connectedDevice = null;
            if (device != null)
            {
                device.SetConnectionState(HeartRateConnectionState.None);
            }

            SendConnectedStateToOsc(false);
            UpdateConnectButton();
            UpdateActiveDeviceText();
        });
    }

    private void MaintenanceTick(object? sender, EventArgs e)
    {
        var now = DateTime.UtcNow;
        foreach (var device in _devices.ToList())
        {
            var isConnected = ReferenceEquals(_connectedDevice, device) && _gatt.IsConnected;
            if (device.IsOnline
                && !isConnected
                && now - device.LastSeenUtc > TimeSpan.FromSeconds(12))
            {
                if (ReferenceEquals(_activeDevice, device)
                    && OscEnabledCheckBox.IsChecked == true)
                {
                    var endpoint = GetOscEndpoint();
                    if (endpoint.Ok)
                    {
                        _osc.Send(endpoint.Host, endpoint.Port, BeatAddress, 0);
                    }
                }

                device.MarkOffline();
            }

            if (now - device.LastSeenUtc > TimeSpan.FromSeconds(90))
            {
                if (ReferenceEquals(_connectedDevice, device))
                {
                    _connectedDevice = null;
                    device.SetConnectionState(HeartRateConnectionState.None);
                    SendConnectedStateToOsc(false);
                    _ = _gatt.DisconnectAsync();
                }

                _devices.Remove(device);
            }
        }

        if (_activeDevice != null && !_devices.Contains(_activeDevice))
        {
            _activeDevice = null;
            DevicesGrid.SelectedItem = null;
        }

        UpdateNoDevices();
        UpdateActiveDeviceText();
        UpdateConnectButton();
    }

    private void UpdateNoDevices()
    {
        NoDevicesText.Visibility = _devices.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
    }

    private void UpdateActiveDeviceText()
    {
        ActiveDeviceText.Text = _activeDevice is { IsOnline: true }
            ? _activeDevice.DisplayName
            : Loc("ActiveDeviceNone");
        UpdateLiveBpm();
    }

    private void UpdateLiveBpm()
    {
        var bpm = _activeDevice?.Bpm;
        LiveBpmText.Text = bpm.HasValue ? bpm.Value.ToString() : "--";

        if (bpm.HasValue && _activeDevice is { IsOnline: true })
        {
            LiveBpmText.Foreground = (Brush)FindResource("HeartRateBrush");
        }
        else
        {
            LiveBpmText.Foreground = (Brush)FindResource("TextMutedBrush");
        }
    }

    private void UpdateConnectButton()
    {
        var isConnected = _activeDevice != null
            && ReferenceEquals(_connectedDevice, _activeDevice)
            && _gatt.IsConnected;
        ConnectButton.Content = Loc(isConnected ? "Disconnect" : "Connect");
        ConnectButton.IsEnabled = _activeDevice != null;
    }

    private void SendHeartRateToOsc(int bpm)
    {
        var endpoint = GetOscEndpoint();
        if (!endpoint.Ok)
        {
            ShowOscStatus(false);
            return;
        }

        var sent = _osc.SendHeartRate(
            endpoint.Host,
            endpoint.Port,
            HrAddress,
            BeatAddress,
            bpm);
        if (!sent)
        {
            ShowOscStatus(false);
        }
    }

    private void SendConnectedStateToOsc(bool connected)
    {
        if (OscEnabledCheckBox.IsChecked != true)
        {
            return;
        }

        var endpoint = GetOscEndpoint();
        if (endpoint.Ok)
        {
            _osc.SendConnectedState(endpoint.Host, endpoint.Port, connected);
        }
    }

    private (string Host, int Port, bool Ok) GetOscEndpoint()
    {
        var host = string.IsNullOrWhiteSpace(OscAddressTextBox.Text)
            ? "127.0.0.1"
            : OscAddressTextBox.Text.Trim();

        if (!int.TryParse(OscPortTextBox.Text.Trim(), out var port) || port is < 1 or > 65535)
        {
            return (host, 0, false);
        }

        return (host, port, true);
    }

    private void ShowOscStatus(bool success)
    {
        OscStatusText.Text = Loc(success ? "OscTestSent" : "OscTestFailed");
        OscStatusText.Foreground = (Brush)FindResource(success ? "SuccessBrush" : "DangerBrush");
    }

    private void SaveSettingsFromUi()
    {
        var settings = SettingsService.Current;
        settings.OscEnabled = OscEnabledCheckBox.IsChecked == true;
        settings.OscAddress = string.IsNullOrWhiteSpace(OscAddressTextBox.Text)
            ? "127.0.0.1"
            : OscAddressTextBox.Text.Trim();
        settings.OscPort = int.TryParse(OscPortTextBox.Text.Trim(), out var port) && port is >= 1 and <= 65535
            ? port
            : 9000;
        settings.OscHrAddress = HrAddress;
        settings.OscBeatAddress = BeatAddress;
        SettingsService.Save();
    }

    private void SettingsButton_Click(object sender, RoutedEventArgs e)
    {
        var settingsWindow = new SettingsWindow { Owner = this };
        settingsWindow.ShowDialog();

        Topmost = SettingsService.Current.AlwaysOnTop;
        RefreshUiTexts();
    }

    private void ScanToggle_Click(object sender, RoutedEventArgs e)
    {
        if (_isScanning)
        {
            StopScanning();
        }
        else
        {
            StartScanning();
        }
    }

    private void ClearList_Click(object sender, RoutedEventArgs e)
    {
        if (_connectedDevice != null)
        {
            _connectedDevice.SetConnectionState(HeartRateConnectionState.None);
            _connectedDevice = null;
            SendConnectedStateToOsc(false);
            _ = _gatt.DisconnectAsync();
        }

        _devices.Clear();
        _activeDevice = null;
        DevicesGrid.SelectedItem = null;
        UpdateNoDevices();
        UpdateActiveDeviceText();
        UpdateConnectButton();
    }

    private void DevicesGrid_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        var newActive = DevicesGrid.SelectedItem as HeartRateDevice;
        if (ReferenceEquals(newActive, _activeDevice))
        {
            return;
        }

        var previous = _activeDevice;
        _activeDevice = newActive;

        if (previous != null && !ReferenceEquals(previous, newActive))
        {
            if (ReferenceEquals(_connectedDevice, previous))
            {
                _connectedDevice = null;
                previous.SetConnectionState(HeartRateConnectionState.None);
                _ = _gatt.DisconnectAsync();
            }
        }

        UpdateActiveDeviceText();
        UpdateConnectButton();

        if (_activeDevice != null)
        {
            _ = EnsureConnectedAsync(_activeDevice);
        }
    }

    private void Connect_Click(object sender, RoutedEventArgs e)
    {
        if (_activeDevice == null)
        {
            return;
        }

        if (ReferenceEquals(_connectedDevice, _activeDevice) && _gatt.IsConnected)
        {
            _ = DisconnectActiveAsync();
        }
        else
        {
            _ = EnsureConnectedAsync(_activeDevice, force: true);
        }
    }

    private void OscTest_Click(object sender, RoutedEventArgs e)
    {
        var endpoint = GetOscEndpoint();
        if (!endpoint.Ok)
        {
            ShowOscStatus(false);
            return;
        }

        var sent = _osc.SendTest(endpoint.Host, endpoint.Port, HrAddress, BeatAddress);
        ShowOscStatus(sent);
    }

    private void OscEnabledChanged(object sender, RoutedEventArgs e)
    {
        if (!_initializingUi)
        {
            SaveSettingsFromUi();
        }
    }

    private void OscSettingChanged(object sender, TextChangedEventArgs e)
    {
        if (!_initializingUi)
        {
            SaveSettingsFromUi();
        }
    }

    private void Window_Closing(object? sender, CancelEventArgs e)
    {
        _maintenanceTimer.Stop();
        _scanner.Stop();
        _ = _gatt.DisconnectAsync();
        SendConnectedStateToOsc(false);
        SaveSettingsFromUi();
        _osc.Dispose();
    }
}

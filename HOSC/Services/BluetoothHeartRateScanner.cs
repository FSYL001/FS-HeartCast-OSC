using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Enumeration;

namespace HOSC.Services;

public sealed class BluetoothHeartRateScanner : IDisposable
{
    private BluetoothLEAdvertisementWatcher? _watcher;

    public event Action<BluetoothAdvertisementResult>? AdvertisementReceived;

    public event Action<IReadOnlyList<BluetoothAdvertisementResult>>? PairedDevicesRefreshed;

    public event Action<BluetoothError>? Stopped;

    public bool IsScanning { get; private set; }

    private bool _pairedRefreshRunning;

    public void Start()
    {
        if (_watcher != null)
        {
            return;
        }

        _watcher = new BluetoothLEAdvertisementWatcher
        {
            ScanningMode = BluetoothLEScanningMode.Active
        };
        _watcher.Received += OnReceived;
        _watcher.Stopped += OnStopped;
        _watcher.Start();
        IsScanning = true;
    }

    public void Stop()
    {
        var watcher = _watcher;
        _watcher = null;
        IsScanning = false;

        if (watcher == null)
        {
            return;
        }

        watcher.Received -= OnReceived;
        watcher.Stopped -= OnStopped;
        watcher.Stop();
    }

    public void Dispose()
    {
        Stop();
    }

    public async Task RefreshPairedDevicesAsync()
    {
        if (_pairedRefreshRunning)
        {
            return;
        }

        _pairedRefreshRunning = true;
        try
        {
            var selector = BluetoothDevice.GetDeviceSelectorFromPairingState(true);
            var infos = await DeviceInformation.FindAllAsync(selector);
            var results = new List<BluetoothAdvertisementResult>();

            foreach (var info in infos)
            {
                BluetoothDevice? device = null;
                try
                {
                    device = await BluetoothDevice.FromIdAsync(info.Id);
                    if (device == null)
                    {
                        continue;
                    }

                    var name = string.IsNullOrWhiteSpace(device.Name)
                        ? info.Name ?? string.Empty
                        : device.Name;
                    if (!LooksLikeHeartRateDevice(name) || IsAudioDevice(name))
                    {
                        continue;
                    }

                    results.Add(new BluetoothAdvertisementResult(
                        device.BluetoothAddress,
                        0,
                        name,
                        DateTimeOffset.UtcNow,
                        null,
                        false));
                }
                catch
                {
                    // A single paired device must never break the whole refresh.
                }
                finally
                {
                    device?.Dispose();
                }
            }

            if (results.Count > 0)
            {
                PairedDevicesRefreshed?.Invoke(results);
            }
        }
        catch
        {
            // Bluetooth enumeration can fail when the radio is disabled.
        }
        finally
        {
            _pairedRefreshRunning = false;
        }
    }

    private void OnReceived(
        BluetoothLEAdvertisementWatcher sender,
        BluetoothLEAdvertisementReceivedEventArgs args)
    {
        var name = args.Advertisement.LocalName?.Trim() ?? string.Empty;
        var advertisedReading = HeartRateParser.TryParse(args.Advertisement);
        var hasHeartRateService = args.Advertisement.ServiceUuids.Contains(HeartRateParser.HeartRateServiceUuid);

        if (string.IsNullOrWhiteSpace(name) && !hasHeartRateService && advertisedReading == null)
        {
            return;
        }

        if (!hasHeartRateService && advertisedReading == null && !LooksLikeHeartRateDevice(name))
        {
            return;
        }

        AdvertisementReceived?.Invoke(new BluetoothAdvertisementResult(
            args.BluetoothAddress,
            args.RawSignalStrengthInDBm,
            name,
            args.Timestamp,
            advertisedReading?.Bpm,
            hasHeartRateService));
    }

    private void OnStopped(
        BluetoothLEAdvertisementWatcher sender,
        BluetoothLEAdvertisementWatcherStoppedEventArgs args)
    {
        IsScanning = false;
        _watcher = null;
        Stopped?.Invoke(args.Error);
    }

    private static bool LooksLikeHeartRateDevice(string name)
    {
        var upper = name.ToUpperInvariant();
        return upper.Contains("HUAWEI")
            || upper.Contains("HONOR")
            || upper.Contains("WATCH")
            || upper.Contains("BAND")
            || upper.Contains("FIT")
            || upper.Contains("HEART")
            || upper.Contains("XIAOMI")
            || upper.Contains("MI BAND")
            || upper.Contains("MIBAND")
            || upper.Contains("AMAZFIT")
            || upper.Contains("GT");
    }

    private static bool IsAudioDevice(string name)
    {
        var upper = name.ToUpperInvariant();
        return upper.Contains("FREEBUDS")
            || upper.Contains("HEADSET")
            || upper.Contains("HEADPHONE")
            || upper.Contains("SPEAKER")
            || upper.Contains("AUDIO");
    }
}

public sealed record BluetoothAdvertisementResult(
    ulong Address,
    short Rssi,
    string LocalName,
    DateTimeOffset Timestamp,
    int? AdvertisedBpm,
    bool HasHeartRateService);

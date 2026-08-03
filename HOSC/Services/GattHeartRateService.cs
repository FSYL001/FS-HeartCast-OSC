using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

namespace HOSC.Services;

public sealed class GattHeartRateService : IAsyncDisposable
{
    private BluetoothLEDevice? _device;
    private GattDeviceService? _service;
    private GattCharacteristic? _characteristic;

    public event Action<int>? HeartRateChanged;

    public event Action? Disconnected;

    public bool IsConnected => _device != null && _characteristic != null;

    public async Task<bool> ConnectAsync(ulong address)
    {
        await DisconnectAsync();

        try
        {
            var device = await BluetoothLEDevice.FromBluetoothAddressAsync(address);
            if (device == null)
            {
                return false;
            }

            _device = device;
            _device.ConnectionStatusChanged += OnConnectionStatusChanged;

            var servicesResult = await _device.GetGattServicesForUuidAsync(
                HeartRateParser.HeartRateServiceUuid,
                BluetoothCacheMode.Uncached);
            if (servicesResult.Status != GattCommunicationStatus.Success
                || servicesResult.Services.Count == 0)
            {
                await DisconnectAsync();
                return false;
            }

            _service = servicesResult.Services[0];
            var characteristicsResult = await _service.GetCharacteristicsForUuidAsync(
                HeartRateParser.HeartRateMeasurementUuid,
                BluetoothCacheMode.Uncached);
            if (characteristicsResult.Status != GattCommunicationStatus.Success
                || characteristicsResult.Characteristics.Count == 0)
            {
                await DisconnectAsync();
                return false;
            }

            _characteristic = characteristicsResult.Characteristics[0];
            var notificationResult = await _characteristic
                .WriteClientCharacteristicConfigurationDescriptorAsync(
                    GattClientCharacteristicConfigurationDescriptorValue.Notify);
            if (notificationResult != GattCommunicationStatus.Success)
            {
                await DisconnectAsync();
                return false;
            }

            _characteristic.ValueChanged += OnHeartRateValueChanged;
            return true;
        }
        catch
        {
            await DisconnectAsync();
            return false;
        }
    }

    public async Task DisconnectAsync()
    {
        if (_characteristic != null)
        {
            _characteristic.ValueChanged -= OnHeartRateValueChanged;
            try
            {
                await _characteristic.WriteClientCharacteristicConfigurationDescriptorAsync(
                    GattClientCharacteristicConfigurationDescriptorValue.None);
            }
            catch
            {
                // Best-effort cleanup.
            }

            _characteristic = null;
        }

        if (_service != null)
        {
            _service.Dispose();
            _service = null;
        }

        if (_device != null)
        {
            _device.ConnectionStatusChanged -= OnConnectionStatusChanged;
            _device.Dispose();
            _device = null;
        }
    }

    public ValueTask DisposeAsync()
    {
        return new ValueTask(DisconnectAsync());
    }

    private void OnHeartRateValueChanged(
        GattCharacteristic sender,
        GattValueChangedEventArgs args)
    {
        if (args.CharacteristicValue.Length < 2)
        {
            return;
        }

        var bytes = new byte[(int)args.CharacteristicValue.Length];
        DataReader.FromBuffer(args.CharacteristicValue).ReadBytes(bytes);

        var reading = HeartRateParser.ParseHeartRatePayload(bytes);
        if (reading != null)
        {
            HeartRateChanged?.Invoke(reading.Bpm);
        }
    }

    private void OnConnectionStatusChanged(BluetoothLEDevice sender, object args)
    {
        if (sender.ConnectionStatus == BluetoothConnectionStatus.Disconnected)
        {
            Disconnected?.Invoke();
        }
    }
}

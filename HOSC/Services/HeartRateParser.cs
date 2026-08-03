using Windows.Devices.Bluetooth.Advertisement;
using Windows.Storage.Streams;

namespace HOSC.Services;

public static class HeartRateParser
{
    public const ushort HeartRateServiceUuid16 = 0x180D;

    public static readonly Guid HeartRateServiceUuid =
        new("0000180d-0000-1000-8000-00805f9b34fb");

    public static readonly Guid HeartRateMeasurementUuid =
        new("00002a37-0000-1000-8000-00805f9b34fb");

    public static HeartRateReading? TryParse(BluetoothLEAdvertisement advertisement)
    {
        if (advertisement == null)
        {
            return null;
        }

        foreach (var section in advertisement.DataSections)
        {
            if (section.DataType != 0x16)
            {
                continue;
            }

            var bytes = ReadSectionBytes(section.Data);
            if (bytes.Length < 3)
            {
                continue;
            }

            var serviceUuid = (ushort)(bytes[0] | (bytes[1] << 8));
            var hasHeartRateService = serviceUuid == HeartRateServiceUuid16
                || (bytes[0] == 0x18 && bytes[1] == 0x0D);
            if (!hasHeartRateService)
            {
                continue;
            }

            var reading = ParseHeartRatePayload(bytes[2..]);
            if (reading != null)
            {
                return reading;
            }
        }

        return null;
    }

    public static HeartRateReading? ParseHeartRatePayload(byte[] payload)
    {
        if (payload == null || payload.Length == 0)
        {
            return null;
        }

        // Some broadcasters send a single BPM byte without the flags byte.
        if (payload.Length == 1)
        {
            return payload[0] is > 0 and <= 255 ? new HeartRateReading(payload[0]) : null;
        }

        if (payload.Length < 2)
        {
            return null;
        }

        var flags = payload[0];
        int bpm;
        if ((flags & 0x01) != 0)
        {
            if (payload.Length < 3)
            {
                return null;
            }

            bpm = payload[1] | (payload[2] << 8);
        }
        else
        {
            bpm = payload[1];
        }

        if (bpm is <= 0 or > 255)
        {
            return null;
        }

        return new HeartRateReading(
            bpm,
            (flags & 0x02) != 0,
            (flags & 0x04) != 0,
            (flags & 0x08) != 0);
    }

    private static byte[] ReadSectionBytes(IBuffer buffer)
    {
        var data = new byte[(int)buffer.Length];
        DataReader.FromBuffer(buffer).ReadBytes(data);
        return data;
    }
}

public sealed record HeartRateReading(
    int Bpm,
    bool HasContactSupport = false,
    bool ContactDetected = false,
    bool HasRrInterval = false);

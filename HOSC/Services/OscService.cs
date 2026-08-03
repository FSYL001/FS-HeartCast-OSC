using System.IO;
using System.Net.Sockets;
using System.Text;

namespace HOSC.Services;

public sealed class OscService : IDisposable
{
    private readonly UdpClient _client = new();
    private readonly object _sendGate = new();
    private CancellationTokenSource? _beatCts;
    private CancellationTokenSource? _chatCts;
    private long _lastBeatTimestamp;
    private bool _heartBeatToggle;
    private bool _disposed;

    public bool SendHeartRate(
        string host,
        int port,
        string hrAddress,
        string beatAddress,
        int bpm)
    {
        if (_disposed || bpm <= 0)
        {
            return false;
        }

        var now = Environment.TickCount64;
        if (now - Interlocked.Read(ref _lastBeatTimestamp) < 400)
        {
            return true;
        }

        Interlocked.Exchange(ref _lastBeatTimestamp, now);
        _heartBeatToggle = !_heartBeatToggle;

        var sent = Send(host, port, hrAddress, bpm);
        sent &= Send(host, port, beatAddress, 1);
        sent &= Send(host, port, "/avatar/parameters/HeartBeatToggle", _heartBeatToggle);

        // VRCOSC-compatible parameter names used by its heart rate prefabs.
        sent &= Send(host, port, "/avatar/parameters/VRCOSC/Heartrate/Value", bpm);
        sent &= Send(host, port, "/avatar/parameters/VRCOSC/Heartrate/Beat", _heartBeatToggle);
        sent &= Send(host, port, "/avatar/parameters/VRCOSC/Heartrate/Normalised", Math.Clamp(bpm / 240f, 0f, 1f));
        sent &= Send(host, port, "/avatar/parameters/VRCOSC/Heartrate/Average", bpm);

        // Common aliases used by HRtoVRChat, HeartOSC and Mi Band style prefabs.
        sent &= Send(host, port, "/avatar/parameters/HeartRateInt", bpm);
        sent &= Send(host, port, "/avatar/parameters/HeartRate3", bpm);
        sent &= Send(host, port, "/avatar/parameters/Heartrate3", bpm);
        sent &= Send(host, port, "/avatar/parameters/HeartRateFloat01", bpm / 240f);
        sent &= Send(host, port, "/avatar/parameters/HRPercent", bpm / 255f);
        sent &= Send(host, port, "/avatar/parameters/HeartRate", bpm / 127.5f - 1f);

        ScheduleBeatReset(host, port, beatAddress);
        return sent;
    }

    public bool SendTest(
        string host,
        int port,
        string hrAddress,
        string beatAddress)
    {
        if (_disposed)
        {
            return false;
        }

        var sent = SendHeartRate(host, port, hrAddress, beatAddress, 72);
        sent &= SendChatboxTest(host, port);
        return sent;
    }

    public bool SendConnectedState(string host, int port, bool connected)
    {
        if (_disposed)
        {
            return false;
        }

        var sent = Send(host, port, "/avatar/parameters/VRCOSC/Heartrate/Connected", connected);
        sent &= Send(host, port, "/avatar/parameters/VRCOSC/Heartrate/Enabled", connected);
        sent &= Send(host, port, "/avatar/parameters/isHRConnected", connected);
        sent &= Send(host, port, "/avatar/parameters/isHRActive", connected);
        return sent;
    }

    public bool SendChatboxTest(string host, int port)
    {
        if (_disposed)
        {
            return false;
        }

        var sent = Send(host, port, "/chatbox/input", "FS-HeartCast-OSC OSC Test 72 BPM", true);
        _chatCts?.Cancel();
        _chatCts?.Dispose();
        _chatCts = new CancellationTokenSource();
        var token = _chatCts.Token;

        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(2500, token).ConfigureAwait(false);
                Send(host, port, "/chatbox/input", string.Empty, false);
            }
            catch (OperationCanceledException)
            {
            }
            catch (ObjectDisposedException)
            {
            }
        });

        return sent;
    }

    public bool Send(string host, int port, string oscAddress, params object[] values)
    {
        try
        {
            var packet = BuildMessage(oscAddress, values);
            lock (_sendGate)
            {
                _client.Send(packet, packet.Length, host, port);
            }

            return true;
        }
        catch
        {
            return false;
        }
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }

        _disposed = true;
        _beatCts?.Cancel();
        _chatCts?.Cancel();
        _client.Dispose();
    }

    private void ScheduleBeatReset(string host, int port, string beatAddress)
    {
        _beatCts?.Cancel();
        _beatCts?.Dispose();
        _beatCts = new CancellationTokenSource();
        var token = _beatCts.Token;

        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(120, token).ConfigureAwait(false);
                Send(host, port, beatAddress, 0);
            }
            catch (OperationCanceledException)
            {
            }
            catch (ObjectDisposedException)
            {
            }
        });
    }

    private static byte[] BuildMessage(string oscAddress, object[] values)
    {
        using var stream = new MemoryStream();

        void WriteString(string value)
        {
            var bytes = Encoding.UTF8.GetBytes(value);
            stream.Write(bytes, 0, bytes.Length);
            stream.WriteByte(0);
            while (stream.Length % 4 != 0)
            {
                stream.WriteByte(0);
            }
        }

        void WriteInt(int value)
        {
            stream.WriteByte((byte)(value >> 24));
            stream.WriteByte((byte)(value >> 16));
            stream.WriteByte((byte)(value >> 8));
            stream.WriteByte((byte)value);
        }

        void WriteFloat(float value)
        {
            var bytes = BitConverter.GetBytes(value);
            Array.Reverse(bytes);
            stream.Write(bytes, 0, bytes.Length);
        }

        WriteString(oscAddress);

        var tags = new StringBuilder(",");
        foreach (var value in values)
        {
            tags.Append(value switch
            {
                int => 'i',
                float or double => 'f',
                bool flag => flag ? 'T' : 'F',
                string => 's',
                _ => 'i'
            });
        }

        WriteString(tags.ToString());

        foreach (var value in values)
        {
            switch (value)
            {
                case int intValue:
                    WriteInt(intValue);
                    break;
                case float floatValue:
                    WriteFloat(floatValue);
                    break;
                case double doubleValue:
                    WriteFloat((float)doubleValue);
                    break;
                case string stringValue:
                    WriteString(stringValue);
                    break;
                case bool:
                    break;
                default:
                    WriteInt(Convert.ToInt32(value));
                    break;
            }
        }

        return stream.ToArray();
    }
}

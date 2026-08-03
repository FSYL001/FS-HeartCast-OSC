using System.Globalization;
using System.IO;
using System.Text.Json;

namespace HOSC.Services;

public sealed class AppSettings
{
    public AppSettings()
    {
        var culture = CultureInfo.CurrentUICulture;
        if (culture.Name.StartsWith("zh-Hant", StringComparison.OrdinalIgnoreCase)
            || culture.Name.StartsWith("zh-TW", StringComparison.OrdinalIgnoreCase)
            || culture.Name.StartsWith("zh-HK", StringComparison.OrdinalIgnoreCase))
        {
            Language = "zh-Hant";
        }
        else if (culture.Name.StartsWith("zh", StringComparison.OrdinalIgnoreCase))
        {
            Language = "zh-Hans";
        }
        else if (culture.Name.StartsWith("ja", StringComparison.OrdinalIgnoreCase))
        {
            Language = "ja";
        }
        else
        {
            Language = "en";
        }
    }

    public string Language { get; set; } = "en";
    public string Theme { get; set; } = "System";
    public string AccentColor { get; set; } = "Teal";
    public bool AutoStartScan { get; set; } = true;
    public bool AlwaysOnTop { get; set; }
    public bool OscEnabled { get; set; }
    public string OscAddress { get; set; } = "127.0.0.1";
    public int OscPort { get; set; } = 9000;
    public string OscHrAddress { get; set; } = "/avatar/parameters/HR";
    public string OscBeatAddress { get; set; } = "/avatar/parameters/isHRBeat";
}

public static class SettingsService
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    public static AppSettings Current { get; private set; } = new();

    private static string SettingsPath =>
        Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "HOSC",
            "settings.json");

    public static void Load()
    {
        try
        {
            var path = SettingsPath;
            if (File.Exists(path))
            {
                Current = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(path), JsonOptions) ?? new AppSettings();
            }
        }
        catch
        {
            Current = new AppSettings();
        }

        Sanitize();
    }

    public static void Save()
    {
        try
        {
            var path = SettingsPath;
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllText(path, JsonSerializer.Serialize(Current, JsonOptions));
        }
        catch
        {
            // Settings persistence must never crash the UI.
        }
    }

    private static void Sanitize()
    {
        var settings = Current;
        if (settings.OscPort is < 1 or > 65535)
        {
            settings.OscPort = 9000;
        }

        if (string.IsNullOrWhiteSpace(settings.OscAddress))
        {
            settings.OscAddress = "127.0.0.1";
        }

        var languages = new[] { "en", "zh-Hans", "zh-Hant", "ja" };
        if (!languages.Contains(settings.Language))
        {
            settings.Language = "en";
        }

        if (settings.Theme is not ("System" or "Light" or "Dark"))
        {
            settings.Theme = "System";
        }

        var accents = new[] { "Teal", "Blue", "Purple", "Green", "Orange", "Pink" };
        if (!accents.Contains(settings.AccentColor, StringComparer.OrdinalIgnoreCase))
        {
            settings.AccentColor = "Teal";
        }
    }
}

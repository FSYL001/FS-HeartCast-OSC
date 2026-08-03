using System.Windows;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Win32;

namespace HOSC.Services;

public static class ThemeService
{
    private static DispatcherTimer? _systemThemeTimer;
    private static readonly IReadOnlyDictionary<string, (string Accent, string OnAccent)> AccentPalette =
        new Dictionary<string, (string, string)>(StringComparer.OrdinalIgnoreCase)
        {
            ["Teal"] = ("#3CC4BB", "#04211F"),
            ["Blue"] = ("#4A9EFF", "#061A33"),
            ["Purple"] = ("#9B7BFF", "#1A1033"),
            ["Green"] = ("#4CC38A", "#04241A"),
            ["Orange"] = ("#FF9F5A", "#2B1505"),
            ["Pink"] = ("#FF6FA5", "#2B0A18"),
        };

    public static void Apply(string mode)
    {
        if (Application.Current == null)
        {
            return;
        }

        var useLight = mode == "Light" || (mode == "System" && IsSystemLight());
        var assemblyName = typeof(ThemeService).Assembly.GetName().Name;
        var source = useLight
            ? new Uri($"pack://application:,,,/{assemblyName};component/Themes/Light.xaml", UriKind.Absolute)
            : new Uri($"pack://application:,,,/{assemblyName};component/Themes/Dark.xaml", UriKind.Absolute);

        Application.Current.Resources.MergedDictionaries.Clear();
        Application.Current.Resources.MergedDictionaries.Add(new ResourceDictionary { Source = source });
        ApplyAccent(SettingsService.Current.AccentColor);
    }

    public static void ApplyAccent(string accent)
    {
        if (Application.Current == null || !AccentPalette.TryGetValue(accent, out var colors))
        {
            return;
        }

        var accentColor = (Color)ColorConverter.ConvertFromString(colors.Accent);
        var onAccentColor = (Color)ColorConverter.ConvertFromString(colors.OnAccent);
        SetBrush("AccentBrush", accentColor);
        SetBrush("AccentTextBrush", onAccentColor);
        SetBrush("RowSelectedBrush", Color.FromArgb(0x33, accentColor.R, accentColor.G, accentColor.B));
    }

    private static void SetBrush(string key, Color color)
    {
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        Application.Current.Resources[key] = brush;
    }

    public static void StartSystemThemeWatcher()
    {
        if (_systemThemeTimer != null)
        {
            return;
        }

        _systemThemeTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(3) };
        _systemThemeTimer.Tick += (_, _) =>
        {
            if (SettingsService.Current.Theme == "System")
            {
                Apply("System");
            }
        };
        _systemThemeTimer.Start();
    }

    private static bool IsSystemLight()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            if (key?.GetValue("AppsUseLightTheme") is int appsValue)
            {
                return appsValue != 0;
            }

            if (key?.GetValue("SystemUsesLightTheme") is int systemValue)
            {
                return systemValue != 0;
            }

            return true;
        }
        catch
        {
            return true;
        }
    }
}

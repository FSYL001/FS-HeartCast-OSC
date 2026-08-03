using System.Diagnostics;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Navigation;
using HOSC.Localization;
using HOSC.Services;

namespace HOSC;

public partial class SettingsWindow : Window
{
    private bool _initializing = true;

    public SettingsWindow()
    {
        InitializeComponent();

        LoadSettings();
        CategoryList.SelectedIndex = 0;
        _initializing = false;
    }

    private void LoadSettings()
    {
        var settings = SettingsService.Current;
        AutoStartScanCheckBox.IsChecked = settings.AutoStartScan;
        StartWithWindowsCheckBox.IsChecked = StartupService.IsEnabled();
        AlwaysOnTopCheckBox.IsChecked = settings.AlwaysOnTop;

        SelectComboItemByTag(LanguageComboBox, settings.Language);
        SelectComboItemByTag(ThemeComboBox, settings.Theme);
        SelectComboItemByTag(AccentComboBox, settings.AccentColor);

        var version = typeof(SettingsWindow).Assembly.GetName().Version;
        VersionText.Text = $"{Loc("VersionLabel")} {version?.ToString(3) ?? "1.0.0"}";
    }

    private static string Loc(string key) => AppResources.Instance[key];

    private static void SelectComboItemByTag(ComboBox comboBox, string tag)
    {
        foreach (ComboBoxItem item in comboBox.Items)
        {
            if (string.Equals(item.Tag as string, tag, StringComparison.OrdinalIgnoreCase))
            {
                comboBox.SelectedItem = item;
                return;
            }
        }
    }

    private void CategoryList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        var tag = (CategoryList.SelectedItem as ListBoxItem)?.Tag as string ?? "General";
        GeneralPage.Visibility = tag == "General" ? Visibility.Visible : Visibility.Collapsed;
        AppearancePage.Visibility = tag == "Appearance" ? Visibility.Visible : Visibility.Collapsed;
        AboutPage.Visibility = tag == "About" ? Visibility.Visible : Visibility.Collapsed;
    }

    private void AutoStartScan_Changed(object sender, RoutedEventArgs e)
    {
        if (_initializing)
        {
            return;
        }

        SettingsService.Current.AutoStartScan = AutoStartScanCheckBox.IsChecked == true;
        SettingsService.Save();
    }

    private void StartWithWindows_Changed(object sender, RoutedEventArgs e)
    {
        if (_initializing)
        {
            return;
        }

        StartupService.SetEnabled(StartWithWindowsCheckBox.IsChecked == true);
        StartWithWindowsCheckBox.IsChecked = StartupService.IsEnabled();
    }

    private void AlwaysOnTop_Changed(object sender, RoutedEventArgs e)
    {
        if (_initializing)
        {
            return;
        }

        var enabled = AlwaysOnTopCheckBox.IsChecked == true;
        SettingsService.Current.AlwaysOnTop = enabled;
        SettingsService.Save();

        if (Owner != null)
        {
            Owner.Topmost = enabled;
        }
    }

    private void Language_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_initializing || LanguageComboBox.SelectedItem is not ComboBoxItem { Tag: string code })
        {
            return;
        }

        AppResources.Instance.SetLanguage(code);
        SettingsService.Current.Language = code;
        SettingsService.Save();

        var version = typeof(SettingsWindow).Assembly.GetName().Version;
        VersionText.Text = $"{Loc("VersionLabel")} {version?.ToString(3) ?? "1.0.0"}";
    }

    private void Theme_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_initializing || ThemeComboBox.SelectedItem is not ComboBoxItem { Tag: string mode })
        {
            return;
        }

        ThemeService.Apply(mode);
        SettingsService.Current.Theme = mode;
        SettingsService.Save();
    }

    private void Accent_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_initializing || AccentComboBox.SelectedItem is not ComboBoxItem { Tag: string accent })
        {
            return;
        }

        ThemeService.ApplyAccent(accent);
        SettingsService.Current.AccentColor = accent;
        SettingsService.Save();
    }

    private void Done_Click(object sender, RoutedEventArgs e)
    {
        Close();
    }

    private void Hyperlink_RequestNavigate(object sender, RequestNavigateEventArgs e)
    {
        try
        {
            Process.Start(new ProcessStartInfo(e.Uri.AbsoluteUri) { UseShellExecute = true });
        }
        catch
        {
            // Opening an external link must never crash the settings window.
        }

        e.Handled = true;
    }
}

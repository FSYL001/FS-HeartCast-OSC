using System.Windows;
using HOSC.Services;

namespace HOSC;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        SettingsService.Load();
        ThemeService.Apply(SettingsService.Current.Theme);
        ThemeService.StartSystemThemeWatcher();
        Localization.AppResources.Instance.SetLanguage(SettingsService.Current.Language);

        MainWindow = new MainWindow();
        MainWindow.Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        SettingsService.Save();
        base.OnExit(e);
    }
}

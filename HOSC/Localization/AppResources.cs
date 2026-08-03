using System.ComponentModel;
using System.Globalization;
using System.Resources;

namespace HOSC.Localization;

public sealed class AppResources : INotifyPropertyChanged
{
    public static AppResources Instance { get; } = new();

    private readonly ResourceManager _resources =
        new("HOSC.Resources.Strings", typeof(AppResources).Assembly);
    private CultureInfo _culture = new("en");

    private AppResources()
    {
    }

    public string LanguageCode => _culture.Name;

    public string this[string key] => _resources.GetString(key, _culture) ?? key;

    public event PropertyChangedEventHandler? PropertyChanged;

    public void SetLanguage(string code)
    {
        _culture = new CultureInfo(code);
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(string.Empty));
    }
}

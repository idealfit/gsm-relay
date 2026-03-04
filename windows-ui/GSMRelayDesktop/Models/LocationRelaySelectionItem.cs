using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace GSMRelayDesktop.Models;

public class LocationRelaySelectionItem : INotifyPropertyChanged
{
    public Relay Relay { get; }

    private bool _isSelected;
    public bool IsSelected
    {
        get => _isSelected;
        set
        {
            if (_isSelected == value) return;
            _isSelected = value;
            OnPropertyChanged();
        }
    }

    public LocationRelaySelectionItem(Relay relay, bool isSelected = true)
    {
        Relay = relay;
        _isSelected = isSelected;
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void OnPropertyChanged([CallerMemberName] string? propertyName = null)
    {
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
    }
}

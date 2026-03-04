using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace GSMRelayDesktop.Models;

public class ServerConfig : INotifyPropertyChanged
{
    private string _baseUrl = "";
    private string _username = "";
    private string _password = "";
    private string _gatewayId = "";
    private string _masterPhone = "";

    public string BaseUrl
    {
        get => _baseUrl;
        set => SetField(ref _baseUrl, value);
    }

    public string Username
    {
        get => _username;
        set => SetField(ref _username, value);
    }

    public string Password
    {
        get => _password;
        set => SetField(ref _password, value);
    }

    public string GatewayId
    {
        get => _gatewayId;
        set => SetField(ref _gatewayId, value);
    }

    public string MasterPhone
    {
        get => _masterPhone;
        set => SetField(ref _masterPhone, value);
    }

    public bool IsValid()
    {
        return !string.IsNullOrWhiteSpace(BaseUrl)
            && !string.IsNullOrWhiteSpace(Username)
            && !string.IsNullOrWhiteSpace(Password);
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private void SetField(ref string field, string value, [CallerMemberName] string? name = null)
    {
        if (field == value) return;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}

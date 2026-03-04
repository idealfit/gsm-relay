using System.Windows;

namespace GSMRelayDesktop;

public partial class EditRelayDialogWindow : Window
{
    public string RelayName { get; private set; }
    public string RelayPhone { get; private set; }
    public string RelayPassword { get; private set; }
    public string RelayLocation { get; private set; }

    public EditRelayDialogWindow(string relayName, string relayPhone, string relayPassword, string relayLocation)
    {
        InitializeComponent();
        RelayName = relayName ?? string.Empty;
        RelayPhone = relayPhone ?? string.Empty;
        RelayPassword = relayPassword ?? string.Empty;
        RelayLocation = relayLocation ?? string.Empty;

        RelayNameTextBox.Text = RelayName;
        RelayPhoneTextBox.Text = RelayPhone;
        RelayPasswordTextBox.Text = RelayPassword;
        RelayLocationTextBox.Text = RelayLocation;
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        var name = RelayNameTextBox.Text?.Trim() ?? string.Empty;
        var phone = RelayPhoneTextBox.Text?.Trim() ?? string.Empty;
        var password = RelayPasswordTextBox.Text?.Trim() ?? string.Empty;
        var location = RelayLocationTextBox.Text?.Trim() ?? string.Empty;
        if (string.IsNullOrWhiteSpace(name) || string.IsNullOrWhiteSpace(phone))
        {
            MessageBox.Show(
                "Numele si numarul de telefon sunt obligatorii.",
                "Validare",
                MessageBoxButton.OK,
                MessageBoxImage.Warning
            );
            return;
        }

        RelayName = name;
        RelayPhone = phone;
        RelayPassword = password;
        RelayLocation = location;
        DialogResult = true;
    }

    private void Cancel_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
    }
}

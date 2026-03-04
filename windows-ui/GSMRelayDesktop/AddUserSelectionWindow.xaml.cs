using System.Windows;
using System.Windows.Controls;

namespace GSMRelayDesktop;

public partial class AddUserSelectionWindow : Window
{
    public string UserPhone { get; private set; } = "";
    public string UserName { get; private set; } = "";
    public string UserGroup { get; private set; } = "general";

    public AddUserSelectionWindow()
    {
        InitializeComponent();
        PhoneTextBox.Focus();
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        var phone = PhoneTextBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(phone))
        {
            MessageBox.Show(
                "Telefonul este obligatoriu.",
                "Adauga utilizator",
                MessageBoxButton.OK,
                MessageBoxImage.Warning
            );
            return;
        }

        UserPhone = phone;
        UserName = NameTextBox.Text.Trim();
        UserGroup = (GroupComboBox.SelectedItem as ComboBoxItem)?.Content?.ToString() ?? "general";
        DialogResult = true;
        Close();
    }

    private void Cancel_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }
}

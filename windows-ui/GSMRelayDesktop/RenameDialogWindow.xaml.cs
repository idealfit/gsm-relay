using System.Windows;

namespace GSMRelayDesktop;

public partial class RenameDialogWindow : Window
{
    public string ResultText { get; private set; } = "";

    public RenameDialogWindow(string title, string prompt, string initialValue)
    {
        InitializeComponent();
        Title = title;
        PromptTextBlock.Text = prompt;
        ValueTextBox.Text = initialValue;
        ValueTextBox.SelectAll();
        ValueTextBox.Focus();
    }

    private void Save_Click(object sender, RoutedEventArgs e)
    {
        ResultText = ValueTextBox.Text.Trim();
        DialogResult = true;
        Close();
    }

    private void Cancel_Click(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }
}

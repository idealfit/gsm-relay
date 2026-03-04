using System.Windows;
using GSMRelayDesktop.ViewModels;

namespace GSMRelayDesktop;

public partial class RelayEditorWindow : Window
{
    public RelayEditorWindow()
    {
        InitializeComponent();
    }

    private void Close_Click(object sender, RoutedEventArgs e)
    {
        Close();
    }

    private void AddRelay_Click(object sender, RoutedEventArgs e)
    {
        if (DataContext is not MainViewModel vm)
        {
            Close();
            return;
        }

        if (vm.AddRelayCommand.CanExecute(null))
        {
            vm.AddRelayCommand.Execute(null);
        }

        Close();
    }
}

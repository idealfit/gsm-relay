using System.Linq;
using System.Windows;
using GSMRelayDesktop.ViewModels;

namespace GSMRelayDesktop;

public partial class MainWindow : Window
{
    public MainViewModel ViewModel { get; } = new();

    public MainWindow()
    {
        InitializeComponent();
        DataContext = ViewModel;
        Loaded += OnLoaded;
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        Loaded -= OnLoaded;
        await ViewModel.InitializeOnStartupAsync();
    }

    private void OpenRelayDialog_Click(object sender, RoutedEventArgs e)
    {
        ViewModel.NewRelayName = "";
        ViewModel.NewRelayPhone = "";
        ViewModel.NewRelayLocation = "";
        ViewModel.ResetNewRelayOnboardingOptions();

        var dialog = new RelayEditorWindow
        {
            Owner = this,
            DataContext = ViewModel
        };
        try
        {
            ViewModel.IsRelayEditorOpen = true;
            dialog.ShowDialog();
        }
        finally
        {
            ViewModel.IsRelayEditorOpen = false;
        }
    }

    private void OpenRelayDialogForLocation_Click(object sender, RoutedEventArgs e)
    {
        var location = (sender as FrameworkElement)?.Tag as string ?? ViewModel.SelectedLocation ?? "";
        ViewModel.SelectedLocation = string.IsNullOrWhiteSpace(location) ? ViewModel.SelectedLocation : location;
        ViewModel.NewRelayName = "";
        ViewModel.NewRelayPhone = "";
        ViewModel.NewRelayLocation = location;
        ViewModel.ResetNewRelayOnboardingOptions();

        var dialog = new RelayEditorWindow
        {
            Owner = this,
            DataContext = ViewModel
        };
        try
        {
            ViewModel.IsRelayEditorOpen = true;
            dialog.ShowDialog();
        }
        finally
        {
            ViewModel.IsRelayEditorOpen = false;
        }
    }

    private void OpenAddLocationDialog_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new RenameDialogWindow(
            "Adauga locatie",
            "Nume locatie noua:",
            ""
        )
        {
            Owner = this
        };
        if (dialog.ShowDialog() != true) return;
        if (string.IsNullOrWhiteSpace(dialog.ResultText)) return;

        ViewModel.NewLocationName = dialog.ResultText;
        if (ViewModel.AddLocationCommand.CanExecute(null))
        {
            ViewModel.AddLocationCommand.Execute(null);
        }
    }

    private void OpenAddUserForSelectionDialog_Click(object sender, RoutedEventArgs e)
    {
        if (ViewModel.LocationRelaySelections.Count == 0 || ViewModel.LocationRelaySelections.All(x => !x.IsSelected))
        {
            MessageBox.Show(
                "Selecteaza cel putin un releu din locatie.",
                "Adaugare utilizator",
                MessageBoxButton.OK,
                MessageBoxImage.Information
            );
            return;
        }

        var dialog = new AddUserSelectionWindow
        {
            Owner = this
        };
        if (dialog.ShowDialog() != true) return;
        if (string.IsNullOrWhiteSpace(dialog.UserPhone)) return;

        ViewModel.NewUserPhone = dialog.UserPhone;
        ViewModel.NewUserName = dialog.UserName;
        ViewModel.NewUserGroup = dialog.UserGroup;

        if (ViewModel.AddUserToLocationCommand.CanExecute(null))
        {
            ViewModel.AddUserToLocationCommand.Execute(null);
        }
    }
}

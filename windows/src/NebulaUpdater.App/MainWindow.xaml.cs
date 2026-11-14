using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace NebulaUpdater.App;

public sealed partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);

        // 初始页面
        ContentFrame.Navigate(typeof(Views.MainPage));

        // 窗体内存：固定尺寸，避免用户拖到极小后布局塌掉
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1000, 720));
    }

    private void Nav_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        var tag = (args.SelectedItemContainer as NavigationViewItem)?.Tag as string;
        switch (tag)
        {
            case "main":
                ContentFrame.Navigate(typeof(Views.MainPage));
                break;
            case "server":
                ContentFrame.Navigate(typeof(Views.ServerPage));
                break;
            case "log":
                ContentFrame.Navigate(typeof(Views.LogPage));
                break;
        }

        // 设置项不是普通 MenuItem，单独处理
        if (args.IsSettingsSelected)
        {
            ContentFrame.Navigate(typeof(Views.SettingsPage));
        }
    }
}
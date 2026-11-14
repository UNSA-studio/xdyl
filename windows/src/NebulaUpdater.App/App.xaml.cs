using Microsoft.UI.Xaml;

namespace NebulaUpdater.App;

/// <summary>应用入口。</summary>
public partial class App : Application
{
    private Window? _window;

    /// <summary>当前主窗口。文件夹选择器需要它拿 HWND。</summary>
    public static MainWindow? MainWindowInstance { get; private set; }

    public App()
    {
        InitializeComponent();
        UnhandledException += OnUnhandledException;
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        var win = new MainWindow();
        MainWindowInstance = win;
        _window = win;
        win.Activate();
    }

    /// <summary>
    /// 兜底异常处理：WinUI 里未处理异常会直接静默退出，
    /// 所以至少要写进日志文件，否则用户报障时无从查起。
    /// </summary>
    private void OnUnhandledException(object sender, Microsoft.UI.Xaml.UnhandledExceptionEventArgs e)
    {
        try
        {
            Directory.CreateDirectory(Core.UpdaterConstants.LogDir);
            var path = Path.Combine(Core.UpdaterConstants.LogDir,
                $"crash_{DateTime.Now:yyyyMMdd_HHmmss}.log");
            File.WriteAllText(path, $"{DateTime.Now}\n{e.Exception}\n\n{e.Message}");
        }
        catch
        {
            // 日志都写不了就只能放弃
        }
    }
}
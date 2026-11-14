using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using NebulaUpdater.App.ViewModels;
using Windows.Storage.Pickers;

namespace NebulaUpdater.App.Views;

public sealed partial class MainPage : Page
{
    public MainViewModel Vm { get; } = new();

    public MainPage()
    {
        InitializeComponent();
    }

    /// <summary>手动选择 mods 文件夹</summary>
    private async void OnPickFolder(object sender, RoutedEventArgs e)
    {
        var picker = new FolderPicker();
        picker.SuggestedStartLocation = PickerLocationId.ComputerFolder;
        picker.FileTypeFilter.Add("*");

        // WinUI 3 必须把 picker 关联到窗口句柄，否则直接抛异常
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(App.MainWindowInstance);
        WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);

        var folder = await picker.PickSingleFolderAsync();
        if (folder == null) return;

        Vm.SetModsDirectory(folder.Path);
    }

    /// <summary>
    /// 自动检测常见启动器目录。
    ///
    /// 覆盖 PCL2 / HMCL / 官方启动器 / 常见整合包目录，
    /// 命中就填进输入框，避免用户手动翻文件夹。
    /// </summary>
    private void OnAutoDetect(object sender, RoutedEventArgs e)
    {
        var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var candidates = new[]
        {
            Path.Combine(home, "Desktop"),
            Path.Combine(home, "Documents"),
            Path.Combine(home, "Downloads"),
            Path.Combine(home, "AppData", "Roaming", ".minecraft"),
            "C:\\Program Files (x86)\\Minecraft Launcher",
            "D:\\", "E:\\"
        };

        foreach (var root in candidates)
        {
            if (!Directory.Exists(root)) continue;
            try
            {
                // 先看根下有没有 .minecraft/versions
                var direct = Path.Combine(root, ".minecraft", "versions");
                if (Directory.Exists(direct))
                {
                    var hit = FindFirstMods(direct);
                    if (hit != null)
                    {
                        Vm.SetModsDirectory(hit);
                        return;
                    }
                }

                // 再往下扫两层（整合包常见结构）
                foreach (var sub in Directory.EnumerateDirectories(root).Take(200))
                {
                    var versions = Path.Combine(sub, ".minecraft", "versions");
                    if (Directory.Exists(versions))
                    {
                        var hit = FindFirstMods(versions);
                        if (hit != null)
                        {
                            Vm.SetModsDirectory(hit);
                            return;
                        }
                    }
                }
            }
            catch (UnauthorizedAccessException)
            {
                // 某些系统目录没权限，跳过继续找
            }
            catch (IOException)
            {
                // 盘符不存在等，跳过
            }
        }

        Vm.AppendLog("自动检测未找到，请手动选择 mods 文件夹");
    }

    private static string? FindFirstMods(string versionsDir)
    {
        foreach (var ver in Directory.EnumerateDirectories(versionsDir))
        {
            var mods = Path.Combine(ver, "mods");
            if (Directory.Exists(mods)) return mods;
        }
        return null;
    }
}
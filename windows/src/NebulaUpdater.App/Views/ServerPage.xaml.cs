using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using NebulaUpdater.Core.Services;

namespace NebulaUpdater.App.Views;

public sealed partial class ServerPage : Page
{
    private const string ServerHost = "82.157.155.86";
    private const string WifiHost = "8.8.8.8";

    private bool _busy;

    public ServerPage()
    {
        InitializeComponent();
    }

    private async void OnPingAll(object sender, RoutedEventArgs e)
    {
        if (_busy) return;
        _busy = true;
        BusyRow.Visibility = Visibility.Visible;

        try
        {
            // 服务器 + 网络出口：走 ICMP
            TxtServer.Text = "检测中...";
            TxtWifi.Text = "检测中...";
            var serverTask = PingService.PingHostAsync(ServerHost);
            var wifiTask = PingService.PingHostAsync(WifiHost);
            await Task.WhenAll(serverTask, wifiTask);

            var s = await serverTask;
            var w = await wifiTask;
            // 不显示具体 IP，只给结果
            TxtServer.Text = s.Success ? $"正常 · {s.Display}" : $"不通 · {s.Display}";
            TxtWifi.Text = w.Success ? $"正常 · {w.Display}" : $"不通 · {w.Display}";

            // MC 服务器：走扩展包的 mcstatus
            await PingMcAsync();
        }
        catch (Exception ex)
        {
            TxtMc.Text = $"检测出错：{ex.Message}";
        }
        finally
        {
            BusyRow.Visibility = Visibility.Collapsed;
            _busy = false;
        }
    }

    /// <summary>
    /// MC 状态查询流程（沿用旧版行为）：
    ///   扩展包未装 → 提示下载约 20MB
    ///   mcstatus 未装 → 自动 pip install
    ///   都就绪 → 执行查询脚本
    /// </summary>
    private async Task PingMcAsync()
    {
        if (!PyExtManager.IsReady())
        {
            var ok = await ShowConfirmAsync(
                "需要扩展程序包",
                "此功能需要下载扩展程序包（嵌入式 Python 运行包，约 15MB；" +
                "mcstatus 会在稍后自动安装）。\n\n" +
                "确认后将自动下载安装到 %USERPROFILE%\\.xdyl\\python_root，期间请耐心等待。");
            if (!ok)
            {
                TxtMc.Text = "已取消（需要扩展程序包）";
                return;
            }

            TxtMc.Text = "正在下载扩展程序包...";
            var progress = new Progress<string>(msg => TxtMc.Text = msg);
            var err = await PyExtManager.InstallAsync(msg =>
            {
                // Progress<T> 会回到同步上下文，这里直接更新 UI
                ((IProgress<string>)progress).Report(msg);
            });

            if (err != null)
            {
                TxtMc.Text = $"扩展包安装失败：{err}";
                return;
            }

            TxtMc.Text = "扩展程序包就绪，正在安装 mcstatus...";
            var ierr = await Task.Run(PyExtManager.InstallMcstatus);
            if (ierr != null)
            {
                TxtMc.Text = $"mcstatus 安装失败：{ierr}";
                return;
            }
            TxtMc.Text = "mcstatus 安装完成，再次点击「检测全部」即可查询";
            return;
        }

        TxtMc.Text = "查询中...";
        var result = await Task.Run(PyExtManager.QueryPing);

        // mcstatus 缺失时现场补装，装完提示用户再点一次（与旧版一致）
        if (result.Contains("mcstatus 未安装"))
        {
            TxtMc.Text = "检测到缺少 mcstatus，正在自动安装...";
            var ierr = await Task.Run(PyExtManager.InstallMcstatus);
            TxtMc.Text = ierr == null
                ? "mcstatus 安装完成!\n再次点击 Ping（MC服务器）即可查询"
                : $"mcstatus 安装失败: {ierr}";
            return;
        }

        TxtMc.Text = result;
    }

    private async Task<bool> ShowConfirmAsync(string title, string message)
    {
        var dialog = new ContentDialog
        {
            Title = title,
            Content = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap },
            PrimaryButtonText = "下载安装",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Primary,
            XamlRoot = XamlRoot
        };
        return await dialog.ShowAsync() == ContentDialogResult.Primary;
    }
}
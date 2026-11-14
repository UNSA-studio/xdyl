using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using NebulaUpdater.Core.Services;

namespace NebulaUpdater.App.Views;

public sealed partial class ServerPage : Page
{
    private const string McHost = "mc.lanternwaves.fun";
    private const int McPort = 25565;

    public ServerPage()
    {
        InitializeComponent();
    }

    private async void OnPingAll(object sender, RoutedEventArgs e)
    {
        BusyRow.Visibility = Visibility.Visible;
        TxtServer.Text = "检测中...";
        TxtWifi.Text = "检测中...";
        TxtMc.Text = "检测中...";

        try
        {
            // 并行执行，三者互不阻塞
            var serverTask = PingService.PingHostAsync("82.157.155.86");
            var wifiTask = PingService.PingHostAsync("8.8.8.8");
            var mcTask = PingService.QueryMcServerAsync(McHost, McPort);

            await Task.WhenAll(serverTask, wifiTask, mcTask);

            var s = await serverTask;
            var w = await wifiTask;
            var mc = await mcTask;

            // 不显示具体 IP，只给结果，符合"不暴露服务端地址"的口径
            TxtServer.Text = s.Success ? $"正常 · {s.Display}" : $"不通 · {s.Display}";
            TxtWifi.Text = w.Success ? $"正常 · {w.Display}" : $"不通 · {w.Display}";
            TxtMc.Text = mc != null ? mc.Display : "无法连接";
        }
        catch (Exception ex)
        {
            TxtMc.Text = $"检测出错：{ex.Message}";
        }
        finally
        {
            BusyRow.Visibility = Visibility.Collapsed;
        }
    }
}
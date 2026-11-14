using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using NebulaUpdater.Core;
using NebulaUpdater.Core.Services;

namespace NebulaUpdater.App.Views;

public sealed partial class SettingsPage : Page
{
    private readonly Settings _settings;

    public SettingsPage()
    {
        InitializeComponent();
        _settings = Settings.Load();

        TxtBaseUrl.Text = _settings.BaseUrl;
        NumThreads.Value = _settings.ThreadCount;
        SwCleanOrphans.IsOn = _settings.CleanOrphans;

        var ver = typeof(App).Assembly.GetName().Version?.ToString() ?? "?";
        TxtVersion.Text = $"星云更新器 {ver}";
        TxtBackend.Text = $"下载后端：{NativeCore.BackendDescription}（{NativeCore.GetVersion()}）";
    }

    private void OnSave(object sender, RoutedEventArgs e)
    {
        var url = TxtBaseUrl.Text.Trim();
        if (string.IsNullOrWhiteSpace(url))
        {
            TxtBaseUrl.Text = UpdaterConstants.DefaultBaseUrl;
            url = UpdaterConstants.DefaultBaseUrl;
        }

        _settings.BaseUrl = url;
        _settings.ThreadCount = (int)(double.IsNaN(NumThreads.Value) ? UpdaterConstants.DefaultThreadCount : NumThreads.Value);
        _settings.CleanOrphans = SwCleanOrphans.IsOn;
        _settings.Save();

        StatusTip("设置已保存");
    }

    /// <summary>用 InfoBar 之外最轻的方式提示：直接改「关于」卡的一行文字</summary>
    private void StatusTip(string text)
    {
        TxtBackend.Text = $"{text} · 后端：{NativeCore.BackendDescription}";
    }
}
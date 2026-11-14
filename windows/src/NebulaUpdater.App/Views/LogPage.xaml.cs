using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using NebulaUpdater.Core;

namespace NebulaUpdater.App.Views;

public sealed partial class LogPage : Page
{
    public LogPage()
    {
        InitializeComponent();
        Loaded += (_, _) => Refresh();
    }

    private void OnRefresh(object sender, RoutedEventArgs e) => Refresh();

    private void Refresh()
    {
        try
        {
            Directory.CreateDirectory(UpdaterConstants.LogDir);
            var files = Directory.GetFiles(UpdaterConstants.LogDir, "*.log")
                .OrderByDescending(f => f)
                .Take(3)
                .ToList();

            if (files.Count == 0)
            {
                TxtLog.Text = "暂无日志文件。\n\n日志会写入：" + UpdaterConstants.LogDir;
                return;
            }

            var sb = new System.Text.StringBuilder();
            foreach (var f in files)
            {
                sb.AppendLine($"===== {Path.GetFileName(f)} =====");
                try
                {
                    // 只读末尾，避免超大日志把界面卡死
                    var lines = File.ReadAllLines(f);
                    var tail = lines.Length > 500 ? lines[^500..] : lines;
                    sb.AppendLine(string.Join('\n', tail));
                }
                catch (Exception ex)
                {
                    sb.AppendLine($"(读取失败: {ex.Message})");
                }
                sb.AppendLine();
            }
            TxtLog.Text = sb.ToString();
        }
        catch (Exception ex)
        {
            TxtLog.Text = $"读取日志失败: {ex.Message}";
        }
    }

    private void OnOpenFolder(object sender, RoutedEventArgs e)
    {
        Directory.CreateDirectory(UpdaterConstants.LogDir);
        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
        {
            FileName = UpdaterConstants.LogDir,
            UseShellExecute = true
        });
    }
}
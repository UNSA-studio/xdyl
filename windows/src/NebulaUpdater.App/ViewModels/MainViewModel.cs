using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using NebulaUpdater.Core;
using NebulaUpdater.Core.Models;
using NebulaUpdater.Core.Services;

namespace NebulaUpdater.App.ViewModels;

/// <summary>
/// 主流程 ViewModel。
///
/// 所有跨线程的 UI 更新都通过 DispatcherQueue 回到 UI 线程，
/// 业务逻辑本身跑在 Task 里，不阻塞界面。
/// </summary>
public sealed partial class MainViewModel : ObservableObject
{
    private readonly Settings _settings;
    private CancellationTokenSource? _cts;

    public ObservableCollection<string> LogLines { get; } = new();

    [ObservableProperty]
    private string _statusText = "就绪";

    [ObservableProperty]
    private double _progressPercent;

    [ObservableProperty]
    private string _progressDetail = "";

    [ObservableProperty]
    private bool _isRunning;

    [ObservableProperty]
    private string _modsDirText = "未选择";

    [ObservableProperty]
    private string _backendText = NativeCore.BackendDescription;

    public MainViewModel()
    {
        _settings = Settings.Load();
        if (!string.IsNullOrWhiteSpace(_settings.ModsDirectory))
        {
            ModsDirText = _settings.ModsDirectory!;
        }
    }

    /// <summary>写一行日志（自动限长，避免长时间运行后内存膨胀）</summary>
    public void AppendLog(string line)
    {
        LogLines.Add($"[{DateTime.Now:HH:mm:ss}] {line}");
        while (LogLines.Count > 2000) LogLines.RemoveAt(0);
    }

    /// <summary>设置 mods 目录（从文件选择器回调进来）</summary>
    public void SetModsDirectory(string dir)
    {
        _settings.ModsDirectory = dir;
        _settings.Save();
        ModsDirText = dir;
        AppendLog($"已选择 mods 目录：{dir}");

        // 顺带检测 NeoForge 版本，提前给用户提示
        var root = UpdateService.FindGameRootFromMods(dir);
        if (root != null)
        {
            var neo = UpdateService.DetectNeoForgeVersion(root);
            if (neo != null)
            {
                AppendLog($"检测到 NeoForge {neo}");
                if (UpdateService.CompareVersion(neo, UpdaterConstants.MinNeoForgeVersion) < 0)
                {
                    AppendLog($"警告：NeoForge 版本低于 {UpdaterConstants.MinNeoForgeVersion}，可能无法启动");
                }
            }
        }
    }

    [RelayCommand]
    private async Task StartUpdateAsync()
    {
        if (IsRunning) return;

        if (string.IsNullOrWhiteSpace(_settings.ModsDirectory) || !Directory.Exists(_settings.ModsDirectory))
        {
            StatusText = "请先选择 mods 目录";
            return;
        }

        IsRunning = true;
        ProgressPercent = 0;
        StatusText = "准备中...";
        LogLines.Clear();
        AppendLog($"后端：{NativeCore.BackendDescription}（{NativeCore.GetVersion()}）");

        _cts = new CancellationTokenSource();
        try
        {
            var csv = await ModListParser.LoadDefaultCsvAsync(_cts.Token);
            var mods = ModListParser.Parse(csv);
            AppendLog($"清单共 {mods.Count} 个模组");

            var service = new UpdateService(_settings.BaseUrl, _settings.ThreadCount);
            var progress = new Progress<UpdateProgress>(p =>
            {
                ProgressPercent = p.BytePercent;
                if (!string.IsNullOrEmpty(p.CurrentFile))
                {
                    ProgressDetail = p.CurrentFile;
                }
                StatusText = $"已完成 {p.Completed}/{p.Total}" + (p.Failed > 0 ? $"，失败 {p.Failed}" : "");
            });

            var result = await service.RunAsync(
                _settings.ModsDirectory!,
                mods,
                _settings.CleanOrphans,
                progress,
                AppendLog,
                _cts.Token);

            StatusText = result.IsSuccess
                ? $"更新完成：下载 {result.Downloaded}，跳过 {result.Skipped}"
                : $"完成但有失败：{result.Failed} 个";
            ProgressPercent = 100;

            foreach (var f in result.FailedFiles.Take(20))
            {
                AppendLog($"失败：{f}");
            }
        }
        catch (OperationCanceledException)
        {
            StatusText = "已取消";
            AppendLog("任务已取消");
        }
        catch (Exception ex)
        {
            StatusText = "出错";
            AppendLog($"错误：{ex.Message}");
        }
        finally
        {
            IsRunning = false;
            _cts?.Dispose();
            _cts = null;
        }
    }

    [RelayCommand]
    private void Cancel() => _cts?.Cancel();

    [RelayCommand]
    private void OpenLogFolder()
    {
        Directory.CreateDirectory(UpdaterConstants.LogDir);
        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
        {
            FileName = UpdaterConstants.LogDir,
            UseShellExecute = true
        });
    }
}
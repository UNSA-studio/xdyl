using System.Text.Json;

namespace NebulaUpdater.Core;

/// <summary>
/// 用户配置，持久化到 %USERPROFILE%\.xdyl\settings.json。
///
/// 故意用简单的 JSON 文件而不是注册表：
/// 便携版（exe 安装包）和 MSI 版可以共用同一份配置，卸载也容易清干净。
/// </summary>
public sealed class Settings
{
    /// <summary>mods 目录（用户选择后记住）</summary>
    public string? ModsDirectory { get; set; }

    /// <summary>服务器基地址</summary>
    public string BaseUrl { get; set; } = UpdaterConstants.DefaultBaseUrl;

    /// <summary>并发下载线程数</summary>
    public int ThreadCount { get; set; } = UpdaterConstants.DefaultThreadCount;

    /// <summary>是否清理清单外的旧 jar</summary>
    public bool CleanOrphans { get; set; } = true;

    /// <summary>模组下载完成后是否自动启动游戏</summary>
    public bool LaunchAfterUpdate { get; set; }

    private static string FilePath => Path.Combine(UpdaterConstants.AppDataDir, "settings.json");

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    /// <summary>读取配置；文件不存在或损坏时返回默认值</summary>
    public static Settings Load()
    {
        try
        {
            if (!File.Exists(FilePath)) return new Settings();
            var json = File.ReadAllText(FilePath);
            return JsonSerializer.Deserialize<Settings>(json, JsonOpts) ?? new Settings();
        }
        catch
        {
            // 配置损坏不能导致程序起不来，直接给默认值
            return new Settings();
        }
    }

    /// <summary>保存配置</summary>
    public void Save()
    {
        try
        {
            Directory.CreateDirectory(UpdaterConstants.AppDataDir);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, JsonOpts));
        }
        catch
        {
            // 写盘失败不抛，避免影响主流程
        }
    }
}
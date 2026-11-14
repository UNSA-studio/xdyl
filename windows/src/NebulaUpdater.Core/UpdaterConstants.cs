namespace NebulaUpdater.Core;

/// <summary>
/// 全局常量。
///
/// 说明：服务器地址通过环境变量/配置可覆盖，方便换源；
/// 默认值与 Kotlin 版保持一致，避免行为漂移。
/// </summary>
public static class UpdaterConstants
{
    /// <summary>模组文件服务器基地址（末尾带 /）</summary>
    public const string DefaultBaseUrl = "http://82.157.155.86:5551/mods/";

    /// <summary>默认目标版本目录名</summary>
    public const string DefaultVersionDir = "1.21.1-NeoForge";

    /// <summary>模组子目录名</summary>
    public const string ModsDirName = "mods";

    /// <summary>NeoForge 最低版本要求</summary>
    public const string MinNeoForgeVersion = "21.1.228";

    /// <summary>并发下载默认上限（保守值，避免把服主服务器打满）</summary>
    public const int DefaultThreadCount = 16;

    /// <summary>单个文件最大重试次数</summary>
    public const int MaxRetries = 5;

    /// <summary>分块下载的分块大小（512KB）</summary>
    public const int ChunkSize = 524_288;

    /// <summary>应用数据目录（%USERPROFILE%\.xdyl）</summary>
    public static string AppDataDir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".xdyl");

    /// <summary>日志目录</summary>
    public static string LogDir => Path.Combine(AppDataDir, "logs");
}

/// <summary>
/// 错误码与说明。
///
/// 保留 Kotlin 版的编号，用户看历史日志时不会串。
/// </summary>
public static class ErrorCodes
{
    public const string Error01 = "ERROR01";
    public const string Error02 = "ERROR02";
    public const string Error03 = "ERROR03";
    public const string Error05 = "ERROR05";
    public const string Error06 = "ERROR06";
    public const string Error07 = "ERROR07";
    public const string Error08 = "ERROR08";
    public const string Error10 = "ERROR10";

    public static readonly IReadOnlyDictionary<string, string> Descriptions = new Dictionary<string, string>
    {
        [Error01] = "找不到游戏目录。请确保已选择正确的启动器根目录（应包含 .minecraft 文件夹）。\n如果版本文件夹名称有误，请在设置中修改。",
        [Error02] = "没有文件读写权限。请检查游戏目录是否被其他程序占用，或尝试以管理员身份运行。",
        [Error03] = "网络连接超时。可能原因：\n- 服务器暂时不可达\n- 网络防火墙限制\n- 网络不稳定\n请稍后重试或检查网络环境。",
        [Error05] = "模组文件校验失败。部分文件下载不完整或 MD5/SHA256 不匹配。\n请重新执行更新，若反复出现请导出日志并联系开发者。",
        [Error06] = "找到了名称相似的文件夹，但不是目标版本文件夹。\n请手动选择正确的版本文件夹。",
        [Error07] = "NeoForge 版本过低，需要 21.1.228 或更高版本。\n请更新 NeoForge 驱动后再试。",
        [Error08] = "无法从服务器获取文件列表。请确认服务器地址是否可用，或服务器页面是否发生了改变。",
        [Error10] = "发生未知错误。请导出日志并联系开发者。"
    };

    public static string Describe(string code) =>
        Descriptions.TryGetValue(code, out var text) ? text : Descriptions[Error10];
}
using System.Text.Json.Serialization;

namespace NebulaUpdater.Core.Models;

/// <summary>模组条目：来自 file_list.csv 的一行。</summary>
public sealed class ModInfo
{
    /// <summary>文件名（不含 ./ 前缀）</summary>
    public string FileName { get; init; } = "";

    /// <summary>人类可读体积，如 "12M"，仅用于展示</summary>
    public string SizeText { get; init; } = "";

    /// <summary>字节数</summary>
    public long Size { get; init; }

    /// <summary>MD5 小写十六进制</summary>
    public string Md5 { get; init; } = "";

    /// <summary>SHA-256 小写十六进制</summary>
    public string Sha256 { get; init; } = "";

    [JsonIgnore]
    public string DownloadUrl { get; set; } = "";
}

/// <summary>一次更新任务的统计结果。</summary>
public sealed class UpdateResult
{
    public int Total { get; init; }
    public int Downloaded { get; init; }
    public int Skipped { get; init; }
    public int Failed { get; init; }
    public int RemovedOrphans { get; init; }
    public List<string> FailedFiles { get; init; } = new();

    public bool IsSuccess => Failed == 0;
}

/// <summary>Ping 结果。</summary>
public sealed record PingResult(bool Success, string Display, string? RawError = null);

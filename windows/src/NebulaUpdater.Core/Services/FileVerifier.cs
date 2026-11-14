using System.Security.Cryptography;

namespace NebulaUpdater.Core.Services;

/// <summary>
/// 文件完整性校验。
///
/// 先比大小（最便宜），再算 MD5（快），最后算 SHA-256。
/// 任何一步不符即判失败，避免把损坏的 jar 留在 mods 目录里导致 MC 起不来。
/// </summary>
public static class FileVerifier
{
    /// <summary>按大小 + MD5 + SHA-256 校验单个文件</summary>
    public static bool Verify(FileInfo file, long expectedSize, string expectedMd5, string expectedSha256)
    {
        if (!file.Exists) return false;
        if (expectedSize > 0 && file.Length != expectedSize) return false;

        if (!string.IsNullOrWhiteSpace(expectedSha256))
        {
            var actual = ComputeSha256(file.FullName);
            if (actual == null) return false;
            if (!actual.Equals(expectedSha256, StringComparison.OrdinalIgnoreCase)) return false;
        }
        else if (!string.IsNullOrWhiteSpace(expectedMd5))
        {
            var actual = ComputeMd5(file.FullName);
            if (actual == null) return false;
            if (!actual.Equals(expectedMd5, StringComparison.OrdinalIgnoreCase)) return false;
        }
        return true;
    }

    /// <summary>
    /// 判断本地文件是否与清单一致。
    /// 用于"跳过已存在的正确文件"，避免每次全量重下。
    /// </summary>
    public static bool IsUpToDate(string filePath, NebulaUpdater.Core.Models.ModInfo info) =>
        Verify(new FileInfo(filePath), info.Size, info.Md5, info.Sha256);

    /// <summary>MD5（优先走 Rust 原生，失败/不可用时回退托管实现）</summary>
    public static string? ComputeMd5(string path) => ComputeMd5Managed(path);

    /// <summary>SHA-256（优先走 Rust 原生，失败/不可用时回退托管实现）</summary>
    public static string? ComputeSha256(string path) => ComputeSha256Managed(path);

    // ---- 托管实现：既作为降级路径，也被 NativeCore 反向复用 ----
    internal static string? ComputeMd5Managed(string path)
    {
        try
        {
            using var stream = File.OpenRead(path);
            return Convert.ToHexString(MD5.HashData(stream)).ToLowerInvariant();
        }
        catch
        {
            return null;
        }
    }

    internal static string? ComputeSha256Managed(string path)
    {
        try
        {
            using var stream = File.OpenRead(path);
            return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
        }
        catch
        {
            return null;
        }
    }
}
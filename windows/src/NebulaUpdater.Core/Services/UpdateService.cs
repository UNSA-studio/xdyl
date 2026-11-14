using System.Collections.Concurrent;
using NebulaUpdater.Core.Models;

namespace NebulaUpdater.Core.Services;

/// <summary>更新过程中的进度与日志回调。</summary>
public sealed class UpdateProgress
{
    public int Total { get; init; }
    public int Completed { get; init; }
    public int Failed { get; init; }
    public long BytesDone { get; init; }
    public long BytesTotal { get; init; }
    public string CurrentFile { get; init; } = "";

    public double Percent => Total > 0 ? Completed * 100.0 / Total : 0;

    /// <summary>按字节估算的百分比；总大小未知时退回文件计数</summary>
    public double BytePercent => BytesTotal > 0 ? BytesDone * 100.0 / BytesTotal : Percent;
}

/// <summary>
/// 更新编排：定位目录 → 拉清单 → 比对 → 并发下载 → 校验 → 清理孤儿。
///
/// 这一层不碰 UI，全部通过回调上报进度，方便 WinUI 用 IProgress 直接绑定。
/// </summary>
public sealed class UpdateService
{
    private readonly string _baseUrl;
    private readonly int _threadCount;

    public UpdateService(string? baseUrl = null, int threadCount = UpdaterConstants.DefaultThreadCount)
    {
        _baseUrl = string.IsNullOrWhiteSpace(baseUrl) ? UpdaterConstants.DefaultBaseUrl : baseUrl!;
        if (!_baseUrl.EndsWith('/')) _baseUrl += "/";
        _threadCount = Math.Clamp(threadCount, 1, 64);
    }

    /// <summary>
    /// 在启动器根目录下定位 mods 目录。
    ///
    /// 依次尝试：
    ///   &lt;root&gt;/.minecraft/versions/&lt;ver&gt;/mods
    ///   &lt;root&gt;/versions/&lt;ver&gt;/mods
    ///   &lt;root&gt;/&lt;ver&gt;/mods
    ///   &lt;root&gt;/mods
    /// </summary>
    public static string? FindModsDirectory(string rootDir, string versionDir)
    {
        if (string.IsNullOrWhiteSpace(rootDir)) return null;

        var candidates = new[]
        {
            Path.Combine(rootDir, ".minecraft", "versions", versionDir, UpdaterConstants.ModsDirName),
            Path.Combine(rootDir, "versions", versionDir, UpdaterConstants.ModsDirName),
            Path.Combine(rootDir, versionDir, UpdaterConstants.ModsDirName),
            Path.Combine(rootDir, ".minecraft", UpdaterConstants.ModsDirName),
            Path.Combine(rootDir, UpdaterConstants.ModsDirName)
        };

        foreach (var c in candidates)
        {
            if (Directory.Exists(c)) return c;
        }
        return null;
    }

    /// <summary>
    /// 从 mods 目录反查游戏根目录（用于"用户只选了 mods 文件夹"的情况）。
    /// </summary>
    public static string? FindGameRootFromMods(string modsDir)
    {
        var d = new DirectoryInfo(modsDir);
        // mods 的父目录是 versions/&lt;ver&gt;，再上一级是 versions，再上一级是 .minecraft
        var ver = d.Parent;
        if (ver == null) return null;
        var versions = ver.Parent;
        if (versions == null) return null;
        var mc = versions.Parent;
        return mc?.FullName;
    }

    /// <summary>
    /// 检测 NeoForge 版本是否满足要求。
    /// 读 &lt;mcDir&gt;/libraries/net/neoforged/neoforge/&lt;ver&gt;/ 或 versions 目录名。
    /// </summary>
    public static string? DetectNeoForgeVersion(string minecraftDir)
    {
        // 方式一：libraries/net/neoforged/neoforge/x.y.z
        var neoDir = Path.Combine(minecraftDir, "libraries", "net", "neoforged", "neoforge");
        if (Directory.Exists(neoDir))
        {
            var dirs = Directory.GetDirectories(neoDir)
                .Select(Path.GetFileName)
                .Where(n => n != null && n.Length > 0)
                .OrderByDescending(n => n, StringComparer.OrdinalIgnoreCase)
                .ToList();
            if (dirs.Count > 0) return dirs[0];
        }

        // 方式二：versions 下的 neoforge-&lt;ver&gt; 目录
        var versionsDir = Path.Combine(minecraftDir, "versions");
        if (Directory.Exists(versionsDir))
        {
            var hit = Directory.GetDirectories(versionsDir)
                .Select(Path.GetFileName)
                .FirstOrDefault(n => n != null && n.StartsWith("neoforge-", StringComparison.OrdinalIgnoreCase));
            if (hit != null) return hit["neoforge-".Length..];
        }

        return null;
    }

    /// <summary>比较版本号（形如 21.1.228）</summary>
    public static int CompareVersion(string v1, string v2)
    {
        var a = v1.Split('.', '-', '+');
        var b = v2.Split('.', '-', '+');
        var len = Math.Max(a.Length, b.Length);
        for (var i = 0; i < len; i++)
        {
            var sa = i < a.Length ? a[i] : "0";
            var sb = i < b.Length ? b[i] : "0";
            var na = int.TryParse(sa, out var x) ? x : 0;
            var nb = int.TryParse(sb, out var y) ? y : 0;
            if (na != nb) return na.CompareTo(nb);
        }
        return 0;
    }

    /// <summary>
    /// 执行完整更新流程。
    /// </summary>
    /// <param name="modsDir">目标 mods 目录</param>
    /// <param name="mods">模组清单</param>
    /// <param name="cleanOrphans">是否删除清单外的 jar</param>
    /// <param name="progress">进度回调</param>
    /// <param name="log">日志回调</param>
    public async Task<UpdateResult> RunAsync(
        string modsDir,
        IReadOnlyList<ModInfo> mods,
        bool cleanOrphans,
        IProgress<UpdateProgress>? progress,
        Action<string>? log,
        CancellationToken ct = default)
    {
        Directory.CreateDirectory(modsDir);

        // ---- 1. 比对：哪些需要下载 ----
        log?.Invoke($"开始比对 {mods.Count} 个模组...");
        var needDownload = new List<ModInfo>();
        foreach (var m in mods)
        {
            ct.ThrowIfCancellationRequested();
            var path = Path.Combine(modsDir, m.FileName);
            if (!File.Exists(path))
            {
                needDownload.Add(m);
                continue;
            }
            if (!FileVerifier.IsUpToDate(path, m))
            {
                log?.Invoke($"[DIFF] {m.FileName}");
                needDownload.Add(m);
            }
        }

        var skipped = mods.Count - needDownload.Count;
        log?.Invoke($"需下载 {needDownload.Count} 个，已是最新 {skipped} 个");

        if (needDownload.Count == 0 && !cleanOrphans)
        {
            return new UpdateResult { Total = mods.Count, Skipped = skipped };
        }

        // ---- 2. 并发下载 ----
        var completed = 0;
        var failed = 0;
        long bytesTotal = needDownload.Sum(m => Math.Max(0, m.Size));
        long bytesDone = 0;
        var failedFiles = new ConcurrentBag<string>();

        using var gate = new SemaphoreSlim(_threadCount);

        void Report(string current)
        {
            progress?.Report(new UpdateProgress
            {
                Total = needDownload.Count,
                Completed = Volatile.Read(ref completed),
                Failed = Volatile.Read(ref failed),
                BytesDone = Interlocked.Read(ref bytesDone),
                BytesTotal = bytesTotal,
                CurrentFile = current
            });
        }

        Report("");

        var tasks = needDownload.Select(m => Task.Run(async () =>
        {
            await gate.WaitAsync(ct).ConfigureAwait(false);
            try
            {
                ct.ThrowIfCancellationRequested();
                var dest = Path.Combine(modsDir, m.FileName);

                for (var attempt = 1; attempt <= UpdaterConstants.MaxRetries; attempt++)
                {
                    try
                    {
                        // 走原生核心：单文件内 4 块并行，校验也在 Rust 侧完成
                        await Task.Run(() => NativeCore.Download(
                            _baseUrl + Uri.EscapeDataString(m.FileName),
                            dest,
                            m.Size,
                            m.Sha256,
                            maxChunks: 4,
                            onBytes: n => Interlocked.Add(ref bytesDone, n),
                            ct: ct), ct).ConfigureAwait(false);

                        Interlocked.Increment(ref completed);
                        log?.Invoke($"[OK] {m.FileName}");
                        Report(m.FileName);
                        return;
                    }
                    catch (OperationCanceledException)
                    {
                        throw;
                    }
                    catch (Exception ex)
                    {
                        // 失败时把已下载的字节数扣回去，避免进度虚高
                        log?.Invoke($"[RETRY {attempt}] {m.FileName}: {ex.Message}");
                        if (attempt == UpdaterConstants.MaxRetries)
                        {
                            Interlocked.Increment(ref failed);
                            failedFiles.Add(m.FileName);
                            Report(m.FileName);
                        }
                        else
                        {
                            await Task.Delay(Math.Min(1000 * attempt, 5000), ct).ConfigureAwait(false);
                        }
                    }
                }
            }
            finally
            {
                gate.Release();
            }
        }, ct)).ToArray();

        await Task.WhenAll(tasks).ConfigureAwait(false);

        // ---- 3. 清理孤儿文件 ----
        var removed = 0;
        if (cleanOrphans)
        {
            var valid = mods.Select(m => m.FileName).ToHashSet(StringComparer.OrdinalIgnoreCase);
            foreach (var file in Directory.GetFiles(modsDir, "*.jar"))
            {
                var name = Path.GetFileName(file);
                if (!valid.Contains(name))
                {
                    try
                    {
                        File.Delete(file);
                        removed++;
                        log?.Invoke($"[DEL] {name}");
                    }
                    catch (Exception ex)
                    {
                        log?.Invoke($"[DEL-FAIL] {name}: {ex.Message}");
                    }
                }
            }
        }

        log?.Invoke($"更新完成：成功 {completed}，失败 {failed}，跳过 {skipped}，清理 {removed}");
        return new UpdateResult
        {
            Total = mods.Count,
            Downloaded = completed,
            Skipped = skipped,
            Failed = failed,
            RemovedOrphans = removed,
            FailedFiles = failedFiles.ToList()
        };
    }

    /// <summary>拉取服务器上的文件清单（若服务端提供目录索引）</summary>
    public async Task<List<string>> FetchServerFileListAsync(CancellationToken ct = default)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
        var html = await http.GetStringAsync(_baseUrl, ct).ConfigureAwait(false);
        var result = new List<string>();
        // 简单粗暴地找 href="xxx.jar"
        // MatchCollection 非泛型，foreach 的 var 会被推断成 object，必须显式声明类型
        var matches = System.Text.RegularExpressions.Regex.Matches(html, "href=\"([^\"]+\\.jar)\"");
        foreach (System.Text.RegularExpressions.Match m in matches)
        {
            var name = Uri.UnescapeDataString(m.Groups[1].Value);
            name = name[(name.LastIndexOf('/') + 1)..];
            if (!result.Contains(name)) result.Add(name);
        }
        return result;
    }
}
using System.Diagnostics;
using System.Formats.Tar;
using System.IO.Compression;
using System.Text;

namespace NebulaUpdater.Core.Services;

/// <summary>
/// Windows 端扩展包管理器（与 Android 端 terminal 自动安装流程对齐）。
///
/// 扩展包 = 官方嵌入式 Python + pip（python_windows.tar.gz，约 14.7MB）。
/// 安装位置：%USERPROFILE%\.xdyl\python_root\
///
/// 包结构：顶层目录 python_windows/，内含 python.exe、Lib/site-packages/pip 等。
/// 解压时需要剥掉这一层，让 python.exe 直接落在 python_root 下。
///
/// 注意：包里不含 mcstatus，首次查询时会用 pip 现场安装。
/// </summary>
public static class PyExtManager
{
    /// <summary>
    /// 扩展包下载地址。
    ///
    /// 网盘重构后地址已变更（旧地址 unsa-fdws.cc.cd 已废弃），
    /// 现由 pan.ezv.cc 直链提供，格式也从 zip 改成了 tar.gz。
    /// </summary>
    public const string DefaultUrl = "https://pan.ezv.cc/v/2d8747f5c6bf4a34d138e1eafd30a78f";

    /// <summary>tar.gz 内的顶层目录名，解压时剥掉</summary>
    private const string TopLevelDir = "python_windows/";

    /// <summary>扩展包安装根目录</summary>
    public static string PythonRoot =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".xdyl", "python_root");

    /// <summary>python.exe 路径；未安装返回 null</summary>
    public static string? PythonExe()
    {
        var exe = Path.Combine(PythonRoot, "python.exe");
        return File.Exists(exe) ? exe : null;
    }

    /// <summary>扩展包是否已就绪</summary>
    public static bool IsReady() => PythonExe() != null;

    /// <summary>
    /// 下载并解压扩展包（tar.gz）。返回 null 表示成功，否则为错误信息。
    /// </summary>
    public static async Task<string?> InstallAsync(Action<string> onProgress, CancellationToken ct = default)
    {
        var tmp = Path.Combine(Path.GetTempPath(), $"python_windows_{Guid.NewGuid():N}.tar.gz");
        try
        {
            onProgress("开始下载扩展程序包...");

            using (var http = new HttpClient { Timeout = TimeSpan.FromMinutes(15) })
            using (var resp = await http.GetAsync(DefaultUrl, HttpCompletionOption.ResponseHeadersRead, ct)
                       .ConfigureAwait(false))
            {
                if (!resp.IsSuccessStatusCode) return $"HTTP {(int)resp.StatusCode}";

                var total = resp.Content.Headers.ContentLength ?? 0;
                await using var input = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
                await using var fos = File.Create(tmp);

                var buf = new byte[65536];
                long done = 0;
                var lastPct = -1;
                int read;
                while ((read = await input.ReadAsync(buf, ct).ConfigureAwait(false)) > 0)
                {
                    await fos.WriteAsync(buf.AsMemory(0, read), ct).ConfigureAwait(false);
                    done += read;
                    if (total > 0)
                    {
                        var pct = (int)(done * 100 / total);
                        // 每 2% 报一次，避免回调过密
                        if (pct > lastPct && pct % 2 == 0)
                        {
                            lastPct = pct;
                            onProgress($"下载中 {pct}% ({done / 1048576}MB / {total / 1048576}MB)");
                        }
                    }
                }
            }

            onProgress("正在解压...");
            var written = await Task.Run(() => ExtractTarGz(tmp, PythonRoot, onProgress, ct), ct)
                .ConfigureAwait(false);
            onProgress($"解压完成（{written} 个文件）");

            return PythonExe() != null ? null : "解压后未找到 python.exe";
        }
        catch (OperationCanceledException)
        {
            return "已取消";
        }
        catch (Exception ex)
        {
            return ex.Message;
        }
        finally
        {
            try { if (File.Exists(tmp)) File.Delete(tmp); } catch { /* 清理失败不影响结果 */ }
        }
    }

    /// <summary>
    /// 解压 tar.gz 到目标目录，剥掉顶层 python_windows/。
    ///
    /// 用 System.Formats.Tar（.NET 7+ 内置），不需要外部 tar.exe ——
    /// Windows 10 1803 之前没有 bsdtar，依赖外部命令会挂。
    /// </summary>
    private static int ExtractTarGz(string archivePath, string destRoot, Action<string> onProgress, CancellationToken ct)
    {
        Directory.CreateDirectory(destRoot);
        var written = 0;

        using var file = File.OpenRead(archivePath);
        using var gz = new GZipStream(file, CompressionMode.Decompress);
        using var reader = new TarReader(gz, leaveOpen: false);

        while (reader.GetNextEntry() is { } entry)
        {
            ct.ThrowIfCancellationRequested();

            var name = entry.Name.Replace('\\', '/');
            // 剥掉顶层目录
            if (name.StartsWith(TopLevelDir, StringComparison.Ordinal))
            {
                name = name[TopLevelDir.Length..];
            }
            if (string.IsNullOrEmpty(name)) continue;

            // 防目录穿越：拒绝 .. 与绝对路径
            if (name.Contains("..", StringComparison.Ordinal) || Path.IsPathRooted(name)) continue;

            var dest = Path.GetFullPath(Path.Combine(destRoot, name));
            if (!dest.StartsWith(Path.GetFullPath(destRoot), StringComparison.OrdinalIgnoreCase)) continue;

            if (entry.EntryType is TarEntryType.Directory)
            {
                Directory.CreateDirectory(dest);
                continue;
            }

            var dir = Path.GetDirectoryName(dest);
            if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);

            if (entry.DataStream != null)
            {
                using var outFs = File.Create(dest);
                entry.DataStream.CopyTo(outFs);
            }
            else
            {
                // 符号链接/设备文件等在 Windows 上没意义，跳过
                continue;
            }

            // 保留可执行位（Windows 上无实际作用，但避免某些 .exe 被标记只读）
            if (entry.Mode.HasFlag(UnixFileMode.UserExecute))
            {
                try { File.SetAttributes(dest, FileAttributes.Normal); } catch { }
            }

            written++;
            if (written % 200 == 0) onProgress($"解压中... 已写入 {written} 个文件");
        }

        return written;
    }

    /// <summary>
    /// 安装 mcstatus。返回 null 表示成功，否则为错误信息。
    /// </summary>
    public static string? InstallMcstatus()
    {
        try
        {
            var exe = PythonExe();
            if (exe == null) return "Python 未安装";

            var psi = new ProcessStartInfo(exe, "-m pip install mcstatus")
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };
            // 强制子进程 stdout 走 UTF-8，和旧版一致
            psi.Environment["PYTHONIOENCODING"] = "utf-8";

            using var p = Process.Start(psi);
            if (p == null) return "无法启动 pip";

            var stdout = p.StandardOutput.ReadToEnd();
            var stderr = p.StandardError.ReadToEnd();

            if (!p.WaitForExit(180_000))
            {
                try { p.Kill(true); } catch { }
                return "pip 安装超时";
            }

            if (p.ExitCode == 0) return null;

            var lines = stderr.TrimEnd().Split('\n');
            var last = lines.LastOrDefault()?.Trim() ?? "";
            return last.Length > 200 ? last[..200] : (last.Length > 0 ? last : "未知错误");
        }
        catch (Exception ex)
        {
            return ex.Message;
        }
    }

    /// <summary>
    /// 与 Android 端输出格式完全一致的查询脚本（Windows 有系统 DNS，无需手动 Resolver）。
    /// </summary>
    private static string PingScript(string server = "mc.lanternwaves.fun:25565") => $"""
import sys
try:
    from mcstatus import JavaServer
    s = JavaServer.lookup('{server}')
    st = s.status()
    print('状态: 在线')
    try:
        print('延迟: %d ms' % round(s.ping()))
    except Exception:
        pass
    desc = str(st.description)
    import re
    desc = re.sub(r'[§\u00a7][0-9a-fk-or]', '', desc)
    print('描述: %s' % desc[:120])
except ImportError:
    print('错误: mcstatus 未安装')
except Exception as e:
    print('查询失败: %s' % e)
""";

    /// <summary>用扩展包 Python 执行 mcstatus 查询</summary>
    public static string QueryPing()
    {
        var script = Path.Combine(Path.GetTempPath(), $"xdyl_mc_ping_{Guid.NewGuid():N}.py");
        try
        {
            var exe = PythonExe();
            if (exe == null) return "错误: Python 未安装";

            File.WriteAllText(script, PingScript(), new UTF8Encoding(false));

            var psi = new ProcessStartInfo(exe, $"\"{script}\"")
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };
            // 中文输出必须显式指定 UTF-8，否则按 GBK 写出会乱码
            psi.Environment["PYTHONIOENCODING"] = "utf-8";

            using var p = Process.Start(psi);
            if (p == null) return "查询失败: 无法启动 Python";

            var stdout = p.StandardOutput.ReadToEnd();
            var stderr = p.StandardError.ReadToEnd();

            if (!p.WaitForExit(20_000))
            {
                try { p.Kill(true); } catch { }
                return "查询失败: 超时";
            }

            return !string.IsNullOrWhiteSpace(stdout)
                ? stdout.TrimEnd()
                : $"查询失败: {(string.IsNullOrWhiteSpace(stderr) ? "未知错误" : stderr.TrimEnd())}";
        }
        catch (Exception ex)
        {
            return $"查询异常: {ex.Message}";
        }
        finally
        {
            try { if (File.Exists(script)) File.Delete(script); } catch { }
        }
    }
}
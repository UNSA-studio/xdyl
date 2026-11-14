using System.Diagnostics;
using System.Formats.Tar;
using System.Text;

namespace NebulaUpdater.Core.Services;

/// <summary>
/// Windows 端扩展包管理器（与 Android 端 terminal 自动安装流程对齐）。
///
/// 扩展包 = 官方嵌入式 Python + pip + mcstatus（python_windows.zip）。
/// 安装位置：%USERPROFILE%\.xdyl\python_root\
///
/// 说明：这是从旧 Kotlin 版 PyExtManager 原样移植过来的，
/// 行为、路径、脚本内容都保持一致，不做"优化"。
/// </summary>
public static class PyExtManager
{
    /// <summary>扩展包直链（网盘重构后的新地址）。内容是 python_windows.tar.gz，不是 zip。</summary>
    public const string DefaultUrl = "https://pan.ezv.cc/v/2d8747f5c6bf4a34d138e1eafd30a78f";

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
    /// 下载并解压扩展包。返回 null 表示成功，否则为错误信息。
    /// </summary>
    public static async Task<string?> InstallAsync(Action<string> onProgress, CancellationToken ct = default)
    {
        try
        {
            onProgress("开始下载扩展程序包...");

            var tmp = Path.GetTempFileName();
            using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };

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
                        // 每 2% 报一次，避免回调过于频繁
                        if (pct > lastPct && pct % 2 == 0)
                        {
                            lastPct = pct;
                            onProgress($"下载中 {pct}% ({done / 1048576}MB)");
                        }
                    }
                }
            }

            onProgress("正在解压...");
            var root = PythonRoot;
            Directory.CreateDirectory(root);

            // 扩展包是 .tar.gz（不是 zip）。
            // System.Formats.Tar 从 .NET 7 起内置，不需要额外 NuGet 依赖。
            // 顶层目录 python_windows/ 要剥掉，解到 python_root 下才是 python.exe。
            using (var gz = new System.IO.Compression.GZipStream(
                       File.OpenRead(tmp), System.IO.Compression.CompressionMode.Decompress))
            using (var reader = new TarReader(gz))
            {
                TarEntry? entry;
                while ((entry = reader.GetNextEntry()) != null)
                {
                    // 只处理普通文件；目录/符号链接跳过（Windows 上符号链接多数用不到）
                    if (entry.EntryType != TarEntryType.RegularFile) continue;
                    if (entry.DataStream == null) continue;

                    var name = StripTopLevel(entry.Name.Replace('\\', '/'));
                    if (string.IsNullOrEmpty(name)) continue;

                    var dest = Path.Combine(root, name.Replace('/', Path.DirectorySeparatorChar));
                    Directory.CreateDirectory(Path.GetDirectoryName(dest)!);
                    using var fs = File.Create(dest);
                    entry.DataStream.CopyTo(fs);

                    // 保留可执行属性对 Windows 无意义，跳过
                }
            }

            try { File.Delete(tmp); } catch { /* 清理失败不影响结果 */ }

            onProgress("扩展包安装完成");
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

            if (!p.WaitForExit(120_000))
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

    /// <summary>兼容 zip 内带顶层目录或不带两种打包方式</summary>
    private static string StripTopLevel(string name)
    {
        var n = name.Replace('\\', '/');
        var idx = n.IndexOf('/');
        return idx > 0 ? n[(idx + 1)..] : n;
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
        try
        {
            var exe = PythonExe();
            if (exe == null) return "错误: Python 未安装";

            var script = Path.Combine(Path.GetTempPath(), "xdyl_mc_ping.py");
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

            try { File.Delete(script); } catch { }

            return !string.IsNullOrWhiteSpace(stdout)
                ? stdout.TrimEnd()
                : $"查询失败: {(string.IsNullOrWhiteSpace(stderr) ? "未知错误" : stderr.TrimEnd())}";
        }
        catch (Exception ex)
        {
            return $"查询异常: {ex.Message}";
        }
    }
}
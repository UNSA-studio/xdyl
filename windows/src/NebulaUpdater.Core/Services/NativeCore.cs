using System.Runtime.InteropServices;
using NebulaUpdater.Core.Native;

namespace NebulaUpdater.Core.Services;

/// <summary>
/// 原生能力的安全封装。
///
/// 这个类的存在意义是：把"跨语言边界"的丑陋全部收敛在这里，
/// 上层业务代码只看到普通的 async 方法，看不到 IntPtr 和委托。
///
/// 同时提供降级路径：如果 nebula_core.dll 加载失败（如被杀软拦、架构不匹配），
/// 自动回退到纯 C# 实现，程序不会因为缺一个 dll 就完全不能用。
/// </summary>
public static class NativeCore
{
    private static readonly Lazy<bool> _available = new(TryProbe, LazyThreadSafetyMode.PublicationOnly);

    /// <summary>原生库是否可用</summary>
    public static bool IsAvailable => _available.Value;

    /// <summary>诊断信息：当前用的是原生还是托管实现</summary>
    public static string BackendDescription =>
        IsAvailable ? "Native (Rust)" : "Managed (C# fallback)";

    private static bool TryProbe()
    {
        try
        {
            var ptr = NativeMethods.VersionRaw();
            if (ptr == IntPtr.Zero) return false;
            NativeMethods.FreeString(ptr);
            return true;
        }
        catch (DllNotFoundException)
        {
            return false;
        }
        catch (EntryPointNotFoundException)
        {
            return false;
        }
        catch (BadImageFormatException)
        {
            // 架构不匹配（比如 x86 进程加载了 x64 dll）
            return false;
        }
    }

    /// <summary>获取原生库版本字符串</summary>
    public static string GetVersion()
    {
        if (!IsAvailable) return "n/a";
        var ptr = NativeMethods.VersionRaw();
        if (ptr == IntPtr.Zero) return "n/a";
        try
        {
            return Marshal.PtrToStringUTF8(ptr) ?? "n/a";
        }
        finally
        {
            NativeMethods.FreeString(ptr);
        }
    }

    /// <summary>计算 SHA-256；失败返回 null</summary>
    public static string? Sha256(string path)
    {
        if (!IsAvailable) return FileVerifier.ComputeSha256(path);
        var ptr = NativeMethods.Sha256(path);
        if (ptr == IntPtr.Zero) return null;
        try
        {
            return Marshal.PtrToStringUTF8(ptr);
        }
        finally
        {
            NativeMethods.FreeString(ptr);
        }
    }

    /// <summary>计算 MD5；失败返回 null</summary>
    public static string? Md5(string path)
    {
        if (!IsAvailable) return FileVerifier.ComputeMd5(path);
        var ptr = NativeMethods.Md5(path);
        if (ptr == IntPtr.Zero) return null;
        try
        {
            return Marshal.PtrToStringUTF8(ptr);
        }
        finally
        {
            NativeMethods.FreeString(ptr);
        }
    }

    /// <summary>
    /// 下载单个文件（阻塞调用，请在 Task.Run 里跑）。
    /// </summary>
    /// <exception cref="IOException">下载或校验失败</exception>
    /// <exception cref="OperationCanceledException">被取消</exception>
    public static void Download(string url, string destPath, long expectedSize, string expectedSha256,
        int maxChunks, Action<long>? onBytes, CancellationToken ct)
    {
        if (!IsAvailable)
        {
            // 降级：用托管的 HttpClient 实现，接口语义保持一致
            DownloadManaged(url, destPath, expectedSize, expectedSha256, onBytes, ct).GetAwaiter().GetResult();
            return;
        }

        using var tokenHandle = new CancelTokenHandle();
        NativeMethods.ProgressFn? callback = null;
        if (onBytes != null)
        {
            // 关键：委托必须存活到 native 调用结束，局部变量持有即可（JIT 不会提前回收仍在使用的方法局部）
            callback = (n, _) =>
            {
                if (ct.IsCancellationRequested)
                {
                    return false;   // 通知 Rust 停下
                }
                onBytes(n);
                return true;
            };
        }

        using var reg = ct.Register(() =>
        {
            // Rust 侧周期性检查这个 token，注册回调能立即生效
            tokenHandle.CancelFromAnyThread();
        });

        var code = NativeMethods.DownloadFile(
            url, destPath, expectedSize, expectedSha256 ?? "",
            Math.Max(1, maxChunks), callback, IntPtr.Zero, tokenHandle.DangerousHandle);

        GC.KeepAlive(callback);

        switch (code)
        {
            case NativeMethods.OK:
                return;
            case NativeMethods.ErrCancelled:
                throw new OperationCanceledException(ct);
            case NativeMethods.ErrVerify:
                throw new IOException("文件校验失败（大小或 SHA-256 不匹配）");
            case NativeMethods.ErrNet:
                throw new IOException("网络请求失败");
            case NativeMethods.ErrIo:
                throw new IOException("文件写入失败");
            case NativeMethods.ErrBadArg:
                throw new IOException("参数错误");
            case NativeMethods.ErrPanic:
                throw new IOException("原生核心内部错误");
            default:
                throw new IOException($"未知错误码 {code}");
        }
    }

    /// <summary>托管降级实现（无原生库时使用）</summary>
    private static async Task DownloadManaged(string url, string destPath, long expectedSize,
        string expectedSha256, Action<long>? onBytes, CancellationToken ct)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        Directory.CreateDirectory(Path.GetDirectoryName(destPath) ?? ".");
        var part = destPath + ".part";

        using (var resp = await http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct).ConfigureAwait(false))
        {
            resp.EnsureSuccessStatusCode();
            await using var src = await resp.Content.ReadAsStreamAsync(ct).ConfigureAwait(false);
            await using var dst = new FileStream(part, FileMode.Create, FileAccess.Write, FileShare.None, 81920, true);
            var buf = new byte[81920];
            int n;
            while ((n = await src.ReadAsync(buf, ct).ConfigureAwait(false)) > 0)
            {
                await dst.WriteAsync(buf.AsMemory(0, n), ct).ConfigureAwait(false);
                onBytes?.Invoke(n);
            }
        }

        if (expectedSize > 0 && new FileInfo(part).Length != expectedSize)
        {
            File.Delete(part);
            throw new IOException("文件大小不符");
        }
        if (!string.IsNullOrWhiteSpace(expectedSha256))
        {
            var actual = FileVerifier.ComputeSha256(part);
            if (!string.Equals(actual, expectedSha256, StringComparison.OrdinalIgnoreCase))
            {
                File.Delete(part);
                throw new IOException("SHA-256 校验失败");
            }
        }

        if (File.Exists(destPath)) File.Delete(destPath);
        File.Move(part, destPath);
    }

    /// <summary>取消令牌句柄的安全包装</summary>
    private sealed class CancelTokenHandle : IDisposable
    {
        private IntPtr _token;
        private int _disposed;

        public CancelTokenHandle()
        {
            _token = NativeMethods.CancelTokenNew();
        }

        public IntPtr DangerousHandle => _token;

        /// <summary>可从任意线程调用（Rust 侧是原子写）</summary>
        public void CancelFromAnyThread()
        {
            var t = _token;
            if (t != IntPtr.Zero) NativeMethods.Cancel(t);
        }

        public void Dispose()
        {
            if (Interlocked.Exchange(ref _disposed, 1) != 0) return;
            var t = Interlocked.Exchange(ref _token, IntPtr.Zero);
            if (t != IntPtr.Zero) NativeMethods.CancelTokenFree(t);
        }
    }
}
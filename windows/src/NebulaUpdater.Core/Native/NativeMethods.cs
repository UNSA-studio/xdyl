using System.Runtime.InteropServices;

namespace NebulaUpdater.Core.Native;

/// <summary>
/// Rust 原生核心（nebula_core.dll）的 P/Invoke 包装。
///
/// 设计约束（跨语言边界的铁律）：
///  1. 只调 C ABI，不做任何 C++ 对象传递；
///  2. 所有字符串由调用方 Marshal 成 UTF-8，返回值必须 free（见 SafeString）；
///  3. 异常不跨边界：Rust 侧 catch_unwind，C# 侧 catch DllNotFoundException；
///  4. 回调委托必须保持强引用直到调用结束，否则 GC 会提前回收导致崩溃。
/// </summary>
internal static partial class NativeMethods
{
    private const string Lib = "nebula_core";

    // ---- 错误码（与 lib.rs 严格一致） ----
    public const int OK = 0;
    public const int ErrPanic = -1;
    public const int ErrBadArg = -2;
    public const int ErrIo = -3;
    public const int ErrNet = -4;
    public const int ErrVerify = -5;
    public const int ErrCancelled = -6;

    /// <summary>进度回调。返回 false 表示请求取消。</summary>
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    public delegate bool ProgressFn(long downloaded, IntPtr userData);

    [LibraryImport(Lib, EntryPoint = "nebula_sha256", StringMarshalling = StringMarshalling.Utf8)]
    public static partial IntPtr Sha256(string path);

    [LibraryImport(Lib, EntryPoint = "nebula_md5", StringMarshalling = StringMarshalling.Utf8)]
    public static partial IntPtr Md5(string path);

    [LibraryImport(Lib, EntryPoint = "nebula_free_string")]
    public static partial void FreeString(IntPtr s);

    [LibraryImport(Lib, EntryPoint = "nebula_version")]
    public static partial IntPtr VersionRaw();

    [LibraryImport(Lib, EntryPoint = "nebula_cancel_token_new")]
    public static partial IntPtr CancelTokenNew();

    [LibraryImport(Lib, EntryPoint = "nebula_cancel_token_free")]
    public static partial void CancelTokenFree(IntPtr token);

    [LibraryImport(Lib, EntryPoint = "nebula_cancel")]
    public static partial void Cancel(IntPtr token);

    [LibraryImport(Lib, EntryPoint = "nebula_is_cancelled")]
    [return: MarshalAs(UnmanagedType.U1)]
    public static partial bool IsCancelled(IntPtr token);

    [LibraryImport(Lib, EntryPoint = "nebula_download_file", StringMarshalling = StringMarshalling.Utf8)]
    public static partial int DownloadFile(
        string url,
        string destPath,
        long expectedSize,
        string expectedSha256,
        int maxChunks,
        ProgressFn? progress,
        IntPtr userData,
        IntPtr cancelToken);
}
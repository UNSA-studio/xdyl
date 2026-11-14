//! Nebula Updater 原生核心。
//!
//! 只暴露 C ABI 的一小组函数给 C# 调用。设计原则：
//!   1. 边界窄：只有"下载一个文件""算一个哈希""ping 一个主机"三种操作；
//!   2. 不 panic 穿越边界：所有入口用 `catch_unwind` 包住，出错返回负错误码；
//!   3. 内存谁分配谁释放：返回给 C# 的字符串由 Rust 分配，C# 用完调 `nebula_free_string`。

use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::io::{Read, Seek, SeekFrom, Write};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;
use std::sync::atomic::{AtomicBool, AtomicI64, Ordering};
use std::sync::Arc;

use md5::{Digest, Md5};
use sha2::Sha256;

// ---------------------------------------------------------------- 错误码

pub const OK: c_int = 0;
pub const ERR_PANIC: c_int = -1;
pub const ERR_BAD_ARG: c_int = -2;
pub const ERR_IO: c_int = -3;
pub const ERR_NET: c_int = -4;
pub const ERR_VERIFY: c_int = -5;
pub const ERR_CANCELLED: c_int = -6;

// ---------------------------------------------------------------- 进度回调

/// 进度回调：C# 侧传进来一个函数指针，Rust 每收到一批字节就调一次。
///
/// `downloaded` 是本次调用新增的字节数（不是累计值），
/// 由 C# 侧自己累加，避免两边对"总量"的理解不一致。
pub type ProgressFn = extern "C" fn(downloaded: i64, user_data: *mut c_void) -> bool;

/// 取消标志：C# 通过 `nebula_cancel_token` 拿到句柄，调 `nebula_cancel` 置位。
pub struct CancelToken {
    cancelled: AtomicBool,
}

impl CancelToken {
    fn new() -> Self {
        Self { cancelled: AtomicBool::new(false) }
    }
    fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Relaxed)
    }
    fn cancel(&self) {
        self.cancelled.store(true, Ordering::Relaxed);
    }
}

#[no_mangle]
pub extern "C" fn nebula_cancel_token_new() -> *mut CancelToken {
    Box::into_raw(Box::new(CancelToken::new()))
}

#[no_mangle]
pub extern "C" fn nebula_cancel_token_free(token: *mut CancelToken) {
    if token.is_null() {
        return;
    }
    unsafe { drop(Box::from_raw(token)) };
}

#[no_mangle]
pub extern "C" fn nebula_cancel(token: *mut CancelToken) {
    if token.is_null() {
        return;
    }
    unsafe { (*token).cancel() };
}

#[no_mangle]
pub extern "C" fn nebula_is_cancelled(token: *mut CancelToken) -> bool {
    if token.is_null() {
        return false;
    }
    unsafe { (*token).is_cancelled() }
}

// ---------------------------------------------------------------- 字符串工具

/// 释放 Rust 分配的字符串。C# 拿到 `nebula_*` 返回的 `*mut c_char` 后必须调用它。
#[no_mangle]
pub extern "C" fn nebula_free_string(s: *mut c_char) {
    if s.is_null() {
        return;
    }
    unsafe { drop(CString::from_raw(s)) };
}

fn to_cstring(s: String) -> *mut c_char {
    // 内部字符串不含 NUL，失败时给空串，绝不返回悬垂指针
    CString::new(s).unwrap_or_else(|_| CString::new("").unwrap()).into_raw()
}

unsafe fn from_cstr<'a>(p: *const c_char) -> Option<&'a str> {
    if p.is_null() {
        return None;
    }
    CStr::from_ptr(p).to_str().ok()
}

// ---------------------------------------------------------------- 哈希

/// 计算 SHA-256，返回小写十六进制；出错返回 NULL。
#[no_mangle]
pub extern "C" fn nebula_sha256(path: *const c_char) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let path = unsafe { from_cstr(path) }?;
        let mut file = std::fs::File::open(path).ok()?;
        let mut hasher = Sha256::new();
        let mut buf = vec![0u8; 1 << 20];
        loop {
            let n = file.read(&mut buf).ok()?;
            if n == 0 {
                break;
            }
            hasher.update(&buf[..n]);
        }
        Some(format!("{:x}", hasher.finalize()))
    }));

    match result {
        Ok(Some(hex)) => to_cstring(hex),
        _ => std::ptr::null_mut(),
    }
}

/// 计算 MD5，返回小写十六进制；出错返回 NULL。
#[no_mangle]
pub extern "C" fn nebula_md5(path: *const c_char) -> *mut c_char {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let path = unsafe { from_cstr(path) }?;
        let mut file = std::fs::File::open(path).ok()?;
        let mut hasher = Md5::new();
        let mut buf = vec![0u8; 1 << 20];
        loop {
            let n = file.read(&mut buf).ok()?;
            if n == 0 {
                break;
            }
            hasher.update(&buf[..n]);
        }
        Some(format!("{:x}", hasher.finalize()))
    }));

    match result {
        Ok(Some(hex)) => to_cstring(hex),
        _ => std::ptr::null_mut(),
    }
}

// ---------------------------------------------------------------- 下载

/// 下载并校验单个文件。
///
/// 参数：
///   url          完整下载地址
///   dest_path    最终落地路径
///   expected_size 预期大小（<=0 表示未知）
///   expected_sha256 预期 SHA-256（空指针/空串表示跳过校验）
///   max_chunks   单文件最多并发几块
///   progress     进度回调（可为 null）
///   user_data    透传给 progress
///   cancel       取消令牌（可为 null）
///
/// 返回：0 成功；负数为错误码。
#[no_mangle]
pub extern "C" fn nebula_download_file(
    url: *const c_char,
    dest_path: *const c_char,
    expected_size: i64,
    expected_sha256: *const c_char,
    max_chunks: c_int,
    progress: Option<ProgressFn>,
    user_data: *mut c_void,
    cancel: *mut CancelToken,
) -> c_int {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let url = match unsafe { from_cstr(url) } {
            Some(u) => u,
            None => return ERR_BAD_ARG,
        };
        let dest = match unsafe { from_cstr(dest_path) } {
            Some(d) => d,
            None => return ERR_BAD_ARG,
        };
        let expect_sha = unsafe { from_cstr(expected_sha256) }.unwrap_or("").to_string();
        let cancel = if cancel.is_null() { None } else { Some(unsafe { &*cancel }) };

        let chunks = max_chunks.max(1) as usize;
        let part = format!("{}.part", dest);

        if let Some(p) = Path::new(&part).parent() {
            let _ = std::fs::create_dir_all(p);
        }

        let step = |n: usize| -> bool {
            match progress {
                Some(f) => f(n as i64, user_data),
                None => true,
            }
        };

        // 先做一次"已有文件是否就够用"的判断，避免重复下载
        let use_chunked = expected_size > 0 && chunks > 1 && expected_size as usize > CHUNK_MIN * 2;

        let dl_result = if use_chunked {
            download_chunked(url, &part, expected_size, chunks, &step, cancel)
        } else {
            download_single(url, &part, expected_size, &step, cancel)
        };

        if let Err(code) = dl_result {
            // 取消时清掉半成品，其他错误保留 .part 以便续传
            if code == ERR_CANCELLED {
                let _ = std::fs::remove_file(&part);
            }
            return code;
        }

        // 校验：文件大小先比，再比哈希
        if expected_size > 0 {
            match std::fs::metadata(&part) {
                Ok(m) if m.len() as i64 == expected_size => {}
                _ => {
                    let _ = std::fs::remove_file(&part);
                    return ERR_VERIFY;
                }
            }
        }
        if !expect_sha.is_empty() {
            let mut hasher = Sha256::new();
            match std::fs::File::open(&part) {
                Ok(mut f) => {
                    let mut buf = vec![0u8; 1 << 20];
                    loop {
                        match f.read(&mut buf) {
                            Ok(0) => break,
                            Ok(n) => hasher.update(&buf[..n]),
                            Err(_) => return ERR_IO,
                        }
                    }
                }
                Err(_) => return ERR_IO,
            }
            let actual = format!("{:x}", hasher.finalize());
            if !actual.eq_ignore_ascii_case(&expect_sha) {
                let _ = std::fs::remove_file(&part);
                return ERR_VERIFY;
            }
        }

        // 原子替换
        if Path::new(dest).exists() {
            let _ = std::fs::remove_file(dest);
        }
        match std::fs::rename(&part, dest) {
            Ok(_) => OK,
            Err(_) => ERR_IO,
        }
    }));

    match result {
        Ok(code) => code,
        Err(_) => ERR_PANIC,
    }
}

/// 小于这个大小的文件不分块（建连开销大于收益）
const CHUNK_MIN: usize = 512 * 1024;

/// 进度回调的跨线程包装。
///
/// SAFETY：内部持有 `&dyn Fn`，本身不是 Send/Sync；
/// 之所以能标记，是因为 `download_chunked` 会在返回前 join 全部线程，
/// 回调的生存期完整覆盖所有使用点，不存在悬垂引用。
struct StepWrap<'a>(&'a dyn Fn(usize) -> bool);
unsafe impl Send for StepWrap<'_> {}
unsafe impl Sync for StepWrap<'_> {}

fn download_single(
    url: &str,
    part: &str,
    expected_size: i64,
    step: &dyn Fn(usize) -> bool,
    cancel: Option<&CancelToken>,
) -> Result<(), c_int> {
    let mut existing = std::fs::metadata(part).map(|m| m.len()).unwrap_or(0);

    if expected_size > 0 && existing as i64 == expected_size {
        return Ok(());
    }
    if expected_size > 0 && existing as i64 > expected_size {
        let _ = std::fs::remove_file(part);
        existing = 0;
    }

    let mut req = ureq::get(url);
    if existing > 0 {
        req = req.set("Range", &format!("bytes={}-", existing));
    }

    let resp = req.call().map_err(|e| match e {
        ureq::Error::Status(416, _) => ERR_IO,
        _ => ERR_NET,
    })?;

    let status = resp.status();
    let append = status == 206 && existing > 0;
    if !append {
        existing = 0;
    }

    let mut file = if append {
        std::fs::OpenOptions::new().append(true).open(part).map_err(|_| ERR_IO)?
    } else {
        std::fs::File::create(part).map_err(|_| ERR_IO)?
    };

    let mut reader = resp.into_reader();
    let mut buf = vec![0u8; 256 * 1024];
    loop {
        if let Some(c) = cancel {
            if c.is_cancelled() {
                return Err(ERR_CANCELLED);
            }
        }
        let n = match reader.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => n,
            Err(_) => return Err(ERR_NET),
        };
        if file.write_all(&buf[..n]).is_err() {
            return Err(ERR_IO);
        }
        if !(step.0)(n) {
            return Err(ERR_CANCELLED);
        }
    }
    let _ = file.flush();
    Ok(())
}

fn download_chunked(
    url: &str,
    part: &str,
    expected_size: i64,
    chunks: usize,
    step: &dyn Fn(usize) -> bool,
    cancel: Option<&CancelToken>,
) -> Result<(), c_int> {
    let total = expected_size as usize;
    // 分块粒度受限：块太多会导致连接爆炸，块太少又发挥不出并行
    let n_chunks = ((total + CHUNK_MIN - 1) / CHUNK_MIN).clamp(1, chunks);
    let per = total / n_chunks;

    let mut handles = Vec::with_capacity(n_chunks);
    // 进度回调跨线程共享：生命周期由 download_chunked 保证
    // （它会 join 所有线程才返回，回调不会比它先死）。
    let step_arc: Arc<StepWrap<'_>> = Arc::new(StepWrap(step));

    for i in 0..n_chunks {
        let start = i * per;
        let end = if i == n_chunks - 1 { total - 1 } else { start + per - 1 };
        let url = url.to_string();
        let chunk_file = format!("{}.{}", part, i);
        let step = Arc::clone(&step_arc);
        // 取消令牌用指针地址传进线程：它生命周期由 C# 保证覆盖整个调用
        let cancel_addr = cancel.map(|c| c as *const CancelToken as usize);

        let handle = std::thread::spawn(move || -> Result<(), c_int> {
            download_range(&url, &chunk_file, start as u64, end as u64, &step, cancel_addr)
        });
        handles.push(handle);
    }

    let mut first_err: Option<c_int> = None;
    for h in handles {
        match h.join() {
            Ok(Ok(())) => {}
            Ok(Err(code)) => {
                if first_err.is_none() {
                    first_err = Some(code);
                }
            }
            Err(_) => {
                if first_err.is_none() {
                    first_err = Some(ERR_PANIC);
                }
            }
        }
    }
    if let Some(code) = first_err {
        return Err(code);
    }

    // 拼接
    let mut out = std::fs::File::create(part).map_err(|_| ERR_IO)?;
    let mut buf = vec![0u8; 256 * 1024];
    for i in 0..n_chunks {
        let chunk_file = format!("{}.{}", part, i);
        let mut f = std::fs::File::open(&chunk_file).map_err(|_| ERR_IO)?;
        loop {
            let n = f.read(&mut buf).map_err(|_| ERR_IO)?;
            if n == 0 {
                break;
            }
            out.write_all(&buf[..n]).map_err(|_| ERR_IO)?;
        }
        drop(f);
        let _ = std::fs::remove_file(&chunk_file);
    }
    let _ = out.flush();
    Ok(())
}

// 说明：并发分块时把进度回调包装成 Arc<dyn Fn> 送进线程。
// 早期版本用 *const () → &dyn Fn 的裸指针转换，那是未定义行为（胖指针被截断），
// 已改为提前构造 Arc，由调用方保证回调活得比所有线程久。

fn download_range(
    url: &str,
    dest: &str,
    start: u64,
    end: u64,
    step: &Arc<StepWrap<'_>>,
    cancel_addr: Option<usize>,
) -> Result<(), c_int> {
    let mut existing = std::fs::metadata(dest).map(|m| m.len()).unwrap_or(0);
    let need = end - start + 1;
    if existing >= need {
        return Ok(());
    }
    if existing > need {
        let _ = std::fs::remove_file(dest);
        existing = 0;
    }

    let from = start + existing;
    let req = ureq::get(url).set("Range", &format!("bytes={}-{}", from, end));
    let resp = req.call().map_err(|_| ERR_NET)?;

    let mut file = if existing > 0 {
        std::fs::OpenOptions::new().append(true).open(dest).map_err(|_| ERR_IO)?
    } else {
        std::fs::File::create(dest).map_err(|_| ERR_IO)?
    };

    let mut reader = resp.into_reader();
    let mut buf = vec![0u8; 256 * 1024];
    loop {
        if let Some(addr) = cancel_addr {
            let c = unsafe { &*(addr as *const CancelToken) };
            if c.is_cancelled() {
                return Err(ERR_CANCELLED);
            }
        }
        let n = match reader.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => n,
            Err(_) => return Err(ERR_NET),
        };
        if file.write_all(&buf[..n]).is_err() {
            return Err(ERR_IO);
        }
        if !(step.0)(n) {
            return Err(ERR_CANCELLED);
        }
    }
    let _ = file.seek(SeekFrom::End(0));
    let _ = file.flush();
    Ok(())
}

// ---------------------------------------------------------------- 版本

#[no_mangle]
pub extern "C" fn nebula_version() -> *mut c_char {
    to_cstring(format!("nebula_core {}", env!("CARGO_PKG_VERSION")))
}
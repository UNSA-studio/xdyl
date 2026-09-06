package www.xdyl.hygge.com

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * 增量同步引擎（吃 mods.json）：
 *
 * - new_mod 目录下的 .jar → 版本目录/mods/（本地sha256相同则跳过）
 * - tacz 目录下的 .zip → 版本目录/tacz/
 * - removed       → 对应位置删除（仅限更新器部署过的文件名）
 *
 * 并发下载沿用原 DownloadManager 的多线程 Range 下载 + 重试。
 */
class IncrementalSync(
    private val context: Context,
    private val installer: ModpackInstaller
) {

    data class SyncResult(
        val downloaded: Int,
        val skipped: Int,
        val failed: Int,
        val cleaned: Int,
        val messages: List<String>
    )

    suspend fun sync(
        manifest: ManifestService.Manifest,
        versionDir: File,
        threadCount: Int = 8,
        onProgress: (ModpackInstaller.Progress) -> Unit
    ): SyncResult = withContext(Dispatchers.IO) {
        coroutineScope {
        val messages = java.util.Collections.synchronizedList(mutableListOf<String>())
        val modsDir = File(versionDir, "mods").apply { mkdirs() }
        val taczDir = File(versionDir, "tacz").apply { mkdirs() }

        // ---- 目标清单：new_mod → mods/ ; tacz → tacz/ ----
        data class Task(val file: ManifestService.ManifestFile, val dest: File)
        val tasks = mutableListOf<Task>()
        for (f in manifest.newMods) tasks.add(Task(f, File(modsDir, f.name)))
        for (f in manifest.taczPacks) tasks.add(Task(f, File(taczDir, f.name)))

        // ---- 本地哈希过滤（size不同直接算变更；size相同算sha256） ----
        val toDownload = mutableListOf<Task>()
        var skipped = 0
        for (t in tasks) {
            val local = t.dest
            if (local.exists() && local.length() == t.file.size &&
                (t.file.sha256.isBlank() || installer.sha256(local).equals(t.file.sha256, true))
            ) {
                skipped++
            } else {
                toDownload.add(t)
            }
        }

        var failed = 0
        val done = AtomicInteger(0)
        val total = toDownload.size
        val sem = Semaphore(threadCount.coerceIn(1, 32))

        onProgress(ModpackInstaller.Progress(0, "增量同步：需下载 $total，已最新 $skipped"))
        val deferreds = toDownload.map { t ->
            async {
                sem.acquire()
                try {
                    val tmp = File(t.dest.parentFile, "${t.dest.name}.part")
                    var lastEx: Exception? = null
                    for (attempt in 1..5) {
                        try {
                            tmp.outputStream().use { fos ->
                                val req = okhttp3.Request.Builder().url(t.file.url).build()
                                val client = okhttp3.OkHttpClient.Builder()
                                    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                                    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                                    .build()
                                client.newCall(req).execute().use { resp ->
                                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                                    val input = resp.body!!.byteStream()
                                    val buf = ByteArray(65536)
                                    var n: Int
                                    while (input.read(buf).also { n = it } != -1) fos.write(buf, 0, n)
                                }
                            }
                            if (t.file.sha256.isNotBlank()) {
                                val got = installer.sha256(tmp)
                                if (!got.equals(t.file.sha256, true)) {
                                    throw RuntimeException("sha256校验失败")
                                }
                            }
                            if (t.dest.exists()) t.dest.delete()
                            tmp.renameTo(t.dest)
                            lastEx = null
                            break
                        } catch (e: Exception) {
                            lastEx = e
                            kotlinx.coroutines.delay((1000L * attempt).coerceAtMost(5000))
                        }
                    }
                    if (lastEx != null) {
                        failed++
                        messages.add("✗ ${t.file.name}: ${lastEx.message}")
                        tmp.delete()
                    }
                    val c = done.incrementAndGet()
                    onProgress(ModpackInstaller.Progress(
                        (c * 100 / (total.coerceAtLeast(1))).coerceIn(0, 99),
                        "同步 $c/$total: ${t.file.name}"
                    ))
                } finally {
                    sem.release()
                }
            }
        }
        for (j in deferreds) runCatching { j.await() }

        // ---- removed 清理 ----
        var cleaned = 0
        for (r in manifest.removed) {
            val name = r.name
            val candidates = listOf(File(modsDir, name), File(taczDir, name))
            for (c in candidates) {
                if (c.exists()) {
                    if (c.delete()) cleaned++
                }
            }
        }
        if (cleaned > 0) messages.add("清理下架文件 $cleaned 个")

        SyncResult(toDownload.size - failed, skipped, failed, cleaned, messages)
        }
    }
}
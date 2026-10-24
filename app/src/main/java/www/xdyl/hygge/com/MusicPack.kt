package www.xdyl.hygge.com

import android.content.SharedPreferences
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 每日歌曲：音乐包下载 / 7z 解压 / 每日选歌
 *
 * 音乐包由 VPS 8090 下载专区提供（明文 HTTP，不经 Cloudflare，无 SNI 问题）：
 *   http://160.202.237.166:8090/music_pack.7z
 *
 * 注意：8090 是 VPS 侧 gpusc_web.py（Python SimpleHTTP），v7 起启用鉴权：
 *   - 需要 header：`Authorization: Bearer <music_pack.7z.pass 里的专用口令>`
 *   - 无凭据 / 错口令 → 服务器直接断连（socket close，无任何响应字节）
 *   - Range 断点续传可用（206 + Content-Range）
 * 因此下载务必带 Authorization；全量回退仅作为兜底。
 *
 * 解压目标：/sdcard/NebulaUpdater/music_pack/（公共目录，用户可直接访问）
 */
object MusicPack {

    const val URL = "http://160.202.237.166:8090/music_pack.7z"
    const val ARCHIVE_SIZE = 234171117L

    /** 稀有曲目关键字（概率 0.01%） */
    private const val RARE_KEYWORD = "Daybreak"
    private const val RARE_CHANCE = 0.0001

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun baseDir(): File {
        val dir = File(Environment.getExternalStorageDirectory(), "NebulaUpdater")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun rootDir(): File {
        val dir = File(baseDir(), "music_pack")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun archiveFile(): File = File(baseDir(), "music_pack.7z")

    fun songs(): List<File> {
        val list = rootDir().listFiles { f -> f.isFile && f.name.lowercase().endsWith(".mp3") }
            ?.toList() ?: emptyList()
        return list.sortedBy { it.name.lowercase() }
    }

    fun isReady(): Boolean = songs().isNotEmpty()

    fun sidecar(song: File): File =
        File(song.parentFile, song.nameWithoutExtension + ".lrc")

    /** 下载音乐包：优先 4 线程分块并发，失败回退单线程。onProgress(已下载字节, 总字节) */
    suspend fun download(onProgress: (Long, Long) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val target = archiveFile()
        val total = remoteSize()
        if (target.exists() && target.length() == total) return@withContext true
        // 分块写入要求整块偏移对齐，因此每次都从头开始
        if (target.exists()) target.delete()
        try {
            LogManager.log("[SONG] 多线程下载开始 ${THREADS} 线程 / $total 字节")
            if (multiThreadFetch(target, total, onProgress)) {
                LogManager.log("[SONG] 多线程下载完成 $total 字节")
                return@withContext true
            }
        } catch (e: Exception) {
            LogManager.log("[SONG] 多线程下载失败: " + e.message + "，回退单线程")
        }
        target.delete()
        runCatching { fetch(target, 0L, onProgress) }.getOrDefault(false)
    }

    /** 音乐包专用下载口令（仅对 music_pack.7z 生效，由 VPS 侧 music_pack.7z.pass 提供） */
    private const val PACK_PASS = "nupd-music-9f3a"

    /** 分块下载线程数 */
    private const val THREADS = 4

    /** 探测服务端文件真实大小（Range 0-0 取 Content-Range 总长），失败回退常量 */
    private fun remoteSize(): Long {
        return try {
            val req = Request.Builder().url(URL)
                .header("Authorization", "Bearer $PACK_PASS")
                .header("Range", "bytes=0-0")
                .build()
            http.newCall(req).execute().use { resp ->
                val cr = resp.header("Content-Range") ?: return@use ARCHIVE_SIZE
                val total = cr.substringAfterLast('/').trim().toLongOrNull()
                if (total != null && total > 0) total else ARCHIVE_SIZE
            }
        } catch (e: Exception) {
            ARCHIVE_SIZE
        }
    }

    /** 多线程分块并发下载，每块 Range 独立写入同一文件的对应偏移 */
    private fun multiThreadFetch(target: File, total: Long, onProgress: (Long, Long) -> Unit): Boolean {
        val chunk = (total + THREADS - 1) / THREADS
        val progress = java.util.concurrent.atomic.AtomicLongArray(THREADS)
        fun totalDone(): Long {
            var s = 0L
            for (k in 0 until THREADS) s += progress.get(k)
            return s
        }
        val errors = java.util.Collections.synchronizedList(mutableListOf<java.lang.Exception>())
        val latch = java.util.concurrent.CountDownLatch(THREADS)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(THREADS) { r ->
            Thread(r, "song-dl").apply { isDaemon = true }
        }
        RandomAccessFile(target, "rw").use { it.setLength(total) }

        for (i in 0 until THREADS) {
            val start = i * chunk
            if (start >= total) {
                latch.countDown()
                continue
            }
            val end = minOf(total - 1, start + chunk - 1)
            pool.execute {
                try {
                    val req = Request.Builder().url(URL)
                        .header("Authorization", "Bearer $PACK_PASS")
                        .header("Range", "bytes=$start-$end")
                        .build()
                    http.newCall(req).execute().use { resp ->
                        if (resp.code != 206) {
                            throw java.io.IOException("块 $i HTTP " + resp.code)
                        }
                        val body = resp.body ?: throw java.io.IOException("块 $i 无响应体")
                        RandomAccessFile(target, "rw").use { w ->
                            w.seek(start)
                            var pos = start
                            body.byteStream().use { input ->
                                val buf = ByteArray(128 * 1024)
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    w.write(buf, 0, n)
                                    pos += n
                                    progress.set(i, pos - start)
                                    onProgress(totalDone(), total)
                                }
                            }
                            val got = pos - start
                            if (got < end - start + 1) throw java.io.IOException("块 $i 仅 $got 字节")
                        }
                    }
                } catch (e: Exception) {
                    errors.add(e)
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await(20, TimeUnit.MINUTES)
        pool.shutdown()
        if (errors.isNotEmpty()) throw java.io.IOException("分块下载失败: " + errors.first().message)
        return target.length() == total
    }

    /** 单次连接拉取。start>0 表示从 start 处续传；服务端回 200 时自动改为全量重写 */
    private fun fetch(target: File, start: Long, onProgress: (Long, Long) -> Unit): Boolean {
        val builder = Request.Builder()
            .url(URL)
            .header("Authorization", "Bearer $PACK_PASS")
        if (start > 0) builder.header("Range", "bytes=$start-")
        return http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                LogManager.log("[SONG] 音乐包下载失败 HTTP " + resp.code)
                return false
            }
            val body = resp.body ?: return false
            // 只有 206 才代表服务端真的按 Range 续传；200 说明要全量重写
            val resume = resp.code == 206 && start > 0
            val total = ARCHIVE_SIZE
            var done = if (resume) start else 0L
            RandomAccessFile(target, "rw").use { raf ->
                if (resume) raf.seek(start) else raf.setLength(0)
                body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            LogManager.log("[SONG] 音乐包下载结束 " + done + " / " + total)
            if (done < ARCHIVE_SIZE * 99 / 100) throw IllegalStateException("incomplete $done/$total")
            true
        }
    }

    /** 解压 7z 中的 mp3 / lrc 到公共目录。onProgress(已解压文件数) */
    fun extract(onProgress: ((Int) -> Unit)? = null): Boolean {
        val archive = archiveFile()
        if (!archive.exists()) return false
        val outRoot = rootDir()
        return try {
            val sevenZ = org.apache.commons.compress.archivers.sevenz.SevenZFile.builder()
                .setFile(archive).get()
            sevenZ.use { sz ->
                var count = 0
                while (true) {
                    val entry = sz.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/')
                    if (name.isBlank()) continue
                    val lower = name.lowercase()
                    if (!lower.endsWith(".mp3") && !lower.endsWith(".lrc")) continue
                    val out = File(outRoot, name)
                    out.outputStream().buffered().use { os ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = sz.read(buf)
                            if (n <= 0) break
                            os.write(buf, 0, n)
                        }
                    }
                    count++
                    onProgress?.invoke(count)
                }
                LogManager.log("[SONG] 解压完成，共 " + count + " 个文件")
            }
            true
        } catch (e: Exception) {
            LogManager.log("[SONG] 解压失败: " + e.message)
            false
        }
    }

    /** 从曲库中抽一首：Daybreak 概率 0.01%，其余均分 99.99% */
    fun pickDaily(all: List<File>): File? {
        if (all.isEmpty()) return null
        val rare = all.firstOrNull { it.name.contains(RARE_KEYWORD, true) }
        if (rare != null && Random.nextDouble() < RARE_CHANCE) return rare
        val rest = all.filter { it != rare }
        return if (rest.isEmpty()) rare else rest.random()
    }

    /** 今日歌曲（按日期缓存；同一天固定不变） */
    fun dailySong(prefs: SharedPreferences): File? {
        val all = songs()
        if (all.isEmpty()) return null
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val savedDate = prefs.getString("song_date", "")
        val savedName = prefs.getString("song_name", "")
        if (savedDate == today && !savedName.isNullOrBlank()) {
            val f = File(rootDir(), savedName)
            if (f.exists()) return f
        }
        val picked = pickDaily(all) ?: return null
        prefs.edit().putString("song_date", today).putString("song_name", picked.name).apply()
        LogManager.log("[SONG] 今日歌曲: " + picked.name)
        return picked
    }
}

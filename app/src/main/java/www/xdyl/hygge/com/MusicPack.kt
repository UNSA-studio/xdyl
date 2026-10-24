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
 * 注意：8090 是 Python SimpleHTTP 服务，**不支持 Range**（带 Range 头会直接断连），
 * 因此下载逻辑必须先尝试续传，失败则自动回退为全量下载。
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

    /** 下载音乐包（尽力续传；服务器不支持 Range 时自动回退全量）。onProgress(已下载字节, 总字节) */
    suspend fun download(onProgress: (Long, Long) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val target = archiveFile()
        var existing = if (target.exists()) target.length() else 0L
        if (existing == ARCHIVE_SIZE) return@withContext true
        if (existing > ARCHIVE_SIZE) {
            target.delete()
            existing = 0L
        }
        // 8090 是 Python SimpleHTTP，收到 Range 头会直接断连，所以先试续传，失败就删档重下
        if (existing > 0) {
            val resumed = runCatching { fetch(target, existing, onProgress) }.getOrDefault(false)
            if (resumed) return@withContext true
            LogManager.log("[SONG] 续传失败（服务端不支持 Range），改为全量下载")
            target.delete()
        }
        runCatching { fetch(target, 0L, onProgress) }.getOrDefault(false)
    }

    /** 单次连接拉取。start>0 表示从 start 处续传；服务端回 200 时自动改为全量重写 */
    private fun fetch(target: File, start: Long, onProgress: (Long, Long) -> Unit): Boolean {
        val builder = Request.Builder().url(URL)
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

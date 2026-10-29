package www.xdyl.hygge.com

import android.content.SharedPreferences
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
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
    const val ARCHIVE_SIZE = 234163017L

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

    @Synchronized fun rootDir(): File {
        val dir = File(baseDir(), "music_pack")
        if (!dir.exists()) {
            baseDir().listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(".music-backup-") }
                .maxByOrNull { it.lastModified() }?.renameTo(dir)
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun archiveFile(): File = File(baseDir(), "music_pack.7z")

    fun songs(): List<File> {
        val list = rootDir().listFiles { f -> f.isFile && f.name.lowercase().endsWith(".mp3") }
            ?.toList() ?: emptyList()
        return list.sortedBy { it.name.lowercase() }
    }

    fun isReady(): Boolean {
        val root = rootDir()
        return if (File(root, MARKER).isFile) manifestValid(root) else adoptExisting(root)
    }

    fun sidecar(song: File): File =
        File(song.parentFile, song.nameWithoutExtension + ".lrc")

    private const val PACK_PASS = "nupd-music-9f3a"
    private const val THREADS = 8
    private const val ARCHIVE_SHA256 = "e5491bbb408161f88e9423014d3b30ba5864428f96be264d732f93e96c88a451"
    private const val MARKER = ".installed.json"
    private val installMutex = kotlinx.coroutines.sync.Mutex()
    val isInstalling: Boolean get() = installMutex.isLocked

    private fun verifiedArchive(file: File): Boolean = file.isFile && file.length() == ARCHIVE_SIZE &&
        FileSafety.sha256(file).equals(ARCHIVE_SHA256, true)

    /** A single installation owns download + extraction. Force reinstall retains the live pack until success. */
    suspend fun install(force: Boolean = false, onProgress: (Long, Long) -> Unit,
                        onExtract: () -> Unit = {}): Boolean {
        installMutex.lock()
        try {
            return withContext(Dispatchers.IO) {
                val archive = archiveFile()
                val part = File(baseDir(), "music_pack.7z.part")
                try {
                    if (force || !verifiedArchive(archive)) {
                        DownloadManager(URL, ARCHIVE_SIZE, THREADS, true,
                            mapOf("Authorization" to "Bearer $PACK_PASS"))
                            .downloadBytes(part) { done, total -> onProgress(done, total) }
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        check(verifiedArchive(part)) { "音乐包 SHA-256 校验失败" }
                        FileSafety.publish(part, archive)
                    } else onProgress(ARCHIVE_SIZE, ARCHIVE_SIZE)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    onExtract()
                    extractVerified(archive)
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    LogManager.log("[SONG] 安装失败（保留现有资源）: ${e.message}")
                    false
                } finally { part.delete() }
            }
        } finally { installMutex.unlock() }
    }

    private fun manifestValid(root: File): Boolean = try {
        val marker = org.json.JSONObject(File(root, MARKER).readText())
        if (marker.optString("archive_sha256") != ARCHIVE_SHA256) false else {
            val files = marker.getJSONArray("files")
            files.length() > 0 && (0 until files.length()).all { i ->
                val f = files.getJSONObject(i)
                val name = FileSafety.checkedName(f.getString("name"))
                val file = File(root, name)
                file.isFile && file.length() == f.getLong("size")
            }
        }
    } catch (_: Exception) { false }

    private fun writeManifest(root: File, entries: List<Pair<String, Long>>) {
        val files = org.json.JSONArray()
        entries.forEach { (name, size) -> files.put(org.json.JSONObject().put("name", name).put("size", size)) }
        FileSafety.writeAtomic(File(root, MARKER), org.json.JSONObject()
            .put("archive_sha256", ARCHIVE_SHA256).put("files", files).toString())
    }

    /** Existing complete installations can be adopted without deleting or redownloading user resources. */
    private fun adoptExisting(root: File): Boolean = try {
        val archive = archiveFile()
        if (!archive.isFile || archive.length() != ARCHIVE_SIZE) false else {
            val entries = org.apache.commons.compress.archivers.sevenz.SevenZFile.builder().setFile(archive).get().use { sz ->
                sz.entries.filter { !it.isDirectory && (it.name.endsWith(".mp3", true) || it.name.endsWith(".lrc", true)) }
                    .map { FileSafety.checkedName(it.name.substringAfterLast('/')) to it.size }
            }
            if (entries.isEmpty() || entries.none { it.first.endsWith(".mp3", true) } ||
                entries.any { !File(root, it.first).isFile || File(root, it.first).length() != it.second }) false
            else { writeManifest(root, entries); true }
        }
    } catch (_: Exception) { false }

    private suspend fun extractVerified(archive: File): Boolean {
        val root = rootDir()
        val staging = File(baseDir(), ".music-install-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val backup = File(baseDir(), ".music-backup-${java.util.UUID.randomUUID()}")
        var retainBackup = false
        try {
            val entries = mutableListOf<Pair<String, Long>>()
            org.apache.commons.compress.archivers.sevenz.SevenZFile.builder().setFile(archive).get().use { sz ->
                while (true) {
                    val entry = sz.nextEntry ?: break
                    if (entry.isDirectory) continue
                    val name = FileSafety.checkedName(entry.name.substringAfterLast('/'))
                    if (!name.endsWith(".mp3", true) && !name.endsWith(".lrc", true)) continue
                    check(entries.none { it.first == name }) { "Duplicate archive entry" }
                    val out = File(staging, name)
                    out.outputStream().buffered().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val n = sz.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                        }
                    }
                    check(out.length() == entry.size) { "Incomplete extracted file: $name" }
                    entries.add(name to entry.size)
                }
            }
            check(entries.any { it.first.endsWith(".mp3", true) }) { "Empty music pack" }
            writeManifest(staging, entries)
            check(manifestValid(staging)) { "Invalid installed manifest" }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            synchronized(this) {
                if (root.exists()) check(root.renameTo(backup)) { "Cannot back up current music" }
                if (!staging.renameTo(root)) {
                    retainBackup = !backup.renameTo(root)
                    throw java.io.IOException("Cannot publish music; backup: ${backup.absolutePath}")
                }
            }
            backup.deleteRecursively()
            return true
        } finally {
            staging.deleteRecursively()
            if (!retainBackup && root.exists()) backup.deleteRecursively()
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

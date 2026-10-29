package www.xdyl.hygge.com

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 头像加载器。
 *
 * 服务端 `/user/profile`、排行榜、帖子里的 `avatar` 字段是**文件名**，
 * iOS 端文档明确：解析为 `https://login.lanternwaves.fun/user/avatar/{filename}`。
 * （已实测：带扩展名的地址返回 200 image/jpeg，不带扩展名是 404）
 *
 * 带内存缓存 + 圆形裁剪，失败时回调 false 让上层回退到“首字母”。
 */
object AvatarLoader {
    private val cacheLock = Any()
    private val cacheGeneration = java.util.concurrent.atomic.AtomicLong(0)

    fun clearAll(context: android.content.Context): Long = synchronized(cacheLock) {
        cacheGeneration.incrementAndGet()
        cache.evictAll()
        clearDisk(context)
    }


    private const val BASE = "https://login.lanternwaves.fun/user/avatar/"

    private val cache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val pool = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "avatar-io").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())

    /** avatar 可能是文件名，也可能已经是完整 URL */
    fun urlOf(avatar: String?): String? {
        val a = avatar?.trim().orEmpty()
        if (a.isEmpty() || a == "null" || a == "none") return null
        if (a.startsWith("http://", true) || a.startsWith("https://", true)) return a
        return BASE + a
    }

    /** 磁盘缓存：进程被杀 / 重启后不用重新下载 */
    private fun diskFile(context: android.content.Context, url: String): File {
        val dir = File(context.cacheDir, "avatars")
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, Integer.toHexString(url.hashCode()) + ".img")
    }

    /** 加载到 ImageView；成功回调 true，失败回调 false */
    fun load(iv: ImageView, avatar: String?, onDone: ((Boolean) -> Unit)? = null) {
        val url = urlOf(avatar)
        iv.tag = url
        val generation = cacheGeneration.get()
        if (url == null) {
            main.post { if (iv.tag == url) onDone?.invoke(false) }
            return
        }
        val cached = cache.get(url)
        if (cached != null) {
            iv.setImageDrawable(circle(iv, cached))
            onDone?.invoke(true)
            return
        }
        val ctx = iv.context
        pool.execute {
            // ① 磁盘命中：直接解码，不发请求
            try {
                val disk = diskFile(ctx, url)
                if (disk.isFile) {
                    val cachedFile = BitmapFactory.decodeFile(disk.absolutePath)
                    if (cachedFile != null) {
                        synchronized(cacheLock) { if (generation == cacheGeneration.get()) cache.put(url, cachedFile) }
                        main.post {
                            if (iv.tag == url) {
                                iv.setImageDrawable(circle(iv, cachedFile))
                                onDone?.invoke(true)
                            }
                        }
                        return@execute
                    }
                }
            } catch (e: Throwable) {
            }
            // ② 网络下载
            try {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        LogManager.w("[AVATAR] $url -> ${resp.code}")
                        main.post { if (iv.tag == url) onDone?.invoke(false) }
                        return@execute
                    }
                    val bytes = resp.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        main.post { if (iv.tag == url) onDone?.invoke(false) }
                        return@execute
                    }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp == null) {
                        LogManager.w("[AVATAR] 解码失败 $url")
                        main.post { if (iv.tag == url) onDone?.invoke(false) }
                        return@execute
                    }
                    synchronized(cacheLock) { if (generation == cacheGeneration.get()) cache.put(url, bmp) }
                    try {
                        synchronized(cacheLock) {
                            if (generation == cacheGeneration.get()) diskFile(ctx, url).writeBytes(bytes)
                        }
                    } catch (e: Throwable) {
                    }
                    main.post {
                        if (iv.tag == url) {
                            iv.setImageDrawable(circle(iv, bmp))
                            onDone?.invoke(true)
                        }
                    }
                }
            } catch (e: Exception) {
                LogManager.w("[AVATAR] 下载失败 ${e.message}")
                main.post { if (iv.tag == url) onDone?.invoke(false) }
            }
        }
    }

    private fun circle(iv: ImageView, bmp: Bitmap) =
        RoundedBitmapDrawableFactory.create(iv.resources, bmp).apply { isCircular = true }

    /** 清空内存缓存（清理全局缓存时调用） */
    fun clearMemory() {
        cache.evictAll()
    }

    /** 删除全部头像磁盘缓存，返回释放字节数 */
    fun clearDisk(context: android.content.Context): Long {
        val dir = File(context.cacheDir, "avatars")
        var freed = 0L
        dir.listFiles()?.forEach { f ->
            val bytes = f.length()
            if (f.delete()) freed += bytes
        }
        return freed
    }
}

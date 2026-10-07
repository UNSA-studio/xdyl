package www.xdyl.hygge.com

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 极简图片加载器（OkHttp + 内存 LRU + 磁盘缓存）。
 *
 * 缓存策略：
 *  1) 内存 LRU 命中 → 直接显示（最快）
 *  2) 磁盘缓存命中（以 URL 的 sha256 命名） → 解码显示，不再走网络
 *  3) 都没有 → 下载 → 写入磁盘缓存 → 显示
 *
 * 下次相同 URL 直接吃本地缓存，解决"加载挺慢"的问题。
 */
object SimpleImageLoader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cache = object : android.util.LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 磁盘缓存目录（App cacheDir 下，系统可自动清理） */
    private fun diskDir(context: Context): File =
        File(context.cacheDir, "imgcache").apply { mkdirs() }

    /** 以 URL 的 sha256 作为磁盘文件名（与"资源按 sha 复用"同一思路） */
    private fun diskFile(context: Context, url: String): File {
        val sha = try {
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }
        } catch (e: Throwable) {
            url.hashCode().toString().padStart(8, '0')
        }
        return File(diskDir(context), sha)
    }

    /** 异步加载图片到 ImageView（tag 校验防止复用错位） */
    fun load(context: Context, url: String, imageView: ImageView) {
        if (url.isBlank()) return
        imageView.tag = url

        // 1) 内存缓存
        cache.get(url)?.let {
            imageView.setImageBitmap(it)
            return
        }

        executor.execute {
            try {
                // 2) 磁盘缓存
                val file = diskFile(context, url)
                if (file.exists() && file.length() > 0) {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    if (bmp != null) {
                        cache.put(url, bmp)
                        mainHandler.post {
                            if (imageView.tag == url) imageView.setImageBitmap(bmp)
                        }
                        return@execute
                    }
                    // 缓存损坏：删除后走网络
                    file.delete()
                }

                // 3) 网络下载
                val req = Request.Builder().url(url)
                    .header("User-Agent", "NebulaUpdater-Android/1.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@execute
                    val bytes = resp.body?.bytes() ?: return@execute
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
                    // 写入磁盘缓存
                    try {
                        val tmp = File(file.parentFile, file.name + ".part")
                        tmp.writeBytes(bytes)
                        if (file.exists()) file.delete()
                        tmp.renameTo(file)
                    } catch (e: Throwable) {
                        // 缓存写入失败不影响显示
                    }
                    cache.put(url, bmp)
                    mainHandler.post {
                        if (imageView.tag == url) imageView.setImageBitmap(bmp)
                    }
                }
            } catch (e: Exception) {
                // 加载失败静默
            }
        }
    }
}
package www.xdyl.hygge.com

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.droidsonroids.gif.GifDrawable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 图片加载器（OkHttp + 内存 LRU + 磁盘缓存）。
 *
 * 解码策略：
 *  - **GIF**：使用第三方库 android-gif-drawable 的 [GifDrawable]（自带 native 解码器），
 *    刻意**不用**系统 AnimatedImageDrawable —— 它在部分 GIF 上会在渲染线程
 *    native crash（libhwui: AnimatedImageDrawable::decodeNextFrame），无法捕获。
 *  - 静态图：BitmapFactory。
 *
 * 其它：三级缓存（内存->磁盘->网络）、磁盘缓存自愈、失败保持占位。
 */
object SimpleImageLoader {

    private val client = OkHttpClient.Builder()
        .dns(NetDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 静态图内存缓存（按条目数限制） */
    private val cache = object : LruCache<String, Drawable>(80) {
        override fun sizeOf(key: String, value: Drawable): Int = 1
    }

    /**
     * GIF 专用缓存：GifDrawable 持有 native 内存、需要 recycle，
     * 若参与 LRU 淘汰可能被回收掉仍在显示的实例，所以单独存放、不淘汰。
     * 同一个 GIF 只构造一次。
     */
    private val gifCache = ConcurrentHashMap<String, GifDrawable>()

    private fun diskDir(context: Context): File =
        File(context.cacheDir, "imgcache").apply { mkdirs() }

    private fun diskFile(context: Context, url: String): File {
        val sha = try {
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }
        } catch (e: Throwable) {
            url.hashCode().toString().padStart(8, '0')
        }
        return File(diskDir(context), sha)
    }

    private fun isGif(url: String, data: ByteArray?): Boolean {
        if (url.lowercase().contains(".gif")) return true
        if (data != null && data.size >= 3) {
            return data[0] == 'G'.code.toByte() &&
                    data[1] == 'I'.code.toByte() &&
                    data[2] == 'F'.code.toByte()
        }
        return false
    }

    /** 移除某个 URL 的内存 + 磁盘缓存 */
    fun removeCache(context: Context, url: String) {
        cache.remove(url)
        gifCache.remove(url)?.let { runCatching { it.recycle() } }
        runCatching { diskFile(context, url).delete() }
    }

    /** 异步加载图片（GIF 会动） */
    fun load(context: Context, url: String, imageView: ImageView) {
        if (url.isBlank()) return
        imageView.tag = url

        // 0) GIF 缓存（同一对象复用，动画状态连续）
        gifCache[url]?.let {
            imageView.setImageDrawable(it)
            return
        }
        // 1) 静态图内存缓存
        cache.get(url)?.let {
            imageView.setImageDrawable(it)
            return
        }

        executor.execute {
            try {
                val file = diskFile(context, url)

                // 2) 磁盘缓存
                var bytes: ByteArray? = null
                if (file.exists() && file.length() > 0) {
                    bytes = runCatching { file.readBytes() }.getOrNull()
                    if (bytes == null || bytes.isEmpty()) {
                        file.delete()
                        bytes = null
                    }
                }

                // 3) 网络
                if (bytes == null) {
                    val req = Request.Builder().url(url)
                        .header("User-Agent", "NebulaUpdater-Android/1.0")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@execute
                        bytes = resp.body?.bytes() ?: return@execute
                    }
                    val d = bytes ?: return@execute
                    runCatching {
                        val tmp = File(file.parentFile, file.name + ".part")
                        tmp.writeBytes(d)
                        if (file.exists()) file.delete()
                        tmp.renameTo(file)
                    }
                }

                val data = bytes ?: return@execute

                // 4) 解码
                var drawable: Drawable? = null
                if (isGif(url, data)) {
                    val gif = runCatching { GifDrawable(data) }.getOrNull()
                    if (gif != null) {
                        gif.loopCount = 0   // 无限循环
                        runCatching { gif.start() }
                        drawable = gif
                        gifCache[url] = gif
                    }
                }
                if (drawable == null) {
                    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (bmp != null) {
                        drawable = BitmapDrawable(context.resources, bmp)
                        cache.put(url, drawable!!)
                    }
                }
                val result = drawable ?: return@execute

                mainHandler.post {
                    if (imageView.tag == url) imageView.setImageDrawable(result)
                }
            } catch (e: Exception) {
                // 加载失败：保持占位背景，不残留错图
            }
        }
    }
}
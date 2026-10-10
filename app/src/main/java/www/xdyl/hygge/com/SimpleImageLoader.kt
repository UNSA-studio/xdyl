package www.xdyl.hygge.com

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 图片加载器（OkHttp + 内存 LRU + 磁盘缓存）。
 *
 * 特性：
 *  - **支持 GIF 动图**（API 28+ 用 ImageDecoder 解码为 AnimatedImageDrawable 并播放）
 *  - 三级缓存：内存 -> 磁盘（URL 的 sha256 命名）-> 网络
 *  - 磁盘缓存损坏自动删除并重新下载
 *  - 加载失败静默保持占位（不崩溃、不残留错图）
 */
object SimpleImageLoader {

    private val client = OkHttpClient.Builder()
        .dns(NetDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 内存缓存：按条目数限制（Drawable 不便统计字节数） */
    private val cache = object : LruCache<String, Drawable>(80) {
        override fun sizeOf(key: String, value: Drawable): Int = 1
    }

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

    /** 移除某个 URL 的内存 + 磁盘缓存 */
    fun removeCache(context: Context, url: String) {
        cache.remove(url)
        try {
            diskFile(context, url).delete()
        } catch (e: Throwable) {
            // 忽略
        }
    }

    /** 异步加载图片（支持 GIF 动图） */
    fun load(context: Context, url: String, imageView: ImageView) {
        if (url.isBlank()) return
        imageView.tag = url

        // 1) 内存缓存
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

                // 3) 网络下载
                if (bytes == null) {
                    val req = Request.Builder().url(url)
                        .header("User-Agent", "NebulaUpdater-Android/1.0")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@execute
                        bytes = resp.body?.bytes() ?: return@execute
                    }
                    val data = bytes ?: return@execute
                    // 写入磁盘缓存
                    runCatching {
                        val tmp = File(file.parentFile, file.name + ".part")
                        tmp.writeBytes(data)
                        if (file.exists()) file.delete()
                        tmp.renameTo(file)
                    }
                }

                val data = bytes ?: return@execute

                // 4) 解码：优先 ImageDecoder（GIF 可动图），回退 BitmapFactory
                var drawable: Drawable? = null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    drawable = runCatching {
                        val source = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(data))
                        ImageDecoder.decodeDrawable(source).also {
                            if (it is AnimatedImageDrawable) it.start()
                        }
                    }.getOrNull()
                }
                if (drawable == null) {
                    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (bmp != null) drawable = BitmapDrawable(context.resources, bmp)
                }
                val result = drawable ?: return@execute

                cache.put(url, result)
                mainHandler.post {
                    if (imageView.tag == url) imageView.setImageDrawable(result)
                }
            } catch (e: Exception) {
                // 加载失败：保持占位背景，不残留错图
            }
        }
    }
}
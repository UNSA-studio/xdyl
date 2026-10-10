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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 图片加载器（OkHttp + 内存 LRU + 磁盘缓存）。
 *
 * 解码策略：
 *  - **GIF**：android-gif-drawable 的 [GifDrawable]（自带 native 解码器）。
 *    刻意不用系统 AnimatedImageDrawable —— 它在部分 GIF 上会 native crash。
 *  - 静态图：BitmapFactory。
 *
 * GIF 实例策略（重要）：
 *  每个 ImageView **各持一个独立 GifDrawable**，不跨 View 共享。
 *  原因：共享实例时，任一 View 被销毁都会触发 drawable.setVisible(false)，
 *  把其它 View 的动画一起停掉（例如关闭详情后列表里的 GIF 变静态）。
 *  旧实例在换图时 recycle，避免 native 内存泄漏。
 */
object SimpleImageLoader {

    private val client = OkHttpClient.Builder()
        .dns(NetDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 静态图内存缓存（按条目数限制；GIF 不在此缓存） */
    private val cache = object : LruCache<String, Drawable>(80) {
        override fun sizeOf(key: String, value: Drawable): Int = 1
    }

    /**
     * GIF 原始字节内存缓存（4MB）。
     * 避免每次打开详情都重新读磁盘/下载并解码，明显加快弹窗加载。
     */
    private val gifBytesCache = object : LruCache<String, ByteArray>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
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

    private fun isGif(url: String, data: ByteArray?): Boolean {
        if (url.lowercase().contains(".gif")) return true
        if (data != null && data.size >= 3) {
            return data[0] == 'G'.code.toByte() &&
                    data[1] == 'I'.code.toByte() &&
                    data[2] == 'F'.code.toByte()
        }
        return false
    }

    /** 移除某个 URL 的缓存（内存 + 磁盘） */
    fun removeCache(context: Context, url: String) {
        cache.remove(url)
        runCatching { diskFile(context, url).delete() }
    }

    /** 异步加载图片（GIF 会动） */
    fun load(context: Context, url: String, imageView: ImageView) {
        if (url.isBlank()) return

        // 这个 View 已经显示同一张 GIF：保持播放，什么都不做
        if (imageView.tag == url && imageView.drawable is GifDrawable) return
        // 同一个 View 已经显示同一张静态图：无需重设
        if (imageView.tag == url && imageView.drawable != null && !isGifUrl(url)) return

        // 换图：回收本 View 的旧 GIF（独立实例，回收安全），避免 native 内存泄漏
        if (imageView.tag != null && imageView.tag != url) {
            (imageView.drawable as? GifDrawable)?.let { old ->
                runCatching { old.setVisible(false, false) }
                runCatching { old.recycle() }
            }
            imageView.setImageDrawable(null)
        }
        imageView.tag = url

        // 静态图内存缓存命中
        if (!isGifUrl(url)) {
            cache.get(url)?.let {
                imageView.setImageDrawable(it)
                return
            }
        }

        // GIF 字节缓存命中：跳过磁盘/网络，直接解码（明显加快详情弹窗）
        if (isGifUrl(url)) {
            val cachedBytes = gifBytesCache.get(url)
            if (cachedBytes != null) {
                executor.execute {
                    val gif = runCatching { GifDrawable(cachedBytes) }.getOrNull() ?: return@execute
                    gif.loopCount = 0
                    mainHandler.post {
                        if (imageView.tag == url) {
                            imageView.setImageDrawable(gif)
                            runCatching { gif.setVisible(true, true) }
                            runCatching { gif.start() }
                        } else {
                            runCatching { gif.recycle() }
                        }
                    }
                }
                return
            }
        }

        executor.execute {
            try {
                val file = diskFile(context, url)

                // 磁盘缓存
                var bytes: ByteArray? = null
                if (file.exists() && file.length() > 0) {
                    bytes = runCatching { file.readBytes() }.getOrNull()
                    if (bytes == null || bytes.isEmpty()) {
                        file.delete()
                        bytes = null
                    }
                }

                // 网络
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

                // 解码
                var drawable: Drawable? = null
                if (isGif(url, data)) {
                    // 字节入内存缓存：下次打开直接解码（免磁盘 IO）
                    gifBytesCache.put(url, data)
                    // 每个 View 独立实例（不复用缓存，避免 setVisible 互相干扰）
                    val gif = runCatching { GifDrawable(data) }.getOrNull()
                    if (gif != null) {
                        gif.loopCount = 0
                        drawable = gif
                    }
                }
                if (drawable == null) {
                    val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (bmp != null) {
                        drawable = BitmapDrawable(context.resources, bmp)
                        cache.put(url, drawable)
                    }
                }
                val result = drawable ?: return@execute

                mainHandler.post {
                    if (imageView.tag == url) {
                        imageView.setImageDrawable(result)
                        if (result is GifDrawable) {
                            runCatching { result.setVisible(true, true) }
                            runCatching { result.start() }
                        }
                    } else if (result is GifDrawable) {
                        // 结果已过期（View 已换图）：回收，避免泄漏
                        runCatching { result.recycle() }
                    }
                }
            } catch (e: Exception) {
                // 加载失败：保持占位背景
            }
        }
    }

    private fun isGifUrl(url: String) = url.lowercase().contains(".gif")
}
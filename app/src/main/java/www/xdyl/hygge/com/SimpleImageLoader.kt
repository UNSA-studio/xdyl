package www.xdyl.hygge.com

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 极简图片加载器（OkHttp 下载 → Bitmap → ImageView）。
 * 无第三方依赖；带内存 LRU 缓存与主线程回投。
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

    /** 异步加载图片到 ImageView（tag 校验防止复用错位） */
    fun load(url: String, imageView: ImageView) {
        if (url.isBlank()) return
        imageView.tag = url
        cache.get(url)?.let {
            imageView.setImageBitmap(it)
            return
        }
        executor.execute {
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", "NebulaUpdater-Android/1.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@execute
                    val bytes = resp.body?.bytes() ?: return@execute
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@execute
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
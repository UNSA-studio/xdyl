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

    private const val BASE = "https://login.lanternwaves.fun/user/avatar/"

    private val cache = object : LruCache<String, Bitmap>(64) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
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

    /** 加载到 ImageView；成功回调 true，失败回调 false */
    fun load(iv: ImageView, avatar: String?, onDone: ((Boolean) -> Unit)? = null) {
        val url = urlOf(avatar)
        if (url == null) {
            main.post { onDone?.invoke(false) }
            return
        }
        val cached = cache.get(url)
        if (cached != null) {
            iv.setImageDrawable(circle(iv, cached))
            onDone?.invoke(true)
            return
        }
        pool.execute {
            try {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        LogManager.w("[AVATAR] $url -> ${resp.code}")
                        main.post { onDone?.invoke(false) }
                        return@execute
                    }
                    val bytes = resp.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        main.post { onDone?.invoke(false) }
                        return@execute
                    }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp == null) {
                        LogManager.w("[AVATAR] 解码失败 $url")
                        main.post { onDone?.invoke(false) }
                        return@execute
                    }
                    cache.put(url, bmp)
                    main.post {
                        iv.setImageDrawable(circle(iv, bmp))
                        onDone?.invoke(true)
                    }
                }
            } catch (e: Exception) {
                LogManager.w("[AVATAR] 下载失败 ${e.message}")
                main.post { onDone?.invoke(false) }
            }
        }
    }

    private fun circle(iv: ImageView, bmp: Bitmap) =
        RoundedBitmapDrawableFactory.create(iv.resources, bmp).apply { isCircular = true }
}

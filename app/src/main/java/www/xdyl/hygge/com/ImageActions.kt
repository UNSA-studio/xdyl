package www.xdyl.hygge.com

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 图片长按操作菜单（类似 Windows 右键菜单）：
 *  - 查看 Markdown 源码
 *  - 下载（默认到 Download/NebulaImages）
 *  - 指定下载（自定义 Download 下的子目录）
 *  - 删除该资源缓存
 */
object ImageActions {

    private val items = arrayOf("查看 Markdown 源码", "下载", "指定下载", "删除该资源缓存")

    /** 在任意 Activity 中弹出图片操作菜单 */
    fun showMenu(activity: androidx.appcompat.app.AppCompatActivity, url: String, markdown: String? = null) {
        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle("图片操作")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showMarkdownSource(activity, url, markdown)
                    1 -> download(activity, url, "NebulaImages")
                    2 -> askDownloadDir(activity, url)
                    3 -> {
                        SimpleImageLoader.removeCache(activity, url)
                        Toast.makeText(activity, "已删除该资源的本地缓存", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 查看 Markdown 源码（可复制） */
    private fun showMarkdownSource(activity: androidx.appcompat.app.AppCompatActivity, url: String, markdown: String?) {
        val md = markdown ?: "![图片]($url)"
        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle("Markdown 源码")
            .setMessage(md)
            .setPositiveButton("复制") { _, _ ->
                val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("markdown", md))
                Toast.makeText(activity, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 指定下载目录（Download 下的子目录名） */
    private fun askDownloadDir(activity: androidx.appcompat.app.AppCompatActivity, url: String) {
        val et = EditText(activity).apply {
            hint = "Download 下的子目录名"
            setText("NebulaImages")
            setSelection(text.length)
        }
        val wrapper = android.widget.FrameLayout(activity).apply {
            setPadding(48, 16, 48, 0)
            addView(et)
        }
        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle("指定下载目录")
            .setMessage("将保存到 /sdcard/Download/<子目录>/")
            .setView(wrapper)
            .setPositiveButton("下载") { _, _ ->
                val sub = et.text.toString().trim().ifBlank { "NebulaImages" }
                download(activity, url, sub)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 下载图片到 /sdcard/Download/<subDir>/ */
    fun download(activity: androidx.appcompat.app.AppCompatActivity, url: String, subDir: String = "NebulaImages") {
        Toast.makeText(activity, "正在下载…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .build()
                    val req = okhttp3.Request.Builder().url(url)
                        .header("User-Agent", "NebulaUpdater-Android/1.0")
                        .build()
                    val bytes = client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                        resp.body?.bytes() ?: throw RuntimeException("响应为空")
                    }
                    val ext = url.substringAfterLast('.', "jpg").substringBefore('?').take(5)
                    val dir = File(
                        Environment.getExternalStorageDirectory(),
                        "Download/$subDir"
                    ).apply { mkdirs() }
                    val out = File(dir, "img_${System.currentTimeMillis()}.$ext")
                    out.writeBytes(bytes)
                    out
                }
                Toast.makeText(activity, "已保存到：${file.absolutePath}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(activity, "下载失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 给 ImageView 绑定长按菜单 */
    fun attachLongPress(activity: androidx.appcompat.app.AppCompatActivity, view: View, url: String, markdown: String? = null) {
        view.setOnLongClickListener {
            showMenu(activity, url, markdown)
            true
        }
    }
}
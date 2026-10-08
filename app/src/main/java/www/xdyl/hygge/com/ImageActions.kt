package www.xdyl.hygge.com

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Environment
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
 *  - 指定下载（调用【文件管理器组件】选择任意目录）
 *  - 删除该资源缓存
 */
object ImageActions {

    private val items = arrayOf("查看 Markdown 源码", "下载", "指定下载", "删除该资源缓存")

    /** 在任意页面弹出图片操作菜单 */
    fun showMenu(activity: AppCompatActivity, url: String, markdown: String? = null) {
        MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setTitle("图片操作")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showMarkdownSource(activity, url, markdown)
                    1 -> downloadTo(
                        activity, url,
                        File(Environment.getExternalStorageDirectory(), "Download/NebulaImages")
                    )
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
    private fun showMarkdownSource(activity: AppCompatActivity, url: String, markdown: String?) {
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

    /**
     * 指定下载：调用【文件管理器组件】选择目标目录。
     * 组件调用形式：FolderBrowserFragment.pick(manager, 标题, 起始目录) { dir -> ... }
     */
    private fun askDownloadDir(activity: AppCompatActivity, url: String) {
        FolderBrowserFragment.pick(
            activity.supportFragmentManager,
            title = "选择图片下载目录",
            startDir = Environment.getExternalStorageDirectory().absolutePath + "/Download"
        ) { dir ->
            downloadTo(activity, url, dir)
        }
    }

    /** 下载图片到指定目录 */
    fun downloadTo(activity: AppCompatActivity, url: String, dir: File) {
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
                    dir.mkdirs()
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
    fun attachLongPress(activity: AppCompatActivity, view: View, url: String, markdown: String? = null) {
        view.setOnLongClickListener {
            showMenu(activity, url, markdown)
            true
        }
    }
}
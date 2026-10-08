package www.xdyl.hygge.com

import android.os.Build
import android.os.Environment
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * 【全局共用文件管理器】
 *
 * 与首页「选择文件夹」对话框**完全同款**：
 *   MaterialAlertDialog + dialog_file_browser.xml + 底部两个对话框按钮
 *   （[返回上级] [选择此文件夹]）
 *
 * 任何页面调用：
 * ```
 * FileManagerDialog.show(
 *     this,                                   // AppCompatActivity
 *     title = "选择图片下载目录",
 *     startDir = "/sdcard/Download",
 *     extensions = null                        // 例：arrayOf("csv") 只显示 .csv
 * ) { picked ->                                // picked = 选中的文件或目录
 *     // 使用 picked
 * }
 * ```
 */
object FileManagerDialog {

    /**
     * @param extensions 后缀过滤（如 arrayOf("csv")）；null = 显示全部
     * @param onPick 回调：选中的目录（点“选择此文件夹”）或文件/条目（单击·长按）
     */
    fun show(
        activity: AppCompatActivity,
        title: String = "选择文件夹",
        startDir: String? = null,
        extensions: Array<String>? = null,
        onPick: (File) -> Unit
    ) {
        var currentDir = File(
            startDir ?: Environment.getExternalStorageDirectory().absolutePath
        )

        val view = activity.layoutInflater.inflate(R.layout.dialog_file_browser, null)
        val tvTitle = view.findViewById<TextView>(R.id.tvBrowserTitle)
        val tvPath = view.findViewById<TextView>(R.id.tvPath)
        val tvWarning = view.findViewById<TextView>(R.id.tvRestrictWarning)
        val rv = view.findViewById<RecyclerView>(R.id.recyclerView)

        tvTitle.text = title
        rv.layoutManager = LinearLayoutManager(activity)

        lateinit var adapter: FileAdapter

        fun load(dir: File) {
            currentDir = dir
            tvPath.text = dir.absolutePath
            // Android 11+ 受限目录提示（与首页一致）
            val restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    (dir.absolutePath.contains("/Android/data") || dir.absolutePath.contains("/Android/obb"))
            tvWarning.visibility = if (restricted) View.VISIBLE else View.GONE
            val files = dir.listFiles()?.toList()
                ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
                ?: emptyList()
            adapter.submitList(files)
        }

        val dialog = MaterialAlertDialogBuilder(activity, R.style.DialogAnimation)
            .setView(view)
            .setPositiveButton("选择此文件夹") { _, _ -> onPick(currentDir) }
            .setNegativeButton("返回上级", null)
            .create()

        adapter = FileAdapter(
            { file ->
                if (file.isDirectory) {
                    load(file)
                } else {
                    // 单击文件 = 选中
                    onPick(file)
                    dialog.dismiss()
                }
            },
            extensions?.toList()
        )
        adapter.onFolderSelected = { file ->
            // 长按条目 = 选中
            onPick(file)
            dialog.dismiss()
        }
        rv.adapter = adapter

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val parent = currentDir.parentFile
                if (parent != null && parent.canRead()) load(parent)
            }
            load(currentDir)
        }
        dialog.show()
    }
}
package www.xdyl.hygge.com

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.io.File

/**
 * 【可复用组件】文件管理器（底部弹出）。
 *
 * 调用方式（任意页面 / Fragment）：
 * ```
 * FolderBrowserFragment.pick(
 *     supportFragmentManager,
 *     title = "选择 CSV 文件",
 *     startDir = "/sdcard",
 *     extensions = arrayOf("csv")      // 只显示该后缀的文件（目录不受限）
 * ) { picked ->                       // picked 可能是文件或目录
 *     // 处理选中的文件/目录
 * }
 * ```
 */
class FolderBrowserFragment : BottomSheetDialogFragment() {

    private var currentDir: File = Environment.getExternalStorageDirectory()
    private var title: String = "选择文件夹"
    private var extensions: Array<String>? = null
    private lateinit var adapter: FileAdapter
    var onFolderSelected: ((File) -> Unit)? = null
    private var tvPath: TextView? = null
    private var recyclerView: RecyclerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentDir = File(arguments?.getString(ARG_START_DIR) ?: Environment.getExternalStorageDirectory().absolutePath)
        title = arguments?.getString(ARG_TITLE) ?: "选择文件夹"
        extensions = arguments?.getStringArray(ARG_EXTENSIONS)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_file_browser, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvPath = view.findViewById(R.id.tvPath)
        recyclerView = view.findViewById(R.id.recyclerView)
        view.findViewById<TextView>(R.id.tvBrowserTitle)?.text = title

        // 右下角「选择此文件夹」：选中当前所在目录
        view.findViewById<View>(R.id.btnPickThisDir)?.setOnClickListener {
            onFolderSelected?.invoke(currentDir)
            dismiss()
        }

        adapter = FileAdapter(
            { file ->
                if (file.isDirectory) {
                    navigateToDirectory(file)
                } else {
                    // 单击文件 = 选中该文件
                    onFolderSelected?.invoke(file)
                    dismiss()
                }
            },
            extensions?.toList()
        )
        adapter.onFolderSelected = { file ->
            // 长按 = 选中该项（文件或目录）
            onFolderSelected?.invoke(file)
            dismiss()
        }

        recyclerView!!.layoutManager = LinearLayoutManager(requireContext())
        recyclerView!!.adapter = adapter
        tvPath!!.text = currentDir.absolutePath
        loadFiles()
    }

    private fun navigateToDirectory(dir: File) {
        val recycler = recyclerView ?: return
        // 向右滑出当前列表
        recycler.animate()
            .translationX(recycler.width.toFloat())
            .setDuration(250)
            .withEndAction {
                currentDir = dir
                tvPath!!.text = currentDir.absolutePath
                loadFiles()
                // 从左侧滑入新列表
                recycler.translationX = -recycler.width.toFloat()
                recycler.animate()
                    .translationX(0f)
                    .setDuration(250)
                    .start()
            }
            .start()
    }

    private fun loadFiles() {
        val files = currentDir.listFiles()?.toList()
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
            ?: emptyList()
        adapter.submitList(files)
    }

    companion object {
        const val ARG_TITLE = "title"
        const val ARG_START_DIR = "startDir"
        const val ARG_EXTENSIONS = "extensions"

        /**
         * 组件入口。
         * @param extensions 只显示这些后缀的文件（目录不受限）；null = 显示全部
         * @param onPick 回调：用户选中的文件或目录
         */
        fun pick(
            manager: androidx.fragment.app.FragmentManager,
            title: String = "选择文件夹",
            startDir: String? = null,
            extensions: Array<String>? = null,
            onPick: (File) -> Unit
        ): FolderBrowserFragment {
            val f = FolderBrowserFragment()
            f.arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_START_DIR, startDir ?: Environment.getExternalStorageDirectory().absolutePath)
                if (extensions != null) putStringArray(ARG_EXTENSIONS, extensions)
            }
            f.onFolderSelected = onPick
            f.show(manager, "folder_browser")
            return f
        }
    }
}
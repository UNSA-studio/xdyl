package www.xdyl.hygge.com

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
class FolderBrowserFragment : BottomSheetDialogFragment() {

    private var currentDir: File = Environment.getExternalStorageDirectory()
    private var title: String = "选择文件夹"
    private lateinit var adapter: FileAdapter
    var onFolderSelected: ((File) -> Unit)? = null
    private var tvPath: TextView? = null
    private var recyclerView: RecyclerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentDir = File(arguments?.getString(ARG_START_DIR) ?: Environment.getExternalStorageDirectory().absolutePath)
        title = arguments?.getString(ARG_TITLE) ?: "选择文件夹"
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
        view.findViewById<View>(R.id.btnPickThisDir)?.setOnClickListener {
            onFolderSelected?.invoke(currentDir)
            dismiss()
        }


        adapter = FileAdapter { file ->
            if (file.isDirectory) {
                navigateToDirectory(file)
            }
        }
        adapter.onFolderSelected = { file ->
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
            ?.sortedWith(compareBy<File> { it.isDirectory }.thenBy { it.name })
            ?: emptyList()
        adapter.submitList(files)
    }

    companion object {
        const val ARG_TITLE = "title"
        const val ARG_START_DIR = "startDir"

        /**
         * 【组件入口】任意页面调用即可弹出文件选择器：
         * ```
         * FolderBrowserFragment.pick(supportFragmentManager, "选择下载目录") { dir ->
         *     // dir 为用户选中的目录
         * }
         * ```
         */
        fun pick(
            manager: androidx.fragment.app.FragmentManager,
            title: String = "选择文件夹",
            startDir: String? = null,
            onPick: (File) -> Unit
        ): FolderBrowserFragment {
            val f = FolderBrowserFragment()
            f.arguments = Bundle().apply {
                putString(ARG_TITLE, title)
                putString(ARG_START_DIR, startDir ?: Environment.getExternalStorageDirectory().absolutePath)
            }
            f.onFolderSelected = onPick
            f.show(manager, "folder_browser")
            return f
        }
    }
}

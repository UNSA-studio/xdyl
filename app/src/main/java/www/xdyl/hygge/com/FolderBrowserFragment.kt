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
    private lateinit var adapter: FileAdapter
    var onFolderSelected: ((File) -> Unit)? = null
    private var tvPath: TextView? = null
    private var recyclerView: RecyclerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentDir = File(arguments?.getString("startDir") ?: Environment.getExternalStorageDirectory().absolutePath)
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
}

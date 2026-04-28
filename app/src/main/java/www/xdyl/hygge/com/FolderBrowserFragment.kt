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
        val tvPath = view.findViewById<TextView>(R.id.tvPath)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerView)

        adapter = FileAdapter { file ->
            if (file.isDirectory) {
                // 进入子文件夹，替换自身并添加动画
                parentFragmentManager.beginTransaction()
                    .setCustomAnimations(
                        R.anim.slide_in_right, R.anim.slide_out_left,
                        R.anim.slide_in_left, R.anim.slide_out_right
                    )
                    .replace(R.id.fragment_container, FolderBrowserFragment().apply {
                        arguments = Bundle().apply {
                            putString("startDir", file.absolutePath)
                        }
                        onFolderSelected = this@FolderBrowserFragment.onFolderSelected
                    })
                    .addToBackStack(null)
                    .commit()
            }
        }

        adapter.onFolderSelected = { file ->
            onFolderSelected?.invoke(file)
            dismiss()
        }

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        tvPath.text = currentDir.absolutePath
        loadFiles()
    }

    private fun loadFiles() {
        val files = currentDir.listFiles()?.toList()
            ?.sortedWith(compareBy<File> { it.isDirectory }.thenBy { it.name })
            ?: emptyList()
        adapter.submitList(files)
    }
}

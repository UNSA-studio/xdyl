package www.xdyl.hygge.com

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.os.bundleOf
import java.io.File

class FolderBrowserFragment : Fragment() {

    private var currentDir: File = Environment.getExternalStorageDirectory()
    private lateinit var adapter: FileAdapter
    private var onFolderSelectedListener: ((File) -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_folder_browser, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        currentDir = File(arguments?.getString("startDir") ?: Environment.getExternalStorageDirectory().absolutePath)
        val tvPath = view.findViewById<TextView>(R.id.tvPath)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerView)

        adapter = FileAdapter { file ->
            if (file.isDirectory) {
                // 进入子文件夹，使用 Fragment 切换动画
                parentFragmentManager.beginTransaction()
                    .setCustomAnimations(R.anim.slide_in_right, R.anim.slide_out_left, R.anim.slide_in_left, R.anim.slide_out_right)
                    .replace(R.id.container, FolderBrowserFragment().apply {
                        arguments = bundleOf("startDir" to file.absolutePath)
                        onFolderSelectedListener = this@FolderBrowserFragment.onFolderSelectedListener
                    })
                    .addToBackStack(null)
                    .commit()
            }
        }
        adapter.onFolderSelected = { file ->
            onFolderSelectedListener?.invoke(file)
        }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        tvPath.text = currentDir.absolutePath
        loadFiles()
    }

    private fun loadFiles() {
        val files = currentDir.listFiles()?.toList()?.sortedWith(compareBy<File> { it.isDirectory }.thenBy { it.name }) ?: emptyList()
        adapter.submitList(files)
    }

    companion object {
        fun setOnFolderSelectedListener(listener: (File) -> Unit) {
            // 在实例化时设置，这里通过静态方式传递，也可以使用 viewModel
        }
    }
}

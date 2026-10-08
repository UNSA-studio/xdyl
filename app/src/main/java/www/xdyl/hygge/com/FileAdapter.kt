package www.xdyl.hygge.com

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * 文件列表适配器。
 *
 * 支持【后缀过滤】：extensions 非空时，只显示「目录 + 指定后缀的文件」，
 * 用于只给用户看到特定类型文件（例如自定义 CSV 只允许 .csv）。
 */
class FileAdapter(
    private val onItemClick: (File) -> Unit,
    private var extensions: List<String>? = null
) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    var onFolderSelected: ((File) -> Unit)? = null
    private var files: List<File> = emptyList()

    /** 设置后缀过滤（null 或空 = 不过滤） */
    fun setExtensions(exts: List<String>?) {
        extensions = exts?.filter { it.isNotBlank() }
    }

    /** 目录一律显示；文件需匹配后缀 */
    private fun accept(f: File): Boolean {
        if (f.isDirectory) return true
        val exts = extensions ?: return true
        if (exts.isEmpty()) return true
        val name = f.name.lowercase()
        return exts.any { name.endsWith("." + it.lowercase().trimStart('.')) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val tv = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
        tv.setBackgroundColor(0xFF1E1E1E.toInt())
        tv.setTextColor(0xFFFFFFFF.toInt())
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.textView.text = if (file.isDirectory) "📁 " + file.name else "📄 " + file.name
        holder.itemView.setOnClickListener { onItemClick(file) }
        holder.itemView.setOnLongClickListener {
            onFolderSelected?.invoke(file)
            true
        }
    }

    override fun getItemCount(): Int = files.size

    fun submitList(newFiles: List<File>) {
        files = newFiles.filter { accept(it) }
        notifyDataSetChanged()
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView as TextView
    }
}
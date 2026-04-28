package www.xdyl.hygge.com

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class FileAdapter(private val onItemClick: (File) -> Unit) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    var onFolderSelected: ((File) -> Unit)? = null
    private var files: List<File> = emptyList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
        view.setBackgroundColor(0xFF1E1E1E.toInt())
        (view as TextView).setTextColor(0xFFFFFFFF.toInt())
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.textView.text = file.name
        holder.itemView.setOnClickListener {
            onItemClick(file)
        }
        holder.itemView.setOnLongClickListener {
            onFolderSelected?.invoke(file)
            true
        }
    }

    override fun getItemCount(): Int = files.size

    fun submitList(newFiles: List<File>) {
        files = newFiles
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view as TextView
    }
}

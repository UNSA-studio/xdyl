package com.tungsten.fcl.nebula

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.tungsten.fcl.R

/**
 * 版本列表适配器（我们的 UI 风格）。
 */
class NebulaVersionAdapter(
    private val onLaunch: (NebulaVersionItem) -> Unit,
    private val onSelect: (NebulaVersionItem) -> Unit
) : RecyclerView.Adapter<NebulaVersionAdapter.Holder>() {

    data class NebulaVersionItem(
        val id: String,
        val gameVersion: String,
        val modCount: Int,
        val isModpack: Boolean
    ) {
        fun meta(): String {
            val parts = ArrayList<String>()
            if (gameVersion.isNotBlank()) parts.add("游戏 $gameVersion")
            if (modCount > 0) parts.add("Mod $modCount")
            if (isModpack) parts.add("整合包")
            if (parts.isEmpty()) parts.add("本地版本")
            return parts.joinToString(" · ")
        }
    }

    private var items: List<NebulaVersionItem> = emptyList()
    private var selectedId: String? = null

    fun submit(list: List<NebulaVersionItem>, selected: String?) {
        items = list
        selectedId = selected
        notifyDataSetChanged()
    }

    fun current(): List<NebulaVersionItem> = items

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nebula_version, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.name.text = item.id
        holder.meta.text = item.meta()
        holder.itemView.setBackgroundResource(
            if (item.id == selectedId) R.drawable.bg_nebula_card_selected else R.drawable.bg_nebula_card
        )
        holder.itemView.setOnClickListener { onSelect(item) }
        holder.launch.setOnClickListener { onLaunch(item) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvVersionName)
        val meta: TextView = view.findViewById(R.id.tvVersionMeta)
        val launch: MaterialButton = view.findViewById(R.id.btnVersionLaunch)
    }
}
package www.xdyl.hygge.com

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File

class EasterEggActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private var csvBrowseDialog: AlertDialog? = null
    private var currentDir: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_easter_egg)
        prefs = getSharedPreferences("xdyl_settings", MODE_PRIVATE)

        // 解锁线程上限
        val swUnlock = findViewById<SwitchMaterial>(R.id.swUnlockThread)
        swUnlock.isChecked = prefs.getBoolean("unlock_thread_limit", false)
        swUnlock.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("unlock_thread_limit", isChecked).apply()
            if (isChecked) {
                Toast.makeText(this, "线程上限已解锁，设置中可调整至 1024", Toast.LENGTH_SHORT).show()
            } else {
                val current = prefs.getInt("thread_limit", 256)
                if (current > 128) {
                    prefs.edit().putInt("thread_limit", 128).apply()
                }
                Toast.makeText(this, "线程上限已锁定为 128", Toast.LENGTH_SHORT).show()
            }
        }

        val switchNeoforge = findViewById<SwitchMaterial>(R.id.swNeoforgeCheck)
        switchNeoforge.isChecked = prefs.getBoolean("neoforge_check_enabled", true)
        switchNeoforge.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("neoforge_check_enabled", isChecked).apply()
            Toast.makeText(this, if (isChecked) "NeoForge 检查已开启" else "NeoForge 检查已关闭", Toast.LENGTH_SHORT).show()
        }

        val switchClean = findViewById<SwitchMaterial>(R.id.swCleanOrphanFiles)
        switchClean.isChecked = prefs.getBoolean("clean_orphan_files", true)
        switchClean.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("clean_orphan_files", isChecked).apply()
        }

        val switchLocalCsv = findViewById<SwitchMaterial>(R.id.swLocalCsv)
        val btnPickCsv = findViewById<MaterialButton>(R.id.btnPickCsv)
        switchLocalCsv.isChecked = prefs.getBoolean("use_local_csv", false)
        btnPickCsv.visibility = if (switchLocalCsv.isChecked) View.VISIBLE else View.GONE
        switchLocalCsv.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_local_csv", isChecked).apply()
            btnPickCsv.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) prefs.edit().remove("local_csv_path").apply()
        }
        btnPickCsv.setOnClickListener { showCsvFilePicker() }

        val btnWhitelist = findViewById<MaterialButton>(R.id.btnWhitelist)
        btnWhitelist.setOnClickListener { showWhitelistDialog() }

        // 重置按钮
        val btnReset = findViewById<MaterialButton>(R.id.btnReset)
        btnReset.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("重置确认")
                .setMessage("将清除所有设置、路径记忆、白名单、标记状态等，恢复到初始状态。此操作不可撤销！")
                .setPositiveButton("重置") { _, _ ->
                    prefs.edit().clear().apply()
                    Toast.makeText(this, "已重置，请重启应用", Toast.LENGTH_LONG).show()
                    finishAffinity()
                    System.exit(0)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showWhitelistDialog() {
        val whitelist = (prefs.getStringSet("mod_whitelist", emptySet()) ?: emptySet()).toMutableList()
        val items = whitelist.toTypedArray()
        val checked = BooleanArray(items.size)

        MaterialAlertDialogBuilder(this)
            .setTitle("模组白名单")
            .setMultiChoiceItems(items, checked) { _, _, _ -> }
            .setPositiveButton("添加") { _, _ ->
                val input = EditText(this)
                input.hint = "输入模组文件名"
                MaterialAlertDialogBuilder(this)
                    .setTitle("添加白名单")
                    .setView(input)
                    .setPositiveButton("确定") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty() && !whitelist.contains(name)) {
                            whitelist.add(name)
                            saveWhitelist(whitelist)
                            Toast.makeText(this, "已添加", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("删除选中") { _, _ ->
                val toRemove = whitelist.filterIndexed { index, _ -> checked[index] }
                if (toRemove.isNotEmpty()) {
                    whitelist.removeAll(toRemove)
                    saveWhitelist(whitelist)
                    Toast.makeText(this, "已删除 ${toRemove.size} 项", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "未选中任何项", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("关闭", null)
            .show()
    }

    private fun saveWhitelist(list: List<String>) {
        prefs.edit().putStringSet("mod_whitelist", list.toSet()).apply()
    }

    // CSV 选择器（略，与之前相同，不再重复，但确保有正确功能）
    private fun showCsvFilePicker() {
        val lastPath = prefs.getString("csv_browser_last_path", Environment.getExternalStorageDirectory().absolutePath)
        currentDir = File(lastPath)
        if (!currentDir!!.exists()) currentDir!!.mkdirs()

        val view = layoutInflater.inflate(R.layout.dialog_file_browser, null)
        val tvPath = view.findViewById<TextView>(R.id.tvPath)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerView)
        recycler.layoutManager = LinearLayoutManager(this)

        fun loadDir(dir: File) {
            val files = dir.listFiles()?.sortedWith(compareBy<File> { it.isDirectory }.thenBy { it.name }) ?: emptyList()
            tvPath.text = dir.absolutePath
            val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val tv = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
                    tv.setBackgroundColor(0xFF1E1E1E.toInt()); tv.setTextColor(0xFFFFFFFF.toInt())
                    return object : RecyclerView.ViewHolder(tv) {}
                }
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val f = files[position]
                    (holder.itemView as TextView).text = f.name
                    holder.itemView.setOnClickListener {
                        if (f.isDirectory) {
                            currentDir = f; prefs.edit().putString("csv_browser_last_path", f.absolutePath).apply()
                            loadDir(f)
                        } else if (f.name.endsWith(".csv")) {
                            prefs.edit().putString("local_csv_path", f.absolutePath).apply()
                            csvBrowseDialog?.dismiss()
                            Toast.makeText(this@EasterEggActivity, "已选择 CSV: ${f.name}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@EasterEggActivity, "请选择 .csv 文件", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                override fun getItemCount(): Int = files.size
            }
            recycler.adapter = adapter
            updateBackButtonState(dir)
        }
        loadDir(currentDir!!)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setNegativeButton("返回上级", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val parent = currentDir!!.parentFile ?: return@setOnClickListener
                currentDir = parent; prefs.edit().putString("csv_browser_last_path", parent.absolutePath).apply()
                loadDir(parent)
            }
        }
        csvBrowseDialog = dialog
        dialog.show()
    }

    private fun updateBackButtonState(dir: File) {
        val btn = csvBrowseDialog?.getButton(AlertDialog.BUTTON_NEGATIVE) ?: return
        val root = Environment.getExternalStorageDirectory()
        val isRoot = try { dir.canonicalPath == root.canonicalPath } catch (e: Exception) { dir.absolutePath == root.absolutePath }
        btn.isEnabled = !isRoot
        btn.alpha = if (isRoot) 0.5f else 1.0f
    }
}

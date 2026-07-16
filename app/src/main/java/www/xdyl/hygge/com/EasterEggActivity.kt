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
import com.google.android.material.textfield.TextInputEditText
import java.io.File

class EasterEggActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private var csvBrowseDialog: AlertDialog? = null
    private var currentDir: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_easter_egg)
        prefs = getSharedPreferences("xdyl_settings", MODE_PRIVATE)

        val editThreadLimit = findViewById<TextInputEditText>(R.id.etThreadLimit)
        editThreadLimit.setText(prefs.getInt("thread_limit", 256).toString())
        editThreadLimit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = editThreadLimit.text.toString().toIntOrNull()?.coerceIn(128, 1024) ?: 256
                prefs.edit().putInt("thread_limit", value).apply()
                Toast.makeText(this, "线程数已更新", Toast.LENGTH_SHORT).show()
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
    }

    private fun showWhitelistDialog() {
        val rawSet = prefs.getStringSet("mod_whitelist", emptySet())
        val whitelist = rawSet?.toMutableSet() ?: mutableSetOf()   // 确保非 null
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, whitelist.toList())
        val listView = ListView(this)
        listView.adapter = adapter
        val input = EditText(this)
        input.hint = "输入模组文件名（如 example.jar）"

        MaterialAlertDialogBuilder(this)
            .setTitle("模组白名单")
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(input)
                addView(listView)
            })
            .setPositiveButton("添加") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    whitelist.add(name)
                    prefs.edit().putStringSet("mod_whitelist", whitelist).apply()
                    adapter.add(name)
                }
            }
            .setNegativeButton("删除选中") { _, _ ->
                val selected = listView.checkedItemPosition
                if (selected >= 0) {
                    val item = adapter.getItem(selected)
                    whitelist.remove(item)
                    prefs.edit().putStringSet("mod_whitelist", whitelist).apply()
                    adapter.remove(item)
                }
            }
            .setNeutralButton("关闭", null)
            .show()
    }

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
            tvPath.text = dir.absolutePath   // 确保路径显示
            val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val tv = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
                    tv.setBackgroundColor(0xFF1E1E1E.toInt()); tv.setTextColor(0xFFFFFFFF.toInt())
                    return object : RecyclerView.ViewHolder(tv) {}
                }
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val f = files[position]
                    val tv = holder.itemView as TextView
                    tv.text = f.name
                    holder.itemView.setOnClickListener {
                        if (f.isDirectory) {
                            currentDir = f
                            prefs.edit().putString("csv_browser_last_path", f.absolutePath).apply()
                            loadDir(f)
                            updateUpButton()
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
            updateUpButton()
        }
        loadDir(currentDir!!)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setNegativeButton("返回上级", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val parent = currentDir!!.parentFile ?: return@setOnClickListener
                currentDir = parent
                prefs.edit().putString("csv_browser_last_path", parent.absolutePath).apply()
                loadDir(parent)
            }
        }
        csvBrowseDialog = dialog
        dialog.show()
    }

    private fun updateUpButton() {
        val btn = csvBrowseDialog?.getButton(AlertDialog.BUTTON_NEGATIVE) ?: return
        val root = Environment.getExternalStorageDirectory()
        val isRoot = currentDir?.absolutePath == root.absolutePath
        btn.isEnabled = !isRoot
        btn.alpha = if (isRoot) 0.5f else 1.0f
    }
}

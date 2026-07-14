package www.xdyl.hygge.com

import android.content.Intent
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
import rikka.shizuku.Shizuku
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
        switchNeoforge.isChecked = prefs.getBoolean("neoforge_check_enabled", true) // 默认开启
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
            if (!isChecked) {
                prefs.edit().remove("local_csv_path").apply()
            }
        }
        btnPickCsv.setOnClickListener { showCsvFilePicker() }

        // Shizuku 测试按钮
        val btnTestShizuku = findViewById<MaterialButton>(R.id.btnTestShizuku)
        btnTestShizuku.setOnClickListener {
            if (Shizuku.pingBinder()) {
                if (Shizuku.isPreV11() || Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    // 已授权，执行测试
                    try {
                        val rootFiles = Shizuku.newProcess(arrayOf("ls", "/"), null, null).inputStream.bufferedReader().readText()
                        Toast.makeText(this, "Shizuku 可用！根目录文件列表:\n$rootFiles", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Shizuku 执行失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    // 需要请求权限
                    Shizuku.requestPermission(0)
                    Toast.makeText(this, "请在弹出的界面中授权 Shizuku", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show()
            }
        }

        // 白名单管理按钮
        val btnWhitelist = findViewById<MaterialButton>(R.id.btnWhitelist)
        btnWhitelist.setOnClickListener {
            showWhitelistDialog()
        }

        findViewById<MaterialButton>(R.id.btnAchievements).setOnClickListener {
            startActivity(Intent(this, AchievementActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnDownloadUrl).setOnClickListener {
            Toast.makeText(this, "自定义下载地址暂未实现", Toast.LENGTH_SHORT).show()
        }
    }

    // 白名单对话框
    private fun showWhitelistDialog() {
        val whitelist = prefs.getStringSet("mod_whitelist", emptySet())?.toMutableSet() ?: mutableSetOf()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, whitelist.toList())
        val listView = ListView(this)
        listView.adapter = adapter
        val input = EditText(this)
        input.hint = "输入模组文件名（如 example.jar）"

        val dialog = MaterialAlertDialogBuilder(this)
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
                    Toast.makeText(this, "已添加", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("删除选中") { _, _ ->
                val selected = listView.checkedItemPosition
                if (selected >= 0) {
                    val item = adapter.getItem(selected)
                    whitelist.remove(item)
                    prefs.edit().putStringSet("mod_whitelist", whitelist).apply()
                    adapter.remove(item)
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("关闭", null)
            .create()
        dialog.show()
    }

    // 内置 CSV 文件选择器（保持不变）
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
            val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val tv = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
                    tv.setBackgroundColor(0xFF1E1E1E.toInt())
                    tv.setTextColor(0xFFFFFFFF.toInt())
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
                            tvPath.text = f.absolutePath
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
                tvPath.text = parent.absolutePath
            }
        }
        csvBrowseDialog = dialog
        dialog.show()
    }

    private fun updateUpButton() {
        val root = Environment.getExternalStorageDirectory()
        val btn = csvBrowseDialog?.getButton(AlertDialog.BUTTON_NEGATIVE) ?: return
        if (currentDir?.absolutePath == root.absolutePath) {
            btn.isEnabled = false
            btn.alpha = 0.5f
        } else {
            btn.isEnabled = true
            btn.alpha = 1.0f
        }
    }
}

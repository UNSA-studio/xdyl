package www.xdyl.hygge.com

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
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
import java.security.MessageDigest

class EasterEggActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private var csvBrowseDialog: AlertDialog? = null
    private var currentDir: File? = null
    private var csvRecycler: RecyclerView? = null
    private var csvTvPath: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_easter_egg)
        prefs = getSharedPreferences("xdyl_settings", MODE_PRIVATE)

        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        btnBack.setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        val swUnlock = findViewById<SwitchMaterial>(R.id.swUnlockThread)
        swUnlock.isChecked = prefs.getBoolean("unlock_thread_limit", false)
        swUnlock.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("unlock_thread_limit", isChecked).apply()
            Toast.makeText(this, if (isChecked) "线程上限已解锁至 1024" else "线程上限已锁定为 128", Toast.LENGTH_SHORT).show()
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

        // 隐藏终端：仅 ADB 广播解锁后显示入口
        val btnTerminal = findViewById<MaterialButton>(R.id.btnTerminal)
        updateTerminalVisibility(btnTerminal)
        btnTerminal.setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        // WiFi ADB 远程日志
        val swWifiAdb = findViewById<SwitchMaterial>(R.id.swWifiAdb)
        val layoutWifiConfig = findViewById<LinearLayout>(R.id.layoutWifiConfig)
        val etWifiIp = findViewById<TextInputEditText>(R.id.etWifiIp)
        val etWifiPort = findViewById<TextInputEditText>(R.id.etWifiPort)
        val btnWifiConnect = findViewById<MaterialButton>(R.id.btnWifiConnect)

        swWifiAdb.isChecked = prefs.getBoolean("wifi_adb_enabled", false)
        layoutWifiConfig.visibility = if (swWifiAdb.isChecked) View.VISIBLE else View.GONE
        LogManager.wifiAdbEnabled = swWifiAdb.isChecked

        // 回显保存的 IP 和端口
        etWifiIp.setText(prefs.getString("wifi_adb_ip", ""))
        val savedPort = prefs.getInt("wifi_adb_port", 5555)
        etWifiPort.setText(savedPort.toString())

        swWifiAdb.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("wifi_adb_enabled", isChecked).apply()
            layoutWifiConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
            LogManager.wifiAdbEnabled = isChecked
        }

        btnWifiConnect.setOnClickListener {
            val ip = etWifiIp.text.toString().trim()
            val port = etWifiPort.text.toString().trim().toIntOrNull() ?: 5555

            if (ip.isBlank()) {
                Toast.makeText(this, "请输入 IP 地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("wifi_adb_ip", ip)
                .putInt("wifi_adb_port", port)
                .apply()

            LogManager.deviceIp = ip
            LogManager.devicePort = port

            Toast.makeText(this, "已保存: $ip:$port", Toast.LENGTH_SHORT).show()
        }

        // 如果已启用且 IP 已配置，启动时自动激活
        if (swWifiAdb.isChecked) {
            val savedIp = prefs.getString("wifi_adb_ip", "")
            val savedPort = prefs.getInt("wifi_adb_port", 5555)
            if (!savedIp.isNullOrBlank()) {
                LogManager.deviceIp = savedIp
                LogManager.devicePort = savedPort
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 广播解锁后返回页面刷新终端入口
        val btnTerminal = findViewById<MaterialButton>(R.id.btnTerminal)
        updateTerminalVisibility(btnTerminal)
    }

    private fun updateTerminalVisibility(btn: MaterialButton) {
        val rawEnabled = prefs.getBoolean("terminal_enabled", false)
        val savedSig = prefs.getString("terminal_sig", "") ?: ""
        val expectSig = sha256(internalTerminalSalt2() + packageName)
        val valid = rawEnabled && savedSig == expectSig
        btn.visibility = if (valid) View.VISIBLE else View.GONE
    }

    private fun internalTerminalSalt2(): String = listOf(
        "s", "t", "a", "r", "_", "x", "d", "y", "l"
    ).joinToString("")

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun showWhitelistDialog() {
        val whitelist = (prefs.getStringSet("mod_whitelist", emptySet()) ?: emptySet()).toMutableList()
        val items = whitelist.toTypedArray()
        val checked = BooleanArray(items.size)

        MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
            .setTitle("模组白名单")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("添加") { d, _ ->
                d.dismiss()
                val input = EditText(this)
                input.hint = "输入模组文件名"
                MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
                    .setTitle("添加白名单")
                    .setView(input)
                    .setPositiveButton("确定") { d2, _ ->
                        d2.dismiss()
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty() && !whitelist.contains(name)) {
                            whitelist.add(name)
                            saveWhitelist(whitelist)
                            Toast.makeText(this, "已添加", Toast.LENGTH_SHORT).show()
                        }
                        // 添加后回到白名单（刷新列表）
                        showWhitelistDialog()
                    }
                    .setNegativeButton("取消") { _, _ ->
                        // 取消也回到白名单
                        showWhitelistDialog()
                    }
                    .setOnCancelListener {
                        showWhitelistDialog()
                    }
                    .show()
            }
            .setNegativeButton("删除选中") { d, _ ->
                val toRemove = mutableListOf<String>()
                for (i in items.indices) {
                    if (checked[i]) toRemove.add(items[i])
                }
                if (toRemove.isNotEmpty()) {
                    whitelist.removeAll(toRemove)
                    saveWhitelist(whitelist)
                    Toast.makeText(this, "已删除 ${toRemove.size} 项", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "未选中任何项", Toast.LENGTH_SHORT).show()
                }
                d.dismiss()
                // 删除后回到白名单（刷新列表）
                showWhitelistDialog()
            }
            .setNeutralButton("关闭", null)
            .show()
    }

    private fun saveWhitelist(list: List<String>) {
        prefs.edit().putStringSet("mod_whitelist", list.toSet()).apply()
    }

    private fun showCsvFilePicker() {
        val lastPath = prefs.getString("csv_browser_last_path", Environment.getExternalStorageDirectory().absolutePath) ?: Environment.getExternalStorageDirectory().absolutePath
        currentDir = File(lastPath)
        if (!currentDir!!.exists()) currentDir!!.mkdirs()

        val view = layoutInflater.inflate(R.layout.dialog_file_browser, null)
        csvTvPath = view.findViewById(R.id.tvPath)
        csvRecycler = view.findViewById(R.id.recyclerView)
        csvRecycler!!.layoutManager = LinearLayoutManager(this)

        val dialog = MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
            .setView(view)
            .setNegativeButton("返回上级", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val parent = currentDir!!.parentFile ?: return@setOnClickListener
                navigateCsvDir(parent, false)
            }
        }
        csvBrowseDialog = dialog
        // csvBrowseDialog 赋值后再加载，updateUpButton 才能正确设置按钮状态
        loadCsvDir(currentDir!!)
        dialog.show()
        // show 后按钮视图已构建，再刷一次确保状态正确
        updateUpButton(currentDir!!)
    }

    // 目录切换滑动动画：前进向右滑出+左滑入，返回反向
    private fun navigateCsvDir(dir: File, forward: Boolean) {
        val recycler = csvRecycler ?: return
        val width = recycler.width.toFloat()
        recycler.animate()
            .translationX(if (forward) -width else width)
            .setDuration(250)
            .setListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    currentDir = dir
                    prefs.edit().putString("csv_browser_last_path", dir.absolutePath).apply()
                    loadCsvDir(dir)
                    recycler.translationX = if (forward) width else -width
                    recycler.animate().translationX(0f).setDuration(250).setListener(null).start()
                }
            })
            .start()
    }

    private fun loadCsvDir(dir: File) {
        val tvPath = csvTvPath ?: return
        val recycler = csvRecycler ?: return
        // 只显示文件夹和 .csv 文件
        val files = dir.listFiles()
            ?.filter { it.isDirectory || it.name.endsWith(".csv") }
            ?.sortedWith(compareBy<File> { it.isDirectory }.thenBy { it.name })
            ?: emptyList()
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
                        navigateCsvDir(f, true)
                    } else {
                        prefs.edit().putString("local_csv_path", f.absolutePath).apply()
                        csvBrowseDialog?.dismiss()
                        Toast.makeText(this@EasterEggActivity, "已选择 CSV: ${f.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            override fun getItemCount(): Int = files.size
        }
        recycler.adapter = adapter
        updateUpButton(dir)
    }

    private fun updateUpButton(dir: File) {
        val btn = csvBrowseDialog?.getButton(AlertDialog.BUTTON_NEGATIVE) ?: return
        val isRoot = dir.absolutePath == Environment.getExternalStorageDirectory().absolutePath
        btn.isEnabled = !isRoot
        btn.alpha = if (isRoot) 0.5f else 1.0f
    }
}

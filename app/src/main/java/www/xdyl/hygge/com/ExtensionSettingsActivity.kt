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

class ExtensionSettingsActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_extension_settings)
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
        btnPickCsv.setOnClickListener {
            FileManagerDialog.show(
                this,
                title = "选择 CSV 文件",
                startDir = prefs.getString("file_browser_last_path", null),
                extensions = arrayOf("csv"),
                mode = FileManagerDialog.MODE_FILE
            ) { f ->
                prefs.edit().putString("local_csv_path", f.absolutePath).apply()
                Toast.makeText(this, "已选择 CSV: ${f.name}", Toast.LENGTH_SHORT).show()
            }
        }

        // 音频播放器（Beta）：开启后需重启，底部出现音频分页
        val swAudioPlayer = findViewById<SwitchMaterial>(R.id.swAudioPlayer)
        swAudioPlayer.isChecked = prefs.getBoolean("audio_player_enabled", false)
        swAudioPlayer.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("audio_player_enabled", isChecked).apply()
            Toast.makeText(
                this,
                if (isChecked) "音频播放器已开启，重启软件后生效" else "音频播放器已关闭，重启软件后生效",
                Toast.LENGTH_LONG
            ).show()
        }
        // 私聊服务器地址
        val etChatServer = findViewById<TextInputEditText>(R.id.etChatServer)
        val btnSaveChatServer = findViewById<MaterialButton>(R.id.btnSaveChatServer)
        etChatServer.setText(prefs.getString("chat_server", ""))
        btnSaveChatServer.setOnClickListener {
            val url = etChatServer.text.toString().trim().trimEnd('/')
            prefs.edit().putString("chat_server", url).apply()
            Toast.makeText(
                this,
                if (url.isBlank()) "已清空私聊服务器地址" else "已保存：$url",
                Toast.LENGTH_SHORT
            ).show()
        }

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


}

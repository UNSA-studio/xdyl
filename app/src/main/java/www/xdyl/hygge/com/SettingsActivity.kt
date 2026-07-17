package www.xdyl.hygge.com

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import www.xdyl.hygge.com.databinding.ActivitySettingsBinding
import java.io.BufferedReader
import java.io.InputStreamReader

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isRestoringState = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("xdyl_settings", MODE_PRIVATE)

        // 返回按钮保存并退出
        binding.btnBack.setOnClickListener {
            savePrefs()
            finish()
        }

        // 先加载状态，标记正在恢复
        isRestoringState = true
        loadPrefs()
        // 恢复完成后才设置监听器，避免触发警告
        binding.swExtensionMode.setOnCheckedChangeListener { _, isChecked ->
            // 如果正在恢复状态，不触发任何弹窗
            if (isRestoringState) {
                isRestoringState = false
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("警告!")
                    .setMessage("您正在开启扩展模式，重启后生效。")
                    .setPositiveButton("开启并重启") { _, _ ->
                        prefs.edit().putBoolean("extension_mode", true).commit()
                        finishAffinity(); System.exit(0)
                    }
                    .setNegativeButton("取消") { _, _ ->
                        // 取消时恢复开关为关闭状态
                        prefs.edit().putBoolean("extension_mode", false).commit()
                        binding.swExtensionMode.isChecked = false
                        binding.btnExtensionPage.visibility = View.GONE
                    }
                    .setOnCancelListener {
                        // 如果用户点击空白处取消，同样恢复
                        prefs.edit().putBoolean("extension_mode", false).commit()
                        binding.swExtensionMode.isChecked = false
                        binding.btnExtensionPage.visibility = View.GONE
                    }
                    .show()
            } else {
                prefs.edit().putBoolean("extension_mode", false).apply()
                binding.btnExtensionPage.visibility = View.GONE
            }
        }

        binding.btnExportLog.setOnClickListener {
            prefs.edit().putBoolean("request_export_log", true).apply()
            finish()
        }

        binding.btnPingServer.setOnClickListener { startPing("82.157.155.86", binding.tvPingServerResult, true) }
        binding.btnPingWifi.setOnClickListener { startPing("8.8.8.8", binding.tvPingWifiResult, false) }

        binding.btnExtensionPage.setOnClickListener {
            startActivity(Intent(this, EasterEggActivity::class.java))
        }

        binding.btnErrorCodes.setOnClickListener { showErrorCodes() }
        binding.btnAbout.setOnClickListener { showAbout() }
    }

    private fun loadPrefs() {
        binding.etVersionName.setText(prefs.getString("version_folder", Constants.TARGET_VERSION_DIR) ?: Constants.TARGET_VERSION_DIR)
        val currentThreads = prefs.getInt("thread_limit", 256)
        binding.etThreadCount.setText(currentThreads.toString())
        val extensionEnabled = prefs.getBoolean("extension_mode", false)
        binding.swExtensionMode.isChecked = extensionEnabled
        binding.btnExtensionPage.visibility = if (extensionEnabled) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        super.onPause()
        savePrefs()
    }

    private fun savePrefs() {
        val version = binding.etVersionName.text.toString().ifBlank { Constants.TARGET_VERSION_DIR }
        val threads = binding.etThreadCount.text.toString().toIntOrNull() ?: 256
        val unlocked = prefs.getBoolean("unlock_thread_limit", false)
        val maxVal = if (unlocked) 1024 else 128
        val finalThreads = threads.coerceIn(20, maxVal)
        prefs.edit()
            .putString("version_folder", version)
            .putInt("thread_limit", finalThreads)
            .apply()
    }

    private fun updateThreadHint() {
        val unlocked = prefs.getBoolean("unlock_thread_limit", false)
        val hint = if (unlocked) "下载线程数 (20-1024)" else "下载线程数 (20-128)"
        binding.threadInputLayout.hint = hint
    }

    override fun onResume() {
        super.onResume()
        updateThreadHint()
    }

    private fun startPing(address: String, textView: TextView, hideIp: Boolean) {
        textView.visibility = View.VISIBLE
        textView.text = "Pinging..."
        scope.launch {
            val result = withContext(Dispatchers.IO) { executePing(address, hideIp) }
            textView.text = result
        }
    }

    private fun executePing(address: String, hideIp: Boolean): String {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("ping", "-c", "4", address))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            val loss = Regex("(\\d+)% packet loss").find(output)?.groupValues?.get(1) ?: "N/A"
            val rtt = Regex("min/avg/max/mdev = (\\d+\\.?\\d*)/(\\d+\\.?\\d*)/(\\d+\\.?\\d*)/(\\d+\\.?\\d*)").find(output)
            val analysis = buildString {
                append("Packet loss: $loss%\n")
                if (rtt != null) append("Min/Avg/Max/mdev: ${rtt.groupValues[1]}/${rtt.groupValues[2]}/${rtt.groupValues[3]}/${rtt.groupValues[4]} ms\n")
            }
            val raw = if (hideIp) output.replace(Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b"), "***") else output
            return "$analysis\n$raw"
        } catch (e: Exception) { return "Ping error: ${e.message}" }
    }

    private fun showErrorCodes() {
        val sb = StringBuilder()
        Constants.errorDescriptions.forEach { (code, desc) -> sb.append("$code: $desc\n\n") }
        MaterialAlertDialogBuilder(this)
            .setTitle("ERROR 错误代码")
            .setMessage(sb.toString().trim())
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showAbout() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        val ivIcon = view.findViewById<ImageView>(R.id.ivIcon)
        ivIcon.setImageResource(R.mipmap.ic_launcher)

        val tvRepo = view.findViewById<TextView>(R.id.tvRepo)
        tvRepo.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/UNSA-studio/xdyl"))) }
        val tvSBA = view.findViewById<TextView>(R.id.tvSBA)
        tvSBA.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/UNSA-studio/Supply-By-Airdrop-SBA"))) }
        val tvST = view.findViewById<TextView>(R.id.tvST)
        tvST.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/UNSA-studio/Shortcut-Terminal"))) }
        val tvJE404 = view.findViewById<TextView>(R.id.tvJE404)
        tvJE404.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/UNSA-studio/Java-ERROR-404"))) }

        MaterialAlertDialogBuilder(this)
            .setTitle("关于软件")
            .setView(view)
            .setPositiveButton("关闭", null)
            .show()
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}

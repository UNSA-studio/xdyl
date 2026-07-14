package www.xdyl.hygge.com

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("xdyl_settings", MODE_PRIVATE)
        loadPrefs()

        binding.btnExportLog.setOnClickListener {
            prefs.edit().putBoolean("request_export_log", true).apply()
            finish()
        }

        binding.btnPingServer.setOnClickListener { startPing("82.157.155.86", binding.tvPingServerResult, "Server") }
        binding.btnPingWifi.setOnClickListener { startPing("8.8.8.8", binding.tvPingWifiResult, "WiFi") }

        binding.swExtensionMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("警告!")
                    .setMessage("您正在开启扩展模式，重启后生效。")
                    .setPositiveButton("开启并重启") { _, _ ->
                        prefs.edit().putBoolean("extension_mode", true).commit()
                        finishAffinity()
                        System.exit(0)
                    }
                    .setNegativeButton("取消") { _, _ ->
                        prefs.edit().putBoolean("extension_mode", false).commit()
                        binding.swExtensionMode.isChecked = false
                    }
                    .show()
            } else {
                prefs.edit().putBoolean("extension_mode", false).apply()
                binding.btnExtensionPage.visibility = View.GONE
            }
        }

        binding.btnExtensionPage.setOnClickListener {
            startActivity(Intent(this, EasterEggActivity::class.java))
        }
    }

    override fun onPause() {
        super.onPause()
        savePrefs()
    }

    private fun loadPrefs() {
        binding.etVersionName.setText(prefs.getString("version_folder", Constants.TARGET_VERSION_DIR) ?: Constants.TARGET_VERSION_DIR)
        binding.etThreadCount.setText(prefs.getInt("thread_count", prefs.getInt("thread_limit", 256)).toString())
        binding.swExtensionMode.isChecked = prefs.getBoolean("extension_mode", false)
        binding.btnExtensionPage.visibility = if (prefs.getBoolean("extension_mode", false)) View.VISIBLE else View.GONE
    }

    private fun savePrefs() {
        val version = binding.etVersionName.text.toString().ifBlank { Constants.TARGET_VERSION_DIR }
        val threads = binding.etThreadCount.text.toString().toIntOrNull() ?: 20
        val threadCount = threads.coerceIn(20, 128)
        prefs.edit().putString("version_folder", version).putInt("thread_count", threadCount).apply()
    }

    private fun startPing(address: String, textView: TextView, label: String) {
        textView.visibility = View.VISIBLE
        textView.text = "正在 Ping $label..."
        scope.launch {
            val result = withContext(Dispatchers.IO) { executePing(address) }
            textView.text = result
        }
    }

    private fun executePing(address: String): String {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("ping", "-c", "4", address))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            val output = reader.readText()
            val error = errorReader.readText()
            process.waitFor()
            if (output.isEmpty() && error.isNotEmpty()) return "Ping 失败: $error"
            return parsePingResult(output)
        } catch (e: Exception) { return "Ping 错误: ${e.message}" }
    }

    private fun parsePingResult(raw: String): String {
        val loss = Regex("(\\d+)% packet loss").find(raw)?.groupValues?.get(1) ?: "N/A"
        val rtt = Regex("min/avg/max/mdev = (\\d+\\.?\\d*)/(\\d+\\.?\\d*)/(\\d+\\.?\\d*)/(\\d+\\.?\\d*)").find(raw)
        return buildString {
            append("丢包率: $loss%\n")
            if (rtt != null) append("最小/平均/最大/mdev: ${rtt.groupValues[1]}/${rtt.groupValues[2]}/${rtt.groupValues[3]}/${rtt.groupValues[4]} ms\n")
            append("原始输出:\n$raw")
        }
    }

    override fun onDestroy() { scope.cancel() }
}

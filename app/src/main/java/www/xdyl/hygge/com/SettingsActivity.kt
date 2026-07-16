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

        binding.btnPingServer.setOnClickListener { startPing("82.157.155.86", binding.tvPingServerResult, true) }
        binding.btnPingWifi.setOnClickListener { startPing("8.8.8.8", binding.tvPingWifiResult, false) }

        binding.swExtensionMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("警告!")
                    .setMessage("您正在开启扩展模式，重启后生效。")
                    .setPositiveButton("开启并重启") { _, _ ->
                        prefs.edit().putBoolean("extension_mode", true).commit()
                        finishAffinity(); System.exit(0)
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

    override fun onPause() { super.onPause(); savePrefs() }

    private fun loadPrefs() {
        binding.etVersionName.setText(prefs.getString("version_folder", Constants.TARGET_VERSION_DIR) ?: Constants.TARGET_VERSION_DIR)
        // 统一显示 thread_limit 的值，范围 20-128
        val currentThreads = prefs.getInt("thread_limit", 256)
        binding.etThreadCount.setText(currentThreads.toString())
        binding.swExtensionMode.isChecked = prefs.getBoolean("extension_mode", false)
        binding.btnExtensionPage.visibility = if (prefs.getBoolean("extension_mode", false)) View.VISIBLE else View.GONE
    }

    private fun savePrefs() {
        val version = binding.etVersionName.text.toString().ifBlank { Constants.TARGET_VERSION_DIR }
        val threads = binding.etThreadCount.text.toString().toIntOrNull() ?: 20
        // 保存到 thread_limit，并限制在 20-128
        prefs.edit()
            .putString("version_folder", version)
            .putInt("thread_limit", threads.coerceIn(20, 128))
            .apply()
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

            val raw = if (hideIp) {
                output.replace(Regex("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b"), "***")
            } else {
                output
            }

            return "$analysis\n$raw"
        } catch (e: Exception) { return "Ping error: ${e.message}" }
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}

package www.xdyl.hygge.com

import android.animation.LayoutTransition
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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

        val tvPingServerResult = findViewById<TextView>(R.id.tvPingServerResult)
        val tvPingWifiResult = findViewById<TextView>(R.id.tvPingWifiResult)

        binding.btnPingServer.setOnClickListener { startPing("8.129.236.213", tvPingServerResult, "Server") }
        binding.btnPingWifi.setOnClickListener { startPing("8.8.8.8", tvPingWifiResult, "WiFi") }

        binding.swExtensionMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("警告!")
                    .setMessage("您正在开启扩展模式，这个模式里面的内容允许您使用一些Beta内容，可能不稳定，重启后生效。")
                    .setPositiveButton("开启并重启") { _, _ ->
                        prefs.edit().putBoolean("extension_mode", true).commit()  // 同步保存
                        finishAffinity()
                        Process.killProcess(Process.myPid())  // 杀掉进程，确保写入
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
        binding.etVersionName.setText(
            prefs.getString("version_folder", Constants.TARGET_VERSION_DIR) ?: Constants.TARGET_VERSION_DIR
        )
        binding.etThreadCount.setText(
            prefs.getInt("thread_count", 20).toString()
        )
        val extensionEnabled = prefs.getBoolean("extension_mode", false)
        binding.swExtensionMode.isChecked = extensionEnabled
        binding.btnExtensionPage.visibility = if (extensionEnabled) View.VISIBLE else View.GONE
    }

    private fun savePrefs() {
        val version = binding.etVersionName.text.toString().ifBlank { Constants.TARGET_VERSION_DIR }
        val threads = binding.etThreadCount.text.toString().toIntOrNull() ?: 20
        val threadCount = threads.coerceIn(20, 128)
        prefs.edit()
            .putString("version_folder", version)
            .putInt("thread_count", threadCount)
            .apply()
    }

    private fun startPing(address: String, textView: TextView, label: String) {
        textView.visibility = View.VISIBLE
        textView.text = "Pinging $label..."
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
            if (output.isEmpty() && error.isNotEmpty()) return "Ping failed: $error"
            return parsePingResult(output, address)
        } catch (e: Exception) {
            return "Ping error: ${e.message}"
        }
    }

    private fun parsePingResult(raw: String, address: String): String {
        val lossPattern = Regex("(\\d+)% packet loss")
        val loss = lossPattern.find(raw)?.groupValues?.get(1) ?: "N/A"
        val rttPattern = Regex("min/avg/max/mdev = (\\d+\\.?\\d*)/(\\d+\\.?\\d*)/(\\d+\\.?\\d*)/(\\d+\\.?\\d*)")
        val rttMatch = rttPattern.find(raw)
        return buildString {
            append("Target: $address\n")
            append("Packet loss: $loss%\n")
            if (rttMatch != null) {
                append("Min/Avg/Max/mdev: ${rttMatch.groupValues[1]}/${rttMatch.groupValues[2]}/${rttMatch.groupValues[3]}/${rttMatch.groupValues[4]} ms\n")
            }
            append("Raw output:\n$raw")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

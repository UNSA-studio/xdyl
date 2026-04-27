package www.xdyl.hygge.com

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

        binding.btnSave.setOnClickListener {
            savePrefs()
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnExportLog.setOnClickListener {
            prefs.edit().putBoolean("request_export_log", true).apply()
            finish()
        }

        binding.btnGreenScreen.setOnClickListener {
            prefs.edit().putBoolean("trigger_green", true).apply()
            finish()
        }

        binding.btnPingServer.setOnClickListener {
            startPing("8.129.236.213", "Server")
        }

        binding.btnPingWifi.setOnClickListener {
            startPing("8.8.8.8", "Wi-Fi")
        }
    }

    private fun loadPrefs() {
        binding.etVersionName.setText(
            prefs.getString("version_folder", Constants.TARGET_VERSION_DIR) ?: Constants.TARGET_VERSION_DIR
        )
        binding.etThreadCount.setText(
            prefs.getInt("thread_count", 20).toString()
        )
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

    private fun startPing(address: String, label: String) {
        binding.tvPingResult.visibility = View.VISIBLE
        binding.tvPingResult.text = "正在 Ping $label ($address)..."
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                executePing(address)
            }
            binding.tvPingResult.text = result
        }
    }

    private fun executePing(address: String): String {
        try {
            val process = ProcessBuilder()
                .command("ping", "-c", "4", address)
                .redirectErrorStream(true)
                .start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            process.waitFor()
            return formatPingOutput(output, address)
        } catch (e: Exception) {
            return "Ping 失败: ${e.message}"
        }
    }

    private fun formatPingOutput(raw: String, address: String): String {
        val lines = raw.trim().lines()
        val stats = lines.lastOrNull() ?: ""
        val packetLoss = Regex("(\\d+)% packet loss").find(stats)?.groupValues?.get(1) ?: "?"
        val rtt = Regex("min/avg/max/.* = (\\d+\\.\\d+)/(\\d+\\.\\d+)/(\\d+\\.\\d+)/.*").find(raw)
        return buildString {
            append("目标: $address\n")
            append("丢包率: $packetLoss%\n")
            if (rtt != null) {
                append("最小延迟: ${rtt.groupValues[1]} ms\n")
                append("平均延迟: ${rtt.groupValues[2]} ms\n")
                append("最大延迟: ${rtt.groupValues[3]} ms\n")
            }
            append("详细输出:\n$raw")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

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
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnExportLog.setOnClickListener {
            prefs.edit().putBoolean("request_export_log", true).apply()
            finish()
        }

        binding.btnPingServer.setOnClickListener { startPing("8.129.236.213", "Server") }
        binding.btnPingWifi.setOnClickListener { startPing("8.8.8.8", "WiFi") }
    }

    private fun startPing(address: String, label: String) {
        binding.tvPingResult.alpha = 0f
        binding.tvPingResult.visibility = View.VISIBLE
        binding.tvPingResult.animate().alpha(1f).setDuration(300).start()
        binding.tvPingResult.text = "Pinging $label..."
        scope.launch {
            val result = withContext(Dispatchers.IO) { executePing(address) }
            binding.tvPingResult.text = result
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
            append("Raw:\n$raw")
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

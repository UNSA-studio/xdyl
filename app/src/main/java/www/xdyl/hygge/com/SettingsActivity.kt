package www.xdyl.hygge.com

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import www.xdyl.hygge.com.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

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
    }

    private fun loadPrefs() {
        binding.etVersionName.setText(
            prefs.getString("version_folder", Constants.TARGET_VERSION_DIR) ?: Constants.TARGET_VERSION_DIR
        )
        binding.etThreadCount.setText(
            prefs.getInt("thread_count", 20).toString()
        )
        when (prefs.getString("extract_kernel", "junrar")) {
            "junrar" -> binding.radioGroup.check(R.id.rbJunrar)
            "sevenz" -> binding.radioGroup.check(R.id.rbSevenZ)
            "builtin_zip" -> binding.radioGroup.check(R.id.rbBuiltinZip)
        }
    }

    private fun savePrefs() {
        val version = binding.etVersionName.text.toString().ifBlank { Constants.TARGET_VERSION_DIR }
        val threads = binding.etThreadCount.text.toString().toIntOrNull() ?: 20
        val threadCount = threads.coerceIn(20, 128)

        val kernel = when (binding.radioGroup.checkedRadioButtonId) {
            R.id.rbJunrar -> "junrar"
            R.id.rbSevenZ -> "sevenz"
            R.id.rbBuiltinZip -> "builtin_zip"
            else -> "junrar"
        }
        prefs.edit()
            .putString("version_folder", version)
            .putInt("thread_count", threadCount)
            .putString("extract_kernel", kernel)
            .apply()
    }
}

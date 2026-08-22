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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("xdyl_settings", MODE_PRIVATE)

        // 返回按钮保存并退出
        binding.btnBack.setOnClickListener {
            savePrefs()
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        // 先加载设置（此时监听器尚未绑定）
        loadPrefs()

        // 绑定扩展模式开关监听器（确保在 loadPrefs 之后，避免初始化时触发）
        binding.swExtensionMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
                    .setTitle("警告!")
                    .setMessage("您正在开启扩展模式，重启后生效。")
                    .setPositiveButton("开启并重启") { _, _ ->
                        prefs.edit().putBoolean("extension_mode", true).commit()
                        finishAffinity()
                        System.exit(0)
                    }
                    .setNegativeButton("取消") { _, _ ->
                        // 取消时恢复开关为关闭状态
                        prefs.edit().putBoolean("extension_mode", false).commit()
                        binding.swExtensionMode.isChecked = false
                        binding.btnExtensionPage.visibility = View.GONE
                    }
                    .setOnCancelListener {
                        // 点击空白处取消，同样恢复
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
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        binding.btnPingServer.setOnClickListener { startPing("82.157.155.86", binding.tvPingServerResult, true) }
        binding.btnPingWifi.setOnClickListener { startPing("8.8.8.8", binding.tvPingWifiResult, false) }

        // MC 服务器 Ping：需要 Python + mcstatus 扩展包
        binding.btnPingMcServer.setOnClickListener {
            MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
                .setTitle("Ping (MC服务器)")
                .setMessage("此功能需要下载扩展程序包（Python 运行时 + mcstatus，约 21MB）。\n\n确认后将跳转到终端自动安装，期间无法操作，请耐心等待。")
                .setPositiveButton("开始安装") { _, _ ->
                    prefs.edit().putBoolean("terminal_auto_setup", true).commit()
                    startActivity(Intent(this, TerminalActivity::class.java))
                    @Suppress("DEPRECATION")
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                }
                .setNegativeButton("取消", null)
                .show()
        }

        binding.btnExtensionPage.setOnClickListener {
            startActivity(Intent(this, EasterEggActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        binding.btnErrorCodes.setOnClickListener { showErrorCodes() }
        binding.btnAbout.setOnClickListener { showAbout() }
    }

    private fun loadPrefs() {
        binding.etVersionName.setText(prefs.getString("version_folder", Constants.TARGET_VERSION_DIR) ?: Constants.TARGET_VERSION_DIR)
        val currentThreads = prefs.getInt("thread_limit", 256)
        binding.etThreadCount.setText(currentThreads.toString())
        binding.ivThreadInfo.setOnClickListener { showThreadInfo() }
        val extensionEnabled = prefs.getBoolean("extension_mode", false)
        // 注意：设置开关状态时不会触发监听器，因为监听器尚未绑定
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
        // 收起状态准备展开
        textView.layoutParams.height = 0
        textView.visibility = View.VISIBLE
        textView.text = "Pinging..."
        expandView(textView)
        scope.launch {
            val result = withContext(Dispatchers.IO) { executePing(address, hideIp) }
            textView.text = result
            // 结果内容更长，再次展开到新高度
            expandView(textView)
        }
    }

    // 结果区域自身展开动画（1.2秒）
    private fun expandView(v: View) {
        v.post {
            v.measure(
                View.MeasureSpec.makeMeasureSpec(v.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val targetH = v.measuredHeight
            val animator = android.animation.ValueAnimator.ofInt(v.height, targetH)
            animator.duration = 1200
            animator.interpolator = android.view.animation.DecelerateInterpolator()
            animator.addUpdateListener {
                v.layoutParams.height = it.animatedValue as Int
                v.requestLayout()
            }
            animator.start()
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
        MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
            .setTitle("ERROR 错误代码")
            .setMessage(sb.toString().trim())
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showThreadInfo() {
        MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
            .setTitle("线程与分块说明")
            .setMessage("「下载线程数」指的是同时下载的文件数量，越大的值会让更多文件并行下载。\n\n「分块规则」是适应性的：≤1MB 的文件默认用 2 个 HTTP 下载块，大于 1MB 的每多 0.5MB 就多分配 1 个下载块。例如 3MB 的文件会被拆成 6 块同时下载。\n\n下载线程数不是越大越好，请根据网络带宽和设备性能合理设置。")
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

        MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
            .setTitle("关于软件")
            .setView(view)
            .setPositiveButton("关闭", null)
            .show()
    }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}

package com.tungsten.fcl.nebula

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tungsten.fcl.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用设置（与旧版 xdyl 交互一致）：
 * 版本文件夹（锁定展示）/ 下载线程数（含说明弹窗）/ Ping ×3（展开动画）/
 * 扩展模式（警告重启）/ 导出日志 / 错误代码 / 关于软件。
 */
class NebulaSettingsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var btnBack: android.widget.ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nebula_settings)

        val prefs = getSharedPreferences("nebula_settings", MODE_PRIVATE)

        btnBack = findViewById(R.id.btnBack)
        btnBack.setOnClickListener {
            savePrefs()
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        // 先加载设置（此时监听器尚未绑定）
        loadPrefs()

        val btnExtensionPage = findViewById<View>(R.id.btnExtensionPage)
        val swExtensionMode = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swExtensionMode)

        // 扩展模式开关（旧版交互：开启警告并重启生效）
        swExtensionMode.setOnCheckedChangeListener { _, isChecked ->
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
                        prefs.edit().putBoolean("extension_mode", false).commit()
                        swExtensionMode.isChecked = false
                        btnExtensionPage.visibility = View.GONE
                    }
                    .setOnCancelListener {
                        prefs.edit().putBoolean("extension_mode", false).commit()
                        swExtensionMode.isChecked = false
                        btnExtensionPage.visibility = View.GONE
                    }
                    .show()
            } else {
                prefs.edit().putBoolean("extension_mode", false).apply()
                btnExtensionPage.visibility = View.GONE
            }
        }

        btnExtensionPage.setOnClickListener {
            startActivity(Intent(this, NebulaEasterEggActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        findViewById<View>(R.id.btnExportLog).setOnClickListener { exportLogs() }

        findViewById<View>(R.id.btnPingServer).setOnClickListener {
            startPing(
                prefs.getString("server_ping_address", "82.157.155.86") ?: "82.157.155.86",
                findViewById(R.id.tvPingServerResult), true
            )
        }
        findViewById<View>(R.id.btnPingWifi).setOnClickListener {
            startPing("8.8.8.8", findViewById(R.id.tvPingWifiResult), false)
        }
        findViewById<View>(R.id.btnPingMcServer).setOnClickListener { pingMcServer() }

        findViewById<View>(R.id.btnErrorCodes).setOnClickListener { showErrorCodes() }
        findViewById<View>(R.id.btnAbout).setOnClickListener { showAbout() }
    }

    private fun loadPrefs() {
        val prefs = getSharedPreferences("nebula_settings", MODE_PRIVATE)
        // 整合包时代：版本文件夹由整合包 manifest 自动管理，此输入框仅展示锁定
        val etVersionName = findViewById<TextView>(R.id.etVersionName)
        etVersionName.setText("（由整合包自动管理）")
        etVersionName.isEnabled = false

        val currentThreads = prefs.getInt("thread_limit", 256)
        findViewById<TextView>(R.id.etThreadCount).setText(currentThreads.toString())
        findViewById<View>(R.id.ivThreadInfo).setOnClickListener { showThreadInfo() }

        val extensionEnabled = prefs.getBoolean("extension_mode", false)
        // 注意：设置开关状态时不会触发监听器，因为监听器尚未绑定
        findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.swExtensionMode).isChecked = extensionEnabled
        findViewById<View>(R.id.btnExtensionPage).visibility =
            if (extensionEnabled) View.VISIBLE else View.GONE
    }

    override fun onPause() {
        super.onPause()
        savePrefs()
    }

    private fun savePrefs() {
        val prefs = getSharedPreferences("nebula_settings", MODE_PRIVATE)
        val threads = findViewById<TextView>(R.id.etThreadCount).text.toString().toIntOrNull() ?: 256
        val unlocked = prefs.getBoolean("unlock_thread_limit", false)
        val maxVal = if (unlocked) 1024 else 128
        val finalThreads = threads.coerceIn(20, maxVal)
        prefs.edit().putInt("thread_limit", finalThreads).apply()
    }

    private fun updateThreadHint() {
        val prefs = getSharedPreferences("nebula_settings", MODE_PRIVATE)
        val unlocked = prefs.getBoolean("unlock_thread_limit", false)
        val hint = if (unlocked) "下载线程数 (20-1024)" else "下载线程数 (20-128)"
        findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.threadInputLayout).hint = hint
    }

    override fun onResume() {
        super.onResume()
        updateThreadHint()
    }

    // ==================== Ping（与旧版一致的展开动画） ====================

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

    /** 结果区域自身展开动画（1.2秒） */
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
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", "/system/bin/ping -c 4 -W 2 $address")
            )
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            process.waitFor()
            var text = if (out.isNotBlank()) out.trim() else "ping 失败: ${err.trim().ifBlank { "未知错误" }}"
            if (hideIp) text = text.replace(address, "服务器")
            text
        } catch (e: Exception) {
            "查询异常: ${e.message}"
        }
    }

    private fun pingMcServer() {
        if (!NebulaTerminalActivity.isPythonReady(this)) {
            MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
                .setTitle("Ping (MC服务器)")
                .setMessage("此功能需要下载扩展程序包（Python 运行包 + mcstatus，约 21MB）。\n\n确认后将跳转到终端自动安装，期间无法操作，请耐心等待。")
                .setPositiveButton("开始安装") { _, _ ->
                    getSharedPreferences("nebula_settings", MODE_PRIVATE)
                        .edit().putBoolean("terminal_auto_setup", true).commit()
                    startActivity(Intent(this, NebulaTerminalActivity::class.java))
                    @Suppress("DEPRECATION")
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        val tv = findViewById<TextView>(R.id.tvPingMcResult)
        tv.layoutParams.height = 0
        tv.visibility = View.VISIBLE
        tv.text = "查询中..."
        expandView(tv)
        scope.launch {
            val result = withContext(Dispatchers.IO) { executeMcPing() }
            tv.text = result
            expandView(tv)
        }
    }

    private fun executeMcPing(): String {
        return try {
            val exe = NebulaTerminalActivity.pythonExePath(this) ?: return "错误: Python 未安装"
            val root = File(filesDir, "python_root")
            val script = """
import sys
try:
    from mcstatus import JavaServer
    # Android 没有 /etc/resolv.conf，必须手动配置 DNS 解析器
    import dns.resolver
    import dns.query
    _r = dns.resolver.Resolver(configure=False)
    _r.nameservers = ['223.5.5.5', '8.8.8.8', '1.1.1.1']
    dns.resolver.DefaultResolver = _r
    dns.query._resolver = _r
    s = JavaServer.lookup('mc.lanternwaves.fun:25565')
    st = s.status()
    print('状态: 在线')
    try:
        print('延迟: %d ms' % round(s.ping()))
    except Exception:
        pass
    desc = str(st.description)
    import re
    desc = re.sub(r'[§\u00a7][0-9a-fk-or]', '', desc)
    print('描述: %s' % desc[:120])
except ImportError:
    print('错误: mcstatus 未安装')
except Exception as e:
    print('查询失败: %s' % e)
""".trimIndent()
            val scriptFile = File(cacheDir, "mc_ping.py")
            scriptFile.writeText(script)
            val env = arrayOf(
                "PATH=${exe.substringBeforeLast('/')}:/sbin:/system/bin",
                "PYTHONPATH=${root.absolutePath}/lib:${root.absolutePath}/lib/site-packages:${root.absolutePath}/lib/lib-dynload",
                "PYTHONHOME=${root.absolutePath}",
                "LD_LIBRARY_PATH=${root.absolutePath}/bin"
            )
            val p = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", "\"$exe\" \"${scriptFile.absolutePath}\""),
                env
            )
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            scriptFile.delete()
            if (out.isNotBlank()) out.trimEnd() else "查询失败: ${err.trimEnd().ifBlank { "未知错误" }}"
        } catch (e: Exception) {
            "查询异常: ${e.message}"
        }
    }

    // ==================== 导出日志 ====================

    private fun exportLogs() {
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                try {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
                    val outDir = File(Environment.getExternalStorageDirectory(), "Download")
                    if (!outDir.exists()) outDir.mkdirs()
                    val outFile = File(outDir, "nebula-log-$stamp.log")
                    val sb = StringBuilder()
                    sb.append("=== Nebula Updater 应用日志 ===\n")
                    sb.append("时间: ").append(Date()).append("\n\n")
                    try {
                        val p = Runtime.getRuntime().exec(
                            arrayOf("/system/bin/sh", "-c", "logcat -d -t 800 --pid=${android.os.Process.myPid()} 2>/dev/null")
                        )
                        val text = p.inputStream.bufferedReader().readText()
                        p.waitFor()
                        sb.append("=== logcat ===\n").append(text.take(200000)).append("\n\n")
                    } catch (e: Exception) {
                        sb.append("logcat 读取失败: ").append(e.message).append("\n\n")
                    }
                    try {
                        val logDir = File(Environment.getExternalStorageDirectory(), "NUL/log")
                        if (logDir.isDirectory) {
                            val files = logDir.listFiles()?.sortedByDescending { it.lastModified() }?.take(3)
                            files?.forEach { f ->
                                sb.append("=== ").append(f.name).append(" ===\n")
                                sb.append(f.readText().take(120000)).append("\n\n")
                            }
                        }
                    } catch (e: Exception) {
                        sb.append("内核日志读取失败: ").append(e.message).append("\n")
                    }
                    outFile.writeText(sb.toString())
                    outFile.absolutePath
                } catch (e: Exception) {
                    null
                }
            }
            if (path == null) {
                Toast.makeText(this@NebulaSettingsActivity, "日志导出失败", Toast.LENGTH_LONG).show()
            } else {
                MaterialAlertDialogBuilder(this@NebulaSettingsActivity, R.style.DialogAnimation)
                    .setTitle("日志已导出")
                    .setMessage(path)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    // ==================== 错误代码 / 线程说明 / 关于 ====================

    private fun showErrorCodes() {
        val sb = StringBuilder()
        NebulaConstants.errorDescriptions.forEach { (code, desc) -> sb.append("$code: $desc\n\n") }
        MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
            .setTitle("ERROR 错误代码")
            .setMessage(sb.toString().trim())
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showThreadInfo() {
        MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
            .setTitle("线程与分块说明")
            .setMessage(
                "「下载线程数」指的是同时下载的文件数量，越大的值会让更多文件并行下载。\n\n" +
                    "「分块规则」是适应性的：≤1MB 的文件默认用 2 个 HTTP 下载块，大于 1MB 的每多 0.5MB 就多分配 1 个下载块。例如 3MB 的文件会被拆成 6 块同时下载。\n\n" +
                    "下载线程数不是越大越好，请根据网络带宽和设备性能合理设置。"
            )
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showAbout() {
        val view = layoutInflater.inflate(R.layout.dialog_about, null)
        val ivIcon = view.findViewById<ImageView>(R.id.ivIcon)
        ivIcon.setImageResource(R.mipmap.ic_launcher)
        view.findViewById<TextView>(R.id.tvRepo).setOnClickListener {
            openUrl("https://github.com/UNSA-studio/xdyl")
        }
        view.findViewById<TextView>(R.id.tvSBA).setOnClickListener {
            openUrl("https://github.com/UNSA-studio/Supply-By-Airdrop-SBA")
        }
        view.findViewById<TextView>(R.id.tvST).setOnClickListener {
            openUrl("https://github.com/UNSA-studio/Shortcut-Terminal")
        }
        view.findViewById<TextView>(R.id.tvJE404).setOnClickListener {
            openUrl("https://github.com/UNSA-studio/Java-ERROR-404")
        }
        MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
            .setView(view)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
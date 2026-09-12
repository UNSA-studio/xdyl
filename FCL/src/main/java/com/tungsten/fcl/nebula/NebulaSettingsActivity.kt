package com.tungsten.fcl.nebula

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.tungsten.fcl.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用设置（我们的风格）：导出日志 / 网络测试 / 扩展页 / 错误代码 / 关于。
 */
class NebulaSettingsActivity : AppCompatActivity() {

    private lateinit var tvPingServerResult: TextView
    private lateinit var tvPingWifiResult: TextView
    private lateinit var tvPingMcResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nebula_settings)

        findViewById<View>(R.id.btnSettingsBack).setOnClickListener { finish() }
        tvPingServerResult = findViewById(R.id.tvPingServerResult)
        tvPingWifiResult = findViewById(R.id.tvPingWifiResult)
        tvPingMcResult = findViewById(R.id.tvPingMcResult)

        findViewById<MaterialButton>(R.id.btnExportLog).setOnClickListener { exportLogs() }
        findViewById<MaterialButton>(R.id.btnPingServer).setOnClickListener {
            startPing(
                getSharedPreferences("nebula_settings", MODE_PRIVATE)
                    .getString("server_ping_address", "82.157.155.86") ?: "82.157.155.86",
                tvPingServerResult, true
            )
        }
        findViewById<MaterialButton>(R.id.btnPingWifi).setOnClickListener {
            startPing("8.8.8.8", tvPingWifiResult, false)
        }
        findViewById<MaterialButton>(R.id.btnPingMcServer).setOnClickListener { pingMcServer() }
        findViewById<MaterialButton>(R.id.btnExtensionPage).setOnClickListener {
            startActivity(Intent(this, NebulaEasterEggActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnErrorCodes).setOnClickListener { showErrorCodes() }
        findViewById<MaterialButton>(R.id.btnAbout).setOnClickListener { showAbout() }
    }

    // ==================== 导出日志 ====================

    private fun exportLogs() {
        lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) {
                try {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
                    val outDir = File(Environment.getExternalStorageDirectory(), "Download")
                    if (!outDir.exists()) outDir.mkdirs()
                    val outFile = File(outDir, "nebula-log-$stamp.log")
                    val sb = StringBuilder()
                    sb.append("=== Nebula Updater 应用日志 ===\n")
                    sb.append("时间: ").append(Date()).append("\n\n")
                    // 1) logcat（仅本进程）
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
                    // 2) FCL 内核日志目录（/NUL/log）
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
                AlertDialog.Builder(this@NebulaSettingsActivity)
                    .setTitle("日志已导出")
                    .setMessage(path)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    // ==================== Ping ====================

    private fun startPing(address: String, textView: TextView, hideIp: Boolean) {
        textView.visibility = View.VISIBLE
        textView.text = "Pinging..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { executePing(address, hideIp) }
            textView.text = result
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
            AlertDialog.Builder(this)
                .setTitle("Ping (MC服务器)")
                .setMessage("此功能需要下载扩展程序包（Python 运行包 + mcstatus，约 21MB）。\n\n确认后将跳转到终端自动安装，期间无法操作，请耐心等待。")
                .setPositiveButton("开始安装") { _, _ ->
                    getSharedPreferences("nebula_settings", MODE_PRIVATE)
                        .edit().putBoolean("terminal_auto_setup", true).apply()
                    startActivity(Intent(this, NebulaTerminalActivity::class.java))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        val tv = tvPingMcResult
        tv.visibility = View.VISIBLE
        tv.text = "查询中..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { executeMcPing() }
            tv.text = result
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

    // ==================== 错误代码 / 关于 ====================

    private fun showErrorCodes() {
        val sb = StringBuilder()
        NebulaConstants.errorDescriptions.forEach { (code, desc) -> sb.append("$code: $desc\n\n") }
        AlertDialog.Builder(this)
            .setTitle("ERROR 错误代码")
            .setMessage(sb.toString().trim())
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Throwable) {
            ""
        }
        val message = "星云更新器 Nebula Updater v$version\n\n" +
            "点击下方项目名可打开对应 GitHub 仓库。"
        AlertDialog.Builder(this)
            .setTitle("关于")
            .setMessage(message)
            .setPositiveButton("xdyl 主仓库") { _, _ ->
                openUrl("https://github.com/UNSA-studio/xdyl")
            }
            .setNeutralButton("Java-ERROR-404") { _, _ ->
                openUrl("https://github.com/UNSA-studio/Java-ERROR-404")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }
}
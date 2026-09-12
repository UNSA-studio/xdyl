package com.tungsten.fcl.nebula

import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tungsten.fcl.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 隐藏终端（适配自旧版）：Python 扩展包安装 / 命令执行。
 */
class NebulaTerminalActivity : AppCompatActivity() {

    companion object {
        /** 供外部（设置页）检测扩展包是否就绪 */
        fun pythonExePath(context: android.content.Context): String? {
            val root = File(context.filesDir, "python_root")
            return listOf(File(root, "bin/python3"), File(root, "bin/python"))
                .firstOrNull { it.exists() && it.canExecute() }?.absolutePath
        }

        fun isPythonReady(context: android.content.Context): Boolean = pythonExePath(context) != null
    }

    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var scrollView: ScrollView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var workingDir = File(Environment.getExternalStorageDirectory(), "NebulaUpdater")
    private lateinit var pythonRoot: File
    private lateinit var pythonBinDir: File
    private lateinit var btnSend: Button
    private lateinit var btnBack: ImageButton
    private var autoSetupMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nebula_terminal)
        tvOutput = findViewById(R.id.tvTerminalOutput)
        etInput = findViewById(R.id.etTerminalInput)
        scrollView = findViewById(R.id.terminalScrollView)
        tvOutput.movementMethod = ScrollingMovementMethod()
        tvOutput.setTextIsSelectable(true)

        pythonRoot = File(filesDir, "python_root")
        pythonBinDir = File(filesDir, "python_bin")
        pythonRoot.mkdirs(); pythonBinDir.mkdirs()
        if (!workingDir.exists()) workingDir.mkdirs()

        btnSend = findViewById(R.id.btnTerminalSend)
        btnBack = findViewById(R.id.btnTerminalBack)

        val prefs = getSharedPreferences("nebula_settings", MODE_PRIVATE)
        if (prefs.getBoolean("terminal_auto_setup", false)) {
            autoSetupMode = true
            prefs.edit().putBoolean("terminal_auto_setup", false).commit()
        }

        btnBack.setOnClickListener {
            if (!autoSetupMode) {
                finish()
            } else {
                MaterialAlertDialogBuilder(this, R.style.DialogAnimation)
                    .setTitle("退出安装?")
                    .setMessage("扩展组件仍在后台安装中，现在退出不会中断安装，\n完成后回到设置页点击 Ping (MC服务器) 即可使用。")
                    .setPositiveButton("退出") { _, _ -> finish() }
                    .setNegativeButton("继续等待", null)
                    .show()
            }
        }

        appendLine(if (autoSetupMode) "星云更新器扩展组件安装" else "星云更新器隐藏终端")
        if (!autoSetupMode) {
            appendLine("命令: exit 退出 | pysetup 安装Python | pysetup status 状态")
            appendLine("当前目录: ${workingDir.absolutePath}")
            appendLine("")
        }
        btnSend.setOnClickListener { runCommand() }
        etInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                runCommand(); true
            } else false
        }
        if (autoSetupMode) {
            startAutoSetup()
        }
    }

    private fun startAutoSetup() {
        btnSend.isEnabled = false
        btnBack.isEnabled = false
        etInput.isEnabled = false
        findViewById<View>(R.id.terminalInputBar).visibility = View.GONE
        appendLine("=== 扩展组件安装模式 ===")
        appendLine("正在下载并安装扩展程序包（Python 运行包 + mcstatus），请稍候...")
        appendLine("")
        scope.launch(Dispatchers.IO) {
            if (!isPythonInstalled()) {
                installPython()
            } else {
                appendLine("Python 已安装，跳过")
            }
            if (isPythonInstalled()) {
                appendLine("正在安装 mcstatus...")
                execShell("\"${findPythonExe(pythonRoot)?.absolutePath}\" -m pip install mcstatus")
                appendLine("扩展程序包安装完成!")
                appendLine("")
                appendLine("现在可以返回设置页使用 Ping (MC服务器) 了")
            } else {
                appendLine("Python 安装失败，请手动输入 pysetup 重试")
            }
            runOnUiThread {
                btnSend.isEnabled = true
                btnBack.isEnabled = true
                etInput.isEnabled = true
                autoSetupMode = false
            }
        }
    }

    private fun runCommand() {
        val cmd = etInput.text.toString().trim()
        if (cmd.isEmpty()) return
        etInput.setText("")
        appendLine("$ $cmd")
        when {
            cmd == "exit" -> {
                finish(); return
            }
            cmd == "pysetup" -> {
                scope.launch(Dispatchers.IO) { installPython() }; return
            }
            cmd == "pysetup status" -> {
                appendLine(if (isPythonInstalled()) "Python: 已安装" else "Python: 未安装, 输入 pysetup 安装"); return
            }
            cmd.startsWith("pysetup source ") -> {
                val url = cmd.removePrefix("pysetup source ").trim()
                if (url.isEmpty()) appendLine("当前下载源: ${getSourceUrl()}")
                else {
                    getSharedPreferences("nebula_settings", MODE_PRIVATE).edit()
                        .putString("python_download_url", url).commit()
                    appendLine("已设置下载源: $url")
                }
                return
            }
            cmd.startsWith("cd ") -> {
                val dir = when (val t = cmd.removePrefix("cd ").trim()) {
                    "" -> workingDir
                    else -> {
                        val f = if (t.startsWith("/")) File(t) else File(workingDir, t)
                        if (f.isDirectory) f else {
                            appendLine("cd: 目录不存在: $t"); return
                        }
                    }
                }
                workingDir = dir; appendLine("当前目录: ${dir.absolutePath}"); return
            }
        }
        scope.launch(Dispatchers.IO) { execShell(cmd) }
    }

    private fun execShell(cmd: String) {
        try {
            var realCmd = cmd
            if (isPythonInstalled()) {
                findPythonExe(pythonRoot)?.let { exe ->
                    realCmd = cmd.replace(Regex("^python3?\\b"), "\"${exe.absolutePath}\"")
                }
            }
            val shCmd = buildString {
                append("cd \"${workingDir.absolutePath}\" && ")
                if (isPythonInstalled()) {
                    append("export PATH=\"${pythonBinDir.absolutePath}:\$PATH\"; ")
                    append("export PYTHONPATH=\"${pythonRoot.absolutePath}/lib:${pythonRoot.absolutePath}/lib/site-packages:${pythonRoot.absolutePath}/lib/lib-dynload:\$PYTHONPATH\"; ")
                    append("export PYTHONHOME=\"${pythonRoot.absolutePath}\"; ")
                    append("export LD_LIBRARY_PATH=\"${pythonRoot.absolutePath}/bin:\$LD_LIBRARY_PATH\"; ")
                }
                append(realCmd)
            }
            val p = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", shCmd))
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            p.waitFor()
            runOnUiThread {
                if (out.isNotEmpty()) appendLine(out.trimEnd())
                if (err.isNotEmpty()) appendLine(err.trimEnd())
                scrollToBottom()
            }
        } catch (e: Exception) {
            runOnUiThread { appendLine("执行失败: ${e.message}") }
        }
    }

    private fun isPythonInstalled() = File(pythonBinDir, "python3").let { it.exists() && it.canExecute() }

    private fun getSourceUrl() = getSharedPreferences("nebula_settings", MODE_PRIVATE)
        .getString("python_download_url", "https://pan.vma.cc/pan/d/84239b97b46e273a193864eaf86b0e84?ext=gz")!!

    private fun installPython() {
        try {
            appendLine("开始下载 Python 包...")
            val tarFile = File(filesDir, "python_android.tar.gz")
            val conn = (java.net.URL(getSourceUrl()).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 120000; instanceFollowRedirects = true
            }
            if (conn.responseCode != 200) {
                appendLine("下载失败: HTTP ${conn.responseCode}"); conn.disconnect(); return
            }
            val totalLen = conn.contentLengthLong
            val startTime = System.currentTimeMillis()
            conn.inputStream.use { input ->
                tarFile.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var done = 0L
                    var lastPct = -1
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        done += n
                        if (totalLen > 0) {
                            val pct = (done * 100 / totalLen).toInt()
                            if (pct >= lastPct + 2) {
                                lastPct = pct
                                val bar = buildString {
                                    append("[")
                                    repeat(pct / 4) { append("=") }
                                    repeat(25 - pct / 4) { append(" ") }
                                    append("]")
                                }
                                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                                val speed = if (elapsed > 0) (done / 1048576.0 / elapsed) else 0.0
                                val remainSec = if (speed > 0) ((totalLen - done) / 1048576.0 / speed).toLong() else 0
                                appendLine(
                                    "$bar $pct% | ${done / 1048576}/${totalLen / 1048576}MB | " +
                                        String.format("%.1fMB/s", speed) + " | 剩余${remainSec}s"
                                )
                            }
                        }
                    }
                }
            }
            conn.disconnect()
            appendLine("下载完成, 正在解压...")
            pythonRoot.deleteRecursively(); pythonRoot.mkdirs()
            val p = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", "tar -xozf \"${tarFile.absolutePath}\" -C \"${pythonRoot.absolutePath}\"")
            )
            val err = p.errorStream.bufferedReader().readText(); p.waitFor()
            if (p.exitValue() != 0) {
                appendLine("解压失败: $err"); return
            }
            appendLine("解压完成, 正在定位 python3...")
            val pythonExe = findPythonExe(pythonRoot) ?: run {
                appendLine("错误: 包内未找到 python3 可执行文件")
                return
            }
            pythonBinDir.deleteRecursively(); pythonBinDir.mkdirs()
            val wrapper = "#!/system/bin/sh\nexec \"${pythonExe.absolutePath}\" \"\$@\"\n"
            for (name in listOf("python3", "python")) {
                File(pythonBinDir, name).apply { writeText(wrapper); setExecutable(true, false) }
            }
            // 建立 lib/python3.14 -> . 符号链接, 让 Python 按标准前缀路径找到库
            val linkCmd = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", "ln -sfn . \"${pythonRoot.absolutePath}/lib/python3.14\"")
            )
            linkCmd.waitFor()
            val fixPerms = Runtime.getRuntime().exec(
                arrayOf(
                    "/system/bin/sh", "-c",
                    "chmod 755 \"${pythonBinDir.absolutePath}\" " +
                        "\"${pythonBinDir.absolutePath}/python3\" " +
                        "\"${pythonBinDir.absolutePath}/python\" " +
                        "\"${pythonExe.absolutePath}\" && " +
                        "chmod -R 755 \"${pythonRoot.absolutePath}/bin\" && " +
                        "find \"${pythonRoot.absolutePath}\" -name '*.so' -exec chmod 755 {} \\; 2>/dev/null"
                )
            )
            fixPerms.waitFor()
            tarFile.delete()
            appendLine("Python 安装完成!")
            if (!autoSetupMode) {
                appendLine("输入 python3 --version 验证")
            }
        } catch (e: Exception) {
            appendLine("安装失败: ${e.message}")
        }
    }

    private fun findPythonExe(dir: File): File? = listOf(
        File(dir, "bin/python3"), File(dir, "bin/python"), File(dir, "python3"), File(dir, "python")
    ).firstOrNull { it.exists() && it.canExecute() }

    private fun appendLine(text: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            tvOutput.append(text + "\n")
            scrollToBottom()
        } else {
            runOnUiThread { appendLine(text) }
        }
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
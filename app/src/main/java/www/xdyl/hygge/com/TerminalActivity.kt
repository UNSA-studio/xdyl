package www.xdyl.hygge.com

import android.os.Bundle
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
import kotlinx.coroutines.*
import java.io.File
import android.os.Environment
import android.os.Looper

class TerminalActivity : AppCompatActivity() {
    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var scrollView: ScrollView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // 工作目录用公开目录，文件管理器可见可管理
    private var workingDir = File(Environment.getExternalStorageDirectory(), "NebulaUpdater")
    private lateinit var pythonRoot: File
    private lateinit var pythonBinDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        tvOutput = findViewById(R.id.tvTerminalOutput)
        etInput = findViewById(R.id.etTerminalInput)
        scrollView = findViewById(R.id.terminalScrollView)
        tvOutput.movementMethod = ScrollingMovementMethod()
        // 长按可选中复制（Termux 同款）
        tvOutput.setTextIsSelectable(true)

        // Python 运行时必须留在私有目录（sdcard 不支持 exec 权限）
        pythonRoot = File(filesDir, "python_root")
        pythonBinDir = File(filesDir, "python_bin")
        pythonRoot.mkdirs(); pythonBinDir.mkdirs()
        // 确保公开工作目录存在
        if (!workingDir.exists()) workingDir.mkdirs()

        findViewById<ImageButton>(R.id.btnTerminalBack).setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        appendLine("星云更新器隐藏终端")
        appendLine("命令: exit 退出 | pysetup 安装Python | pysetup status 状态")
        appendLine("当前目录: ${workingDir.absolutePath}")
        appendLine("")

        findViewById<Button>(R.id.btnTerminalSend).setOnClickListener { runCommand() }
        etInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                runCommand(); true
            } else false
        }
    }

    private fun runCommand() {
        val cmd = etInput.text.toString().trim()
        if (cmd.isEmpty()) return
        etInput.setText("")
        appendLine("$ $cmd")
        when {
            cmd == "exit" -> { finish(); return }
            cmd == "pysetup" -> { scope.launch(Dispatchers.IO) { installPython() }; return }
            cmd == "pysetup status" -> { appendLine(if (isPythonInstalled()) "Python: 已安装" else "Python: 未安装, 输入 pysetup 安装"); return }
            cmd.startsWith("pysetup source ") -> {
                val url = cmd.removePrefix("pysetup source ").trim()
                if (url.isEmpty()) appendLine("当前下载源: ${getSourceUrl()}")
                else { getSharedPreferences("xdyl_settings", MODE_PRIVATE).edit().putString("python_download_url", url).commit(); appendLine("已设置下载源: $url") }
                return
            }
            cmd.startsWith("cd ") -> {
                val dir = when (val t = cmd.removePrefix("cd ").trim()) {
                    "" -> workingDir
                    else -> { val f = if (t.startsWith("/")) File(t) else File(workingDir, t); if (f.isDirectory) f else { appendLine("cd: 目录不存在: $t"); return } }
                }
                workingDir = dir; appendLine("当前目录: ${dir.absolutePath}"); return
            }
        }
        scope.launch(Dispatchers.IO) { execShell(cmd) }
    }

    private fun execShell(cmd: String) {
        try {
            var realCmd = cmd
            // python3/python 直接指向真实 ELF（wrapper 脚本受 SELinux execute_no_trans 限制不可执行）
            if (isPythonInstalled()) {
                findPythonExe(pythonRoot)?.let { exe ->
                    realCmd = cmd.replace(Regex("^python3?\\b"), "\"${exe.absolutePath}\"")
                }
            }
            val shCmd = buildString {
                append("cd \"${workingDir.absolutePath}\" && ")
                if (isPythonInstalled()) {
                    append("export PATH=\"${pythonBinDir.absolutePath}:\$PATH\"; ")
                    append("export PYTHONHOME=\"${pythonRoot.absolutePath}\"; ")
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
        } catch (e: Exception) { runOnUiThread { appendLine("执行失败: ${e.message}") } }
    }

    private fun isPythonInstalled() = File(pythonBinDir, "python3").let { it.exists() && it.canExecute() }

    private fun getSourceUrl() = getSharedPreferences("xdyl_settings", MODE_PRIVATE)
        .getString("python_download_url", "https://pan.vma.cc/pan/d/bb7ce82b195961c575901d8795ac4e78?ext=gz")!!

    private fun installPython() {
        try {
            appendLine("开始下载 Python 包...")
            val tarFile = File(filesDir, "python_android.tar.gz")
            val conn = (java.net.URL(getSourceUrl()).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 120000; instanceFollowRedirects = true
            }
            if (conn.responseCode != 200) { appendLine("下载失败: HTTP ${conn.responseCode}"); conn.disconnect(); return }
            conn.inputStream.use { input -> tarFile.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            appendLine("下载完成, 正在解压...")

            pythonRoot.deleteRecursively(); pythonRoot.mkdirs()
            // -o 不还原文件所有者（包内是 Termux uid，应用无 chown 权限）
            val p = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "tar -xozf \"${tarFile.absolutePath}\" -C \"${pythonRoot.absolutePath}\""))
            val err = p.errorStream.bufferedReader().readText(); p.waitFor()
            if (p.exitValue() != 0) { appendLine("解压失败: $err"); return }
            appendLine("解压完成, 正在定位 python3...")

            val pythonExe = findPythonExe(pythonRoot) ?: run {
                appendLine("错误: 包内未找到 python3 可执行文件")
                appendLine("请确保包结构为 bin/python3 或根目录直接含 python3")
                return
            }
            pythonBinDir.deleteRecursively(); pythonBinDir.mkdirs()
            val wrapper = "#!/system/bin/sh\nexec \"${pythonExe.absolutePath}\" \"\$@\"\n"
            for (name in listOf("python3", "python")) {
                File(pythonBinDir, name).apply { writeText(wrapper); setExecutable(true, false) }
            }

            // 强制修复权限：目录755、wrapper脚本755、可执行文件755、共享库755
            val fixPerms = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c",
                    "chmod 755 \"${pythonBinDir.absolutePath}\" " +
                    "\"${pythonBinDir.absolutePath}/python3\" " +
                    "\"${pythonBinDir.absolutePath}/python\" " +
                    "\"${pythonExe.absolutePath}\" && " +
                    "chmod -R 755 \"${pythonRoot.absolutePath}/bin\" && " +
                    "find \"${pythonRoot.absolutePath}\" -name '*.so' -exec chmod 755 {} \\; 2>/dev/null")
            )
            fixPerms.waitFor()
            tarFile.delete()
            appendLine("Python 安装完成!")
            appendLine("输入 python3 --version 验证")
        } catch (e: Exception) { appendLine("安装失败: ${e.message}") }
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

    private fun scrollToBottom() { scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) } }

    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}
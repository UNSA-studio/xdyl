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

/**
 * 隐藏终端：只能通过 ADB 广播解锁入口。
 * 以应用权限执行 shell 命令（无 root，权限受限）。
 */
class TerminalActivity : AppCompatActivity() {
    private lateinit var tvOutput: TextView
    private lateinit var etInput: EditText
    private lateinit var scrollView: ScrollView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var workingDir: File = File("/sdcard")
    private lateinit var pythonRoot: File
    private lateinit var pythonBinDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        tvOutput = findViewById(R.id.tvTerminalOutput)
        etInput = findViewById(R.id.etTerminalInput)
        scrollView = findViewById(R.id.terminalScrollView)
        tvOutput.movementMethod = ScrollingMovementMethod()

        pythonRoot = File(filesDir, "python_root")
        pythonBinDir = File(filesDir, "python_bin")
        pythonRoot.mkdirs()
        pythonBinDir.mkdirs()

        val btnBack = findViewById<ImageButton>(R.id.btnTerminalBack)
        btnBack.setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        appendLine("星云更新器隐藏终端")
        appendLine("以应用权限执行命令（无 root）")
        appendLine("输入 'exit' 退出本页面")
        appendLine("输入 'pysetup' 下载安装 Python")
        appendLine("输入 'pysetup <下载地址>' 从自定义地址安装")
        appendLine("输入 'pysetup status' 查看 Python 状态")
        appendLine("当前目录: ${workingDir.absolutePath}")
        appendLine("")

        val btnSend = findViewById<Button>(R.id.btnTerminalSend)
        btnSend.setOnClickListener { runCommand() }

        etInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                runCommand()
                true
            } else false
        }
    }

    private fun runCommand() {
        val cmd = etInput.text.toString().trim()
        if (cmd.isEmpty()) return
        etInput.setText("")
        appendLine("$ $cmd")

        if (cmd == "exit") {
            finish()
            return
        }

        if (cmd == "pysetup") {
            scope.launch(Dispatchers.IO) { installPython("https://unsa-fdws.cc.cd/api/download/python_android.tar.gz") }
            return
        }
        if (cmd == "pysetup status") {
            scope.launch(Dispatchers.IO) { checkPythonStatus() }
            return
        }
        if (cmd.startsWith("pysetup ")) {
            val url = cmd.removePrefix("pysetup ").trim()
            if (url.isEmpty()) {
                appendLine("用法: pysetup [下载地址]")
            } else {
                scope.launch(Dispatchers.IO) { installPython(url) }
            }
            return
        }

        // 处理 cd
        if (cmd.startsWith("cd ")) {
            val target = cmd.removePrefix("cd ").trim()
            val newDir = when (target) {
                "" -> File("/sdcard")
                else -> {
                    val f = if (target.startsWith("/")) File(target) else File(workingDir, target)
                    if (f.exists() && f.isDirectory) f else {
                        appendLine("cd: ${f.absolutePath}: 目录不存在")
                        return
                    }
                }
            }
            workingDir = newDir
            appendLine("当前目录: ${workingDir.absolutePath}")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val shCmd = buildString {
                    append("cd \"${workingDir.absolutePath}\" && ")
                    if (isPythonInstalled()) {
                        append("export PATH=\"${pythonBinDir.absolutePath}:$PATH\"; ")
                        append("export PYTHONHOME=\"${pythonRoot.absolutePath}\"; ")
                    }
                    append(cmd)
                }
                val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", shCmd))
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                process.waitFor()
                withContext(Dispatchers.Main) {
                    if (stdout.isNotEmpty()) appendLine(stdout.trimEnd())
                    if (stderr.isNotEmpty()) appendLine(stderr.trimEnd())
                    scrollToBottom()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    appendLine("执行失败: ${e.message}")
                }
            }
        }
    }

    private fun isPythonInstalled(): Boolean {
        val py = File(pythonBinDir, "python3")
        return py.exists() && py.canExecute()
    }

    private fun checkPythonStatus() {
        appendLine(if (isPythonInstalled()) "Python: 已安装" else "Python: 未安装（输入 pysetup 安装）")
    }

    private fun installPython(downloadUrl: String) {
        try {
            appendLine("开始下载 Python 运行时...")
            val tarFile = File(filesDir, "python_android.tar.gz")
            val conn = java.net.URL(downloadUrl).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) {
                appendLine("下载失败: HTTP ${conn.responseCode}")
                conn.disconnect()
                return
            }
            conn.inputStream.use { input ->
                tarFile.outputStream().use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            appendLine("下载完成，开始解压...")

            pythonRoot.deleteRecursively()
            pythonRoot.mkdirs()
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/tar", "-xzf", tarFile.absolutePath, "-C", pythonRoot.absolutePath))
            val err = process.errorStream.bufferedReader().readText()
            process.waitFor()
            if (process.exitValue() != 0) {
                appendLine("解压失败: ${err.ifBlank { "tar 可能不可用" }}")
                return
            }
            appendLine("解压完成，创建启动脚本...")

            val pythonExe = findPythonExe(pythonRoot)
            if (pythonExe == null) {
                appendLine("错误：压缩包内未找到 python3 可执行文件")
                appendLine("请确保包结构为 bin/python3 或直接包含 python3")
                return
            }

            pythonBinDir.deleteRecursively()
            pythonBinDir.mkdirs()
            val wrapperText = """#!/system/bin/sh
exec "${pythonExe.absolutePath}" "\$@"
""".trimIndent()
            for (name in listOf("python3", "python")) {
                val wrapper = File(pythonBinDir, name)
                wrapper.writeText(wrapperText)
                wrapper.setExecutable(true, false)
            }
            tarFile.delete()
            appendLine("Python 安装完成")
            appendLine("现在可以输入 python3 --version 验证")
        } catch (e: Exception) {
            appendLine("安装失败: ${e.message}")
        }
    }

    private fun findPythonExe(dir: File): File? {
        val candidates = listOf(
            File(dir, "bin/python3"),
            File(dir, "bin/python"),
            File(dir, "python3"),
            File(dir, "python")
        )
        for (f in candidates) {
            if (f.exists() && f.canExecute()) return f
        }
        return null
    }

    private fun appendLine(text: String) {
        tvOutput.append(text + "\n")
        scrollToBottom()
    }

    private fun scrollToBottom() {
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}

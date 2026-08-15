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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)

        tvOutput = findViewById(R.id.tvTerminalOutput)
        etInput = findViewById(R.id.etTerminalInput)
        scrollView = findViewById(R.id.terminalScrollView)
        tvOutput.movementMethod = ScrollingMovementMethod()

        val btnBack = findViewById<ImageButton>(R.id.btnTerminalBack)
        btnBack.setOnClickListener {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        appendLine("星云更新器隐藏终端")
        appendLine("以应用权限执行命令（无 root）")
        appendLine("输入 'exit' 退出本页面")
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
        appendLine("$ ${if (cmd.startsWith("cd ")) cmd else cmd}")

        if (cmd == "exit") {
            finish()
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
                val process = Runtime.getRuntime().exec(
                    arrayOf("/system/bin/sh", "-c", "cd \"${workingDir.absolutePath}\" && $cmd")
                )
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

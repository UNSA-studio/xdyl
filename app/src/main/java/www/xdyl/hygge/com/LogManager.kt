package www.xdyl.hygge.com

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object LogManager {
    private val logBuilder = StringBuilder()
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")!!

    init {
        log("LogManager initialized at ${LocalDateTime.now().format(formatter)}")
    }

    fun log(msg: String) {
        val timestamp = LocalDateTime.now().format(formatter)
        val entry = "[$timestamp] $msg"
        logBuilder.appendLine(entry)
        // 同步更新到 UI 的日志窗口（如果有的话）
        MainActivity.instance?.appendLog(entry)
    }

    fun getFullLog(): String = logBuilder.toString()

    fun clear() {
        logBuilder.clear()
    }
}

package www.xdyl.hygge.desktop

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object LogManager {
    private val logBuilder = StringBuilder()
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    init {
        log("LogManager initialized")
    }

    fun log(msg: String) {
        val timestamp = LocalDateTime.now().format(formatter)
        val entry = "[$timestamp] $msg"
        logBuilder.appendLine(entry)
    }

    fun getFullLog(): String = logBuilder.toString()

    fun clear() {
        logBuilder.clear()
        log("Log cleared")
    }
}

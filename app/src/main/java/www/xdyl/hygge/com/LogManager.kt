package www.xdyl.hygge.com

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object LogManager {
    private val logBuilder = StringBuilder()
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    init {
        writeEntry("LogManager initialized")
    }

    fun log(msg: String) {
        writeEntry(msg)
    }

    fun getFullLog(): String = logBuilder.toString()

    fun clear() {
        logBuilder.clear()
        writeEntry("Log cleared")
    }

    private fun writeEntry(msg: String) {
        val timestamp = LocalDateTime.now().format(formatter)
        logBuilder.appendLine("[$timestamp] $msg")
    }
}

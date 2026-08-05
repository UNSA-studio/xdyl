package www.xdyl.hygge.com

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

object LogManager {
    private const val TAG = "NebulaUpdater"
    private val logBuilder = StringBuilder()
    @Volatile var wifiAdbEnabled: Boolean = false

    fun log(msg: String) {
        Log.i(TAG, msg)
        logBuilder.appendLine("[${System.currentTimeMillis()}] $msg")
    }

    fun getFullLog(): String {
        val sb = StringBuilder()
        sb.appendLine("=== NebulaUpdater 应用日志 ===")
        sb.append(logBuilder.toString())
        if (wifiAdbEnabled) {
            sb.appendLine()
            sb.appendLine("=== 系统崩溃日志 (logcat) ===")
            sb.append(getSystemLog())
        }
        return sb.toString()
    }

    fun getSystemLog(): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "500"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = reader.readLines()
            process.waitFor()
            reader.close()
            lines.filter { line ->
                line.contains("FATAL") || line.contains("NebulaUpdater") || line.contains("xdyl")
            }.joinToString("\n").ifEmpty { "(无系统崩溃记录)" }
        } catch (e: Exception) {
            "(无法获取系统日志: ${e.message})"
        }
    }

    fun clear() {
        logBuilder.clear()
        Log.d(TAG, "Log cleared")
    }
}

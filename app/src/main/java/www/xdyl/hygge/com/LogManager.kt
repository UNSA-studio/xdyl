package www.xdyl.hygge.com

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

object LogManager {
    private const val TAG = "NebulaUpdater"
    private val logBuilder = StringBuilder()
    private val systemLogBuffer = StringBuilder()
    @Volatile var wifiAdbEnabled: Boolean = false
        set(value) {
            field = value
            if (value) startAutoCollect() else stopAutoCollect()
        }
    @Volatile var deviceIp: String? = null
    @Volatile var devicePort: Int = 5555
    private var collectorThread: Thread? = null

    fun log(msg: String) {
        Log.i(TAG, msg)
        logBuilder.appendLine("[${System.currentTimeMillis()}] $msg")
    }

    fun getFullLog(): String {
        val sb = StringBuilder()
        sb.appendLine("=== NebulaUpdater 应用日志 ===")
        if (wifiAdbEnabled && deviceIp != null) {
            sb.appendLine("目标设备: ${deviceIp}:${devicePort}")
        }
        sb.append(logBuilder.toString())
        if (wifiAdbEnabled && systemLogBuffer.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("=== 系统崩溃日志 (自动收集) ===")
            sb.append(systemLogBuffer.toString())
        }
        return sb.toString()
    }

    private fun startAutoCollect() {
        collectorThread?.interrupt()
        collectorThread = Thread {
            systemLogBuffer.clear()
            while (wifiAdbEnabled) {
                try {
                    val result = getSystemLog()
                    if (result.isNotBlank() && result != "(无系统崩溃记录)") {
                        systemLogBuffer.appendLine(result)
                        systemLogBuffer.appendLine("---")
                    }
                } catch (_: Exception) {}
                try { Thread.sleep(30_000) } catch (_: InterruptedException) { break }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
        Log.i(TAG, "系统日志自动收集已启动")
    }

    private fun stopAutoCollect() {
        collectorThread?.interrupt()
        collectorThread = null
        Log.i(TAG, "系统日志自动收集已停止")
    }

    fun getSystemLog(): String {
        if (wifiAdbEnabled && deviceIp != null) {
            try {
                val adbOutput = AdbClient.exec(deviceIp!!, devicePort, "logcat -d -v time -t 200")
                if (adbOutput.isNotBlank()) {
                    val filtered = adbOutput.lines().filter { line ->
                        line.contains("FATAL") || line.contains("NebulaUpdater") || line.contains("xdyl")
                    }
                    return if (filtered.isEmpty()) "(无系统崩溃记录)" else filtered.joinToString("\n")
                }
            } catch (e: Exception) {
                Log.d(TAG, "ADB 连接失败: ${e.message}，降级到本地 logcat")
            }
        }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "200"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = reader.readLines()
            process.waitFor()
            reader.close()
            val filtered = lines.filter { line ->
                line.contains("FATAL") || line.contains("NebulaUpdater") || line.contains("xdyl")
            }
            if (filtered.isEmpty()) "(无系统崩溃记录)" else filtered.joinToString("\n")
        } catch (e: Exception) {
            "(无法获取系统日志: ${e.message})"
        }
    }

    fun clear() {
        logBuilder.clear()
        systemLogBuffer.clear()
        Log.d(TAG, "Log cleared")
    }
}

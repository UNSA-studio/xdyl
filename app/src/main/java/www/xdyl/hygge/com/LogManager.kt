package www.xdyl.hygge.com

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

object LogManager {
    private const val TAG = "NebulaUpdater"
    private val logBuilder = StringBuilder()
    @Volatile var wifiAdbEnabled: Boolean = false
    @Volatile var deviceIp: String? = null
    @Volatile var devicePort: Int = 5555

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
        if (wifiAdbEnabled) {
            sb.appendLine()
            sb.appendLine("=== 系统崩溃日志 (logcat) ===")
            sb.append(getSystemLog())
        }
        return sb.toString()
    }

    fun getSystemLog(): String {
        // 如果配置了 WiFi ADB 设备，优先通过 ADB 协议获取日志
        if (wifiAdbEnabled && deviceIp != null) {
            try {
                val adbOutput = AdbClient.exec(deviceIp!!, devicePort, "logcat -d -v time -t 500")
                if (adbOutput.isNotBlank()) {
                    val filtered = adbOutput.lines().filter { line ->
                        line.contains("FATAL") || line.contains("NebulaUpdater") || line.contains("xdyl")
                    }
                    return if (filtered.isEmpty()) "(无系统崩溃记录)" else filtered.joinToString("\n")
                }
            } catch (e: Exception) {
                // ADB 连接失败，降级到本地 logcat
                Log.d(TAG, "ADB 连接失败: ${e.message}，降级到本地 logcat")
            }
        }
        // 降级：直接用 Runtime.exec 调用本地 logcat
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "500"))
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
        Log.d(TAG, "Log cleared")
    }
}

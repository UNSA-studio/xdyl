package www.xdyl.hygge.com

import dev.mobile.dadb.AdbClient
import dev.mobile.dadb.AdbKey
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ADB 客户端封装 — 基于 dadb 库
 * 本机调用 Runtime.exec，远程通过 ADB 协议连接
 */
object AdbClient {

    /** 执行 shell 命令，返回标准输出 */
    fun exec(host: String, port: Int, command: String): String {
        // 本机直接用 Runtime.exec，无需 ADB 连接
        if (host == "127.0.0.1" || host == "localhost" || host == "::1") {
            return execLocal(command)
        }

        // 远程设备通过 dadb ADB 客户端连接
        try {
            val adb = AdbClient.create(host, port)
            val keypair = AdbKey.generate()
            adb.connect(keypair) // 首次连接会弹出授权框
            val result = adb.execute(command)
            adb.close()
            return result
        } catch (e: Exception) {
            // 降级：尝试本地 logcat
            return execLocal(command)
        }
    }

    /** 本地直接执行 logcat */
    private fun execLocal(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "500"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = reader.readLines()
            process.waitFor()
            reader.close()
            lines.joinToString("\n")
        } catch (e: Exception) {
            ""
        }
    }
}
package www.xdyl.hygge.com

import dadb.Dadb
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * ADB 客户端封装 — 基于 dadb 库
 * 本机调用 Runtime.exec，远程通过 Dadb.create 连接
 */
object AdbClient {

    fun exec(host: String, port: Int, command: String): String {
        if (host == "127.0.0.1" || host == "localhost" || host == "::1") {
            return execLocal(command)
        }
        try {
            Dadb.create(host, port).use { dadb ->
                val response = dadb.shell(command)
                return response.output
            }
        } catch (e: Exception) {
            return execLocal(command)
        }
    }

    private fun execLocal(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "500"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = reader.readLines()
            process.waitFor()
            reader.close()
            lines.joinToString("\n")
        } catch (e: Exception) { "" }
    }
}
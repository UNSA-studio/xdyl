package www.xdyl.hygge.com

import android.util.Log
import dadb.Dadb
import java.io.BufferedReader
import java.io.InputStreamReader

object AdbClient {

    fun exec(host: String, port: Int, command: String): String {
        try {
            Log.d("AdbClient", "尝试 ADB 连接 $host:$port ...")
            Dadb.create(host, port).use { dadb ->
                Log.d("AdbClient", "ADB 已连接，执行: $command")
                val response = dadb.shell(command)
                Log.d("AdbClient", "ADB 命令完成, exitCode=${response.exitCode}")
                return response.output
            }
        } catch (e: Exception) {
            Log.e("AdbClient", "ADB 失败: ${e.javaClass.simpleName} - ${e.message}", e)
            return execLocal(command)
        }
    }

    private fun execLocal(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val lines = reader.readLines()
            process.waitFor()
            reader.close()
            lines.joinToString("\n")
        } catch (e: Exception) { "" }
    }
}
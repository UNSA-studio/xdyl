package www.xdyl.hygge.com

import dadb.Dadb
import java.io.BufferedReader
import java.io.InputStreamReader

object AdbClient {

    fun exec(host: String, port: Int, command: String): String {
        try {
            Dadb.create(host, port).use { dadb ->
                val response = dadb.shell(command)
                return response.output
            }
        } catch (e: Exception) {
            // ADB 失败则降级到本地 logcat
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
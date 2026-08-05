package www.xdyl.hygge.com

import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object AdbClient {

    /** 获取或生成 ADB 密钥对 */
    private fun getAdbKeyPair(): AdbKeyPair {
        val dir = File(android.os.Environment.getExternalStorageDirectory(), ".android")
        if (!dir.exists()) dir.mkdirs()
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub")
        // 如果密钥不存在，生成一对并写入文件
        if (!priv.exists() || !pub.exists()) {
            val pair = AdbKeyPair.generate(priv, pub)
            Log.i("AdbClient", "ADB 密钥已生成: ${priv.absolutePath}")
            return pair
        }
        return AdbKeyPair.read(priv, pub)
    }

    fun exec(host: String, port: Int, command: String): String {
        try {
            Log.d("AdbClient", "尝试 ADB 连接 $host:$port ...")
            val keyPair = getAdbKeyPair()
            Dadb.create(host, port, keyPair).use { dadb ->
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
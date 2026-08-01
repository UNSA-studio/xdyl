package www.xdyl.hygge.com

import android.util.Log
import java.io.PrintWriter
import java.net.Socket

object LogManager {
    private const val TAG = "NebulaUpdater"
    private val logBuilder = StringBuilder()
    @Volatile var remoteIp: String? = null
    @Volatile var remotePort: Int = 5555
    @Volatile var wifiAdbEnabled: Boolean = false

    fun log(msg: String) {
        Log.i(TAG, msg)
        logBuilder.appendLine("[${System.currentTimeMillis()}] $msg")
        if (wifiAdbEnabled) sendToRemote(msg)
    }

    fun getFullLog(): String = logBuilder.toString()

    fun clear() {
        logBuilder.clear()
        Log.d(TAG, "Log cleared")
    }

    private fun sendToRemote(msg: String) {
        val ip = remoteIp ?: return
        try {
            Thread {
                try {
                    Socket(ip, remotePort).use { socket ->
                        PrintWriter(socket.getOutputStream(), true).use { writer ->
                            writer.println("[NebulaUpdater] $msg")
                        }
                    }
                } catch (_: Exception) {}
            }.start()
        } catch (_: Exception) {}
    }
}

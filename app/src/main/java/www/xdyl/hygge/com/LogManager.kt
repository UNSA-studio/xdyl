package www.xdyl.hygge.com

import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

object LogManager {
    private const val TAG = "NebulaUpdater"
    private val logBuilder = StringBuilder()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var logFile: File? = null

    init {
        writeEntry("=== Device: ${Build.MANUFACTURER} ${Build.MODEL} | SDK ${Build.VERSION.SDK_INT} | ${Build.VERSION.RELEASE} ===")
    }

    fun initLogFile(dir: File) {
        logFile = File(dir, "nebula_updater_full_log_${System.currentTimeMillis()}.txt").also {
            it.parentFile?.mkdirs()
        }
        writeEntry("Log file initialized at ${logFile?.absolutePath}")
    }

    @Synchronized
    fun log(msg: String) {
        writeEntry(msg)
    }

    @JvmStatic
    fun getFullLog(): String = synchronized(logBuilder) { logBuilder.toString() }

    fun clear() {
        synchronized(logBuilder) {
            logBuilder.clear()
            writeEntry("Log cleared")
        }
    }

    fun recordCrash(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        writeEntry("!!! FATAL CRASH on ${thread.name} !!!\n${sw}")
    }

    fun recordNetworkRequest(url: String, code: Int, durationMs: Long) {
        writeEntry("[NET] $url -> $code (${durationMs}ms)")
    }

    fun recordNetworkError(url: String, error: String) {
        writeEntry("[NET ERROR] $url : $error")
    }

    private fun writeEntry(msg: String) {
        val timestamp = dateFormat.format(Date())
        val line = "[$timestamp] $msg"
        synchronized(logBuilder) {
            logBuilder.appendLine(line)
        }
        Log.d(TAG, line)
        logFile?.let { file ->
            try {
                FileWriter(file, true).use { it.write(line + "\n") }
            } catch (_: Exception) {}
        }
    }
}

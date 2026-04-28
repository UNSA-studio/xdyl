package www.xdyl.hygge.com

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import www.xdyl.hygge.com.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var targetModsDir: File? = null
    private var isProcessing = false
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private lateinit var prefs: SharedPreferences
    private val logBuilder = StringBuilder()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    companion object {
        var instance: MainActivity? = null
    }

    data class ModInfo(val fileName: String, val size: Long, val md5: String, val sha256: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        instance = this

        prefs = getSharedPreferences("xdyl_settings", MODE_PRIVATE)
        binding.tvLog.movementMethod = ScrollingMovementMethod()

        requestPermissionsIfNeeded()
        restoreLastDirectory()

        binding.btnSelectDir.setOnClickListener {
            it.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in))
            showFolderBrowser()
        }
        binding.btnStartDownload.setOnClickListener {
            it.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in))
            startUpdateProcess()
        }
        binding.btnSettings.setOnClickListener {
            it.animate().rotationBy(180f).setDuration(300).start()
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getBoolean("request_export_log", false)) {
            prefs.edit().putBoolean("request_export_log", false).apply()
            exportLogToFile()
        }
        if (prefs.getBoolean("trigger_green", false)) {
            prefs.edit().putBoolean("trigger_green", false).apply()
            activateGreenScreen()
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun restoreLastDirectory() {
        val lastPath = prefs.getString("launcher_root", null)
        if (lastPath != null) {
            val dir = File(lastPath)
            if (dir.exists() && dir.isDirectory) {
                val found = findMinecraftModsDir(dir)
                if (found != null) {
                    targetModsDir = found
                    binding.btnStartDownload.isEnabled = true
                    logGlobal("Auto-located mods: ${found.absolutePath}")
                    return
                }
            }
        }
    }

    // ================== 稳定内置文件浏览器（无Fragment） ==================
    private var currentDir: File = Environment.getExternalStorageDirectory()

    private fun showFolderBrowser() {
        currentDir = File(prefs.getString("launcher_root", Environment.getExternalStorageDirectory().absolutePath))
        showBottomSheetForDir(currentDir)
    }

    private fun showBottomSheetForDir(dir: File) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_file_browser, null)
        val tvPath = view.findViewById<TextView>(R.id.tvPath)
        val recycler = view.findViewById<RecyclerView>(R.id.recyclerView)
        tvPath.text = dir.absolutePath

        val files = dir.listFiles()?.toList()?.sortedWith(compareBy<File> { it.isDirectory }.thenBy { it.name }) ?: emptyList()
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val tv = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
                tv.setBackgroundColor(0xFF1E1E1E.toInt())
                tv.setTextColor(0xFFFFFFFF.toInt())
                return object : RecyclerView.ViewHolder(tv) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val file = files[position]
                val tv = holder.itemView as TextView
                tv.text = file.name
                holder.itemView.setOnClickListener {
                    if (file.isDirectory) {
                        currentDir = file
                        updateRecyclerForDir(dialog, recycler, tvPath, file)
                    }
                }
                holder.itemView.setOnLongClickListener {
                    // 长按选择当前文件夹
                    dialog.dismiss()
                    prefs.edit().putString("launcher_root", dir.absolutePath).apply()
                    handleSelectedFolder(dir)
                    true
                }
            }
            override fun getItemCount(): Int = files.size
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun updateRecyclerForDir(dialog: BottomSheetDialog, recycler: RecyclerView, tvPath: TextView, newDir: File) {
        tvPath.text = newDir.absolutePath
        val files = newDir.listFiles()?.toList()?.sortedWith(compareBy<File> { it.isDirectory }.thenBy { it.name }) ?: emptyList()
        (recycler.adapter as? RecyclerView.Adapter<*>)?.let { adapter ->
            // 简单刷新，无动画
            recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val tv = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
                    tv.setBackgroundColor(0xFF1E1E1E.toInt())
                    tv.setTextColor(0xFFFFFFFF.toInt())
                    return object : RecyclerView.ViewHolder(tv) {}
                }
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val file = files[position]
                    val tv = holder.itemView as TextView
                    tv.text = file.name
                    holder.itemView.setOnClickListener {
                        if (file.isDirectory) {
                            currentDir = file
                            updateRecyclerForDir(dialog, recycler, tvPath, file)
                        }
                    }
                    holder.itemView.setOnLongClickListener {
                        dialog.dismiss()
                        prefs.edit().putString("launcher_root", newDir.absolutePath).apply()
                        handleSelectedFolder(newDir)
                        true
                    }
                }
                override fun getItemCount(): Int = files.size
            }
        }
    }

    private fun handleSelectedFolder(folder: File) {
        val modsDir = findMinecraftModsDir(folder)
        if (modsDir != null) {
            targetModsDir = modsDir
            binding.btnStartDownload.isEnabled = true
            logGlobal("Mods directory set to ${modsDir.absolutePath}")
            Toast.makeText(this, "游戏目录已选择", Toast.LENGTH_SHORT).show()
        } else {
            showError(Constants.ERROR01)
        }
    }

    private fun findMinecraftModsDir(launcherRoot: File): File? {
        val mc = File(launcherRoot, ".minecraft")
        val mcAlt = File(launcherRoot, "minecraft")
        val minecraftDir = when {
            mc.exists() -> mc
            mcAlt.exists() -> mcAlt
            else -> return null
        }
        val versionsDir = File(minecraftDir, "versions")
        if (!versionsDir.exists()) return null
        val targetVersion = prefs.getString("version_folder", Constants.TARGET_VERSION_DIR) ?: Constants.TARGET_VERSION_DIR
        val targetDir = File(versionsDir, targetVersion)
        if (!targetDir.exists()) return null
        val modsDir = File(targetDir, "mods")
        if (!modsDir.exists()) modsDir.mkdirs()
        return modsDir
    }
    // ================== 内置浏览器结束 ==================

    // 其余部分与原来一致（showError, download, log, export, greenScreen等）
    // 为节省篇幅，这里省略，但实际文件必须包含以下方法：

    private fun showError(errorCode: String) {
        logGlobal("Error: $errorCode")
        MaterialAlertDialogBuilder(this)
            .setTitle("Error")
            .setMessage("Error code: $errorCode\nPlease check your settings or contact developer.")
            .setPositiveButton("OK", null)
            .show()
    }

    private suspend fun fetchServerFileList(): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(Constants.BASE_URL).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            logGlobal("Server response: HTTP ${response.code}")
            if (response.code != 200) return@withContext emptyList()
            val pattern = Pattern.compile("<a href=\"([^\"]+)\">")
            val matcher = pattern.matcher(body)
            val files = mutableListOf<String>()
            while (matcher.find()) {
                val link = matcher.group(1)
                if (link != null && link.endsWith(".jar")) files.add(link)
            }
            logGlobal("Found ${files.size} .jar files on server")
            files
        } catch (e: Exception) {
            logGlobal("Failed to fetch server file list: ${e.message}")
            emptyList()
        }
    }

    private fun parseCsvMods(csv: String): List<ModInfo> {
        return csv.lines().drop(1).filter { it.isNotBlank() }.map { line ->
            val parts = line.split(",")
            ModInfo(
                fileName = parts[0].trim('"').removePrefix("./"),
                size = parts[2].toLong(),
                md5 = parts[3].trim('"'),
                sha256 = parts[4].trim('"')
            )
        }
    }

    private suspend fun downloadWithRetry(url: String, size: Long, destFile: File, maxRetries: Int = 5) {
        var lastEx: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                val start = System.currentTimeMillis()
                DownloadManager(url, size, 1, useRange = false).download(destFile) { }
                val elapsed = System.currentTimeMillis() - start
                logGlobal("${destFile.name} downloaded in ${elapsed}ms")
                return
            } catch (e: Exception) {
                lastEx = e
                logGlobal("${destFile.name} attempt $attempt failed: ${e.message}")
                delay((1000L * attempt).coerceAtMost(5000))
            }
        }
        throw lastEx!!
    }

    private fun startUpdateProcess() {
        if (isProcessing) return
        val modsDir = targetModsDir ?: run { showError(Constants.ERROR01); return }
        isProcessing = true
        binding.btnStartDownload.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        logBuilder.clear()
        binding.tvLog.text = ""
        appendLog("Starting download...")

        val threadCount = prefs.getInt("thread_count", 20).coerceIn(20, 128)
        scope.launch {
            try {
                val serverFiles = fetchServerFileList()
                if (serverFiles.isEmpty()) { showError(Constants.ERROR01); return@launch }
                val csvMods = parseCsvMods(Constants.CSV_CONTENT)
                val csvSet = csvMods.map { it.fileName }.toSet()
                val toDownload = serverFiles.filter { csvSet.contains(it) }
                if (toDownload.isEmpty()) { showError(Constants.ERROR01); return@launch }

                val sem = Semaphore(threadCount)
                val failed = AtomicInteger(0)
                var completed = 0
                val total = toDownload.size

                withContext(Dispatchers.IO) {
                    toDownload.map { name ->
                        launch {
                            sem.acquire()
                            try {
                                val mod = csvMods.first { it.fileName == name }
                                val file = File(modsDir, name)
                                appendLog("[${completed+1}/$total] $name")
                                downloadWithRetry(Constants.BASE_URL + name, mod.size, file)
                                if (!FileVerifier().verifyFile(file, mod.md5, mod.sha256))
                                    throw RuntimeException("Checksum mismatch")
                                completed++
                                withContext(Dispatchers.Main) {
                                    binding.progressBar.progress = (completed * 100) / total
                                    binding.tvStatus.text = "$completed/$total"
                                }
                            } catch (e: Exception) {
                                logGlobal("Failed $name: ${e.message}")
                                failed.incrementAndGet()
                            } finally {
                                sem.release()
                            }
                        }
                    }.joinAll()
                }

                if (failed.get() > 0) {
                    showError(Constants.ERROR05)
                } else {
                    appendLog("All mods updated!")
                    Toast.makeText(this@MainActivity, "Update completed!", Toast.LENGTH_LONG).show()
                    binding.progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                showError(Constants.ERROR03)
            } finally {
                isProcessing = false
                binding.btnStartDownload.isEnabled = true
            }
        }
    }

    fun appendLog(msg: String) {
        logBuilder.appendLine(msg)
        runOnUiThread {
            binding.tvLog.text = logBuilder.toString()
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun logGlobal(msg: String) {
        LogManager.log(msg)
    }

    private fun exportLogToFile() {
        try {
            val log = LogManager.getFullLog()
            if (log.isEmpty()) {
                Toast.makeText(this, "No log to export", Toast.LENGTH_SHORT).show()
                return
            }
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, "mod_update_log_${System.currentTimeMillis()}.txt")
            FileOutputStream(file).use { it.write(log.toByteArray()) }
            Toast.makeText(this, "Log exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun activateGreenScreen() {
        binding.greenOverlay.visibility = View.VISIBLE
        onBackPressedDispatcher.addCallback(this) { }
    }

    override fun onDestroy() {
        instance = null
        job.cancel()
        super.onDestroy()
    }
}

package www.xdyl.hygge.com

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    private var easterEggCounter = 0
    private var lastClickTime = 0L
    private val CLICK_INTERVAL = 600L
    private var lastToast: Toast? = null

    companion object {
        var instance: MainActivity? = null
    }

    data class ModInfo(val fileName: String, val size: Long, val md5: String, val sha256: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogManager.log("MainActivity onCreate")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        instance = this

        prefs = getSharedPreferences("xdyl_settings", MODE_PRIVATE)
        binding.tvLog.movementMethod = ScrollingMovementMethod()

        requestPermissionsIfNeeded()
        restoreLastDirectory()
        setupEasterEggTrigger()

        binding.btnSelectDir.setOnClickListener {
            LogManager.log("User tapped 'Select Game Directory'")
            it.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in))
            showFileBrowser()
        }
        binding.btnStartDownload.setOnClickListener {
            LogManager.log("User tapped 'Start Download'")
            it.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in))
            if (prefs.getBoolean("neoforge_check_enabled", false)) {
                verifyNeoforgeVersion { verified ->
                    if (verified) startUpdateProcess()
                    else {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("NeoForge 版本过低")
                            .setMessage("需要更新 NeoForge 驱动至 21.1.227 或更高版本。")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }
            } else {
                startUpdateProcess()
            }
        }
        binding.btnSettings.setOnClickListener {
            LogManager.log("User tapped Settings button")
            it.animate().rotationBy(180f).setDuration(300).start()
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }

    override fun onResume() {
        super.onResume()
        LogManager.log("MainActivity onResume")
        if (prefs.getBoolean("request_export_log", false)) {
            prefs.edit().putBoolean("request_export_log", false).apply()
            exportLogToFile()
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                LogManager.log("Requesting MANAGE_EXTERNAL_STORAGE permission")
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }
    }

    private fun restoreLastDirectory() {
        val lastPath = prefs.getString("launcher_root", null)
        if (lastPath != null) {
            LogManager.log("Attempting to restore launcher root: $lastPath")
            val dir = File(lastPath)
            if (dir.exists() && dir.isDirectory) {
                val found = findMinecraftModsDir(dir)
                if (found != null) {
                    targetModsDir = found
                    binding.btnStartDownload.isEnabled = true
                    LogManager.log("Auto-located mods directory: ${found.absolutePath}")
                    return
                }
            }
        }
        LogManager.log("No valid launcher root restored, user must select manually")
    }

    private fun setupEasterEggTrigger() {
        binding.tvTitleSuffix.setOnClickListener {
            handleEasterEggClick()
        }
        LogManager.log("Easter egg trigger set on 'Android' text")
    }

    private fun handleEasterEggClick() {
        val now = System.currentTimeMillis()
        if (now - lastClickTime < CLICK_INTERVAL) {
            easterEggCounter++
        } else {
            easterEggCounter = 1
        }
        lastClickTime = now

        LogManager.log("Easter egg clicked, counter = $easterEggCounter")
        lastToast?.cancel()
        if (easterEggCounter == 7) {
            lastToast = Toast.makeText(this, "你不会以为真有开发者模式吧?", Toast.LENGTH_SHORT)
            lastToast?.show()
            LogManager.log("Easter egg hint displayed")
            easterEggCounter = 0
        } else if (easterEggCounter >= 15) {
            lastToast = Toast.makeText(this, "开发者模式已打开!", Toast.LENGTH_SHORT)
            lastToast?.show()
            LogManager.log("Opening extension page")
            easterEggCounter = 0
            startActivity(Intent(this, EasterEggActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }

    // ===== 文件浏览器 =====
    private var currentBrowseDir: File = Environment.getExternalStorageDirectory()

    private fun showFileBrowser() {
        currentBrowseDir = File(prefs.getString("launcher_root", Environment.getExternalStorageDirectory().absolutePath))
        LogManager.log("Opening file browser at ${currentBrowseDir.absolutePath}")
        browseDirectory(currentBrowseDir)
    }

    private fun browseDirectory(dir: File) {
        if (!dir.exists() || !dir.isDirectory) {
            Toast.makeText(this, "无效的文件夹", Toast.LENGTH_SHORT).show()
            return
        }
        val files = dir.listFiles()?.toList()?.sortedWith(compareBy<File> { it.isDirectory }.thenBy { it.name }) ?: emptyList()
        val names = files.map { it.name }.toTypedArray()

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(dir.absolutePath)
            .setItems(names) { _, which ->
                val selected = files[which]
                if (selected.isDirectory) {
                    LogManager.log("Navigated into: ${selected.name}")
                    currentBrowseDir = selected
                    browseDirectory(selected)
                }
            }
            .setPositiveButton("选择此文件夹") { _, _ ->
                LogManager.log("User selected launcher root: ${dir.absolutePath}")
                prefs.edit().putString("launcher_root", dir.absolutePath).apply()
                handleSelectedFolder(dir)
            }

        val root = Environment.getExternalStorageDirectory()
        if (dir.absolutePath != root.absolutePath) {
            builder.setNegativeButton("返回上级") { _, _ ->
                val parent = dir.parentFile
                if (parent != null) {
                    LogManager.log("Navigated up to: ${parent.name}")
                    currentBrowseDir = parent
                    browseDirectory(parent)
                }
            }
        }
        builder.show()
    }

    private fun handleSelectedFolder(folder: File) {
        val modsDir = findMinecraftModsDir(folder)
        if (modsDir != null) {
            targetModsDir = modsDir
            binding.btnStartDownload.isEnabled = true
            LogManager.log("Game directory set to ${modsDir.absolutePath}")
            Toast.makeText(this, "游戏目录已选择", Toast.LENGTH_SHORT).show()
        } else {
            LogManager.log("Failed to find mods directory in selected folder")
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

    private fun showError(errorCode: String) {
        LogManager.log("Error shown: $errorCode")
        MaterialAlertDialogBuilder(this)
            .setTitle("Error")
            .setMessage("Error code: $errorCode")
            .setPositiveButton("OK", null)
            .show()
    }

    // ===== NeoForge 版本检查 =====
    private fun verifyNeoforgeVersion(callback: (Boolean) -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val targetVersion = prefs.getString("version_folder", Constants.TARGET_VERSION_DIR) ?: Constants.TARGET_VERSION_DIR
                    val launcherRoot = prefs.getString("launcher_root", Environment.getExternalStorageDirectory().absolutePath)
                    val mc = findMinecraftDir(File(launcherRoot)) ?: return@withContext false
                    val versionsDir = File(mc, "versions")
                    val versionDir = File(versionsDir, targetVersion)
                    if (!versionDir.exists()) return@withContext false
                    val neoforgeJars = versionDir.listFiles()?.filter { it.nameWithoutExtension.startsWith("neoforge-", true) } ?: emptyList()
                    val versionPattern = Regex("neoforge-(\\d+\\.\\d+\\.\\d+)")
                    var installedVersion = ""
                    for (jar in neoforgeJars) {
                        val match = versionPattern.find(jar.name)
                        if (match != null) { installedVersion = match.groupValues[1]; break }
                    }
                    if (installedVersion.isEmpty()) return@withContext false
                    compareVersion(installedVersion, "21.1.227") >= 0
                } catch (e: Exception) {
                    false
                }
            }
            callback(result)
        }
    }

    private fun findMinecraftDir(start: File): File? {
        val mc = File(start, ".minecraft")
        if (mc.exists()) return mc
        val mcAlt = File(start, "minecraft")
        return if (mcAlt.exists()) mcAlt else null
    }

    private fun compareVersion(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val a = parts1.getOrElse(i) { 0 }
            val b = parts2.getOrElse(i) { 0 }
            if (a != b) return a - b
        }
        return 0
    }

    // ===== 网络与下载 =====
    private suspend fun fetchServerFileList(): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(Constants.BASE_URL).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            LogManager.log("Server response: HTTP ${response.code}")
            if (response.code != 200) return@withContext emptyList()
            val pattern = Pattern.compile("<a href=\"([^\"]+)\">")
            val matcher = pattern.matcher(body)
            val files = mutableListOf<String>()
            while (matcher.find()) {
                val link = matcher.group(1)
                if (link != null && link.endsWith(".jar")) files.add(link)
            }
            LogManager.log("Found ${files.size} .jar files on server")
            files
        } catch (e: Exception) {
            LogManager.log("Failed to fetch server file list: ${e.message}")
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
                LogManager.log("${destFile.name} downloaded in ${elapsed}ms")
                return
            } catch (e: Exception) {
                lastEx = e
                LogManager.log("${destFile.name} attempt $attempt failed: ${e.message}")
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

        val threadCount = prefs.getInt("thread_limit", prefs.getInt("thread_count", 256)).coerceIn(1, 1024)
        LogManager.log("Update started with $threadCount threads")
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
                                LogManager.log("Failed $name: ${e.message}")
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
                    LogManager.log("Update finished successfully")
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
            LogManager.log("Log exported to ${file.absolutePath}")
            Toast.makeText(this, "Log exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        LogManager.log("MainActivity onDestroy")
        instance = null
        job.cancel()
        super.onDestroy()
    }
}

package www.xdyl.hygge.com

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
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
    private var selectedBaseUri: Uri? = null
    private var targetModsDir: File? = null
    private var isProcessing = false
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private lateinit var prefs: SharedPreferences
    private val logBuilder = StringBuilder()
    private val client = OkHttpClient()

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

        binding.btnSelectDir.setOnClickListener {
            it.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in))
            openBuiltInFileBrowser()
        }
        binding.btnStartDownload.setOnClickListener {
            it.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in))
            startUpdateProcess()
        }
        binding.btnSettings.setOnClickListener {
            it.animate().rotationBy(180f).setDuration(300).start()
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
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
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        } else {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                100
            )
        }
    }

    // ================== 内置文件浏览器 ==================
    private var currentBrowseDoc: DocumentFile? = null
    private var currentBrowseUri: Uri? = null

    private fun openBuiltInFileBrowser() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        browserRootLauncher.launch(intent)
    }

    private val browserRootLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            currentBrowseUri = uri
            currentBrowseDoc = DocumentFile.fromTreeUri(this, uri)
            // 尝试自动进入 .minecraft/versions
            autoNavigateToVersions()
        }
    }

    private fun autoNavigateToVersions() {
        val doc = currentBrowseDoc ?: return
        val mc = doc.findFile(".minecraft") ?: doc.findFile("minecraft")
        if (mc != null) {
            val versions = mc.listFiles().find { it.name == "versions" }
            if (versions != null) {
                showVersionPicker(versions)
                return
            }
        }
        // 如果没有找到，则显示当前目录内容，让用户手动导航
        showDirectoryBrowser(doc)
    }

    private fun showVersionPicker(versionsDoc: DocumentFile) {
        val dirs = versionsDoc.listFiles().filter { it.isDirectory }
        if (dirs.isEmpty()) {
            Toast.makeText(this, "未找到任何版本文件夹", Toast.LENGTH_SHORT).show()
            return
        }
        val names = dirs.map { it.name ?: "未知" }
        AlertDialog.Builder(this)
            .setTitle("选择版本文件夹")
            .setItems(names.toTypedArray()) { _, which ->
                val chosen = dirs[which]
                val modsDir = chosen.listFiles().find { it.name == "mods" }
                    ?: chosen.createDirectory("mods")
                val rawPath = modsDir?.uri?.path?.let {
                    it.substring(it.indexOf("/tree/") + 6).let { p -> "/storage/emulated/0/$p" }
                }
                if (rawPath != null) {
                    val file = File(rawPath)
                    if (!file.exists()) file.mkdirs()
                    targetModsDir = file
                    selectedBaseUri = chosen.uri
                    binding.btnStartDownload.isEnabled = true
                    log("Game dir selected: ${file.absolutePath}")
                    Toast.makeText(this, "已选择游戏目录", Toast.LENGTH_SHORT).show()
                } else {
                    showError(Constants.ERROR01)
                }
            }
            .setNeutralButton("返回上级") { _, _ ->
                currentBrowseDoc = versionsDoc.parentFile
                currentBrowseDoc?.let { showDirectoryBrowser(it) }
            }
            .show()
    }

    private fun showDirectoryBrowser(doc: DocumentFile) {
        val items = doc.listFiles().map {
            val icon = if (it.isDirectory) "📁" else "📄"
            "$icon ${it.name}"
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "此目录为空", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("浏览文件夹")
            .setItems(items.toTypedArray()) { _, which ->
                val selected = doc.listFiles()[which]
                if (selected.isDirectory) {
                    currentBrowseDoc = selected
                    showDirectoryBrowser(selected)
                }
            }
            .setNegativeButton("返回") { _, _ ->
                currentBrowseDoc = doc.parentFile
                currentBrowseDoc?.let { showDirectoryBrowser(it) }
            }
            .setNeutralButton("选择此目录") { _, _ ->
                val mc = doc.findFile(".minecraft") ?: doc.findFile("minecraft")
                if (mc != null) {
                    val versions = mc.listFiles().find { it.name == "versions" }
                    if (versions != null) {
                        showVersionPicker(versions)
                        return@setNeutralButton
                    }
                }
                Toast.makeText(this, "未找到 .minecraft/versions 结构", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
    // ================== 内置浏览器结束 ==================

    private fun showError(errorCode: String) {
        log("Error: $errorCode")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("意外错误!")
            .setMessage("错误码: $errorCode\n请查看是否是您的问题,如不是,请联系开发者")
            .setPositiveButton("确定", null)
            .show()
    }

    private suspend fun fetchServerFileList(): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(Constants.BASE_URL).build()
            val response = client.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext emptyList()
            val pattern = Pattern.compile("<a href=\"([^\"]+)\">")
            val matcher = pattern.matcher(html)
            val files = mutableListOf<String>()
            while (matcher.find()) {
                val link = matcher.group(1)
                if (link != null && link.endsWith(".jar")) {
                    files.add(link)
                }
            }
            log("Server file count: ${files.size}")
            files
        } catch (e: Exception) {
            log("Failed to fetch server file list: ${e.message}")
            emptyList()
        }
    }

    private fun parseCsvMods(csv: String): List<ModInfo> {
        val lines = csv.lines().drop(1).filter { it.isNotBlank() }
        return lines.map { line ->
            val parts = line.split(",")
            val fileName = parts[0].trim('"').removePrefix("./")
            val size = parts[2].toLong()
            val md5 = parts[3].trim('"')
            val sha256 = parts[4].trim('"')
            ModInfo(fileName, size, md5, sha256)
        }
    }

    private suspend fun downloadWithRetry(
        url: String,
        size: Long,
        destFile: File,
        maxRetries: Int = 5
    ) {
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                val manager = DownloadManager(url, size, 1, useRange = false)
                manager.download(destFile) { /* progress handled by caller */ }
                return
            } catch (e: Exception) {
                lastException = e
                log("Retry $attempt/$maxRetries for ${destFile.name}: ${e.message}")
                delay((1000L * attempt).coerceAtMost(5000))
            }
        }
        throw lastException ?: RuntimeException("Download failed after $maxRetries retries")
    }

    private fun startUpdateProcess() {
        if (isProcessing) {
            Toast.makeText(this, "正在处理中，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        val modsDir = targetModsDir ?: run {
            showError(Constants.ERROR01)
            return
        }
        isProcessing = true
        binding.btnStartDownload.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        logBuilder.clear()
        binding.tvLog.text = ""
        log("=== Starting update ===")

        val threadCount = prefs.getInt("thread_count", 20).coerceIn(20, 128)
        log("Using $threadCount concurrent threads")

        scope.launch {
            try {
                val serverFiles = fetchServerFileList()
                if (serverFiles.isEmpty()) {
                    showError(Constants.ERROR01)
                    return@launch
                }
                val csvMods = parseCsvMods(Constants.CSV_CONTENT)
                val csvSet = csvMods.map { it.fileName }.toSet()
                val toDownload = serverFiles.filter { csvSet.contains(it) }
                if (toDownload.isEmpty()) {
                    log("No files to download (CSV mismatch)")
                    showError(Constants.ERROR01)
                    return@launch
                }

                val sem = Semaphore(threadCount)
                val failed = AtomicInteger(0)
                var completed = 0
                val total = toDownload.size

                withContext(Dispatchers.IO) {
                    val jobs = toDownload.map { name ->
                        launch {
                            sem.acquire()
                            try {
                                val mod = csvMods.first { it.fileName == name }
                                val file = File(modsDir, name)
                                log("[${completed+1}/$total] Downloading $name (${mod.size} bytes)")
                                downloadWithRetry(Constants.BASE_URL + name, mod.size, file)
                                val verifier = FileVerifier()
                                if (!verifier.verifyFile(file, mod.md5, mod.sha256)) {
                                    throw RuntimeException("Checksum failed: $name")
                                }
                                completed++
                                withContext(Dispatchers.Main) {
                                    binding.progressBar.progress = (completed * 100) / total
                                    binding.tvStatus.text = "$completed/$total"
                                }
                                log("$name done")
                            } catch (e: Exception) {
                                log("Failed $name: ${e.message}")
                                failed.incrementAndGet()
                            } finally {
                                sem.release()
                            }
                        }
                    }
                    jobs.joinAll()
                }

                if (failed.get() > 0) {
                    showError(Constants.ERROR05)
                } else {
                    withContext(Dispatchers.Main) {
                        log("All mods updated successfully!")
                        Toast.makeText(this@MainActivity, "模组已经更新完成!", Toast.LENGTH_LONG).show()
                        binding.tvStatus.text = "完成"
                        binding.progressBar.visibility = View.GONE
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                showError(Constants.ERROR03)
            } catch (e: Exception) {
                if (e.message?.contains("Permission") == true) showError(Constants.ERROR02)
                else showError(Constants.ERROR01)
                log("Exception: ${e.message}")
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

    private fun log(msg: String) {
        LogManager.log(msg)
    }

    private fun exportLogToFile() {
        try {
            val log = LogManager.getFullLog()
            if (log.isEmpty()) {
                Toast.makeText(this, "暂无日志可导出", Toast.LENGTH_SHORT).show()
                return
            }
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val fileName = "mod_update_log_${System.currentTimeMillis()}.txt"
            val file = File(downloadsDir, fileName)
            FileOutputStream(file).use { it.write(log.toByteArray()) }
            Toast.makeText(this, "日志已导出至 ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun activateGreenScreen() {
        binding.greenOverlay.visibility = View.VISIBLE
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 屏蔽返回
            }
        })
        Toast.makeText(this, "你为啥要点呢？", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        instance = null
        job.cancel()
        super.onDestroy()
    }
}

package www.xdyl.hygge.com

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

        if (!hasStoragePermission()) {
            requestStoragePermission()
        } else {
            initAfterPermission()
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            startActivity(intent)
            Toast.makeText(this, "请授予“所有文件访问权限”后重启应用", Toast.LENGTH_LONG).show()
        } else {
            requestPermissions(
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                100
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            initAfterPermission()
        } else {
            Toast.makeText(this, "需要存储权限才能运行", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasStoragePermission() && targetModsDir == null) {
            initAfterPermission()
        }
        if (prefs.getBoolean("request_export_log", false)) {
            prefs.edit().putBoolean("request_export_log", false).apply()
            exportLogToFile()
        }
        if (prefs.getBoolean("trigger_green", false)) {
            prefs.edit().putBoolean("trigger_green", false).apply()
            activateGreenScreen()
        }
    }

    private fun initAfterPermission() {
        if (!hasStoragePermission()) return

        binding.btnSelectDir.setOnClickListener { showManualBrowser() }
        binding.btnStartDownload.setOnClickListener { startUpdateProcess() }
        binding.btnSettings.setOnClickListener {
            it.animate().rotationBy(180f).setDuration(300).start()
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }

        // 尝试自动搜索或从持久化路径加载
        val savedPath = prefs.getString("mods_dir_path", null)
        if (savedPath != null) {
            val dir = File(savedPath)
            if (dir.exists() && dir.isDirectory) {
                targetModsDir = dir
                binding.btnStartDownload.isEnabled = true
                log("Loaded previous mods dir: ${dir.absolutePath}")
                return
            }
        }

        autoSearchGameDir()
    }

    private fun autoSearchGameDir() {
        scope.launch(Dispatchers.IO) {
            val possibleBases = listOf(
                "/storage/emulated/0",
                "/storage/emulated/0/Android/data",
                "/storage/emulated/0/games",
                "/storage/emulated/0/Download"
            )
            val patterns = listOf(
                ".minecraft/versions/%s/mods",
                "games/.minecraft/versions/%s/mods",
                "Android/data/com.mojang.minecraftpe/files/games/com.mojang/.minecraft/versions/%s/mods"
            )
            val versionFolder = getVersionFolderName()
            val found = mutableListOf<File>()

            for (base in possibleBases) {
                for (pattern in patterns) {
                    val full = File(base, pattern.replace("%s", versionFolder))
                    if (full.exists() && full.isDirectory) {
                        found.add(full)
                    }
                }
                // 也直接搜索 .minecraft 目录
                val dirs = File(base).listFiles { f -> f.isDirectory && f.name == ".minecraft" }
                dirs?.forEach { mc ->
                    val ver = File(mc, "versions/$versionFolder/mods")
                    if (ver.exists() && ver.isDirectory) found.add(ver)
                }
            }

            withContext(Dispatchers.Main) {
                if (found.isNotEmpty()) {
                    showFolderChoiceDialog(found.distinct().toTypedArray())
                } else {
                    Toast.makeText(this@MainActivity, "未自动找到游戏目录，请手动浏览", Toast.LENGTH_LONG).show()
                    showManualBrowser()
                }
            }
        }
    }

    private fun showFolderChoiceDialog(dirs: Array<File>) {
        val names = dirs.map { it.absolutePath }.toTypedArray()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("选择游戏版本 Mods 文件夹")
            .setItems(names) { _, i ->
                setModsDir(dirs[i])
            }
            .setNegativeButton("手动浏览") { _, _ -> showManualBrowser() }
            .show()
    }

    private fun setModsDir(dir: File) {
        targetModsDir = dir
        prefs.edit().putString("mods_dir_path", dir.absolutePath).apply()
        binding.btnStartDownload.isEnabled = true
        log("Mods directory set to: ${dir.absolutePath}")
        Toast.makeText(this, "已设置目录", Toast.LENGTH_SHORT).show()
    }

    // 手动文件夹浏览器
    private var currentBrowseDir = Environment.getExternalStorageDirectory()
    private fun showManualBrowser() {
        browseDir(currentBrowseDir)
    }

    private fun browseDir(dir: File) {
        try {
            val subs = dir.listFiles { f -> f.isDirectory } ?: emptyArray()
            val items = mutableListOf("[选择此文件夹]")
            items.addAll(subs.map { it.name })
            currentBrowseDir = dir
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(dir.absolutePath)
                .setItems(items.toTypedArray()) { _, which ->
                    if (which == 0) {
                        // 用户选择当前文件夹作为游戏根目录或直接识别
                        handleSelectedFolder(dir)
                    } else {
                        val selected = subs[which - 1]
                        browseDir(selected)
                    }
                }
                .setNegativeButton("返回上级") { _, _ ->
                    val parent = dir.parentFile
                    if (parent != null && parent.canRead()) {
                        browseDir(parent)
                    }
                }
                .setPositiveButton("取消", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法访问此文件夹", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSelectedFolder(dir: File) {
        // 如果选的文件夹里面直接有 mods 子文件夹，且其父级看起来像版本文件夹，则直接使用
        val modsDir = File(dir, "mods")
        if (modsDir.exists() && modsDir.isDirectory) {
            setModsDir(modsDir)
            return
        }
        // 否则尝试寻找 versions/xxx/mods
        val versionFolder = getVersionFolderName()
        val possible = File(dir, "versions/$versionFolder/mods")
        if (possible.exists() && possible.isDirectory) {
            setModsDir(possible)
            return
        }
        // 如果都没找到，就让用户继续浏览
        Toast.makeText(this, "未找到目标 mods 文件夹，请继续浏览或选择 .minecraft 文件夹", Toast.LENGTH_LONG).show()
        browseDir(dir)
    }

    // 下载相关函数（保持不变）
    private fun getVersionFolderName(): String {
        return prefs.getString("version_folder", Constants.TARGET_VERSION_DIR) ?: Constants.TARGET_VERSION_DIR
    }

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
        maxRetries: Int = 3
    ) {
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                val manager = DownloadManager(url, size, 1, useRange = false)
                manager.download(destFile) { /* optional progress */ }
                return
            } catch (e: Exception) {
                lastException = e
                log("Retry $attempt/$maxRetries for ${destFile.name}: ${e.message}")
                delay(500)
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
        log("Fetching server file list...")

        val threadCount = prefs.getInt("thread_count", 20).coerceIn(20, 128)

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
                                log("Downloading $name (${mod.size} bytes)")
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

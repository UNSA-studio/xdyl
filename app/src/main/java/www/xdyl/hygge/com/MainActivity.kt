package www.xdyl.hygge.com

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.addCallback
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
            showFileBrowser()
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
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
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
                    log("已保存的启动器目录: $lastPath")
                    log("自动定位到 mods: ${found.absolutePath}")
                    return
                } else {
                    log("上次选择的启动器目录中未找到目标版本文件夹")
                }
            } else {
                log("上次保存的路径无效: $lastPath")
            }
        }
        // 不弹 Toast，等待用户手动选择
    }

    // ================== 内置文件浏览器 (无SAF) ==================
    private var currentBrowserDir: File = Environment.getExternalStorageDirectory()

    private fun showFileBrowser() {
        // 尝试用上次保存的路径，如果无效则默认外部存储根目录
        val savedPath = prefs.getString("launcher_root", null)
        if (savedPath != null) {
            val savedDir = File(savedPath)
            if (savedDir.exists() && savedDir.isDirectory) {
                currentBrowserDir = savedDir
            } else {
                currentBrowserDir = Environment.getExternalStorageDirectory()
            }
        } else {
            currentBrowserDir = Environment.getExternalStorageDirectory()
        }
        browseDirectory(currentBrowserDir)
    }

    private fun browseDirectory(dir: File) {
        if (!dir.exists() || !dir.isDirectory) {
            Toast.makeText(this, "无效的文件夹", Toast.LENGTH_SHORT).show()
            return
        }

        val items = dir.listFiles()?.toList()?.sortedWith(compareBy<File> { it.isDirectory }.thenBy { it.name }) ?: emptyList()
        val displayItems = items.map { it.name }.toTypedArray()

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(dir.name)
            .setItems(displayItems) { _, which ->
                val selected = items[which]
                if (selected.isDirectory) {
                    currentBrowserDir = selected
                    browseDirectory(selected)
                }
            }
            .setPositiveButton("选择此文件夹") { _, _ ->
                prefs.edit().putString("launcher_root", dir.absolutePath).apply()
                val modsDir = findMinecraftModsDir(dir)
                if (modsDir != null) {
                    targetModsDir = modsDir
                    binding.btnStartDownload.isEnabled = true
                    log("已定位到 mods: ${modsDir.absolutePath}")
                    Toast.makeText(this, "游戏目录已选择", Toast.LENGTH_SHORT).show()
                } else {
                    showError(Constants.ERROR01)
                }
            }

        val rootDir = Environment.getExternalStorageDirectory()
        if (dir.absolutePath != rootDir.absolutePath) {
            builder.setNegativeButton("返回上级") { _, _ ->
                val parent = dir.parentFile
                if (parent != null) {
                    currentBrowserDir = parent
                    browseDirectory(parent)
                }
            }
        }

        val dialog = builder.create()
        dialog.window?.setWindowAnimations(R.style.DialogAnimation)
        dialog.show()
    }

    private fun findMinecraftModsDir(launcherRoot: File): File? {
        val mc = File(launcherRoot, ".minecraft")
        val mcAlt = File(launcherRoot, "minecraft")
        val minecraftDir = if (mc.exists()) mc else if (mcAlt.exists()) mcAlt else return null
        val versionsDir = File(minecraftDir, "versions")
        if (!versionsDir.exists()) return null
        val targetVersion = getVersionFolderName()
        val targetVersionDir = File(versionsDir, targetVersion)
        if (!targetVersionDir.exists()) {
            // 可以列出所有版本文件夹让用户选？这里暂时返回 null
            return null
        }
        val modsDir = File(targetVersionDir, "mods")
        if (!modsDir.exists()) modsDir.mkdirs()
        return modsDir
    }

    private fun getVersionFolderName(): String {
        return prefs.getString("version_folder", Constants.TARGET_VERSION_DIR) ?: Constants.TARGET_VERSION_DIR
    }
    // ================== 内置浏览器结束 ==================

    private fun showError(errorCode: String) {
        log("Error: $errorCode")
        MaterialAlertDialogBuilder(this)
            .setTitle("意外错误!")
            .setMessage("错误码: $errorCode\n请查看是否是您的问题,如不是,请联系开发者")
            .setPositiveButton("确定", null)
            .show()
    }

    private suspend fun fetchServerFileList(): List<String> = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            val request = Request.Builder().url(Constants.BASE_URL).build()
            val response = client.newCall(request).execute()
            val code = response.code
            val elapsed = System.currentTimeMillis() - startTime
            log("服务器响应状态码: $code, 耗时: ${elapsed}ms")
            if (!response.isSuccessful) {
                log("服务器返回错误: $code")
                return@withContext emptyList()
            }
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
            log("服务器文件列表获取成功，共 ${files.size} 个文件")
            files
        } catch (e: Exception) {
            log("获取服务器文件列表失败: ${e.message}")
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
                val startTime = System.currentTimeMillis()
                val manager = DownloadManager(url, size, 1, useRange = false)
                manager.download(destFile) { /* progress handled by caller */ }
                val elapsed = System.currentTimeMillis() - startTime
                val speed = if (elapsed > 0) size.toDouble() / elapsed * 1000 else 0.0
                log("  ${destFile.name} 下载成功: ${elapsed}ms, 平均速度 ${"%.1f".format(speed)} B/s")
                return
            } catch (e: Exception) {
                lastException = e
                log("  ${destFile.name} 第 $attempt/$maxRetries 次尝试失败: ${e.message}")
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
        log("=== 开始更新 ===")

        val threadCount = prefs.getInt("thread_count", 20).coerceIn(20, 128)
        log("使用线程数: $threadCount")

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
                    log("没有需要下载的文件（CSV 不匹配）")
                    showError(Constants.ERROR01)
                    return@launch
                }
                log("需要下载的文件数: ${toDownload.size}")

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
                                log("[${completed+1}/$total] 下载 $name (${mod.size} bytes)")
                                downloadWithRetry(Constants.BASE_URL + name, mod.size, file)
                                val verifier = FileVerifier()
                                val checkStart = System.currentTimeMillis()
                                val ok = verifier.verifyFile(file, mod.md5, mod.sha256)
                                val checkTime = System.currentTimeMillis() - checkStart
                                if (!ok) throw RuntimeException("校验失败 (耗时${checkTime}ms)")
                                log("    ${name} 校验通过 (${checkTime}ms)")
                                completed++
                                withContext(Dispatchers.Main) {
                                    binding.progressBar.progress = (completed * 100) / total
                                    binding.tvStatus.text = "$completed/$total"
                                }
                            } catch (e: Exception) {
                                log("失败 $name: ${e.message}")
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
                        log("所有模组更新成功！")
                        Toast.makeText(this@MainActivity, "模组已经更新完成!", Toast.LENGTH_LONG).show()
                        binding.tvStatus.text = "完成"
                        binding.progressBar.visibility = View.GONE
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                log("网络超时")
                showError(Constants.ERROR03)
            } catch (e: Exception) {
                if (e.message?.contains("Permission") == true) {
                    log("权限错误: ${e.message}")
                    showError(Constants.ERROR02)
                } else {
                    log("未预期错误: ${e.message}")
                    showError(Constants.ERROR01)
                }
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
        onBackPressedDispatcher.addCallback(this) {
            // 屏蔽返回键
        }
        Toast.makeText(this, "你为啥要点呢？", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        instance = null
        job.cancel()
        super.onDestroy()
    }
}

package www.xdyl.hygge.com

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import www.xdyl.hygge.com.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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

    private var fileBrowserDialog: AlertDialog? = null
    private var currentBrowseDir: File = Environment.getExternalStorageDirectory()
    private var fileAdapter: FileAdapter? = null
    private var tvPath: TextView? = null
    private var recyclerView: RecyclerView? = null

    // CSV 文件选择器相关
    private var csvPickDialog: AlertDialog? = null
    private var csvPickRecycler: RecyclerView? = null
    private var csvPickTvPath: TextView? = null
    private var csvPickCurrentDir: File = Environment.getExternalStorageDirectory()

    companion object {
        var instance: MainActivity? = null
    }

    data class ModInfo(val fileName: String, val size: Long, val md5: String, val sha256: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogManager.log("MainActivity created")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        instance = this

        // 设置标题
        binding.tvTitlePrefix.text = "Nebula updater-NU 星云更新器-"
        binding.tvTitleSuffix.text = "Android端"

        prefs = getSharedPreferences("xdyl_settings", MODE_PRIVATE)
        binding.tvLog.movementMethod = ScrollingMovementMethod()

        requestPermissionsIfNeeded()
        restoreLastDirectory()

        // 处理来自扩展页面的 CSV 选择请求
        if (prefs.getBoolean("pick_csv_request", false)) {
            prefs.edit().putBoolean("pick_csv_request", false).apply()
            showCsvFilePicker()
        }

        binding.btnSelectDir.setOnClickListener {
            it.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in))
            showFileBrowser()
        }
        binding.btnStartDownload.setOnClickListener {
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
            it.animate().rotationBy(180f).setDuration(300).start()
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getBoolean("pick_csv_request", false)) {
            prefs.edit().putBoolean("pick_csv_request", false).apply()
            showCsvFilePicker()
        }
        if (prefs.getBoolean("request_export_log", false)) {
            prefs.edit().putBoolean("request_export_log", false).apply()
            exportLogToFile()
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
                    LogManager.log("Restored mods dir: ${found.absolutePath}")
                    return
                }
            }
        }
    }

    // ==================== CSV 文件选择器 ====================
    private fun showCsvFilePicker() {
        val lastPath = prefs.getString("csv_browser_last_path", Environment.getExternalStorageDirectory().absolutePath)
        csvPickCurrentDir = File(lastPath)
        showCsvPickDialog(csvPickCurrentDir)
    }

    private fun showCsvPickDialog(dir: File) {
        val view = layoutInflater.inflate(R.layout.dialog_file_browser, null)
        csvPickTvPath = view.findViewById(R.id.tvPath)
        csvPickRecycler = view.findViewById(R.id.recyclerView)
        csvPickRecycler!!.layoutManager = LinearLayoutManager(this)
        loadCsvPickDir(dir)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setNegativeButton("返回上级", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { csvNavigateUp() }
        }
        csvPickDialog = dialog
        dialog.show()
    }

    private fun loadCsvPickDir(dir: File) {
        scope.launch(Dispatchers.IO) {
            val files = dir.listFiles()?.sortedWith(compareBy<File> { it.isDirectory }.thenBy { it.name }) ?: emptyList()
            withContext(Dispatchers.Main) {
                val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                        val tv = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
                        tv.setBackgroundColor(0xFF1E1E1E.toInt())
                        tv.setTextColor(0xFFFFFFFF.toInt())
                        return object : RecyclerView.ViewHolder(tv) {}
                    }
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                        val f = files[position]
                        (holder.itemView as TextView).text = f.name
                        holder.itemView.setOnClickListener {
                            if (f.isDirectory) {
                                csvPickCurrentDir = f
                                prefs.edit().putString("csv_browser_last_path", f.absolutePath).apply()
                                loadCsvPickDir(f)
                            } else {
                                prefs.edit().putString("local_csv_path", f.absolutePath).apply()
                                csvPickDialog?.dismiss()
                                Toast.makeText(this@MainActivity, "已选择 CSV: ${f.name}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    override fun getItemCount(): Int = files.size
                }
                csvPickRecycler!!.adapter = adapter
                csvPickTvPath!!.text = dir.absolutePath
            }
        }
    }

    private fun csvNavigateUp() {
        val parent = csvPickCurrentDir.parentFile ?: return
        csvPickCurrentDir = parent
        prefs.edit().putString("csv_browser_last_path", parent.absolutePath).apply()
        loadCsvPickDir(parent)
    }

    // ==================== 通用文件浏览器（目录选择） ====================
    private class FileAdapter(private var files: List<File>, private val onItemClick: (File) -> Unit) :
        RecyclerView.Adapter<FileAdapter.VH>() {
        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false) as TextView
            tv.setBackgroundColor(0xFF1E1E1E.toInt())
            tv.setTextColor(0xFFFFFFFF.toInt())
            return VH(tv)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.tv.text = files[position].name
            holder.itemView.setOnClickListener { onItemClick(files[position]) }
        }
        override fun getItemCount() = files.size
        fun setFiles(newFiles: List<File>) {
            files = newFiles
            notifyDataSetChanged()
        }
    }

    private fun showFileBrowser() {
        currentBrowseDir = File(prefs.getString("launcher_root", Environment.getExternalStorageDirectory().absolutePath))
        val view = layoutInflater.inflate(R.layout.dialog_file_browser, null)
        tvPath = view.findViewById(R.id.tvPath)
        recyclerView = view.findViewById(R.id.recyclerView)
        recyclerView!!.layoutManager = LinearLayoutManager(this)
        recyclerView!!.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .setPositiveButton("选择此文件夹") { _, _ ->
                prefs.edit().putString("launcher_root", currentBrowseDir.absolutePath).apply()
                handleSelectedFolder(currentBrowseDir)
            }
            .setNegativeButton("返回上级", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { navigateUp() }
            loadDirectory(currentBrowseDir)
        }
        fileBrowserDialog = dialog
        dialog.show()
    }

    private fun loadDirectory(dir: File) {
        scope.launch(Dispatchers.IO) {
            val files = dir.listFiles()?.toList()?.sortedWith(compareBy<File> { it.isDirectory }.thenBy { it.name }) ?: emptyList()
            withContext(Dispatchers.Main) {
                fileAdapter = FileAdapter(files) { file ->
                    if (file.isDirectory) navigateToDirectory(file)
                }
                recyclerView!!.adapter = fileAdapter
                tvPath!!.text = dir.absolutePath
                updateUpButtonState()
            }
        }
    }

    private fun navigateToDirectory(dir: File) {
        recyclerView!!.animate()
            .translationX(-recyclerView!!.width.toFloat())
            .setDuration(250)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentBrowseDir = dir
                    loadDirectory(dir)
                    recyclerView!!.translationX = recyclerView!!.width.toFloat()
                    recyclerView!!.animate()
                        .translationX(0f)
                        .setDuration(250)
                        .setListener(null)
                        .start()
                }
            })
    }

    private fun navigateUp() {
        val parent = currentBrowseDir.parentFile ?: return
        recyclerView!!.animate()
            .translationX(recyclerView!!.width.toFloat())
            .setDuration(250)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentBrowseDir = parent
                    loadDirectory(parent)
                    recyclerView!!.translationX = -recyclerView!!.width.toFloat()
                    recyclerView!!.animate()
                        .translationX(0f)
                        .setDuration(250)
                        .setListener(null)
                        .start()
                }
            })
    }

    private fun updateUpButtonState() {
        val root = Environment.getExternalStorageDirectory()
        val btn = fileBrowserDialog?.getButton(AlertDialog.BUTTON_NEGATIVE) ?: return
        if (currentBrowseDir.absolutePath == root.absolutePath) {
            btn.isEnabled = false
            btn.alpha = 0.5f
        } else {
            btn.isEnabled = true
            btn.alpha = 1.0f
        }
    }

    private fun handleSelectedFolder(folder: File) {
        val modsDir = findMinecraftModsDir(folder)
        if (modsDir != null) {
            targetModsDir = modsDir
            binding.btnStartDownload.isEnabled = true
            LogManager.log("Selected mods directory: ${modsDir.absolutePath}")
            Toast.makeText(this, "游戏目录已选择", Toast.LENGTH_SHORT).show()
        } else {
            showError(Constants.ERROR01)
        }
        fileBrowserDialog?.dismiss()
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
        LogManager.log("Error: $errorCode")
        MaterialAlertDialogBuilder(this)
            .setTitle("意外错误!")
            .setMessage("错误码: $errorCode\n请查看是否是您的问题,如不是,请联系开发者")
            .setPositiveButton("确定", null)
            .show()
    }

    // ==================== NeoForge check ====================
    private fun verifyNeoforgeVersion(callback: (Boolean) -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val targetVersion = prefs.getString("version_folder", Constants.TARGET_VERSION_DIR) ?: Constants.TARGET_VERSION_DIR
                    val launcherRoot = prefs.getString("launcher_root", Environment.getExternalStorageDirectory().absolutePath)
                    val mc = findMinecraftDir(File(launcherRoot)) ?: return@withContext false
                    val versionDir = File(File(mc, "versions"), targetVersion)
                    if (!versionDir.exists()) return@withContext false
                    val jsonFile = File(versionDir, "$targetVersion.json")
                    if (!jsonFile.exists()) return@withContext false
                    val jsonContent = jsonFile.readText()
                    val versionPattern = Regex("\"--fml\\.neoForgeVersion\",\\s*\"(\\d+\\.\\d+\\.\\d+)\"")
                    val match = versionPattern.find(jsonContent) ?: return@withContext false
                    val installedVersion = match.groupValues[1]
                    val required = "21.1.227"
                    compareVersion(installedVersion, required) >= 0
                } catch (e: Exception) {
                    LogManager.log("NeoForge check exception: ${e.message}")
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

    // ==================== Smart update ====================
    private suspend fun filterOutUnchangedMods(modsDir: File, csvMods: List<ModInfo>): List<ModInfo> = withContext(Dispatchers.IO) {
        val toDownload = mutableListOf<ModInfo>()
        for (mod in csvMods) {
            val localFile = File(modsDir, mod.fileName)
            if (!localFile.exists() || localFile.length() != mod.size) {
                toDownload.add(mod)
            } else {
                val localMd5 = calculateMD5(localFile)
                if (localMd5 != null && localMd5.equals(mod.md5, true)) {
                    LogManager.log("Skipping ${mod.fileName} (unchanged)")
                } else {
                    toDownload.add(mod)
                }
            }
        }
        LogManager.log("${toDownload.size} mods need update")
        toDownload
    }

    private fun calculateMD5(file: File): String? {
        try {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            LogManager.log("MD5 calc failed for ${file.name}: ${e.message}")
            return null
        }
    }

    // ==================== Download ====================
    private suspend fun fetchServerFileList(): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(Constants.BASE_URL).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (response.code != 200) return@withContext emptyList()
            val pattern = Pattern.compile("<a href=\"([^\"]+)\">")
            val matcher = pattern.matcher(body)
            val files = mutableListOf<String>()
            while (matcher.find()) {
                val link = matcher.group(1)
                if (link != null && link.endsWith(".jar")) files.add(link)
            }
            LogManager.log("Found ${files.size} files on server")
            files
        } catch (e: Exception) {
            LogManager.log("Failed to fetch server file list: ${e.message}")
            emptyList()
        }
    }

    private fun parseCsvMods(csv: String): List<ModInfo> {
        return csv.lines().drop(1).filter { it.isNotBlank() }.map { line ->
            val parts = line.split(",")
            ModInfo(parts[0].trim('"').removePrefix("./"), parts[2].toLong(), parts[3].trim('"'), parts[4].trim('"'))
        }
    }

    private fun getCsvContent(): String {
        if (prefs.getBoolean("use_local_csv", false)) {
            val path = prefs.getString("local_csv_path", null)
            if (path != null) {
                val file = File(path)
                if (file.exists()) {
                    return file.readText()
                }
            }
        }
        return Constants.CSV_CONTENT
    }

    private suspend fun downloadWithRetry(url: String, size: Long, destFile: File, maxRetries: Int = 5) {
        var lastEx: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                val start = System.currentTimeMillis()
                DownloadManager(url, size, 1, useRange = false).download(destFile) { }
                LogManager.log("${destFile.name} downloaded in ${System.currentTimeMillis() - start}ms")
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
        appendLog("开始检查已有模组...")

        val threadCount = prefs.getInt("thread_limit", prefs.getInt("thread_count", 256)).coerceIn(1, 1024)
        scope.launch {
            try {
                val serverFiles = fetchServerFileList()
                if (serverFiles.isEmpty()) { showError(Constants.ERROR01); return@launch }
                val csvMods = parseCsvMods(getCsvContent())
                val csvSet = csvMods.map { it.fileName }.toSet()
                val allServerMods = serverFiles.filter { csvSet.contains(it) }

                val toDownload = filterOutUnchangedMods(modsDir, csvMods.filter { it.fileName in allServerMods })
                if (toDownload.isEmpty()) {
                    appendLog("所有模组均为最新版本！")
                    Toast.makeText(this@MainActivity, "没有需要更新的文件", Toast.LENGTH_SHORT).show()
                    binding.progressBar.visibility = View.GONE
                    isProcessing = false
                    binding.btnStartDownload.isEnabled = true
                    return@launch
                }

                appendLog("开始下载 ${toDownload.size} 个模组...")
                val sem = Semaphore(threadCount)
                val failed = AtomicInteger(0)
                var completed = 0
                val total = toDownload.size

                withContext(Dispatchers.IO) {
                    toDownload.map { mod ->
                        launch {
                            sem.acquire()
                            try {
                                val file = File(modsDir, mod.fileName)
                                appendLog("[${completed+1}/$total] ${mod.fileName}")
                                downloadWithRetry(Constants.BASE_URL + mod.fileName, mod.size, file)
                                if (!FileVerifier().verifyFile(file, mod.md5, mod.sha256))
                                    throw RuntimeException("校验失败")
                                completed++
                                withContext(Dispatchers.Main) {
                                    binding.progressBar.progress = (completed * 100) / total
                                    binding.tvStatus.text = "$completed/$total"
                                }
                            } catch (e: Exception) {
                                LogManager.log("Failed ${mod.fileName}: ${e.message}")
                                failed.incrementAndGet()
                            } finally {
                                sem.release()
                            }
                        }
                    }.joinAll()
                }

                val cleanOrphan = prefs.getBoolean("clean_orphan_files", true)
                if (cleanOrphan) {
                    withContext(Dispatchers.IO) {
                        val csvFiles = csvMods.map { it.fileName }.toSet()
                        val modFiles = modsDir.listFiles()?.filter { it.extension.equals("jar", true) } ?: emptyList()
                        var deleted = 0
                        for (file in modFiles) {
                            if (file.name !in csvFiles) {
                                if (file.delete()) {
                                    deleted++
                                    LogManager.log("Deleted orphan file: ${file.name}")
                                }
                            }
                        }
                        if (deleted > 0) {
                            withContext(Dispatchers.Main) {
                                appendLog("已清理 $deleted 个多余文件")
                            }
                        }
                    }
                }

                if (failed.get() > 0) {
                    showError(Constants.ERROR05)
                } else {
                    appendLog("更新完成！")
                    Toast.makeText(this@MainActivity, "模组已经更新完成!", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this, "暂无日志可导出", Toast.LENGTH_SHORT).show()
                return
            }
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, "mod_update_log_${System.currentTimeMillis()}.txt")
            FileOutputStream(file).use { it.write(log.toByteArray()) }
            LogManager.log("Log exported to ${file.absolutePath}")
            Toast.makeText(this, "日志已导出至 ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        instance = null
        job.cancel()
        super.onDestroy()
    }
}

package www.xdyl.hygge.com

import org.json.JSONObject
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import www.xdyl.hygge.com.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
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
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var fileBrowserDialog: AlertDialog? = null
    private var currentBrowseDir: File = Environment.getExternalStorageDirectory()
    private var fileAdapter: FileAdapter? = null
    private var tvPath: TextView? = null
    private var recyclerView: RecyclerView? = null

    // 每日名言数据
    private val quoteCategories = listOf("WH", "RW", "HC", "ED", "CE", "AC")
    private val categoryNames = mapOf(
        "WH" to "警世箴言",
        "RW" to "理性思辨",
        "HC" to "心灵疗愈",
        "ED" to "存在哲思",
        "CE" to "人际纽带",
        "AC" to "行动召唤"
    )

    companion object {
        var instance: MainActivity? = null
    }

    data class ModInfo(val fileName: String, val size: Long, val md5: String, val sha256: String)
    data class Quote(val chinese: String, val english: String, val author: String, val authorEn: String, val source: String, val sourceEn: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogManager.log("MainActivity 创建")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        instance = this

        binding.tvTitleLine1.text = "Nebula updater-NU"
        binding.tvTitleLine2.text = "星云更新器-Android端"

        prefs = getSharedPreferences("xdyl_settings", MODE_PRIVATE)
        binding.tvLog.movementMethod = ScrollingMovementMethod()

        requestStoragePermissions()
        loadDailyQuote()  // 加载每日名言

        binding.btnSelectDir.setOnClickListener {
            it.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in))
            showFileBrowser()
        }
        binding.btnStartDownload.setOnClickListener {
            it.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in))
            if (prefs.getBoolean("neoforge_check_enabled", true)) {
                verifyNeoforgeVersion { verified ->
                    if (verified) startUpdateProcess()
                    else {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("NeoForge 版本过低")
                            .setMessage("需要更新 NeoForge 驱动至 21.1.228 或更高版本。")
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
        if (prefs.getBoolean("request_export_log", false)) {
            prefs.edit().putBoolean("request_export_log", false).apply()
            exportLogToFile()
        }
        // 再次检查是否需要刷新名言
        loadDailyQuote()
    }

    private fun requestStoragePermissions() { /* 保持不变 */ }

    private fun restoreLastDirectory() { /* 保持不变 */ }

    // ========== 每日名言 ==========
    private fun loadDailyQuote() {
    val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
    val lastDate = prefs.getString("quote_date", "")
    if (lastDate != today) {
        scope.launch(Dispatchers.IO) {
            try {
                val allQuotes = mutableListOf<Pair<String, Quote>>()
                for (cat in quoteCategories) {
                    val jsonStr = assets.open("$cat.json").bufferedReader().readText()
                    val jsonObject = JSONObject(jsonStr)
                    val quotesArray = jsonObject.getJSONArray("quotes")
                    for (i in 0 until quotesArray.length()) {
                        val obj = quotesArray.getJSONObject(i)
                        val quote = Quote(
                            chinese = obj.getString("chinese"),
                            english = obj.getString("english"),
                            author = obj.getString("author"),
                            authorEn = obj.getString("author_en"),
                            source = obj.getString("source"),
                            sourceEn = obj.getString("source_en")
                        )
                        allQuotes.add(Pair(cat, quote))
                    }
                }
                if (allQuotes.isNotEmpty()) {
                    val randomIndex = Random().nextInt(allQuotes.size)
                    val (cat, quote) = allQuotes[randomIndex]
                    withContext(Dispatchers.Main) {
                        displayQuote(cat, quote)
                    }
                    prefs.edit()
                        .putString("quote_date", today)
                        .putString("quote_cat", cat)
                        .putInt("quote_index", randomIndex)
                        .apply()
                }
            } catch (e: Exception) {
                LogManager.log("加载名言失败: ${e.message}")
            }
        }
    } else {
        val cat = prefs.getString("quote_cat", quoteCategories[0]) ?: quoteCategories[0]
        val index = prefs.getInt("quote_index", 0)
        scope.launch(Dispatchers.IO) {
            try {
                val jsonStr = assets.open("$cat.json").bufferedReader().readText()
                val jsonObject = JSONObject(jsonStr)
                val quotesArray = jsonObject.getJSONArray("quotes")
                if (index < quotesArray.length()) {
                    val obj = quotesArray.getJSONObject(index)
                    val quote = Quote(
                        chinese = obj.getString("chinese"),
                        english = obj.getString("english"),
                        author = obj.getString("author"),
                        authorEn = obj.getString("author_en"),
                        source = obj.getString("source"),
                        sourceEn = obj.getString("source_en")
                    )
                    withContext(Dispatchers.Main) {
                        displayQuote(cat, quote)
                    }
                }
            } catch (e: Exception) {
                LogManager.log("恢复名言失败: ${e.message}")
            }
        }
    }
}

    private fun displayQuote(category: String, quote: Quote) {
        binding.tvQuoteTitle.text = "今日名言 - ${categoryNames[category] ?: category}"
        binding.tvQuoteChinese.text = quote.chinese
        binding.tvQuoteEnglish.text = quote.english
        binding.tvQuoteAuthor.text = "- ${quote.author} / ${quote.source}"
        binding.tvQuoteAuthorEn.text = "- ${quote.authorEn} / ${quote.sourceEn}"
    }

    // ========== 文件浏览器 ==========
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
        fun setFiles(newFiles: List<File>) { files = newFiles; notifyDataSetChanged() }
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
                fileAdapter = FileAdapter(files) { file -> if (file.isDirectory) navigateToDirectory(file) }
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
                    recyclerView!!.animate().translationX(0f).setDuration(250).setListener(null).start()
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
                    recyclerView!!.animate().translationX(0f).setDuration(250).setListener(null).start()
                }
            })
    }

    private fun updateUpButtonState() {
        val btn = fileBrowserDialog?.getButton(AlertDialog.BUTTON_NEGATIVE) ?: return
        val isRoot = currentBrowseDir.absolutePath == Environment.getExternalStorageDirectory().absolutePath
        btn.isEnabled = !isRoot
        btn.alpha = if (isRoot) 0.5f else 1.0f
    }

    private fun handleSelectedFolder(folder: File) {
        val modsDir = findMinecraftModsDir(folder)
        if (modsDir != null) {
            targetModsDir = modsDir
            binding.btnStartDownload.isEnabled = true
            Toast.makeText(this, "游戏目录已选择", Toast.LENGTH_SHORT).show()
        } else {
            showError(Constants.ERROR01)
        }
        fileBrowserDialog?.dismiss()
    }

    private fun findMinecraftModsDir(launcherRoot: File): File? {
        val mc = File(launcherRoot, ".minecraft")
        val mcAlt = File(launcherRoot, "minecraft")
        val minecraftDir = when { mc.exists() -> mc; mcAlt.exists() -> mcAlt; else -> return null }
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
        LogManager.log("错误: $errorCode")
        MaterialAlertDialogBuilder(this)
            .setTitle("意外错误!")
            .setMessage("错误码: $errorCode\n请查看是否是您的问题,如不是,请联系开发者")
            .setPositiveButton("确定", null).show()
    }

    // ========== NeoForge 检查 ==========
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
                    compareVersion(installedVersion, "21.1.228") >= 0
                } catch (e: Exception) {
                    LogManager.log("NeoForge 检查异常: ${e.message}")
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

    // ========== 下载与日志 ==========
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
                if (link != null && link.endsWith(".jar")) {
                    files.add(java.net.URLDecoder.decode(link, "UTF-8"))
                }
            }
            LogManager.log("从服务器获取到 ${files.size} 个文件")
            files
        } catch (e: Exception) {
            LogManager.log("获取服务器文件列表失败: ${e.message}")
            emptyList()
        }
    }

    private fun getCsvContent(): String {
        if (prefs.getBoolean("use_local_csv", false)) {
            val path = prefs.getString("local_csv_path", null)
            if (path != null) { val file = File(path); if (file.exists()) return file.readText() }
        }
        return Constants.CSV_CONTENT
    }

    private suspend fun downloadWithRetry(url: String, size: Long, destFile: File, maxRetries: Int = 5) {
        var lastEx: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                DownloadManager(url, size, 1, useRange = false).download(destFile) { }
                appendLog("[OK] ${destFile.name}")
                return
            } catch (e: Exception) {
                lastEx = e
                appendLog("[RETRY $attempt] ${destFile.name}")
                delay((1000L * attempt).coerceAtMost(5000))
            }
        }
        appendLog("[FAILED] ${destFile.name}")
        throw lastEx!!
    }

    private fun startUpdateProcess() {
        if (isProcessing) return
        val modsDir = targetModsDir ?: run { showError(Constants.ERROR01); return }
        isProcessing = true
        binding.btnStartDownload.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvLog.text = "Checking mods..."
        LogManager.log("开始更新")

        val threadCount = prefs.getInt("thread_limit", prefs.getInt("thread_count", 256)).coerceIn(1, 1024)
        scope.launch {
            try {
                val serverFiles = fetchServerFileList()
                if (serverFiles.isEmpty()) { showError(Constants.ERROR01); return@launch }
                val csvMods = getCsvContent().lines().drop(1).filter { it.isNotBlank() }.map { line ->
                    val parts = line.split(",")
                    ModInfo(parts[0].trim('"').removePrefix("./"), parts[2].toLong(), parts[3].trim('"'), parts[4].trim('"'))
                }
                val csvSet = csvMods.map { it.fileName }.toSet()
                val allServerMods = serverFiles.filter { csvSet.contains(it) }
                val toDownload = filterOutUnchangedMods(modsDir, csvMods.filter { it.fileName in allServerMods })
                if (toDownload.isEmpty()) {
                    appendLog("All mods are up-to-date!")
                    binding.progressBar.visibility = View.GONE
                    isProcessing = false
                    binding.btnStartDownload.isEnabled = true
                    return@launch
                }

                binding.tvLog.text = "Downloading ${toDownload.size} mods..."
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
                                val encodedName = URLEncoder.encode(mod.fileName, "UTF-8").replace("+", "%20")
                                val url = Constants.BASE_URL + encodedName
                                downloadWithRetry(url, mod.size, file)
                                if (!FileVerifier().verifyFile(file, mod.md5, mod.sha256))
                                    throw RuntimeException("校验失败")
                                completed++
                                withContext(Dispatchers.Main) {
                                    binding.progressBar.progress = (completed * 100) / total
                                    binding.tvStatus.text = "$completed/$total"
                                }
                            } catch (e: Exception) {
                                LogManager.log("下载失败 ${mod.fileName}: ${e.message}")
                                failed.incrementAndGet()
                            } finally {
                                sem.release()
                            }
                        }
                    }.joinAll()
                }

                if (prefs.getBoolean("clean_orphan_files", true)) {
                    withContext(Dispatchers.IO) {
                        val whiteList = prefs.getStringSet("mod_whitelist", emptySet()) ?: emptySet()
                        val csvFiles = csvMods.map { it.fileName }.toSet()
                        val modFiles = modsDir.listFiles()?.filter { it.extension == "jar" } ?: emptyList()
                        var deleted = 0
                        for (file in modFiles) {
                            if (file.name !in csvFiles && file.name !in whiteList) {
                                if (file.delete()) { deleted++; LogManager.log("已删除孤儿文件: ${file.name}") }
                            }
                        }
                        if (deleted > 0) appendLog("Cleaned $deleted files")
                    }
                }

                if (failed.get() > 0) showError(Constants.ERROR05)
                else {
                    appendLog("Update completed!")
                                    val packsDir = File(targetModsDir, "../resourcepacks/generated.zip")
                                    val resourcePackExists = packsDir.exists()
                    if (!prefs.getBoolean("has_completed_first_update", false) && !resourcePackExists) {
                        withContext(Dispatchers.Main) {
                            MaterialAlertDialogBuilder(this@MainActivity)
                                .setTitle("安装服务器材质包")
                                .setMessage("是否要安装 Server 材质包？\n注意！这是必要，如不装，进服将下载材质包，在这里安装可以加快速度。")
                                .setPositiveButton("好的") { _, _ ->
                                    scope.launch { installResourcePack() }
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    }
                }
            } catch (e: Exception) {
                showError(Constants.ERROR03)
            } finally {
                isProcessing = false
                binding.btnStartDownload.isEnabled = true
            }
        }
    }

    private suspend fun installResourcePack() {
        withContext(Dispatchers.IO) {
            try {
                val launcherRoot = File(prefs.getString("launcher_root", Environment.getExternalStorageDirectory().absolutePath)!!)
                val mc = File(launcherRoot, ".minecraft")
                val targetVersion = prefs.getString("version_folder", Constants.TARGET_VERSION_DIR) ?: Constants.TARGET_VERSION_DIR
                val versionDir = File(mc, "versions/$targetVersion")
                val packsDir = File(versionDir, "resourcepacks")
                if (!packsDir.exists()) packsDir.mkdirs()
                val destFile = File(packsDir, "generated.zip")
                if (!destFile.exists()) {
                    resources.openRawResource(R.raw.generated).use { input ->
                        FileOutputStream(destFile).use { output -> input.copyTo(output) }
                    }
                    LogManager.log("材质包已安装到 ${destFile.absolutePath}")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "材质包已安装", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                LogManager.log("安装材质包失败: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "安装材质包失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun filterOutUnchangedMods(modsDir: File, csvMods: List<ModInfo>): List<ModInfo> = withContext(Dispatchers.IO) {
        val toDownload = mutableListOf<ModInfo>()
        for (mod in csvMods) {
            val localFile = File(modsDir, mod.fileName)
            if (!localFile.exists() || localFile.length() != mod.size) toDownload.add(mod)
            else {
                val localMd5 = calculateMD5(localFile)
                if (localMd5 != null && localMd5.equals(mod.md5, true)) LogManager.log("跳过未变化的模组: ${mod.fileName}")
                else toDownload.add(mod)
            }
        }
        toDownload
    }

    private fun calculateMD5(file: File): String? = try {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { fis -> val buffer = ByteArray(8192); var len: Int; while (fis.read(buffer).also { len = it } != -1) digest.update(buffer, 0, len) }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { null }

    fun appendLog(msg: String) {
        runOnUiThread {
            val current = binding.tvLog.text.toString()
            binding.tvLog.text = "$current\n$msg"
            binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun exportLogToFile() {
        scope.launch(Dispatchers.IO) {
            try {
                val log = LogManager.getFullLog()
                val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val file = File(downloadsDir, "mod_update_log_${System.currentTimeMillis()}.txt")
                FileOutputStream(file).use { it.write(log.toByteArray()) }
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "日志已保存至 ${file.absolutePath}", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@MainActivity, "导出失败: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onDestroy() { instance = null; job.cancel(); super.onDestroy() }
}

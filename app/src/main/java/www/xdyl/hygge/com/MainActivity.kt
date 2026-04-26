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
            showDirectorySelector()
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
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                100
            )
        }
    }

    private fun showDirectorySelector() {
        val managers = listOf(
            "Solid Explorer" to "pl.solidexplorer2",
            "FX File Explorer" to "nextapp.fx",
            "Material Files" to "me.zhanghai.android.files"
        )
        val installed = managers.filter { isPackageInstalled(it.second) }
        if (installed.isEmpty()) {
            selectDirectory(null)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("请选择文件管理器")
            .setItems(installed.map { it.first }.toTypedArray()) { _, which ->
                launchThirdPartyManager(installed[which].second)
            }
            .setNeutralButton("系统默认") { _, _ -> selectDirectory(null) }
            .show()
    }

    private fun isPackageInstalled(pkg: String) = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    private fun launchThirdPartyManager(pkg: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setClassName(pkg, "com.android.documentsui.DocumentsActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            selectDirectory(null)
        }
    }

    private fun selectDirectory(uri: Uri?) = dirPickerLauncher.launch(uri)

    private val dirPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            selectedBaseUri = it
            findMinecraftVersionDir(it)?.let { (dir, similar) ->
                if (similar) showError(Constants.ERROR06)
                else {
                    targetModsDir = dir
                    binding.btnStartDownload.isEnabled = true
                    log("Game dir selected: ${dir.absolutePath}")
                }
            } ?: showError(Constants.ERROR01)
        }
    }

    private fun findMinecraftVersionDir(baseUri: Uri): Pair<File, Boolean>? {
        try {
            val doc = DocumentFile.fromTreeUri(this, baseUri) ?: return null
            val mc = doc.findFile(".minecraft") ?: doc.findFile("minecraft") ?: return null
            val vers = mc.listFiles()?.find { it.name == "versions" } ?: return null
            val target = getVersionFolderName()
            val vDir = vers.listFiles()?.find { it.name?.equals(target, true) == true }
            if (vDir != null) {
                val mods = vDir.listFiles()?.find { it.name == "mods" } ?: vDir.createDirectory("mods")
                val rawPath = mods?.uri?.path?.substringAfter("/tree/")?.let { "/storage/emulated/0/$it" } ?: return null
                val f = File(rawPath).also { if (!it.exists()) it.mkdirs() }
                return Pair(f, false)
            } else return checkSimilar(vers)
        } catch (_: Exception) { return null }
    }

    private fun getVersionFolderName() = prefs.getString("version_folder", Constants.TARGET_VERSION_DIR) ?: Constants.TARGET_VERSION_DIR

    private fun checkSimilar(vers: DocumentFile): Pair<File, Boolean>? {
        val prefix = getVersionFolderName().take(5)
        val similar = vers.listFiles()?.find { it.name?.contains(prefix) == true && it.name != getVersionFolderName() }
        if (similar != null) {
            val mods = similar.listFiles()?.find { it.name == "mods" } ?: similar.createDirectory("mods")
            val rawPath = mods?.uri?.path?.substringAfter("/tree/") ?: return null
            return Pair(File("/storage/emulated/0/$rawPath"), true)
        }
        return null
    }

    private fun parseCsvMods(csv: String): List<ModInfo> {
        return csv.lines().drop(1).filter { it.isNotBlank() }.map { line ->
            val p = line.split(",")
            ModInfo(p[0].trim('"').removePrefix("./"), p[2].toLong(), p[3].trim('"'), p[4].trim('"'))
        }
    }

    private fun showError(code: String) {
        log("Error: $code")
        AlertDialog.Builder(this).setTitle("意外错误!").setMessage("错误码: $code\n请查看是否是您的问题,如不是,请联系开发者").setPositiveButton("确定", null).show()
    }

    private suspend fun fetchServerFileList(): List<String> = withContext(Dispatchers.IO) {
        try {
            val html = client.newCall(Request.Builder().url(Constants.BASE_URL).build()).execute().body?.string() ?: return@withContext emptyList()
            val files = mutableListOf<String>()
            val m = Pattern.compile("<a href=\"([^\"]+)\">").matcher(html)
            while (m.find()) m.group(1)?.let { if (it.endsWith(".jar")) files.add(it) }
            log("Server file count: ${files.size}")
            files
        } catch (e: Exception) {
            log("Server fetch error: ${e.message}")
            emptyList()
        }
    }

    private fun startUpdateProcess() {
        if (isProcessing) return
        val modsDir = targetModsDir ?: run { showError(Constants.ERROR01); return }
        isProcessing = true
        binding.btnStartDownload.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        logBuilder.clear(); binding.tvLog.text = ""
        log("Fetching server file list...")

        val threads = prefs.getInt("thread_count", 20).coerceIn(20, 128)

        scope.launch {
            try {
                val serverFiles = fetchServerFileList()
                if (serverFiles.isEmpty()) { showError(Constants.ERROR01); return@launch }
                val csvMods = parseCsvMods(Constants.CSV_CONTENT)
                val csvSet = csvMods.map { it.fileName }.toSet()
                val toDownload = serverFiles.filter { csvSet.contains(it) }
                if (toDownload.isEmpty()) { showError(Constants.ERROR01); return@launch }

                val sem = Semaphore(threads)
                val failed = AtomicInteger(0)
                var completed = 0
                withContext(Dispatchers.IO) {
                    toDownload.map { name ->
                        launch {
                            sem.acquire()
                            try {
                                val mod = csvMods.first { it.fileName == name }
                                val file = File(modsDir, name)
                                log("Download $name (${mod.size} bytes)")
                                DownloadManager(Constants.BASE_URL + name, mod.size, 1).download(file) {}
                                if (!FileVerifier().verifyFile(file, mod.md5, mod.sha256)) throw RuntimeException("Checksum fail: $name")
                                completed++
                                withContext(Dispatchers.Main) {
                                    binding.progressBar.progress = completed * 100 / toDownload.size
                                    binding.tvStatus.text = "$completed/${toDownload.size}"
                                }
                                log("$name done")
                            } catch (e: Exception) {
                                log("Failed $name: ${e.message}")
                                failed.incrementAndGet()
                            } finally { sem.release() }
                        }
                    }.joinAll()
                }
                if (failed.get() > 0) showError(Constants.ERROR05)
                else {
                    log("All done!")
                    Toast.makeText(this@MainActivity, "模组已经更新完成!", Toast.LENGTH_LONG).show()
                    binding.progressBar.visibility = View.GONE
                }
            } catch (e: java.net.SocketTimeoutException) { showError(Constants.ERROR03) }
            catch (e: Exception) {
                if (e.message?.contains("Permission") == true) showError(Constants.ERROR02) else showError(Constants.ERROR01)
                log("Exception: ${e.message}")
            } finally {
                isProcessing = false
                binding.btnStartDownload.isEnabled = true
            }
        }
    }

    fun appendLog(msg: String) {
        logBuilder.appendLine(msg)
        runOnUiThread { binding.tvLog.text = logBuilder.toString(); binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) } }
    }

    private fun log(msg: String) = LogManager.log(msg)

    private fun exportLogToFile() {
        try {
            val log = LogManager.getFullLog()
            if (log.isEmpty()) { Toast.makeText(this, "暂无日志", Toast.LENGTH_SHORT).show(); return }
            val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "mod_update_log_${System.currentTimeMillis()}.txt")
            FileOutputStream(file).use { it.write(log.toByteArray()) }
            Toast.makeText(this, "日志导出至 ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) { Toast.makeText(this, "导出失败: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    private fun activateGreenScreen() {
        binding.greenOverlay.visibility = View.VISIBLE
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
    }

    override fun onDestroy() { instance = null; job.cancel(); super.onDestroy() }
}

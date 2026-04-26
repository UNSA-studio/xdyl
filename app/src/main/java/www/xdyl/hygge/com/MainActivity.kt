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
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import www.xdyl.hygge.com.databinding.ActivityMainBinding
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile
import com.github.junrar.Junrar
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var selectedBaseUri: Uri? = null
    private var targetModsDir: File? = null
    private var isProcessing = false
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private lateinit var prefs: SharedPreferences
    private val logBuilder = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            startActivity(Intent(this, SettingsActivity::class.java))
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

        val items = installed.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("请选择文件管理器")
            .setItems(items) { _, which ->
                val packageName = installed[which].second
                launchThirdPartyManager(packageName)
            }
            .setNeutralButton("系统默认") { _, _ ->
                selectDirectory(null)
            }
            .show()
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun launchThirdPartyManager(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setClassName(packageName, "com.android.documentsui.DocumentsActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            Toast.makeText(this, "请在该管理器中手动导航至游戏目录，然后返回本应用", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "无法启动所选管理器，将使用系统默认", Toast.LENGTH_SHORT).show()
            selectDirectory(null)
        }
    }

    private fun selectDirectory(initialUri: Uri?) {
        dirPickerLauncher.launch(initialUri)
    }

    private val dirPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            selectedBaseUri = it
            findMinecraftVersionDir(it)?.let { (dir, isSimilar) ->
                if (isSimilar) {
                    showError(Constants.ERROR06)
                } else {
                    targetModsDir = dir
                    binding.btnStartDownload.isEnabled = true
                    appendLog("游戏目录已选择: ${dir.absolutePath}")
                    Toast.makeText(this, "已选择游戏目录", Toast.LENGTH_SHORT).show()
                }
            } ?: run {
                showError(Constants.ERROR01)
            }
        }
    }

    private fun findMinecraftVersionDir(baseUri: Uri): Pair<File, Boolean>? {
        try {
            val doc = DocumentFile.fromTreeUri(this, baseUri) ?: return null
            val minecraftDoc = doc.findFile(".minecraft") ?: doc.findFile("minecraft") ?: return null
            val versionsDoc = minecraftDoc.listFiles()?.find { it.name == "versions" } ?: return null
            val targetVersion = getVersionFolderName()
            val versionDir = versionsDoc.listFiles()?.find {
                it.name?.equals(targetVersion, ignoreCase = true) == true
            }
            if (versionDir != null) {
                val modsDir = versionDir.listFiles()?.find { it.name == Constants.MODS_DIR }
                    ?: versionDir.createDirectory(Constants.MODS_DIR)
                val rawPath = modsDir?.uri?.path?.let {
                    it.substring(it.indexOf("/tree/") + 6).let { p ->
                        "/storage/emulated/0/$p"
                    }
                } ?: return null
                val file = File(rawPath)
                if (!file.exists()) file.mkdirs()
                return Pair(file, false)
            } else {
                return checkSimilar(versionsDoc)
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun getVersionFolderName(): String {
        return prefs.getString("version_folder", Constants.TARGET_VERSION_DIR) ?: Constants.TARGET_VERSION_DIR
    }

    private fun checkSimilar(versionsDoc: DocumentFile): Pair<File, Boolean>? {
        val targetPrefix = getVersionFolderName().substring(0, 5)
        val similar = versionsDoc.listFiles()?.find {
            it.name?.contains(targetPrefix) == true &&
            it.name != getVersionFolderName()
        }
        if (similar != null) {
            val modsDir = similar.listFiles()?.find { it.name == Constants.MODS_DIR }
                ?: similar.createDirectory(Constants.MODS_DIR)
            val rawPath = modsDir?.uri?.path?.let {
                it.substring(it.indexOf("/tree/") + 6)
            } ?: return null
            val file = File("/storage/emulated/0/$rawPath")
            return Pair(file, true)
        }
        return null
    }

    private fun showError(errorCode: String) {
        appendLog("错误: $errorCode")
        AlertDialog.Builder(this)
            .setTitle("意外错误!")
            .setMessage("错误码: $errorCode\n请查看是否是您的问题,如不是,请联系开发者")
            .setPositiveButton("确定", null)
            .show()
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
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.progress = 0
        logBuilder.clear()
        binding.tvLog.text = ""
        appendLog("开始下载 mods.rar ...")

        val threadCount = getThreadCount()
        val extractKernel = getExtractKernel()

        scope.launch {
            val downloadDir = cacheDir
            val rarFile = File(downloadDir, Constants.RAR_NAME)

            try {
                withContext(Dispatchers.IO) {
                    val manager = DownloadManager(Constants.DOWNLOAD_URL, Constants.FILE_SIZE, threadCount)
                    manager.download(rarFile) { progress ->
                        launch(Dispatchers.Main) {
                            binding.progressBar.progress = progress
                            binding.tvStatus.text = "下载中... $progress%"
                            if (progress % 10 == 0) appendLog("下载进度 $progress%")
                        }
                    }
                }
                appendLog("下载完成，开始校验...")
                val verifier = FileVerifier()
                val rarCheck = withContext(Dispatchers.IO) {
                    verifier.verifyFile(rarFile, Constants.EXPECTED_MD5, Constants.EXPECTED_SHA256)
                }
                if (!rarCheck) {
                    showError(Constants.ERROR04)
                    return@launch
                }
                appendLog("校验通过，开始解压（内核：$extractKernel）...")
                withContext(Dispatchers.IO) {
                    when (extractKernel) {
                        "junrar" -> {
                            Junrar.extract(rarFile, modsDir)
                            appendLog("Junrar 解压完成")
                        }
                        "sevenz" -> {
                            // 使用 Apache Commons Compress 处理 7z/RAR/ZIP 等
                            val fis = FileInputStream(rarFile)
                            val ais: ArchiveInputStream<*> = ArchiveStreamFactory().createArchiveInputStream(fis)
                            ais.use { archive ->
                                var entry: ArchiveEntry? = archive.nextEntry
                                while (entry != null) {
                                    if (!entry.isDirectory) {
                                        val outFile = File(modsDir, entry.name)
                                        outFile.parentFile?.mkdirs()
                                        FileOutputStream(outFile).use { fos ->
                                            val buffer = ByteArray(8192)
                                            var len: Int
                                            while (archive.read(buffer).also { len = it } != -1) {
                                                fos.write(buffer, 0, len)
                                            }
                                        }
                                    }
                                    entry = archive.nextEntry
                                }
                            }
                            appendLog("7z (Commons Compress) 解压完成")
                        }
                        "builtin_zip" -> {
                            ZipFile(rarFile).use { zip ->
                                zip.entries().asIterator().forEach { entry ->
                                    if (!entry.isDirectory) {
                                        val outFile = File(modsDir, entry.name)
                                        outFile.parentFile?.mkdirs()
                                        FileOutputStream(outFile).use { fos ->
                                            zip.getInputStream(entry).copyTo(fos)
                                        }
                                    }
                                }
                            }
                            appendLog("内置ZIP解压完成")
                        }
                        else -> throw RuntimeException("未知解压内核")
                    }
                }
                appendLog("开始校验模组文件...")
                val allValid = withContext(Dispatchers.IO) {
                    verifier.verifyModsFromCsv(modsDir, Constants.CSV_CONTENT)
                }
                if (!allValid) {
                    showError(Constants.ERROR05)
                } else {
                    withContext(Dispatchers.Main) {
                        appendLog("所有模组更新完成！")
                        Toast.makeText(this@MainActivity, "模组已经更新完成!", Toast.LENGTH_LONG).show()
                        binding.tvStatus.text = "完成"
                        binding.progressBar.visibility = android.view.View.GONE
                    }
                }
            } catch (e: java.net.SocketTimeoutException) {
                showError(Constants.ERROR03)
            } catch (e: Exception) {
                if (e.message?.contains("Permission") == true) showError(Constants.ERROR02)
                else showError(Constants.ERROR01)
                appendLog("异常: ${e.message}")
            } finally {
                isProcessing = false
                binding.btnStartDownload.isEnabled = true
            }
        }
    }

    private fun appendLog(msg: String) {
        logBuilder.appendLine(msg)
        runOnUiThread {
            binding.tvLog.text = logBuilder.toString()
            binding.logScroll.post { binding.logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun getThreadCount(): Int {
        return prefs.getInt("thread_count", 20).coerceIn(20, 128)
    }

    private fun getExtractKernel(): String {
        return prefs.getString("extract_kernel", "junrar") ?: "junrar"
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }
}

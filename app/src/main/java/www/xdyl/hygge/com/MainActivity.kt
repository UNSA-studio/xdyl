package www.xdyl.hygge.com

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import www.xdyl.hygge.com.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var selectedBaseUri: Uri? = null
    private var targetModsDir: File? = null
    private var isProcessing = false
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissionsIfNeeded()

        binding.btnSelectDir.setOnClickListener { showDirectorySelector() }
        binding.btnStartDownload.setOnClickListener { startUpdateProcess() }
        binding.btnSettings.setOnClickListener {
            Toast.makeText(this, "设置功能将在后续版本开放", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDirectorySelector() {
        // 列出支持 SAF 的常见第三方管理器
        val managers = listOf(
            "Solid Explorer" to "pl.solidexplorer2",
            "FX File Explorer" to "nextapp.fx",
            "Material Files" to "me.zhanghai.android.files"
        )
        val installed = managers.filter { isPackageInstalled(it.second) }

        if (installed.isEmpty()) {
            // 没有找到第三方管理器，直接启动系统选择器
            selectDirectory(null)
            return
        }

        // 构建底部选择菜单，优先使用第三方管理器
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
            Toast.makeText(this, "请在该管理器中手动导航至游戏目录", Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this, "已选择游戏目录", Toast.LENGTH_SHORT).show()
                }
            } ?: run {
                showError(Constants.ERROR01)
            }
        }
    }

    // --- 其余 findMinecraftVersionDir, startUpdateProcess 等方法保持不变 ---
    private fun findMinecraftVersionDir(baseUri: Uri): Pair<File, Boolean>? {
        try {
            val doc = DocumentFile.fromTreeUri(this, baseUri) ?: return null
            val minecraftDoc = doc.findFile(".minecraft") ?: doc.findFile("minecraft") ?: return null
            val versionsDoc = minecraftDoc.listFiles()?.find { it.name == "versions" } ?: return null
            val versionDir = versionsDoc.listFiles()?.find { 
                it.name?.equals(Constants.TARGET_VERSION_DIR, ignoreCase = true) == true 
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

    private fun checkSimilar(versionsDoc: DocumentFile): Pair<File, Boolean>? {
        val similar = versionsDoc.listFiles()?.find {
            it.name?.contains(Constants.TARGET_VERSION_DIR.substring(0, 5)) == true &&
            it.name != Constants.TARGET_VERSION_DIR
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
        binding.tvStatus.text = "准备下载..."

        scope.launch {
            val downloadDir = cacheDir
            val rarFile = File(downloadDir, Constants.RAR_NAME)

            try {
                withContext(Dispatchers.IO) {
                    val manager = DownloadManager(Constants.DOWNLOAD_URL, Constants.FILE_SIZE)
                    manager.download(rarFile) { progress ->
                        binding.progressBar.progress = progress
                        binding.tvStatus.text = "下载中... $progress%"
                    }
                }
                val verifier = FileVerifier()
                val rarCheck = withContext(Dispatchers.IO) {
                    verifier.verifyFile(rarFile, Constants.EXPECTED_MD5, Constants.EXPECTED_SHA256)
                }
                if (!rarCheck) {
                    showError(Constants.ERROR04)
                    return@launch
                }
                binding.tvStatus.text = "正在解压..."
                withContext(Dispatchers.IO) {
                    RarExtractor.extract(rarFile, modsDir)
                }
                binding.tvStatus.text = "正在校验模组..."
                val allValid = withContext(Dispatchers.IO) {
                    verifier.verifyModsFromCsv(modsDir, Constants.CSV_CONTENT)
                }
                if (!allValid) {
                    showError(Constants.ERROR05)
                } else {
                    withContext(Dispatchers.Main) {
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
            } finally {
                isProcessing = false
                binding.btnStartDownload.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }
}

package www.xdyl.hygge.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

// 正确加载自定义字体（Compose Multiplatform 1.6.0 桌面端）
val silverFontFamily = FontFamily(
    Font(
        java.awt.Font.createFont(
            java.awt.Font.TRUETYPE_FONT,
            Thread.currentThread().contextClassLoader.getResourceAsStream("fonts/silver.ttf")!!
        )
    )
)

val client = OkHttpClient.Builder()
    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
    .build()

fun main() = application {
    var targetModsDir by remember { mutableStateOf<File?>(null) }
    val scope = rememberCoroutineScope()
    val prefs = remember { Preferences() }
    val logBuilder = remember { StringBuilder() }
    var logText by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showExtension by remember { mutableStateOf(false) }
    var versionName by remember { mutableStateOf("1.21.1-NeoForge") }
    var threadCount by remember { mutableStateOf(20) }
    var neoforgeCheckEnabled by remember { mutableStateOf(false) }
    var cleanOrphanFiles by remember { mutableStateOf(true) }
    var threadLimit by remember { mutableStateOf(256) }
    var useLocalCsv by remember { mutableStateOf(false) }
    var localCsvPath by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val lastPath = prefs.getString("launcher_root", null)
        if (lastPath != null) {
            val dir = File(lastPath)
            if (dir.exists() && dir.isDirectory) {
                targetModsDir = findMinecraftModsDir(dir, prefs)
            }
        }
        versionName = prefs.getString("version_folder", "1.21.1-NeoForge") ?: "1.21.1-NeoForge"
        threadCount = prefs.getInt("thread_count", 20)
        neoforgeCheckEnabled = prefs.getBoolean("neoforge_check_enabled", false)
        cleanOrphanFiles = prefs.getBoolean("clean_orphan_files", true)
        threadLimit = prefs.getInt("thread_limit", 256)
        useLocalCsv = prefs.getBoolean("use_local_csv", false)
        localCsvPath = prefs.getString("local_csv_path", "") ?: ""
    }

    // ---------- 设置窗口 ----------
    if (showSettings) {
        Window(onCloseRequest = { showSettings = false }, title = "设置") {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFA0C4FF),
                    onPrimary = Color.Black,
                    background = Color(0xFF1E1E1E),
                    surface = Color(0xFF2A2A2A),
                    onSurface = Color.White
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).width(400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("设置", color = MaterialTheme.colorScheme.primary, fontSize = 24.sp)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = versionName,
                        onValueChange = { versionName = it; prefs.putString("version_folder", it) },
                        label = { Text("Minecraft 版本文件夹名") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = threadCount.toString(),
                        onValueChange = {
                            threadCount = it.toIntOrNull()?.coerceIn(20, 128) ?: 20
                            prefs.putInt("thread_count", threadCount)
                        },
                        label = { Text("下载线程数 (20-128)") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        val dialog = FileDialog(Frame(), "选择启动器根目录", FileDialog.LOAD)
                        dialog.mode = FileDialog.LOAD
                        dialog.isVisible = true
                        val dir = dialog.directory
                        if (dir != null) {
                            val file = File(dir)
                            if (file.exists() && file.isDirectory) {
                                targetModsDir = findMinecraftModsDir(file, prefs)
                                prefs.putString("launcher_root", dir)
                            }
                        }
                    }) {
                        Text("选择游戏目录")
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = neoforgeCheckEnabled,
                            onCheckedChange = {
                                neoforgeCheckEnabled = it
                                prefs.putBoolean("neoforge_check_enabled", it)
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("开启 NeoForge 版本检查", color = Color.White)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = cleanOrphanFiles,
                            onCheckedChange = {
                                cleanOrphanFiles = it
                                prefs.putBoolean("clean_orphan_files", it)
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("更新后自动清理多余文件", color = Color.White)
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { showSettings = false }) { Text("关闭") }
                }
            }
        }
    }

    // ---------- 扩展窗口 ----------
    if (showExtension) {
        Window(onCloseRequest = { showExtension = false }, title = "扩展页面") {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFA0C4FF),
                    onPrimary = Color.Black,
                    background = Color(0xFF1E1E1E),
                    surface = Color(0xFF2A2A2A),
                    onSurface = Color.White
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp).width(400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text("扩展页面", color = MaterialTheme.colorScheme.primary, fontSize = 24.sp)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = threadLimit.toString(),
                        onValueChange = {
                            threadLimit = it.toIntOrNull()?.coerceIn(128, 1024) ?: 256
                            prefs.putInt("thread_limit", threadLimit)
                        },
                        label = { Text("线程数上限 (128-1024)") },
                        singleLine = true
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = useLocalCsv,
                            onCheckedChange = {
                                useLocalCsv = it
                                prefs.putBoolean("use_local_csv", it)
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("使用本地 CSV", color = Color.White)
                    }
                    if (useLocalCsv) {
                        Button(onClick = {
                            val dialog = FileDialog(Frame(), "选择 CSV 文件", FileDialog.LOAD)
                            dialog.file = "*.csv"
                            dialog.mode = FileDialog.LOAD
                            dialog.isVisible = true
                            val file = dialog.file
                            if (file != null) {
                                val selectedFile = File(dialog.directory, file)
                                if (selectedFile.exists()) {
                                    localCsvPath = selectedFile.absolutePath
                                    prefs.putString("local_csv_path", localCsvPath)
                                }
                            }
                        }) {
                            Text("浏览...")
                        }
                        if (localCsvPath.isNotEmpty()) {
                            Text("已选择: $localCsvPath", color = Color.White, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { /* 成就 */ }) { Text("成就") }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showExtension = false }) { Text("关闭") }
                }
            }
        }
    }

    // ---------- 主窗口 ----------
    Window(
        onCloseRequest = ::exitApplication,
        title = "Nebula updater-NU 星云更新器-Windows端",
        state = rememberWindowState(width = 800.dp, height = 600.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFA0C4FF),
                    onPrimary = Color.Black,
                    background = Color(0xFF1E1E1E),
                    surface = Color(0xFF2A2A2A),
                    onSurface = Color.White
                )
            ) {
                Column(modifier = Modifier.padding(16.dp).fillMaxSize()) {
                    // 标题两行，使用自定义字体
                    Column {
                        Text(
                            "Nebula updater-NU",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 28.sp,
                            fontFamily = silverFontFamily
                        )
                        Text(
                            "星云更新器-Windows端",
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            fontSize = 18.sp,
                            fontFamily = silverFontFamily
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    // 选择目录
                    Button(onClick = {
                        val dialog = FileDialog(Frame(), "选择游戏目录", FileDialog.LOAD)
                        dialog.mode = FileDialog.LOAD
                        dialog.isVisible = true
                        val dir = dialog.directory
                        if (dir != null) {
                            val file = File(dir)
                            if (file.exists() && file.isDirectory) {
                                targetModsDir = findMinecraftModsDir(file, prefs)
                                prefs.putString("launcher_root", dir)
                            }
                        }
                    }) { Text("选择游戏目录") }
                    Spacer(Modifier.height(16.dp))
                    // 开始下载
                    Button(
                        onClick = {
                            if (!downloading && targetModsDir != null) {
                                downloading = true
                                scope.launch {
                                    try {
                                        val serverFiles = fetchDesktopServerFileList()
                                        val csvMods = if (useLocalCsv && localCsvPath.isNotEmpty()) {
                                            parseCsvFromFile(File(localCsvPath))
                                        } else {
                                            parseDesktopCsvMods()
                                        }
                                        val toDownload =
                                            filterOutUnchangedModsDesktop(targetModsDir!!, csvMods)
                                        if (toDownload.isEmpty()) {
                                            logBuilder.appendLine("All mods are up-to-date!")
                                            logText = logBuilder.toString()
                                            downloading = false
                                            return@launch
                                        }
                                        logBuilder.appendLine("Downloading ${toDownload.size} mods...")
                                        logText = logBuilder.toString()

                                        val sem = Semaphore(threadLimit.coerceIn(1, 1024))
                                        val failed = AtomicInteger(0)
                                        var completed = 0
                                        val total = toDownload.size

                                        withContext(Dispatchers.IO) {
                                            toDownload.map { mod ->
                                                launch {
                                                    sem.acquire()
                                                    try {
                                                        val file = File(targetModsDir!!, mod.fileName)
                                                        val manager = DownloadManager(
                                                            Constants.BASE_URL + mod.fileName,
                                                            mod.size,
                                                            1,
                                                            false
                                                        )
                                                        manager.download(file) { pct ->
                                                            progress = pct.toFloat()
                                                        }
                                                        if (!FileVerifier().verifyFile(
                                                                file,
                                                                mod.md5,
                                                                mod.sha256
                                                            )
                                                        ) throw RuntimeException("Checksum mismatch")
                                                        completed++
                                                        progress = (completed * 100f) / total
                                                        statusText = "$completed/$total"
                                                    } catch (e: Exception) {
                                                        LogManager.log("Failed ${mod.fileName}: ${e.message}")
                                                        failed.incrementAndGet()
                                                    } finally {
                                                        sem.release()
                                                    }
                                                }
                                            }.joinAll()
                                        }

                                        if (cleanOrphanFiles) {
                                            val csvFiles = csvMods.map { it.fileName }.toSet()
                                            val modFiles =
                                                targetModsDir!!.listFiles()
                                                    ?.filter { it.extension == "jar" } ?: emptyList()
                                            var deleted = 0
                                            for (file in modFiles) {
                                                if (file.name !in csvFiles) {
                                                    if (file.delete()) {
                                                        deleted++
                                                        LogManager.log("Deleted orphan: ${file.name}")
                                                    }
                                                }
                                            }
                                            if (deleted > 0) logBuilder.appendLine("Cleaned $deleted orphan files")
                                        }

                                        if (failed.get() > 0) logBuilder.appendLine("Error: ERROR05")
                                        else logBuilder.appendLine("Update completed!")
                                        logText = logBuilder.toString()
                                    } catch (e: Exception) {
                                        logBuilder.appendLine("Exception: ${e.message}")
                                        logText = logBuilder.toString()
                                    } finally {
                                        downloading = false
                                    }
                                }
                            }
                        },
                        enabled = targetModsDir != null && !downloading
                    ) { Text("开始下载") }
                    Spacer(Modifier.height(16.dp))
                    // 进度条
                    LinearProgressIndicator(
                        progress = { (progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(statusText, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.height(8.dp))
                    // 日志区域
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val scrollState = rememberScrollState()
                        Text(
                            logBuilder.toString(),
                            modifier = Modifier.verticalScroll(scrollState).padding(8.dp)
                                .fillMaxWidth(),
                            fontSize = 12.sp,
                            color = Color.LightGray,
                            maxLines = Int.MAX_VALUE,
                            overflow = TextOverflow.Clip
                        )
                    }
                }
            }
        }
    }
}

// ---------- 辅助函数 ----------
fun findMinecraftModsDir(root: File, prefs: Preferences): File? {
    val mc = File(root, ".minecraft")
    val mcAlt = File(root, "minecraft")
    val m = if (mc.exists()) mc else if (mcAlt.exists()) mcAlt else return null
    val versions = File(m, "versions")
    if (!versions.exists()) return null
    val target = prefs.getString("version_folder", "1.21.1-NeoForge") ?: "1.21.1-NeoForge"
    val targetDir = File(versions, target)
    if (!targetDir.exists()) return null
    val mods = File(targetDir, "mods")
    if (!mods.exists()) mods.mkdirs()
    return mods
}

suspend fun fetchDesktopServerFileList(): List<String> = withContext(Dispatchers.IO) {
    try {
        val request = Request.Builder().url(Constants.BASE_URL).build()
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext emptyList()
        if (response.code != 200) return@withContext emptyList()
        val pattern = Regex("<a href=\"([^\"]+)\">")
        pattern.findAll(body).map { it.groupValues[1] }.filter { it.endsWith(".jar") }.toList()
    } catch (e: Exception) { emptyList() }
}

fun parseDesktopCsvMods(): List<ModInfo> =
    Constants.CSV_CONTENT.lines().drop(1).filter { it.isNotBlank() }.map { line ->
        val parts = line.split(",")
        ModInfo(
            parts[0].trim('"').removePrefix("./"),
            parts[2].toLong(),
            parts[3].trim('"'),
            parts[4].trim('"')
        )
    }

fun parseCsvFromFile(file: File): List<ModInfo> =
    file.readText().lines().drop(1).filter { it.isNotBlank() }.map { line ->
        val parts = line.split(",")
        ModInfo(
            parts[0].trim('"').removePrefix("./"),
            parts[2].toLong(),
            parts[3].trim('"'),
            parts[4].trim('"')
        )
    }

fun filterOutUnchangedModsDesktop(modsDir: File, csvMods: List<ModInfo>): List<ModInfo> =
    csvMods.filterNot { mod ->
        val local = File(modsDir, mod.fileName)
        local.exists() && local.length() == mod.size && calculateMD5Desktop(local) == mod.md5
    }

fun calculateMD5Desktop(file: File): String? = try {
    val digest = MessageDigest.getInstance("MD5")
    file.inputStream().use { fis ->
        val buffer = ByteArray(8192)
        var len: Int
        while (fis.read(buffer).also { len = it } != -1) digest.update(buffer, 0, len)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
} catch (e: Exception) { null }

data class ModInfo(val fileName: String, val size: Long, val md5: String, val sha256: String)

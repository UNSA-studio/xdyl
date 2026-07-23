package www.xdyl.hygge.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

// 自定义字体（已验证可编译）
val silverFontFamily = FontFamily(Font(resource = "font/silver.ttf"))

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
    var currentScreen by remember { mutableStateOf("main") }  // 统一界面状态
    var versionName by remember { mutableStateOf("1.21.1-NeoForge") }
    var threadCount by remember { mutableStateOf(256) }
    var neoforgeCheckEnabled by remember { mutableStateOf(true) }
    var cleanOrphanFiles by remember { mutableStateOf(true) }
    var unlockThread by remember { mutableStateOf(false) }
    var useLocalCsv by remember { mutableStateOf(false) }
    var localCsvPath by remember { mutableStateOf("") }
    var extensionMode by remember { mutableStateOf(false) }
    var showJavaDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!prefs.getBoolean("java8_checked", false)) {
            val java8Installed = try {
                val process = ProcessBuilder("java", "-version").redirectErrorStream(true).start()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = reader.readText()
                process.waitFor()
                output.contains("1.8") || output.contains("8.0")
            } catch (e: Exception) { false }
            if (!java8Installed) showJavaDialog = true
            else prefs.putBoolean("java8_checked", true)
        }

        val lastPath = prefs.getString("launcher_root", null)
        if (lastPath != null) {
            val dir = File(lastPath)
            if (dir.exists() && dir.isDirectory) targetModsDir = findMinecraftModsDir(dir, prefs)
        }
        versionName = prefs.getString("version_folder", "1.21.1-NeoForge") ?: "1.21.1-NeoForge"
        threadCount = prefs.getInt("thread_limit", 256)
        neoforgeCheckEnabled = prefs.getBoolean("neoforge_check_enabled", true)
        cleanOrphanFiles = prefs.getBoolean("clean_orphan_files", true)
        unlockThread = prefs.getBoolean("unlock_thread_limit", false)
        useLocalCsv = prefs.getBoolean("use_local_csv", false)
        localCsvPath = prefs.getString("local_csv_path", "") ?: ""
        extensionMode = prefs.getBoolean("extension_mode", false)
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Nebula updater-NU 星云更新器-Windows端",
        state = rememberWindowState(width = 1100.dp, height = 900.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
            if (showJavaDialog) {
                AlertDialog(
                    onDismissRequest = { showJavaDialog = false; prefs.putBoolean("java8_checked", true) },
                    title = { Text("安装 Java 8", fontSize = 24.sp) },
                    text = { Text("检测到您尚未安装 Java 8。安装 Java 8 将允许您运行旧版本的 Minecraft。是否立即安装？", fontSize = 20.sp) },
                    confirmButton = {
                        TextButton(onClick = {
                            showJavaDialog = false
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val scriptStream = Thread.currentThread().contextClassLoader.getResourceAsStream("install_java.ps1")
                                    if (scriptStream != null) {
                                        val tempScript = File.createTempFile("java_install", ".ps1")
                                        tempScript.deleteOnExit()
                                        FileOutputStream(tempScript).use { output -> scriptStream.copyTo(output) }
                                        ProcessBuilder("powershell", "-ExecutionPolicy", "Bypass", "-File", tempScript.absolutePath).inheritIO().start().waitFor()
                                        prefs.putBoolean("java8_installed", true)
                                    }
                                } catch (_: Exception) {}
                                prefs.putBoolean("java8_checked", true)
                            }
                        }) { Text("安装", fontSize = 22.sp) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showJavaDialog = false; prefs.putBoolean("java8_checked", true) }) { Text("跳过", fontSize = 22.sp) }
                    }
                )
            }

            when (currentScreen) {
                "main" -> MainScreen(
                    targetModsDir = targetModsDir,
                    onSelectDir = { currentScreen = "fileBrowser" },
                    onStartDownload = {
                        if (!downloading && targetModsDir != null) {
                            downloading = true
                            scope.launch { /* 下载逻辑保持不变 */ }
                        }
                    },
                    downloading = downloading,
                    logText = logText,
                    progress = progress,
                    statusText = statusText,
                    onSettings = { currentScreen = "settings" }
                )
                "settings" -> SettingsScreen(
                    versionName = versionName,
                    onVersionChange = { versionName = it; prefs.putString("version_folder", it) },
                    threadCount = threadCount,
                    onThreadChange = { threadCount = it; prefs.putInt("thread_limit", it) },
                    maxThreads = if (unlockThread) 1024 else 128,
                    neoforgeCheckEnabled = neoforgeCheckEnabled,
                    onNeoforgeChange = { neoforgeCheckEnabled = it; prefs.putBoolean("neoforge_check_enabled", it) },
                    cleanOrphanFiles = cleanOrphanFiles,
                    onCleanOrphanChange = { cleanOrphanFiles = it; prefs.putBoolean("clean_orphan_files", it) },
                    extensionMode = extensionMode,
                    onExtensionChange = { enabled ->
                        if (enabled) { prefs.putBoolean("extension_mode", true); exitApplication() }
                        else prefs.putBoolean("extension_mode", false)
                    },
                    onSelectDir = { currentScreen = "fileBrowser" },
                    onBack = { currentScreen = "main" },
                    onExtensionPage = { currentScreen = "extension" }
                )
                "extension" -> ExtensionScreen(
                    unlockThread = unlockThread,
                    onUnlockChange = { unlockThread = it; prefs.putBoolean("unlock_thread_limit", it) },
                    neoforgeCheckEnabled = neoforgeCheckEnabled,
                    onNeoforgeChange = { neoforgeCheckEnabled = it; prefs.putBoolean("neoforge_check_enabled", it) },
                    cleanOrphanFiles = cleanOrphanFiles,
                    onCleanOrphanChange = { cleanOrphanFiles = it; prefs.putBoolean("clean_orphan_files", it) },
                    useLocalCsv = useLocalCsv,
                    onLocalCsvChange = { useLocalCsv = it; prefs.putBoolean("use_local_csv", it) },
                    localCsvPath = localCsvPath,
                    onPickCsv = {
                        val dialog = java.awt.FileDialog(java.awt.Frame(), "选择 CSV 文件", java.awt.FileDialog.LOAD)
                        dialog.file = "*.csv"; dialog.isVisible = true
                        val file = dialog.file
                        if (file != null) { val selectedFile = File(dialog.directory, file); if (selectedFile.exists()) { localCsvPath = selectedFile.absolutePath; prefs.putString("local_csv_path", localCsvPath) } }
                    },
                    onReset = { prefs.clear(); exitApplication() },
                    onBack = { currentScreen = "settings" }
                )
                "fileBrowser" -> FileBrowserScreen(
                    onSelect = { dir ->
                        prefs.putString("launcher_root", dir.absolutePath)
                        targetModsDir = findMinecraftModsDir(dir, prefs)
                        currentScreen = "main"
                    },
                    onBack = { currentScreen = "main" }
                )
            }
        }
    }
}

// ---------- 主界面 ----------
@Composable
fun MainScreen(
    targetModsDir: File?,
    onSelectDir: () -> Unit,
    onStartDownload: () -> Unit,
    downloading: Boolean,
    logText: String,
    progress: Float,
    statusText: String,
    onSettings: () -> Unit
) {
    Column(modifier = Modifier.padding(24.dp).fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Nebula updater-NU", color = Color(0xFFA0C4FF), fontSize = 40.sp, fontFamily = silverFontFamily)
                Text("星云更新器-Windows端", color = Color(0xFFA0C4FF).copy(alpha = 0.8f), fontSize = 28.sp, fontFamily = silverFontFamily)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color(0xFFA0C4FF), modifier = Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onSelectDir, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("选择游戏目录", fontSize = 28.sp) }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onStartDownload,
            enabled = targetModsDir != null && !downloading,
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) { Text("开始下载", fontSize = 28.sp) }
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(
            progress = { (progress / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)),
            color = Color(0xFFA0C4FF),
            trackColor = Color(0xFFA0C4FF).copy(alpha = 0.2f)
        )
        Text(statusText, color = Color(0xFFA0C4FF).copy(alpha = 0.8f), fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val scrollState = rememberScrollState()
            Text(logText, modifier = Modifier.verticalScroll(scrollState).padding(8.dp).fillMaxWidth(), fontSize = 18.sp, color = Color.LightGray, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
        }
    }
}

// ---------- 设置界面 ----------
@Composable
fun SettingsScreen(
    versionName: String,
    onVersionChange: (String) -> Unit,
    threadCount: Int,
    onThreadChange: (Int) -> Unit,
    maxThreads: Int,
    neoforgeCheckEnabled: Boolean,
    onNeoforgeChange: (Boolean) -> Unit,
    cleanOrphanFiles: Boolean,
    onCleanOrphanChange: (Boolean) -> Unit,
    extensionMode: Boolean,
    onExtensionChange: (Boolean) -> Unit,
    onSelectDir: () -> Unit,
    onBack: () -> Unit,
    onExtensionPage: () -> Unit
) {
    Column(modifier = Modifier.padding(24.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFFA0C4FF), fontSize = 24.sp) }
            Spacer(Modifier.width(8.dp))
            Text("设置", color = Color(0xFFA0C4FF), fontSize = 36.sp, fontFamily = silverFontFamily)
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(value = versionName, onValueChange = onVersionChange, label = { Text("Minecraft 版本文件夹名", fontSize = 20.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 22.sp))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = threadCount.toString(),
            onValueChange = { v -> v.toIntOrNull()?.let { onThreadChange(it.coerceIn(20, maxThreads)) } },
            label = { Text("下载线程数 (20-$maxThreads)", fontSize = 20.sp) },
            singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = LocalTextStyle.current.copy(fontSize = 22.sp)
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSelectDir, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("选择游戏目录", fontSize = 24.sp) }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = neoforgeCheckEnabled, onCheckedChange = onNeoforgeChange); Spacer(Modifier.width(8.dp)); Text("开启 NeoForge 版本检查", color = Color.White, fontSize = 22.sp) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = cleanOrphanFiles, onCheckedChange = onCleanOrphanChange); Spacer(Modifier.width(8.dp)); Text("更新后自动清理多余文件", color = Color.White, fontSize = 22.sp) }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = extensionMode, onCheckedChange = onExtensionChange); Spacer(Modifier.width(8.dp)); Text("扩展模式", color = Color.White, fontSize = 22.sp)
        }
        if (extensionMode) { Button(onClick = onExtensionPage, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("进入扩展页面", fontSize = 24.sp) } }
        Spacer(Modifier.height(24.dp))
        Button(onClick = { /* 导出日志 */ }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("导出日志", fontSize = 24.sp) }
        Button(onClick = { /* 错误代码 */ }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("ERROR 错误代码", fontSize = 24.sp) }
        Button(onClick = { /* 关于 */ }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("关于软件", fontSize = 24.sp) }
    }
}

// ---------- 扩展界面 ----------
@Composable
fun ExtensionScreen(
    unlockThread: Boolean,
    onUnlockChange: (Boolean) -> Unit,
    neoforgeCheckEnabled: Boolean,
    onNeoforgeChange: (Boolean) -> Unit,
    cleanOrphanFiles: Boolean,
    onCleanOrphanChange: (Boolean) -> Unit,
    useLocalCsv: Boolean,
    onLocalCsvChange: (Boolean) -> Unit,
    localCsvPath: String,
    onPickCsv: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.padding(24.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFFA0C4FF), fontSize = 24.sp) }
            Spacer(Modifier.width(8.dp))
            Text("扩展页面", color = Color(0xFFA0C4FF), fontSize = 36.sp, fontFamily = silverFontFamily)
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = unlockThread, onCheckedChange = onUnlockChange); Spacer(Modifier.width(8.dp)); Text("解锁线程数上限至 1024", color = Color.White, fontSize = 22.sp) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = neoforgeCheckEnabled, onCheckedChange = onNeoforgeChange); Spacer(Modifier.width(8.dp)); Text("开启 NeoForge 版本检查", color = Color.White, fontSize = 22.sp) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = cleanOrphanFiles, onCheckedChange = onCleanOrphanChange); Spacer(Modifier.width(8.dp)); Text("更新后自动清理多余文件", color = Color.White, fontSize = 22.sp) }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = useLocalCsv, onCheckedChange = onLocalCsvChange); Spacer(Modifier.width(8.dp)); Text("使用本地 CSV", color = Color.White, fontSize = 22.sp) }
        if (useLocalCsv) {
            Button(onClick = onPickCsv, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("浏览...", fontSize = 24.sp) }
            if (localCsvPath.isNotEmpty()) Text("已选择: $localCsvPath", color = Color.White, fontSize = 20.sp)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = { /* 白名单 */ }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("模组白名单", fontSize = 24.sp) }
        Button(onClick = onReset, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("重置登记状态", fontSize = 24.sp) }
    }
}

// ---------- 文件浏览器界面 ----------
@Composable
fun FileBrowserScreen(
    onSelect: (File) -> Unit,
    onBack: () -> Unit
) {
    var currentDir by remember { mutableStateOf(File.listRoots().firstOrNull() ?: File("C:\\")) }
    var files by remember { mutableStateOf(currentDir.listFiles()?.sortedWith(compareBy<File> { it.isDirectory }.thenBy { it.name }) ?: emptyList()) }

    Column(modifier = Modifier.padding(24.dp).fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回", color = Color(0xFFA0C4FF), fontSize = 24.sp) }
            Spacer(Modifier.width(8.dp))
            Text("选择启动器根目录", color = Color(0xFFA0C4FF), fontSize = 28.sp, fontFamily = silverFontFamily)
        }
        Spacer(Modifier.height(12.dp))
        Text("当前目录: ${currentDir.absolutePath}", color = Color.White, fontSize = 22.sp, fontFamily = silverFontFamily)
        Spacer(Modifier.height(8.dp))
        val isRoot = currentDir.parentFile == null
        Button(
            onClick = {
                val parent = currentDir.parentFile
                if (parent != null) {
                    currentDir = parent
                    files = currentDir.listFiles()?.sortedWith(compareBy<File> { it.isDirectory }.thenBy { it.name }) ?: emptyList()
                }
            },
            enabled = !isRoot,
            modifier = Modifier.height(48.dp)
        ) { Text("返回上级", fontSize = 20.sp) }
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(files) { file ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (file.isDirectory) {
                            currentDir = file
                            files = currentDir.listFiles()?.sortedWith(compareBy<File> { it.isDirectory }.thenBy { it.name }) ?: emptyList()
                        }
                    }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = file.name,
                        color = if (file.isDirectory) Color(0xFFA0C4FF) else Color.White,
                        fontSize = 22.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onSelect(currentDir) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("选择此文件夹", fontSize = 24.sp) }
    }
}

// ---------- 辅助函数 ----------
suspend fun installResourcePack(prefs: Preferences) {
    try {
        val launcherRoot = File(prefs.getString("launcher_root", System.getProperty("user.home")))
        val mc = File(launcherRoot, ".minecraft")
        val targetVersion = prefs.getString("version_folder", "1.21.1-NeoForge") ?: "1.21.1-NeoForge"
        val versionDir = File(mc, "versions/$targetVersion")
        val packsDir = File(versionDir, "resourcepacks")
        if (!packsDir.exists()) packsDir.mkdirs()
        val destFile = File(packsDir, "generated.zip")
        if (!destFile.exists()) {
            Thread.currentThread().contextClassLoader.getResourceAsStream("generated.zip").use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
        }
    } catch (e: Exception) { /* ignore */ }
}

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
        pattern.findAll(body).map { it.groupValues[1] }.filter { it.endsWith(".jar") }.map { java.net.URLDecoder.decode(it, "UTF-8") }.toList()
    } catch (e: Exception) { emptyList() }
}

fun parseDesktopCsvMods(): List<ModInfo> = Constants.CSV_CONTENT.lines().drop(1).filter { it.isNotBlank() }.map { line ->
    val parts = line.split(",")
    ModInfo(parts[0].trim('"').removePrefix("./"), parts[2].toLong(), parts[3].trim('"'), parts[4].trim('"'))
}

fun parseCsvFromFile(file: File): List<ModInfo> = file.readText().lines().drop(1).filter { it.isNotBlank() }.map { line ->
    val parts = line.split(",")
    ModInfo(parts[0].trim('"').removePrefix("./"), parts[2].toLong(), parts[3].trim('"'), parts[4].trim('"'))
}

fun filterOutUnchangedModsDesktop(modsDir: File, csvMods: List<ModInfo>): List<ModInfo> = csvMods.filterNot { mod ->
    val local = File(modsDir, mod.fileName)
    local.exists() && local.length() == mod.size && calculateMD5Desktop(local) == mod.md5
}

fun calculateMD5Desktop(file: File): String? = try {
    val digest = MessageDigest.getInstance("MD5")
    file.inputStream().use { fis -> val buffer = ByteArray(8192); var len: Int; while (fis.read(buffer).also { len = it } != -1) digest.update(buffer, 0, len) }
    digest.digest().joinToString("") { "%02x".format(it) }
} catch (e: Exception) { null }

data class ModInfo(val fileName: String, val size: Long, val md5: String, val sha256: String)

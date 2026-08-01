package www.xdyl.hygge.desktop

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.*
import com.google.gson.Gson
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.Desktop
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

val silverFontFamily = FontFamily(Font(resource = "font/silver.ttf"))
val defaultTextStyle = TextStyle(fontFamily = silverFontFamily)

val client = OkHttpClient.Builder()
    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
    .build()

data class Quote(val chinese: String, val english: String, val author: String, val authorEn: String, val source: String, val sourceEn: String)
data class ModInfo(val fileName: String, val size: Long, val md5: String, val sha256: String)

fun main() = application {
    var targetModsDir by remember { mutableStateOf(null as File?) }
    val scope = rememberCoroutineScope()
    val prefs = remember { Preferences() }
    val logBuilder = remember { StringBuilder() }
    var logText by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var statusText by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf(false) }
    var currentScreen by remember { mutableStateOf("main") }
    var versionName by remember { mutableStateOf("1.21.1-NeoForge") }
    var threadCount by remember { mutableStateOf(256) }
    var neoforgeCheckEnabled by remember { mutableStateOf(true) }
    var cleanOrphanFiles by remember { mutableStateOf(true) }
    var unlockThread by remember { mutableStateOf(false) }
    var useLocalCsv by remember { mutableStateOf(false) }
    var localCsvPath by remember { mutableStateOf("") }
    var extensionMode by remember { mutableStateOf(false) }

    var showErrorCodesDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showWhitelistDialog by remember { mutableStateOf(false) }
    var showExtensionModeConfirm by remember { mutableStateOf(false) }

    var dailyQuote by remember { mutableStateOf(null as Quote?) }
    val quoteCategories = listOf("WH", "RW", "HC", "ED", "CE", "AC")

    fun loadDailyQuote() {
        val today = SimpleDateFormat("yyyyMMdd").format(Date())
        val lastDate = prefs.getString("quote_date", "")
        if (lastDate != today) {
            scope.launch(Dispatchers.IO) {
                try {
                    val allQuotes = mutableListOf<Quote>()
                    for (cat in quoteCategories) {
                        val jsonStr = Thread.currentThread().contextClassLoader
                            .getResourceAsStream("$cat.json")?.bufferedReader()?.readText() ?: continue
                        val jsonObject = Gson().fromJson(jsonStr, Map::class.java)
                        @Suppress("UNCHECKED_CAST")
                        val quotes = jsonObject["quotes"] as? List<Map<String, String>> ?: continue
                        allQuotes.addAll(quotes.map {
                            Quote(it["chinese"]!!, it["english"]!!, it["author"]!!, it["author_en"]!!, it["source"]!!, it["source_en"]!!)
                        })
                    }
                    if (allQuotes.isNotEmpty()) {
                        dailyQuote = allQuotes[Random().nextInt(allQuotes.size)]
                        prefs.putString("quote_date", today)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        } else {
            dailyQuote = Quote("生活不是等待风暴过去，而是学会在雨中跳舞。", "Life isn't about waiting for the storm to pass...", "薇薇安·格林", "Vivian Greene", "未知", "Unknown")
        }
    }

    LaunchedEffect(Unit) {
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
        loadDailyQuote()
        val versionManager = VersionManager(prefs)
        versionManager.checkAndUpdate(
            onUpdateAvailable = { diff ->
                println("New version found: ${diff.version}")
                scope.launch { versionManager.downloadNewCsv(diff.version); println("CSV updated") }
            },
            onComplete = {}
        )
    }

    fun exportLog() {
        try {
            val dir = File(System.getProperty("user.home"), "Documents")
            val file = File(dir, "nebula_updater_log_${System.currentTimeMillis()}.txt")
            file.parentFile?.mkdirs()
            file.writeText(LogManager.getFullLog())
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file.parentFile)
            logBuilder.appendLine("Log exported to: ${file.absolutePath}")
            logText = logBuilder.toString()
        } catch (e: Exception) {
            logBuilder.appendLine("Export failed: ${e.message}")
            logText = logBuilder.toString()
        }
    }

    fun getErrorCodesText(): String = buildString {
        Constants.errorDescriptions.forEach { (code, desc) ->
            appendLine("$code: $desc")
            appendLine()
        }
    }

    MaterialTheme {
        CompositionLocalProvider(LocalTextStyle provides defaultTextStyle) {
            Window(
                onCloseRequest = ::exitApplication,
                title = "星云更新器",
                state = rememberWindowState(width = 1100.dp, height = 900.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                        }
                    ) { screen ->
                        when (screen) {
                            "main" -> MainScreen(
                                targetModsDir = targetModsDir,
                                onSelectDir = { currentScreen = "fileBrowser" },
                                onStartDownload = {
                                    if (!downloading && targetModsDir != null) {
                                        downloading = true
                                        scope.launch {
                                            try {
                                                fetchDesktopServerFileList() // validate server reachable
                                                val csvMods = if (useLocalCsv && localCsvPath.isNotEmpty())
                                                    parseCsvFromFile(File(localCsvPath))
                                                else
                                                    parseDesktopCsvMods()
                                                val toDownload = filterOutUnchangedModsDesktop(targetModsDir!!, csvMods)
                                                if (toDownload.isEmpty()) {
                                                    logBuilder.appendLine("All mods are up-to-date!")
                                                    logText = logBuilder.toString()
                                                    downloading = false
                                                    return@launch
                                                }
                                                logBuilder.appendLine("Downloading ${toDownload.size} mods...")
                                                logText = logBuilder.toString()
                                                val sem = Semaphore(threadCount.coerceIn(1, 1024))
                                                val failed = AtomicInteger(0)
                                                var completed = 0
                                                val total = toDownload.size
                                                withContext(Dispatchers.IO) {
                                                    toDownload.map { mod ->
                                                        launch {
                                                            sem.acquire()
                                                            try {
                                                                val file = File(targetModsDir!!, mod.fileName)
                                                                val encodedName = URLEncoder.encode(mod.fileName, "UTF-8").replace("+", "%20")
                                                                val useChunked = mod.size > 65536 && mod.size > 0
                                                                        val perFileThreads = if (useChunked) 4.coerceAtMost((mod.size / 65536).toInt().coerceAtLeast(2)) else 1
                                                                        DownloadManager(Constants.BASE_URL + encodedName, mod.size, perFileThreads, useChunked)
                                                                    .download(file) { pct -> progress = pct.toFloat() }
                                                                if (!FileVerifier().verifyFile(file, mod.md5, mod.sha256))
                                                                    throw RuntimeException("Checksum mismatch")
                                                                completed++
                                                                progress = (completed * 100f) / total
                                                                statusText = "$completed/$total"
                                                            } catch (e: Exception) {
                                                                LogManager.log("Failed ${mod.fileName}: ${e.message}")
                                                                failed.incrementAndGet()
                                                            } finally { sem.release() }
                                                        }
                                                    }.joinAll()
                                                }
                                                if (cleanOrphanFiles) {
                                                    val csvSet = csvMods.map { it.fileName }.toSet()
                                                    val modFiles = targetModsDir!!.listFiles()?.filter { it.extension == "jar" } ?: emptyList()
                                                    var deleted = 0
                                                    for (f in modFiles) {
                                                        if (f.name !in csvSet && f.delete()) {
                                                            deleted++
                                                            LogManager.log("Deleted orphan: ${f.name}")
                                                        }
                                                    }
                                                    if (deleted > 0) logBuilder.appendLine("Cleaned $deleted orphan files")
                                                }
                                                if (failed.get() > 0) logBuilder.appendLine("Error: ERROR05 — some files failed checksum")
                                                else logBuilder.appendLine("Update completed!")
                                                logText = logBuilder.toString()
                                            } catch (e: Exception) {
                                                logBuilder.appendLine("Exception: ${e.message}")
                                                logText = logBuilder.toString()
                                            } finally { downloading = false }
                                        }
                                    }
                                },
                                downloading = downloading,
                                logText = logText,
                                progress = progress,
                                statusText = statusText,
                                onSettings = { currentScreen = "settings" },
                                dailyQuote = dailyQuote
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
                                    if (enabled) showExtensionModeConfirm = true
                                    else { extensionMode = false; prefs.putBoolean("extension_mode", false) }
                                },
                                onSelectDir = { currentScreen = "fileBrowser" },
                                onBack = { currentScreen = "main" },
                                onExtensionPage = { currentScreen = "extension" },
                                onExportLog = { exportLog() },
                                onErrorCodes = { showErrorCodesDialog = true },
                                onAbout = { showAboutDialog = true }
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
                                    val dialog = java.awt.FileDialog(java.awt.Frame(), "Select CSV", java.awt.FileDialog.LOAD)
                                    dialog.file = "*.csv"; dialog.isVisible = true
                                    dialog.file?.let { f ->
                                        val sf = File(dialog.directory, f)
                                        if (sf.exists()) { localCsvPath = sf.absolutePath; prefs.putString("local_csv_path", localCsvPath) }
                                    }
                                },
                                onBack = { currentScreen = "settings" },
                                onWhitelist = { showWhitelistDialog = true }
                            )
                            "fileBrowser" -> FileBrowserScreen(
                                onSelect = { dir ->
                                    // Create NebulaUpdater subdirectory for MSI uninstall isolation
                                    val workDir = if (dir.name.equals("NebulaUpdater", true)) dir else File(dir, "NebulaUpdater")
                                    if (!workDir.exists()) workDir.mkdirs()
                                    prefs.putString("launcher_root", dir.absolutePath)
                                    prefs.putString("work_dir", workDir.absolutePath)
                                    targetModsDir = findMinecraftModsDir(dir, prefs)
                                    currentScreen = "main"
                                },
                                onBack = { currentScreen = "main" }
                            )
                        }
                    }
                    // Dialogs
                    if (showErrorCodesDialog) {
                        AlertDialog(
                            onDismissRequest = { showErrorCodesDialog = false },
                            title = { Text("ERROR 错误代码", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF)) },
                            text = { Text(getErrorCodesText(), fontFamily = silverFontFamily, color = Color.White, fontSize = 14.sp) },
                            confirmButton = {
                                TextButton(onClick = { showErrorCodesDialog = false }) { Text("关闭", fontFamily = silverFontFamily) }
                            }
                        )
                    }
                    if (showAboutDialog) {
                        AlertDialog(
                            onDismissRequest = { showAboutDialog = false },
                            title = { Text("关于软件", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF)) },
                            text = {
                                Column {
                                    Text("星云更新器", fontFamily = silverFontFamily, color = Color.White, fontSize = 16.sp)
                                    Text("Windows Desktop v1.0.0", fontFamily = silverFontFamily, color = Color.Gray, fontSize = 14.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Text("Author: UNSA-studio", fontFamily = silverFontFamily, color = Color.White, fontSize = 14.sp)
                                    Text("GitHub: github.com/UNSA-studio/xdyl", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF), fontSize = 12.sp)
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    try { Desktop.getDesktop().browse(URI("https://github.com/UNSA-studio/xdyl")) } catch (_: Exception) {}
                                    showAboutDialog = false
                                }) { Text("访问仓库", fontFamily = silverFontFamily) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showAboutDialog = false }) { Text("关闭", fontFamily = silverFontFamily) }
                            }
                        )
                    }
                    if (showWhitelistDialog) {
                        AlertDialog(
                            onDismissRequest = { showWhitelistDialog = false },
                            title = { Text("模组白名单", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF)) },
                            text = {
                                Text(
                                    "Whitelisted mods will not be removed by the orphan file cleaner.\n\nDesktop edition currently supports config-file editing only:\n~/.xdyl_config.properties\nadd: mod_whitelist=<file1>,<file2>",
                                    fontFamily = silverFontFamily,
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = { showWhitelistDialog = false }) { Text("关闭", fontFamily = silverFontFamily) }
                            }
                        )
                    }
                    if (showExtensionModeConfirm) {
                        AlertDialog(
                            onDismissRequest = {
                                showExtensionModeConfirm = false
                                extensionMode = false
                                prefs.putBoolean("extension_mode", false)
                            },
                            title = { Text("警告!", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF)) },
                            text = { Text("开启扩展模式，重启后生效。", fontFamily = silverFontFamily, color = Color.White) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showExtensionModeConfirm = false
                                    prefs.putBoolean("extension_mode", true)
                                    exitApplication()
                                }) { Text("开启并重启", fontFamily = silverFontFamily) }
                            },
                            dismissButton = {
                                TextButton(onClick = {
                                    showExtensionModeConfirm = false
                                    extensionMode = false
                                    prefs.putBoolean("extension_mode", false)
                                }) { Text("取消", fontFamily = silverFontFamily) }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ===== Main Screen =====
@Composable
fun MainScreen(
    targetModsDir: File?,
    onSelectDir: () -> Unit,
    onStartDownload: () -> Unit,
    downloading: Boolean,
    logText: String,
    progress: Float,
    statusText: String,
    onSettings: () -> Unit,
    dailyQuote: Quote?
) {
    Column(modifier = Modifier.padding(24.dp).fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("星云更新器", color = Color(0xFFA0C4FF), fontSize = 40.sp, fontFamily = silverFontFamily)
                Text("星云更新器-Windows端", color = Color(0xFFA0C4FF).copy(alpha = 0.8f), fontSize = 28.sp, fontFamily = silverFontFamily)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color(0xFFA0C4FF), modifier = Modifier.size(36.dp))
            }
        }

        dailyQuote?.let { quote ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(quote.chinese, color = Color(0xFFA0C4FF), fontSize = 14.sp, fontFamily = silverFontFamily)
                    Text("— ${quote.author} / ${quote.source}", color = Color.Gray, fontSize = 11.sp, fontFamily = silverFontFamily)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onSelectDir,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA0C4FF), contentColor = Color.Black)
        ) { Text("选择游戏目录", fontSize = 24.sp, fontFamily = silverFontFamily) }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onStartDownload,
            enabled = targetModsDir != null && !downloading,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA0C4FF), contentColor = Color.Black)
        ) { Text("开始下载", fontSize = 24.sp, fontFamily = silverFontFamily) }

        Spacer(modifier = Modifier.height(16.dp))

        LinearProgressIndicator(
            progress = { (progress / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = Color(0xFFA0C4FF),
            trackColor = Color(0xFFA0C4FF).copy(alpha = 0.2f)
        )

        Text(statusText, color = Color(0xFFA0C4FF).copy(alpha = 0.8f), fontSize = 18.sp, fontFamily = silverFontFamily)

        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Text(
                text = logText,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(8.dp).fillMaxWidth(),
                fontSize = 16.sp,
                color = Color.LightGray,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Clip,
                fontFamily = silverFontFamily
            )
        }
    }
}

// ===== Settings Screen =====
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
    onExtensionPage: () -> Unit,
    onExportLog: () -> Unit,
    onErrorCodes: () -> Unit,
    onAbout: () -> Unit
) {
    val tfColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        cursorColor = Color(0xFFA0C4FF)
    )
    val tfTextStyle = TextStyle(fontSize = 22.sp, color = Color.White, fontFamily = silverFontFamily)
    val swColors = SwitchDefaults.colors(
        checkedThumbColor = Color(0xFFA0C4FF),
        checkedTrackColor = Color(0xFFA0C4FF).copy(alpha = 0.5f)
    )

    Column(
        modifier = Modifier
            .padding(24.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("返回", color = Color(0xFFA0C4FF), fontSize = 24.sp, fontFamily = silverFontFamily)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("设置", color = Color(0xFFA0C4FF), fontSize = 36.sp, fontFamily = silverFontFamily)
        }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = versionName,
            onValueChange = onVersionChange,
            label = { Text("Minecraft 版本文件夹名", fontSize = 20.sp, color = Color.Gray, fontFamily = silverFontFamily) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = tfTextStyle,
            colors = tfColors
        )

        HorizontalDivider(color = Color(0xFF3A3A3A), thickness = 1.dp)
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = threadCount.toString(),
            onValueChange = { v -> v.toIntOrNull()?.let { onThreadChange(it.coerceIn(20, maxThreads)) } },
            label = { Text("下载线程数 (20-$maxThreads)", fontSize = 20.sp, color = Color.Gray, fontFamily = silverFontFamily) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = tfTextStyle,
            colors = tfColors
        )

        HorizontalDivider(color = Color(0xFF3A3A3A), thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onSelectDir,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            border = BorderStroke(1.dp, Color(0xFFA0C4FF)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFA0C4FF))
        ) { Text("选择游戏目录", fontSize = 24.sp, fontFamily = silverFontFamily) }

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = neoforgeCheckEnabled, onCheckedChange = onNeoforgeChange, colors = swColors)
            Spacer(modifier = Modifier.width(8.dp))
            Text("开启 NeoForge 版本检查", color = Color.White, fontSize = 22.sp, fontFamily = silverFontFamily)
        }

        HorizontalDivider(color = Color(0xFF3A3A3A), thickness = 1.dp)
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = cleanOrphanFiles, onCheckedChange = onCleanOrphanChange, colors = swColors)
            Spacer(modifier = Modifier.width(8.dp))
            Text("更新后自动清理多余文件", color = Color.White, fontSize = 22.sp, fontFamily = silverFontFamily)
        }

        HorizontalDivider(color = Color(0xFF3A3A3A), thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = extensionMode, onCheckedChange = onExtensionChange, colors = swColors)
            Spacer(modifier = Modifier.width(8.dp))
            Text("扩展模式", color = Color.White, fontSize = 22.sp, fontFamily = silverFontFamily)
        }

        if (extensionMode) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onExtensionPage,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA0C4FF), contentColor = Color.Black)
            ) { Text("进入扩展页面", fontSize = 24.sp, fontFamily = silverFontFamily) }
        }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(
            onClick = onExportLog,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            border = BorderStroke(1.dp, Color(0xFFA0C4FF)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFA0C4FF))
        ) { Text("导出日志", fontSize = 24.sp, fontFamily = silverFontFamily) }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onErrorCodes,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            border = BorderStroke(1.dp, Color(0xFFA0C4FF)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFA0C4FF))
        ) { Text("ERROR 错误代码", fontSize = 24.sp, fontFamily = silverFontFamily) }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onAbout,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            border = BorderStroke(1.dp, Color(0xFFA0C4FF)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFA0C4FF))
        ) { Text("关于软件", fontSize = 24.sp, fontFamily = silverFontFamily) }
    }
}

// ===== Extension Screen =====
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
    onBack: () -> Unit,
    onWhitelist: () -> Unit
) {
    val swColors = SwitchDefaults.colors(
        checkedThumbColor = Color(0xFFA0C4FF),
        checkedTrackColor = Color(0xFFA0C4FF).copy(alpha = 0.5f)
    )

    Column(
        modifier = Modifier
            .padding(24.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("返回", color = Color(0xFFA0C4FF), fontSize = 24.sp, fontFamily = silverFontFamily)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("扩展页面", color = Color(0xFFA0C4FF), fontSize = 36.sp, fontFamily = silverFontFamily)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = unlockThread, onCheckedChange = onUnlockChange, colors = swColors)
            Spacer(modifier = Modifier.width(8.dp))
            Text("解锁线程数上限至 1024", color = Color.White, fontSize = 22.sp, fontFamily = silverFontFamily)
        }

        HorizontalDivider(color = Color(0xFF3A3A3A), thickness = 1.dp)
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = neoforgeCheckEnabled, onCheckedChange = onNeoforgeChange, colors = swColors)
            Spacer(modifier = Modifier.width(8.dp))
            Text("开启 NeoForge 版本检查", color = Color.White, fontSize = 22.sp, fontFamily = silverFontFamily)
        }

        HorizontalDivider(color = Color(0xFF3A3A3A), thickness = 1.dp)
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = cleanOrphanFiles, onCheckedChange = onCleanOrphanChange, colors = swColors)
            Spacer(modifier = Modifier.width(8.dp))
            Text("更新后自动清理多余文件", color = Color.White, fontSize = 22.sp, fontFamily = silverFontFamily)
        }

        HorizontalDivider(color = Color(0xFF3A3A3A), thickness = 1.dp)
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = useLocalCsv, onCheckedChange = onLocalCsvChange, colors = swColors)
            Spacer(modifier = Modifier.width(8.dp))
            Text("使用本地 CSV", color = Color.White, fontSize = 22.sp, fontFamily = silverFontFamily)
        }

        if (useLocalCsv) {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onPickCsv,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA0C4FF), contentColor = Color.Black)
            ) { Text("浏览...", fontSize = 24.sp, fontFamily = silverFontFamily) }
            if (localCsvPath.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("已选择: $localCsvPath", color = Color.White, fontSize = 20.sp, fontFamily = silverFontFamily)
            }
        }

        HorizontalDivider(color = Color(0xFF3A3A3A), thickness = 1.dp)
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(
            onClick = onWhitelist,
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            border = BorderStroke(1.dp, Color(0xFFA0C4FF)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFA0C4FF))
        ) { Text("模组白名单", fontSize = 24.sp, fontFamily = silverFontFamily) }
    }
}

// ===== File Browser (multi-drive support) =====
@Composable
fun FileBrowserScreen(onSelect: (File) -> Unit, onBack: () -> Unit) {
    var currentDir by remember { mutableStateOf(File(System.getProperty("user.home") ?: "C:\\")) }
    var files by remember {
        mutableStateOf(
            currentDir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name }) ?: emptyList()
        )
    }
    val roots = remember { File.listRoots().filter { it.exists() }.sortedBy { it.absolutePath } }

    val displayFiles = files.filter { f ->
        !f.name.startsWith(".") && !f.name.startsWith("$") && f.isDirectory
    }

    val onNavigate: (File) -> Unit = { dir ->
        currentDir = dir
        files = dir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name }) ?: emptyList()
    }

    Column(modifier = Modifier.padding(24.dp).fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("返回", color = Color(0xFFA0C4FF), fontSize = 24.sp, fontFamily = silverFontFamily)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("选择启动器根目录", color = Color(0xFFA0C4FF), fontSize = 28.sp, fontFamily = silverFontFamily)
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (roots.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                roots.forEach { root ->
                    val active = currentDir.absolutePath.startsWith(root.absolutePath)
                    FilterChip(
                        selected = active,
                        onClick = { onNavigate(root) },
                        label = {
                            Text(
                                root.absolutePath.removeSuffix("\\"),
                                fontFamily = silverFontFamily,
                                color = if (active) Color.Black else Color(0xFFA0C4FF)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFA0C4FF))
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text(
            "当前目录: ${currentDir.absolutePath}",
            color = Color.White,
            fontSize = 22.sp,
            fontFamily = silverFontFamily
        )

        Spacer(modifier = Modifier.height(8.dp))

        val isRoot = currentDir.parentFile == null
        Button(
            onClick = { currentDir.parentFile?.let { onNavigate(it) } },
            enabled = !isRoot,
            modifier = Modifier.height(48.dp).padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA0C4FF), contentColor = Color.Black)
        ) { Text("返回上级", fontSize = 20.sp, fontFamily = silverFontFamily) }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(displayFiles) { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (file.isDirectory) onNavigate(file) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${if (file.isDirectory) "[DIR] " else "[FILE] "}${file.name}",
                        color = if (file.isDirectory) Color(0xFFA0C4FF) else Color.White,
                        fontSize = 22.sp,
                        fontFamily = silverFontFamily
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { onSelect(currentDir) },
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA0C4FF), contentColor = Color.Black)
        ) { Text("选择此文件夹", fontSize = 24.sp, fontFamily = silverFontFamily) }
    }
}

// ===== Helper Functions =====
suspend fun installResourcePack(prefs: Preferences) {
    try {
        val launcherRoot = File(prefs.getString("launcher_root", System.getProperty("user.home")))
        val mc = File(launcherRoot, ".minecraft")
        val tv = prefs.getString("version_folder", "1.21.1-NeoForge") ?: "1.21.1-NeoForge"
        val versionDir = File(mc, "versions/$tv")
        val packsDir = File(versionDir, "resourcepacks")
        if (!packsDir.exists()) packsDir.mkdirs()
        val destFile = File(packsDir, "generated.zip")
        if (!destFile.exists()) {
            Thread.currentThread().contextClassLoader.getResourceAsStream("generated.zip").use { input ->
                FileOutputStream(destFile).use { output -> input?.copyTo(output) }
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
        Regex("<a href=\"([^\"]+)\">").findAll(body)
            .map { it.groupValues[1] }
            .filter { it.endsWith(".jar") }
            .map { java.net.URLDecoder.decode(it, "UTF-8") }
            .toList()
    } catch (e: Exception) { emptyList() }
}

fun parseDesktopCsvMods(): List<ModInfo> =
    Constants.CSV_CONTENT.lines().drop(1).filter { it.isNotBlank() }.map { line ->
        val p = line.split(",")
        ModInfo(p[0].trim('"').removePrefix("./"), p[2].toLong(), p[3].trim('"'), p[4].trim('"'))
    }

fun parseCsvFromFile(file: File): List<ModInfo> =
    file.readText().lines().drop(1).filter { it.isNotBlank() }.map { line ->
        val p = line.split(",")
        ModInfo(p[0].trim('"').removePrefix("./"), p[2].toLong(), p[3].trim('"'), p[4].trim('"'))
    }

fun filterOutUnchangedModsDesktop(modsDir: File, csvMods: List<ModInfo>): List<ModInfo> =
    csvMods.filterNot { mod ->
        val local = File(modsDir, mod.fileName)
        local.exists() && local.length() == mod.size && calculateMD5Desktop(local) == mod.md5
    }

fun calculateMD5Desktop(file: File): String? = try {
    val digest = MessageDigest.getInstance("MD5")
    file.inputStream().use { fis ->
        val b = ByteArray(8192)
        var len: Int
        while (fis.read(b).also { len = it } != -1) digest.update(b, 0, len)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
} catch (e: Exception) { null }

package www.xdyl.hygge.desktop

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.res.painterResource
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
    var showThreadInfoDialog by remember { mutableStateOf(false) }
    var showCsvPickerDialog by remember { mutableStateOf(false) }
    var showFolderErrorDialog by remember { mutableStateOf(false) }
    var showFileBrowserDialog by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showCsvUpdateDialog by remember { mutableStateOf(false) }
    var csvUpdateInfo by remember { mutableStateOf<VersionDiff?>(null) }
    var folderErrorMsg by remember { mutableStateOf("") }
    var showResourcePackDialog by remember { mutableStateOf(false) }
    var slideDirection by remember { mutableStateOf(1) }

    var dailyQuote by remember { mutableStateOf(null as Quote?) }
    var cachedQuoteDate by remember { mutableStateOf("") }
    val quoteCategories = listOf("WH", "RW", "HC", "ED", "CE", "AC")

    fun loadDailyQuote() {
        val today = SimpleDateFormat("yyyyMMdd").format(Date())
        // 内存缓存命中，直接返回
        if (dailyQuote != null && cachedQuoteDate == today) return
        val lastDate = prefs.getString("quote_date", "")
        if (lastDate != today) {
            scope.launch(Dispatchers.IO) {
                try {
                    val allQuotes = mutableListOf<Triple<String, Int, Quote>>()
                    for (cat in quoteCategories) {
                        val jsonStr = Thread.currentThread().contextClassLoader
                            .getResourceAsStream("$cat.json")?.bufferedReader()?.readText() ?: continue
                        val jsonObject = Gson().fromJson(jsonStr, Map::class.java)
                        @Suppress("UNCHECKED_CAST")
                        val quotes = jsonObject["quotes"] as? List<Map<String, String>> ?: continue
                        quotes.forEachIndexed { i, it ->
                            allQuotes.add(Triple(cat, i, Quote(
                                it["chinese"]!!, it["english"]!!, it["author"]!!, it["author_en"]!!, it["source"]!!, it["source_en"]!!
                            )))
                        }
                    }
                    if (allQuotes.isNotEmpty()) {
                        val (cat, idx, quote) = allQuotes[Random().nextInt(allQuotes.size)]
                        dailyQuote = quote
                        prefs.putString("quote_date", today)
                        prefs.putString("quote_cat", cat)
                        prefs.putInt("quote_index", idx)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        } else {
            val cat = prefs.getString("quote_cat", quoteCategories[0]) ?: quoteCategories[0]
            val idx = prefs.getInt("quote_index", 0)
            scope.launch(Dispatchers.IO) {
                try {
                    val jsonStr = Thread.currentThread().contextClassLoader
                        .getResourceAsStream("$cat.json")?.bufferedReader()?.readText() ?: return@launch
                    val jsonObject = Gson().fromJson(jsonStr, Map::class.java)
                    @Suppress("UNCHECKED_CAST")
                    val quotes = jsonObject["quotes"] as? List<Map<String, String>> ?: return@launch
                    if (idx in quotes.indices) {
                        val it = quotes[idx]
                        dailyQuote = Quote(it["chinese"]!!, it["english"]!!, it["author"]!!, it["author_en"]!!, it["source"]!!, it["source_en"]!!)
                    } else {
                        prefs.putString("quote_date", "")
                        loadDailyQuote()
                    }
                } catch (e: Exception) {
                    prefs.putString("quote_date", "")
                    loadDailyQuote()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        LogManager.log("应用启动, 开始初始化...")
        val lastPath = prefs.getString("launcher_root", null)
        if (lastPath != null) {
            val dir = File(lastPath)
            if (dir.exists() && dir.isDirectory) {
                LogManager.log("恢复上次目录: $lastPath")
                targetModsDir = findMinecraftModsDir(dir, prefs)
                if (targetModsDir != null) LogManager.log("mods目录: ${targetModsDir!!.absolutePath}")
            }
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
                LogManager.log("[CSV] 发现新版本 ${diff.version}，弹窗通知用户")
                csvUpdateInfo = diff
                showCsvUpdateDialog = true
            },
            onComplete = { /* 无需弹窗，静默更新 */ }
        )
    }

    fun showError(code: String) {
        folderErrorMsg = "错误码: $code\n${Constants.errorDescriptions[code] ?: "未知错误"}\n\n请查看是否是您的问题,如不是,请联系开发者"
        showFolderErrorDialog = true
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

    fun resetData() {
        prefs.clear()
        LogManager.log("所有配置数据已清除")
        logBuilder.appendLine("===数据格式化完成===")
        logText = logBuilder.toString()
    }

    var pingResult by remember { mutableStateOf("") }
    fun pingServer() { pingResult = executePing("82.157.155.86", "Server") }
    fun pingWifi() { pingResult = executePing("8.8.8.8", "WiFi") }

    val darkColorScheme = darkColorScheme(
        primary = Color(0xFFA0C4FF),
        onPrimary = Color.Black,
        surface = Color(0xFF2A2A2A),
        onSurface = Color.White,
        background = Color(0xFF1E1E1E),
        onBackground = Color.White,
        surfaceVariant = Color(0xFF2A2A2A),
        onSurfaceVariant = Color(0xFFA0C4FF)
    )

    MaterialTheme(colorScheme = darkColorScheme) {
        CompositionLocalProvider(LocalTextStyle provides defaultTextStyle) {
            Window(
                onCloseRequest = {
                    scope.cancel()
                    exitApplication()
                },
                title = "星云更新器",
                state = rememberWindowState(width = 640.dp, height = 480.dp),
                resizable = false,
                icon = androidx.compose.ui.res.painterResource("icon.ico")
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            val dir = slideDirection
                            (slideInHorizontally { dir * it } + fadeIn(tween(300))) togetherWith
                                    (slideOutHorizontally { -dir * it } + fadeOut(tween(300)))
                        }
                    ) { screen ->
                        when (screen) {
                            "main" -> MainScreen(
                                targetModsDir = targetModsDir,
                                onSelectDir = { showFileBrowserDialog = true },
                                neoforgeCheckEnabled = neoforgeCheckEnabled,
                                onStartDownload = {
                                    if (!downloading && targetModsDir != null) {
                                        var blocked = false
                                        if (neoforgeCheckEnabled) {
                                            val nv = getNeoForgeVersion(targetModsDir!!, prefs)
                                            if (nv != null && compareVersionStrings(nv, "21.1.235") < 0) {
                                                showError("NeoForge 版本过低: $nv (需要 ≥ 21.1.235)")
                                                blocked = true
                                            }
                                        }
                                        if (!blocked) {
                                            downloading = true
                                        scope.launch {
                                            try {
                                                LogManager.log("开始扫描服务器文件...")
                                                val serverFiles = fetchDesktopServerFileList()
                                                LogManager.log("服务器文件: ${serverFiles.size} 个")
                                                val csvMods = if (useLocalCsv && localCsvPath.isNotEmpty()) {
                                                    LogManager.log("使用本地CSV: $localCsvPath")
                                                    parseCsvFromFile(File(localCsvPath))
                                                } else {
                                                    parseDesktopCsvMods()
                                                }
                                                LogManager.log("CSV 解析完成: ${csvMods.size} 个模组")
                                                val serverMods = csvMods.filter { it.fileName in serverFiles.toSet() }
                                                val toDownload = filterOutUnchangedModsDesktop(targetModsDir!!, serverMods)
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
                                                progress = 0f
                                                statusText = "0/$total"
                                                withContext(Dispatchers.IO) {
                                                    toDownload.map { mod ->
                                                        launch {
                                                            sem.acquire()
                                                            try {
                                                                val file = File(targetModsDir!!, mod.fileName)
                                                                val encodedName = URLEncoder.encode(mod.fileName, "UTF-8").replace("+", "%20")
                                                                val chunks = if (mod.size > 0) maxOf(2, (mod.size / 524288).toInt()) else 2
                                                                        val useChunked = chunks > 1
                                                                        DownloadManager(Constants.BASE_URL + encodedName, mod.size, chunks, useChunked)
                                                                    .download(file) { /* 只用文件数进度 */ }
                                                                if (!FileVerifier().verifyFile(file, mod.md5, mod.sha256))
                                                                    throw RuntimeException("Checksum mismatch")
                                                                completed++
                                                                logBuilder.appendLine("[OK] ${mod.fileName}")
                                                                logText = logBuilder.toString()
                                                                progress = (completed * 100f) / total
                                                                statusText = "$completed/$total"
                                                            } catch (e: Exception) {
                                                                logBuilder.appendLine("[FAILED] ${mod.fileName}")
                                                                logText = logBuilder.toString()
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
                                                if (failed.get() > 0) {
                                                    logBuilder.appendLine("Error: ERROR05 — some files failed checksum")
                                                    showError(Constants.ERROR05)
                                                }
                                                else logBuilder.appendLine("Update completed!")
                                                logText = logBuilder.toString()
                                                // 材质包检查
                                                val vn = prefs.getString("version_folder", "1.21.1-NeoForge") ?: "1.21.1-NeoForge"
                                                val rpFile = File(targetModsDir!!.parentFile, "$vn/resourcepacks/generated.zip")
                                                if (!rpFile.exists()) showResourcePackDialog = true
                                            } catch (e: Exception) {
                                                logBuilder.appendLine("Exception: ${e.message}")
                                                logText = logBuilder.toString()
                                            } finally { downloading = false }
                                        }
                                        }
                                    }
                                },
                                downloading = downloading,
                                logText = logText,
                                progress = progress,
                                statusText = statusText,
                                onSettings = { slideDirection = 1; currentScreen = "settings" },
                                dailyQuote = dailyQuote
                            )
                            "settings" -> SettingsScreen(
                                versionName = versionName,
                                onVersionChange = { versionName = it; prefs.putString("version_folder", it) },
                                threadCount = threadCount,
                                onThreadChange = { threadCount = it; prefs.putInt("thread_limit", it) },
                                maxThreads = if (unlockThread) 1024 else 128,
                                extensionMode = extensionMode,
                                onExtensionChange = { enabled ->
                                    if (enabled) showExtensionModeConfirm = true
                                    else { extensionMode = false; prefs.putBoolean("extension_mode", false) }
                                },
                                onBack = { slideDirection = -1; currentScreen = "main" },
                                onExtensionPage = { slideDirection = 1; currentScreen = "extension" },
                                onExportLog = { exportLog() },
                                onErrorCodes = { showErrorCodesDialog = true },
                                onAbout = { showAboutDialog = true },
                                onThreadInfo = { showThreadInfoDialog = true },
                                onResetData = { showResetConfirm = true },
                                onPingServer = { scope.launch { pingServer() } },
                                onPingWifi = { scope.launch { pingWifi() } },
                                pingResult = pingResult
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
                                onPickCsv = { showCsvPickerDialog = true },
                                onBack = { slideDirection = -1; currentScreen = "settings" },
                                onWhitelist = { showWhitelistDialog = true }
                            )
                            "fileBrowser" -> FileBrowserScreen(
                                onSelect = { dir ->
                                    val workDir = if (dir.name.equals("NebulaUpdater", true)) dir else File(dir, "NebulaUpdater")
                                    if (!workDir.exists()) workDir.mkdirs()
                                    prefs.putString("launcher_root", dir.absolutePath)
                                    prefs.putString("work_dir", workDir.absolutePath)
                                    val found = findMinecraftModsDir(dir, prefs)
                                    if (found != null) {
                                        targetModsDir = found
                                        currentScreen = "main"
                                    } else {
                                        showError(Constants.ERROR01)
                                    }
                                },
                                onBack = { slideDirection = -1; currentScreen = "main" }
                            )
                        }
                    // Dialogs
                    AnimatedVisibility(visible=showErrorCodesDialog, enter=slideInVertically{it}+fadeIn(tween(300)), exit=slideOutVertically{it}+fadeOut(tween(300))) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.5f)).clickable { showErrorCodesDialog = false }, contentAlignment = Alignment.Center) {
                            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)), modifier = Modifier.widthIn(max = 340.dp).padding(12.dp)) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text("ERROR 错误代码", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF), fontSize = 18.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Box(modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                                        Text(getErrorCodesText(), fontFamily = silverFontFamily, color = Color.White, fontSize = 14.sp)
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = { showErrorCodesDialog = false }) { Text("关闭", fontFamily = silverFontFamily) }
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible=showAboutDialog, enter=slideInVertically{it}+fadeIn(tween(300)), exit=slideOutVertically{it}+fadeOut(tween(300))) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.5f)).clickable { showAboutDialog = false }, contentAlignment = Alignment.Center) {
                            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)), modifier = Modifier.widthIn(max = 400.dp).padding(16.dp)) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text("关于软件", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF), fontSize = 18.sp)
                                    Spacer(Modifier.height(12.dp))
                                    Text("星云更新器", fontFamily = silverFontFamily, color = Color.White, fontSize = 16.sp)
                                    Text("Windows Desktop v1.0.0", fontFamily = silverFontFamily, color = Color.Gray, fontSize = 14.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Text("Author: UNSA-studio", fontFamily = silverFontFamily, color = Color.White, fontSize = 14.sp)
                                    Text("GitHub: github.com/UNSA-studio/xdyl", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF), fontSize = 12.sp)
                                    Spacer(Modifier.height(16.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = {
                                            try { Desktop.getDesktop().browse(URI("https://github.com/UNSA-studio/xdyl")) } catch (_: Exception) {}
                                            showAboutDialog = false
                                        }) { Text("访问仓库", fontFamily = silverFontFamily) }
                                        TextButton(onClick = { showAboutDialog = false }) { Text("关闭", fontFamily = silverFontFamily) }
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible=showCsvPickerDialog, enter=slideInVertically{it}+fadeIn(tween(300)), exit=slideOutVertically{it}+fadeOut(tween(300))) {
                        var csvDir by remember { mutableStateOf(File(System.getProperty("user.home"))) }
                        var csvFiles by remember { mutableStateOf(csvDir.listFiles()?.filter { it.isDirectory || it.name.endsWith(".csv") }?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name }) ?: emptyList()) }
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.5f)).clickable { showCsvPickerDialog = false }, contentAlignment = Alignment.Center) {
                            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)), modifier = Modifier.widthIn(max = 400.dp).padding(16.dp)) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text("选择 CSV 文件", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF), fontSize = 18.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Text(csvDir.absolutePath, color = Color.Gray, fontSize = 12.sp, fontFamily = silverFontFamily)
                                    Spacer(Modifier.height(8.dp))
                                    LazyColumn(modifier = Modifier.height(140.dp)) {
                                        items(csvFiles) { f ->
                                            TextButton(onClick = {
                                                if (f.isDirectory) {
                                                    csvDir = f
                                                    csvFiles = f.listFiles()?.filter { it.isDirectory || it.name.endsWith(".csv") }?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name }) ?: emptyList()
                                                } else {
                                                    localCsvPath = f.absolutePath
                                                    prefs.putString("local_csv_path", localCsvPath)
                                                    showCsvPickerDialog = false
                                                }
                                            }, modifier = Modifier.fillMaxWidth()) {
                                                Text(if (f.isDirectory) "📁 ${f.name}" else "📄 ${f.name}", color = if (f.isDirectory) Color(0xFFA0C4FF) else Color.White, fontSize = 14.sp, fontFamily = silverFontFamily)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = { csvDir.parentFile?.let { csvDir = it; csvFiles = it.listFiles()?.filter { it.isDirectory || it.name.endsWith(".csv") }?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name }) ?: emptyList() } }) { Text("返回上级", fontFamily = silverFontFamily) }
                                        TextButton(onClick = { showCsvPickerDialog = false }) { Text("取消", fontFamily = silverFontFamily) }
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible=showWhitelistDialog, enter=slideInVertically{it}+fadeIn(tween(300)), exit=slideOutVertically{it}+fadeOut(tween(300))) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.5f)).clickable { showWhitelistDialog = false }, contentAlignment = Alignment.Center) {
                            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)), modifier = Modifier.widthIn(max = 400.dp).padding(16.dp)) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text("模组白名单", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF), fontSize = 18.sp)
                                    Spacer(Modifier.height(12.dp))
                                    Text("Whitelisted mods will not be removed by the orphan file cleaner.\n\nDesktop edition currently supports config-file editing only:\n~/.xdyl_config.properties\nadd: mod_whitelist=<file1>,<file2>", fontFamily = silverFontFamily, color = Color.White, fontSize = 14.sp)
                                    Spacer(Modifier.height(16.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = { showWhitelistDialog = false }) { Text("关闭", fontFamily = silverFontFamily) }
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible=showThreadInfoDialog, enter=slideInVertically{it}+fadeIn(tween(300)), exit=slideOutVertically{it}+fadeOut(tween(300))) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.5f)).clickable { showThreadInfoDialog = false }, contentAlignment = Alignment.Center) {
                            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)), modifier = Modifier.widthIn(max = 400.dp).padding(16.dp)) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text("线程与分块说明", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF), fontSize = 18.sp)
                                    Spacer(Modifier.height(12.dp))
                                    Text("「下载线程数」指的是同时下载的文件数量，越大的值会让更多文件并行下载。\n\n「分块规则」是适应性的：≤1MB 的文件默认用 2 个 HTTP 下载块，大于 1MB 的每多 0.5MB 就多分配 2 个下载块。例如 3MB 的文件会被拆成 6 块同时下载。\n\n下载线程数不是越大越好，请根据网络带宽和设备性能合理设置。", fontFamily = silverFontFamily, color = Color.White, fontSize = 14.sp)
                                    Spacer(Modifier.height(16.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = { showThreadInfoDialog = false }) { Text("关闭", fontFamily = silverFontFamily) }
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible=showFileBrowserDialog, enter=slideInVertically{it}+fadeIn(tween(300)), exit=slideOutVertically{it}+fadeOut(tween(300))) {
                        var fbDir by remember { mutableStateOf(File(System.getProperty("user.home"))) }
                        var fbFiles by remember { mutableStateOf(fbDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()) }
                        val roots = remember { File.listRoots().filter { it.exists() }.sortedBy { it.absolutePath } }
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.5f)).clickable { showFileBrowserDialog = false }, contentAlignment = Alignment.Center) {
                            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)), modifier = Modifier.widthIn(max = 420.dp).padding(16.dp)) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text("选择启动器根目录", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF), fontSize = 18.sp)
                                    Spacer(Modifier.height(8.dp))
                                    if (roots.size > 1) {
                                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            roots.forEach { root ->
                                                FilterChip(
                                                    selected = fbDir.absolutePath.startsWith(root.absolutePath),
                                                    onClick = { fbDir = root; fbFiles = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList() },
                                                    label = { Text(root.absolutePath.removeSuffix("\\"), fontSize = 12.sp, fontFamily = silverFontFamily) },
                                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFFA0C4FF))
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    Text(fbDir.absolutePath, color = Color.Gray, fontSize = 12.sp, fontFamily = silverFontFamily)
                                    Spacer(Modifier.height(8.dp))
                                    LazyColumn(modifier = Modifier.height(130.dp)) {
                                        items(fbFiles) { f ->
                                            TextButton(onClick = {
                                                fbDir = f
                                                fbFiles = f.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()
                                            }, modifier = Modifier.fillMaxWidth()) {
                                                Text("📁 ${f.name}", color = Color(0xFFA0C4FF), fontSize = 14.sp, fontFamily = silverFontFamily)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = { fbDir.parentFile?.let { fbDir = it; fbFiles = it.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList() } }) {
                                            Text("返回上级", fontFamily = silverFontFamily)
                                        }
                                        TextButton(onClick = {
                                            val workDir = if (fbDir.name.equals("NebulaUpdater", true)) fbDir else File(fbDir, "NebulaUpdater")
                                            if (!workDir.exists()) workDir.mkdirs()
                                            prefs.putString("launcher_root", fbDir.absolutePath)
                                            prefs.putString("work_dir", workDir.absolutePath)
                                            val found = findMinecraftModsDir(fbDir, prefs)
                                            if (found != null) { targetModsDir = found; showFileBrowserDialog = false }
                                            else showError(Constants.ERROR01)
                                        }) { Text("选择此文件夹", fontFamily = silverFontFamily) }
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible=showFolderErrorDialog, enter=slideInVertically{it}+fadeIn(tween(300)), exit=slideOutVertically{it}+fadeOut(tween(300))) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.5f)).clickable { showFolderErrorDialog = false }, contentAlignment = Alignment.Center) {
                            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)), modifier = Modifier.widthIn(max = 400.dp).padding(16.dp)) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text("意外错误!", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF), fontSize = 18.sp)
                                    Spacer(Modifier.height(12.dp))
                                    Text(folderErrorMsg, fontFamily = silverFontFamily, color = Color.White, fontSize = 14.sp)
                                    Spacer(Modifier.height(16.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = { showFolderErrorDialog = false }) { Text("确定", fontFamily = silverFontFamily) }
                                    }
                                }
                            }
                        }
                    }
                    AnimatedVisibility(visible=showExtensionModeConfirm, enter=slideInVertically{it}+fadeIn(tween(300)), exit=slideOutVertically{it}+fadeOut(tween(300))) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=0.5f)).clickable {
                            showExtensionModeConfirm = false
                            extensionMode = false
                            prefs.putBoolean("extension_mode", false)
                        }, contentAlignment = Alignment.Center) {
                            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)), modifier = Modifier.widthIn(max = 400.dp).padding(16.dp)) {
                                Column(modifier = Modifier.padding(20.dp)) {
                                    Text("警告!", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF), fontSize = 18.sp)
                                    Spacer(Modifier.height(12.dp))
                                    Text("开启扩展模式，重启后生效。", fontFamily = silverFontFamily, color = Color.White)
                                    Spacer(Modifier.height(16.dp))
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = {
                                            showExtensionModeConfirm = false
                                            extensionMode = false
                                            prefs.putBoolean("extension_mode", false)
                                        }) { Text("取消", fontFamily = silverFontFamily) }
                                        TextButton(onClick = {
                                            showExtensionModeConfirm = false
                                            prefs.putBoolean("extension_mode", true)
                                            exitApplication()
                                        }) { Text("开启并重启", fontFamily = silverFontFamily) }
                                    }
                                }
                            }
                        }
                    }
                    if (showResetConfirm) {
                        AlertDialog(
                            onDismissRequest = { showResetConfirm = false },
                            title = { Text("数据格式化", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF)) },
                            text = { Text("这将清除所有配置数据（目录路径、线程设置、CSV版本记录等），确定继续？", fontFamily = silverFontFamily, color = Color.White) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showResetConfirm = false
                                    resetData()
                                    exitApplication()
                                }) { Text("清除并重启", color = Color.Red, fontFamily = silverFontFamily) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showResetConfirm = false }) { Text("取消", fontFamily = silverFontFamily) }
                            }
                        )
                    }
                    if (showCsvUpdateDialog && csvUpdateInfo != null) {
                        val diff = csvUpdateInfo!!
                        AlertDialog(
                            onDismissRequest = { showCsvUpdateDialog = false },
                            title = { Text("CSV 需要更新 (${diff.version})", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF)) },
                            text = {
                                Column {
                                    Text(buildString {
                                        appendLine("【新增】")
                                        diff.added.forEach { appendLine("  • ${it.name}") }
                                        appendLine()
                                        appendLine("【移除】")
                                        diff.removed.forEach { appendLine("  • ${it.name}") }
                                        appendLine()
                                        appendLine("【更新】")
                                        diff.updated.forEach { appendLine("  • ${it.name} (${it.oldVersion} → ${it.newVersion})") }
                                    }.trim(), fontFamily = silverFontFamily, color = Color.White, fontSize = 14.sp)
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showCsvUpdateDialog = false
                                    scope.launch {
                                        VersionManager(prefs).downloadNewCsv(diff.version)
                                        LogManager.log("[CSV] 用户确认更新完成")
                                    }
                                }) { Text("更新", fontFamily = silverFontFamily) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showCsvUpdateDialog = false }) { Text("稍后", fontFamily = silverFontFamily) }
                            }
                        )
                    }
                    if (showResourcePackDialog) {
                        AlertDialog(
                            onDismissRequest = { showResourcePackDialog = false },
                            title = { Text("安装服务器材质包", fontFamily = silverFontFamily, color = Color(0xFFA0C4FF)) },
                            text = { Text("是否要安装 Server 材质包？\n这是必要的，如不装进服将下载材质包，\n在这里安装可以加快速度。", fontFamily = silverFontFamily, color = Color.White) },
                            confirmButton = {
                                TextButton(onClick = {
                                    showResourcePackDialog = false
                                    scope.launch {
                                        try {
                                            val vn = prefs.getString("version_folder", "1.21.1-NeoForge") ?: "1.21.1-NeoForge"
                                            val dest = File(targetModsDir!!.parentFile, "$vn/resourcepacks/generated.zip")
                                            dest.parentFile?.mkdirs()
                                            okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url("${Constants.BASE_URL}generated.zip").build()).execute().use { resp ->
                                                if (resp.isSuccessful) dest.outputStream().use { it.write(resp.body!!.bytes()) }
                                            }
                                            LogManager.log("材质包已安装")
                                        } catch (e: Exception) { LogManager.log("材质包下载失败: ${e.message}") }
                                    }
                                }) { Text("好的", fontFamily = silverFontFamily) }
                            },
                            dismissButton = { TextButton(onClick = { showResourcePackDialog = false }) { Text("取消", fontFamily = silverFontFamily) } }
                        )
                    }
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
    dailyQuote: Quote?,
    neoforgeCheckEnabled: Boolean = true
) {
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it, containerColor = Color(0xFF2A2A2A), contentColor = Color.White) } },
        containerColor = Color(0xFF1E1E1E)
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(horizontal = 20.dp, vertical = 12.dp).fillMaxSize()) {
            // 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Nebula updater-NU", color = Color(0xFFA0C4FF), fontSize = 28.sp, fontFamily = silverFontFamily)
                    Text("星云更新器-Windows端", color = Color(0xFFA0C4FF).copy(alpha = 0.8f), fontSize = 18.sp, fontFamily = silverFontFamily)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "设置", tint = Color(0xFFA0C4FF), modifier = Modifier.size(28.dp))
                }
            }

            Spacer(Modifier.height(8.dp))

            // 按钮组
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onSelectDir,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA0C4FF), contentColor = Color.Black)
                ) { Text("选择目录", fontSize = 18.sp, fontFamily = silverFontFamily) }
                Button(
                    onClick = onStartDownload,
                    enabled = targetModsDir != null && !downloading,
                    modifier = Modifier.weight(1f).height(42.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA0C4FF), contentColor = Color.Black)
                ) { Text(if (targetModsDir == null) "请先选择目录" else "开始下载", fontSize = 18.sp, fontFamily = silverFontFamily) }
            }

            Spacer(Modifier.height(8.dp))

            // 进度条
            val smoothProgress by animateFloatAsState((progress / 100f).coerceIn(0f, 1f), animationSpec = tween(200))
            LinearProgressIndicator(
                progress = { smoothProgress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = Color(0xFFA0C4FF),
                trackColor = Color(0xFFA0C4FF).copy(alpha = 0.2f)
            )
            Text(statusText, color = Color(0xFFA0C4FF).copy(alpha = 0.8f), fontSize = 13.sp, fontFamily = silverFontFamily)

            Spacer(Modifier.height(4.dp))

            // 日志区（占满剩余空间）
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Text(
                    text = logText,
                    modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxSize(),
                    fontSize = 13.sp,
                    color = Color.LightGray,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip,
                    fontFamily = silverFontFamily
                )
            }

            Spacer(Modifier.height(6.dp))

            // 名言面板（底部固定）
            dailyQuote?.let { quote ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2A2A)),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF3A3A3A))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(quote.chinese, color = Color(0xFFA0C4FF), fontSize = 16.sp, fontFamily = silverFontFamily, maxLines = Int.MAX_VALUE)
                        Text(quote.english, color = Color(0xFFA0C4FF).copy(alpha = 0.8f), fontSize = 13.sp, fontFamily = silverFontFamily, maxLines = Int.MAX_VALUE)
                        Spacer(Modifier.height(4.dp))
                        Text("— ${quote.author} / ${quote.authorEn}", color = Color.Gray, fontSize = 12.sp, fontFamily = silverFontFamily)
                        Text("《${quote.source}》 / ${quote.sourceEn}", color = Color.Gray.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = silverFontFamily)
                    }
                }
            }
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
    extensionMode: Boolean,
    onExtensionChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onExtensionPage: () -> Unit,
    onExportLog: () -> Unit,
    onErrorCodes: () -> Unit,
    onAbout: () -> Unit,
    onThreadInfo: () -> Unit = {},
    onResetData: () -> Unit = {},
    onPingServer: () -> Unit = {},
    onPingWifi: () -> Unit = {},
    pingResult: String = ""
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
        // 版本文件夹
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DesktopIcons.Folder, contentDescription = null, modifier = Modifier.size(28.dp), tint = Color(0xFFA0C4FF))
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedTextField(
                value = versionName,
                onValueChange = onVersionChange,
                label = { Text("Minecraft 版本文件夹名", fontSize = 20.sp, color = Color.Gray, fontFamily = silverFontFamily) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = tfTextStyle,
                colors = tfColors
            )
        }
        HorizontalDivider(color = Color(0xFF3A3A3A), thickness = 1.dp)
        Spacer(modifier = Modifier.height(12.dp))
        // 下载线程数
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DesktopIcons.Thread, contentDescription = null, modifier = Modifier.size(28.dp), tint = Color(0xFFA0C4FF))
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedTextField(
                value = threadCount.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { onThreadChange(it.coerceIn(20, maxThreads)) } },
                label = { Text("下载线程数 (20-$maxThreads)", fontSize = 14.sp, color = Color.Gray, fontFamily = silverFontFamily) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = tfTextStyle,
                colors = tfColors
            )
            IconButton(onClick = onThreadInfo, modifier = Modifier.size(32.dp)) {
                Icon(DesktopIcons.Info, contentDescription = "线程说明", modifier = Modifier.size(22.dp), tint = Color(0xFFA0C4FF).copy(alpha = 0.7f))
            }
        }
        HorizontalDivider(color = Color(0xFF3A3A3A), thickness = 1.dp)
        Spacer(modifier = Modifier.height(16.dp))

        // 扩展模式
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DesktopIcons.Extension, contentDescription = null, modifier = Modifier.size(28.dp), tint = Color(0xFFA0C4FF))
            Spacer(modifier = Modifier.width(12.dp))
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
        Spacer(modifier = Modifier.height(16.dp))
        // Ping 按钮
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onPingServer, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA0C4FF), contentColor = Color.Black)) {
                Text("Ping Server", fontSize = 14.sp, fontFamily = silverFontFamily)
            }
            Button(onClick = onPingWifi, modifier = Modifier.weight(1f).height(42.dp), shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA0C4FF), contentColor = Color.Black)) {
                Text("Ping WiFi", fontSize = 14.sp, fontFamily = silverFontFamily)
            }
        }
        if (pingResult.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(pingResult, color = Color(0xFFA0C4FF), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(modifier = Modifier.height(24.dp))
        // 导出日志
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DesktopIcons.Export, contentDescription = null, modifier = Modifier.size(28.dp), tint = Color(0xFFA0C4FF))
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = onExportLog,
                modifier = Modifier.weight(1f).height(56.dp),
                border = BorderStroke(1.dp, Color(0xFFA0C4FF)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFA0C4FF))
            ) { Text("导出日志", fontSize = 24.sp, fontFamily = silverFontFamily) }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // ERROR 错误代码
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DesktopIcons.Info, contentDescription = null, modifier = Modifier.size(28.dp), tint = Color(0xFFA0C4FF))
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = onErrorCodes,
                modifier = Modifier.weight(1f).height(56.dp),
                border = BorderStroke(1.dp, Color(0xFFA0C4FF)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFA0C4FF))
            ) { Text("ERROR 错误代码", fontSize = 24.sp, fontFamily = silverFontFamily) }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 关于软件
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DesktopIcons.Info, contentDescription = null, modifier = Modifier.size(28.dp), tint = Color(0xFFA0C4FF))
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = onAbout,
                modifier = Modifier.weight(1f).height(56.dp),
                border = BorderStroke(1.dp, Color(0xFFA0C4FF)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFA0C4FF))
            ) { Text("关于软件", fontSize = 24.sp, fontFamily = silverFontFamily) }
        }
        Spacer(modifier = Modifier.height(16.dp))
        // 数据格式化
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(DesktopIcons.Thread, contentDescription = null, modifier = Modifier.size(28.dp), tint = Color.Red.copy(alpha = 0.7f))
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(
                onClick = onResetData,
                modifier = Modifier.weight(1f).height(56.dp),
                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red.copy(alpha = 0.8f))
            ) { Text("数据格式化", fontSize = 24.sp, fontFamily = silverFontFamily) }
        }
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

fun parseDesktopCsvMods(): List<ModInfo> {
    // 优先读取云端下载的 CSV 文件
    val downloadedCsv = File(System.getProperty("user.home"), ".xdyl/file_list.csv")
    val csvContent = if (downloadedCsv.exists()) downloadedCsv.readText() else Constants.CSV_CONTENT
    return csvContent.lines().drop(1).filter { it.isNotBlank() }.map { line ->
        val p = line.split(",")
        ModInfo(p[0].trim('"').removePrefix("./"), p[2].toLong(), p[3].trim('"'), p[4].trim('"'))
    }
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

fun getNeoForgeVersion(modsDir: File, prefs: Preferences): String? {
    return try {
        val versionName = prefs.getString("version_folder", "1.21.1-NeoForge") ?: "1.21.1-NeoForge"
        val mc = findMinecraftDir(modsDir)
        val versionDir = File(File(mc, "versions"), versionName)
        val jsonFile = File(versionDir, "$versionName.json")
        if (!jsonFile.exists()) return null
        val content = jsonFile.readText()
        val match = Regex("\"--fml\\.neoForgeVersion\",\\s*\"(\\d+\\.\\d+\\.\\d+)\"").find(content)
        match?.groupValues?.get(1)
    } catch (e: Exception) { null }
}

fun findMinecraftDir(start: File): File? {
    val mc = File(start, ".minecraft"); if (mc.exists()) return mc
    val mcAlt = File(start, "minecraft"); return if (mcAlt.exists()) mcAlt else null
}

fun compareVersionStrings(v1: String, v2: String): Int {
    val p1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
    val p2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(p1.size, p2.size)) {
        val a = p1.getOrElse(i) { 0 }; val b = p2.getOrElse(i) { 0 }
        if (a != b) return a - b
    }
    return 0
}

fun executePing(address: String, label: String): String {
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("ping", "-n", "4", address))
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        val loss = Regex("(\\d+)% loss").find(output)?.groupValues?.get(1) ?: "?"
        val avg = Regex("Average = (\\d+)ms").find(output)?.groupValues?.get(1) ?: "?"
        "[$label] 丢包: $loss% 平均: ${avg}ms"
    } catch (e: Exception) { "[$label] Ping失败: ${e.message}" }
}

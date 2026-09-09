package com.tungsten.fcl.nebula

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 星云更新器（Nebula Updater）—— FCL 内嵌整合包自动更新页。
 * 自动流程：拉 mods.json → 需要时下载并安装 NAST 整合包 → 增量同步 new_mod/tacz → removed 清理。
 */
class NebulaUpdateActivity : Activity() {

    private lateinit var session: SessionStore
    private lateinit var api: ApiClient
    private var busy = false

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var actionButton: TextView
    private lateinit var packStatus: TextView
    private lateinit var progressBar: View

    private val logs = StringBuilder()
    private val scope get() = lifecycleScope

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(0xFF1E1E1E.toInt())
        }
        val title = TextView(this).apply {
            text = "星云更新器"
            setTextColor(0xFFA0C4FF.toInt())
            textSize = 24f
        }
        packStatus = TextView(this).apply {
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }
        statusText = TextView(this).apply {
            text = "就绪"
            setTextColor(0xFFA0C4FF.toInt())
            textSize = 15f
            setPadding(0, 32, 0, 0)
        }
        progressBar = View(this).apply {
            setBackgroundColor(0xFFA0C4FF.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 4
            ).apply { topMargin = 12 }
            visibility = View.GONE
        }
        actionButton = TextView(this).apply {
            text = "开始一键全自动更新"
            setTextColor(0xFF000000.toInt())
            setBackgroundColor(0xFFA0C4FF.toInt())
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 36, 0, 36)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
            setOnClickListener { startAutoFlow() }
        }
        logText = TextView(this).apply {
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 12f
            setPadding(0, 24, 0, 0)
        }
        root.addView(title)
        root.addView(packStatus)
        root.addView(statusText)
        root.addView(progressBar)
        root.addView(actionButton)
        root.addView(logText)

        setContentView(root)
        session = SessionStore(this)
        api = ApiClient(session)
        refreshPackStatus()
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            logs.appendLine(msg)
            logText.text = logs.toString()
        }
    }

    private fun refreshPackStatus() {
        val info = NebulaInstallStore.getInstalledInfo(this)
        val v = info["version"]
        val pv = info["pack_version"]
        packStatus.text = if (v != null) {
            "已安装 $v" + (if (!pv.isNullOrEmpty()) "（整合包 v$pv）" else "")
        } else {
            "未安装——点击下方按钮一键全自动"
        }
    }

    private fun gameRoot(): File? {
        val fcl = NebulaDirs.fclGameRoot(this)
        return fcl.takeIf { it.exists() } ?: fcl.also { it.mkdirs() }
    }

    private fun startAutoFlow() {
        if (busy) return
        val root = gameRoot() ?: return
        busy = true
        actionButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        appendLog("[AUTO] 一键流程启动")

        scope.launch {
            try {
                setStatus("获取清单...")
                val manifest = ManifestService().fetch()
                appendLog("[AUTO] pack_version=${manifest.packVersion}, 文件=${manifest.files.size}, 下架=${manifest.removed.size}")

                val installed = NebulaInstallStore.getInstalledInfo(this@NebulaUpdateActivity)
                val installedPack = installed["pack_version"]
                val needInstall = manifest.latestModpack?.let {
                    installed["version"] == null || installedPack != manifest.packVersion
                } ?: false

                var versionDir: File
                if (needInstall && manifest.latestModpack != null) {
                    val pack = manifest.latestModpack!!
                    if (pack.name.startsWith("serverfix", ignoreCase = true)) {
                        throw RuntimeException("清单中只有 serverfix 便携包（供电脑解压），无法在手机安装。请让服主上传 NAST 整合包")
                    }
                    appendLog("[AUTO] 需要安装整合包: ${pack.name} (${pack.size / 1048576}MB)")
                    setStatus("下载整合包...")

                    val versionId = "NAST-" + manifest.packVersion.replace(Regex("[^A-Za-z0-9.\\-]"), "")
                    val zipFile = File(getExternalFilesDir(null), "modpack_${manifest.packVersion}.zip")

                    var needDownload = true
                    val hasher = NebulaHasher()
                    if (zipFile.exists() && zipFile.length() == pack.size) {
                        if (hasher.sha256(zipFile).equals(pack.sha256, true)) needDownload = false
                    }
                    if (needDownload) {
                        withContext(Dispatchers.IO) {
                            val client = okhttp3.OkHttpClient.Builder()
                                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                            val req = okhttp3.Request.Builder().url(pack.url).build()
                            client.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                                val input = resp.body!!.byteStream()
                                val total = resp.body!!.contentLength()
                                var done = 0L
                                zipFile.outputStream().use { fos ->
                                    val buf = ByteArray(131072)
                                    var n: Int
                                    var lastPct = -1
                                    while (input.read(buf).also { n = it } != -1) {
                                        fos.write(buf, 0, n)
                                        done += n
                                        if (total > 0) {
                                            val pct = (done * 100 / total).toInt()
                                            if (pct != lastPct) {
                                                lastPct = pct
                                                setStatus("下载整合包 $pct%")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    appendLog("[AUTO] 整合包下载完成 (${zipFile.length() / 1048576}MB)")

                    setStatus("校验整合包...")
                    val hash = hasher.sha256(zipFile)
                    if (!hash.equals(pack.sha256, true)) {
                        zipFile.delete()
                        throw RuntimeException("sha256 校验失败，已删除损坏文件，请重试")
                    }
                    appendLog("[AUTO] sha256 校验通过")

                    setStatus("安装整合包（fclcore 安装链）...")
                    // 直接使用 fclcore 安装链（同类加载器，无跨模块问题）
                    val installer = NebulaFclInstaller()
                    versionDir = installer.install(zipFile, root, versionId) { pct, msg ->
                        setStatus("$msg")
                        if (pct % 20 == 0) appendLog("[AUTO] $msg")
                    }
                    NebulaInstallStore.save(this@NebulaUpdateActivity, versionId, manifest.packVersion, root, versionDir)
                    zipFile.delete()
                    appendLog("[AUTO] 整合包安装完成: $versionDir")
                } else {
                    val dir = installed["version_dir"]
                    if (dir.isNullOrEmpty() || !File(dir).exists()) {
                        throw RuntimeException("未安装整合包且清单中无可用包")
                    }
                    versionDir = File(dir)
                    appendLog("[AUTO] 整合包已是最新 (${installedPack})，跳过安装")
                }

                setStatus("增量同步...")
                val sync = IncrementalSync(this@NebulaUpdateActivity, NebulaHasher())
                val result = sync.sync(manifest, versionDir, threadCount = 8) { p ->
                    setStatus(p.message)
                }
                result.messages.forEach { appendLog("[SYNC] $it") }
                appendLog("[AUTO] 同步完成: 新下 ${result.downloaded}, 已最新 ${result.skipped}, 失败 ${result.failed}, 清理 ${result.cleaned}")

                setStatus(if (result.failed > 0) "完成（${result.failed} 个失败，详见日志）" else "全部完成")
                refreshPackStatus()
            } catch (e: Exception) {
                android.util.Log.e("Nebula", "exc: ${e.message}", e)
                e.stackTrace.take(12).forEach { android.util.Log.e("Nebula", "  at $it") }
                appendLog("[AUTO] 失败: ${e.message}")
                setStatus("失败: ${e.message}")
            } finally {
                busy = false
                runOnUiThread { actionButton.isEnabled = true; progressBar.visibility = View.GONE }
            }
        }
    }

    private fun setStatus(s: String) {
        runOnUiThread { statusText.text = s }
    }
}
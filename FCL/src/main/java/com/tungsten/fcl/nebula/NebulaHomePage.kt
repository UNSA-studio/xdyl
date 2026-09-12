package com.tungsten.fcl.nebula

import android.content.Intent
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.tungsten.fcl.R
import com.tungsten.fcl.activity.NebulaMainActivity
import com.tungsten.fcl.setting.Profiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 主页（与旧版 xdyl 流程一致）：
 * 整合包状态卡 / 一键全自动更新（在主页内跑完整流程，进度与日志就地显示）/
 * 启动游戏 / 每日名言。
 */
class NebulaHomePage(
    private val activity: NebulaMainActivity,
    root: View
) {
    private val tvPackStatus: TextView = root.findViewById(R.id.tvPackStatus)
    private val btnInstallModpack: MaterialButton = root.findViewById(R.id.btnInstallModpack)
    private val btnLaunch: MaterialButton = root.findViewById(R.id.btnLaunch)
    private val btnOpenSettings: View = root.findViewById(R.id.btnOpenSettings)
    private val progressBar: LinearProgressIndicator = root.findViewById(R.id.progressBar)
    private val tvStatus: TextView = root.findViewById(R.id.tvStatus)
    private val tvLog: TextView = root.findViewById(R.id.tvLog)
    private val logScroll: ScrollView = root.findViewById(R.id.logScroll)

    private val tvQuoteTitle: TextView = root.findViewById(R.id.tvQuoteTitle)
    private val tvQuoteChinese: TextView = root.findViewById(R.id.tvQuoteChinese)
    private val tvQuoteEnglish: TextView = root.findViewById(R.id.tvQuoteEnglish)
    private val tvQuoteAuthor: TextView = root.findViewById(R.id.tvQuoteAuthor)
    private val tvQuoteAuthorEn: TextView = root.findViewById(R.id.tvQuoteAuthorEn)

    private var busy = false

    init {
        tvLog.movementMethod = ScrollingMovementMethod()
        btnInstallModpack.setOnClickListener { startAutoFlow() }
        btnLaunch.setOnClickListener {
            val version = NebulaLauncher.currentVersion()
            if (version == null) {
                Toast.makeText(activity, "没有可启动的版本，请先执行一键更新", Toast.LENGTH_SHORT).show()
            } else {
                appendLog("正在启动 $version …")
                NebulaLauncher.launch(activity, version)
            }
        }
        btnOpenSettings.setOnClickListener {
            activity.startActivity(Intent(activity, NebulaSettingsActivity::class.java))
            @Suppress("DEPRECATION")
            activity.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }

    fun onShow() {
        refreshPackStatus()
        loadQuote()
    }

    // ==================== 整合包状态 ====================

    private fun refreshPackStatus() {
        val info = NebulaInstallStore.getInstalledInfo(activity)
        val v = info["version"]
        val pv = info["pack_version"]
        tvPackStatus.text = if (v != null) {
            "已安装 $v" + (if (!pv.isNullOrEmpty()) "（整合包 v$pv）" else "")
        } else {
            "未安装——点击下方按钮一键全自动"
        }
    }

    // ==================== 一键全自动流程（主页内嵌） ====================

    private fun appendLog(msg: String) {
        activity.runOnUiThread {
            tvLog.text = tvLog.text.toString() + "\n" + msg
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setStatus(s: String) {
        activity.runOnUiThread { tvStatus.text = s }
    }

    private fun startAutoFlow() {
        if (busy) return
        busy = true
        btnInstallModpack.isEnabled = false
        progressBar.visibility = View.VISIBLE
        appendLog("[AUTO] 一键流程启动")
        val root = NebulaDirs.fclGameRoot(activity).let { fcl ->
            fcl.takeIf { it.exists() } ?: fcl.also { it.mkdirs() }
        }

        activity.lifecycleScope.launch {
            try {
                setStatus("获取清单...")
                val manifest = withContext(Dispatchers.IO) { ManifestService().fetch() }
                appendLog("[AUTO] pack_version=${manifest.packVersion}, 文件=${manifest.files.size}, 下架=${manifest.removed.size}")

                val installed = NebulaInstallStore.getInstalledInfo(activity)
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
                    val zipFile = File(activity.getExternalFilesDir(null), "modpack_${manifest.packVersion}.zip")
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
                    setStatus("安装整合包（游戏版本 → 驱动 → 模组）...")
                    val installer = NebulaFclInstaller()
                    versionDir = withContext(Dispatchers.IO) {
                        installer.install(zipFile, root, versionId) { _, msg ->
                            setStatus(msg)
                        }
                    }
                    NebulaInstallStore.save(activity, versionId, manifest.packVersion, root, versionDir)
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
                val sync = IncrementalSync(activity, NebulaHasher())
                val result = withContext(Dispatchers.IO) {
                    sync.sync(manifest, versionDir, threadCount = 8) { p ->
                        setStatus(p.message)
                    }
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
                activity.runOnUiThread {
                    btnInstallModpack.isEnabled = true
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    // ==================== 每日名言 ====================

    private fun loadQuote() {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    NebulaQuote.load(activity)
                } catch (e: Throwable) {
                    null
                }
            }
            if (result == null) {
                tvQuoteChinese.text = "名言加载失败"
                return@launch
            }
            val (category, quote) = result
            tvQuoteTitle.text = "今日名言 - " + NebulaQuote.nameOf(category)
            tvQuoteChinese.text = quote.chinese
            tvQuoteEnglish.text = quote.english
            tvQuoteAuthor.text = "- ${quote.author} / ${quote.source}"
            tvQuoteAuthorEn.text = "- ${quote.authorEn} / ${quote.sourceEn}"
        }
    }
}
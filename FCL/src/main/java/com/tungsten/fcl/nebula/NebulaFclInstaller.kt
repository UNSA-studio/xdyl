package com.tungsten.fcl.nebula

import com.tungsten.fcl.FCLApp
import com.tungsten.fcl.setting.Profiles
import com.tungsten.fclauncher.utils.FCLPath
import com.tungsten.fclcore.mod.Modpack
import com.tungsten.fclcore.mod.curse.CurseManifest
import com.tungsten.fclcore.mod.curse.CurseModpackProvider
import com.tungsten.fclcore.mod.mcbbs.McbbsModpackProvider
import com.tungsten.fclcore.util.io.Unzipper
import java.io.File

/**
 * 整合包安装（完整链路，FCL 内核标准姿势）。
 *
 * 支持两种格式自动识别：
 *  - CurseForge 格式（NAST 就是这种）：CurseInstallTask 会安装
 *      ① 游戏版本本体 ② NeoForge 驱动 ③ 解压 overrides ④ 下载 files 里全部 CurseForge 模组
 *      （无 API key 时通过 cfwidget + forgecdn 镜像下载，见 NebulaCfMirror）
 *  - MCBBS 格式：McbbsModpackLocalInstallTask 同等的游戏版本 + 加载器 + overrides 链
 *
 * 安装过程中轮询报告"模组已就位 N/M"，给日志/状态提供模组下载数据。
 */
class NebulaFclInstaller {

    fun install(
        zipFile: File,
        gameRoot: File,
        versionId: String,
        onProgress: (Int, String) -> Unit
    ): File {
        FCLPath.loadPaths(FCLApp.getAppContext())

        val profile = Profiles.getSelectedProfile()
        val repository = profile.repository
        repository.refreshVersions()

        // 1) 解析整合包（CurseForge 优先，其次 MCBBS；都不是则通用解压）
        val modpack = parseModpack(zipFile)
        if (modpack == null) {
            onProgress(30, "通用解压模式...")
            val targetDir = File(gameRoot, "versions/" + versionId)
            targetDir.mkdirs()
            var hasOverrides = false
            org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(zipFile).get().use { zf ->
                hasOverrides = zf.getEntry("overrides/") != null || zf.getEntry("overrides") != null
            }
            val unzipper = Unzipper(zipFile, targetDir).setReplaceExistentFile(true)
            if (hasOverrides) unzipper.setSubDirectory("overrides")
            unzipper.setProgressCallback { done, total, path ->
                if (total > 0) onProgress((30 + done * 55 / total).toInt().coerceAtMost(85), "解压: " + path)
            }
            unzipper.unzip()
            return targetDir
        }

        onProgress(26, "整合包安装链启动（" + modpack.name + "）...")

        // 2) 清理同名残留版本（上次安装失败 / 旧数据），确保安装链从干净状态开始
        if (repository.hasVersion(versionId)) {
            try {
                repository.clean(versionId)
                onProgress(28, "清理旧版本目录...")
            } catch (e: Exception) {
                com.tungsten.fclcore.util.Logging.LOG.warning("Nebula: clean old version failed: " + e.message)
            }
        }

        // 3) FCL 标准依赖管理（下载源 + 缓存齐全）
        val dependency = profile.getDependency()

        // 4) 统一安装入口：各 provider 自带完整安装链
        val task = modpack.getInstallTask(dependency, zipFile, versionId)

        // 期望模组总数（用于进度展示）
        val totalMods = when (val mf = modpack.manifest) {
            is CurseManifest -> mf.files.size
            else -> 0
        }

        val runDir = repository.getRunDirectory(versionId)
        val modsDir = File(runDir, "mods")
        val taczDir = File(runDir, "tacz")

        onProgress(32, "安装中：游戏版本 → 驱动 → 模组，缺失文件将在线补全...")

        // 5) 进度轮询：报告"模组已就位 N/M"（模组下载数据输出）
        var stopped = false
        val poller = Thread {
            while (!stopped) {
                try {
                    val mods = modsDir.listFiles()?.count { it.isFile && it.name.endsWith(".jar", true) } ?: 0
                    val tacz = taczDir.listFiles()?.count { it.isFile && it.name.endsWith(".zip", true) } ?: 0
                    val n = mods + tacz
                    val pct = if (totalMods > 0) {
                        (32 + n.toLong().times(50).div(totalMods.toLong())).toInt().coerceIn(32, 82)
                    } else {
                        40
                    }
                    val msg = if (totalMods > 0) {
                        "安装中…模组已就位 $n/$totalMods"
                    } else {
                        "安装中…模组已就位 $n"
                    }
                    onProgress(pct, msg)
                    Thread.sleep(1500)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Throwable) {
                    // 忽略轮询异常
                }
            }
        }
        poller.isDaemon = true
        poller.start()

        try {
            val executor = task.executor()
            val ok = executor.test()
            if (!ok) {
                throw executor.getException() ?: RuntimeException("fclcore 安装任务失败")
            }
        } finally {
            stopped = true
            poller.interrupt()
        }

        onProgress(85, "游戏版本 / 驱动 / 模组安装完成")
        return runDir
    }

    /** 解析整合包：CurseForge → MCBBS → null（通用解压兜底） */
    private fun parseModpack(zipFile: File): Modpack? {
        // CurseForge 格式
        try {
            org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(zipFile).get().use { zf ->
                return CurseModpackProvider.INSTANCE.readManifest(
                    zf, zipFile.toPath(), java.nio.charset.StandardCharsets.UTF_8
                )
            }
        } catch (e: Exception) {
            com.tungsten.fclcore.util.Logging.LOG.info("Nebula: not a CurseForge modpack: " + e.message)
        }
        // MCBBS 格式
        try {
            org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(zipFile).get().use { zf ->
                return McbbsModpackProvider.INSTANCE.readManifest(
                    zf, zipFile.toPath(), java.nio.charset.StandardCharsets.UTF_8
                )
            }
        } catch (e: Exception) {
            com.tungsten.fclcore.util.Logging.LOG.info("Nebula: not a MCBBS modpack: " + e.message)
        }
        return null
    }
}
package com.tungsten.fcl.nebula

import com.tungsten.fcl.FCLApp
import com.tungsten.fcl.setting.Profiles
import com.tungsten.fclauncher.utils.FCLPath
import com.tungsten.fclcore.mod.mcbbs.McbbsModpackLocalInstallTask
import com.tungsten.fclcore.mod.mcbbs.McbbsModpackManifest
import com.tungsten.fclcore.mod.mcbbs.McbbsModpackProvider
import com.tungsten.fclcore.util.io.Unzipper
import java.io.File

/**
 * 整合包安装（接入 FCL 标准安装链）。
 *
 * 完整安装流程（对应"安装版本 → 安装驱动 → 安装模组"）：
 *  1. GameBuilder：安装 Minecraft 游戏版本本体（下载 jar / libraries / assets）
 *  2. MinecraftInstanceTask：安装 Mod 加载器（"驱动器"，如 NeoForge）并写入实例配置
 *  3. ModpackInstallTask：解压 overrides（mods / config / 脚本等整合包内容）
 *
 * 依赖管理必须使用 FCL 标准 downloadProvider（DownloadProviders）+ FCLCacheRepository，
 * 否则游戏本体与加载器无法下载——这是之前"装了但没游戏"的根因。
 */
class NebulaFclInstaller {

    fun install(
        zipFile: File,
        gameRoot: File,
        versionId: String,
        onProgress: (Int, String) -> Unit
    ): File {
        FCLPath.loadPaths(FCLApp.getAppContext())

        // 统一使用 FCL 标准 Profile 仓库与依赖管理（含官方下载源、镜像回退与缓存）
        val profile = Profiles.getSelectedProfile()
        val repository = profile.repository
        repository.refreshVersions()

        val modpack = try {
            org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(zipFile).get().use { zf ->
                McbbsModpackProvider.INSTANCE.readManifest(
                    zf, zipFile.toPath(), java.nio.charset.StandardCharsets.UTF_8
                )
            }
        } catch (e: Exception) {
            com.tungsten.fclcore.util.Logging.LOG.warning("Nebula: manifest parse failed: " + e.message)
            null
        }

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

        onProgress(28, "fclcore 安装链启动（" + modpack.name + "）...")
        val manifest = modpack.manifest as McbbsModpackManifest

        // 清理同名残留版本（上次安装失败 / 旧数据），确保安装链从干净状态开始
        if (repository.hasVersion(versionId)) {
            try {
                repository.clean(versionId)
                onProgress(29, "清理旧版本目录...")
            } catch (e: Exception) {
                com.tungsten.fclcore.util.Logging.LOG.warning("Nebula: clean old version failed: " + e.message)
            }
        }

        // FCL 标准依赖管理：下载源 + 缓存齐全，游戏本体 / 加载器 / 库文件均可下载
        val dependency = profile.getDependency()

        val task = McbbsModpackLocalInstallTask(
            dependency, zipFile, modpack, manifest, versionId
        )
        onProgress(32, "安装三件套：游戏版本 → 驱动（NeoForge）→ 模组，缺失文件将在线补全...")
        val executor = task.executor()
        val ok = executor.test()
        if (!ok) {
            throw executor.getException() ?: RuntimeException("fclcore 安装任务失败")
        }
        onProgress(85, "游戏版本 / 驱动 / 模组安装完成")
        return repository.getRunDirectory(versionId)
    }
}
package com.tungsten.fcl.nebula

import com.tungsten.fcl.FCLApp
import com.tungsten.fclauncher.utils.FCLPath
import com.tungsten.fclcore.download.DefaultCacheRepository
import com.tungsten.fclcore.download.DefaultDependencyManager
import com.tungsten.fclcore.game.DefaultGameRepository
import com.tungsten.fclcore.mod.mcbbs.McbbsModpackProvider
import com.tungsten.fclcore.util.io.Unzipper
import java.io.File

/**
 * fclcore 安装链封装（Nebula 适配版）。
 */
class NebulaFclInstaller {

    fun install(
        zipFile: File,
        gameRoot: File,
        versionId: String,
        onProgress: (Int, String) -> Unit
    ): File {
        FCLPath.loadPaths(FCLApp.getAppContext())

        val repository = DefaultGameRepository(gameRoot)
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

        onProgress(30, "fclcore 安装链启动（" + modpack.name + "）...")
        val manifest = modpack.manifest as com.tungsten.fclcore.mod.mcbbs.McbbsModpackManifest
        val dependency = DefaultDependencyManager(repository, null, DefaultCacheRepository())
        val task = com.tungsten.fclcore.mod.mcbbs.McbbsModpackLocalInstallTask(
            dependency, zipFile, modpack, manifest, versionId
        )
        onProgress(40, "解压 + 补全库文件...")
        val executor = task.executor()
        val ok = executor.test()
        if (!ok) {
            throw executor.getException() ?: RuntimeException("fclcore 安装任务失败")
        }
        onProgress(85, "安装链完成")
        return repository.getRunDirectory(versionId)
    }
}

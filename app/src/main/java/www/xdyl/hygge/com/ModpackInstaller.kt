package www.xdyl.hygge.com

import android.content.Context
import com.tungsten.fcl.FCLApp
import com.tungsten.fclcore.download.DefaultCacheRepository
import com.tungsten.fclcore.download.DefaultDependencyManager
import com.tungsten.fclcore.game.DefaultGameRepository
import com.tungsten.fclcore.mod.Modpack
import com.tungsten.fclcore.mod.mcbbs.McbbsModpackProvider
import com.tungsten.fclcore.util.io.Unzipper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 整合包一键安装器：下载服务器整合包 zip → 校验 → fclcore 安装链解压 → 记录 tacz 枪包目录。
 *
 * fclcore 为 GPL-3.0 vendored 代码（from FCL-Team/FoldCraftLauncher），
 * 完整支持：MCBBS/中文打包规范（mcbbs.packmeta / manifest.json）、CurseForge、Modrinth、MultiMC。
 */
class ModpackInstaller(private val context: Context) {

    data class InstallResult(
        val ok: Boolean,
        val versionId: String? = null,
        val versionDir: File? = null,
        val message: String
    )

    /** 读取 mcbbs 规范 manifest（mcbbs.packmeta 或 manifest.json），失败返回 null */
    private fun readMcbbsManifest(zipFile: File, fallbackName: String): Modpack? {
        return try {
            ZipFile.builder().setFile(zipFile).get().use { zf ->
                McbbsModpackProvider.INSTANCE.readManifest(
                    zf, zipFile.toPath(), StandardCharsets.UTF_8
                )
            }
        } catch (e: Exception) {
            LogManager.log("[MODPACK] mcbbs manifest 解析失败: ${e.message}")
            null
        }
    }

    /** 提取包版本号（mcbbs manifest 的 version 字段） */
    private fun extractPackVersion(zipFile: File): String {
        return try {
            ZipFile.builder().setFile(zipFile).get().use { zf ->
                val entry: ZipArchiveEntry? = zf.getEntry("mcbbs.packmeta") ?: zf.getEntry("manifest.json")
                if (entry != null) {
                    val json = zf.getInputStream(entry).bufferedReader().readText()
                    Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: ""
                } else ""
            }
        } catch (_: Exception) { "" }
    }

    /**
     * 安装整合包。
     * @param zipFile 已下载的整合包 zip
     * @param gameRoot 游戏根目录（.minecraft 所在处）
     * @param versionId 版本目录名（默认用 zip 文件名）
     */
    suspend fun install(
        zipFile: File,
        gameRoot: File,
        versionId: String? = null,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): InstallResult = withContext(Dispatchers.IO) {
        try {
            FCLApp.init(context.applicationContext)
            com.tungsten.fclauncher.utils.FCLPath.loadPaths(context.applicationContext)

            val finalVersionId = (versionId ?: zipFile.nameWithoutExtension).take(50)
            LogManager.log("[MODPACK] 开始安装: ${zipFile.name} → $finalVersionId")
            onProgress(5, "解析整合包...")

            val modpack = readMcbbsManifest(zipFile, finalVersionId)
            if (modpack == null) {
                LogManager.log("[MODPACK] 非 MCBBS 规范 zip，走通用 overrides 解压")
                return@withContext installGeneric(zipFile, gameRoot, finalVersionId, onProgress)
            }

            val packVersion = extractPackVersion(zipFile)
            LogManager.log("[MODPACK] manifest: name=${modpack.name} version=${modpack.version}")

            onProgress(15, "准备安装环境...")
            val repository = DefaultGameRepository(gameRoot)
            if (repository.hasVersion(finalVersionId) && repository.getModpackConfiguration(finalVersionId) == null) {
                return@withContext InstallResult(
                    ok = false,
                    message = "版本 $finalVersionId 已存在但不是整合包安装，请换一个名字或先删除该版本"
                )
            }

            val cacheRepository = DefaultCacheRepository()
            val dependency = DefaultDependencyManager(repository, null, cacheRepository)

            onProgress(25, "构建安装任务...")
            val manifest = modpack.getManifest() as com.tungsten.fclcore.mod.mcbbs.McbbsModpackManifest
            val installTask = com.tungsten.fclcore.mod.mcbbs.McbbsModpackLocalInstallTask(
                dependency,
                zipFile,
                modpack,
                manifest,
                finalVersionId
            )

            onProgress(30, "安装中（解压 + 补全库文件）...")
            val executor = installTask.executor()
            val ok = executor.test() // 同步阻塞执行；返回 false 表示失败
            if (!ok) {
                val ex = executor.getException()
                throw ex ?: RuntimeException("fclcore 安装任务失败（无异常详情）")
            }

            onProgress(90, "记录安装信息...")
            val versionDir = repository.getRunDirectory(finalVersionId)
            val taczDir = File(versionDir, "tacz")
            LogManager.log("[MODPACK] 安装完成: $finalVersionId → ${versionDir.absolutePath}, tacz目录存在=${taczDir.isDirectory}")

            context.getSharedPreferences("modpack", Context.MODE_PRIVATE).edit()
                .putString("installed_version", finalVersionId)
                .putString("installed_pack_version", packVersion)
                .putLong("installed_at", System.currentTimeMillis())
                .putString("installed_game_root", gameRoot.absolutePath)
                .putString("installed_version_dir", versionDir.absolutePath)
                .apply()

            InstallResult(
                ok = true,
                versionId = finalVersionId,
                versionDir = versionDir,
                message = "整合包安装完成：$finalVersionId" + if (packVersion.isNotEmpty()) " (v$packVersion)" else ""
            )
        } catch (e: Exception) {
            LogManager.log("[MODPACK] 安装失败: ${e.javaClass.simpleName} - ${e.message}")
            InstallResult(ok = false, message = "整合包安装失败：${e.message}")
        }
    }

    /**
     * 通用解压：把 zip 的 overrides/（若有）或整个 zip 铺到版本目录。
     * 用于非 mcbbs 规范的 zip（纯目录型整合包）。
     */
    private suspend fun installGeneric(
        zipFile: File,
        gameRoot: File,
        versionId: String,
        onProgress: (Int, String) -> Unit
    ): InstallResult = withContext(Dispatchers.IO) {
        try {
            val targetDir = File(gameRoot, "versions/$versionId")
            targetDir.mkdirs()

            // 探测是否有 overrides 目录
            var hasOverrides = false
            ZipFile.builder().setFile(zipFile).get().use { zf ->
                hasOverrides = zf.getEntry("overrides/") != null || zf.getEntry("overrides") != null
            }

            onProgress(30, "解压整合包（通用模式）...")
            val unzipper = Unzipper(zipFile, targetDir)
                .setReplaceExistentFile(true)
            if (hasOverrides) unzipper.setSubDirectory("overrides")
            unzipper.setProgressCallback { done, total, path ->
                if (total > 0) {
                    val pct = 30 + (done * 55 / total).toInt()
                    onProgress(pct.coerceAtMost(85), "解压: $path")
                }
            }
            unzipper.unzip()

            onProgress(90, "记录安装信息...")
            context.getSharedPreferences("modpack", Context.MODE_PRIVATE).edit()
                .putString("installed_version", versionId)
                .putLong("installed_at", System.currentTimeMillis())
                .putString("installed_game_root", gameRoot.absolutePath)
                .putString("installed_version_dir", targetDir.absolutePath)
                .apply()

            InstallResult(ok = true, versionId = versionId, versionDir = targetDir, message = "整合包解压完成：$versionId")
        } catch (e: Exception) {
            LogManager.log("[MODPACK] 通用解压失败: ${e.message}")
            InstallResult(ok = false, message = "解压失败：${e.message}")
        }
    }

    companion object {
        /** 服务器整合包地址（运维把 nast.zip 放 mods 根目录即可） */
        const val MODPACK_FILE = "nast.zip"
        val MODPACK_URL: String get() = "${Constants.BASE_URL}$MODPACK_FILE"

        fun getInstalledInfo(context: Context): Map<String, String?> {
            val p = context.getSharedPreferences("modpack", Context.MODE_PRIVATE)
            return mapOf(
                "version" to p.getString("installed_version", null),
                "pack_version" to p.getString("installed_pack_version", null),
                "game_root" to p.getString("installed_game_root", null),
                "version_dir" to p.getString("installed_version_dir", null)
            )
        }

        fun isInstalled(context: Context): Boolean =
            getInstalledInfo(context)["version"] != null
    }
}
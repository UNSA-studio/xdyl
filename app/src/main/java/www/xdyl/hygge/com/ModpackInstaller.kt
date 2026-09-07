package www.xdyl.hygge.com

import android.content.Context
import com.tungsten.fcl.FCLApp
import com.tungsten.fclcore.download.DefaultCacheRepository
import com.tungsten.fclcore.download.DefaultDependencyManager
import com.tungsten.fclcore.game.DefaultGameRepository
import com.tungsten.fclcore.mod.mcbbs.McbbsModpackProvider
import com.tungsten.fclcore.util.io.Unzipper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 整合包安装器 + 全量自动化编排：
 *
 *   mods.json → 下载 modpack zip（sha256校验）→ fclcore 安装链解压
 *   → new_mod 增量同步（sha256对比，只下缺/变的）→ tacz 枪包放置 → removed 清理
 *
 * fclcore 为 GPL-3.0 vendored（FCL-Team/FoldCraftLauncher）。
 */
class ModpackInstaller(private val context: Context) {

    data class Progress(val percent: Int, val message: String)

    /** 已安装信息（manifest 中的 pack_version / versionId / 版本目录） */
    companion object {
        fun prefs(context: Context) = context.getSharedPreferences("modpack", Context.MODE_PRIVATE)

        fun getInstalledInfo(context: Context): Map<String, String?> {
            val p = prefs(context)
            return mapOf(
                "version" to p.getString("installed_version", null),
                "pack_version" to p.getString("installed_pack_version", null),
                "game_root" to p.getString("installed_game_root", null),
                "version_dir" to p.getString("installed_version_dir", null)
            )
        }

        fun isInstalled(context: Context): Boolean =
            getInstalledInfo(context)["version"] != null

        fun saveInstalled(context: Context, versionId: String, packVersion: String, gameRoot: File, versionDir: File) {
            prefs(context).edit()
                .putString("installed_version", versionId)
                .putString("installed_pack_version", packVersion)
                .putLong("installed_at", System.currentTimeMillis())
                .putString("installed_game_root", gameRoot.absolutePath)
                .putString("installed_version_dir", versionDir.absolutePath)
                .apply()
        }
    }

    // ==================== sha256 ====================

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(65536)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ==================== 整合包安装 ====================

    /**
     * 安装整合包 zip（CurseForge manifest 或通用 overrides）。
     * @return 版本目录（.minecraft/versions/<versionId>）
     */
    suspend fun install(
        zipFile: File,
        gameRoot: File,
        versionId: String,
        onProgress: (Progress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        FCLApp.init(context.applicationContext)
        com.tungsten.fclauncher.utils.FCLPath.loadPaths(context.applicationContext)

        val repository = DefaultGameRepository(gameRoot)
        repository.refreshVersions() // 填充 versions 映射（否则 hasVersion 内部 NPE）
        val config = repository.getModpackConfiguration(versionId)
        if (repository.hasVersion(versionId) && config == null) {
            throw Exception("版本 $versionId 已存在但不是整合包安装，请手动处理")
        }

        onProgress(Progress(20, "解析整合包..."))
        val modpack = try {
            org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(zipFile).get().use { zf ->
                McbbsModpackProvider.INSTANCE.readManifest(zf, zipFile.toPath(), java.nio.charset.StandardCharsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }

        if (modpack == null) {
            onProgress(Progress(30, "通用解压模式..."))
            installGeneric(zipFile, gameRoot, versionId, onProgress)
        } else {
            onProgress(Progress(30, "fclcore 安装链启动（${modpack.name}）..."))
            val manifest = modpack.manifest as com.tungsten.fclcore.mod.mcbbs.McbbsModpackManifest
            val dependency = DefaultDependencyManager(repository, null, DefaultCacheRepository())
            val task = com.tungsten.fclcore.mod.mcbbs.McbbsModpackLocalInstallTask(
                dependency, zipFile, modpack, manifest, versionId
            )
            onProgress(Progress(40, "解压 + 补全库文件..."))
            val executor = task.executor()
            val ok = executor.test()
            if (!ok) throw executor.getException() ?: RuntimeException("安装任务失败")
            onProgress(Progress(85, "安装链完成"))
            repository.getRunDirectory(versionId)
        }
    }

    /** 非 mcbbs 规范 zip：解压 overrides/（或整个zip）到版本目录 */
    private suspend fun installGeneric(
        zipFile: File,
        gameRoot: File,
        versionId: String,
        onProgress: (Progress) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val targetDir = File(gameRoot, "versions/$versionId")
        targetDir.mkdirs()
        var hasOverrides = false
        org.apache.commons.compress.archivers.zip.ZipFile.builder().setFile(zipFile).get().use { zf ->
            hasOverrides = zf.getEntry("overrides/") != null || zf.getEntry("overrides") != null
        }
        val unzipper = Unzipper(zipFile, targetDir).setReplaceExistentFile(true)
        if (hasOverrides) unzipper.setSubDirectory("overrides")
        unzipper.setProgressCallback { done, total, path ->
            if (total > 0) onProgress(Progress((30 + done * 50 / total).toInt().coerceAtMost(80), "解压: $path"))
        }
        unzipper.unzip()
        onProgress(Progress(85, "解压完成"))
        targetDir
    }
}
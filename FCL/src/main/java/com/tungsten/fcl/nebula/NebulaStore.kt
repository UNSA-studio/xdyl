package com.tungsten.fcl.nebula

import android.content.Context
import com.tungsten.fclauncher.utils.FCLPath
import java.io.File

object NebulaDirs {
    /**
     * 游戏根目录：与 FCL 内核真正读取的位置保持一致
     * （FCLPath.SHARED_COMMON_DIR = /sdcard/NUL/.minecraft）。
     * 整合包安装与游戏启动必须使用同一目录，否则版本列表看不到已安装的包。
     */
    fun fclGameRoot(context: Context): File = File(FCLPath.SHARED_COMMON_DIR)
}

object NebulaInstallStore {
    fun prefs(context: Context) = context.getSharedPreferences("nebula_modpack", Context.MODE_PRIVATE)

    fun getInstalledInfo(context: Context): Map<String, String?> {
        val p = prefs(context)
        return mapOf(
            "version" to p.getString("installed_version", null),
            "pack_version" to p.getString("installed_pack_version", null),
            "game_root" to p.getString("installed_game_root", null),
            "version_dir" to p.getString("installed_version_dir", null)
        )
    }

    fun save(context: Context, versionId: String, packVersion: String, gameRoot: File, versionDir: File) {
        prefs(context).edit()
            .putString("installed_version", versionId)
            .putString("installed_pack_version", packVersion)
            .putLong("installed_at", System.currentTimeMillis())
            .putString("installed_game_root", gameRoot.absolutePath)
            .putString("installed_version_dir", versionDir.absolutePath)
            .apply()
    }
}
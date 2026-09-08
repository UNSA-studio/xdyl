package com.tungsten.fcl.nebula

import android.content.Context
import java.io.File

object NebulaDirs {
    /** FCL 自己的游戏根目录（.minecraft） */
    fun fclGameRoot(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, ".minecraft")
    }
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

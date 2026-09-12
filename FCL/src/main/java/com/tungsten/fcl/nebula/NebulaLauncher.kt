package com.tungsten.fcl.nebula

import android.content.Context
import android.widget.Toast
import com.tungsten.fcl.game.LauncherHelper
import com.tungsten.fcl.setting.Accounts
import com.tungsten.fcl.setting.Profiles
import com.tungsten.fclcore.util.Logging

/**
 * 星云启动 facade —— 以我们的 UI 风格封装 FCL 内核启动链。
 * 不依赖 FCL 主界面（MainActivity / UIManager），只有 JVMActivity 作为游戏渲染容器。
 */
object NebulaLauncher {
    /** 用 FCL 内核启动指定版本。返回是否已发起启动。 */
    fun launch(context: Context, versionId: String?): Boolean {
        if (versionId.isNullOrBlank()) {
            Toast.makeText(context, "没有可启动的版本，请先执行一键更新", Toast.LENGTH_SHORT).show()
            return false
        }
        val profile = Profiles.getSelectedProfile()
        val account = Accounts.getSelectedAccount()
        if (account == null) {
            Toast.makeText(context, "请先在「我的」页创建账户", Toast.LENGTH_SHORT).show()
            return false
        }
        val repository = profile.repository
        if (!repository.isLoaded || !repository.hasVersion(versionId)) {
            Toast.makeText(context, "版本不存在，请先执行一键更新", Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            LauncherHelper(context, profile, account, versionId).launch()
            true
        } catch (e: Throwable) {
            Logging.LOG.warning("Nebula: launch failed: " + e.message)
            Toast.makeText(context, "启动失败: " + e.message, Toast.LENGTH_LONG).show()
            false
        }
    }

    /** 当前选中版本（无选中时回退到最新版本）。 */
    fun currentVersion(): String? {
        val profile = Profiles.getSelectedProfile()
        val selected = profile.selectedVersion
        if (!selected.isNullOrBlank() && profile.repository.hasVersion(selected)) return selected
        val repository = profile.repository
        return try {
            repository.displayVersions
                .filter { version -> repository.isModpack(version.id) }
                .map { version -> version.id }
                .findFirst()
                .orElse(null)
                ?: repository.displayVersions.map { version -> version.id }.findFirst().orElse(null)
        } catch (e: Throwable) {
            null
        }
    }
}

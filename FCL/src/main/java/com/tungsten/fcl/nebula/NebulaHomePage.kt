package com.tungsten.fcl.nebula

import android.content.Intent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.tungsten.fcl.R
import com.tungsten.fcl.activity.NebulaMainActivity
import com.tungsten.fcl.setting.Profiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主页：整合包状态 + 一键全自动更新 + 启动游戏。
 */
class NebulaHomePage(
    private val activity: NebulaMainActivity,
    root: View
) {
    private val tvPackStatus: TextView = root.findViewById(R.id.tvPackStatus)
    private val tvVersionInfo: TextView = root.findViewById(R.id.tvVersionInfo)
    private val tvLog: TextView = root.findViewById(R.id.tvHomeLog)
    private val btnAutoUpdate: MaterialButton = root.findViewById(R.id.btnAutoUpdate)
    private val btnLaunch: MaterialButton = root.findViewById(R.id.btnLaunch)

    init {
        btnAutoUpdate.setOnClickListener {
            activity.startActivity(Intent(activity, NebulaUpdateActivity::class.java))
        }
        btnLaunch.setOnClickListener {
            val version = NebulaLauncher.currentVersion()
            if (version == null) {
                Toast.makeText(activity, "没有可启动的版本，请先执行一键更新", Toast.LENGTH_SHORT).show()
            } else {
                tvLog.text = "正在启动 $version …"
                NebulaLauncher.launch(activity, version)
            }
        }
    }

    fun onShow() {
        refreshStatus()
    }

    private fun refreshStatus() {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val installed = NebulaInstallStore.getInstalledInfo(activity)
                val count = try {
                    Profiles.getSelectedProfile().repository.displayVersions.count().toInt()
                } catch (e: Throwable) {
                    0
                }
                Pair(installed, count)
            }
            val (installed, count) = result
            val version = installed["version"]
            if (version.isNullOrBlank()) {
                tvPackStatus.text = "未安装整合包"
                tvVersionInfo.text = "点击下方按钮开始一键全自动更新"
            } else {
                tvPackStatus.text = "已安装 $version"
                val packVersion = installed["pack_version"]
                tvVersionInfo.text = "包版本 ${packVersion ?: "未知"} · 共 $count 个版本"
            }
        }
    }
}
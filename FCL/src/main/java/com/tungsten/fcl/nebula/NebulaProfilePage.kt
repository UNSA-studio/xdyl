package com.tungsten.fcl.nebula

import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.tungsten.fcl.R
import com.tungsten.fcl.activity.NebulaMainActivity
import com.tungsten.fcl.setting.Accounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 我的页：账户管理（离线账户创建/切换）+ 关于信息。
 */
class NebulaProfilePage(
    private val activity: NebulaMainActivity,
    root: View
) {
    private val tvAccountName: TextView = root.findViewById(R.id.tvAccountName)
    private val tvAccountType: TextView = root.findViewById(R.id.tvAccountType)
    private val tvAboutVersion: TextView = root.findViewById(R.id.tvAboutVersion)
    private val btnSwitchAccount: MaterialButton = root.findViewById(R.id.btnSwitchAccount)
    private val btnCreateOffline: MaterialButton = root.findViewById(R.id.btnCreateOffline)

    init {
        tvAboutVersion.text = "版本 " + versionName()
        btnCreateOffline.setOnClickListener { showCreateOfflineDialog() }
        btnSwitchAccount.setOnClickListener { showSwitchAccountDialog() }
    }

    fun onShow() {
        refreshAccount()
    }

    private fun versionName(): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
    } catch (e: Throwable) {
        ""
    }

    private fun refreshAccount() {
        val account = try {
            Accounts.getSelectedAccount()
        } catch (e: Throwable) {
            null
        }
        if (account == null) {
            tvAccountName.text = "未登录"
            tvAccountType.text = "创建一个账户以启动游戏"
        } else {
            val name = try {
                account.profile?.name ?: "账户"
            } catch (e: Throwable) {
                "账户"
            }
            val type = try {
                val factory = Accounts.getAccountFactory(account)
                Accounts.getLocalizedLoginTypeName(activity, factory)
            } catch (e: Throwable) {
                ""
            }
            tvAccountName.text = name
            tvAccountType.text = "登录方式：$type"
        }
    }

    private fun showCreateOfflineDialog() {
        val input = EditText(activity).apply {
            hint = "玩家名（英文 / 数字 / 下划线）"
            setSingleLine()
        }
        AlertDialog.Builder(activity)
            .setTitle("新建离线账户")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(activity, "玩家名不能为空", Toast.LENGTH_SHORT).show()
                } else {
                    createOffline(name)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createOffline(name: String) {
        activity.lifecycleScope.launch {
            try {
                val account = withContext(Dispatchers.IO) {
                    Accounts.FACTORY_OFFLINE.create(name, null)
                }
                Accounts.addAccount(account)
                Accounts.setSelectedAccount(account)
                refreshAccount()
                Toast.makeText(activity, "已创建并切换到离线账户 $name", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Toast.makeText(activity, "创建失败：" + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSwitchAccountDialog() {
        val accounts = try {
            Accounts.getAccounts().toList()
        } catch (e: Throwable) {
            emptyList()
        }
        if (accounts.isEmpty()) {
            Toast.makeText(activity, "暂无账户，请先新建离线账户", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = accounts.map { acc ->
            val n = try {
                acc.profile?.name ?: "账户"
            } catch (e: Throwable) {
                "账户"
            }
            val t = try {
                Accounts.getLocalizedLoginTypeName(activity, Accounts.getAccountFactory(acc))
            } catch (e: Throwable) {
                ""
            }
            if (t.isBlank()) n else "$n（$t）"
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("切换账户")
            .setItems(labels) { _, which ->
                try {
                    Accounts.setSelectedAccount(accounts[which])
                    refreshAccount()
                } catch (e: Throwable) {
                    Toast.makeText(activity, "切换失败：" + e.message, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}